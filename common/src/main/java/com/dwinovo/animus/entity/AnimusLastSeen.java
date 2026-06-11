package com.dwinovo.animus.entity;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.BlockPos;
import net.minecraft.core.UUIDUtil;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Persistent "where did I last see this companion" index, world-saved on the
 * overworld's data storage (one file per save, all dimensions' pets in it).
 *
 * <p>This exists for exactly one consumer: {@code findByUuid} misses. A
 * companion in an unloaded chunk is invisible to entity lookup, so payload
 * handlers use this index to know WHERE to aim a revival chunk ticket. It
 * must survive restarts — the common case is a singleplayer owner quitting
 * while a pet works far away and expecting to chat with it after rejoining.
 *
 * <p>Updated from the entity's server tick (cheap: only writes when the chunk
 * actually changes), removed on death. Entries for pets that no longer exist
 * are harmless — revival just finds nothing and reports failure.
 */
public final class AnimusLastSeen extends SavedData {

    /** One companion's last known whereabouts. */
    public record Entry(ResourceKey<Level> dimension, BlockPos pos) {
        static final Codec<Entry> CODEC = RecordCodecBuilder.create(i -> i.group(
                ResourceKey.codec(Registries.DIMENSION).fieldOf("dimension").forGetter(Entry::dimension),
                BlockPos.CODEC.fieldOf("pos").forGetter(Entry::pos)
        ).apply(i, Entry::new));
    }

    private static final Codec<AnimusLastSeen> CODEC =
            Codec.unboundedMap(UUIDUtil.STRING_CODEC, Entry.CODEC)
                    .xmap(AnimusLastSeen::new, d -> d.entries)
                    .fieldOf("companions").codec();

    // Any SAVED_DATA fix type works: we stamp the current DataVersion on write,
    // so no vanilla fixer ever has a version gap to act on for this file.
    private static final SavedDataType<AnimusLastSeen> TYPE = new SavedDataType<>(
            Identifier.fromNamespaceAndPath("animus", "last_seen"),
            AnimusLastSeen::new, CODEC,
            net.minecraft.util.datafix.DataFixTypes.SAVED_DATA_RANDOM_SEQUENCES);

    private final Map<UUID, Entry> entries;

    private AnimusLastSeen() {
        this.entries = new HashMap<>();
    }

    private AnimusLastSeen(Map<UUID, Entry> entries) {
        this.entries = new HashMap<>(entries);
    }

    /** The index lives on the overworld so it exists regardless of pet dimension. */
    public static AnimusLastSeen get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(TYPE);
    }

    public static void update(AnimusEntity animus) {
        if (!(animus.level() instanceof ServerLevel sl)) return;
        AnimusLastSeen data = get(sl.getServer());
        Entry next = new Entry(sl.dimension(), animus.blockPosition());
        Entry prev = data.entries.put(animus.getUUID(), next);
        if (prev == null || !prev.equals(next)) data.setDirty();
    }

    public static void remove(MinecraftServer server, UUID uuid) {
        AnimusLastSeen data = get(server);
        if (data.entries.remove(uuid) != null) data.setDirty();
    }

    /** @return last known whereabouts, or null if this pet was never indexed. */
    public static Entry find(MinecraftServer server, UUID uuid) {
        return get(server).entries.get(uuid);
    }
}
