package com.dwinovo.animus.data;

import com.dwinovo.animus.Constants;
import com.dwinovo.animus.entity.AnimusEntity;
import com.dwinovo.animus.entity.InitEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.EntitySpawnReason;

/**
 * Server-side helpers for the unit summon/recall lifecycle.
 *
 * <h2>Summon flow</h2>
 * <ol>
 *   <li>{@link #summonUnit} — caller (typically {@code AssignTaskPayload.handle})
 *       passes the player, unit_id, and a task prompt.</li>
 *   <li>This class checks the slot is idle (busy-guard) and the unit is
 *       alive (not in respawn cooldown), then finds a safe spawn pos near
 *       the player.</li>
 *   <li>Constructs a fresh {@link AnimusEntity}, applies the saved
 *       model_key, sets the player as owner, marks tame, places it,
 *       broadcasts summon particles + sound.</li>
 *   <li>Registers the binding in {@link PlayerAnimusData#bindActive}.</li>
 *   <li>Returns the spawned entity so the caller can wire up the
 *       EntityAgent and send the prompt.</li>
 * </ol>
 *
 * <h2>Recall flow</h2>
 * Symmetric — {@link #recallUnit} broadcasts recall particles, removes the
 * entity, unbinds the slot. Used by both {@code RecallUnitPayload} (explicit
 * abort) and the EntityAgent final-text-reply hook (natural termination).
 */
public final class PlayerAnimusManager {

    /** Max horizontal distance from player for the safe-spawn search. */
    private static final int SPAWN_SEARCH_RADIUS = 3;
    /** Vertical wiggle room when probing for safe ground. */
    private static final int SPAWN_VERTICAL_RADIUS = 2;

    private PlayerAnimusManager() {}

    /** Result of {@link #summonUnit} — either an entity ref or a fail reason. */
    public sealed interface SummonResult {
        record Ok(AnimusEntity entity) implements SummonResult {}
        record Fail(String reason) implements SummonResult {}
    }

    /**
     * Summon the player's unit_id into the world near the player.
     *
     * <p>Validates: unit_id is in range, slot is currently idle (no active
     * entity), unit is alive (not in death cooldown), a safe spawn pos is
     * available. Returns a {@link SummonResult.Fail} with a human-readable
     * reason on any precondition miss — the caller (network handler)
     * surfaces this back to the LLM as a tool result.
     */
    public static SummonResult summonUnit(ServerPlayer player, int unitId) {
        if (unitId < 1 || unitId > PlayerAnimusData.SLOT_COUNT) {
            return new SummonResult.Fail("unit_id out of range (must be 1.."
                    + PlayerAnimusData.SLOT_COUNT + ")");
        }
        PlayerAnimusData data = PlayerAnimusData.of(player);
        if (data.isActive(unitId)) {
            return new SummonResult.Fail("unit " + unitId + " is busy with another task; "
                    + "wait for it to finish or call recall_unit first");
        }
        UnitConfig cfg = data.unit(unitId);
        if (!cfg.alive()) {
            return new SummonResult.Fail("unit " + unitId + " is dead and respawning");
        }
        if (!(player.level() instanceof ServerLevel level)) {
            return new SummonResult.Fail("player is not in a server level");
        }

        BlockPos spawnPos = findSafeSpawn(level, player.blockPosition());
        if (spawnPos == null) {
            return new SummonResult.Fail("no safe spawn position near player (try moving)");
        }

        AnimusEntity entity = InitEntity.ANIMUS.get().create(level, EntitySpawnReason.MOB_SUMMONED);
        if (entity == null) {
            return new SummonResult.Fail("entity construction failed");
        }
        entity.snapTo(spawnPos.getX() + 0.5, (double) spawnPos.getY(), spawnPos.getZ() + 0.5,
                level.getRandom().nextFloat() * 360.0F, 0.0F);
        // Bind ownership: tame to player so InteractHandler / future checks
        // treat the player as owner.
        entity.tame(player);
        // Apply persisted per-unit model key.
        Identifier modelId = Identifier.tryParse(cfg.modelKey());
        if (modelId != null) entity.setModelKey(modelId);

        if (!level.addFreshEntity(entity)) {
            return new SummonResult.Fail("level rejected entity spawn");
        }

        data.bindActive(unitId, entity.getId());
        playSummonEffects(level, spawnPos);

        Constants.LOG.info("[animus-manager] summoned unit {} for player {} → vanilla id {}",
                unitId, player.getName().getString(), entity.getId());
        return new SummonResult.Ok(entity);
    }

