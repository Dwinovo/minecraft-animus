package com.dwinovo.animus.entity;

import com.dwinovo.animus.Constants;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Relative;
import net.minecraft.world.level.entity.EntityTypeTest;

import java.util.List;
import java.util.Set;

/**
 * Brings owned Animus companions along when their owner crosses a dimension
 * boundary (Nether / End / custom). The loader-specific entry points
 * (Fabric {@code ServerEntityWorldChangeEvents.AFTER_PLAYER_CHANGE_WORLD},
 * NeoForge {@code PlayerEvent.PlayerChangedDimensionEvent}) both funnel here.
 *
 * <h2>Why an event, not entity AI</h2>
 * A pet that self-detects "my owner is in another dimension" in its own tick is
 * fragile: the moment the owner leaves, the companion's chunk can stop ticking,
 * so the check never fires. Hooking the owner's dimension change runs while the
 * companion is still loaded in the dimension being left — the standard
 * follower-mod approach. Vanilla {@code TamableAnimal} has no cross-dimension
 * follow of its own (its teleport-to-owner is same-dimension only).
 *
 * <h2>Why idle companions travel with the owner</h2>
 * An idle pet's job is companionship — vanilla-pet expectations say it comes
 * along. The conversation survives the trip because the loop is keyed by UUID
 * (see {@link com.dwinovo.animus.client.agent.AgentLoopRegistry}); the
 * recreated body in the new dimension resolves back to the same loop.
 *
 * <h2>Why ENGAGED companions stay behind</h2>
 * The agent loop drives the body over UUID-addressed payloads that the server
 * resolves across all dimensions ({@code AnimusEntity.findByUuid}), so a
 * working pet does not need to share the owner's dimension — its chunk
 * tickets keep it simulating and the roster reaches it from anywhere. (TLM
 * forbids maid dimension travel outright; we need both directions, so the
 * split is by engagement.)
 */
public final class AnimusDimensionFollow {

    private AnimusDimensionFollow() {}

    /**
     * Teleport every owned, living, IDLE Animus in {@code from} to the
     * owner's new position in {@code to}. Engaged companions (task
     * running/queued or mid-conversation) stay behind: yanking a worker off
     * its mining run because the owner took a portal home would break the
     * task — chunk tickets keep it working and the revival path keeps it
     * reachable from any dimension.
     */
    public static void onOwnerChangedDimension(ServerPlayer owner, ServerLevel from, ServerLevel to) {
        if (from == to) return;

        List<? extends AnimusEntity> companions = from.getEntities(
                EntityTypeTest.forClass(AnimusEntity.class),
                // UUID comparison: this event fires AFTER the owner left `from`,
                // so a level-scoped owner lookup would match no companion here.
                a -> a.isAlive() && a.isOwnedByPlayer(owner.getUUID()) && !a.isEngaged());
        if (companions.isEmpty()) return;

        for (AnimusEntity companion : companions) {
            // teleportTo handles the cross-dimension recreate (new int id, same
            // UUID); the rebuilt body lands on the owner. Absolute coords (empty
            // relative-movement set), facing the owner's direction.
            boolean ok = companion.teleportTo(to,
                    owner.getX(), owner.getY(), owner.getZ(),
                    Set.<Relative>of(), owner.getYRot(), owner.getXRot(), false);
            Constants.LOG.info("[animus] companion {} follows {} {} -> {} ({})",
                    companion.getUUID(), owner.getName().getString(),
                    from.dimension().identifier(), to.dimension().identifier(),
                    ok ? "ok" : "FAILED");
        }
    }
}
