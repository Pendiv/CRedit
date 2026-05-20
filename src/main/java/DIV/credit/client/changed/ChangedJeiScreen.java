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
 * v3.3: 「credit が変更を加えた ADD レシピ」 を JEI 風 dashboard で閲覧。
 * <pre>
 *   [tab][tab][tab]                                       ← screen 上端固定 (CategoryTab.png 単独)
 *   ┌────────────────────────────────────┐
 *   │   drawable 1 列目                  │
 *   │   drawable 2 列目                  │   ← 縦 N 行同時表示 (画面高さで自動算出)
 *   │   ...                              │
 *   └────────────────────────────────────┘
 *               < page 1/P >  [X]                         ← page nav (N 単位)
 * </pre>
 * 閲覧 only。 click は drawable に渡さない。 ESC で close。
 */
public class ChangedJeiScreen extends Screen {

    // vanilla container 風 灰色テーマ
    private static final int BG_PLATE   = 0xFFC6C6C6;
    private static final int EDGE_LIGHT = 0xFFFFFFFF;
    private static final int EDGE_DARK  = 0xFF555555;
    private static final int TITLE_COLOR = 0xFF404040;

    private static final int TAB_H        = CategoryTab.H;
    private static final int TAB_W        = CategoryTab.W;
    private static final int TAB_GAP      = 1;
    private static final int TAB_TOP_MARGIN = 4;
    private static final int CONTENT_GAP_FROM_TAB = 0;  // 連結感: tab と panel くっつける
    private static final int CAT_NAV_W    = 16;        // tab 列左右の < > 矢印幅
    private static final int CAT_NAV_GAP  = 4;
    private static final int ROW_SPACING  = 6;
    private static final int PAGE_NAV_H   = 22;
    private static final int CLOSE_BTN_W  = 14;
    private static final int CLOSE_BTN_H  = 14;
    private static final int BOTTOM_MARGIN = 6;
    private static final int DROPDOWN_GAP = 6;   // tab bar 右端から dropdown までの隙間

    @Nullable private final Screen parent;
    /** v3.6: filter 前の生データ。 categoriesAll/visible は再構築で派生。 */
    private final Map<IRecipeCategory<?>, List<ChangedRecipeCollector.Item>> rawData;
    /** v3.6: 現 filter 適用後のデータ (= 描画対象)。 */
    private Map<IRecipeCategory<?>, List<ChangedRecipeCollector.Item>> data;
    private List<IRecipeCategory<?>> categories;
    private final List<CategoryTab> tabs = new ArrayList<>();

    private int selectedCatIdx = 0;
    private int currentPageIdx = 0;
    private int rowsPerPage = 1;
    private final List<IRecipeLayoutDrawable<?>> visibleDrawables = new ArrayList<>();
    // v3.5: drawable と同じ index で source tag を保持 (= 描画時に隣に表示)
    private final List<ChangedRecipeCollector.SourceTag> visibleTags = new ArrayList<>();
    // v3.14: placeholder bucket 用に Item を直接保持して plate 描画する
    private final List<ChangedRecipeCollector.Item> visiblePlaceholderItems = new ArrayList<>();

    // v3.14: mod placeholder plate のサイズ
    private static final int MOD_PLATE_W = 200;
    private static final int MOD_PLATE_H = 56;
    private static final int MOD_TAB_W = 24;
    private static final int MOD_TAB_H = 24;

    // v3.8: dropdown (ViewMode) + loading
    private final Dropdown<ChangedRecipeCollector.ViewMode> viewDropdown;
    // v3.10: 時間軸 dropdown (= 未 push + 過去 push 列)
    private final Dropdown<TimeSlot> timeDropdown;
    /** 現在表示中の pushTs (null = 未 push)。 */
    @Nullable private final Long currentPushTs;
    /** view 切替後 1 frame は "Loading..." 表示 → 次 frame で重い rebuild or Screen 交換。 */
    private boolean pendingRebuild = false;

