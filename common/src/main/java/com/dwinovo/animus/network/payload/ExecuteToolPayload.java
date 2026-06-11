package com.dwinovo.animus.network.payload;

import com.dwinovo.animus.Constants;
import com.dwinovo.animus.agent.tool.AnimusTool;
import com.dwinovo.animus.agent.tool.ToolRegistry;
import com.dwinovo.animus.entity.AnimusEntity;
import com.dwinovo.animus.task.TaskRecord;
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
 *   <li>Target entity must be an {@link AnimusEntity} (searched across ALL
 *       dimensions — a working companion may be in the Nether while the owner
 *       waits in the overworld).</li>
 *   <li>Sender must be the entity's owner (TamableAnimal.isOwnedBy).</li>
 *   <li>Tool name must resolve to a registered {@link AnimusTool}.</li>
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
 * Read-only perception tools ({@link AnimusTool#isQuery()}) never touch the
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
        Constants.LOG.debug("[animus-net] ← execute_tool from {} entity={} tool={} id={} args_chars={}",
                who, p.entityUuid(), p.toolName(), p.toolCallId(), p.argumentsJson().length());

        // -- 1. resolve entity by UUID across all dimensions: the companion may
        //       be working in another dimension or far outside the owner's view,
        //       kept ticking by its own chunk tickets.
        AnimusEntity animus = AnimusEntity.findByUuid(player.level().getServer(), p.entityUuid());
        if (animus == null) {
            // Likely sitting in unloaded chunks (idle past the ticket linger, or
            // a server restart). Try a chunk-ticket revival at its last known
            // position; if one starts, the retry loop owns the reply.
            if (com.dwinovo.animus.network.AnimusRevival.tryRevive(
                    player.level().getServer(), player, p)) {
                return;
            }
            replyError(player, p, "entity not found in any dimension (never seen or dead?)");
            return;
        }
        // -- 2. owner check (the actual authorization). UUID comparison, NOT
        //       vanilla isOwnedBy — that resolves through the PET's level and
        //       rejects a cross-dimension owner as "not the owner".
        if (!animus.isOwnedByPlayer(player.getUUID())) {
            replyError(player, p, "not the owner");
            return;
        }
        // Any owner-driven tool call counts as engagement: it keeps the pet's
        // self-loading chunk ticket alive through LLM think-time gaps.
        animus.markEngagement();
        // -- 3. tool lookup
        AnimusTool tool = ToolRegistry.get(p.toolName());
        if (tool == null) {
            replyError(player, p, "unknown tool: " + p.toolName());
            return;
        }
        // -- 4. parse args
        JsonObject args;
        try {
            args = JsonParser.parseString(p.argumentsJson()).getAsJsonObject();
        } catch (RuntimeException ex) {
            replyError(player, p, "invalid arguments JSON: " + ex.getMessage());
            return;
        }

        // -- 5a. query fast path: execute now, reply now, never queue.
        if (tool.isQuery()) {
            String result;
            try {
                result = tool.executeQuery(args, animus);
            } catch (RuntimeException ex) {
                result = "{\"success\":false,\"message\":\"" + escape(ex.getMessage()) + "\"}";
            }
            com.dwinovo.animus.platform.Services.NETWORK.sendToPlayer(player,
                    new TaskResultPayload(p.entityUuid(), p.toolCallId(), result));
            Constants.LOG.debug("[animus-net] ✓ query tool={} id={} answered inline for {}",
                    p.toolName(), p.toolCallId(), who);
            return;
        }

        // -- 5b. world-action path: validate into a TaskRecord and enqueue.
        TaskRecord record;
        try {
            record = tool.toTaskRecord(p.toolCallId(), args,
                    animus.level().getGameTime());
        } catch (RuntimeException ex) {
            replyError(player, p, "invalid arguments: " + ex.getMessage());
            return;
        }
        animus.getTaskQueue().enqueue(record);
        Constants.LOG.info("[animus-net] ✓ enqueued tool={} id={} on entity {} for {} (queue depth now={})",
                p.toolName(), p.toolCallId(), p.entityUuid(), who,
                animus.getTaskQueue().pendingCount());
    }

    public static void replyError(ServerPlayer player, ExecuteToolPayload p, String message) {
        Constants.LOG.warn("[animus-net] ✗ execute_tool rejected from {}: tool={} id={} reason={}",
                player.getName().getString(), p.toolName(), p.toolCallId(), message);
        String json = "{\"success\":false,\"message\":\"" + escape(message) + "\"}";
        com.dwinovo.animus.platform.Services.NETWORK.sendToPlayer(player,
                new TaskResultPayload(p.entityUuid(), p.toolCallId(), json));
    }

    private static String escape(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
