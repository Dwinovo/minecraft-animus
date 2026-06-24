package com.dwinovo.tulpa.platform;

import com.dwinovo.tulpa.platform.services.IBlockCapabilityReader;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.transfer.ResourceHandler;
import net.neoforged.neoforge.transfer.energy.EnergyHandler;
import net.neoforged.neoforge.transfer.fluid.FluidResource;
import net.neoforged.neoforge.transfer.item.ItemResource;
import net.neoforged.neoforge.transfer.transaction.Transaction;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/**
 * NeoForge implementation of {@link IBlockCapabilityReader} — reads a block's
 * item/fluid/energy contents through the standard block capabilities.
 *
 * <h2>Transfer Rework (MC 1.21.9+)</h2>
 * This branch targets the reworked transfer API: items and fluids are both
 * {@link ResourceHandler} (slot-indexed {@code size()}/{@code getResource}/
 * {@code getAmountAsLong}), and energy is {@link EnergyHandler}. The old
 * {@code canReceive()}/{@code canExtract()} flags are gone, so we probe energy
 * direction by simulating an insert/extract inside a rolled-back
 * {@link Transaction}. The pre-1.21.9 branches use the classic
 * {@code IItemHandler}/{@code IFluidHandler}/{@code IEnergyStorage} variant.
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
        Map<ResourceHandler<ItemResource>, List<String>> byHandler = new IdentityHashMap<>();
        collect(byHandler, level.getCapability(Capabilities.Item.BLOCK, pos, null), "all");
        for (Direction d : Direction.values()) {
            collect(byHandler, level.getCapability(Capabilities.Item.BLOCK, pos, d), d.getName());
        }
        if (byHandler.isEmpty()) return;
        int idx = 0;
        for (Map.Entry<ResourceHandler<ItemResource>, List<String>> e : byHandler.entrySet()) {
            ResourceHandler<ItemResource> h = e.getKey();
            sb.append("items").append(byHandler.size() > 1 ? " #" + idx : "")
                    .append(" (sides: ").append(String.join(",", e.getValue())).append("), ")
                    .append(h.size()).append(" slots:\n");
            int shown = 0;
            boolean any = false;
            for (int s = 0; s < h.size(); s++) {
                ItemResource res = h.getResource(s);
                if (res.isEmpty()) continue;
                any = true;
                if (shown++ >= MAX_SLOT_LINES) continue;
                sb.append("  slot ").append(s).append(": ")
                        .append(itemId(res)).append(" x").append(h.getAmountAsLong(s)).append("\n");
            }
            if (!any) {
                sb.append("  (all ").append(h.size()).append(" slots empty)\n");
            } else if (shown > MAX_SLOT_LINES) {
                sb.append("  … and ").append(shown - MAX_SLOT_LINES).append(" more non-empty slots\n");
            }
            idx++;
        }
    }

    private void appendFluids(Level level, BlockPos pos, StringBuilder sb) {
        Map<ResourceHandler<FluidResource>, List<String>> byHandler = new IdentityHashMap<>();
        collect(byHandler, level.getCapability(Capabilities.Fluid.BLOCK, pos, null), "all");
        for (Direction d : Direction.values()) {
            collect(byHandler, level.getCapability(Capabilities.Fluid.BLOCK, pos, d), d.getName());
        }
        if (byHandler.isEmpty()) return;
        int idx = 0;
        for (Map.Entry<ResourceHandler<FluidResource>, List<String>> e : byHandler.entrySet()) {
            ResourceHandler<FluidResource> h = e.getKey();
            sb.append("fluids").append(byHandler.size() > 1 ? " #" + idx : "")
                    .append(" (sides: ").append(String.join(",", e.getValue())).append("):\n");
            for (int t = 0; t < h.size(); t++) {
                FluidResource res = h.getResource(t);
                sb.append("  tank ").append(t).append(": ");
                if (res.isEmpty()) {
                    sb.append("empty");
                } else {
                    sb.append(fluidId(res)).append(" ").append(h.getAmountAsLong(t));
                }
                sb.append("/").append(h.getCapacityAsLong(t, res)).append(" mB\n");
            }
            idx++;
        }
    }

    private void appendEnergy(Level level, BlockPos pos, StringBuilder sb) {
        EnergyHandler en = level.getCapability(Capabilities.Energy.BLOCK, pos, null);
        if (en == null) {
            for (Direction d : Direction.values()) {
                en = level.getCapability(Capabilities.Energy.BLOCK, pos, d);
                if (en != null) break;
            }
        }
        if (en == null) return;
        sb.append("energy: ").append(en.getAmountAsLong()).append("/").append(en.getCapacityAsLong())
                .append(" FE");
        // The reworked EnergyHandler dropped canReceive()/canExtract(); probe the
        // direction by simulating a max insert/extract inside a transaction we
        // never commit, so the block's state is untouched.
        List<String> io = new ArrayList<>();
        try (Transaction tx = Transaction.openRoot()) {
            if (en.insert(Integer.MAX_VALUE, tx) > 0) io.add("accepts");
            if (en.extract(Integer.MAX_VALUE, tx) > 0) io.add("provides");
        }
        if (!io.isEmpty()) sb.append(" (").append(String.join("/", io)).append(")");
        sb.append("\n");
    }

    /** Record a non-null handler under the side that exposed it, de-duplicating by identity. */
    private static <T> void collect(Map<T, List<String>> byHandler, T handler, String side) {
        if (handler == null) return;
        byHandler.computeIfAbsent(handler, h -> new ArrayList<>()).add(side);
    }

    private static String itemId(ItemResource res) {
        return BuiltInRegistries.ITEM.getKey(res.getItem()).toString();
    }

    private static String fluidId(FluidResource res) {
        return BuiltInRegistries.FLUID.getKey(res.getFluid()).toString();
    }
}
