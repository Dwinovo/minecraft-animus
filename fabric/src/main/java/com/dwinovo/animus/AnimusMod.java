package com.dwinovo.animus;

import com.dwinovo.animus.entity.AnimusDimensionFollow;
import com.dwinovo.animus.entity.AnimusEntity;
import com.dwinovo.animus.entity.InitEntity;
import com.dwinovo.animus.init.InitItem;
import com.dwinovo.animus.network.AnimusNetwork;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.object.builder.v1.entity.FabricDefaultAttributeRegistry;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.SpawnEggItem;
import net.minecraft.world.level.Level;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public class AnimusMod implements ModInitializer {

    /**
     * Last-known dimension per online player. Fabric API 5.x removed
     * {@code ServerEntityWorldChangeEvents}, so we detect a dimension change by
     * polling each tick (a handful of players, one key compare each) and fire
     * the companion-follow when a player's dimension key changes.
     */
    private static final Map<UUID, ResourceKey<Level>> LAST_DIM = new HashMap<>();

    @Override
    public void onInitialize() {
        EntityType<AnimusEntity> animusType = Registry.register(
                BuiltInRegistries.ENTITY_TYPE,
                InitEntity.ANIMUS_KEY,
                EntityType.Builder.<AnimusEntity>of(AnimusEntity::new, MobCategory.CREATURE)
                        .sized(0.6f, 0.9f)
                        .build(InitEntity.ANIMUS_KEY));
        InitEntity.ANIMUS = () -> animusType;

        FabricDefaultAttributeRegistry.register(animusType,
                AnimusEntity.createAttributes().build());

        // Spawn egg item — how players obtain a (wild) Animus to tame.
        // Creative-tab placement on Fabric is wired separately (the
        // fabric-item-group module isn't on this project's compile classpath);
        // obtainable via /give animus:animus_spawn_egg in the meantime.
        Item spawnEgg = Registry.register(
                BuiltInRegistries.ITEM,
                InitItem.ANIMUS_SPAWN_EGG_KEY,
                new SpawnEggItem(new Item.Properties()
                        .spawnEgg(animusType)
                        .setId(InitItem.ANIMUS_SPAWN_EGG_KEY)));
        InitItem.ANIMUS_SPAWN_EGG = () -> spawnEgg;

        AnimusNetwork.register();

        // Bring owned companions along when the owner crosses a dimension.
        ServerTickEvents.END_SERVER_TICK.register(AnimusMod::detectDimensionChanges);
        // Poll chunk-ticket revivals of companions stranded in unloaded chunks.
        ServerTickEvents.END_SERVER_TICK.register(
                com.dwinovo.animus.network.AnimusRevival::tick);

        CommonClass.init();
        Constants.LOG.info("Animus mod initialised on Fabric.");
    }

    private static void detectDimensionChanges(net.minecraft.server.MinecraftServer server) {
        LAST_DIM.keySet().retainAll(
                server.getPlayerList().getPlayers().stream().map(ServerPlayer::getUUID).toList());
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            ResourceKey<Level> current = player.level().dimension();
            ResourceKey<Level> previous = LAST_DIM.put(player.getUUID(), current);
            if (previous != null && !previous.equals(current)) {
                ServerLevel from = server.getLevel(previous);
                ServerLevel to = server.getLevel(current);
                if (from != null && to != null) {
                    AnimusDimensionFollow.onOwnerChangedDimension(player, from, to);
                }
            }
        }
    }
}
