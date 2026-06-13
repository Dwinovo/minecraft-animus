package com.dwinovo.animus.network.payload;

import com.dwinovo.animus.Constants;
import com.dwinovo.animus.client.agent.AnimusRoster;
import net.minecraft.core.UUIDUtil;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

import java.util.List;
import java.util.UUID;

/**
 * Server → Client: the roster of companions this player owns (UUID + name).
 * The companion body is a fake {@code ServerPlayer}, so the client can't be
 * "enrolled" by right-clicking a Mob any more — the server is the authority on
 * which companions exist. Pushed on owner login (after their dormant companions
 * respawn) and right after a fresh summon, so the client's {@link AnimusRoster}
 * panel always reflects the truth without the player having to seek each body
 * out physically.
 */
public record CompanionListPayload(List<Entry> companions) implements CustomPacketPayload {

    /** Cap defends against absurd input; nobody owns hundreds of companions. */
    public static final int MAX = 64;

    /** One companion's roster line. */
    public record Entry(UUID uuid, String name) {
        static final StreamCodec<RegistryFriendlyByteBuf, Entry> CODEC =
                StreamCodec.composite(
                        UUIDUtil.STREAM_CODEC, Entry::uuid,
                        ByteBufCodecs.stringUtf8(256), Entry::name,
                        Entry::new);
    }

    public static final Type<CompanionListPayload> TYPE = new Type<>(
            Identifier.fromNamespaceAndPath(Constants.MOD_ID, "companion_list"));

    public static final StreamCodec<RegistryFriendlyByteBuf, CompanionListPayload> STREAM_CODEC =
            StreamCodec.composite(
                    Entry.CODEC.apply(ByteBufCodecs.list(MAX)),
                    CompanionListPayload::companions,
                    CompanionListPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    /** Client-side handler. Runs on the client main thread (network layer arranges that). */
    public static void handle(CompanionListPayload p) {
        java.util.List<AnimusRoster.Entry> snapshot = new java.util.ArrayList<>();
        for (Entry e : p.companions()) {
            snapshot.add(new AnimusRoster.Entry(e.uuid(), e.name()));
        }
        AnimusRoster.instance().replaceAll(snapshot);
    }
}
