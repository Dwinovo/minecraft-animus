package com.dwinovo.animus.init;

import com.dwinovo.animus.Constants;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.Item;

import java.util.function.Supplier;

/**
 * Cross-loader handle for the mod's items. Mirrors {@link InitEntity}: each
 * loader assigns the supplier from its own registration mechanism (Fabric:
 * direct {@code Registry.register}; NeoForge: a {@code DeferredHolder}).
 * Common code reads the live {@link Item} via {@code InitItem.ANIMUS_SPAWN_EGG.get()}.
 */
public final class InitItem {

    public static final Identifier ANIMUS_SPAWN_EGG_ID =
            Identifier.fromNamespaceAndPath(Constants.MOD_ID, "animus_spawn_egg");

    public static final ResourceKey<Item> ANIMUS_SPAWN_EGG_KEY =
            ResourceKey.create(Registries.ITEM, ANIMUS_SPAWN_EGG_ID);

    /** Filled in by each loader's initializer. */
    public static Supplier<Item> ANIMUS_SPAWN_EGG = () -> {
        throw new IllegalStateException("InitItem.ANIMUS_SPAWN_EGG not assigned by loader");
    };

    private InitItem() {}
}
