package com.dwinovo.tulpa.network.payload;

import com.dwinovo.tulpa.Constants;
import com.dwinovo.tulpa.entity.Companions;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

/**
 * Client → Server: the owner asked to summon a companion by name from the panel's
 * "+" button. Mirrors the {@code /tulpa player summon} command — summon is
 * idempotent per (owner, name), so re-summoning an existing name just wakes it.
 */
public record SummonRequestPayload(String name) implements CustomPacketPayload {

    public static final int MAX_NAME = 32;

    public static final Type<SummonRequestPayload> TYPE = new Type<>(
            Identifier.fromNamespaceAndPath(Constants.MOD_ID, "summon_request"));

    public static final StreamCodec<RegistryFriendlyByteBuf, SummonRequestPayload> STREAM_CODEC =
            StreamCodec.composite(ByteBufCodecs.stringUtf8(MAX_NAME), SummonRequestPayload::name,
                    SummonRequestPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    /** Server main thread. */
    public static void handle(SummonRequestPayload p, ServerPlayer owner) {
        String name = p.name() == null ? "" : p.name().trim();
        if (name.isEmpty() || name.length() > MAX_NAME) return;
        ServerLevel level = (ServerLevel) owner.level();
        Companions.summon(level.getServer(), owner.getUUID(), name, level, owner.position());
        Companions.syncRosterToOwner(level.getServer(), owner);   // push the new roster to the owner
    }
}
