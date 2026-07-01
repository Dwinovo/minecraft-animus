package com.dwinovo.numen.core.net;

import com.dwinovo.numen.Constants;
import com.dwinovo.numen.agent.tool.NumenTool;
import com.dwinovo.numen.agent.tool.ToolRegistry;
import com.dwinovo.numen.task.TaskResult;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.core.UUIDUtil;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;

import java.util.UUID;

/**
 * Client-to-server payload: "the LLM running on my client side decided to
 * call this tool — please execute it on my entity".
 *
 * <h2>Trust model</h2>
 * The server treats this as unvalidated input. Validation chain:
 * <ol>
 *   <li>Target must be an {@link com.dwinovo.numen.entity.NumenPlayer} (searched across ALL
 *       dimensions — a working companion may be in the Nether while the owner
 *       waits in the overworld).</li>
 *   <li>Sender must be the entity's owner (UUID comparison, cross-dimension safe).</li>
 *   <li>Tool name must resolve to a registered {@link NumenTool}.</li>
 *   <li>Arguments JSON must parse and pass the tool's
 *       {@code toTaskRecord} validation.</li>
 * </ol>
 * There is deliberately NO owner-distance check: the whole point of the
 * chunk-ticket system is that the companion keeps working far away, and the
 * tools act at the <em>entity's</em> location with the entity's own abilities
 * — owner distance grants nothing exploitable. (Ownership is the auth.)
 *
 * <p>Any failure path emits an immediate
 * {@link TaskResultPayload} with {@code success:false} back to the sender so
 * the client agent loop can keep the conversation consistent — silently
 * dropping a tool call would leave the LLM waiting forever and the chain
 * stuck.
 *
 * <h2>Query fast path</h2>
 * Read-only perception tools never touch the
 * {@code TaskQueue} — they execute synchronously right here on the tick
 * thread and the result ships back in the same tick. Queueing them behind a
 * running {@code move_to} would turn "what's my HP" into a minute-long wait.
 *
 * <h2>Wire format</h2>
 * Fixed-shape strings (Identifier for compactness on the tool name even
 * though tools live under a single namespace; this is forward-compatible for
 * multi-namespace tool registries). The arguments arrive as the raw JSON
 * string the LLM emitted; server parses with Gson and re-validates.
 */
public record ExecuteToolPayload(UUID entityUuid,
                                  String toolCallId,
                                  String toolName,
                                  String argumentsJson) implements CustomPacketPayload {

    public static final int MAX_TOOL_CALL_ID_LENGTH = 128;
    public static final int MAX_TOOL_NAME_LENGTH = 128;
    public static final int MAX_ARGUMENTS_JSON_LENGTH = 16 * 1024;

    public static final Type<ExecuteToolPayload> TYPE = new Type<>(
            Identifier.fromNamespaceAndPath(Constants.MOD_ID, "execute_tool"));

    public static final StreamCodec<RegistryFriendlyByteBuf, ExecuteToolPayload> STREAM_CODEC =
            StreamCodec.composite(
                    UUIDUtil.STREAM_CODEC, ExecuteToolPayload::entityUuid,
                    ByteBufCodecs.stringUtf8(MAX_TOOL_CALL_ID_LENGTH), ExecuteToolPayload::toolCallId,
                    ByteBufCodecs.stringUtf8(MAX_TOOL_NAME_LENGTH), ExecuteToolPayload::toolName,
                    ByteBufCodecs.stringUtf8(MAX_ARGUMENTS_JSON_LENGTH), ExecuteToolPayload::argumentsJson,
                    ExecuteToolPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    /** Handler invoked on the server main thread. */
    public static void handle(ExecuteToolPayload p, ServerPlayer player) {
        String who = player.getName().getString();
        Constants.LOG.debug("[numen-net] ← execute_tool from {} entity={} tool={} id={} args_chars={}",
                who, p.entityUuid(), p.toolName(), p.toolCallId(), p.argumentsJson().length());

        // -- 0. companion player body (the new architecture). Resolve it (or
        //       respawn it from the registry on a cold start) and run the tool
        //       through the CompanionTickDispatcher instead of the Mob GoalSelector.
        var server = player.level().getServer();
        com.dwinovo.numen.entity.NumenPlayer companion =
                com.dwinovo.numen.entity.NumenPlayer.findByUuid(server, p.entityUuid());
        if (companion == null) {
            companion = com.dwinovo.numen.entity.Companions.respawn(server, p.entityUuid());
        }
        if (companion != null) {
            handleCompanion(p, player, companion);
            return;
        }
        replyError(player, p, "companion not found (never summoned, or its data is gone)");
    }

    /**
     * Run a tool against the companion player body: owner-check, then enqueue
     * world-action tools onto its queue for the {@code CompanionTickDispatcher}.
     * Query/perception tools aren't ported to the player body yet (Phase 0 wires
     * move_to + auto_mine), so they reply with a clear not-yet message.
     */
    private static void handleCompanion(ExecuteToolPayload p, ServerPlayer player,
                                        com.dwinovo.numen.entity.NumenPlayer companion) {
        if (!companion.isOwnedByPlayer(player.getUUID())) {
            replyError(player, p, "not the owner");
            return;
        }
        NumenTool tool = ToolRegistry.get(p.toolName());
        if (tool == null) {
            replyError(player, p, "unknown tool: " + p.toolName());
            return;
        }
        JsonObject args;
        try {
            args = JsonParser.parseString(p.argumentsJson()).getAsJsonObject();
        } catch (RuntimeException ex) {
            replyError(player, p, "invalid arguments JSON: " + ex.getMessage());
            return;
        }
        // Run the tool against the live companion: a query replies now, a world action
        // enqueues and its result returns via the task lifecycle. Server execution isn't
        // part of the MC-free NumenTool contract, so dispatch via core's ServerNumenTool base.
        java.util.function.Consumer<String> reply = json ->
                com.dwinovo.numen.platform.Services.NETWORK.sendToPlayer(player,
                        new TaskResultPayload(p.entityUuid(), p.toolCallId(), json));
        try {
            if (tool instanceof com.dwinovo.numen.core.tool.ServerNumenTool st) {
                st.runOnServer(p.toolCallId(), args, companion, reply);
            } else {
                replyError(player, p, "tool not server-runnable: " + p.toolName());
            }
        } catch (RuntimeException ex) {
            replyError(player, p, "invalid arguments: " + ex.getMessage());
        }
    }

    public static void replyError(ServerPlayer player, ExecuteToolPayload p, String message) {
        Constants.LOG.warn("[numen-net] ✗ execute_tool rejected from {}: tool={} id={} reason={}",
                player.getName().getString(), p.toolName(), p.toolCallId(), message);
        String json = TaskResult.fail(message).toJson();
        com.dwinovo.numen.platform.Services.NETWORK.sendToPlayer(player,
                new TaskResultPayload(p.entityUuid(), p.toolCallId(), json));
    }
}
