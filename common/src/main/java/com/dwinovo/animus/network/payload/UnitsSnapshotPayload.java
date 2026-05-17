package com.dwinovo.animus.network.payload;

import com.dwinovo.animus.Constants;
import com.dwinovo.animus.client.data.ClientPlayerAnimusState;
import com.dwinovo.animus.client.data.ClientUnitView;
import com.dwinovo.animus.data.PlayerAnimusData;
import com.dwinovo.animus.data.UnitConfig;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;

/**
 * Server → Client snapshot of one player's full {@link PlayerAnimusData}:
 * unit configs, active states, and storage contents. Sent on:
 * <ul>
 *   <li>Player login (initial state push).</li>
 *   <li>Any unit summon / recall / death (active flag flips).</li>
 *   <li>Any storage mutation (after entity inserts, after GUI take).</li>
 *   <li>Any unit config update (name / model_key change).</li>
 * </ul>
 *
 * <h2>Why one big snapshot vs per-event diffs</h2>
 * The data is small (~6 configs + 54 slots, a few KB at most). Snapshots
 * are simpler and self-healing — a lost packet recovers on the next
 * snapshot rather than leaving the client out of sync indefinitely.
 */
public record UnitsSnapshotPayload(List<UnitEntry> units, List<ItemStack> storage)
        implements CustomPacketPayload {

    public static final Type<UnitsSnapshotPayload> TYPE = new Type<>(
            Identifier.fromNamespaceAndPath(Constants.MOD_ID, "units_snapshot"));

    public record UnitEntry(int unitId, String name, String modelKey, boolean alive, boolean active) {}

    public static final StreamCodec<RegistryFriendlyByteBuf, UnitEntry> ENTRY_CODEC =
            StreamCodec.composite(
                    ByteBufCodecs.VAR_INT, UnitEntry::unitId,
                    ByteBufCodecs.STRING_UTF8, UnitEntry::name,
                    ByteBufCodecs.STRING_UTF8, UnitEntry::modelKey,
                    ByteBufCodecs.BOOL, UnitEntry::alive,
                    ByteBufCodecs.BOOL, UnitEntry::active,
                    UnitEntry::new);

    public static final StreamCodec<RegistryFriendlyByteBuf, UnitsSnapshotPayload> STREAM_CODEC =
            StreamCodec.composite(
                    ENTRY_CODEC.apply(ByteBufCodecs.list()), UnitsSnapshotPayload::units,
                    ItemStack.OPTIONAL_STREAM_CODEC.apply(ByteBufCodecs.list()), UnitsSnapshotPayload::storage,
                    UnitsSnapshotPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() { return TYPE; }

    /** Build a snapshot from server-side state and send to the player. */
    public static void sendTo(ServerPlayer player) {
        PlayerAnimusData data = PlayerAnimusData.of(player);
        List<UnitEntry> entries = new ArrayList<>(PlayerAnimusData.SLOT_COUNT);
        for (UnitConfig cfg : data.units()) {
            entries.add(new UnitEntry(cfg.unitId,
                    cfg.name() == null ? "" : cfg.name(),
                    cfg.modelKey(),
                    cfg.alive(),
                    data.isActive(cfg.unitId)));
        }
        List<ItemStack> stacks = new ArrayList<>(data.storage().getContainerSize());
        for (int i = 0; i < data.storage().getContainerSize(); i++) {
            stacks.add(data.storage().getItem(i).copy());
        }
        com.dwinovo.animus.platform.Services.NETWORK.sendToPlayer(player,
                new UnitsSnapshotPayload(entries, stacks));
        Constants.LOG.debug("[animus-net] snapshot → {} units={} storage_slots={}",
                player.getName().getString(), entries.size(), stacks.size());
    }

    /** Client-side handler — overwrite the mirror. */
    public static void handle(UnitsSnapshotPayload p) {
        ClientPlayerAnimusState state = ClientPlayerAnimusState.instance();
        for (UnitEntry e : p.units()) {
            String name = e.name().isEmpty() ? null : e.name();
            state.setUnit(e.unitId(),
                    new ClientUnitView(e.unitId(), name, e.modelKey(), e.alive(), e.active()));
        }
        state.setStorageAll(p.storage().toArray(new ItemStack[0]));
    }
}
