package com.dwinovo.animus;

import com.dwinovo.animus.entity.AnimusEntity;
import com.dwinovo.animus.entity.InitEntity;
import com.dwinovo.animus.network.AnimusNetwork;
import com.dwinovo.animus.platform.NeoForgeNetworkChannel;
import com.dwinovo.animus.platform.Services;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.event.entity.EntityAttributeCreationEvent;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

@Mod(Constants.MOD_ID)
public class AnimusMod {

    private static final DeferredRegister<EntityType<?>> ENTITY_TYPES =
            DeferredRegister.create(BuiltInRegistries.ENTITY_TYPE, Constants.MOD_ID);

    private static final DeferredHolder<EntityType<?>, EntityType<AnimusEntity>> ANIMUS_HOLDER =
            ENTITY_TYPES.register("animus", () ->
                    EntityType.Builder.<AnimusEntity>of(AnimusEntity::new, MobCategory.CREATURE)
                            .sized(0.6f, 0.9f)
                            .build(InitEntity.ANIMUS_KEY));

    public AnimusMod(IEventBus eventBus) {
        ENTITY_TYPES.register(eventBus);
        InitEntity.ANIMUS = ANIMUS_HOLDER::get;

        eventBus.addListener(AnimusMod::registerAttributes);
        eventBus.addListener(AnimusMod::registerPayloads);

        // Queue payload registrations into NeoForgeNetworkChannel; the queue
        // flushes when RegisterPayloadHandlersEvent fires (see below).
        AnimusNetwork.register();

        CommonClass.init();
        Constants.LOG.info("Animus mod initialised on NeoForge.");
    }

    private static void registerAttributes(EntityAttributeCreationEvent event) {
        event.put(ANIMUS_HOLDER.get(), AnimusEntity.createAttributes().build());
    }

    private static void registerPayloads(RegisterPayloadHandlersEvent event) {
        if (Services.NETWORK instanceof NeoForgeNetworkChannel ch) {
            ch.flushPending(event);
        }
    }
}
