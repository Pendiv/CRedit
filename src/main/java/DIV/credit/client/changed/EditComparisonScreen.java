package DIV.credit.client.changed;

import DIV.credit.Credit;
import DIV.credit.client.preview.JeiRenderBridge;
import DIV.credit.client.tab.CategoryTab;
import DIV.credit.jei.CraftPatternJeiPlugin;
import mezz.jei.api.gui.IRecipeLayoutDrawable;
import mezz.jei.api.recipe.category.IRecipeCategory;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.renderer.Rect2i;
import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * v3.3: EDIT 系の preview。 左に元 recipe、 右に新 recipe を並置。 縦 N 行同時表示。
 * <pre>
 *   [tab][tab][tab]                                        ← screen 上端固定
 *   ┌──────────────┬────────────────────┐
 *   │ 元 (left 1)  │  新 (right 1)      │
 *   │ 元 (left 2)  │  新 (right 2)      │  ← 縦 N 行 (画面高さで自動算出)
 *   │ ...          │  ...               │
 *   └──────────────┴────────────────────┘
 *               < page 1/P >  [X]
 * </pre>
 */
public class EditComparisonScreen extends Screen {

    private static final int TITLE_COLOR    = 0xFF404040;
    private static final int TAB_H          = CategoryTab.H;
    private static final int TAB_W          = CategoryTab.W;
    private static final int TAB_GAP        = 1;
    private static final int TAB_TOP_MARGIN = 4;
    private static final int CONTENT_GAP_FROM_TAB = 0;  // 連結感
    private static final int CAT_NAV_W      = 16;
    private static final int CAT_NAV_GAP    = 4;
    private static final int BG_PLATE_COLOR = 0xFFC6C6C6;  // ChangedJeiScreen.BG_PLATE と同値
    private static final int ROW_SPACING    = 6;
    private static final int SIDE_GAP       = 4;     // 左右 drawable の隣接 gap (画面中央で接する)
    private static final int SIDE_LABEL_H   = 12;
    private static final int PAGE_NAV_H     = 22;
    private static final int CLOSE_BTN_W    = 14;
    private static final int CLOSE_BTN_H    = 14;
    private static final int BOTTOM_MARGIN  = 6;
    private static final int CONTENT_X_MARGIN = 16;

    private static final int DROPDOWN_GAP = 6;

    @Nullable private final Screen parent;
    private final Map<IRecipeCategory<?>, List<ChangedRecipeCollector.Item>> rawData;
    private Map<IRecipeCategory<?>, List<ChangedRecipeCollector.Item>> data;
    private List<IRecipeCategory<?>> categories;
    private final List<CategoryTab> tabs = new ArrayList<>();

    private int selectedCatIdx = 0;
    private int currentPageIdx = 0;
    private int rowsPerPage = 1;
    private final List<Row> visibleRows = new ArrayList<>();
    // v3.14: placeholder bucket 用 — drawable は作らず plate 描画
    private final List<ChangedRecipeCollector.Item> visiblePlaceholderItems = new ArrayList<>();

    private int tabBarX, tabBarY;
    private int contentTop, contentBottom;
    private int pageNavY;
    private int leftColumnX, rightColumnX;
    private int columnW;

    // v3.8/3.10: dropdown + loading
    private final Dropdown<ChangedRecipeCollector.ViewMode> viewDropdown;
    private final Dropdown<ChangedJeiScreen.TimeSlot> timeDropdown;
    @Nullable private final Long currentPushTs;
    private boolean pendingRebuild = false;

    /** 1 行分の左右 drawable + label 描画用ペア。 v3.5/v3.15: item 参照 + source tag。 */
    private record Row(@Nullable IRecipeLayoutDrawable<?> left,
                       @Nullable IRecipeLayoutDrawable<?> right,
                       int height,
                       @Nullable ChangedRecipeCollector.SourceTag tag,
                       ChangedRecipeCollector.Item item) {}

