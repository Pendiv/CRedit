package DIV.credit.client.screen;

import DIV.credit.Credit;
import DIV.credit.client.draft.CookingDraft;
import DIV.credit.client.draft.DraftStore;
import DIV.credit.client.draft.RecipeDraft;
import DIV.credit.client.io.ScriptWriter;
import DIV.credit.client.menu.CreditBuilderMenu;
import DIV.credit.client.recipe.RecipeArea;
import DIV.credit.client.tab.CategoryTab;
import DIV.credit.client.tab.CategoryTabBar;
import DIV.credit.client.tab.PageNav;
import DIV.credit.client.tag.TagBar;
import DIV.credit.jei.CraftPatternJeiPlugin;
import mezz.jei.api.helpers.IGuiHelper;
import mezz.jei.api.recipe.IRecipeManager;
import mezz.jei.api.recipe.category.IRecipeCategory;
import mezz.jei.api.runtime.IJeiRuntime;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@OnlyIn(Dist.CLIENT)
public class BuilderScreen extends AbstractContainerScreen<CreditBuilderMenu> {

    public static final int TOP_MARGIN      = 5;
    public static final int BOTTOM_MARGIN   = 5;
    public static final int TAB_AREA_HEIGHT = PageNav.H + CategoryTab.H;

    public static final int DUMP_W = 21;
    public static final int DUMP_H = 10;
    public static final int SETTINGS_W = 12;
    public static final int SETTINGS_H = 12;
    public static final int QUESTION_W = 16;
    public static final int QUESTION_H = 16;
    private static final ResourceLocation DUMP_TEX =
            new ResourceLocation(Credit.MODID, "ui/dump.png");
    private static final ResourceLocation SETTINGS_TEX =
            new ResourceLocation(Credit.MODID, "ui/setting.png");
    private static final ResourceLocation QUESTION_TEX =
            new ResourceLocation(Credit.MODID, "ui/question.png");
    /** v2.0.0 編集モード badge。? icon と同じ位置に出す。 */
    private static final ResourceLocation CHOIS_TEX =
            new ResourceLocation(Credit.MODID, "ui/chois.png");
    public static final int CHOIS_W = 16;
    public static final int CHOIS_H = 16;

    /** Static so drafts and last-selected category persist across screen open/close (until game exit). */
    private static final DraftStore DRAFT_STORE = new DraftStore();
    private static IRecipeCategory<?> lastCategory;
    /** MAX persistence: ゲームセッションで一度だけファイルから復元する。 */
    private static boolean persistenceLoaded = false;
    /** v2.1.0 staging.dat はゲーム起動毎 1 回読む（モード問わず常に保持）。 */
    private static boolean stagingLoaded = false;

    // ─────── v2.0.0 編集モード state（プロセスグローバル、JEI からの遷移で set）───────
    /** 編集中のオリジナルレシピ ID。null なら通常モード。 */
    private static String editModeOrigId = null;
    /** 編集中のオリジナルカテゴリ UID（このカテゴリ以外に切替したら edit mode 解除）。 */
    private static String editModeOrigCategoryUid = null;

    // Undo state
    private long lastSnapshotHash = 0;
    /** 直近で hash check を実行した時刻。編集の有無に関わらず check 自体を debounce する。 */
    private long lastSnapshotCheckMs = 0;
    private static final long SNAPSHOT_DEBOUNCE_MS = 500;

    private CategoryTabBar tabBar;
    private final RecipeArea recipeArea = new RecipeArea();
    private final TagBar tagBar = new TagBar();
    private final StackBuilderWidget stackBuilder = new StackBuilderWidget();
    private final EnergyHelperWidget energyHelper = new EnergyHelperWidget();
    /** カテゴリ別の EnergyHelper 状態 [tierIdx, ampIdx]。 */
    private static final java.util.Map<String, int[]> energyHelperStateByCategory = new java.util.HashMap<>();
    private ItemStack ghostCursor = ItemStack.EMPTY;
    private DIV.credit.client.draft.IngredientSpec ghostSpec = DIV.credit.client.draft.IngredientSpec.EMPTY;
    /** drag-from-slot 状態。空でない時、cursor に追従して描画 + release で StackBuilder 等に転送。 */
    private DIV.credit.client.draft.IngredientSpec dragSpec = DIV.credit.client.draft.IngredientSpec.EMPTY;
    /** drag 開始元が CFG slot か。drop 成功時のみ CFG をクリアするため。 */
    private boolean dragFromCfg = false;
    private IRecipeCategory<?> currentCategory;

    private int recipeAreaTop;
    private int recipeAreaBottom;
    private int tagBarY;

    private int toggleX = -1, toggleY, toggleW, toggleH;
    private int tierX = -1, tierY, tierW, tierH;
    private int dumpX   = -1, dumpY;
    private int settingsX = -1, settingsY;
    private int questionX = -1, questionY;
    /** クリックで tooltip ON/OFF。hover では出さない (ユーザー要望)。 */
    private boolean questionTooltipOpen = false;

    // Dynamic numeric fields (derived from current draft.numericFields())
    private final List<EditBox> numericBoxes = new ArrayList<>();
    private final List<RecipeDraft.NumericField> currentFields = new ArrayList<>();
    private boolean updatingFieldsFromDraft = false;

    public BuilderScreen(CreditBuilderMenu menu, Inventory playerInv) {
        super(menu, playerInv, Component.literal("CraftPattern Builder"));
        this.imageWidth      = 176;
        this.imageHeight     = CreditBuilderMenu.IMAGE_HEIGHT;
        this.titleLabelX     = -10000;
        this.inventoryLabelX = -10000;
    }

    public static BuilderScreen open() {
        var player = Minecraft.getInstance().player;
        return new BuilderScreen(new CreditBuilderMenu(player.getInventory()), player.getInventory());
    }

    /** Settings の「編集データ削除」から呼ばれる。in-memory 状態を全クリア。 */
    public static void clearAllDraftData() {
        DRAFT_STORE.clear();
        energyHelperStateByCategory.clear();
        lastCategory = null;
        Credit.LOGGER.info("[CraftPattern] Cleared all draft data (DRAFT_STORE + energy state + lastCategory)");
    }

    // ─────── v2.0.0 編集モード API ───────
    /** EditHandler から呼ぶ：edit mode 開始 + 次回 BuilderScreen open 時の lastCategory も set。 */
    public static void enterEditMode(String origRecipeId, IRecipeCategory<?> category) {
        editModeOrigId = origRecipeId;
        editModeOrigCategoryUid = category.getRecipeType().getUid().toString();
        lastCategory = category;
        Credit.LOGGER.info("[CraftPattern] EDIT mode entered: orig={} category={}",
            origRecipeId, editModeOrigCategoryUid);
    }
    /** edit mode 解除（カテゴリ切替 / dump 完了 / undo で呼ばれる）。 */
    public static void exitEditMode() {
        if (editModeOrigId != null) {
            Credit.LOGGER.info("[CraftPattern] EDIT mode exited (was: {})", editModeOrigId);
        }
        editModeOrigId = null;
        editModeOrigCategoryUid = null;
    }
    public static boolean isEditMode() { return editModeOrigId != null; }
    public static @Nullable String getEditModeOrigId() { return editModeOrigId; }
    public static @Nullable String getEditModeOrigCategoryUid() { return editModeOrigCategoryUid; }
    /** Snapshot.applyTo 専用: UID 直接 set。category インスタンスは要らない。 */
    public static void restoreEditModeState(String origId, String origCategoryUid) {
        editModeOrigId = origId;
        editModeOrigCategoryUid = origCategoryUid;
    }
    /** EditHandler が draft 取得 + loadFromRecipe するため expose。 */
    public static DraftStore getDraftStore() { return DRAFT_STORE; }

