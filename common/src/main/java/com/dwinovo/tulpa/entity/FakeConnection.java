package com.dwinovo.tulpa.entity;

import net.minecraft.network.PacketSendListener;
import io.netty.channel.embedded.EmbeddedChannel;
import net.minecraft.network.Connection;
import net.minecraft.network.ConnectionProtocol;
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
 * this is pure common code — no reflection, access-wideners or mixins. Two
 * details make {@code placeNewPlayer} accept it:
 * <ul>
 *   <li><b>SERVERBOUND</b> receiving flow — a server-side player connection
 *       receives serverbound packets (the client sends them), so its listener
 *       is serverbound; {@code validateListener} rejects a CLIENTBOUND mismatch.</li>
 *   <li>a real (in-memory) {@link EmbeddedChannel} — {@code placeNewPlayer ->
 *       setupInboundProtocol} configures the channel's pipeline, which NPEs on a
 *       channel-less connection. Registering this Connection as the embedded
 *       channel's handler fires {@code channelActive}, setting the channel.</li>
 * </ul>
 * Outbound packets are still discarded by the {@code send} override (the
 * embedded channel is never written to, so it never buffers/leaks), and the
 * keep-alive timeout is neutralised via the no-op {@code disconnect} (a fake
 * player never answers keep-alive). Lifecycle is governed by
 * {@code CompanionLifecycle}, not by connection state.
 */
public final class FakeConnection extends Connection {

    public FakeConnection() {
        super(PacketFlow.SERVERBOUND);
        // Registering this Connection (a netty inbound handler) as the embedded
        // channel's handler fires channelActive → sets this.channel, so the
        // pipeline setup inside placeNewPlayer has a channel to work on.
        EmbeddedChannel channel = new EmbeddedChannel(this);
        // 1.20.4: placeNewPlayer → new ServerGamePacketListenerImpl → Connection.setListener
        // validates channel.attr(PROTOCOL).get().protocol() == listener.protocol() (PLAY). A real
        // login walks handshake→…→play, seeding those attrs; our hand-built connection skips that,
        // so the attr is null → NPE. Seed both directions straight to the PLAY codec.
        Connection.setInitialProtocolAttributes(channel);
        channel.attr(Connection.ATTRIBUTE_SERVERBOUND_PROTOCOL)
                .set(ConnectionProtocol.PLAY.codec(PacketFlow.SERVERBOUND));
        channel.attr(Connection.ATTRIBUTE_CLIENTBOUND_PROTOCOL)
                .set(ConnectionProtocol.PLAY.codec(PacketFlow.CLIENTBOUND));
    }

    /** Discard every outbound packet — there is no client to receive it.
     *  The 1-arg and 2-arg {@code send} overloads route through this one. */
    @Override
    public void send(Packet<?> packet, PacketSendListener listener, boolean flush) {
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

    /** Neutralise the keep-alive timeout (and any other) disconnect. */
    @Override
    public void disconnect(Component message) {
        // no-op: the companion is removed via CompanionLifecycle, never by the wire
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