    public EditComparisonScreen(@Nullable Screen parent, Component title,
                                 Map<IRecipeCategory<?>, List<ChangedRecipeCollector.Item>> data,
                                 ChangedRecipeCollector.ViewMode initialMode,
                                 @Nullable Long pushTs) {
        super(title);
        this.parent = parent;
        this.rawData = data;
        this.currentPushTs = pushTs;
        this.viewDropdown = new Dropdown<>(
            java.util.List.of(ChangedRecipeCollector.ViewMode.values()),
            initialMode, 130,
            ChangedJeiScreen::viewModeLabel,
            m -> {
                if (m.kind() == DIV.credit.client.io.ScriptWriter.OperationKind.ADD) {
                    // EDIT 系画面 → ADD 系画面に乗り換え (time slot 引き継ぎ)
                    PreviewLauncher.open(this.parent, m, this.currentPushTs);
                } else {
                    this.pendingRebuild = true;
                }
            }
        );
        // v3.10: 時間軸 dropdown
        var slots = ChangedJeiScreen.buildTimeSlots();
        ChangedJeiScreen.TimeSlot initialSlot = slots.stream()
            .filter(s -> java.util.Objects.equals(s.pushTs(), pushTs))
            .findFirst().orElse(slots.get(0));
        this.timeDropdown = new Dropdown<>(slots, initialSlot, 130,
            ChangedJeiScreen::timeSlotLabel,
            sl -> {
                if (!java.util.Objects.equals(sl.pushTs(), this.currentPushTs)) {
                    PreviewLauncher.open(this.parent, viewDropdown.value(), sl.pushTs());
                }
            });
        applyView(initialMode);
    }

    /** v3.8/v3.12/v3.14: rawData に ViewMode の acceptsTag を適用。 null key (placeholder) も残す。 */
    private void applyView(ChangedRecipeCollector.ViewMode mode) {
        Map<IRecipeCategory<?>, List<ChangedRecipeCollector.Item>> filtered = new java.util.LinkedHashMap<>();
        for (var e : rawData.entrySet()) {
            List<ChangedRecipeCollector.Item> kept = new ArrayList<>();
            for (var it : e.getValue()) {
                if (mode.acceptsTag(it.sourceTag())) kept.add(it);
            }
            if (!kept.isEmpty()) filtered.put(e.getKey(), kept);
        }
        this.data = filtered;
        this.categories = new ArrayList<>(filtered.keySet());
        this.selectedCatIdx = 0;
        this.currentPageIdx = 0;
    }

    @Override
    protected void init() {
        super.init();
        rebuildTabs();
        recomputeLayout();
        rebuildVisibleRows();
    }

    private void rebuildTabs() {
        tabs.clear();
        var runtime = CraftPatternJeiPlugin.runtime;
        for (IRecipeCategory<?> cat : categories) {
            if (cat == null) {
                tabs.add(null);  // v3.14: mod placeholder bucket
            } else if (runtime != null) {
                var rm = runtime.getRecipeManager();
                var gh = runtime.getJeiHelpers().getGuiHelper();
                tabs.add(new CategoryTab(cat, rm, gh));
            } else {
                tabs.add(null);
            }
        }
    }

    private void recomputeLayout() {
        int totalTabsW = Math.max(TAB_W, tabs.size() * TAB_W + Math.max(0, tabs.size() - 1) * TAB_GAP);
        tabBarX = (this.width - totalTabsW) / 2;
        tabBarY = TAB_TOP_MARGIN;
        // v3.8/3.10: dropdown 2 個を画面右上
        int viewX = this.width - viewDropdown.width() - 8;
        int timeX = viewX - timeDropdown.width() - 4;
        int ddY = tabBarY + (TAB_H - viewDropdown.height()) / 2;
        viewDropdown.setPos(viewX, ddY);
        timeDropdown.setPos(timeX, ddY);

        contentTop = TAB_TOP_MARGIN + TAB_H + CONTENT_GAP_FROM_TAB;
        pageNavY = this.height - BOTTOM_MARGIN - PAGE_NAV_H;
        contentBottom = pageNavY - 4;

        // 1 行高さで N 行算出
        int rowH = SIDE_LABEL_H + probeRowHeight();
        int availH = Math.max(0, contentBottom - contentTop);
        this.rowsPerPage = Math.max(1, (availH + ROW_SPACING) / (rowH + ROW_SPACING));

        // 列幅: drawable 幅 (= probe) で確定。 画面中央を基準に左右隣接。
        int dw = probeColumnWidth();
        this.columnW = Math.max(80, dw);
        int cx = this.width / 2;
        this.leftColumnX  = cx - SIDE_GAP / 2 - columnW;
        this.rightColumnX = cx + SIDE_GAP / 2;

        int total = currentCategoryItems().size();
        int pages = totalPages(total, rowsPerPage);
        if (currentPageIdx >= pages) currentPageIdx = Math.max(0, pages - 1);
    }

