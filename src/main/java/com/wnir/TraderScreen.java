package com.wnir;

import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.client.network.ClientPacketDistributor;

import java.util.List;
import java.util.Optional;

/**
 * Trader GUI (275×170, no player inventory).
 *
 * Layout:
 *   [7, 8]    Cellulose tank (20×150 frame, fills from bottom)
 *   [33, 8]   Trader list panel (100×140) — scrollable, one row per tracked trader
 *   [33, 152] "Rescan" button (100×12)
 *   [140, 8]  Trade list panel (128×155) — trades for the selected trader
 *
 * Trader list row (height 18):
 *   Gray tint if missCount > 0. Click to select.
 *
 * Trade list row (height 18):
 *   [checkbox][costA icon][+costB icon if present][→][result icon][done / failed]
 *   Checkbox click → TraderActionPayload ACTION_TOGGLE.
 *   If villager entity not loaded → "Not loaded" label.
 */
public class TraderScreen extends AbstractContainerScreen<TraderMenu> {

    // ── Geometry ──────────────────────────────────────────────────────────────

    private static final int W = 295, H = 190;

    // Fluid tank (column 1)
    private static final int TANK_X = 7, TANK_Y = 8, TANK_W = 20, TANK_H = 150;
    private static final int TANK_FILL_X = TANK_X + 2, TANK_FILL_Y = TANK_Y + 2;
    private static final int TANK_FILL_W = TANK_W - 4, TANK_FILL_H = TANK_H - 4;

    // Buffer column (column 2) — 1×9 slots, panel at x=29,y=8,w=20,h=164
    private static final int BUF_X = 29, BUF_Y = 8, BUF_W = 20, BUF_H = 164;

    // Trader list panel (column 3)
    private static final int TL_X = 53, TL_Y = 8, TL_W = 100, TL_H = 140;
    private static final int RESCAN_X = 53, RESCAN_Y = 152, RESCAN_W = 100, RESCAN_H = 14;
    private static final int ROW_H = 18;

    // Trade list panel (column 4)
    private static final int TR_X = 160, TR_Y = 8, TR_W = 128, TR_H = 174;

    // Trade button (small square, right side of trader row)
    private static final int TRADE_BTN_W  = 10;
    private static final int TRADE_BTN_H  = 10;

    // Colors
    private static final int COL_BG       = 0xFF3C3C3C;
    private static final int COL_PANEL    = 0xFF4A4A4A;
    private static final int COL_BORDER   = 0xFF1A1A1A;
    private static final int COL_SEL      = 0xFF5B7B5B;
    private static final int COL_HOVER    = 0xFF525252;
    private static final int COL_TEXT     = 0xFFE0E0E0;
    private static final int COL_GRAY     = 0xFF888888;
    private static final int COL_TANK_BG  = 0xFF1A1A2E;
    private static final int COL_CELLULOSE = 0xFFFFB3D9; // pale pink
    private static final int COL_CHECK_ON  = 0xFF55AA55;
    private static final int COL_CHECK_OFF = 0xFF555555;
    private static final int COL_ERROR     = 0xFFCC4444;
    private static final int COL_BTN       = 0xFF2D6E2D;
    private static final int COL_BTN_HOVER = 0xFF3FA83F;

    // ── State ─────────────────────────────────────────────────────────────────

    private int selectedTrader = -1;
    private int traderScroll   = 0;
    private int tradeScroll    = 0;

    private Button rescanButton;
    private Button giveXpButton;

    public TraderScreen(TraderMenu menu, Inventory inv, Component title) {
        super(menu, inv, title, W, H);
        this.inventoryLabelY = H + 10;
    }

