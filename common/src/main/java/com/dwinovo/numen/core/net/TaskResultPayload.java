package com.dwinovo.numen.core.net;

import com.dwinovo.numen.Constants;
import com.dwinovo.numen.core.tool.CoreServerTools;
import net.minecraft.core.UUIDUtil;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import java.util.UUID;

/**
 * Server-to-client payload: result of a previously requested tool execution.
 * Server drains task outboxes each tick and ships completed results back to
 * the owning player; the player's {@code EntityAgentLoop} feeds them into
 * the LLM conversation as {@code role:tool} messages, then triggers the
 * next turn when all pending results are in.
 *
 * <h2>Pairing</h2>
 * {@link #toolCallId} matches the one in the originating {@link ExecuteToolPayload}
 * and, transitively, the LLM's tool_call.id — this is the field that
 * threads request→execution→reply through the network boundary.
 *
 * <h2>Result body</h2>
 * Pre-serialised JSON string ({@link com.dwinovo.numen.task.TaskResult#toJson}).
 * Server-side decisions about field shape live in {@code TaskResult}; the
 * network layer just shuttles bytes.
 */
public record TaskResultPayload(UUID entityUuid,
                                 String toolCallId,
                                 String resultJson) implements CustomPacketPayload {

    public static final int MAX_TOOL_CALL_ID_LENGTH = 128;
    public static final int MAX_RESULT_JSON_LENGTH = 16 * 1024;

    public static final Type<TaskResultPayload> TYPE = new Type<>(
            new ResourceLocation(Constants.MOD_ID, "task_result"));

    public static final StreamCodec<RegistryFriendlyByteBuf, TaskResultPayload> STREAM_CODEC =
            StreamCodec.composite(
                    UUIDUtil.STREAM_CODEC, TaskResultPayload::entityUuid,
                    ByteBufCodecs.stringUtf8(MAX_TOOL_CALL_ID_LENGTH), TaskResultPayload::toolCallId,
                    ByteBufCodecs.stringUtf8(MAX_RESULT_JSON_LENGTH), TaskResultPayload::resultJson,
                    TaskResultPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    /** Client-side handler. Runs on client main thread (network layer arranges that). */
    public static void handle(TaskResultPayload p) {
        Constants.LOG.debug("[numen-net] task_result entity={} tool_call_id={} → {}",
                p.entityUuid(), p.toolCallId(), truncate(p.resultJson(), 200));
        CoreServerTools.deliver(p.toolCallId(), p.resultJson());
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + "...";
    }
}