    @Override
    protected void init() {
        super.init();
        // MAX persistence: ゲームセッションで一度だけファイルから復元
        if (!persistenceLoaded) {
            persistenceLoaded = true;
            if (DIV.credit.CreditConfig.EDIT_PERSISTENCE.get() == DIV.credit.CreditConfig.EditPersistence.MAX) {
                DIV.credit.client.draft.DraftPersistence.load(DRAFT_STORE);
            }
        }
        // v2.2.6: staging + history は world join 時に ImmediateHistorySession.Hook で load される。
        // BuilderScreen.init 起動時は冗長だが、念のため stagingLoaded ガードで 1 回だけ走る fallback。
        if (!stagingLoaded) {
            stagingLoaded = true;
            DIV.credit.client.staging.StagingPersistence.load();
            DIV.credit.client.history.HistoryPersistence.load();
        }
        int minInventoryTop = TOP_MARGIN + TAB_AREA_HEIGHT + TagBar.H + 10;
        this.topPos = Math.max(minInventoryTop, this.height - this.imageHeight - BOTTOM_MARGIN);

        IJeiRuntime rt = CraftPatternJeiPlugin.runtime;
        if (rt != null) {
            IRecipeManager rm = rt.getRecipeManager();
            IGuiHelper     gh = rt.getJeiHelpers().getGuiHelper();
            List<IRecipeCategory<?>> cats = rm.createRecipeCategoryLookup().get().toList();
            this.tabBar = new CategoryTabBar(cats, rm, gh, this::onCategorySelected);
            this.tabBar.setBounds(leftPos, TOP_MARGIN, imageWidth);
            IRecipeCategory<?> restored = findCategory(cats, lastCategory);
            if (restored != null) tabBar.select(restored);
        }

        this.recipeAreaTop    = TOP_MARGIN + TAB_AREA_HEIGHT;
        this.tagBarY          = topPos - TagBar.H;
        this.recipeAreaBottom = tagBarY;

        recipeArea.setBounds(leftPos, recipeAreaTop, imageWidth, recipeAreaBottom - recipeAreaTop);

        tagBar.init(this, font, leftPos, tagBarY, imageWidth, this::addRenderableWidget);

        // StackBuilder helper widget: Duration/EUt 編集行のさらに上に配置
        int stackBuilderY = recipeAreaBottom - 14 - StackBuilderWidget.H - 2;
        stackBuilder.init(this, font, leftPos + 4, stackBuilderY,
            this::addRenderableWidget, spec -> {
                this.ghostSpec = spec;
                if (spec instanceof DIV.credit.client.draft.IngredientSpec.Item it) {
                    this.ghostCursor = it.stack().copy();
                } else {
                    this.ghostCursor = ItemStack.EMPTY;
                }
            });

        if (tabBar != null && tabBar.getSelected() != null) {
            applyCategory(tabBar.getSelected());
        }

        // Undo: 初期スナップショットを push (空でも or 既存履歴の続きでも OK)
        // 履歴は世界セッション中保持されるので、再 open 時は新しい初期 snapshot として積む
        long h = DIV.credit.client.undo.UndoHistory.computeHash(DRAFT_STORE, this);
        if (h != lastSnapshotHash || DIV.credit.client.undo.UndoHistory.INSTANCE.size() == 0) {
            DIV.credit.client.undo.UndoHistory.INSTANCE.push(
                DIV.credit.client.undo.Snapshot.capture(DRAFT_STORE, this));
            lastSnapshotHash = h;
            lastSnapshotCheckMs = System.currentTimeMillis();
        }
    }

    /** Draft の numericFields() に基づいて EditBox を動的生成。 */
    private void rebuildNumericFields(@Nullable RecipeDraft draft) {
        // EnergyHelper は前カテゴリの callback が空 list を参照するとクラッシュするので、
        // 何より先に hide + callback クリア（draft == null や empty fields の早期 return より前）
        energyHelper.setVisible(false);
        energyHelper.setOnEUtChanged(null);
        for (EditBox box : numericBoxes) removeWidget(box);
        numericBoxes.clear();
        currentFields.clear();
        if (draft == null) return;
        List<RecipeDraft.NumericField> fields = draft.numericFields();
        if (fields.isEmpty()) return;

        int boxW = 50;  // 10 桁収まる程度に拡大
        int boxH = 12;
        int y    = recipeAreaBottom - boxH - 2;
        int x    = leftPos + 4;
        int spacing = 6;

        updatingFieldsFromDraft = true;
        try {
            for (RecipeDraft.NumericField field : fields) {
                int labelW = font.width(field.label());
                x += labelW + 3;
                EditBox box = new EditBox(font, x, y, boxW, boxH, Component.literal(field.label()));
                box.setMaxLength(10);  // 2147483647 (Int.MAX) は 10 桁
                box.setValue(formatField(field));
                box.setResponder(s -> onFieldChanged(field, s));
                numericBoxes.add(box);
                currentFields.add(field);
                addRenderableWidget(box);
                x += boxW + spacing;
            }
        } finally {
            updatingFieldsFromDraft = false;
        }

        // EnergyHelper: GT 電気機械（usesGtElectricity）かつ EUt フィールドがある時のみ表示
        // (visible/callback は冒頭でクリア済み)
        if (!draft.usesGtElectricity()) return;
        for (int i = 0; i < currentFields.size(); i++) {
            if ("EUt".equals(currentFields.get(i).label())) {
                EditBox eutBox = numericBoxes.get(i);
                int hx = eutBox.getX();
                int hy = eutBox.getY() - EnergyHelperWidget.H - 2;
                energyHelper.setBounds(hx, hy);
                energyHelper.setVisible(true);
                final int idx = i;
                final String catId = currentCategory != null ? currentCategory.getRecipeType().getUid().toString() : null;
                energyHelper.setOnEUtChanged(eu -> {
                    var field = currentFields.get(idx);
                    field.setter().accept((double) eu);
                    updatingFieldsFromDraft = true;
                    try { numericBoxes.get(idx).setValue(String.valueOf(eu)); }
                    finally { updatingFieldsFromDraft = false; }
                    if (catId != null) {
                        energyHelperStateByCategory.put(catId, new int[]{energyHelper.getTierIdx(), energyHelper.getAmpIdx()});
                    }
                    recipeArea.rebuild();
                });
                break;
            }
        }
    }

    private static String formatField(RecipeDraft.NumericField field) {
        double v = field.getter().getAsDouble();
        if (field.kind() == RecipeDraft.NumericField.Kind.INT) return String.valueOf((long) v);
        if (v == (long) v) return String.valueOf((long) v);
        return String.valueOf((float) v);
    }

    private void onFieldChanged(RecipeDraft.NumericField field, String s) {
        if (updatingFieldsFromDraft) return;
        if (s.isBlank()) return;
        try {
            double v = field.kind() == RecipeDraft.NumericField.Kind.INT
                ? (double) Integer.parseInt(s)
                : (double) Float.parseFloat(s);
            v = Math.max(field.min(), Math.min(field.max(), v));
            field.setter().accept(v);
            recipeArea.rebuild();
        } catch (NumberFormatException ignored) {}
    }

    private static IRecipeCategory<?> findCategory(List<IRecipeCategory<?>> cats, IRecipeCategory<?> target) {
        if (target == null) return null;
        for (IRecipeCategory<?> c : cats) {
            if (c.getRecipeType().equals(target.getRecipeType())) return c;
        }
        return null;
    }

