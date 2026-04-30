package DIV.credit.client.screen;

import DIV.credit.client.history.HistoryEntry;
import DIV.credit.client.history.HistoryStore;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.Nullable;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * /credit history で開く画面。push 単位で議事録を時系列表示。
 * 左ペイン: push entry 一覧 (時刻 + 件数)、選択された entry の詳細を右ペインに展開。
 * 詳細は (操作 / recipe ID / 出力ファイル path) の list。
 */
public class HistoryScreen extends Screen {

    private static final DateTimeFormatter TIME_FMT =
        DateTimeFormatter.ofPattern("MM-dd HH:mm:ss").withZone(ZoneId.systemDefault());

    private static final int LEFT_W      = 180;
    private static final int ROW_H       = 18;
    private static final int HEADER_H    = 60;  // タブ追加で +20
    private static final int FOOTER_H    = 40;
    private static final int DETAIL_PAD  = 10;
    private static final int TAB_H       = 18;

    @Nullable private final Screen parent;
    private int leftX, leftY, leftH;
    private int rightX, rightY, rightW, rightH;
    private int visibleRows;
    private int scrollOffset = 0;
    private int detailScroll = 0;
    @Nullable private HistoryEntry selected;
    /** 表示タブ。デフォルトは PUSHED。 */
    private HistoryEntry.Kind currentTab = HistoryEntry.Kind.STAGED;  // staged 件を最優先で見せる
    private int tabPushedX = -1, tabImmediateX = -1, tabStagedX = -1;
    private int tabY = -1, tabPushedW = 0, tabImmediateW = 0, tabStagedW = 0;
    /** detail ペインの 1 行のクリック領域。renderDetail 中に登録、mouseClicked で検査。 */
    private final java.util.List<DetailHotspot> hotspots = new java.util.ArrayList<>();

    private record DetailHotspot(int x, int y, int w, int h, HistoryEntry.Item item) {}