    private int probeRowHeight() {
        if (categories.isEmpty()) return 80;
        var items = currentCategoryItems();
        if (items.isEmpty()) return 80;
        var cat = categories.get(selectedCatIdx);
        if (cat == null) return 56;  // v3.14: placeholder plate 高さ
        var d = JeiRenderBridge.build(cat, items.get(0).recipe());
        if (d == null) return 80;
        return d.getRectWithBorder().getHeight();
    }

    private int probeColumnWidth() {
        if (categories.isEmpty()) return 120;
        var items = currentCategoryItems();
        if (items.isEmpty()) return 120;
        var cat = categories.get(selectedCatIdx);
        if (cat == null) return 200;  // v3.14: placeholder plate 幅
        var d = JeiRenderBridge.build(cat, items.get(0).recipe());
        if (d == null) return 120;
        return d.getRectWithBorder().getWidth();
    }

    private void rebuildVisibleRows() {
        visibleRows.clear();
        visiblePlaceholderItems.clear();
        if (categories.isEmpty()) return;
        var cat = categories.get(selectedCatIdx);
        var items = currentCategoryItems();
        int start = currentPageIdx * rowsPerPage;
        int end = Math.min(items.size(), start + rowsPerPage);
        if (cat == null) {
            // unknown type の mod recipe — left/right とも plate 描画する
            for (int i = start; i < end; i++) visiblePlaceholderItems.add(items.get(i));
            return;
        }
        for (int i = start; i < end; i++) {
            var item = items.get(i);
            IRecipeLayoutDrawable<?> left = item.origRecipe() != null
                ? JeiRenderBridge.build(cat, item.origRecipe()) : null;
            IRecipeLayoutDrawable<?> right = item.recipe() != null
                ? JeiRenderBridge.build(cat, item.recipe()) : null;
            int hL = left  != null ? left.getRectWithBorder().getHeight()  : 56;
            int hR = right != null ? right.getRectWithBorder().getHeight() : 56;
            visibleRows.add(new Row(left, right, Math.max(hL, hR), item.sourceTag(), item));
        }
    }

    private List<ChangedRecipeCollector.Item> currentCategoryItems() {
        if (categories.isEmpty()) return List.of();
        var cat = categories.get(selectedCatIdx);
        var list = data.get(cat);
        return list != null ? list : List.of();
    }

    private static int totalPages(int total, int perPage) {
        if (total <= 0) return 0;
        return (total + perPage - 1) / perPage;
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        renderBackground(g, mouseX, mouseY, partialTick);  // 1.21: 4 引数
        renderTabBar(g, mouseX, mouseY);

        int areaH = Math.max(0, contentBottom - contentTop);
        if (areaH > 0) {
            // panel BG は drawable 2 列を中央でぴったり囲む形 (= 左右隣接の枠)
            int areaL = leftColumnX - 4;
            int areaR = rightColumnX + columnW + 4;
            ChangedJeiScreen.drawBeveledPanel(g, areaL, contentTop, areaR - areaL, areaH, false);
        }

        if (pendingRebuild) {
            g.drawCenteredString(font, Component.translatable("gui.credit.changed.loading"),
                this.width / 2, contentTop + areaH / 2, TITLE_COLOR);
        } else if (categories.isEmpty()) {
            g.drawCenteredString(font, Component.translatable("gui.credit.preview.edit.empty"),
                this.width / 2, contentTop + areaH / 2, TITLE_COLOR);
        } else {
            renderRows(g, mouseX, mouseY);
        }

        renderPageNav(g, mouseX, mouseY);
        renderCloseButton(g, mouseX, mouseY);
        timeDropdown.render(g, mouseX, mouseY);
        viewDropdown.render(g, mouseX, mouseY);
        super.render(g, mouseX, mouseY, partialTick);
        renderTabTooltip(g, mouseX, mouseY);

        if (pendingRebuild) {
            pendingRebuild = false;
            applyView(viewDropdown.value());
            rebuildTabs();
            recomputeLayout();
            rebuildVisibleRows();
        }
    }