    // layout state
    private int tabBarX, tabBarY;
    private int contentX, contentTop, contentBottom;
    private int pageNavY;

    /** v3.10: 時間軸 dropdown の値型。 null timestamp = 未 push。 */
    public record TimeSlot(@Nullable Long pushTs) {
        public boolean isUnpushed() { return pushTs == null; }
    }

    public ChangedJeiScreen(@Nullable Screen parent, Component title,
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
                if (m.kind() == DIV.credit.client.io.ScriptWriter.OperationKind.EDIT) {
                    // ADD系画面 → EDIT 系画面に乗り換え (time slot 引き継ぎ)
                    PreviewLauncher.open(this.parent, m, this.currentPushTs);
                } else {
                    this.pendingRebuild = true;  // 同じ ADD 系画面で filter のみ rebuild
                }
            }
        );
        // v3.10: 時間軸 dropdown — 未 push + 過去 push 列挙
        var slots = buildTimeSlots();
        TimeSlot initialSlot = slots.stream()
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

    /** v3.10: PushPayloadStore から past push timestamp を集めて [未 push, 過去...] を作る。 */
    static java.util.List<TimeSlot> buildTimeSlots() {
        java.util.List<TimeSlot> out = new ArrayList<>();
        out.add(new TimeSlot(null));  // 未 push
        for (Long ts : DIV.credit.client.history.PushPayloadStore.availableTimestamps()) {
            out.add(new TimeSlot(ts));
        }
        return out;
    }

    static Component timeSlotLabel(TimeSlot s) {
        if (s.isUnpushed()) {
            return Component.translatable("gui.credit.changed.time.unpushed");
        }
        java.util.Date d = new java.util.Date(s.pushTs());
        return Component.literal(new java.text.SimpleDateFormat("MM/dd HH:mm").format(d));
    }

    static Component viewModeLabel(ChangedRecipeCollector.ViewMode m) {
        return Component.translatable(switch (m) {
            case USER_ADD    -> "gui.credit.changed.view.user_add";
            case USER_EDIT   -> "gui.credit.changed.view.user_edit";
            case IMPORT_ADD  -> "gui.credit.changed.view.import_add";
            case IMPORT_EDIT -> "gui.credit.changed.view.import_edit";
        });
    }

    /** v3.8/v3.12/v3.14: rawData に ViewMode の acceptsTag を適用 → data / categories 再生成。
     *  v3.14: key=null (= mod recipe placeholder) bucket も描画対象として残す。 */
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
        rebuildVisibleDrawables();
    }

    /** v3.6/v3.14: filter 切替時にも呼ぶ。 categories から CategoryTab を再生成。
     *  v3.14: null cat (= mod recipe placeholder bucket) は CategoryTab を作らず、
     *   tabs リストに null を入れる (= renderTabBar で 「Mod」 plain tab として描画)。 */
    private void rebuildTabs() {
        tabs.clear();
        var runtime = CraftPatternJeiPlugin.runtime;
        for (IRecipeCategory<?> cat : categories) {
            if (cat == null) {
                tabs.add(null);  // placeholder slot
            } else if (runtime != null) {
                var rm = runtime.getRecipeManager();
                var gh = runtime.getJeiHelpers().getGuiHelper();
                tabs.add(new CategoryTab(cat, rm, gh));
            } else {
                tabs.add(null);
            }
        }
    }

    /** screen サイズ + 選択 category の drawable 高から rowsPerPage 再算出。 */
    private void recomputeLayout() {
        // tab bar 中央寄せ
        int totalTabsW = Math.max(TAB_W, tabs.size() * TAB_W + Math.max(0, tabs.size() - 1) * TAB_GAP);
        tabBarX = (this.width - totalTabsW) / 2;
        tabBarY = TAB_TOP_MARGIN;

        // v3.8/3.10: dropdown 2 個を画面右上 (= 右に view、 左に time)
        int viewX = this.width - viewDropdown.width() - 8;
        int timeX = viewX - timeDropdown.width() - 4;
        int ddY = tabBarY + (TAB_H - viewDropdown.height()) / 2;
        viewDropdown.setPos(viewX, ddY);
        timeDropdown.setPos(timeX, ddY);

        contentX = MARGIN_DEFAULT;
        contentTop = TAB_TOP_MARGIN + TAB_H + CONTENT_GAP_FROM_TAB;
        pageNavY = this.height - BOTTOM_MARGIN - PAGE_NAV_H;
        contentBottom = pageNavY - 4;

        // 選択 category の drawable 1 件高さで N 行算出 (= 暫定構築)
        int rowH = probeRowHeight();
        int availH = Math.max(0, contentBottom - contentTop);
        this.rowsPerPage = Math.max(1, (availH + ROW_SPACING) / (rowH + ROW_SPACING));
        // 現在 page index が範囲外なら 0 に
        int total = currentCategoryItems().size();
        int pages = totalPages(total, rowsPerPage);
        if (currentPageIdx >= pages) currentPageIdx = Math.max(0, pages - 1);
    }

    /** 選択 category の 1 件目を probe して drawable 高さを得る。 失敗時は 100 (= 安全 fallback)。 */
    private int probeRowHeight() {
        if (categories.isEmpty()) return 100;
        var items = currentCategoryItems();
        if (items.isEmpty()) return 100;
        var cat = categories.get(selectedCatIdx);
        if (cat == null) return MOD_PLATE_H;  // v3.14: placeholder plate 高さ固定
        var d = JeiRenderBridge.build(cat, items.get(0).recipe());
        if (d == null) return 100;
        return d.getRectWithBorder().getHeight();
    }

    /** 現 page 分の N 件を build。 既存 visibleDrawables は捨てる。
     *  v3.14/v3.15: recipe=null (= mod recipe で Recovery 不可) Item は placeholder bucket と同じ plate path に流す。
     *  これで JEI drawable と mod plate が同じ tab 内に共存可能。 */
    private void rebuildVisibleDrawables() {
        visibleDrawables.clear();
        visibleTags.clear();
        visiblePlaceholderItems.clear();
        if (categories.isEmpty()) return;
        var cat = categories.get(selectedCatIdx);
        var items = currentCategoryItems();
        int start = currentPageIdx * rowsPerPage;
        int end = Math.min(items.size(), start + rowsPerPage);
        if (cat == null) {
            // unknown type の mod recipe — plate のみ
            for (int i = start; i < end; i++) visiblePlaceholderItems.add(items.get(i));
            return;
        }
        int built = 0, failed = 0;
        for (int i = start; i < end; i++) {
            var item = items.get(i);
            if (item.recipe() == null) {
                // mod recipe (category 解決済だが Recipe<?> 未復元) → plate
                visiblePlaceholderItems.add(item);
                continue;
            }
            var d = JeiRenderBridge.build(cat, item.recipe());
            if (d != null) {
                visibleDrawables.add(d);
                visibleTags.add(item.sourceTag());
                built++;
            } else {
                // build 失敗も plate にフォールバック (= 完全な情報損失を避ける)
                visiblePlaceholderItems.add(item);
                failed++;
            }
        }
        Credit.LOGGER.debug("[CraftPattern] rebuildVisibleDrawables: built={}, failed={}, plate={}",
            built, failed, visiblePlaceholderItems.size());
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
        renderBackground(g);

        // tab bar (= screen 上端、 plate なし)
        renderTabBar(g, mouseX, mouseY);

        // 中央 content area = vanilla 風 凹 panel で全体を 1 つの枠に
        int areaH = Math.max(0, contentBottom - contentTop);
        if (areaH > 0) {
            drawBeveledPanel(g, contentX, contentTop, this.width - contentX * 2, areaH, false);
        }

        // v3.6: pendingRebuild なら "Loading..." を 1 frame 表示 → 次 frame で実 rebuild
        if (pendingRebuild) {
            g.drawCenteredString(font, Component.translatable("gui.credit.changed.loading"),
                this.width / 2, contentTop + areaH / 2, TITLE_COLOR);
        } else if (categories.isEmpty()) {
            g.drawCenteredString(font, Component.translatable("gui.credit.changed.empty"),
                this.width / 2, contentTop + areaH / 2, TITLE_COLOR);
        } else {
            renderDrawablesColumn(g, mouseX, mouseY);
        }

        renderPageNav(g, mouseX, mouseY);
        renderCloseButton(g, mouseX, mouseY);

        // v3.8/3.10: dropdown は最後 (= 開いた時タブの上にも被さる)
        timeDropdown.render(g, mouseX, mouseY);
        viewDropdown.render(g, mouseX, mouseY);

        super.render(g, mouseX, mouseY, partialTick);

        // tooltip は overlay 後 (= 最前面)
        renderTabTooltip(g, mouseX, mouseY);

        // v3.8: 表示した次 frame で実 rebuild (= 1 frame の loading 表示でロード感)
        if (pendingRebuild) {
            pendingRebuild = false;
            applyView(viewDropdown.value());
            rebuildTabs();
            recomputeLayout();
            rebuildVisibleDrawables();
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
                // v3.14: null tab = mod placeholder bucket — vanilla 風 plate + "Mod" 文字
                boolean sel = (i == selectedCatIdx);
                drawBeveledPanel(g, tx, ty, TAB_W, TAB_H, !sel);
                int tw = font.width("Mod");
                g.drawString(font, "Mod", tx + (TAB_W - tw) / 2, ty + (TAB_H - font.lineHeight) / 2 + 1,
                    TITLE_COLOR, false);
            }
        }
        // 連結感: 選択タブの下 1 px を panel BG 色で上書き = タブと panel の境界が消える
        if (!tabs.isEmpty() && selectedCatIdx >= 0 && selectedCatIdx < tabs.size()) {
            int tx = tabBarX + selectedCatIdx * (TAB_W + TAB_GAP);
            int ty = tabBarY;
            g.fill(tx + 1, ty + TAB_H, tx + TAB_W - 1, ty + TAB_H + 1, BG_PLATE);
        }
        // 左右 category nav (= tab 列両端、 2 個以上の時のみ)
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
            boolean over = (tab != null)
                ? tab.isMouseOver(mouseX, mouseY)
                : inRect(mouseX, mouseY, tx, ty, TAB_W, TAB_H);
            if (!over) continue;
            IRecipeCategory<?> cat = categories.get(i);
            int count = data.getOrDefault(cat, java.util.List.of()).size();
            java.util.List<Component> lines = (tab != null)
                ? new ArrayList<>(tab.tooltip())
                : new ArrayList<>();
            if (tab == null) {
                lines.add(Component.translatable("gui.credit.changed.tab_mod_title"));
            }
            lines.add(Component.translatable("gui.credit.changed.tab_count", count)
                .withStyle(ChatFormatting.GRAY));
            g.renderTooltip(font, lines, java.util.Optional.empty(), mouseX, mouseY);
            return;
        }
    }

    private void renderDrawablesColumn(GuiGraphics g, int mouseX, int mouseY) {
        int y = contentTop + 4;
        // v3.15: drawable と plate を順に並べる。 drawable を先に、 その下に plate
        for (int i = 0; i < visibleDrawables.size(); i++) {
            var d = visibleDrawables.get(i);
            Rect2i r = d.getRectWithBorder();
            int x = (this.width - r.getWidth()) / 2;
            d.setPosition(x, y);
            try { d.tick(); } catch (Exception ignored) {}
            try { d.drawRecipe(g, mouseX, mouseY); }
            catch (Exception e) { Credit.LOGGER.warn("[CraftPattern] drawRecipe failed: {}", e.getMessage()); }
            try { d.drawOverlays(g, mouseX, mouseY); }
            catch (Exception e) { Credit.LOGGER.warn("[CraftPattern] drawOverlays failed: {}", e.getMessage()); }
            if (i < visibleTags.size()) {
                drawSourceTag(g, visibleTags.get(i), x - 4, y + 2);
            }
            y += r.getHeight() + ROW_SPACING;
            if (y >= contentBottom) return;
        }
        for (var item : visiblePlaceholderItems) {
            int x = (this.width - MOD_PLATE_W) / 2;
            drawModPlaceholderPlate(g, item, x, y);
            drawSourceTag(g, item.sourceTag(), x - 4, y + 2);
            y += MOD_PLATE_H + ROW_SPACING;
            if (y >= contentBottom) return;
        }
    }

    /** v3.14: mod recipe placeholder の plate 描画。 recipeType + recipeId + (output item) を表示。
     *  EditComparisonScreen からも static で呼ぶ。 */
    static void drawModPlaceholderPlate(GuiGraphics g, ChangedRecipeCollector.Item item, int x, int y) {
        drawModPlaceholderPlate(g, item, x, y, MOD_PLATE_W, MOD_PLATE_H);
    }

    static void drawModPlaceholderPlate(GuiGraphics g, ChangedRecipeCollector.Item item,
                                         int x, int y, int w, int h) {
        var font = Minecraft.getInstance().font;
        drawBeveledPanel(g, x, y, w, h, false);
        var imp = item.imported();
        String type = imp != null && imp.recipeType() != null ? imp.recipeType() : "?";
        String id   = imp != null ? imp.recipeId() : "?";
        // 1 行目 (太字風): type
        g.drawString(font, Component.literal(type).withStyle(ChatFormatting.BOLD),
            x + 6, y + 4, TITLE_COLOR, false);
        // 2 行目: recipeId (短縮)
        String shortId = id.length() > 48 ? id.substring(0, 45) + "…" : id;
        g.drawString(font, shortId, x + 6, y + 4 + font.lineHeight + 2,
            0xFF555555, false);
        // 3 行目: kind ラベル
        String kindLabel = imp != null ? imp.kind().name() : "?";
        g.drawString(font, kindLabel, x + 6, y + 4 + (font.lineHeight + 2) * 2,
            0xFF777777, false);
        // 右側に output item があれば slot 風に表示
        if (imp != null && imp.outputId() != null) {
            try {
                var rl = new net.minecraft.resources.ResourceLocation(imp.outputId());
                var itm = net.minecraft.core.registries.BuiltInRegistries.ITEM.get(rl);
                var stack = new net.minecraft.world.item.ItemStack(itm);
                int slotX = x + w - 24;
                int slotY = y + (h - 16) / 2;
                g.renderItem(stack, slotX, slotY);
            } catch (Exception ignored) {}
        }
    }

    /**
     * v3.5: drawable 左に [user]/[import] タグ。 anchorX は drawable 左端 (= 右寄せで描く)。
     */
    static void drawSourceTag(GuiGraphics g, ChangedRecipeCollector.SourceTag tag, int anchorX, int y) {
        if (tag == null) return;
        boolean isImport = tag == ChangedRecipeCollector.SourceTag.IMPORT;
        Component label = Component.translatable(isImport
            ? "gui.credit.changed.tag_import"
            : "gui.credit.changed.tag_user");
        int color = isImport ? 0xFF3366CC : 0xFF2E8B57; // import=青、 user=緑
        int w = Minecraft.getInstance().font.width(label);
        int tx = anchorX - w;
        // 背景うっすら (= drawable 左の余白に置く)
        g.fill(tx - 2, y - 1, tx + w + 2, y + Minecraft.getInstance().font.lineHeight + 1, 0x88FFFFFF);
        g.drawString(Minecraft.getInstance().font, label, tx, y, color, false);
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

        // v3.8/3.10: dropdown 優先 (= 開いてる時は他より上)
        if (viewDropdown.mouseClicked(mx, my)) return true;
        if (timeDropdown.mouseClicked(mx, my)) return true;

        // close button
        int closeBx = this.width - CLOSE_BTN_W - 6;
        int closeBy = pageNavY + (PAGE_NAV_H - CLOSE_BTN_H) / 2;
        if (inRect(mx, my, closeBx, closeBy, CLOSE_BTN_W, CLOSE_BTN_H)) {
            onClose();
            return true;
        }

        // tab click — CategoryTab.isMouseOver 委譲、 null tab は座標で判定
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
                    rebuildVisibleDrawables();
                }
                return true;
            }
        }
        // category nav 矢印 (= tab 列両端の < > )
        if (tabs.size() >= 2) {
            int leftAx = tabBarX - CAT_NAV_GAP - CAT_NAV_W;
            int rightAx = tabBarX + tabs.size() * (TAB_W + TAB_GAP) - TAB_GAP + CAT_NAV_GAP;
            int ay = tabBarY + (TAB_H - 14) / 2;
            if (inRect(mx, my, leftAx, ay, CAT_NAV_W, 14)) {
                selectedCatIdx = (selectedCatIdx - 1 + tabs.size()) % tabs.size();
                currentPageIdx = 0;
                recomputeLayout();
                rebuildVisibleDrawables();
                return true;
            }
            if (inRect(mx, my, rightAx, ay, CAT_NAV_W, 14)) {
                selectedCatIdx = (selectedCatIdx + 1) % tabs.size();
                currentPageIdx = 0;
                recomputeLayout();
                rebuildVisibleDrawables();
                return true;
            }
        }

        // page nav
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
                    rebuildVisibleDrawables();
                    return true;
                }
                if (inRect(mx, my, rightX, pageNavY + 4, 14, 14)) {
                    currentPageIdx = (currentPageIdx + 1) % pages;
                    rebuildVisibleDrawables();
                    return true;
                }
            }
        }
        return super.mouseClicked(mx, my, button);
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double delta) {
        // ホイール: 上 = 前 page、 下 = 次 page。 wrap around。
        if (categories.isEmpty()) return false;
        int total = currentCategoryItems().size();
        int pages = totalPages(total, rowsPerPage);
        if (pages <= 1) return false;
        if (delta > 0) currentPageIdx = (currentPageIdx - 1 + pages) % pages;
        else if (delta < 0) currentPageIdx = (currentPageIdx + 1) % pages;
        else return false;
        rebuildVisibleDrawables();
        return true;
    }

    @Override
    public void onClose() {
        Minecraft.getInstance().setScreen(parent);
    }

    @Override
    public boolean isPauseScreen() { return false; }

    private static final int MARGIN_DEFAULT = 16;

    private static boolean inRect(double mx, double my, int x, int y, int w, int h) {
        return mx >= x && mx < x + w && my >= y && my < y + h;
    }

    /**
     * vanilla container 風 bevel panel。 raised=true は凸、 false は凹。
     * EditComparisonScreen からも static で呼ぶ。
     */
    static void drawBeveledPanel(GuiGraphics g, int x, int y, int w, int h, boolean raised) {
        int light = raised ? EDGE_LIGHT : EDGE_DARK;
        int dark  = raised ? EDGE_DARK  : EDGE_LIGHT;
        g.fill(x, y, x + w, y + h, BG_PLATE);
        g.fill(x,         y,         x + w,     y + 1,     light);
        g.fill(x,         y,         x + 1,     y + h,     light);
        g.fill(x + w - 1, y,         x + w,     y + h,     dark);
        g.fill(x,         y + h - 1, x + w,     y + h,     dark);
    }
}
