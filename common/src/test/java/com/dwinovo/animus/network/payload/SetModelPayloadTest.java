package com.dwinovo.animus.network.payload;

import io.netty.buffer.Unpooled;
import net.minecraft.core.RegistryAccess;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.resources.Identifier;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Wire-format round-trip tests for {@link SetModelPayload}. The codec is part
 * of the public contract between client and server — adding / reordering /
 * removing fields here breaks deployed clients silently, so we lock the
 * shape with these tests.
 */
class SetModelPayloadTest {

    private static RegistryFriendlyByteBuf newBuf() {
        return new RegistryFriendlyByteBuf(Unpooled.buffer(), RegistryAccess.EMPTY);
    }

    private static SetModelPayload roundtrip(SetModelPayload original) {
        RegistryFriendlyByteBuf buf = newBuf();
        SetModelPayload.STREAM_CODEC.encode(buf, original);
        return SetModelPayload.STREAM_CODEC.decode(buf);
    }

    @Test
    void builtinModelRoundtrip() {
        SetModelPayload original = new SetModelPayload(
                42, Identifier.fromNamespaceAndPath("animus", "hachiware"));
        SetModelPayload decoded = roundtrip(original);
        assertEquals(original.entityId(), decoded.entityId());
        assertEquals(original.modelKey(), decoded.modelKey());
    }

    @Test
    void userNamespaceRoundtrip() {
        SetModelPayload original = new SetModelPayload(
                0, Identifier.fromNamespaceAndPath("animus_user", "my_custom_skin"));
        SetModelPayload decoded = roundtrip(original);
        assertEquals(original.entityId(), decoded.entityId());
        assertEquals(original.modelKey(), decoded.modelKey());
    }

    @Test
    void edgeCaseEntityIds() {
        for (int id : new int[]{0, 1, -1, Integer.MAX_VALUE, Integer.MIN_VALUE, 0xFFFF}) {
            SetModelPayload original = new SetModelPayload(
                    id, Identifier.fromNamespaceAndPath("animus", "test"));
            SetModelPayload decoded = roundtrip(original);
            assertEquals(id, decoded.entityId(), "round-trip failed for entityId=" + id);
        }
    }

    @Test
    void multiplePayloadsShareBuffer() {
        // Encoder / decoder should be stateless — encoding two payloads in a
        // row and decoding back gives the same two values.
        RegistryFriendlyByteBuf buf = newBuf();
        SetModelPayload a = new SetModelPayload(
                10, Identifier.fromNamespaceAndPath("animus", "a"));
        SetModelPayload b = new SetModelPayload(
                20, Identifier.fromNamespaceAndPath("animus_user", "b"));
        SetModelPayload.STREAM_CODEC.encode(buf, a);
        SetModelPayload.STREAM_CODEC.encode(buf, b);
        SetModelPayload decodedA = SetModelPayload.STREAM_CODEC.decode(buf);
        SetModelPayload decodedB = SetModelPayload.STREAM_CODEC.decode(buf);
        assertEquals(a, decodedA);
        assertEquals(b, decodedB);
    }
}
