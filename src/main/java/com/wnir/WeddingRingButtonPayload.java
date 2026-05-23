package com.wnir;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/** Client → Server: player clicked a button in the Wedding Ring UI. */
public record WeddingRingButtonPayload(int buttonId) implements CustomPacketPayload {

    public static final Type<WeddingRingButtonPayload> TYPE = new Type<>(
        Identifier.fromNamespaceAndPath(WnirMod.MOD_ID, "wedding_ring_button")
    );

    public static final StreamCodec<FriendlyByteBuf, WeddingRingButtonPayload> STREAM_CODEC = StreamCodec.of(
        (buf, p) -> buf.writeByte(p.buttonId()),
        buf -> new WeddingRingButtonPayload(buf.readByte())
    );

    @Override
    public Type<? extends CustomPacketPayload> type() { return TYPE; }

    public static void handle(WeddingRingButtonPayload p, IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            if (!(ctx.player() instanceof ServerPlayer player)) return;
            if (!(player.containerMenu instanceof WeddingRingMenu menu)) return;
            menu.onButtonClick(p.buttonId());
        });
    }
}
