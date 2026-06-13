package com.dwinovo.animus.init;

import net.minecraft.server.level.TicketType;

/**
 * The mod's chunk ticket types. Constructed in common; each loader REGISTERS
 * them into {@code BuiltInRegistries.TICKET_TYPE} at init — the same thing
 * vanilla does for ENDER_PEARL/PORTAL/etc. Registration is what makes the
 * type visible to debug tooling and serializable by the ticket storage;
 * an unregistered instance works but is invisible and unsaveable.
 */
public final class InitTicketType {

    /** Registry path for {@link #TASK} (namespace is the mod id). */
    public static final String TASK_ID = "task";

    /** Ticket timeout in ticks: expires this long after the last refresh. */
    public static final long TASK_TIMEOUT_TICKS = 200L;

    /** Ticket radius in chunks: 5x5 covers pathfinder snapshots and dig radii. */
    public static final int TASK_TICKET_RADIUS = 2;

    /**
     * Re-drop cadence for whoever holds the lease. Both refreshers — the
     * owner-liveness heartbeat ({@code KeepLoadedPayload}) and the server-side
     * in-flight-task floor ({@code AnimusEntity.refreshChunkTicket}) — re-issue
     * the ticket this often, which must stay well under {@link #TASK_TIMEOUT_TICKS}
     * so a single dropped refresh never unloads the pet. 20 ticks (1s) against a
     * 200-tick (10s) timeout is a 10× margin — the relationship is loose by
     * design, so there is nothing fragile to keep in sync.
     */
    public static final int TASK_TICKET_REFRESH_TICKS = 20;

    /**
     * Keeps an Animus loaded and ticking away from any player while its owner's
     * agent loop is alive (heartbeat lease) or it has in-flight task work.
     * Flags mirror vanilla ENDER_PEARL: LOADING alone gives border chunks
     * whose entities never tick (hence SIMULATION), and a playerless
     * dimension stops ticking entities entirely after 300 empty ticks unless
     * a ticket carries KEEP_DIMENSION_ACTIVE. Deliberately NOT FLAG_PERSIST:
     * the timeout makes every teardown path (loop idle, owner offline, death,
     * crash, server stop) "just stop refreshing", and the last-seen index
     * re-loads pets on the next heartbeat after a restart — a persisted ticket
     * with no owner-liveness validation would orphan chunks instead.
     */
    public static final TicketType TASK = new TicketType(TASK_TIMEOUT_TICKS,
            TicketType.FLAG_LOADING
                    | TicketType.FLAG_SIMULATION
                    | TicketType.FLAG_KEEP_DIMENSION_ACTIVE);

    private InitTicketType() {}
}
