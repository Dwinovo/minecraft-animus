package com.dwinovo.animus.network.payload;

import com.dwinovo.animus.Constants;
import com.dwinovo.animus.client.data.ClientAnimusLocations;
import net.minecraft.core.UUIDUtil;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

import java.util.List;
import java.util.UUID;

/**
 * Server → Client: answer to {@link LocateAnimusPayload}. One snapshot per
 * requested UUID — position, dimension and HP for owned + loaded companions,
 * {@code found=false} otherwise. The client drops them into
 * {@link ClientAnimusLocations} for the roster panel / vitals strip to read.
 */
public record AnimusLocationsPayload(List<Snapshot> snapshots) implements CustomPacketPayload {

    /** Wire shape of one located (or not) companion. */
    public record Snapshot(UUID uuid, boolean found, double x, double y, double z,
                           String dimension, float hp, float maxHp) {

        public static Snapshot notFound(UUID uuid) {
            return new Snapshot(uuid, false, 0, 0, 0, "", 0, 0);
        }

        static final StreamCodec<RegistryFriendlyByteBuf, Snapshot> CODEC =
                StreamCodec.composite(
                        UUIDUtil.STREAM_CODEC, Snapshot::uuid,
                        ByteBufCodecs.BOOL, Snapshot::found,
                        ByteBufCodecs.DOUBLE, Snapshot::x,
                        ByteBufCodecs.DOUBLE, Snapshot::y,
                        ByteBufCodecs.DOUBLE, Snapshot::z,
                        ByteBufCodecs.stringUtf8(256), Snapshot::dimension,
                        ByteBufCodecs.FLOAT, Snapshot::hp,
                        ByteBufCodecs.FLOAT, Snapshot::maxHp,
                        Snapshot::new);
    }

    public static final Type<AnimusLocationsPayload> TYPE = new Type<>(
            Identifier.fromNamespaceAndPath(Constants.MOD_ID, "animus_locations"));

    public static final StreamCodec<RegistryFriendlyByteBuf, AnimusLocationsPayload> STREAM_CODEC =
            StreamCodec.composite(
                    Snapshot.CODEC.apply(ByteBufCodecs.list(LocateAnimusPayload.MAX_UUIDS)),
                    AnimusLocationsPayload::snapshots,
                    AnimusLocationsPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    /** Client-side handler. Runs on the client main thread (network layer arranges that). */
    public static void handle(AnimusLocationsPayload p) {
        long now = System.currentTimeMillis();
        for (Snapshot s : p.snapshots()) {
            ClientAnimusLocations.update(s.uuid(), new ClientAnimusLocations.Snapshot(
                    s.found(), s.x(), s.y(), s.z(), s.dimension(), s.hp(), s.maxHp(), now));
        }
    }
}