    public HistoryScreen(@Nullable Screen parent) {
        super(Component.translatable("gui.credit.history.title"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        super.init();
        DIV.credit.Credit.LOGGER.info("[CraftPattern] HistoryScreen.init: currentTab={}, StagingArea.size={}, HistoryStore.size={}",
            currentTab,
            DIV.credit.client.staging.StagingArea.INSTANCE.size(),
            DIV.credit.client.history.HistoryStore.INSTANCE.size());
        int margin = 12;
        this.leftX = margin;
        this.leftY = HEADER_H;
        this.leftH = this.height - HEADER_H - FOOTER_H;
        this.rightX = leftX + LEFT_W + margin;
        this.rightY = HEADER_H;
        this.rightW = this.width - rightX - margin;
        this.rightH = leftH;
        this.visibleRows = Math.max(1, leftH / ROW_H);

        // 自動的に先頭 entry 選択 (current tab フィルタ後)
        autoSelectFirst();

        addRenderableWidget(Button.builder(
                Component.translatable("gui.credit.history.close"),
                b -> onClose())
            .bounds(this.width / 2 - 50, this.height - FOOTER_H + 10, 100, 20)
            .build());
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        renderBackground(g);
        super.render(g, mouseX, mouseY, partialTick);

        // Header (タイトル)
        g.drawCenteredString(font, getTitle(), this.width / 2, 8, 0xFFFFFFFF);

        // Tabs (左上)
        renderTabs(g, mouseX, mouseY);

        // Summary (タブ右側または中央)
        List<HistoryEntry> filtered = filteredEntries();
        Component summary = Component.translatable("gui.credit.history.summary",
            filtered.size());
        int sx = this.width / 2;
        g.drawCenteredString(font, summary, sx, 22 + TAB_H + 2, 0xFFAAAAAA);

        // Left pane bg
        g.fill(leftX - 2, leftY - 2, leftX + LEFT_W + 2, leftY + leftH + 2, 0xFF1A1A2E);
        // Right pane bg
        g.fill(rightX - 2, rightY - 2, rightX + rightW + 2, rightY + rightH + 2, 0xFF161628);

        if (filtered.isEmpty()) {
            // tab 別に分かりやすい空メッセージ
            String emptyKey = switch (currentTab) {
                case STAGED    -> "gui.credit.history.empty.staged";
                case PUSHED    -> "gui.credit.history.empty.pushed";
                case IMMEDIATE -> "gui.credit.history.empty.immediate";
            };
            int totalPushed = (int) HistoryStore.INSTANCE.all().stream()
                .filter(e -> e.kind == HistoryEntry.Kind.PUSHED).count();
            int totalImmediate = (int) HistoryStore.INSTANCE.all().stream()
                .filter(e -> e.kind == HistoryEntry.Kind.IMMEDIATE).count();
            int cy = leftY + leftH / 2 - 10;
            g.drawCenteredString(font, Component.translatable(emptyKey),
                this.width / 2, cy, 0xFFAAAAAA);
            // STAGED tab で空の時、他タブへの案内
            if (currentTab == HistoryEntry.Kind.STAGED && (totalPushed > 0 || totalImmediate > 0)) {
                String hintKey = (totalPushed > 0 && totalImmediate > 0)
                    ? "gui.credit.history.empty.staged.hint_both"
                    : (totalPushed > 0 ? "gui.credit.history.empty.staged.hint_pushed"
                                       : "gui.credit.history.empty.staged.hint_immediate");
                g.drawCenteredString(font,
                    Component.translatable(hintKey, totalPushed, totalImmediate)
                        .withStyle(ChatFormatting.DARK_GRAY),
                    this.width / 2, cy + 14, 0xFF777777);
            }
            return;
        }

        int maxScroll = Math.max(0, filtered.size() - visibleRows);
        if (scrollOffset > maxScroll) scrollOffset = maxScroll;
        if (scrollOffset < 0) scrollOffset = 0;

        for (int i = 0; i < visibleRows; i++) {
            int idx = scrollOffset + i;
            if (idx >= filtered.size()) break;
            renderEntryRow(g, filtered.get(idx), leftY + i * ROW_H, mouseX, mouseY);
        }
        renderDetail(g, mouseX, mouseY);
    }

    private void renderTabs(GuiGraphics g, int mouseX, int mouseY) {
        // count バッジ付きラベル
        int stagedCount = DIV.credit.client.staging.StagingArea.INSTANCE.size();
        Component stagedLabel = Component.translatable("gui.credit.history.tab.staged",
            stagedCount);
        Component pushedLabel = Component.translatable("gui.credit.history.tab.pushed");
        Component immLabel    = Component.translatable("gui.credit.history.tab.immediate");
        tabStagedW    = font.width(stagedLabel) + 12;
        tabPushedW    = font.width(pushedLabel) + 12;
        tabImmediateW = font.width(immLabel)    + 12;
        tabStagedX    = 12;
        tabPushedX    = tabStagedX + tabStagedW + 4;
        tabImmediateX = tabPushedX + tabPushedW + 4;
        tabY          = 22;
        drawTab(g, tabStagedX, tabY, tabStagedW, stagedLabel,
            currentTab == HistoryEntry.Kind.STAGED, mouseX, mouseY);
        drawTab(g, tabPushedX, tabY, tabPushedW, pushedLabel,
            currentTab == HistoryEntry.Kind.PUSHED, mouseX, mouseY);
        drawTab(g, tabImmediateX, tabY, tabImmediateW, immLabel,
            currentTab == HistoryEntry.Kind.IMMEDIATE, mouseX, mouseY);
    }

    private void drawTab(GuiGraphics g, int x, int y, int w, Component label,
                         boolean active, int mouseX, int mouseY) {
        boolean hover = mouseX >= x && mouseX < x + w && mouseY >= y && mouseY < y + TAB_H;
        int bg = active ? 0xFF334466 : (hover ? 0xCC2A2A40 : 0x88202040);
        g.fill(x, y, x + w, y + TAB_H, bg);
        int fg = active ? 0xFFFFFFFF : (hover ? 0xFFCCCCCC : 0xFF888888);
        g.drawString(font, label, x + 6, y + 5, fg, false);
    }

    private List<HistoryEntry> filteredEntries() {
        // STAGED タブ: StagingArea を動的に HistoryEntry 形式に変換 (永続化なし)
        if (currentTab == HistoryEntry.Kind.STAGED) {
            return buildStagedEntries();
        }
        List<HistoryEntry> all = HistoryStore.INSTANCE.all();
        List<HistoryEntry> out = new java.util.ArrayList<>();
        for (HistoryEntry e : all) if (e.kind == currentTab) out.add(e);
        return out;
    }

    /**
     * StagingArea の内容を HistoryEntry 形式に変換。
     * 全 staged を 1 entry に集約 (immediate と同じ流儀)。filePath には
     * commit 状態 ([committed]/[pending]) を入れて hover で見せる。
     */
    private List<HistoryEntry> buildStagedEntries() {
        var staged = DIV.credit.client.staging.StagingArea.INSTANCE.all();
        DIV.credit.Credit.LOGGER.info("[CraftPattern] HistoryScreen.buildStagedEntries: StagingArea has {} items", staged.size());
        if (staged.isEmpty()) return List.of();
        List<HistoryEntry.Item> items = new java.util.ArrayList<>(staged.size());
        long maxTs = 0;
        for (var c : staged) {
            String pseudoFile = (c.committed ? "[committed] " : "[pending] ") + c.modid + "/" + c.kind.fileName + ".js";
            items.add(new HistoryEntry.Item(c.kind, c.modid, c.recipeId, pseudoFile, true, c.jeiCategoryUid));
            if (c.timestamp > maxTs) maxTs = c.timestamp;
        }
        return List.of(new HistoryEntry(maxTs, HistoryEntry.Kind.STAGED, items));
    }

    private void autoSelectFirst() {
        List<HistoryEntry> filtered = filteredEntries();
        DIV.credit.Credit.LOGGER.info("[CraftPattern] HistoryScreen.autoSelectFirst: tab={} → filtered.size={}",
            currentTab, filtered.size());
        selected = filtered.isEmpty() ? null : filtered.get(0);
        scrollOffset = 0;
        detailScroll = 0;
    }

    private void renderEntryRow(GuiGraphics g, HistoryEntry e, int y, int mouseX, int mouseY) {
        boolean hover = mouseX >= leftX && mouseX < leftX + LEFT_W
                     && mouseY >= y && mouseY < y + ROW_H;
        boolean isSel = (e == selected);
        if (isSel) g.fill(leftX, y, leftX + LEFT_W, y + ROW_H, 0xFF334466);
        else if (hover) g.fill(leftX, y, leftX + LEFT_W, y + ROW_H, 0x33FFFFFF);

        String ts = TIME_FMT.format(Instant.ofEpochMilli(e.timestamp));
        g.drawString(font, ts, leftX + 6, y + 5, 0xFFCCCCCC, false);
        String count = e.items.size() + " items";
        g.drawString(font, count, leftX + LEFT_W - font.width(count) - 6, y + 5, 0xFFAAAAAA, false);
    }

    private void renderDetail(GuiGraphics g, int mouseX, int mouseY) {
        hotspots.clear();
        if (selected == null) {
            g.drawCenteredString(font,
                Component.translatable("gui.credit.history.no_selection")
                    .withStyle(ChatFormatting.DARK_GRAY),
                rightX + rightW / 2, rightY + rightH / 2, 0xFF666666);
            return;
        }
        int y = rightY + DETAIL_PAD;
        String header = TIME_FMT.format(Instant.ofEpochMilli(selected.timestamp))
            + "  (" + selected.items.size() + " items)";
        g.drawString(font, header, rightX + DETAIL_PAD, y, 0xFFFFFFFF, false);
        y += 14;
        g.fill(rightX + DETAIL_PAD, y, rightX + rightW - DETAIL_PAD, y + 1, 0xFF444466);
        y += 4;

        int maxY = rightY + rightH - DETAIL_PAD;
        int lineH = font.lineHeight + 2;
        int visibleLines = Math.max(1, (maxY - y) / lineH);
        int totalLines = selected.items.size();
        int maxDetailScroll = Math.max(0, totalLines - visibleLines);
        if (detailScroll > maxDetailScroll) detailScroll = maxDetailScroll;
        if (detailScroll < 0) detailScroll = 0;

        for (int i = 0; i < visibleLines; i++) {
            int idx = detailScroll + i;
            if (idx >= selected.items.size()) break;
            HistoryEntry.Item it = selected.items.get(idx);
            int kindColor = switch (it.kind) {
                case ADD    -> 0xFF66FF66;
                case EDIT   -> 0xFF66CCFF;
                case DELETE -> 0xFFFF6666;
            };
            String kindStr = "[" + it.kind.name() + "]";
            g.drawString(font, kindStr, rightX + DETAIL_PAD, y, kindColor, false);
            int ix = rightX + DETAIL_PAD + font.width(kindStr) + 6;
            String idStr = it.recipeId;
            int idMaxW = rightX + rightW - ix - DETAIL_PAD;
            String shown = idStr;
            if (font.width(shown) > idMaxW) {
                while (font.width(shown + "...") > idMaxW && shown.length() > 4) shown = shown.substring(0, shown.length() - 1);
                shown += "...";
            }
            int idShownW = font.width(shown);
            // クリック可能か: DELETE 以外 + success
            boolean clickable = it.kind != DIV.credit.client.io.ScriptWriter.OperationKind.DELETE && it.success;
            boolean hoverId = mouseX >= ix && mouseX < ix + idShownW && mouseY >= y && mouseY < y + lineH;
            int color;
            if (!it.success)        color = 0xFFFF8888;
            else if (!clickable)    color = 0xFF888888;  // DELETE は灰色
            else if (hoverId)       color = 0xFFFFFF88;  // hover は黄色 (clickable hint)
            else                    color = 0xFFFFFFFF;
            g.drawString(font, shown, ix, y, color, false);
            // clickable な行 = underline で hint
            if (clickable && hoverId) {
                g.fill(ix, y + lineH - 2, ix + idShownW, y + lineH - 1, color);
            }
            // hotspot 登録 (DELETE でも path tooltip 用に登録するが click は openRecipeId だけ実行)
            hotspots.add(new DetailHotspot(ix, y, idShownW, lineH, it));

            // ファイル path tooltip (recipe ID hover 外、行内なら出す — 既存挙動)
            boolean rowHover = mouseX >= rightX + DETAIL_PAD && mouseX < rightX + rightW - DETAIL_PAD
                            && mouseY >= y && mouseY < y + lineH;
            if (rowHover && !hoverId && it.filePath != null) {
                g.renderTooltip(font, Component.literal(it.filePath), mouseX, mouseY);
            } else if (hoverId && clickable) {
                g.renderTooltip(font, Component.translatable("gui.credit.history.click_to_open"),
                    mouseX, mouseY);
            }
            y += lineH;
        }
    }

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        if (button == 0) {
            // Tab clicks
            if (mx >= tabStagedX && mx < tabStagedX + tabStagedW
                && my >= tabY && my < tabY + TAB_H) {
                if (currentTab != HistoryEntry.Kind.STAGED) {
                    currentTab = HistoryEntry.Kind.STAGED;
                    autoSelectFirst();
                }
                return true;
            }
            if (mx >= tabPushedX && mx < tabPushedX + tabPushedW
                && my >= tabY && my < tabY + TAB_H) {
                if (currentTab != HistoryEntry.Kind.PUSHED) {
                    currentTab = HistoryEntry.Kind.PUSHED;
                    autoSelectFirst();
                }
                return true;
            }
            if (mx >= tabImmediateX && mx < tabImmediateX + tabImmediateW
                && my >= tabY && my < tabY + TAB_H) {
                if (currentTab != HistoryEntry.Kind.IMMEDIATE) {
                    currentTab = HistoryEntry.Kind.IMMEDIATE;
                    autoSelectFirst();
                }
                return true;
            }
            // Detail hotspot (recipe ID クリック → JEI 飛ぶ)
            for (DetailHotspot h : hotspots) {
                if (mx >= h.x && mx < h.x + h.w && my >= h.y && my < h.y + h.h) {
                    HistoryEntry.Item it = h.item;
                    if (it.kind == DIV.credit.client.io.ScriptWriter.OperationKind.DELETE) {
                        return true; // DELETE: 既に消えてるレシピなので no-op
                    }
                    if (!it.success) return true;
                    try {
                        net.minecraft.resources.ResourceLocation rl =
                            new net.minecraft.resources.ResourceLocation(it.recipeId);
                        boolean ok = DIV.credit.client.jei.JeiNavigation.openRecipeId(rl, it.jeiCategoryUid);
                        if (!ok && this.minecraft != null && this.minecraft.player != null) {
                            this.minecraft.player.displayClientMessage(
                                Component.translatable("gui.credit.history.recipe_not_found", it.recipeId)
                                    .withStyle(ChatFormatting.YELLOW), false);
                        }
                    } catch (Exception ignored) {}
                    return true;
                }
            }
            // Entry rows
            List<HistoryEntry> filtered = filteredEntries();
            for (int i = 0; i < visibleRows; i++) {
                int idx = scrollOffset + i;
                if (idx >= filtered.size()) break;
                int y = leftY + i * ROW_H;
                if (mx >= leftX && mx < leftX + LEFT_W && my >= y && my < y + ROW_H) {
                    selected = filtered.get(idx);
                    detailScroll = 0;
                    return true;
                }
            }
        }
        return super.mouseClicked(mx, my, button);
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double delta) {
        if (mx >= leftX && mx < leftX + LEFT_W && my >= leftY && my < leftY + leftH) {
            scrollOffset -= (int) delta;
            int max = Math.max(0, filteredEntries().size() - visibleRows);
            scrollOffset = Math.max(0, Math.min(scrollOffset, max));
            return true;
        }
        if (mx >= rightX && mx < rightX + rightW && my >= rightY && my < rightY + rightH) {
            detailScroll -= (int) delta;
            return true;
        }
        return super.mouseScrolled(mx, my, delta);
    }

    @Override
    public void onClose() {
        if (this.minecraft != null) this.minecraft.setScreen(parent);
    }

    @Override
    public boolean isPauseScreen() { return false; }
}