    private void renderTabBar(GuiGraphics g, int mouseX, int mouseY) {
        for (int i = 0; i < tabs.size(); i++) {
            int tx = tabBarX + i * (TAB_W + TAB_GAP);
            int ty = tabBarY;
            CategoryTab tab = tabs.get(i);
            if (tab != null) {
                tab.setPos(tx, ty);
                tab.draw(g, i == selectedCatIdx);
            } else {
                boolean sel = (i == selectedCatIdx);
                ChangedJeiScreen.drawBeveledPanel(g, tx, ty, TAB_W, TAB_H, !sel);
                int tw = font.width("Mod");
                g.drawString(font, "Mod", tx + (TAB_W - tw) / 2,
                    ty + (TAB_H - font.lineHeight) / 2 + 1, TITLE_COLOR, false);
            }
        }
        if (!tabs.isEmpty() && selectedCatIdx >= 0 && selectedCatIdx < tabs.size()) {
            int tx = tabBarX + selectedCatIdx * (TAB_W + TAB_GAP);
            int ty = tabBarY;
            g.fill(tx + 1, ty + TAB_H, tx + TAB_W - 1, ty + TAB_H + 1, BG_PLATE_COLOR);
        }
        if (tabs.size() >= 2) {
            int leftAx = tabBarX - CAT_NAV_GAP - CAT_NAV_W;
            int rightAx = tabBarX + tabs.size() * (TAB_W + TAB_GAP) - TAB_GAP + CAT_NAV_GAP;
            int ay = tabBarY + (TAB_H - 14) / 2;
            boolean lh = inRect(mouseX, mouseY, leftAx, ay, CAT_NAV_W, 14);
            boolean rh = inRect(mouseX, mouseY, rightAx, ay, CAT_NAV_W, 14);
            g.drawString(font, "◀", leftAx + 3, ay + 3, lh ? 0xFFFFFFFF : TITLE_COLOR, true);
            g.drawString(font, "▶", rightAx + 3, ay + 3, rh ? 0xFFFFFFFF : TITLE_COLOR, true);
        }
    }

    private void renderTabTooltip(GuiGraphics g, int mouseX, int mouseY) {
        for (int i = 0; i < tabs.size(); i++) {
            CategoryTab tab = tabs.get(i);
            int tx = tabBarX + i * (TAB_W + TAB_GAP);
            int ty = tabBarY;
            boolean over = (tab != null) ? tab.isMouseOver(mouseX, mouseY)
                : inRect(mouseX, mouseY, tx, ty, TAB_W, TAB_H);
            if (!over) continue;
            IRecipeCategory<?> cat = categories.get(i);
            int count = data.getOrDefault(cat, java.util.List.of()).size();
            java.util.List<Component> lines = (tab != null)
                ? new ArrayList<>(tab.tooltip()) : new ArrayList<>();
            if (tab == null) lines.add(Component.translatable("gui.credit.changed.tab_mod_title"));
            lines.add(Component.translatable("gui.credit.changed.tab_count", count)
                .withStyle(ChatFormatting.GRAY));
            g.renderTooltip(font, lines, java.util.Optional.empty(), mouseX, mouseY);
            return;
        }
    }

