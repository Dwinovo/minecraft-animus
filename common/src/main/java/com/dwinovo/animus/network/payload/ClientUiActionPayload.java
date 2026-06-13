package com.dwinovo.animus.network.payload;

import com.dwinovo.animus.Constants;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/**
 * Server → Client: ask the receiving owner's client to perform a local UI action.
 * The {@code /animus} command tree lives entirely on the server now (so it
 * doesn't collide with the client-side command dispatcher), but a couple of its
 * verbs — opening the settings GUI, clearing the conversation loops — are
 * inherently client-local. The server command just fires this packet at the
 * caller and their client does the rest.
 */
public record ClientUiActionPayload(Action action) implements CustomPacketPayload {

    public enum Action { OPEN_SETTINGS, RESET_LOOPS }

    public static final Type<ClientUiActionPayload> TYPE = new Type<>(
            Identifier.fromNamespaceAndPath(Constants.MOD_ID, "client_ui_action"));

    public static final StreamCodec<RegistryFriendlyByteBuf, ClientUiActionPayload> STREAM_CODEC =
            StreamCodec.composite(
                    ByteBufCodecs.VAR_INT.map(i -> Action.values()[i], a -> a.ordinal()),
                    ClientUiActionPayload::action,
                    ClientUiActionPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    /** Client-side handler. Runs on the client main thread (network layer arranges that). */
    public static void handle(ClientUiActionPayload p) {
        switch (p.action()) {
            case OPEN_SETTINGS -> com.dwinovo.animus.client.screen.SettingsScreen.open(null);
            case RESET_LOOPS -> com.dwinovo.animus.client.agent.AgentLoopRegistry.clear();
        }
    }
}
