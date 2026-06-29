package com.dwinovo.numen.agent.tool.tools;

import com.dwinovo.numen.agent.tool.api.Arg;
import com.dwinovo.numen.agent.tool.api.NumenAction;
import com.dwinovo.numen.entity.NumenPlayer;
import com.dwinovo.numen.pathing.util.BlockScanner;
import com.dwinovo.numen.task.tasks.ScanBlocksJob;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

/**
 * The {@code scan_blocks} perception tool authored on the {@link NumenAction}
 * surface. It is an async (budget-sliced) server job: the adapter infers ASYNC
 * because the method takes the live entity plus a reply {@link Consumer} and
 * returns void — the result arrives on a later tick through the callback.
 */
public final class ScanTools {

    private static final int MIN_RADIUS = 1;
    private static final int MAX_RADIUS = 192;
    private static final int MAX_RESULTS = 32;

    @NumenAction(name = "scan_blocks", description =
            "Bulk find blocks of given type(s) within a spherical radius "
            + "around you. Returns matches sorted by distance, capped at "
            + MAX_RESULTS + ". A perception tool for surveying the area — to "
            + "actually gather blocks use auto_mine, which finds and digs "
            + "them itself. Radius is in blocks (max " + MAX_RADIUS + " = 12 "
            + "chunks — use big radii for landscape features like water, "
            + "lava lakes or villages' blocks; a big scan answers after a few "
            + "seconds, you can keep acting meanwhile). FLUIDS are scannable "
            + "too: minecraft:water / minecraft:lava work as block_ids, and "
            + "fluid matches carry source:true/false — only SOURCE lava turns "
            + "to obsidian under water, and only SOURCE water fills a bucket. "
            + "block_ids accepts one or more namespaced ids; include all "
            + "variants (e.g. both iron_ore and deepslate_iron_ore for iron).")
    public void scanBlocks(
            @Arg(value = "Spherical search radius in blocks (max " + MAX_RADIUS + ").",
                    min = MIN_RADIUS, max = MAX_RADIUS) int radius,
            @Arg(value = "List of namespaced block ids to search for.", minItems = 1) List<String> block_ids,
            NumenPlayer self, Consumer<String> reply) {
        int r = Math.clamp(radius, MIN_RADIUS, MAX_RADIUS);
        Set<Block> targets = readBlockIds(block_ids);
        if (targets.isEmpty()) {
            throw new IllegalArgumentException("no valid block_ids provided");
        }
        if (!(self.level() instanceof ServerLevel sl)) {
            throw new IllegalArgumentException("not on a server level");
        }
        BlockPos center = self.blockPosition();
        ScanBlocksJob.start(self.getUUID(), sl, center, r, targets,
                matches -> reply.accept(buildResult(matches, r, center, null)),
                partial -> reply.accept(buildResult(partial.matches(), r, center,
                        "partial: time budget hit after " + partial.columnsScanned() + "/"
                                + partial.columnsTotal() + " chunk columns — results cover "
                                + "the area nearest you; retry for fresh coverage or scan smaller")));
    }

    private static String buildResult(List<BlockScanner.Hit> matches, int radius,
                                      BlockPos center, String partialNote) {
        int limit = Math.min(matches.size(), MAX_RESULTS);
        JsonArray out = new JsonArray();
        for (int i = 0; i < limit; i++) {
            BlockScanner.Hit s = matches.get(i);
            JsonObject o = new JsonObject();
            o.addProperty("x", s.pos().getX());
            o.addProperty("y", s.pos().getY());
            o.addProperty("z", s.pos().getZ());
            o.addProperty("block", BuiltInRegistries.BLOCK.getKey(s.state().getBlock()).toString());
            o.addProperty("distance", s.distance());
            // Source vs flowing is THE decision bit for fluids: obsidian casting
            // and bucket-filling both demand a source cell.
            if (!s.state().getFluidState().isEmpty()) {
                o.addProperty("source", s.state().getFluidState().isSource());
            }
            out.add(o);
        }
        JsonObject root = new JsonObject();
        root.add("matches", out);
        root.addProperty("total_found", matches.size());
        root.addProperty("truncated", matches.size() > MAX_RESULTS);
        root.addProperty("radius_searched", radius);
        if (partialNote != null) {
            root.addProperty("note", partialNote);
        }
        JsonObject centerJson = new JsonObject();
        centerJson.addProperty("x", center.getX());
        centerJson.addProperty("y", center.getY());
        centerJson.addProperty("z", center.getZ());
        root.add("center", centerJson);
        return root.toString();
    }

    private static Set<Block> readBlockIds(List<String> ids) {
        Set<Block> out = new HashSet<>();
        for (String raw : ids) {
            if (raw == null) continue;
            ResourceLocation id = ResourceLocation.tryParse(raw);
            if (id == null) continue;
            Block b = BuiltInRegistries.BLOCK.get(id);
            if (b != null && b != Blocks.AIR) out.add(b);
        }
        return out;
    }
}