    private void renderRows(GuiGraphics g, int mouseX, int mouseY) {
        // v3.14: placeholder bucket — left/right とも plate で描画
        if (!visiblePlaceholderItems.isEmpty()) {
            int y = contentTop + 4;
            Component beforeLabel = Component.translatable("gui.credit.preview.edit.before");
            Component afterLabel  = Component.translatable("gui.credit.preview.edit.after");
            for (var item : visiblePlaceholderItems) {
                if (y == contentTop + 4) {
                    int blW = font.width(beforeLabel);
                    int alW = font.width(afterLabel);
                    g.drawString(font, beforeLabel,
                        leftColumnX + (columnW - blW) / 2, y, TITLE_COLOR, false);
                    g.drawString(font, afterLabel,
                        rightColumnX + (columnW - alW) / 2, y, TITLE_COLOR, false);
                }
                int drawY = y + SIDE_LABEL_H;
                // before: origRecipe があれば JEI drawable、 無ければ「不明」 plate
                if (item.origRecipe() != null) {
                    // origRecipe の category を解決して drawable build
                    var origCat = DIV.credit.client.preview.JeiRenderBridge
                        .findCategoryForRecipe(item.origRecipe());
                    if (origCat != null) {
                        var origDraw = DIV.credit.client.preview.JeiRenderBridge
                            .build(origCat, item.origRecipe());
                        if (origDraw != null) {
                            var r = origDraw.getRectWithBorder();
                            int x = leftColumnX + (columnW - r.getWidth()) / 2;
                            origDraw.setPosition(x, drawY);
                            try { origDraw.tick(); } catch (Exception ignored) {}
                            try { origDraw.drawRecipe(g, mouseX, mouseY); } catch (Exception ignored) {}
                            try { origDraw.drawOverlays(g, mouseX, mouseY); } catch (Exception ignored) {}
                        } else {
                            ChangedJeiScreen.drawModPlaceholderPlate(g, item,
                                leftColumnX, drawY, columnW, 56);
                        }
                    } else {
                        ChangedJeiScreen.drawModPlaceholderPlate(g, item,
                            leftColumnX, drawY, columnW, 56);
                    }
                } else {
                    // before 不明
                    ChangedJeiScreen.drawBeveledPanel(g, leftColumnX, drawY, columnW, 56, false);
                    g.drawCenteredString(font,
                        Component.translatable("gui.credit.preview.edit.no_before"),
                        leftColumnX + columnW / 2, drawY + 24, TITLE_COLOR);
                }
                // after: 必ず mod placeholder plate
                ChangedJeiScreen.drawModPlaceholderPlate(g, item,
                    rightColumnX, drawY, columnW, 56);
                if (item.sourceTag() != null) {
                    ChangedJeiScreen.drawSourceTag(g, item.sourceTag(), leftColumnX - 4, drawY + 2);
                }
                y += SIDE_LABEL_H + 56 + ROW_SPACING;
                if (y >= contentBottom) break;
            }
            return;
        }
        Component beforeLabel = Component.translatable("gui.credit.preview.edit.before");
        Component afterLabel  = Component.translatable("gui.credit.preview.edit.after");
        int y = contentTop + 4;
        for (Row row : visibleRows) {
            // 1 行目だけ before/after ラベルを上に
            if (y == contentTop + 4) {
                int blW = font.width(beforeLabel);
                int alW = font.width(afterLabel);
                g.drawString(font, beforeLabel,
                    leftColumnX + (columnW - blW) / 2, y, TITLE_COLOR, false);
                g.drawString(font, afterLabel,
                    rightColumnX + (columnW - alW) / 2, y, TITLE_COLOR, false);
            }
            int drawY = y + SIDE_LABEL_H;
            // v3.15: drawable=null は mod placeholder plate にフォールバック (= 「未ロード」 表示はやめる)
            drawSide(g, row.left(),  row.item(), leftColumnX,  drawY, mouseX, mouseY, true);
            drawSide(g, row.right(), row.item(), rightColumnX, drawY, mouseX, mouseY, false);
            if (row.tag() != null) {
                ChangedJeiScreen.drawSourceTag(g, row.tag(), leftColumnX - 4, drawY + 2);
            }
            y += SIDE_LABEL_H + row.height() + ROW_SPACING;
            if (y >= contentBottom) break;
        }
    }

