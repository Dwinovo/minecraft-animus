package com.dwinovo.animus;

import com.dwinovo.animus.entity.AnimusEntity;
import com.dwinovo.animus.entity.InitEntity;
import com.dwinovo.animus.init.InitItem;
import com.dwinovo.animus.network.AnimusNetwork;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.object.builder.v1.entity.FabricDefaultAttributeRegistry;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.SpawnEggItem;

public class AnimusMod implements ModInitializer {

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

        CommonClass.init();
        Constants.LOG.info("Animus mod initialised on Fabric.");
    }
}
