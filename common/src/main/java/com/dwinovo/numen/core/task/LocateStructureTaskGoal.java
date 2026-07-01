package com.dwinovo.numen.core.task;

import com.dwinovo.numen.entity.NumenPlayer;
import com.dwinovo.numen.core.task.CompanionTask;

import com.dwinovo.numen.task.TaskResult;
import com.dwinovo.numen.core.task.TaskState;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.SectionPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkGeneratorStructureState;
import net.minecraft.world.level.chunk.ChunkStatus;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructureCheckResult;
import net.minecraft.world.level.levelgen.structure.StructureStart;
import net.minecraft.world.level.levelgen.structure.placement.ConcentricRingsStructurePlacement;
import net.minecraft.world.level.levelgen.structure.placement.RandomSpreadStructurePlacement;
import net.minecraft.world.level.levelgen.structure.placement.StructurePlacement;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Goal for {@code locate_structure}: find the nearest instance of a structure
 * (by id) or structure family (by {@code #tag}) in the entity's CURRENT
 * dimension — vanilla {@code /locate structure} semantics, but <b>time-sliced
 * across ticks instead of one synchronous call</b>.
 *
 * <h2>Why not {@code findNearestMapStructure}</h2>
 * The vanilla helper walks its whole candidate spiral inside one call; for
 * unlucky seeds / sparse structures the chunk-load fallbacks inside it stall
 * the server tick. Explorer's Compass (MattCzyr) solved this with budgeted
 * tick workers; this goal adapts that pattern to our already-tick-sliced task
 * machinery:
 * <ul>
 *   <li>candidate chunks come from the placement math itself
 *       ({@link RandomSpreadStructurePlacement#getPotentialStructureChunk} on
 *       an expanding ring spiral; {@code getRingPositionsFor} for concentric
 *       rings like the stronghold) — never a blind chunk walk;</li>
 *   <li>presence is checked with
 *       {@link net.minecraft.world.level.StructureManager#checkStructurePresence}
 *       (a cached lookup that does NOT generate chunks);</li>
 *   <li>only {@code CHUNK_LOAD_NEEDED} fallbacks pay a real
 *       {@code STRUCTURE_STARTS} chunk load, strictly budgeted via the
 *       GLOBAL {@link SearchBudget} shared by all searches.</li>
 * </ul>
 * Worst case the answer takes a handful of ticks instead of hitching one.
 */
public final class LocateStructureTaskGoal implements CompanionTask {

    /**
     * Search radius in placement-region RINGS, exactly vanilla /locate's
     * radius unit (one ring = one region = {@code spacing} chunks, so the
     * covered distance scales with the structure's rarity: fortress ≈ 43k
     * blocks, village ≈ 54k). The global budget + the task deadline bound the
     * actual work; a search that exhausts its deadline reports how far it got.
     */
    private static final int SEARCH_RADIUS_RINGS = 100;

    /** One placement's candidate stream + the structures that use it. */
    private static final class Job {
        final StructurePlacement placement;
        final List<Structure> structures = new ArrayList<>(1);
        // Spiral state (RandomSpread): region-grid ring walk around the entity.
        RandomSpreadStructurePlacement spread;
        long seed;
        int centerRegX, centerRegZ, maxRing, ring, perimIdx;
        // Ring-list state (ConcentricRings, e.g. stronghold).
        List<ChunkPos> ringPositions;

        Job(StructurePlacement placement) {
            this.placement = placement;
        }

        /** Next candidate chunk, or null when this job is exhausted. */
        ChunkPos next() {
            if (spread == null) return null;     // ring jobs are consumed in onStart
            while (ring <= maxRing) {
                if (perimIdx >= RingSpiral.perimeter(ring)) {
                    ring++;
                    perimIdx = 0;
                    continue;
                }
                int[] d = RingSpiral.offset(ring, perimIdx++);
                // getPotentialStructureChunk takes CHUNK coords and floorDivs by
                // spacing itself — scale the region index back to chunk scale.
                return spread.getPotentialStructureChunk(seed,
                        (centerRegX + d[0]) * spread.spacing(),
                        (centerRegZ + d[1]) * spread.spacing());
            }
            return null;
        }
    }

    private final List<Job> jobs = new ArrayList<>();
    private int jobIndex;
    private ChunkPos pendingCandidate;     // budget ran out mid-candidate; resume here
    private BlockPos best;
    private double bestDistSqr = Double.MAX_VALUE;
    private String failReason = "not on a server level";

    private final NumenPlayer player;
    private final LocateStructureTaskRecord r;

    public LocateStructureTaskGoal(NumenPlayer player, LocateStructureTaskRecord record) {
        this.player = player;
        this.r = record;
    }

    @Override
    public void start() {
        jobs.clear();
        jobIndex = 0;
        pendingCandidate = null;
        best = null;
        bestDistSqr = Double.MAX_VALUE;

        if (!(player.level() instanceof ServerLevel sl)) {
            failReason = "not on a server level";
            r.setState(TaskState.FAILED);
            return;
        }
        List<Holder<Structure>> holders = resolveStructures(sl, r.structure.trim());
        if (holders == null) {
            // failReason set by resolveStructures
            r.setState(TaskState.FAILED);
            return;
        }
        if (holders.isEmpty()) {
            r.setState(TaskState.SUCCESS);   // valid tag, nothing in it → "not found"
            return;
        }

        ChunkGeneratorStructureState state = sl.getChunkSource().getGeneratorState();
        ChunkPos here = player.chunkPosition();
        Map<StructurePlacement, Job> byPlacement = new LinkedHashMap<>();
        for (Holder<Structure> holder : holders) {
            for (StructurePlacement placement : state.getPlacementsForStructure(holder)) {
                byPlacement.computeIfAbsent(placement, Job::new)
                        .structures.add(holder.value());
            }
        }
        for (Job job : byPlacement.values()) {
            if (job.placement instanceof RandomSpreadStructurePlacement spread) {
                job.spread = spread;
                job.seed = state.getLevelSeed();
                job.centerRegX = Math.floorDiv(here.x, spread.spacing());
                job.centerRegZ = Math.floorDiv(here.z, spread.spacing());
                job.maxRing = SEARCH_RADIUS_RINGS;
                jobs.add(job);
            } else if (job.placement instanceof ConcentricRingsStructurePlacement rings) {
                // Ring positions ARE where these structures generate (vanilla
                // computes + caches them once per world) — pick the nearest
                // directly, no presence checks or chunk loads needed.
                List<ChunkPos> positions = state.getRingPositionsFor(rings);
                if (positions != null) {
                    for (ChunkPos cp : positions) {
                        consider(job.placement.getLocatePos(cp));
                    }
                }
            }
        }
        // No jobs and no ring hit → finish immediately as "not found" (e.g.
        // fortress searched from the overworld: no placement in this dimension).
        if (jobs.isEmpty()) {
            r.setState(TaskState.SUCCESS);
        }
    }

    /** @return resolved holders, empty list for a valid-but-empty tag, or null on bad input. */
    private List<Holder<Structure>> resolveStructures(ServerLevel sl, String arg) {
        var registry = sl.registryAccess().lookupOrThrow(Registries.STRUCTURE);
        List<Holder<Structure>> out = new ArrayList<>();
        if (arg.startsWith("#")) {
            ResourceLocation tagId = ResourceLocation.tryParse(arg.substring(1));
            if (tagId == null) {
                failReason = "invalid structure tag: " + arg;
                return null;
            }
            var set = registry.get(TagKey.create(Registries.STRUCTURE, tagId));
            if (set.isEmpty()) {
                failReason = isBiomeTag(sl, tagId)
                        ? arg + " is a BIOME tag, not a structure tag — call "
                                + "locate_biome(biome=\"" + arg + "\") instead"
                        : "unknown structure tag: " + arg + " — try #minecraft:village "
                                + "or an id like minecraft:fortress";
                return null;
            }
            set.get().forEach(out::add);
            return out;
        }
        ResourceLocation id = ResourceLocation.tryParse(arg);
        Optional<? extends Holder<Structure>> holder = id == null ? Optional.empty()
                : registry.get(ResourceKey.create(Registries.STRUCTURE, id));
        if (holder.isEmpty()) {
            if (id != null && isBiomeId(sl, id)) {
                failReason = arg + " is a BIOME, not a structure — call "
                        + "locate_biome(biome=\"" + arg + "\") instead";
                return null;
            }
            String suggestion = IdSuggest.closest(
                    registry.listElements().map(ref -> ref.key().location()), arg);
            failReason = "unknown structure: " + arg
                    + (suggestion != null
                            ? " — did you mean " + suggestion + "?"
                            : " — use a structure id like minecraft:fortress / "
                                    + "minecraft:stronghold, or a tag like #minecraft:village; "
                                    + "load_skill(world_atlas) lists every id");
            return null;
        }
        out.add(holder.get());
        return out;
    }

    private static boolean isBiomeId(ServerLevel sl, ResourceLocation id) {
        return sl.registryAccess().lookupOrThrow(Registries.BIOME)
                .get(ResourceKey.create(Registries.BIOME, id)).isPresent();
    }

    private static boolean isBiomeTag(ServerLevel sl, ResourceLocation tagId) {
        return sl.registryAccess().lookupOrThrow(Registries.BIOME)
                .get(TagKey.create(Registries.BIOME, tagId)).isPresent();
    }

    @Override
    public TaskState tick() {
        if (!(player.level() instanceof ServerLevel sl)) {
            failReason = "not on a server level";
            r.setState(TaskState.FAILED);
            return r.getState();
        }
        // GLOBAL budget: shared by every searching companion on the server, so
        // total per-tick search cost is a constant regardless of pet count.
        SearchBudget.refresh(sl.getServer());
        while (true) {
            if (jobIndex >= jobs.size()) {
                r.setState(TaskState.SUCCESS);
                return r.getState();
            }
            Job job = jobs.get(jobIndex);
            ChunkPos candidate = pendingCandidate != null ? pendingCandidate : job.next();
            pendingCandidate = null;
            if (candidate == null) {
                jobIndex++;
                continue;
            }
            if (!SearchBudget.tryCheck()) {
                pendingCandidate = candidate;   // pool drained — resume next tick
                return r.getState();
            }
            Boolean hit = checkCandidate(sl, job, candidate);
            if (hit == null) {
                pendingCandidate = candidate;   // out of chunk-load budget — resume next tick
                return r.getState();
            }
            if (hit) {
                consider(job.placement.getLocatePos(candidate));
                jobIndex++;   // ring order ⇒ first hit is this job's nearest
            }
        }
    }

    /**
     * Is one of the job's structures generating at {@code candidate}?
     * {@code null} means the cached check was inconclusive and the global
     * chunk-load budget for this tick is spent — call again next tick.
     */
    private Boolean checkCandidate(ServerLevel sl, Job job, ChunkPos candidate) {
        ChunkAccess loaded = null;   // one load permit per candidate, shared by all its structures
        for (Structure structure : job.structures) {
            StructureCheckResult res = sl.structureManager()
                    .checkStructurePresence(candidate, structure, false);   // 1.20.4: no StructurePlacement arg
            if (res == StructureCheckResult.START_NOT_PRESENT) continue;
            if (res == StructureCheckResult.START_PRESENT) return true;
            // CHUNK_LOAD_NEEDED — the expensive fallback, globally budgeted.
            if (loaded == null) {
                if (!SearchBudget.tryChunkLoad()) return null;
                loaded = sl.getChunk(candidate.x, candidate.z, ChunkStatus.STRUCTURE_STARTS);
            }
            StructureStart start = sl.structureManager()
                    .getStartForStructure(SectionPos.bottomOf(loaded), structure, loaded);
            if (start != null && start.isValid()) return true;
        }
        return false;
    }

    private void consider(BlockPos pos) {
        double d = pos.distSqr(player.blockPosition());
        if (d < bestDistSqr) {
            bestDistSqr = d;
            best = pos;
        }
    }

    @Override
    public TaskResult buildResult(TaskState finalState) {
        Map<String, Object> data = new HashMap<>();
        data.put("structure", r.structure);
        if (finalState == TaskState.SUCCESS && best != null) {
            BlockPos me = player.blockPosition();
            int dx = best.getX() - me.getX();
            int dz = best.getZ() - me.getZ();
            int dist = (int) Math.sqrt((double) dx * dx + (double) dz * dz);
            String dir = CompassUtil.compass(dx, dz);
            data.put("found", true);
            data.put("x", best.getX());
            data.put("y", best.getY());
            data.put("z", best.getZ());
            data.put("direction", dir);
            data.put("horizontal_distance", dist);
            return TaskResult.ok("nearest " + r.structure + " at " + best.getX() + ","
                    + best.getY() + "," + best.getZ() + " (" + dir + ", ~" + dist
                    + " blocks). move_to the x/z (pick a sensible y for the terrain), "
                    + "then scan_blocks to find its actual blocks.", data);
        }
        data.put("found", false);
        String dim = player.level().dimension().location().getPath();
        return switch (finalState) {
            case SUCCESS -> {
                int searched = searchedRadiusBlocks();
                yield searched == 0
                        ? TaskResult.ok(r.structure + " does not generate IN THIS DIMENSION ("
                                + dim + ") — fortress/bastion: nether; end_city: the end; "
                                + "stronghold/village/mansion/monument: overworld", data)
                        : TaskResult.ok("no " + r.structure + " within ~" + searched
                                + " blocks of here (" + dim + ") — extremely unlucky seed; "
                                + "travel a few thousand blocks and retry", data);
            }
            case TIMEOUT -> TaskResult.timeout("search deadline hit after covering ~"
                    + searchedRadiusBlocks() + " blocks outward with no " + r.structure
                    + " — it is at least that far. Retrying immediately is fine (results "
                    + "are cached, the search resumes fast), or travel toward unexplored "
                    + "land first");
            case CANCELLED -> TaskResult.cancelled("locate_structure interrupted");
            case FAILED -> TaskResult.fail(failReason, data);
            default -> TaskResult.fail("unexpected state: " + finalState, data);
        };
    }

    /** How far outward (blocks) the random-spread spirals have covered so far. */
    private int searchedRadiusBlocks() {
        int max = 0;
        for (Job job : jobs) {
            if (job.spread == null) continue;
            int rings = Math.min(job.ring, job.maxRing);
            max = Math.max(max, rings * job.spread.spacing() * 16);
        }
        return max;
    }

}
