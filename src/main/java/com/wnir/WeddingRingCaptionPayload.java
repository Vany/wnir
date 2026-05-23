package com.wnir;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * Server → Client: display a caption text below screen center for 5 seconds.
 * Registration: RegisterPayloadHandlersEvent in WnirMod.
 */
public record WeddingRingCaptionPayload(String text) implements CustomPacketPayload {

    public static final Type<WeddingRingCaptionPayload> TYPE = new Type<>(
        Identifier.fromNamespaceAndPath(WnirMod.MOD_ID, "wedding_ring_caption")
    );

    public static final StreamCodec<FriendlyByteBuf, WeddingRingCaptionPayload> STREAM_CODEC = StreamCodec.of(
        (buf, p) -> buf.writeUtf(p.text()),
        buf -> new WeddingRingCaptionPayload(buf.readUtf(Short.MAX_VALUE))
    );

    @Override
    public Type<? extends CustomPacketPayload> type() { return TYPE; }

    public static void send(ServerPlayer player, String text) {
        PacketDistributor.sendToPlayer(player, new WeddingRingCaptionPayload(text));
    }

    public static void handle(WeddingRingCaptionPayload p, IPayloadContext ctx) {
        ctx.enqueueWork(() -> WeddingRingCaptionRenderer.addCaption(p.text()));
    }
}
