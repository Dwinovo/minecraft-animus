package com.dwinovo.tulpa.platform;

import com.dwinovo.tulpa.platform.services.IBlockCapabilityReader;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;

/**
 * Fabric implementation of {@link IBlockCapabilityReader}.
 *
 * <p>TODO(T1-fabric): implement via the Fabric Transfer API
 * ({@code Storage<ItemVariant>} / {@code Storage<FluidVariant>} via
 * {@code ItemStorage.SIDED}/{@code FluidStorage.SIDED}, and Team Reborn Energy's
 * {@code EnergyStorage.SIDED}). For now this is a stub so the service resolves on
 * Fabric and the mod loads; capability reads return {@code null} (the tool then
 * reports "exposes no storage", which is honest for an unimplemented loader).
 * The reader is developed and verified on NeoForge first (where the industrial
 * mod ecosystem lives for testing).
 */
public final class FabricBlockCapabilityReader implements IBlockCapabilityReader {

    @Override
    public String describe(Level level, BlockPos pos) {
        return null;
    }
}