    /**
     * Recall a unit from the world. No-op if the slot is already idle. Used
     * by both explicit {@code recall_unit} from the LLM and the EntityAgent
     * natural-termination hook.
     */
    public static void recallUnit(ServerPlayer player, int unitId) {
        PlayerAnimusData data = PlayerAnimusData.of(player);
        int vanillaId = data.activeVanillaId(unitId);
        if (vanillaId == -1) return;
        if (!(player.level() instanceof ServerLevel level)) return;

        // Unbind FIRST so the AnimusEntity.remove() hook sees no binding
        // and skips its UnitDiedPayload notification. This is the
        // intentional-recall path — the client already initiated the
        // tear-down and doesn't need a death event echoed back.
        data.unbindActive(unitId);

        var raw = level.getEntity(vanillaId);
        if (raw instanceof AnimusEntity entity) {
            BlockPos pos = entity.blockPosition();
            playRecallEffects(level, pos);
            entity.discard();
        }

        Constants.LOG.info("[animus-manager] recalled unit {} for player {}",
                unitId, player.getName().getString());
    }

    /**
     * Probe blocks around {@code center} for a position with breathable air
     * above and a solid floor — same heuristic vanilla uses for mob teleport.
     * Returns {@code null} if nothing works in the search box.
     */
    private static BlockPos findSafeSpawn(ServerLevel level, BlockPos center) {
        var rand = level.getRandom();
        for (int attempt = 0; attempt < 20; attempt++) {
            int dx = rand.nextInt(SPAWN_SEARCH_RADIUS * 2 + 1) - SPAWN_SEARCH_RADIUS;
            int dy = rand.nextInt(SPAWN_VERTICAL_RADIUS * 2 + 1) - SPAWN_VERTICAL_RADIUS;
            int dz = rand.nextInt(SPAWN_SEARCH_RADIUS * 2 + 1) - SPAWN_SEARCH_RADIUS;
            BlockPos candidate = center.offset(dx, dy, dz);
            if (isSafe(level, candidate)) return candidate;
        }
        return null;
    }

    private static boolean isSafe(ServerLevel level, BlockPos pos) {
        return level.getBlockState(pos).isAir()
                && level.getBlockState(pos.above()).isAir()
                && !level.getBlockState(pos.below()).isAir();
    }

    private static void playSummonEffects(ServerLevel level, BlockPos pos) {
        level.sendParticles(ParticleTypes.PORTAL,
                pos.getX() + 0.5, pos.getY() + 1.0, pos.getZ() + 0.5,
                30, 0.4, 0.6, 0.4, 0.05);
        level.playSound(null, pos, SoundEvents.ENDERMAN_TELEPORT,
                SoundSource.NEUTRAL, 0.6F, 1.4F);
    }

    private static void playRecallEffects(ServerLevel level, BlockPos pos) {
        level.sendParticles(ParticleTypes.POOF,
                pos.getX() + 0.5, pos.getY() + 1.0, pos.getZ() + 0.5,
                15, 0.3, 0.5, 0.3, 0.02);
        level.playSound(null, pos, SoundEvents.ENDERMAN_TELEPORT,
                SoundSource.NEUTRAL, 0.4F, 0.8F);
    }
}