    public RecipeArea getRecipeArea() { return recipeArea; }
    public StackBuilderWidget getStackBuilder() { return stackBuilder; }
    public DIV.credit.client.tag.TagBar getTagBar() { return tagBar; }

    // --- Undo snapshot 用 公開アクセス ---
    public java.util.Map<String, int[]> getEnergyHelperStateMap() {
        return energyHelperStateByCategory;
    }
    @Nullable
    public String getCurrentCategoryUid() {
        return currentCategory == null ? null : currentCategory.getRecipeType().getUid().toString();
    }
    /** Snapshot apply 時：UID 一致するカテゴリへ tab 切替。一致なければ現状維持で applyCategory のみ。 */
    public void restoreSelectedCategory(@Nullable String uid) {
        if (tabBar == null) return;
        if (uid != null && (currentCategory == null
            || !uid.equals(currentCategory.getRecipeType().getUid().toString()))) {
            IJeiRuntime rt = CraftPatternJeiPlugin.runtime;
            if (rt == null) return;
            var cats = rt.getRecipeManager().createRecipeCategoryLookup().get().toList();
            for (IRecipeCategory<?> c : cats) {
                if (uid.equals(c.getRecipeType().getUid().toString())) {
                    tabBar.select(c); // → onCategorySelected → applyCategory
                    return;
                }
            }
        }
        // 同じ category だった場合も再描画（draft 状態が変わってる）
        if (currentCategory != null) applyCategory(currentCategory);
    }
    /** ghost handler 用：StackBuilder の slot 領域 (画面座標)。非表示時 null。 */
    @Nullable
    public net.minecraft.client.renderer.Rect2i getStackBuilderArea() {
        if (!stackBuilder.isVisible()) return null;
        // init で stackBuilder の位置は確定済み。座標を取り出す helper を提供。
        return stackBuilder.getSlotRect();
    }
    /** ghost handler 用：TagBar の CFG slot 領域 (画面座標)。非表示時 null。 */
    @Nullable
    public net.minecraft.client.renderer.Rect2i getTagBarCfgArea() {
        return tagBar.getCfgSlotRect();
    }
    /** ghost handler 用：TagBar の Result slot 領域 (画面座標)。非表示時 null。 */
    @Nullable
    public net.minecraft.client.renderer.Rect2i getTagBarResultArea() {
        return tagBar.getResultSlotRect();
    }

    private void onCategorySelected(IRecipeCategory<?> cat) {
        Credit.LOGGER.info("[CraftPattern] Selected category: {}", cat.getRecipeType().getUid());
        applyCategory(cat);
    }

    private void applyCategory(IRecipeCategory<?> cat) {
        // edit mode: 元カテゴリ以外に切替したら edit mode 解除
        if (isEditMode() && cat != null
            && !cat.getRecipeType().getUid().toString().equals(editModeOrigCategoryUid)) {
            // v2.0.9: 設定で「保持」OFF (default) なら、元 edit カテゴリの grid 内容をクリア
            //         → 後で同カテゴリに戻った時に「load 残骸」で誤レシピを作らないように
            if (!DIV.credit.CreditConfig.PRESERVE_EDIT_GRID_ON_SWITCH.get()) {
                clearOrigEditCategoryDraft();
            }
            exitEditMode();
        }
        this.currentCategory = cat;
        lastCategory = cat;
        // カテゴリ遷移で ? tooltip 自動 close
        questionTooltipOpen = false;
        recipeArea.setCategory(cat, DRAFT_STORE.getOrCreate(cat));
        rebuildNumericFields(recipeArea.getDraft());
        RecipeDraft draft = recipeArea.getDraft();
        boolean editable = draft != null;
        tagBar.setVisible(editable);
        // CFG dropdown options はカテゴリ namespace で決まる（vanilla/gtceu/その他）。
        // CFG slot に何か乗ってて新カテゴリで option が無効なら NONE に戻る。
        tagBar.setCategoryNamespace(cat != null ? cat.getRecipeType().getUid().getNamespace() : null);
        // StackBuilder：fluid/gas slot を持つ draft のみ表示
        stackBuilder.setVisible(editable && hasFluidOrGasSlot(draft));
        // EnergyHelper の per-category 状態を復元
        if (cat != null) {
            String catId = cat.getRecipeType().getUid().toString();
            int[] state = energyHelperStateByCategory.computeIfAbsent(catId, k -> new int[]{1, 0});
            energyHelper.setState(state[0], state[1]);
        }
    }

    private static boolean hasFluidOrGasSlot(RecipeDraft draft) {
        if (draft == null) return false;
        for (int i = 0; i < draft.slotCount(); i++) {
            RecipeDraft.SlotKind k = draft.slotKind(i);
            if (k == RecipeDraft.SlotKind.FLUID_INPUT  || k == RecipeDraft.SlotKind.FLUID_OUTPUT
             || k == RecipeDraft.SlotKind.GAS_INPUT    || k == RecipeDraft.SlotKind.GAS_OUTPUT) return true;
        }
        return false;
    }

    private void toggleCraftingMode() {
        DRAFT_STORE.setCraftingVariant(
            DRAFT_STORE.getCraftingVariant() == DraftStore.CraftingVariant.SHAPED
                ? DraftStore.CraftingVariant.SHAPELESS
                : DraftStore.CraftingVariant.SHAPED
        );
        applyCategory(currentCategory);
        playClick();
    }

    private void doDump() {
        RecipeDraft draft = recipeArea.getDraft();
        if (draft == null) {
            chat(Component.literal("[CraftPattern] No draft for this category.").withStyle(ChatFormatting.RED));
            return;
        }
        String recipeId = autoRecipeId(draft);
        boolean wasEdit = isEditMode();
        // v2.1.0: ScriptWriter 直接呼出 → StagingArea.stage 経由に変更。
        // push されるまで実ファイルは触らない。
        String code;
        String origForLog;
        String modid;
        ScriptWriter.OperationKind opKind;
        if (wasEdit) {
            code = ScriptWriter.buildEditCode(draft, editModeOrigId, recipeId);
            origForLog = editModeOrigId;
            modid = ScriptWriter.editModid(draft, editModeOrigId);
            opKind = ScriptWriter.OperationKind.EDIT;
        } else {
            code = ScriptWriter.buildAddCode(draft, recipeId);
            origForLog = null;
            modid = draft.recipeType().getUid().getNamespace();
            opKind = ScriptWriter.OperationKind.ADD;
        }
        if (code == null) {
            chat(Component.literal("[CraftPattern] Draft is empty (need at least output + relevant inputs)")
                .withStyle(ChatFormatting.RED));
            return;
        }
        // v2.2.0 immediate apply 判定: 該当 kind が即時適応対象なら staging を介さず直接書き込み
        if (DIV.credit.CreditConfig.shouldApplyImmediately(opKind)) {
            DIV.credit.client.io.ScriptWriter.DumpResult r =
                DIV.credit.client.io.ScriptWriter.writeStagedCode(opKind, modid, code);
            if (r instanceof DIV.credit.client.io.ScriptWriter.DumpResult.Failure f) {
                chat(Component.literal("[CraftPattern] " + f.message()).withStyle(ChatFormatting.RED));
                return;
            }
            String filePath = r.path() != null ? r.path().toString() : null;
            String jeiCatUid = draft.recipeType().getUid().toString();
            DIV.credit.client.history.ImmediateHistorySession.INSTANCE.addItem(
                new DIV.credit.client.history.HistoryEntry.Item(opKind, modid, recipeId, filePath, true, jeiCatUid));
            chat(Component.translatable("gui.credit.immediate.applied",
                opKind.name(), recipeId).withStyle(ChatFormatting.GOLD));
            chat(Component.translatable("gui.credit.dump.reload_hint").withStyle(ChatFormatting.GRAY));
        } else {
            String jeiCategoryUid = draft.recipeType().getUid().toString();
            DIV.credit.client.staging.StagingArea.INSTANCE.stage(opKind, modid, recipeId, origForLog, code, jeiCategoryUid);
            DIV.credit.client.staging.StagingPersistence.save();
            chat(Component.translatable("gui.credit.staging.staged",
                opKind.name(), recipeId).withStyle(ChatFormatting.AQUA));
            chat(Component.translatable("gui.credit.staging.commit_hint")
                .withStyle(ChatFormatting.DARK_GRAY));
        }
        playClick();
        if (wasEdit) {
            exitEditMode();
            clearCurrentDraft(draft);
        }
    }

