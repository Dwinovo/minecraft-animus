package com.dwinovo.tulpa.platform;

import com.dwinovo.tulpa.platform.services.IBlockCapabilityReader;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.energy.IEnergyStorage;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.capability.IFluidHandler;
import net.neoforged.neoforge.items.IItemHandler;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/**
 * NeoForge implementation of {@link IBlockCapabilityReader} — reads a block's
 * item/fluid/energy contents through the standard block capabilities.
 *
 * <h2>Why query null AND every face</h2>
 * The {@code null} context means "no particular side"; many machines expose a
 * combined handler there, but several (Industrial Foregoing, Mekanism disabled
 * faces, Thermal side-config) return {@code null} for {@code null} and ONLY
 * expose per-face handlers. There is no documented contract that null-side is a
 * combined view, so we probe {@code null} + all six {@link Direction}s and
 * de-duplicate the returned handlers by identity, recording which sides exposed
 * each one.
 */
public final class NeoForgeBlockCapabilityReader implements IBlockCapabilityReader {

    /** Cap on listed non-empty item slots per handler, so a huge modded inventory can't blow up the reply. */
    private static final int MAX_SLOT_LINES = 64;

    @Override
    public String describe(Level level, BlockPos pos) {
        StringBuilder sb = new StringBuilder();
        appendItems(level, pos, sb);
        appendFluids(level, pos, sb);
        appendEnergy(level, pos, sb);
        return sb.length() == 0 ? null : sb.toString();
    }

    private void appendItems(Level level, BlockPos pos, StringBuilder sb) {
        Map<IItemHandler, List<String>> byHandler = new IdentityHashMap<>();
        collect(byHandler, level.getCapability(Capabilities.ItemHandler.BLOCK, pos, null), "all");
        for (Direction d : Direction.values()) {
            collect(byHandler, level.getCapability(Capabilities.ItemHandler.BLOCK, pos, d), d.getName());
        }
        if (byHandler.isEmpty()) return;
        int idx = 0;
        for (Map.Entry<IItemHandler, List<String>> e : byHandler.entrySet()) {
            IItemHandler h = e.getKey();
            sb.append("items").append(byHandler.size() > 1 ? " #" + idx : "")
                    .append(" (sides: ").append(String.join(",", e.getValue())).append("), ")
                    .append(h.getSlots()).append(" slots:\n");
            int shown = 0;
            boolean any = false;
            for (int s = 0; s < h.getSlots(); s++) {
                ItemStack st = h.getStackInSlot(s);
                if (st.isEmpty()) continue;
                any = true;
                if (shown++ >= MAX_SLOT_LINES) continue;
                sb.append("  slot ").append(s).append(": ")
                        .append(itemId(st)).append(" x").append(st.getCount()).append("\n");
            }
            if (!any) {
                sb.append("  (all ").append(h.getSlots()).append(" slots empty)\n");
            } else if (shown > MAX_SLOT_LINES) {
                sb.append("  … and ").append(shown - MAX_SLOT_LINES).append(" more non-empty slots\n");
            }
            idx++;
        }
    }

    private void appendFluids(Level level, BlockPos pos, StringBuilder sb) {
        Map<IFluidHandler, List<String>> byHandler = new IdentityHashMap<>();
        collect(byHandler, level.getCapability(Capabilities.FluidHandler.BLOCK, pos, null), "all");
        for (Direction d : Direction.values()) {
            collect(byHandler, level.getCapability(Capabilities.FluidHandler.BLOCK, pos, d), d.getName());
        }
        if (byHandler.isEmpty()) return;
        int idx = 0;
        for (Map.Entry<IFluidHandler, List<String>> e : byHandler.entrySet()) {
            IFluidHandler h = e.getKey();
            sb.append("fluids").append(byHandler.size() > 1 ? " #" + idx : "")
                    .append(" (sides: ").append(String.join(",", e.getValue())).append("):\n");
            for (int t = 0; t < h.getTanks(); t++) {
                FluidStack fs = h.getFluidInTank(t);
                sb.append("  tank ").append(t).append(": ");
                if (fs.isEmpty()) {
                    sb.append("empty");
                } else {
                    sb.append(fluidId(fs)).append(" ").append(fs.getAmount());
                }
                sb.append("/").append(h.getTankCapacity(t)).append(" mB\n");
            }
            idx++;
        }
    }

    private void appendEnergy(Level level, BlockPos pos, StringBuilder sb) {
        IEnergyStorage en = level.getCapability(Capabilities.EnergyStorage.BLOCK, pos, null);
        if (en == null) {
            for (Direction d : Direction.values()) {
                en = level.getCapability(Capabilities.EnergyStorage.BLOCK, pos, d);
                if (en != null) break;
            }
        }
        if (en == null) return;
        sb.append("energy: ").append(en.getEnergyStored()).append("/").append(en.getMaxEnergyStored())
                .append(" FE");
        List<String> io = new ArrayList<>();
        if (en.canReceive()) io.add("accepts");
        if (en.canExtract()) io.add("provides");
        if (!io.isEmpty()) sb.append(" (").append(String.join("/", io)).append(")");
        sb.append("\n");
    }

    /** Record a non-null handler under the side that exposed it, de-duplicating by identity. */
    private static <T> void collect(Map<T, List<String>> byHandler, T handler, String side) {
        if (handler == null) return;
        byHandler.computeIfAbsent(handler, h -> new ArrayList<>()).add(side);
    }

    private static String itemId(ItemStack stack) {
        return BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
    }

    private static String fluidId(FluidStack stack) {
        return BuiltInRegistries.FLUID.getKey(stack.getFluid()).toString();
    }
}
