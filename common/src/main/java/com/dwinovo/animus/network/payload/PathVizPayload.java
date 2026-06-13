package com.dwinovo.animus.network.payload;

import com.dwinovo.animus.Constants;
import com.dwinovo.animus.client.path.ClientPathViz;
import net.minecraft.core.BlockPos;
import net.minecraft.core.UUIDUtil;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Server → Client: the companion's current pathfinding plan, for the in-world
 * path overlay (Baritone's {@code PathRenderer}, ported to our server-authored
 * model). Baritone renders client-side from its own {@code PathingBehavior};
 * our path lives on the server, so the body pushes it to the owner whenever it
 * (re)plans a segment, and pushes an EMPTY one (all lists empty, no goal) to
 * clear the overlay when the path ends.
 *
 * <ul>
 *   <li>{@code nodes} — the path positions (feet cells); drawn as a red poly-line.</li>
 *   <li>{@code toBreak} — blocks the path will dig; drawn as red boxes.</li>
 *   <li>{@code toPlace} — scaffold blocks the path will place; drawn as green boxes.</li>
 *   <li>{@code goal} — the goal cell; drawn as a green box (absent while clearing).</li>
 * </ul>
 */
public record PathVizPayload(UUID companion,
                             Identifier dimension,
                             List<BlockPos> nodes,
                             List<BlockPos> toBreak,
                             List<BlockPos> toPlace,
                             Optional<BlockPos> goal) implements CustomPacketPayload {

    /** Cap per list — paths are trimmed well below this; defends against absurd input. */
    public static final int MAX = 512;

    public static final Type<PathVizPayload> TYPE = new Type<>(
            Identifier.fromNamespaceAndPath(Constants.MOD_ID, "path_viz"));

    public static final StreamCodec<RegistryFriendlyByteBuf, PathVizPayload> STREAM_CODEC =
            StreamCodec.composite(
                    UUIDUtil.STREAM_CODEC, PathVizPayload::companion,
                    Identifier.STREAM_CODEC, PathVizPayload::dimension,
                    BlockPos.STREAM_CODEC.apply(ByteBufCodecs.list(MAX)), PathVizPayload::nodes,
                    BlockPos.STREAM_CODEC.apply(ByteBufCodecs.list(MAX)), PathVizPayload::toBreak,
                    BlockPos.STREAM_CODEC.apply(ByteBufCodecs.list(MAX)), PathVizPayload::toPlace,
                    ByteBufCodecs.optional(BlockPos.STREAM_CODEC), PathVizPayload::goal,
                    PathVizPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    /** Client-side handler. Runs on the client main thread (network layer arranges that). */
    public static void handle(PathVizPayload p) {
        ClientPathViz.accept(p);
    }
}
