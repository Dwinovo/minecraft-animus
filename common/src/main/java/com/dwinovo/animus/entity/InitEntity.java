package com.dwinovo.animus.entity;

import com.dwinovo.animus.Constants;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.entity.EntityType;

import java.util.function.Supplier;

/**
 * Cross-loader handle for the Animus entity type. Each loader assigns
 * {@link #ANIMUS} from its own registration mechanism (Fabric: direct
 * {@code Registry.register}; NeoForge: a {@code DeferredHolder} method
 * reference). Common code reads the live {@link EntityType} via
 * {@code InitEntity.ANIMUS.get()} — never store the result, the holder
 * may be re-resolved after a registry reload.
 *
 * <p>Deliberately not abstracted through an {@code IEntityHelper} service:
 * only one entity to register. Lift the pattern into a platform service
 * when a second entity arrives.
 */
public final class InitEntity {

    public static final Identifier ANIMUS_ID =
            Identifier.fromNamespaceAndPath(Constants.MOD_ID, "animus");

    public static final ResourceKey<EntityType<?>> ANIMUS_KEY =
            ResourceKey.create(Registries.ENTITY_TYPE, ANIMUS_ID);

    /** Filled in by each loader's initializer. */
    public static Supplier<EntityType<AnimusEntity>> ANIMUS = () -> {
        throw new IllegalStateException("InitEntity.ANIMUS not assigned by loader");
    };

    private InitEntity() {
    }
}
