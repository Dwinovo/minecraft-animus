package com.dwinovo.tulpa.mixin;

import com.dwinovo.tulpa.entity.FakeConnection;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.Packet;
import net.minecraft.server.network.ServerCommonPacketListenerImpl;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Drop every outbound packet aimed at a companion {@link FakeConnection}, at the
 * <em>packet-listener</em> level — one layer above {@link FakeConnection#send}.
 *
 * <h2>Why this is needed on top of {@code FakeConnection.send} being a no-op</h2>
 * NeoForge inserts its custom-payload channel validation
 * ({@code NetworkRegistry.checkPacket}) <em>inside</em>
 * {@link ServerCommonPacketListenerImpl#send}, which runs <strong>before</strong> the
 * call reaches {@link Connection#send}. So a companion's no-op {@code Connection.send}
 * never gets the chance to discard a modded clientbound payload — the validation throws
 * first:
 * <pre>UnsupportedOperationException: Payload &lt;mod:foo&gt; may not be sent to the client!</pre>
 * A fake player never negotiated any custom channels (it skips the configuration phase),
 * so the channel set is empty and <em>any</em> mod custom payload trips the check and
 * crashes the server tick. This happens for any mod that pushes a clientbound payload at
 * the body — an item's {@code use()} opening a screen (chiikawa's music box), a menu's
 * per-tick {@code broadcastChanges} (AE2 terminals), etc.
 *
 * <p>Cancelling at {@code HEAD} of the 1-arg {@code send(Packet)} short-circuits before
 * the (2-arg) overload that carries {@code checkPacket} is ever reached. It is a strict
 * superset of what {@link FakeConnection#send} already did (drop on the floor — there is
 * no client), so there is no behavioural regression: vanilla position/chunk/keep-alive
 * packets were already discarded.
 *
 * <p>Common (all environments): the integrated server hits this path in singleplayer too,
 * so it must NOT be a {@code server}-only (dedicated) mixin.
 */
@Mixin(ServerCommonPacketListenerImpl.class)
public abstract class MixinServerCommonPacketListener {

    @Shadow
    @Final
    protected Connection connection;

    @Inject(method = "send(Lnet/minecraft/network/protocol/Packet;)V", at = @At("HEAD"), cancellable = true)
    private void tulpa$dropOutboundForFakeConnection(Packet<?> packet, CallbackInfo ci) {
        if (this.connection instanceof FakeConnection) {
            ci.cancel();
        }
    }
}