    @Override
    protected void init() {
        super.init();
        rescanButton = addRenderableWidget(Button.builder(
            Component.translatable("gui.wnir.trader.rescan"),
            btn -> sendAction(TraderPayloads.TraderActionPayload.ACTION_RESCAN, -1, -1)
        ).bounds(leftPos + RESCAN_X, topPos + RESCAN_Y, RESCAN_W, RESCAN_H).build());
        giveXpButton = addRenderableWidget(Button.builder(
            Component.translatable("gui.wnir.trader.give_xp"),
            btn -> sendAction(TraderPayloads.TraderActionPayload.ACTION_GIVE_XP, -1, -1)
        ).bounds(leftPos + RESCAN_X, topPos + RESCAN_Y + RESCAN_H + 2, RESCAN_W, RESCAN_H).build());
    }

    // ── Rendering ─────────────────────────────────────────────────────────────

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partial) {
        // Update XP button label with current stored XP
        int xp = menu.getSyncedStoredXp();
        giveXpButton.setMessage(Component.translatable("gui.wnir.trader.give_xp")
            .append(Component.literal(" (" + xp + ")")));
        extractBackground(g, mouseX, mouseY, partial);
        super.extractRenderState(g, mouseX, mouseY, partial);
        extractTooltip(g, mouseX, mouseY);
    }

    @Override
    public void extractContents(GuiGraphicsExtractor g, int mouseX, int mouseY, float partial) {
        int lx = leftPos, ty = topPos;

        // Main background
        g.fill(lx, ty, lx + W, ty + H, COL_BG);

        // Title bar
        g.fill(lx, ty, lx + W, ty + 8, COL_BORDER);

        // Fluid tank
        drawTank(g, lx, ty, mouseX, mouseY);

        // Trader list panel
        drawPanel(g, lx + TL_X, ty + TL_Y, TL_W, TL_H);
        drawTraderList(g, lx, ty, mouseX, mouseY);

        // Trade list panel
        drawPanel(g, lx + TR_X, ty + TR_Y, TR_W, TR_H);
        drawTradeList(g, lx, ty, mouseX, mouseY);

        // Buffer column (1×9 slots, second column after the cellulose tank)
        drawPanel(g, lx + BUF_X, ty + BUF_Y, BUF_W, BUF_H);
        for (int i = 0; i < 9; i++) {
            int sx = lx + BUF_X + 1;
            int sy = ty + BUF_Y + 1 + i * 18;
            g.fill(sx, sy, sx + 16, sy + 16, 0xFF2A2A2A);
        }

        // Title
        g.text(font, this.title, lx + TL_X, ty - 2, COL_TEXT, false);
        super.extractContents(g, mouseX, mouseY, partial);
    }

    // ── Tank ──────────────────────────────────────────────────────────────────

    private void drawTank(GuiGraphicsExtractor g, int lx, int ty, int mx, int my) {
        // Frame
        g.fill(lx + TANK_X, ty + TANK_Y, lx + TANK_X + TANK_W, ty + TANK_Y + TANK_H, COL_BORDER);
        // Background
        g.fill(lx + TANK_FILL_X, ty + TANK_FILL_Y,
               lx + TANK_FILL_X + TANK_FILL_W, ty + TANK_FILL_Y + TANK_FILL_H, COL_TANK_BG);

        // Fill (from bottom, proportion of TANK_CAPACITY)
        int fluidMb = menu.getFluidMb();
        if (fluidMb > 0) {
            int fillH = (int)((long) TANK_FILL_H * fluidMb / TraderBlockEntity.TANK_CAPACITY);
            fillH = Math.max(1, fillH);
            int yOff = TANK_FILL_H - fillH;
            g.fill(lx + TANK_FILL_X, ty + TANK_FILL_Y + yOff,
                   lx + TANK_FILL_X + TANK_FILL_W, ty + TANK_FILL_Y + TANK_FILL_H,
                   COL_CELLULOSE);
        }

        // Tooltip on hover
        if (mx >= lx + TANK_X && mx < lx + TANK_X + TANK_W
         && my >= ty + TANK_Y && my < ty + TANK_Y + TANK_H) {
            g.setTooltipForNextFrame(
                Component.literal(fluidMb + " / " + TraderBlockEntity.TANK_CAPACITY + " mB"),
                mx, my);
        }
    }

    // ── Trader list ───────────────────────────────────────────────────────────

    private void drawPanel(GuiGraphicsExtractor g, int x, int y, int w, int h) {
        g.fill(x, y, x + w, y + h, COL_BORDER);
        g.fill(x + 1, y + 1, x + w - 1, y + h - 1, COL_PANEL);
    }

    private void drawTraderList(GuiGraphicsExtractor g, int lx, int ty, int mx, int my) {
        List<TraderPayloads.TraderEntry> traders = menu.getTraders();
        if (traders.isEmpty()) {
            g.text(font,
                Component.translatable("gui.wnir.trader.no_traders").withStyle(ChatFormatting.GRAY),
                lx + TL_X + 4, ty + TL_Y + 6, COL_GRAY, false);
            return;
        }

        int panelX = lx + TL_X + 1, panelY = ty + TL_Y + 1;
        int maxRows = (TL_H - 2) / ROW_H;

        for (int i = 0; i < maxRows; i++) {
            int idx = i + traderScroll;
            if (idx >= traders.size()) break;
            TraderPayloads.TraderEntry entry = traders.get(idx);

            int rowX = panelX, rowY = panelY + i * ROW_H;
            boolean hovered = mx >= rowX && mx < rowX + TL_W - 2
                           && my >= rowY && my < rowY + ROW_H;
            boolean selected = idx == selectedTrader;

            // Row background
            if (selected) {
                g.fill(rowX, rowY, rowX + TL_W - 2, rowY + ROW_H, COL_SEL);
            } else if (hovered) {
                g.fill(rowX, rowY, rowX + TL_W - 2, rowY + ROW_H, COL_HOVER);
            }

            // Name — gray if missed
            int textColor = entry.missCount() > 0 ? COL_GRAY : COL_TEXT;
            String displayName = entry.name().length() > 10
                ? entry.name().substring(0, 10) + "…"
                : entry.name();
            g.text(font, displayName, rowX + 4, rowY + (ROW_H - 8) / 2, textColor, false);

            // Miss indicator (shifted left to leave room for the trade button)
            if (entry.missCount() > 0) {
                g.text(font, "(" + entry.missCount() + ")",
                    rowX + TL_W - 38, rowY + (ROW_H - 8) / 2, COL_GRAY, false);
            }

            // Trade button — small square, opens villager's own trade screen
            int btnX = rowX + TL_W - 2 - TRADE_BTN_W - 2;
            int btnY = rowY + (ROW_H - TRADE_BTN_H) / 2;
            boolean btnHovered = mx >= btnX && mx < btnX + TRADE_BTN_W
                              && my >= btnY && my < btnY + TRADE_BTN_H;
            g.fill(btnX, btnY, btnX + TRADE_BTN_W, btnY + TRADE_BTN_H,
                   btnHovered ? COL_BTN_HOVER : COL_BTN);
            // Star/glow icon: cross + diagonals in yellow
            g.fill(btnX + 4, btnY + 1, btnX + 6, btnY + 9, 0xFFFFDD44); // vertical
            g.fill(btnX + 1, btnY + 4, btnX + 9, btnY + 6, 0xFFFFDD44); // horizontal
            g.fill(btnX + 2, btnY + 2, btnX + 4, btnY + 4, 0xFFFFDD44); // TL diagonal
            g.fill(btnX + 6, btnY + 6, btnX + 8, btnY + 8, 0xFFFFDD44); // BR diagonal
            g.fill(btnX + 6, btnY + 2, btnX + 8, btnY + 4, 0xFFFFDD44); // TR diagonal
            g.fill(btnX + 2, btnY + 6, btnX + 4, btnY + 8, 0xFFFFDD44); // BL diagonal
            if (btnHovered) {
                g.setTooltipForNextFrame(
                    net.minecraft.network.chat.Component.translatable("gui.wnir.trader.glow_villager"),
                    mx, my);
            }
        }
    }

    // ── Trade list ────────────────────────────────────────────────────────────

    private void drawTradeList(GuiGraphicsExtractor g, int lx, int ty, int mx, int my) {
        if (selectedTrader < 0 || selectedTrader >= menu.getTraders().size()) {
            g.text(font,
                Component.translatable("gui.wnir.trader.select_trader").withStyle(ChatFormatting.GRAY),
                lx + TR_X + 4, ty + TR_Y + 6, COL_GRAY, false);
            return;
        }

        TraderPayloads.TraderEntry entry = menu.getTraders().get(selectedTrader);
        List<TraderPayloads.SyncedOffer> offers = entry.offers();

        if (offers.isEmpty()) {
            g.text(font,
                Component.translatable("gui.wnir.trader.not_loaded").withStyle(ChatFormatting.RED),
                lx + TR_X + 4, ty + TR_Y + 6, COL_ERROR, false);
            return;
        }

        int panelX = lx + TR_X + 2, panelY = ty + TR_Y + 2;
        int maxRows = (TR_H - 4) / ROW_H;

        for (int i = 0; i < maxRows; i++) {
            int idx = i + tradeScroll;
            if (idx >= offers.size()) break;
            TraderPayloads.SyncedOffer offer = offers.get(idx);

            int rowY = panelY + i * ROW_H;
            boolean outOfStock = offer.outOfStock();

            // Checkbox (8×8)
            boolean checked = entry.isChecked(idx);
            g.fill(panelX, rowY + 5, panelX + 8, rowY + 13,
                   checked ? COL_CHECK_ON : COL_CHECK_OFF);
            if (checked) {
                // Checkmark X strokes
                g.fill(panelX + 1, rowY + 6, panelX + 3, rowY + 8, COL_TEXT);
                g.fill(panelX + 2, rowY + 7, panelX + 7, rowY + 12, COL_TEXT);
            }

            // Detect checkbox click area
            if (mx >= panelX && mx < panelX + 8 && my >= rowY + 5 && my < rowY + 13) {
                // hover highlight
                g.fill(panelX, rowY + 5, panelX + 8, rowY + 13,
                       0x55FFFFFF); // translucent highlight
            }

            int cx = panelX + 10;

            // costA (16×16 item)
            ItemStack costA = offer.costA();
            g.item(costA, cx, rowY + 1);
            if (costA.getCount() > 1) {
                g.text(font, String.valueOf(costA.getCount()),
                    cx + 10, rowY + 9, 0xFFFFFF, true);
            }
            cx += 18;

            // costB (optional)
            ItemStack costB = offer.costB();
            if (!costB.isEmpty()) {
                g.text(font, "+", cx, rowY + 5, COL_GRAY, false);
                cx += 6;
                g.item(costB, cx, rowY + 1);
                if (costB.getCount() > 1) {
                    g.text(font, String.valueOf(costB.getCount()),
                        cx + 10, rowY + 9, 0xFFFFFF, true);
                }
                cx += 18;
            }

            // Arrow
            g.text(font, "→", cx, rowY + 5, outOfStock ? COL_ERROR : COL_GRAY, false);
            cx += 10;

            // result
            ItemStack result = offer.result();
            g.item(result, cx, rowY + 1);
            if (result.getCount() > 1) {
                g.text(font, String.valueOf(result.getCount()),
                    cx + 10, rowY + 9, 0xFFFFFF, true);
            }
            cx += 20;

            // done / failed counters (right-aligned in panel)
            int done   = idx < entry.tradesDone().size()   ? entry.tradesDone().get(idx)   : 0;
            int failed = idx < entry.tradesFailed().size() ? entry.tradesFailed().get(idx) : 0;
            String stat = done + "/" + failed;
            int statX = lx + TR_X + TR_W - 4 - font.width(stat);
            g.text(font, stat, statX, rowY + 5, COL_GRAY, false);

            // Out-of-stock strikethrough tint
            if (outOfStock) {
                g.fill(panelX + 10, rowY + 8, cx, rowY + 10, 0x88CC4444);
            }
        }
    }

    // ── Mouse clicks ──────────────────────────────────────────────────────────

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean consumed) {
        if (event.button() == 0) {
            double mx = event.x(), my = event.y();
            int lx = leftPos, ty = topPos;

            // Trader list — check trade button before row-select
            if (mx >= lx + TL_X + 1 && mx < lx + TL_X + TL_W - 1
             && my >= ty + TL_Y + 1 && my < ty + TL_Y + TL_H - 1) {
                int row = (int)(my - ty - TL_Y - 1) / ROW_H + traderScroll;
                if (row >= 0 && row < menu.getTraders().size()) {
                    // Trade button hit test
                    int panelX = lx + TL_X + 1;
                    int rowY   = ty + TL_Y + 1 + (row - traderScroll) * ROW_H;
                    int btnX   = panelX + TL_W - 2 - TRADE_BTN_W - 2;
                    int btnY   = rowY + (ROW_H - TRADE_BTN_H) / 2;
                    if (mx >= btnX && mx < btnX + TRADE_BTN_W
                     && my >= btnY && my < btnY + TRADE_BTN_H) {
                        sendAction(TraderPayloads.TraderActionPayload.ACTION_OPEN_TRADING, row, -1);
                        return true;
                    }
                    // Row select
                    selectedTrader = (selectedTrader == row) ? -1 : row;
                    tradeScroll = 0;
                    return true;
                }
            }

            // Trade list checkbox click
            if (selectedTrader >= 0 && selectedTrader < menu.getTraders().size()
             && mx >= lx + TR_X + 2 && mx < lx + TR_X + 2 + 8) {
                int panelY = topPos + TR_Y + 2;
                int row = ((int) my - panelY) / ROW_H + tradeScroll;
                int rowY = panelY + (row - tradeScroll) * ROW_H;
                if (my >= rowY + 5 && my < rowY + 13 && row >= 0) {
                    var entry = menu.getTraders().get(selectedTrader);
                    if (row < entry.offers().size()) {
                        sendAction(TraderPayloads.TraderActionPayload.ACTION_TOGGLE,
                                   selectedTrader, row);
                        return true;
                    }
                }
            }
        }
        return super.mouseClicked(event, consumed);
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double scrollX, double scrollY) {
        int lx = leftPos, ty = topPos;
        int delta = scrollY > 0 ? -1 : 1;

        if (mx >= lx + TL_X && mx < lx + TL_X + TL_W
         && my >= ty + TL_Y && my < ty + TL_Y + TL_H) {
            int maxScroll = Math.max(0, menu.getTraders().size() - (TL_H - 2) / ROW_H);
            traderScroll = Math.max(0, Math.min(traderScroll + delta, maxScroll));
            return true;
        }
        if (mx >= lx + TR_X && mx < lx + TR_X + TR_W
         && my >= ty + TR_Y && my < ty + TR_Y + TR_H) {
            int tradeCount = (selectedTrader >= 0 && selectedTrader < menu.getTraders().size())
                ? menu.getTraders().get(selectedTrader).offers().size() : 0;
            int maxScroll = Math.max(0, tradeCount - (TR_H - 4) / ROW_H);
            tradeScroll = Math.max(0, Math.min(tradeScroll + delta, maxScroll));
            return true;
        }
        return super.mouseScrolled(mx, my, scrollX, scrollY);
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private void sendAction(int action, int traderIdx, int tradeIdx) {
        ClientPacketDistributor.sendToServer(new TraderPayloads.TraderActionPayload(
            menu.containerId, action, traderIdx, tradeIdx));
    }

}