    /** draft の全 slot を EMPTY に。numericFields は draft 側 default を維持。 */
    private void clearCurrentDraft(RecipeDraft draft) {
        for (int i = 0; i < draft.slotCount(); i++) {
            draft.setSlot(i, DIV.credit.client.draft.IngredientSpec.EMPTY);
        }
        recipeArea.rebuild();
    }

    /**
     * v2.0.9: edit mode 元カテゴリの draft slot を全部 EMPTY にする。
     * DRAFT_STORE 内の key は <recipeType uid> あるいは <uid>|VARIANT (crafting のみ)。
     */
    private void clearOrigEditCategoryDraft() {
        if (editModeOrigCategoryUid == null) return;
        String prefix = editModeOrigCategoryUid;
        for (var entry : DRAFT_STORE.snapshotDrafts().entrySet()) {
            String key = entry.getKey();
            if (!(key.equals(prefix) || key.startsWith(prefix + "|"))) continue;
            RecipeDraft d = entry.getValue();
            for (int i = 0; i < d.slotCount(); i++) {
                d.setSlot(i, DIV.credit.client.draft.IngredientSpec.EMPTY);
            }
            // GT cleanroom も剥がす (再訪時に予期しない要件が残らないよう)
            if (d instanceof DIV.credit.client.draft.GenericDraft gd) {
                gd.setCleanroomType(null);
            }
            Credit.LOGGER.info("[CraftPattern] Cleared edit-mode draft slots for {}", key);
        }
    }

    private String autoRecipeId(RecipeDraft draft) {
        String outPath = draft.outputItemPath();
        if (outPath != null && !outPath.isEmpty()) {
            return Credit.MODID + ":generated/" + outPath;
        }
        return Credit.MODID + ":generated/recipe_" + (System.currentTimeMillis() % 100000);
    }

    private static void chat(Component msg) {
        var p = Minecraft.getInstance().player;
        if (p != null) p.displayClientMessage(msg, false);
    }

