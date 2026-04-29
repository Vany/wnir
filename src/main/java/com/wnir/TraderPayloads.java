package com.wnir;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Custom network payloads for the Trader block GUI.
 *
 * TraderSyncPayload  (Server → Client) — full trader state on menu open and after rescan.
 * TraderActionPayload (Client → Server) — rescan request or checkbox toggle.
 *
 * Registration: modEventBus → RegisterPayloadsEvent in WnirMod.
 */
public class TraderPayloads {

    // ─────────────────────────────────────────────────────────────────────────
    // Server → Client: full state sync
    // ─────────────────────────────────────────────────────────────────────────

    public record TraderSyncPayload(
        int containerId,
        int fluidMb,
        int storedXp,
        List<TraderEntry> entries
    ) implements CustomPacketPayload {

        public static final Type<TraderSyncPayload> TYPE = new Type<>(
            Identifier.fromNamespaceAndPath(WnirMod.MOD_ID, "trader_sync")
        );

        public static final StreamCodec<RegistryFriendlyByteBuf, TraderSyncPayload> STREAM_CODEC = StreamCodec.of(
            (buf, p) -> {
                buf.writeInt(p.containerId());
                buf.writeInt(p.fluidMb());
                buf.writeInt(p.storedXp());
                buf.writeInt(p.entries().size());
                for (TraderEntry e : p.entries()) TraderEntry.encode(buf, e);
            },
            buf -> {
                int cid   = buf.readInt();
                int fluid = buf.readInt();
                int xp    = buf.readInt();
                int n     = buf.readInt();
                List<TraderEntry> entries = new ArrayList<>(n);
                for (int i = 0; i < n; i++) entries.add(TraderEntry.decode(buf));
                return new TraderSyncPayload(cid, fluid, xp, entries);
            }
        );

        @Override
        public Type<? extends CustomPacketPayload> type() { return TYPE; }

        /** Applied on the client main thread. Finds the open TraderMenu and updates it. */
        public static void handle(TraderSyncPayload p, IPayloadContext ctx) {
            ctx.enqueueWork(() -> {
                var mc = net.minecraft.client.Minecraft.getInstance();
                if (mc.player != null
                        && mc.player.containerMenu instanceof TraderMenu menu
                        && menu.containerId == p.containerId()) {
                    menu.setTraderData(p.fluidMb(), p.storedXp(), p.entries());
                }
            });
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Client → Server: action request
    // ─────────────────────────────────────────────────────────────────────────

    public record TraderActionPayload(
        int containerId,
        int action,     // ACTION_RESCAN or ACTION_TOGGLE
        int traderIdx,
        int tradeIdx
    ) implements CustomPacketPayload {

        public static final int ACTION_RESCAN        = 0;
        public static final int ACTION_TOGGLE        = 1;
        public static final int ACTION_GIVE_XP       = 2;
        public static final int ACTION_OPEN_TRADING  = 3;

        public static final Type<TraderActionPayload> TYPE = new Type<>(
            Identifier.fromNamespaceAndPath(WnirMod.MOD_ID, "trader_action")
        );

        public static final StreamCodec<FriendlyByteBuf, TraderActionPayload> STREAM_CODEC = StreamCodec.of(
            (buf, p) -> {
                buf.writeInt(p.containerId());
                buf.writeInt(p.action());
                buf.writeInt(p.traderIdx());
                buf.writeInt(p.tradeIdx());
            },
            buf -> new TraderActionPayload(buf.readInt(), buf.readInt(), buf.readInt(), buf.readInt())
        );

        @Override
        public Type<? extends CustomPacketPayload> type() { return TYPE; }

        /** Applied on the server main thread. */
        public static void handle(TraderActionPayload p, IPayloadContext ctx) {
            ctx.enqueueWork(() -> {
                var player = ctx.player();
                if (!(player.level() instanceof net.minecraft.server.level.ServerLevel level)) return;
                if (!(player.containerMenu instanceof TraderMenu menu
                        && menu.containerId == p.containerId())) return;
                TraderBlockEntity be = menu.getBlockEntity();
                if (be == null) return;

                switch (p.action()) {
                    case ACTION_RESCAN -> {
                        be.performRescan(level);
                        sendSync(be, player, level, p.containerId());
                    }
                    case ACTION_TOGGLE -> {
                        be.toggleTrade(p.traderIdx(), p.tradeIdx());
                        sendSync(be, player, level, p.containerId());
                    }
                    case ACTION_GIVE_XP -> {
                        if (player instanceof net.minecraft.server.level.ServerPlayer sp) {
                            be.giveXpToPlayer(sp);
                            sendSync(be, player, level, p.containerId());
                        }
                    }
                    case ACTION_OPEN_TRADING -> {
                        if (be.storedXp < 1) return;
                        int idx = p.traderIdx();
                        if (idx < 0 || idx >= be.traders.size()) return;
                        var td = be.traders.get(idx);
                        var entity = level.getEntity(td.uuid);
                        if (entity instanceof net.minecraft.world.entity.LivingEntity villager) {
                            villager.addEffect(new net.minecraft.world.effect.MobEffectInstance(
                                net.minecraft.world.effect.MobEffects.GLOWING, 1200, 0));
                            be.storedXp--;
                            be.setChanged();
                            if (player instanceof net.minecraft.server.level.ServerPlayer sp) {
                                sendSync(be, player, level, p.containerId());
                            }
                        }
                    }
                }
            });
        }

        private static void sendSync(TraderBlockEntity be,
                                     net.minecraft.world.entity.player.Player player,
                                     net.minecraft.server.level.ServerLevel level,
                                     int menuId) {
            if (player instanceof net.minecraft.server.level.ServerPlayer sp) {
                net.neoforged.neoforge.network.PacketDistributor.sendToPlayer(
                    sp, be.buildSyncPacket(level, menuId));
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Shared data type: one tracked trader's entry
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * One villager trade offer snapshot — safe to use client-side.
     */
    public record SyncedOffer(ItemStack costA, ItemStack costB, ItemStack result, boolean outOfStock) {
        static void encode(RegistryFriendlyByteBuf buf, SyncedOffer o) {
            writeItem(buf, o.costA());
            writeItem(buf, o.costB());
            writeItem(buf, o.result());
            buf.writeBoolean(o.outOfStock());
        }
        static SyncedOffer decode(RegistryFriendlyByteBuf buf) {
            return new SyncedOffer(readItem(buf), readItem(buf), readItem(buf), buf.readBoolean());
        }
        private static void writeItem(RegistryFriendlyByteBuf buf, ItemStack stack) {
            buf.writeBoolean(!stack.isEmpty());
            if (!stack.isEmpty()) ItemStack.STREAM_CODEC.encode(buf, stack);
        }
        private static ItemStack readItem(RegistryFriendlyByteBuf buf) {
            return buf.readBoolean() ? ItemStack.STREAM_CODEC.decode(buf) : ItemStack.EMPTY;
        }
    }

    /**
     * Client-side snapshot of a TrackedTrader.
     * tradesDone / tradesFailed have MAX_TRADES entries (padded with 0s).
     * offers is populated from the live villager at sync time; empty if villager not loaded.
     */
    public record TraderEntry(
        UUID         uuid,
        String       name,
        int          missCount,
        int          checkedBits,
        List<Integer> tradesDone,
        List<Integer> tradesFailed,
        List<SyncedOffer> offers
    ) {
        static void encode(RegistryFriendlyByteBuf buf, TraderEntry e) {
            buf.writeUUID(e.uuid());
            buf.writeUtf(e.name(), 256);
            buf.writeInt(e.missCount());
            buf.writeInt(e.checkedBits());
            buf.writeInt(e.tradesDone().size());
            for (int d : e.tradesDone()) buf.writeInt(d);
            buf.writeInt(e.tradesFailed().size());
            for (int f : e.tradesFailed()) buf.writeInt(f);
            buf.writeInt(e.offers().size());
            for (SyncedOffer o : e.offers()) SyncedOffer.encode(buf, o);
        }

        static TraderEntry decode(RegistryFriendlyByteBuf buf) {
            UUID   uuid  = buf.readUUID();
            String name  = buf.readUtf(256);
            int    miss  = buf.readInt();
            int    check = buf.readInt();
            int    dn    = buf.readInt();
            List<Integer> done = new ArrayList<>(dn);
            for (int i = 0; i < dn; i++) done.add(buf.readInt());
            int fn = buf.readInt();
            List<Integer> failed = new ArrayList<>(fn);
            for (int i = 0; i < fn; i++) failed.add(buf.readInt());
            int on = buf.readInt();
            List<SyncedOffer> offers = new ArrayList<>(on);
            for (int i = 0; i < on; i++) offers.add(SyncedOffer.decode(buf));
            return new TraderEntry(uuid, name, miss, check, done, failed, offers);
        }

        public boolean isChecked(int idx) {
            return idx >= 0 && idx < 32 && (checkedBits & (1 << idx)) != 0;
        }
    }
}
