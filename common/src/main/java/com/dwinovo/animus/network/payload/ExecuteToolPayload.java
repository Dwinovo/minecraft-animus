package com.dwinovo.animus.network.payload;

import com.dwinovo.animus.Constants;
import com.dwinovo.animus.agent.tool.AnimusTool;
import com.dwinovo.animus.agent.tool.ToolRegistry;
import com.dwinovo.animus.entity.AnimusEntity;
import com.dwinovo.animus.task.TaskRecord;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;

/**
 * Client-to-server payload: "the LLM running on my client side decided to
 * call this tool — please execute it on my entity".
 *
 * <h2>Trust model</h2>
 * The server treats this as unvalidated input. Validation chain:
 * <ol>
 *   <li>Target entity must be an {@link AnimusEntity}.</li>
 *   <li>Sender must be the entity's owner (TamableAnimal.isOwnedBy).</li>
 *   <li>Sender must be within {@link #MAX_INTERACT_DISTANCE_SQR}.</li>
 *   <li>Tool name must resolve to a registered {@link AnimusTool}.</li>
 *   <li>Arguments JSON must parse and pass the tool's
 *       {@code toTaskRecord} validation.</li>
 * </ol>
 * Any failure path emits an immediate
 * {@link TaskResultPayload} with {@code success:false} back to the sender so
 * the client agent loop can keep the conversation consistent — silently
 * dropping a tool call would leave the LLM waiting forever and the chain
 * stuck.
 *
 * <h2>Wire format</h2>
 * Fixed-shape strings (Identifier for compactness on the tool name even
 * though tools live under a single namespace; this is forward-compatible for
 * multi-namespace tool registries). The arguments arrive as the raw JSON
 * string the LLM emitted; server parses with Gson and re-validates.
 */
public record ExecuteToolPayload(int entityId,
                                  String toolCallId,
                                  String toolName,
                                  String argumentsJson) implements CustomPacketPayload {

    public static final int MAX_TOOL_CALL_ID_LENGTH = 128;
    public static final int MAX_TOOL_NAME_LENGTH = 128;
    public static final int MAX_ARGUMENTS_JSON_LENGTH = 16 * 1024;
    public static final double MAX_INTERACT_DISTANCE_SQR = 32.0 * 32.0;

    public static final Type<ExecuteToolPayload> TYPE = new Type<>(
            Identifier.fromNamespaceAndPath(Constants.MOD_ID, "execute_tool"));

    public static final StreamCodec<RegistryFriendlyByteBuf, ExecuteToolPayload> STREAM_CODEC =
            StreamCodec.composite(
                    ByteBufCodecs.VAR_INT, ExecuteToolPayload::entityId,
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
                who, p.entityId(), p.toolName(), p.toolCallId(), p.argumentsJson().length());

        if (!(player.level() instanceof ServerLevel level)) {
            replyError(player, p, "no server level (logged out?)");
            return;
        }

        // -- 1. resolve entity
        Entity raw = level.getEntity(p.entityId());
        if (!(raw instanceof AnimusEntity animus)) {
            replyError(player, p, "entity not found or not an Animus");
            return;
        }
        // -- 2. owner check
        if (!animus.isOwnedBy(player)) {
            replyError(player, p, "not the owner");
            return;
        }
        // -- 3. distance check (server-authoritative, defends against teleport-via-tool)
        if (animus.distanceToSqr(player) > MAX_INTERACT_DISTANCE_SQR) {
            replyError(player, p,
                    "too far from entity (sqDist=" + (int) animus.distanceToSqr(player)
                            + " > " + (int) MAX_INTERACT_DISTANCE_SQR + ")");
            return;
        }
        // -- 4. tool lookup
        AnimusTool tool = ToolRegistry.get(p.toolName());
        if (tool == null) {
            replyError(player, p, "unknown tool: " + p.toolName());
            return;
        }
        // -- 5. parse + validate args, build TaskRecord
        JsonObject args;
        try {
            args = JsonParser.parseString(p.argumentsJson()).getAsJsonObject();
        } catch (RuntimeException ex) {
            replyError(player, p, "invalid arguments JSON: " + ex.getMessage());
            return;
        }
        TaskRecord record;
        try {
            record = tool.toTaskRecord(p.toolCallId(), args, level.getGameTime());
        } catch (RuntimeException ex) {
            replyError(player, p, "invalid arguments: " + ex.getMessage());
            return;
        }
        animus.getTaskQueue().enqueue(record);
        Constants.LOG.info("[animus-net] ✓ enqueued tool={} id={} on entity {} for {} (queue depth now={})",
                p.toolName(), p.toolCallId(), p.entityId(), who,
                animus.getTaskQueue().pendingCount());
    }

    private static void replyError(ServerPlayer player, ExecuteToolPayload p, String message) {
        Constants.LOG.warn("[animus-net] ✗ execute_tool rejected from {}: tool={} id={} reason={}",
                player.getName().getString(), p.toolName(), p.toolCallId(), message);
        String json = "{\"success\":false,\"message\":\"" + escape(message) + "\"}";
        com.dwinovo.animus.platform.Services.NETWORK.sendToPlayer(player,
                new TaskResultPayload(p.entityId(), p.toolCallId(), json));
    }

    private static String escape(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