    /** v3.15: 1 side の描画。 drawable があれば JEI 描画、 無ければ mod placeholder plate。 */
    private void drawSide(GuiGraphics g, @Nullable IRecipeLayoutDrawable<?> d,
                          ChangedRecipeCollector.Item item,
                          int columnLeftX, int y, int mouseX, int mouseY, boolean leftSide) {
        if (d != null) {
            int dw = d.getRectWithBorder().getWidth();
            int x = columnLeftX + (columnW - dw) / 2;
            d.setPosition(x, y);
            try { d.tick(); } catch (Exception ignored) {}
            try { d.drawRecipe(g, mouseX, mouseY); }
            catch (Exception e) { Credit.LOGGER.warn("[C6007] cmp.drawRecipe: {}", e.getMessage()); }
            try { d.drawOverlays(g, mouseX, mouseY); }
            catch (Exception e) { Credit.LOGGER.warn("[C6008] cmp.drawOverlays: {}", e.getMessage()); }
            return;
        }
        ChangedJeiScreen.drawModPlaceholderPlate(g, item, columnLeftX, y, columnW, 56);
        // before/after の文脈 ラベルを plate 上部に小さく
        Component side = Component.translatable(leftSide
            ? "gui.credit.preview.edit.before" : "gui.credit.preview.edit.after");
        int sw = font.width(side);
        g.drawString(font, side,
            columnLeftX + columnW - sw - 4, y - font.lineHeight - 1, 0xFF777777, false);
    }

    private int leftAreaX(Row row) {
        int dw = row.left() != null ? row.left().getRectWithBorder().getWidth() : 120;
        return leftColumnX + (columnW - dw) / 2;
    }

    private int rightAreaX(Row row) {
        int dw = row.right() != null ? row.right().getRectWithBorder().getWidth() : 120;
        return rightColumnX + (columnW - dw) / 2;
    }

    private void drawOne(GuiGraphics g, @Nullable IRecipeLayoutDrawable<?> d,
                          int x, int y, int mouseX, int mouseY, String emptyKey) {
        if (d == null) {
            int w = 120, h = 80;
            ChangedJeiScreen.drawBeveledPanel(g, x, y, w, h, false);
            g.drawCenteredString(font, Component.translatable(emptyKey),
                x + w / 2, y + h / 2 - 4, TITLE_COLOR);
            return;
        }
        d.setPosition(x, y);
        try { d.tick(); } catch (Exception ignored) {}
        try { d.drawRecipe(g, mouseX, mouseY); }
        catch (Exception e) { Credit.LOGGER.warn("[C6007] cmp.drawRecipe: {}", e.getMessage()); }
        try { d.drawOverlays(g, mouseX, mouseY); }
        catch (Exception e) { Credit.LOGGER.warn("[C6008] cmp.drawOverlays: {}", e.getMessage()); }
    }

    private void renderPageNav(GuiGraphics g, int mouseX, int mouseY) {
        if (categories.isEmpty()) return;
        int total = currentCategoryItems().size();
        int pages = totalPages(total, rowsPerPage);
        if (pages <= 0) return;
        String label = "page " + (currentPageIdx + 1) + " / " + pages
            + "  (" + total + " items, " + rowsPerPage + "/page)";
        int labelW = font.width(label);
        int cx = this.width / 2;
        int cy = pageNavY + (PAGE_NAV_H - font.lineHeight) / 2;

        int leftX = cx - labelW / 2 - 20;
        boolean leftHover = inRect(mouseX, mouseY, leftX, pageNavY + 4, 14, 14);
        int leftColor = pages > 1 ? (leftHover ? 0xFF000000 : TITLE_COLOR) : 0xFF999999;
        g.drawString(font, "<", leftX + 4, cy, leftColor, false);
        g.drawString(font, label, cx - labelW / 2, cy, TITLE_COLOR, false);
        int rightX = cx + labelW / 2 + 8;
        boolean rightHover = inRect(mouseX, mouseY, rightX, pageNavY + 4, 14, 14);
        int rightColor = pages > 1 ? (rightHover ? 0xFF000000 : TITLE_COLOR) : 0xFF999999;
        g.drawString(font, ">", rightX + 4, cy, rightColor, false);
    }

    private void renderCloseButton(GuiGraphics g, int mouseX, int mouseY) {
        int bx = this.width - CLOSE_BTN_W - 6;
        int by = pageNavY + (PAGE_NAV_H - CLOSE_BTN_H) / 2;
        boolean hover = inRect(mouseX, mouseY, bx, by, CLOSE_BTN_W, CLOSE_BTN_H);
        g.drawString(font, "✕", bx + 3, by + 2, hover ? 0xFFAA0000 : TITLE_COLOR, false);
    }

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        if (button != 0) return super.mouseClicked(mx, my, button);

