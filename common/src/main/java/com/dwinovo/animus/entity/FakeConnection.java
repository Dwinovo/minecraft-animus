package com.dwinovo.animus.entity;

import io.netty.channel.ChannelFutureListener;
import net.minecraft.network.Connection;
import net.minecraft.network.DisconnectionDetails;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketFlow;

import java.util.function.Consumer;

/**
 * A channel-less {@link Connection} for a companion fake {@code ServerPlayer}.
 * The server pushes a steady stream of clientbound packets at any list-resident
 * player (position, chunks, entity updates, keep-alives); a real connection
 * writes them to a netty channel, but our companion has no client on the other
 * end. A bare {@code new Connection(...)} is NOT safe: its {@code send} queues
 * every packet into an unbounded {@code pendingActions} list when no channel is
 * attached — a slow leak. So we subclass and DISCARD all outbound I/O.
 *
 * <p>{@code Connection} is non-final with a public {@code PacketFlow} ctor, so
 * this is pure common code — no reflection, access-wideners or mixins. We report
 * {@link #isConnected()} as {@code true} so vanilla treats the player as live and
 * ticks it normally, and we neutralise the keep-alive timeout: that path calls
 * {@link #disconnect(Component)} after 15s of no client reply, which we no-op so
 * the companion is never auto-removed. The body's lifecycle is governed
 * explicitly by {@code CompanionLifecycle}, not by connection state.
 */
public final class FakeConnection extends Connection {

    public FakeConnection() {
        super(PacketFlow.CLIENTBOUND);
    }

    /** Discard every outbound packet — there is no client to receive it.
     *  The 1-arg and 2-arg {@code send} overloads route through this one. */
    @Override
    public void send(Packet<?> packet, ChannelFutureListener listener, boolean flush) {
        // no-op: drop it on the floor (no channel, no pendingActions growth)
    }

    /** Vanilla queues these until "connected"; we never connect, so run nothing. */
    @Override
    public void runOnceConnected(Consumer<Connection> action) {
        // no-op
    }

    /**
     * Report live so player ticking / chunk tracking proceed as for a real
     * player. Safe because every channel-dereferencing path (send, tick,
     * flushChannel) is overridden to no-op below, so the null channel is never
     * touched.
     */
    @Override
    public boolean isConnected() {
        return true;
    }

    /** Don't drive the packet listener's tick (that's what runs the keep-alive). */
    @Override
    public void tick() {
        // no-op
    }

    /** Neutralise the keep-alive timeout (and any other) disconnect — both overloads. */
    @Override
    public void disconnect(Component message) {
        // no-op: the companion is removed via CompanionLifecycle, never by the wire
    }

    @Override
    public void disconnect(DisconnectionDetails details) {
        // no-op
    }

    @Override
    public void handleDisconnection() {
        // no-op
    }

    @Override
    public void flushChannel() {
        // no-op
    }

    @Override
    public void setReadOnly() {
        // no-op
    }
}