    private static void playClick() {
        Minecraft.getInstance().getSoundManager().play(
            SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK, 1.0F));
    }

    @Override
    protected void renderBg(GuiGraphics g, float partialTick, int mouseX, int mouseY) {
        g.fill(leftPos, recipeAreaTop, leftPos + imageWidth, recipeAreaBottom,    0xFF1A1A2E);
        g.fill(leftPos, tagBarY,       leftPos + imageWidth, tagBarY + TagBar.H,  0xFF161628);
        g.fill(leftPos, topPos,        leftPos + imageWidth, topPos + imageHeight, 0xFF2A2A3E);
        for (Slot s : menu.slots) {
            int sx = leftPos + s.x;
            int sy = topPos  + s.y;
            g.fill(sx - 1, sy - 1, sx + 17, sy + 17, 0xFF8B8B8B);
            g.fill(sx,     sy,     sx + 16, sy + 16, 0xFF373737);
        }
        if (tabBar != null) tabBar.draw(g);
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        renderBackground(g);
        super.render(g, mouseX, mouseY, partialTick);
        recipeArea.render(g, mouseX, mouseY);

        renderModeToggle(g, mouseX, mouseY);
        renderUnsupportedNotice(g);
        renderJeiViewHint(g, mouseX, mouseY);
        renderEditModeBadge(g, mouseX, mouseY);
        // v2.0.13: tier toggle 旧 [Wyvern] 描画は削除 (DE 本体 JEI 表記に置換)
        // renderTierToggle(g, mouseX, mouseY);
        renderNumberLabels(g);
        renderDumpButton(g, mouseX, mouseY);
        renderSettingsButton(g, mouseX, mouseY);
        renderHeatToggle(g, mouseX, mouseY);
        renderQuestionHelp(g, mouseX, mouseY);
        tagBar.render(g, mouseX, mouseY);
        stackBuilder.render(g, mouseX, mouseY);
        energyHelper.render(g, font, mouseX, mouseY);

        if (tabBar != null) {
            CategoryTab hover = tabBar.getHovered(mouseX, mouseY);
            if (hover != null) g.renderTooltip(font, hover.tooltip(), Optional.empty(), mouseX, mouseY);
        }
        recipeArea.renderOverlays(g, mouseX, mouseY);
        recipeArea.renderUserEditTooltip(g, mouseX, mouseY);
        tagBar.renderTooltip(g, font, mouseX, mouseY);
        stackBuilder.renderTooltip(g, font, mouseX, mouseY);
        energyHelper.renderTooltip(g, font, mouseX, mouseY);
        renderTooltip(g, mouseX, mouseY);

        if (!ghostCursor.isEmpty()) {
            int gx = mouseX - 8;
            int gy = mouseY - 8;
            g.renderItem(ghostCursor, gx, gy);
            g.renderItemDecorations(font, ghostCursor, gx, gy);
        }
        if (!dragSpec.isEmpty()) {
            DIV.credit.client.recipe.RecipeArea.renderSpecAt(g, dragSpec, mouseX - 8, mouseY - 8);
        }
        // dropdown は最前面（tooltip より下、cursor より下）
        tagBar.renderOverlay(g, mouseX, mouseY);
    }

    private void renderModeToggle(GuiGraphics g, int mouseX, int mouseY) {
        toggleX = -1;
        if (!DRAFT_STORE.isCraftingCategory(currentCategory)) return;
        String label = "[" + (DRAFT_STORE.getCraftingVariant() == DraftStore.CraftingVariant.SHAPED
            ? "Shaped" : "Shapeless") + "]";
        toggleW = font.width(label) + 4;
        toggleH = font.lineHeight + 2;
        // Settings ボタン (右端) の左に重ならないよう余白を空けて配置
        toggleX = leftPos + imageWidth - toggleW - SETTINGS_W - 6;
        toggleY = recipeAreaTop + 2;
        boolean hover = mouseX >= toggleX && mouseX < toggleX + toggleW
                     && mouseY >= toggleY && mouseY < toggleY + toggleH;
        int bg = hover ? 0xCC404060 : 0x88202040;
        g.fill(toggleX, toggleY, toggleX + toggleW, toggleY + toggleH, bg);
        g.drawString(font, label, toggleX + 2, toggleY + 2, hover ? 0xFFFFFF55 : 0xFFFFFFFF, false);
    }

    /**
     * DE FusionCraftingDraft の tier toggle: settings の左に「[Wyvern]」等を tier 色で。
     * 左クリで次、Shift+左クリで前へ cycle。
     */
    private void renderTierToggle(GuiGraphics g, int mouseX, int mouseY) {
        tierX = -1;
        RecipeDraft draft = recipeArea.getDraft();
        if (draft == null || !draft.canCycleTier()) return;
        String label = "[" + draft.getTierLabel() + "]";
        tierW = font.width(label) + 4;
        tierH = font.lineHeight + 2;
        // settings の左側、[Shaped]toggle と同位置（DE は crafting じゃないので衝突なし）
        tierX = leftPos + imageWidth - tierW - SETTINGS_W - 6;
        tierY = recipeAreaTop + 2;
        boolean hover = mouseX >= tierX && mouseX < tierX + tierW
                     && mouseY >= tierY && mouseY < tierY + tierH;
        int bg = hover ? 0xCC404060 : 0x88202040;
        g.fill(tierX, tierY, tierX + tierW, tierY + tierH, bg);
        g.drawString(font, label, tierX + 2, tierY + 2, draft.getTierColor(), false);
        if (hover) {
            g.renderTooltip(font, Component.translatable("gui.credit.tier.hint"), mouseX, mouseY);
        }
    }

    /** 編集モード時に chois.png を ? icon と同じ位置に出す。hover で原 ID tooltip。 */
    private void renderEditModeBadge(GuiGraphics g, int mouseX, int mouseY) {
        if (!isEditMode()) return;
        int x = leftPos + 2;
        int y = recipeAreaTop + 2;
        boolean hover = mouseX >= x && mouseX < x + CHOIS_W
                     && mouseY >= y && mouseY < y + CHOIS_H;
        if (hover) g.fill(x - 1, y - 1, x + CHOIS_W + 1, y + CHOIS_H + 1, 0x66FFFFFF);
        g.blit(CHOIS_TEX, x, y, 0, 0, CHOIS_W, CHOIS_H, CHOIS_W, CHOIS_H);
        if (hover) {
            g.renderComponentTooltip(font, java.util.List.of(
                Component.translatable("gui.credit.edit.badge").withStyle(ChatFormatting.WHITE),
                Component.literal(String.valueOf(editModeOrigId)).withStyle(ChatFormatting.GRAY)
            ), mouseX, mouseY);
        }
    }

    /**
     * recipe area 上部中央に「JEIで表示」ヒント（settings と同じ高さ）。
     * 未対応カテゴリ時は renderUnsupportedNotice 側を出すので、こちらは draft != null 時のみ。
     * ホバー時のみ「Shift+クリックで表示」tooltip を出して通常時の横幅を抑える。
     */
    private void renderJeiViewHint(GuiGraphics g, int mouseX, int mouseY) {
        if (currentCategory == null || recipeArea.getDraft() == null) return;
        Component msg = Component.translatable("gui.credit.hint.jei_view");
        int tw = font.width(msg);
        // crafting カテゴリは [Shaped]/[Shapeless] toggle が右上にある + ? と被らないよう
        // 左寄せ（chois badge / ? icon の右）。それ以外は中央寄せ。
        int tx;
        if (DRAFT_STORE.isCraftingCategory(currentCategory)) {
            tx = leftPos + 2 + QUESTION_W + 4;  // ? icon の右、4px gap
        } else {
            tx = leftPos + (imageWidth - tw) / 2;
        }
        int ty = recipeAreaTop + 2;
        boolean hover = mouseX >= tx - 3 && mouseX < tx + tw + 3
                     && mouseY >= ty - 2 && mouseY < ty + font.lineHeight + 1;
        g.fill(tx - 3, ty - 2, tx + tw + 3, ty + font.lineHeight + 1, hover ? 0x88000000 : 0x44000000);
        g.drawString(font, msg, tx, ty, hover ? 0xFFCCCCCC : 0xFF777777, false);
        if (hover) {
            g.renderTooltip(font, Component.translatable("gui.credit.hint.jei_view.shift"), mouseX, mouseY);
        }
    }

    private void renderUnsupportedNotice(GuiGraphics g) {
        if (currentCategory == null || recipeArea.getDraft() != null) return;
        Component msg = Component.translatable("gui.credit.notice.unsupported");
        int tw = font.width(msg);
        int tx = leftPos + (imageWidth - tw) / 2;
        int ty = recipeAreaTop + 2;
        g.fill(tx - 3, ty - 2, tx + tw + 3, ty + font.lineHeight + 1, 0x88000000);
        g.drawString(font, msg, tx, ty, 0xFFFF5555, false);
    }

    private void renderNumberLabels(GuiGraphics g) {
        for (int i = 0; i < numericBoxes.size(); i++) {
            EditBox box = numericBoxes.get(i);
            if (!box.visible) continue;
            String label = currentFields.get(i).label();
            g.drawString(font, label, box.getX() - font.width(label) - 3, box.getY() + 2, 0xFFE0E0E0, false);
        }
    }

    private void renderDumpButton(GuiGraphics g, int mouseX, int mouseY) {
        dumpX = -1;
        if (recipeArea.getDraft() == null) return;
        dumpX = leftPos + imageWidth - DUMP_W - 2;
        // numericFields y = recipeAreaBottom - 12 - 2、その上 + EnergyHelper の上にもう少し余白
        dumpY = recipeAreaBottom - 12 - 2 - EnergyHelperWidget.H - 4 - DUMP_H - 4;
        boolean hover = mouseX >= dumpX && mouseX < dumpX + DUMP_W
                     && mouseY >= dumpY && mouseY < dumpY + DUMP_H;
        if (hover) g.fill(dumpX - 1, dumpY - 1, dumpX + DUMP_W + 1, dumpY + DUMP_H + 1, 0x66FFFFFF);
        g.blit(DUMP_TEX, dumpX, dumpY, 0, 0, DUMP_W, DUMP_H, DUMP_W, DUMP_H);
    }

    /**
     * Create カテゴリ別の trick toggle アイコン。drawable rect 中央右下に常時上書き。
     * mixing/packing → heat 3 値切替 (BLAZE_POWDER/_ROD/BARRIER icon)。
     * item_application/deploying → keepHeldItem 2 値切替 (item icon ON/OFF)。
     * これらカテゴリは同時に該当しないため位置を共有する。
     * クリック: 値変更 / Shift+クリック: NONE/false 直接。
     */
    private void renderHeatToggle(GuiGraphics g, int mouseX, int mouseY) {
        RecipeDraft draft = recipeArea.getDraft();
        if (draft == null) return;
        boolean heatMode = draft.canRequireHeat();
        boolean keepMode = !heatMode && draft.canKeepHeldItem();
        if (!heatMode && !keepMode) return;
        net.minecraft.client.renderer.Rect2i rect = recipeArea.getHeatIconRect();
        if (rect == null) return;
        int x = rect.getX();
        int y = rect.getY();
        boolean hover = mouseX >= x && mouseX < x + rect.getWidth()
                     && mouseY >= y && mouseY < y + rect.getHeight();
        g.fill(x - 1, y - 1, x + 19, y + 19, 0xFF8B8B8B);
        g.fill(x,     y,     x + 18, y + 18, 0xFF161628);
        ItemStack icon;
        Component header;
        Component hint;
        if (heatMode) {
            var hl = draft.getHeatLevel();
            icon = switch (hl) {
                case NONE        -> new ItemStack(net.minecraft.world.item.Items.BARRIER);
                case HEATED      -> new ItemStack(net.minecraft.world.item.Items.BLAZE_POWDER);
                case SUPERHEATED -> new ItemStack(net.minecraft.world.item.Items.BLAZE_ROD);
            };
            Component name = Component.translatable("gui.credit.heat." + hl.name().toLowerCase());
            header = Component.translatable("gui.credit.heat.current", name)
                .withStyle(ChatFormatting.WHITE);
            hint = Component.translatable("gui.credit.heat.hint")
                .withStyle(ChatFormatting.DARK_GRAY);
        } else {
            boolean on = draft.isKeepHeldItem();
            icon = on ? new ItemStack(net.minecraft.world.item.Items.SHIELD)
                      : new ItemStack(net.minecraft.world.item.Items.BARRIER);
            Component state = Component.literal(on ? "ON" : "OFF")
                .withStyle(on ? ChatFormatting.GREEN : ChatFormatting.GRAY);
            header = Component.translatable("gui.credit.keep_held.current", state)
                .withStyle(ChatFormatting.WHITE);
            hint = Component.translatable("gui.credit.keep_held.hint")
                .withStyle(ChatFormatting.DARK_GRAY);
        }
        g.renderItem(icon, x + 1, y + 1);
        if (hover) {
            g.fill(x, y, x + 18, y + 18, 0x33FFFFFF);
            g.renderComponentTooltip(font, java.util.List.of(header, hint), mouseX, mouseY);
        }
    }

    /**
     * 編集不可カテゴリの ? icon を recipeArea 左上に描画。
     * hover で hilight、click で tooltip 表示 ON/OFF (ユーザー要望)。
     * 翻訳キー: gui.credit.unsupported.<reason>
     */
    private void renderQuestionHelp(GuiGraphics g, int mouseX, int mouseY) {
        questionX = -1;
        if (currentCategory == null) return;
        String uid = currentCategory.getRecipeType().getUid().toString();
        DIV.credit.client.draft.UnsupportedReason reason =
            DIV.credit.client.draft.DraftStore.getUnsupportedReason(uid);
        if (reason == null) return;
        questionX = leftPos + 2;
        questionY = recipeAreaTop + 2;
        boolean hover = mouseX >= questionX && mouseX < questionX + QUESTION_W
                     && mouseY >= questionY && mouseY < questionY + QUESTION_H;
        if (hover) g.fill(questionX - 1, questionY - 1,
                          questionX + QUESTION_W + 1, questionY + QUESTION_H + 1, 0x66FFFFFF);
        g.blit(QUESTION_TEX, questionX, questionY, 0, 0, QUESTION_W, QUESTION_H, QUESTION_W, QUESTION_H);
        if (questionTooltipOpen) {
            Component header = Component.translatable("gui.credit.unsupported.header")
                .withStyle(ChatFormatting.WHITE);
            Component body = Component.translatable(reason.translationKey())
                .withStyle(ChatFormatting.GRAY);
            Component hint = Component.translatable("gui.credit.unsupported.click_close")
                .withStyle(ChatFormatting.DARK_GRAY);
            g.renderComponentTooltip(font, java.util.List.of(header, body, hint), mouseX, mouseY);
        }
    }

    /** Settings (gear) ボタン：常時表示、画面右上付近。dump とは独立。 */
    private void renderSettingsButton(GuiGraphics g, int mouseX, int mouseY) {
        settingsX = leftPos + imageWidth - SETTINGS_W - 2;
        settingsY = recipeAreaTop + 2;
        boolean hover = mouseX >= settingsX && mouseX < settingsX + SETTINGS_W
                     && mouseY >= settingsY && mouseY < settingsY + SETTINGS_H;
        if (hover) g.fill(settingsX - 1, settingsY - 1, settingsX + SETTINGS_W + 1, settingsY + SETTINGS_H + 1, 0x66FFFFFF);
        g.blit(SETTINGS_TEX, settingsX, settingsY, 0, 0, SETTINGS_W, SETTINGS_H, SETTINGS_W, SETTINGS_H);
    }

    @Override
    protected void containerTick() {
        super.containerTick();
        recipeArea.tick();
        maybeCaptureSnapshot();
    }

    /**
     * 500ms 毎に 1 回だけ hash 比較を実行し、diff があれば snapshot を push。
     * 編集の有無に関わらず check 間隔を debounce することで毎 tick の hash compute を回避。
     * Settings で undo 無効の時は snapshot しない。
     */
    private void maybeCaptureSnapshot() {
        if (!DIV.credit.CreditConfig.UNDO_ENABLED.get()) return;
        long now = System.currentTimeMillis();
        if (now - lastSnapshotCheckMs < SNAPSHOT_DEBOUNCE_MS) return;
        lastSnapshotCheckMs = now;
        long h = DIV.credit.client.undo.UndoHistory.computeHash(DRAFT_STORE, this);
        if (h == lastSnapshotHash) return;
        DIV.credit.client.undo.UndoHistory.INSTANCE.push(
            DIV.credit.client.undo.Snapshot.capture(DRAFT_STORE, this));
        lastSnapshotHash = h;
    }

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        if (button == 0) {
            var resultRect = tagBar.getResultSlotRect();
            DIV.credit.Credit.LOGGER.info(
                "[CraftPattern] mouseClicked ENTRY: pos=({},{}) button={} ghost={} carried={} dragSpec={} finder={} overResult={} resultRect={}",
                (int) mx, (int) my, button,
                ghostCursor.isEmpty() ? "EMPTY" : ghostCursor.getItem().toString(),
                menu.getCarried().isEmpty() ? "EMPTY" : menu.getCarried().getItem().toString(),
                dragSpec.getClass().getSimpleName(),
                tagBar.getFinderSource().isEmpty() ? "EMPTY" : "ACTIVE",
                tagBar.isOverResultSlot(mx, my),
                resultRect == null ? "null" : "(" + resultRect.getX() + "," + resultRect.getY()
                    + "," + resultRect.getWidth() + "x" + resultRect.getHeight() + ")");
        }
        if (tabBar != null && tabBar.mouseClicked(mx, my, button)) return true;

        // Shift+left-click on recipe area → open JEI for current category (sets OriginTracker)
        if (button == 0 && Screen.hasShiftDown() && currentCategory != null
            && recipeArea.isInside(mx, my)) {
            if (DIV.credit.client.jei.JeiNavigation.openCategory(currentCategory)) {
                playClick();
                return true;
            }
        }

        if (button == 0 && toggleX >= 0
            && mx >= toggleX && mx < toggleX + toggleW
            && my >= toggleY && my < toggleY + toggleH) {
            toggleCraftingMode();
            return true;
        }
        // v2.0.13: DE tier cycle — recipe area の non-slot 領域クリックで cycle。
        // 左クリ → 次、Shift+左クリ → 前。
        // 旧 [Wyvern] toggle button は撤去、DE 本体 JEI 表記の上をクリックする想定。
        if (button == 0 && recipeArea.isInside(mx, my)) {
            RecipeDraft drCycle = recipeArea.getDraft();
            if (drCycle != null && drCycle.canCycleTier()) {
                if (recipeArea.findSlotIndexAt(mx, my) < 0) {  // slot 上ではない
                    drCycle.cycleTier(!Screen.hasShiftDown());
                    recipeArea.rebuild();
                    playClick();
                    return true;
                }
            }
        }
        if (button == 0 && dumpX >= 0
            && mx >= dumpX && mx < dumpX + DUMP_W
            && my >= dumpY && my < dumpY + DUMP_H) {
            doDump();
            return true;
        }
        if (button == 0 && settingsX >= 0
            && mx >= settingsX && mx < settingsX + SETTINGS_W
            && my >= settingsY && my < settingsY + SETTINGS_H) {
            playClick();
            Minecraft.getInstance().setScreen(new SettingsScreen(this));
            return true;
        }
        // ? icon: クリックで tooltip 表示 toggle
        if (button == 0 && questionX >= 0
            && mx >= questionX && mx < questionX + QUESTION_W
            && my >= questionY && my < questionY + QUESTION_H) {
            questionTooltipOpen = !questionTooltipOpen;
            playClick();
            return true;
        }
        // ? tooltip 表示中、icon 以外をクリックしたら close (click は通常処理に流す)
        if (questionTooltipOpen) {
            questionTooltipOpen = false;
            // return true せず、他 handler に click を委譲
        }
        // Create カテゴリ別 toggle icon (heat or keepHeldItem)
        RecipeDraft hd = recipeArea.getDraft();
        if (button == 0 && hd != null && (hd.canRequireHeat() || hd.canKeepHeldItem())) {
            net.minecraft.client.renderer.Rect2i hr = recipeArea.getHeatIconRect();
            if (hr != null && mx >= hr.getX() && mx < hr.getX() + hr.getWidth()
                           && my >= hr.getY() && my < hr.getY() + hr.getHeight()) {
                if (hd.canRequireHeat()) {
                    if (Screen.hasShiftDown()) {
                        hd.setHeatLevel(DIV.credit.client.draft.create.HeatLevel.NONE);
                    } else {
                        hd.setHeatLevel(hd.getHeatLevel().cycle());
                    }
                } else {
                    // keepHeldItem: Shift+クリで強制 OFF、通常クリで toggle
                    if (Screen.hasShiftDown()) hd.setKeepHeldItem(false);
                    else                       hd.setKeepHeldItem(!hd.isKeepHeldItem());
                }
                playClick();
                return true;
            }
        }

        // Tag bar: Ctrl+right-click anywhere on the bar clears input + result
        if (button == 1 && Screen.hasControlDown() && tagBar.isOverBar(mx, my)) {
            tagBar.clear();
            playClick();
            return true;
        }
        // Tag bar: namespace toggle (Shift+左クリで GT cleanroom mode toggle)
        if (button == 0 && tagBar.isOverNsSlot(mx, my)) {
            if (Screen.hasShiftDown()) {
                tagBar.toggleCleanroomMode();
            } else {
                tagBar.toggleNamespace();
            }
            playClick();
            return true;
        }
        // cleanroom header click → dropdown 開閉
        if (tagBar.handleCleanroomHeaderClick(mx, my, button)) {
            playClick();
            return true;
        }
        // cleanroom dropdown click → 選択
        if (tagBar.handleCleanroomDropdownClick(mx, my, button)) {
            playClick();
            return true;
        }
        // Tag bar result slot: Shift+右クリで finder source クリア
        if (button == 1 && Screen.hasShiftDown() && tagBar.isOverResultSlot(mx, my)
            && !tagBar.getFinderSource().isEmpty()) {
            tagBar.setFinderSource(DIV.credit.client.draft.IngredientSpec.EMPTY);
            playClick();
            return true;
        }
        // Tag bar result slot: finder アクティブ時はそちら優先
        if (tagBar.handleFinderClick(mx, my, button)) {
            playClick();
            return true;
        }
        // Tag bar result slot: ghost cursor (credit 独自) または vanilla cursor を持って Result slot にクリック
        // → finder source として set + cursor 消費。 finderSource の状態に関わらず処理 (上書き可)。
        // この処理が無いと末尾の `ghostCursor = EMPTY` で消えてしまう or super で vanilla drop されてしまう。
        if (button == 0 && tagBar.isOverResultSlot(mx, my)) {
            ItemStack source = !ghostCursor.isEmpty() ? ghostCursor : menu.getCarried();
            if (!source.isEmpty()) {
                DIV.credit.client.draft.IngredientSpec spec =
                    DIV.credit.client.recipe.RecipeArea.ingredientFromCursor(source);
                if (!spec.isEmpty()) {
                    tagBar.setFinderSource(spec);
                }
                // ghost cursor 経由なら消費。 vanilla cursor (menu.carried) は触らない。
                if (!ghostCursor.isEmpty()) {
                    ghostCursor = ItemStack.EMPTY;
                }
                return true;
            }
        }
        // Tag bar result slot pickup → ghost cursor (finder 非アクティブ + vanilla cursor 空の時のみ)
        if (button == 0 && tagBar.isOverResultSlot(mx, my) && tagBar.getFinderSource().isEmpty()) {
            ItemStack r = tagBar.getResult();
            if (r.isEmpty()) {
                this.ghostCursor = ItemStack.EMPTY;
            } else {
                this.ghostCursor = r.copy();
                DIV.credit.client.draft.IngredientSpec tagSpec =
                    DIV.credit.client.tag.TagItemHelper.specFromNameTag(r);
                if (!tagSpec.isEmpty()) {
                    this.dragSpec = tagSpec;
                    this.dragFromCfg = false;
                }
            }
            return true;
        }
        // Tag bar CFG slot: Shift+右クリで明示クリア。プレーン右クリは何もしない（誤クリック防止）。
        if (button == 1 && Screen.hasShiftDown() && tagBar.isOverCfgSlot(mx, my)) {
            tagBar.setCfgContent(DIV.credit.client.draft.IngredientSpec.EMPTY);
            playClick();
            return true;
        }
        if (button == 1 && tagBar.isOverCfgSlot(mx, my)) {
            // プレーン右クリは消費してクリア処理を飲む（dropdown 開閉等にも繋がない）
            return true;
        }
        // Dropdown のクリック処理（cfg 非空時のみ反応）
        if (tagBar.handleDropdownClick(mx, my, button)) {
            playClick();
            return true;
        }

        // EnergyHelper (GT 用 tier × amperage)
        if (energyHelper.mouseClicked(mx, my, button)) {
            playClick();
            return true;
        }

        // StackBuilder の右クリ系（multiplier 操作）は widget 側で処理
        if (button != 0 && stackBuilder.mouseClicked(mx, my, button)) {
            playClick();
            return true;
        }

        // Drag-from-slot: cursor 空 + 左クリ で source から spec を取得して drag 開始
        if (button == 0 && ghostCursor.isEmpty()) {
            // 1. StackBuilder slot から
            if (stackBuilder.isOverSlot(mx, my) && !stackBuilder.getContent().isEmpty()) {
                this.dragSpec = stackBuilder.getContent();
                this.dragFromCfg = false;
                return true;
            }
            // 2. TagBar CFG slot から
            // CFG は drop 成功で初めてクリアする（drop 失敗時はアイテムを失わない）
            if (tagBar.isOverCfgSlot(mx, my) && !tagBar.getCfgContent().isEmpty()) {
                this.dragSpec = tagBar.getCfgContent();
                this.dragFromCfg = true;
                return true;
            }
            // 3. recipe slot から（編集済み）
            DIV.credit.client.draft.IngredientSpec edited = recipeArea.getEditedSpecAt(mx, my);
            if (edited != null) {
                this.dragSpec = edited;
                this.dragFromCfg = false;
                return true;
            }
        }

        // v2.0.8: cleanroom marker を recipe area 内に drop → draft.cleanroomType に適用
        if (button == 0 && DIV.credit.client.tag.TagBar.isCleanroomMarker(ghostCursor)
            && recipeArea.isInside(mx, my)) {
            String name = DIV.credit.client.tag.TagBar.readCleanroomMarker(ghostCursor);
            RecipeDraft draft = recipeArea.getDraft();
            if (draft instanceof DIV.credit.client.draft.GenericDraft gd) {
                String value = DIV.credit.client.tag.TagBar.CLEANROOM_NONE.equals(name) ? null : name;
                gd.setCleanroomType(value);
                recipeArea.rebuild();
                Component msg = (value == null)
                    ? Component.translatable("gui.credit.cleanroom.cleared").withStyle(ChatFormatting.YELLOW)
                    : Component.translatable("gui.credit.cleanroom.set", value).withStyle(ChatFormatting.AQUA);
                chat(msg);
            } else {
                chat(Component.translatable("gui.credit.cleanroom.unsupported")
                    .withStyle(ChatFormatting.RED));
            }
            ghostCursor = ItemStack.EMPTY;
            playClick();
            return true;
        }
        if (recipeArea.mouseClicked(mx, my, button, ghostCursor)) return true;

        boolean overInvSlot = isOverInventorySlot(mx, my);
        boolean handled = super.mouseClicked(mx, my, button);

        // Vanilla-like: dropping the cursor outside any interactive region clears it
        if (!overInvSlot && !ghostCursor.isEmpty()) {
            ghostCursor = ItemStack.EMPTY;
        }
        return handled;
    }

    @Override
    public boolean mouseReleased(double mx, double my, int button) {
        // ghost cursor / vanilla cursor を Result slot 上で release した場合 → finder source 取り込み + 消費。
        // dragSpec 空チェックは ghost drag (credit dragSpec 経由) と区別するため。
        if (button == 0 && dragSpec.isEmpty() && tagBar.isOverResultSlot(mx, my)) {
            ItemStack source = !ghostCursor.isEmpty() ? ghostCursor : menu.getCarried();
            if (!source.isEmpty()) {
                DIV.credit.client.draft.IngredientSpec spec =
                    DIV.credit.client.recipe.RecipeArea.ingredientFromCursor(source);
                if (!spec.isEmpty()) {
                    tagBar.setFinderSource(spec);
                }
                if (!ghostCursor.isEmpty()) {
                    ghostCursor = ItemStack.EMPTY;
                }
                return true;
            }
        }
        if (button == 0 && !dragSpec.isEmpty()) {
            // 「他の場所」に正常に置けたか。CFG → CFG 同一スロットは「移動成功」ではない。
            boolean placedElsewhere = false;
            if (stackBuilder.isOverSlot(mx, my)) {
                stackBuilder.setContent(dragSpec);
                placedElsewhere = true;
                playClick();
            } else if (tagBar.isOverCfgSlot(mx, my)) {
                // CFG → CFG 同一なら no-op（dragFromCfg なので setCfgContent も実質変化なし）
                if (!dragFromCfg) {
                    tagBar.setCfgContent(dragSpec);
                    placedElsewhere = true;
                    playClick();
                }
            } else if (tagBar.isOverResultSlot(mx, my)) {
                // Result slot に drop → finder mode 起動（item/fluid のみ受理）
                tagBar.setFinderSource(dragSpec);
                if (!tagBar.getFinderSource().isEmpty()) {
                    placedElsewhere = true;
                    playClick();
                }
            } else {
                int slotIdx = recipeArea.findSlotIndexAt(mx, my);
                if (slotIdx >= 0) {
                    boolean ok = recipeArea.setSlotIngredient(slotIdx, dragSpec);
                    if (ok) {
                        placedElsewhere = true;
                        playClick();
                    }
                }
            }
            // CFG 由来 + 他所に成功 → 元 CFG をクリア。失敗時は触らずアイテム保持。
            if (dragFromCfg && placedElsewhere) {
                tagBar.setCfgContent(DIV.credit.client.draft.IngredientSpec.EMPTY);
            }
            dragSpec = DIV.credit.client.draft.IngredientSpec.EMPTY;
            dragFromCfg = false;
            return true;
        }
        return super.mouseReleased(mx, my, button);
    }

    private boolean isOverInventorySlot(double mx, double my) {
        for (Slot s : menu.slots) {
            int sx = leftPos + s.x;
            int sy = topPos  + s.y;
            if (mx >= sx && mx < sx + 16 && my >= sy && my < sy + 16) return true;
        }
        return false;
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double delta) {
        if (tabBar != null && tabBar.mouseScrolled(mx, my, delta)) return true;
        if (recipeArea.mouseScrolled(mx, my, delta)) return true;
        return super.mouseScrolled(mx, my, delta);
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        // Ctrl+Z: undo / Ctrl+Shift+Z: redo / Ctrl+Y: redo (両対応)
        // GLFW: Z=90, Y=89, MOD_CONTROL=2, MOD_SHIFT=1
        if ((modifiers & 2) != 0) {
            if (keyCode == 90 && (modifiers & 1) == 0) return doUndo();
            if (keyCode == 90 && (modifiers & 1) != 0) return doRedo();
            if (keyCode == 89) return doRedo();
        }
        // While any text field is focused, swallow container shortcuts (E to close, Q to drop,
        // 1-9 hotbar swap, middle-click pick) so they don't trigger while typing.
        // ESC and editing keys (arrows, backspace, etc.) propagate normally.
        if (isAnyTextFieldFocused()) {
            var opts = this.minecraft.options;
            if (opts.keyInventory.matches(keyCode, scanCode)) return true;
            if (opts.keyDrop.matches(keyCode, scanCode))      return true;
            if (opts.keyPickItem.matches(keyCode, scanCode))  return true;
            for (var k : opts.keyHotbarSlots) {
                if (k.matches(keyCode, scanCode)) return true;
            }
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    private boolean doUndo() {
        if (!DIV.credit.CreditConfig.UNDO_ENABLED.get()) return true;
        DIV.credit.client.undo.Snapshot prev = DIV.credit.client.undo.UndoHistory.INSTANCE.undo();
        if (prev == null) return true; // history empty: 飲んで終わり
        prev.applyTo(DRAFT_STORE, this);
        // 直後の tick で再 capture しないよう hash を更新
        lastSnapshotHash = DIV.credit.client.undo.UndoHistory.computeHash(DRAFT_STORE, this);
        lastSnapshotCheckMs = System.currentTimeMillis();
        playClick();
        return true;
    }

    private boolean doRedo() {
        if (!DIV.credit.CreditConfig.UNDO_ENABLED.get()) return true;
        DIV.credit.client.undo.Snapshot next = DIV.credit.client.undo.UndoHistory.INSTANCE.redo();
        if (next == null) return true;
        next.applyTo(DRAFT_STORE, this);
        lastSnapshotHash = DIV.credit.client.undo.UndoHistory.computeHash(DRAFT_STORE, this);
        lastSnapshotCheckMs = System.currentTimeMillis();
        playClick();
        return true;
    }

    private boolean isAnyTextFieldFocused() {
        for (EditBox box : numericBoxes) {
            if (box.isFocused()) return true;
        }
        return tagBar.isEditBoxFocused() || stackBuilder.isEditBoxFocused();
    }

    @Override
    protected void slotClicked(Slot slot, int slotId, int mouseButton, ClickType type) {
        if (type != ClickType.PICKUP || slot == null) return;
        if (mouseButton == 0) {
            if (slot.hasItem()) {
                ItemStack stack = slot.getItem().copy();
                stack.setCount(1);
                this.ghostCursor = stack;
            } else {
                this.ghostCursor = ItemStack.EMPTY;
            }
        }
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public void removed() {
        super.removed();
        var mode = DIV.credit.CreditConfig.EDIT_PERSISTENCE.get();
        switch (mode) {
            case MIN -> DRAFT_STORE.clear();
            case MAX -> DIV.credit.client.draft.DraftPersistence.save(DRAFT_STORE);
            case NORMAL -> {} // keep in-memory until game exits
        }
    }
}