        // v3.8/3.10: dropdown 優先
        if (viewDropdown.mouseClicked(mx, my)) return true;
        if (timeDropdown.mouseClicked(mx, my)) return true;

        int closeBx = this.width - CLOSE_BTN_W - 6;
        int closeBy = pageNavY + (PAGE_NAV_H - CLOSE_BTN_H) / 2;
        if (inRect(mx, my, closeBx, closeBy, CLOSE_BTN_W, CLOSE_BTN_H)) {
            onClose();
            return true;
        }

        for (int i = 0; i < tabs.size(); i++) {
            var tab = tabs.get(i);
            int tx = tabBarX + i * (TAB_W + TAB_GAP);
            int ty = tabBarY;
            boolean over = (tab != null)
                ? tab.isMouseOver(mx, my)
                : inRect(mx, my, tx, ty, TAB_W, TAB_H);
            if (over) {
                if (i != selectedCatIdx) {
                    selectedCatIdx = i;
                    currentPageIdx = 0;
                    recomputeLayout();
                    rebuildVisibleRows();
                }
                return true;
            }
        }
        if (tabs.size() >= 2) {
            int leftAx = tabBarX - CAT_NAV_GAP - CAT_NAV_W;
            int rightAx = tabBarX + tabs.size() * (TAB_W + TAB_GAP) - TAB_GAP + CAT_NAV_GAP;
            int ay = tabBarY + (TAB_H - 14) / 2;
            if (inRect(mx, my, leftAx, ay, CAT_NAV_W, 14)) {
                selectedCatIdx = (selectedCatIdx - 1 + tabs.size()) % tabs.size();
                currentPageIdx = 0;
                recomputeLayout();
                rebuildVisibleRows();
                return true;
            }
            if (inRect(mx, my, rightAx, ay, CAT_NAV_W, 14)) {
                selectedCatIdx = (selectedCatIdx + 1) % tabs.size();
                currentPageIdx = 0;
                recomputeLayout();
                rebuildVisibleRows();
                return true;
            }
        }

        if (!categories.isEmpty()) {
            int total = currentCategoryItems().size();
            int pages = totalPages(total, rowsPerPage);
            if (pages > 1) {
                String label = "page " + (currentPageIdx + 1) + " / " + pages
                    + "  (" + total + " items, " + rowsPerPage + "/page)";
                int labelW = font.width(label);
                int cx = this.width / 2;
                int leftX = cx - labelW / 2 - 20;
                int rightX = cx + labelW / 2 + 8;
                if (inRect(mx, my, leftX, pageNavY + 4, 14, 14)) {
                    currentPageIdx = (currentPageIdx - 1 + pages) % pages;
                    rebuildVisibleRows();
                    return true;
                }
                if (inRect(mx, my, rightX, pageNavY + 4, 14, 14)) {
                    currentPageIdx = (currentPageIdx + 1) % pages;
                    rebuildVisibleRows();
                    return true;
                }
            }
        }
        return super.mouseClicked(mx, my, button);
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double scrollX, double scrollY) {
        double delta = scrollY;  // 1.21: scrollX 追加。縦スクロールは scrollY。
        if (categories.isEmpty()) return false;
        int total = currentCategoryItems().size();
        int pages = totalPages(total, rowsPerPage);
        if (pages <= 1) return false;
        if (delta > 0) currentPageIdx = (currentPageIdx - 1 + pages) % pages;
        else if (delta < 0) currentPageIdx = (currentPageIdx + 1) % pages;
        else return false;
        rebuildVisibleRows();
        return true;
    }

    @Override
    public void onClose() {
        Minecraft.getInstance().setScreen(parent);
    }

    @Override
    public boolean isPauseScreen() { return false; }

    private static boolean inRect(double mx, double my, int x, int y, int w, int h) {
        return mx >= x && mx < x + w && my >= y && my < y + h;
    }
}
