package DIV.credit.client.tag;

import DIV.credit.client.draft.IngredientSpec;
import DIV.credit.client.jei.mek.MekanismIngredientAdapter;
import DIV.credit.jei.CraftPatternJeiPlugin;
import mezz.jei.api.forge.ForgeTypes;
import mezz.jei.api.runtime.IJeiRuntime;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraftforge.fml.ModList;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * レシピエリア下部のタグ検索バー。
 * 構成（左→右）：[namespace toggle] [cfg slot] [EditBox or dropdown] [result slot]
 *
 * cfg slot に item/fluid/gas が入ると EditBox は隠れ、ItemOption 選択 dropdown が表示される。
 * 利用可能オプションは入っている spec の namespace で決まる：
 *   minecraft → VANILLA_DAMAGE / VANILLA_KEEP
 *   gtceu     → GT_CATALYST / GT_CHANCE
 *   mekanism  → なし（編集できる機能がありません）
 */
public class TagBar {

    public static final int H      = 18;
    public static final int SLOT_W = 16;

    private static final String NS_MINECRAFT = "minecraft";
    private static final String NS_FORGE     = "forge";

    /** 短期記憶：namespace は static で保持。 */
    private static String currentNamespace = NS_MINECRAFT;

    /** 現在開いてる recipe category の namespace。CFG dropdown options 判定に使う。 */
    @org.jetbrains.annotations.Nullable
    private String categoryNamespace = null;

    private EditBox box;
    private ItemStack resultStack = ItemStack.EMPTY;
    private int x, y, width;
    private int nsSlotX,     nsSlotY;
    private int cfgSlotX,    cfgSlotY;
    private int resultSlotX, resultSlotY;
    private int boxX, boxY, boxW;
    private boolean visible = true;

    // CFG slot 状態
    private IngredientSpec cfgContent = IngredientSpec.EMPTY;
    private boolean dropdownOpen = false;
    private static final int OPT_ROW_H = 11;

    /** EditBox の入力モード。CFG content と option に応じて切替。 */
    private enum BoxMode { TAG, CHANCE, HIDDEN }
    private BoxMode boxMode = BoxMode.TAG;

    // result slot tag-finder 状態
    /** result slot に drop された source（item/fluid のみ）。空の時は通常 name_tag 表示。 */
    private IngredientSpec finderSource = IngredientSpec.EMPTY;
    /** finderSource から列挙したタグ ID 一覧（display 用にソート済）。 */
    private final java.util.List<ResourceLocation> finderTags = new java.util.ArrayList<>();
    private boolean finderDropdownOpen = false;
    private static final int FINDER_ROW_H = 11;
    private int finderListX = -1, finderListY = -1, finderListW = 0, finderListH = 0;
    /** dropdown 再オープン用の ▾ ボタン領域（CHANCE モード時のみ）。 */
    private int reopenBtnX = -1, reopenBtnY = -1;
    private static final int REOPEN_BTN_W = 10;
    private static final java.util.regex.Pattern CHANCE_FILTER =
        java.util.regex.Pattern.compile("[0-9,]*");

    // ─── v2.0.8 cleanroom mode ───
    /** GT カテゴリ時に namespace slot Shift+click で ON。category 切替で OFF。 */
    private boolean cleanroomMode = false;
    /** 選択された cleanroom 名 (null = none / 未選択)。 */
    @org.jetbrains.annotations.Nullable
    private String selectedCleanroom = null;
    private boolean cleanroomDropdownOpen = false;
    /** 表示用キャッシュ。category 切替や mode 切替時に refresh。 */
    private java.util.List<String> cleanroomChoices = java.util.List.of();
    /** marker アイテムの NBT tag key — drag-drop で検出する。 */
    public static final String CLEANROOM_MARKER_TAG = "credit_cleanroom_marker";
    /** "none" を内部表現する placeholder string。NBT の値として保存する。 */
    public static final String CLEANROOM_NONE = "__none__";

    public void init(Screen screen, Font font, int x, int y, int width, Consumer<EditBox> register) {
        this.x = x;
        this.y = y;
        this.width = width;
        this.nsSlotX     = x + 1;
        this.nsSlotY     = y + 1;
        this.cfgSlotX    = nsSlotX + SLOT_W + 2;
        this.cfgSlotY    = y + 1;
        this.resultSlotX = x + width - SLOT_W - 1;
        this.resultSlotY = y + 1;
        this.boxX = cfgSlotX + SLOT_W + 4;
        this.boxY = y + 3;
        this.boxW = resultSlotX - boxX - 2;
        this.box = new EditBox(font, boxX, boxY, boxW, 12, Component.literal("Tag"));
        this.box.setMaxLength(80);
        this.box.setResponder(this::onBoxChanged);
        this.box.setFilter(s -> {
            // CHANCE モード時は数字と , のみ受理
            if (boxMode == BoxMode.CHANCE) return CHANCE_FILTER.matcher(s).matches();
            return true;
        });
        register.accept(this.box);
    }

    public void setVisible(boolean v) {
        this.visible = v;
        if (!v) {
            this.resultStack = ItemStack.EMPTY;
            this.cfgContent = IngredientSpec.EMPTY;
            this.dropdownOpen = false;
            this.finderSource = IngredientSpec.EMPTY;
            this.finderTags.clear();
            this.finderDropdownOpen = false;
        }
        applyBoxMode();
    }

    public boolean isVisible() {
        return visible;
    }

    public boolean isEditBoxFocused() {
        return box != null && box.isFocused();
    }

    // --- Undo snapshot 用 ---
    public String getBoxValue() {
        return box == null ? "" : box.getValue();
    }

    public void setBoxValue(String s) {
        if (box != null) box.setValue(s == null ? "" : s);
    }

    public ItemStack getNsIcon() {
        if (cleanroomMode) {
            // GT plascrete を使う。GT 不在時は IRON_BLOCK fallback。
            Item ply = BuiltInRegistries.ITEM.get(new ResourceLocation("gtceu", "plascrete"));
            if (ply != null && ply != Items.AIR) return new ItemStack(ply);
            return new ItemStack(Items.IRON_BLOCK);
        }
        return new ItemStack(currentNamespace.equals(NS_FORGE) ? Items.ANVIL : Items.GRASS_BLOCK);
    }

    // ─── v2.0.8 cleanroom mode API ───
    public boolean isCleanroomMode() { return cleanroomMode; }

    /** GT カテゴリ時のみ呼ばれる想定。toggle = ON ↔ OFF。 */
    public void toggleCleanroomMode() {
        if (!isGtCategory()) {
            cleanroomMode = false;  // 非 GT では強制 OFF
            return;
        }
        cleanroomMode = !cleanroomMode;
        cleanroomDropdownOpen = cleanroomMode;
        if (cleanroomMode) {
            cleanroomChoices = DIV.credit.client.draft.gt.GTSupport.getAllCleanroomNames();
            // none 選択肢を追加
            java.util.List<String> withNone = new java.util.ArrayList<>(cleanroomChoices.size() + 1);
            withNone.add(CLEANROOM_NONE);
            withNone.addAll(cleanroomChoices);
            cleanroomChoices = withNone;
        } else {
            cleanroomDropdownOpen = false;
            selectedCleanroom = null;
            resultStack = ItemStack.EMPTY;
        }
    }

    private boolean isGtCategory() {
        return categoryNamespace != null
            && ("gtceu".equals(categoryNamespace) || "gtcsolo".equals(categoryNamespace));
    }

    /** 選択した cleanroom 名で marker stack を生成。none なら none marker。 */
    private ItemStack buildCleanroomMarker(String name) {
        Item plascrete = BuiltInRegistries.ITEM.get(new ResourceLocation("gtceu", "plascrete"));
        Item base = (plascrete != null && plascrete != Items.AIR) ? plascrete : Items.IRON_BLOCK;
        ItemStack stack = new ItemStack(base);
        stack.getOrCreateTag().putString(CLEANROOM_MARKER_TAG, name);
        Component label = CLEANROOM_NONE.equals(name)
            ? Component.translatable("gui.credit.tagbar.cleanroom.marker_none").withStyle(ChatFormatting.RED)
            : Component.translatable("gui.credit.tagbar.cleanroom.marker", name).withStyle(ChatFormatting.AQUA);
        stack.setHoverName(label);
        return stack;
    }

    /** ItemStack が cleanroom marker か判定。null safe. */
    public static boolean isCleanroomMarker(ItemStack stack) {
        if (stack == null || stack.isEmpty() || !stack.hasTag()) return false;
        return stack.getTag().contains(CLEANROOM_MARKER_TAG);
    }

    /** Marker から cleanroom 名取得。none なら CLEANROOM_NONE 文字列。 */
    public static String readCleanroomMarker(ItemStack stack) {
        if (!isCleanroomMarker(stack)) return null;
        return stack.getTag().getString(CLEANROOM_MARKER_TAG);
    }

    public boolean isOverNsSlot(double mx, double my) {
        return visible
            && mx >= nsSlotX && mx < nsSlotX + SLOT_W
            && my >= nsSlotY && my < nsSlotY + SLOT_W;
    }

    public boolean isOverCfgSlot(double mx, double my) {
        return visible
            && mx >= cfgSlotX && mx < cfgSlotX + SLOT_W
            && my >= cfgSlotY && my < cfgSlotY + SLOT_W;
    }

    /** JEI ghost handler 用：CFG slot の screen rect。非表示時 null。 */
    @org.jetbrains.annotations.Nullable
    public net.minecraft.client.renderer.Rect2i getCfgSlotRect() {
        if (!visible) return null;
        return new net.minecraft.client.renderer.Rect2i(cfgSlotX, cfgSlotY, SLOT_W, SLOT_W);
    }

    /** JEI ghost handler 用：Result slot の screen rect。非表示時 null。 */
    @org.jetbrains.annotations.Nullable
    public net.minecraft.client.renderer.Rect2i getResultSlotRect() {
        if (!visible) return null;
        return new net.minecraft.client.renderer.Rect2i(resultSlotX, resultSlotY, SLOT_W, SLOT_W);
    }

    public IngredientSpec getFinderSource() {
        return finderSource;
    }

    /**
     * Result slot に item/fluid を投入。タグ列挙して dropdown を自動オープン。
     * Gas は registry tag 機構が異なるため未対応（無視）。空 spec で finder 解除。
     */
    public void setFinderSource(IngredientSpec spec) {
        if (spec == null || spec.isEmpty()) {
            finderSource = IngredientSpec.EMPTY;
            finderTags.clear();
            finderDropdownOpen = false;
            return;
        }
        IngredientSpec base = spec.unwrap();
        // Item / Fluid のみ対応
        if (!(base instanceof IngredientSpec.Item) && !(base instanceof IngredientSpec.Fluid)) {
            return;
        }
        finderSource = base;
        finderTags.clear();
        collectTags(base, finderTags);
        finderDropdownOpen = !finderTags.isEmpty();
    }

    private static void collectTags(IngredientSpec base, java.util.List<ResourceLocation> out) {
        try {
            if (base instanceof IngredientSpec.Item it && !it.stack().isEmpty()) {
                it.stack().getTags().forEach(tk -> out.add(tk.location()));
            } else if (base instanceof IngredientSpec.Fluid fl && !fl.stack().isEmpty()) {
                fl.stack().getFluid().builtInRegistryHolder().tags()
                    .forEach(tk -> out.add(tk.location()));
            }
        } catch (Exception ignored) {}
        out.sort(java.util.Comparator.comparing(ResourceLocation::toString));
    }

    public boolean isOverResultSlot(double mx, double my) {
        return visible
            && mx >= resultSlotX && mx < resultSlotX + SLOT_W
            && my >= resultSlotY && my < resultSlotY + SLOT_W;
    }

    public boolean isOverBar(double mx, double my) {
        return visible && mx >= x && mx < x + width && my >= y && my < y + H;
    }

    /** 入力と結果を消去。Ctrl+右クリで呼ばれる。 */
    public void clear() {
        if (box != null) box.setValue("");
        resultStack = ItemStack.EMPTY;
    }

    public ItemStack getResult() {
        return resultStack;
    }

    public IngredientSpec getCfgContent() {
        return cfgContent;
    }

    /** CFG slot 設定。空にする時は dropdown も閉じる。option が現カテゴリで無効なら剥がす。 */
    public void setCfgContent(IngredientSpec spec) {
        if (spec == null || spec.isEmpty()) {
            this.cfgContent = IngredientSpec.EMPTY;
            this.dropdownOpen = false;
        } else {
            this.cfgContent = spec;
            // dropped spec に option が乗ってる場合、現カテゴリで利用可能か検証
            if (cfgContent.option() != IngredientSpec.ItemOption.NONE) {
                var opts = optionsForCategory();
                if (!opts.contains(cfgContent.option())) {
                    cfgContent = cfgContent.withOption(IngredientSpec.ItemOption.NONE);
                }
            }
        }
        applyBoxMode();
    }

    /**
     * cfg state に応じて EditBox の visibility / value / mode を更新。
     * - cfg empty               → TAG mode (visible, タグ入力)
     * - cfg + opt = GT_CHANCE   → CHANCE mode (visible, "300,100" 入力)
     * - cfg + 他 opt / NONE     → HIDDEN (dropdown header に置換)
     */
    private void applyBoxMode() {
        if (box == null) return;
        BoxMode newMode;
        if (cfgContent.isEmpty()) {
            newMode = BoxMode.TAG;
        } else if (cfgContent.option() == IngredientSpec.ItemOption.GT_CHANCE
                || cfgContent.option() == IngredientSpec.ItemOption.CREATE_CHANCE) {
            newMode = BoxMode.CHANCE;
        } else {
            newMode = BoxMode.HIDDEN;
        }
        boolean show = visible && newMode != BoxMode.HIDDEN;
        box.visible = show;
        box.active  = show;
        if (newMode != boxMode) {
            boxMode = newMode;
            if (newMode == BoxMode.TAG) {
                box.setValue("");
            } else if (newMode == BoxMode.CHANCE) {
                if (cfgContent instanceof IngredientSpec.Configured c) {
                    box.setValue(c.chanceMille() + "," + c.tierBoost());
                } else {
                    box.setValue("1000,0");
                }
            }
        } else if (newMode == BoxMode.CHANCE && cfgContent instanceof IngredientSpec.Configured c) {
            // 同じモードでも cfg が更新された時は box value を最新化
            String want = c.chanceMille() + "," + c.tierBoost();
            if (!want.equals(box.getValue())) box.setValue(want);
        }
    }

    public void toggleNamespace() {
        currentNamespace = currentNamespace.equals(NS_MINECRAFT) ? NS_FORGE : NS_MINECRAFT;
        if (box != null) onChange(box.getValue());
    }

    /**
     * 現在のレシピカテゴリ namespace を設定。CFG dropdown options 判定に使う。
     * 設定中の option が新カテゴリで利用不可なら NONE に戻す。
     */
    public void setCategoryNamespace(@org.jetbrains.annotations.Nullable String ns) {
        this.categoryNamespace = ns;
        // カテゴリ遷移後に古い option が新カテゴリで無効なら剥がす
        if (!cfgContent.isEmpty() && cfgContent.option() != IngredientSpec.ItemOption.NONE) {
            var opts = optionsForCategory();
            if (!opts.contains(cfgContent.option())) {
                cfgContent = cfgContent.withOption(IngredientSpec.ItemOption.NONE);
                applyBoxMode();
            }
        }
        // v2.0.8: 非 GT カテゴリに切替 → cleanroom mode 自動 OFF (アイコン金床/草に戻る)
        if (cleanroomMode && !isGtCategory()) {
            cleanroomMode = false;
            cleanroomDropdownOpen = false;
            selectedCleanroom = null;
            if (isCleanroomMarker(resultStack)) resultStack = ItemStack.EMPTY;
        }
    }

    /** cleanroom header クリック → dropdown 開閉。BuilderScreen から呼ぶ。 */
    public boolean handleCleanroomHeaderClick(double mx, double my, int button) {
        if (!cleanroomMode) return false;
        if (button != 0) return false;
        if (mx < boxX || mx >= boxX + boxW || my < boxY - 1 || my >= boxY + 12) return false;
        cleanroomDropdownOpen = !cleanroomDropdownOpen;
        return true;
    }

    public void render(GuiGraphics g, int mouseX, int mouseY) {
        if (!visible) return;
        // Namespace slot
        drawSlot(g, nsSlotX, nsSlotY, isOverNsSlot(mouseX, mouseY));
        g.renderItem(getNsIcon(), nsSlotX, nsSlotY);
        // CFG slot
        drawSlot(g, cfgSlotX, cfgSlotY, isOverCfgSlot(mouseX, mouseY));
        renderCfgContent(g);
        // Box 描画モード分岐
        reopenBtnX = -1;
        if (cleanroomMode) {
            // Cleanroom mode: dropdown-header 風表示 (box 領域に「Cleanroom: <selected>」)
            renderCleanroomHeader(g, mouseX, mouseY);
        } else if (cfgContent.isEmpty()) {
            // TAG mode: 普通の EditBox（追加描画なし）
        } else if (boxMode == BoxMode.CHANCE) {
            renderReopenButton(g, mouseX, mouseY);
        } else {
            renderDropdownHeader(g, mouseX, mouseY);
        }
        // Result slot — finderSource があればそれを優先描画
        drawSlot(g, resultSlotX, resultSlotY, isOverResultSlot(mouseX, mouseY));
        if (!finderSource.isEmpty()) {
            renderFinderSource(g);
        } else if (!resultStack.isEmpty()) {
            g.renderItem(resultStack, resultSlotX, resultSlotY);
        }
    }

    /** cleanroom mode: dropdown header を box 領域に。 */
    private void renderCleanroomHeader(GuiGraphics g, int mouseX, int mouseY) {
        Font font = Minecraft.getInstance().font;
        boolean hover = mouseX >= boxX && mouseX < boxX + boxW
                     && mouseY >= boxY - 1 && mouseY < boxY + 12;
        g.fill(boxX - 1, boxY - 1, boxX + boxW + 1, boxY + 12, 0xFF8B8B8B);
        g.fill(boxX,     boxY,     boxX + boxW,     boxY + 11, 0xFF002030);
        if (hover) g.fill(boxX, boxY, boxX + boxW, boxY + 11, 0x33FFFFFF);
        Component label;
        if (selectedCleanroom == null) {
            label = Component.translatable("gui.credit.tagbar.cleanroom.choose").withStyle(ChatFormatting.YELLOW);
        } else if (CLEANROOM_NONE.equals(selectedCleanroom)) {
            label = Component.translatable("gui.credit.tagbar.cleanroom.selected_none")
                .withStyle(ChatFormatting.RED);
        } else {
            label = Component.translatable("gui.credit.tagbar.cleanroom.selected", selectedCleanroom)
                .withStyle(ChatFormatting.AQUA);
        }
        g.drawString(font, label, boxX + 3, boxY + 2, 0xFFFFFFFF, false);
        String arrow = cleanroomDropdownOpen ? "▴" : "▾";
        int aw = font.width(arrow);
        g.drawString(font, arrow, boxX + boxW - aw - 3, boxY + 2, 0xFFAAAAAA, false);
    }

    private void renderFinderSource(GuiGraphics g) {
        try {
            IngredientSpec base = finderSource.unwrap();
            if (base instanceof IngredientSpec.Item it && !it.stack().isEmpty()) {
                g.renderItem(it.stack(), resultSlotX, resultSlotY);
                return;
            }
            IJeiRuntime rt = CraftPatternJeiPlugin.runtime;
            if (rt == null) return;
            if (base instanceof IngredientSpec.Fluid fl && !fl.stack().isEmpty()) {
                rt.getIngredientManager().getIngredientRenderer(mezz.jei.api.forge.ForgeTypes.FLUID_STACK)
                    .render(g, fl.stack(), resultSlotX, resultSlotY);
            }
        } catch (Exception ignored) {}
    }

    /** dropdown が開いている時のリスト本体。最前面で描くため別メソッド。 */
    public void renderOverlay(GuiGraphics g, int mouseX, int mouseY) {
        if (!visible) return;
        renderFinderDropdown(g, mouseX, mouseY);
        renderCfgDropdown(g, mouseX, mouseY);
        renderCleanroomDropdown(g, mouseX, mouseY);
    }

    /** cleanroom dropdown 本体。none + getAllCleanroomNames(). */
    private int crListX = -1, crListY = -1, crListW = 0, crListH = 0;
    private void renderCleanroomDropdown(GuiGraphics g, int mouseX, int mouseY) {
        crListX = -1;
        if (!cleanroomMode || !cleanroomDropdownOpen || cleanroomChoices.isEmpty()) return;
        Font font = Minecraft.getInstance().font;
        int rowH = OPT_ROW_H;
        int listH = cleanroomChoices.size() * rowH;
        int listY = boxY + 12 + 1;
        crListX = boxX;
        crListY = listY;
        crListW = boxW;
        crListH = listH;
        g.fill(boxX - 1, listY - 1, boxX + boxW + 1, listY + listH + 1, 0xFF8B8B8B);
        g.fill(boxX,     listY,     boxX + boxW,     listY + listH,     0xFF002030);
        for (int i = 0; i < cleanroomChoices.size(); i++) {
            int rowY = listY + i * rowH;
            String name = cleanroomChoices.get(i);
            boolean hover = mouseX >= boxX && mouseX < boxX + boxW
                         && mouseY >= rowY && mouseY < rowY + rowH;
            boolean selected = name.equals(selectedCleanroom);
            if (hover)    g.fill(boxX, rowY, boxX + boxW, rowY + rowH, 0x55FFFFFF);
            if (selected) g.fill(boxX, rowY, boxX + boxW, rowY + rowH, 0x3366CCFF);
            String label = CLEANROOM_NONE.equals(name) ? "(none)" : name;
            int color = CLEANROOM_NONE.equals(name) ? 0xFFFF8888
                      : (hover ? 0xFFFFFF55 : 0xFFE0E0E0);
            g.drawString(font, label, boxX + 3, rowY + 2, color, false);
        }
    }

    /** dropdown row click 検知 + 選択処理。BuilderScreen から委譲。 */
    public boolean handleCleanroomDropdownClick(double mx, double my, int button) {
        if (!cleanroomMode || !cleanroomDropdownOpen) return false;
        if (button != 0) return false;
        if (crListX < 0) return false;
        if (mx < crListX || mx >= crListX + crListW
         || my < crListY || my >= crListY + crListH) return false;
        int idx = (int) ((my - crListY) / OPT_ROW_H);
        if (idx < 0 || idx >= cleanroomChoices.size()) return false;
        String picked = cleanroomChoices.get(idx);
        selectedCleanroom = picked;
        cleanroomDropdownOpen = false;
        // result slot に marker を配置
        resultStack = buildCleanroomMarker(picked);
        return true;
    }

    /** Result slot の finder dropdown（タグ一覧）。 */
    private void renderFinderDropdown(GuiGraphics g, int mouseX, int mouseY) {
        finderListX = -1;
        if (!finderDropdownOpen || finderTags.isEmpty()) return;
        Font font = Minecraft.getInstance().font;
        int rowH = FINDER_ROW_H;
        // 幅 = 最長タグ + padding。最低 80px。
        int w = 80;
        for (ResourceLocation tag : finderTags) {
            w = Math.max(w, font.width(tag.toString()) + 8);
        }
        // 右端は result slot の右端、左へ伸ばす
        finderListX = resultSlotX + SLOT_W - w;
        // ただし TagBar 左端より左には行かない
        if (finderListX < x + 1) finderListX = x + 1;
        finderListW = w;
        finderListY = resultSlotY + SLOT_W + 1;
        finderListH = finderTags.size() * rowH;
        // backdrop
        g.fill(finderListX - 1, finderListY - 1,
               finderListX + finderListW + 1, finderListY + finderListH + 1, 0xFF8B8B8B);
        g.fill(finderListX, finderListY,
               finderListX + finderListW, finderListY + finderListH, 0xFF1A1A2E);
        // rows
        for (int i = 0; i < finderTags.size(); i++) {
            int rowY = finderListY + i * rowH;
            boolean hover = mouseX >= finderListX && mouseX < finderListX + finderListW
                         && mouseY >= rowY && mouseY < rowY + rowH;
            if (hover) g.fill(finderListX, rowY, finderListX + finderListW, rowY + rowH, 0x55FFFFFF);
            String label = finderTags.get(i).toString();
            g.drawString(font, label, finderListX + 4, rowY + 2,
                hover ? 0xFFFFFF55 : 0xFFE0E0E0, false);
        }
    }

    private void renderCfgDropdown(GuiGraphics g, int mouseX, int mouseY) {
        if (!dropdownOpen || cfgContent.isEmpty()) return;
        var opts = optionsForCategory();
        if (opts.isEmpty()) return;
        Font font = Minecraft.getInstance().font;
        int rowH = OPT_ROW_H;
        int listH = opts.size() * rowH;
        int listY = boxY + 12 + 1;          // EditBox の 1px 下
        // backdrop
        g.fill(boxX - 1, listY - 1, boxX + boxW + 1, listY + listH + 1, 0xFF8B8B8B);
        g.fill(boxX,     listY,     boxX + boxW,     listY + listH,     0xFF1A1A2E);
        // rows
        for (int i = 0; i < opts.size(); i++) {
            int rowY = listY + i * rowH;
            boolean hover = mouseX >= boxX && mouseX < boxX + boxW
                         && mouseY >= rowY && mouseY < rowY + rowH;
            boolean selected = (cfgContent.option() == opts.get(i));
            if (hover)    g.fill(boxX, rowY, boxX + boxW, rowY + rowH, 0x55FFFFFF);
            if (selected) g.fill(boxX, rowY, boxX + boxW, rowY + rowH, 0x33FFEE66);
            Component label = Component.translatable(nameKey(opts.get(i)));
            g.drawString(font, label, boxX + 3, rowY + 2,
                hover ? 0xFFFFFF55 : 0xFFE0E0E0, false);
        }
    }

    private void renderCfgContent(GuiGraphics g) {
        if (cfgContent.isEmpty()) return;
        try {
            IngredientSpec base = cfgContent.unwrap();
            if (base instanceof IngredientSpec.Item it && !it.stack().isEmpty()) {
                g.renderItem(it.stack(), cfgSlotX, cfgSlotY);
                g.renderItemDecorations(Minecraft.getInstance().font, it.stack(), cfgSlotX, cfgSlotY);
                return;
            }
            IJeiRuntime rt = CraftPatternJeiPlugin.runtime;
            if (rt == null) return;
            if (base instanceof IngredientSpec.Fluid fl && !fl.stack().isEmpty()) {
                rt.getIngredientManager().getIngredientRenderer(ForgeTypes.FLUID_STACK)
                    .render(g, fl.stack(), cfgSlotX, cfgSlotY);
                return;
            }
            if (base instanceof IngredientSpec.Gas gas && gas.gasId() != null) {
                if (!ModList.get().isLoaded("mekanism")) return;
                renderGas(g, rt, gas, cfgSlotX, cfgSlotY);
            }
        } catch (Exception ignored) {}
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static void renderGas(GuiGraphics g, IJeiRuntime rt, IngredientSpec.Gas gas, int x, int y) {
        var gasStack = MekanismIngredientAdapter.toGasStack(gas);
        if (gasStack.isEmpty()) return;
        var type = mekanism.client.jei.MekanismJEI.TYPE_GAS;
        var renderer = (mezz.jei.api.ingredients.IIngredientRenderer) rt.getIngredientManager().getIngredientRenderer(type);
        renderer.render(g, gasStack, x, y);
    }

    /** CHANCE モード時、EditBox 右端に ▾ ボタンを重ねて描画。dropdown 再オープン用。 */
    private void renderReopenButton(GuiGraphics g, int mouseX, int mouseY) {
        Font font = Minecraft.getInstance().font;
        this.reopenBtnX = boxX + boxW - REOPEN_BTN_W;
        this.reopenBtnY = boxY;
        boolean hover = mouseX >= reopenBtnX && mouseX < reopenBtnX + REOPEN_BTN_W
                     && mouseY >= reopenBtnY && mouseY < reopenBtnY + 11;
        // 背景（EditBox の上に塗りつぶし、テキストとの被りを避ける）
        g.fill(reopenBtnX, reopenBtnY, reopenBtnX + REOPEN_BTN_W, reopenBtnY + 11,
            hover ? 0xCC404060 : 0xAA1A1A2E);
        String arrow = dropdownOpen ? "▴" : "▾";
        int aw = font.width(arrow);
        g.drawString(font, arrow,
            reopenBtnX + (REOPEN_BTN_W - aw) / 2 + 1,
            reopenBtnY + 2,
            hover ? 0xFFFFFF55 : 0xFFAAAAAA, false);
    }

    private void renderDropdownHeader(GuiGraphics g, int mouseX, int mouseY) {
        Font font = Minecraft.getInstance().font;
        boolean hover = mouseX >= boxX && mouseX < boxX + boxW
                     && mouseY >= boxY - 1 && mouseY < boxY + 12;
        // backdrop
        g.fill(boxX - 1, boxY - 1, boxX + boxW + 1, boxY + 12, 0xFF8B8B8B);
        g.fill(boxX,     boxY,     boxX + boxW,     boxY + 11, 0xFF000000);
        if (hover) g.fill(boxX, boxY, boxX + boxW, boxY + 11, 0x33FFFFFF);
        var opts = optionsForCategory();
        Component label;
        if (opts.isEmpty()) {
            label = Component.translatable("gui.credit.tagbar.cfg.no_options")
                .withStyle(ChatFormatting.GRAY);
        } else {
            IngredientSpec.ItemOption cur = cfgContent.option();
            if (cur == IngredientSpec.ItemOption.NONE) {
                label = Component.translatable("gui.credit.tagbar.cfg.choose")
                    .withStyle(ChatFormatting.YELLOW);
            } else {
                label = Component.translatable(nameKey(cur));
            }
        }
        g.drawString(font, label, boxX + 3, boxY + 2, 0xFFFFFFFF, false);
        // ▾ indicator
        if (!opts.isEmpty()) {
            String arrow = dropdownOpen ? "▴" : "▾"; // ▴ ▾
            int aw = font.width(arrow);
            g.drawString(font, arrow, boxX + boxW - aw - 3, boxY + 2, 0xFFAAAAAA, false);
        }
    }

    private static void drawSlot(GuiGraphics g, int sx, int sy, boolean hover) {
        g.fill(sx - 1, sy - 1, sx + SLOT_W + 1, sy + SLOT_W + 1, 0xFF8B8B8B);
        g.fill(sx,     sy,     sx + SLOT_W,     sy + SLOT_W,     0xFF373737);
        if (hover) g.fill(sx, sy, sx + SLOT_W, sy + SLOT_W, 0x55FFFFFF);
    }

    public void renderTooltip(GuiGraphics g, Font font, int mouseX, int mouseY) {
        if (!visible) return;
        if (isOverNsSlot(mouseX, mouseY)) {
            if (cleanroomMode) {
                g.renderComponentTooltip(font, java.util.List.of(
                    Component.translatable("gui.credit.tagbar.cleanroom.mode_on")
                        .withStyle(ChatFormatting.AQUA),
                    Component.translatable("gui.credit.tagbar.cleanroom.shift_off")
                        .withStyle(ChatFormatting.DARK_GRAY)
                ), mouseX, mouseY);
            } else if (isGtCategory()) {
                g.renderComponentTooltip(font, java.util.List.of(
                    Component.literal("Default namespace: ")
                        .append(Component.literal(currentNamespace).withStyle(ChatFormatting.AQUA)),
                    Component.translatable("gui.credit.tagbar.cleanroom.shift_on")
                        .withStyle(ChatFormatting.DARK_GRAY)
                ), mouseX, mouseY);
            } else {
                g.renderTooltip(font, Component.literal("Default namespace: ")
                    .append(Component.literal(currentNamespace).withStyle(ChatFormatting.AQUA)),
                    mouseX, mouseY);
            }
        } else if (isOverCfgSlot(mouseX, mouseY)) {
            renderCfgSlotTooltip(g, font, mouseX, mouseY);
        } else if (isOverResultSlot(mouseX, mouseY)) {
            if (!finderSource.isEmpty()) {
                Component header = Component.translatable("gui.credit.tagbar.finder.header")
                    .withStyle(ChatFormatting.WHITE);
                Component count = Component.translatable("gui.credit.tagbar.finder.tag_count", finderTags.size())
                    .withStyle(ChatFormatting.GRAY);
                Component hint = Component.translatable("gui.credit.tagbar.finder.hint")
                    .withStyle(ChatFormatting.DARK_GRAY);
                g.renderComponentTooltip(font, java.util.List.of(header, count, hint), mouseX, mouseY);
            } else if (!resultStack.isEmpty()) {
                g.renderTooltip(font, resultStack, mouseX, mouseY);
            }
        }
    }

    private void renderCfgSlotTooltip(GuiGraphics g, Font font, int mouseX, int mouseY) {
        if (cfgContent.isEmpty()) {
            g.renderTooltip(font, Component.translatable("gui.credit.tagbar.cfg.empty_hint")
                .withStyle(ChatFormatting.GRAY), mouseX, mouseY);
            return;
        }
        List<Component> lines = new ArrayList<>();
        lines.add(describeContent(cfgContent));
        IngredientSpec.ItemOption opt = cfgContent.option();
        if (opt != IngredientSpec.ItemOption.NONE) {
            lines.add(Component.translatable("gui.credit.tagbar.cfg.applied",
                Component.translatable(nameKey(opt))).withStyle(ChatFormatting.AQUA));
            if ((opt == IngredientSpec.ItemOption.GT_CHANCE
                || opt == IngredientSpec.ItemOption.CREATE_CHANCE)
                && cfgContent instanceof IngredientSpec.Configured c) {
                if (opt == IngredientSpec.ItemOption.CREATE_CHANCE) {
                    // Create は boost 無し、確率のみ表示
                    lines.add(Component.translatable("gui.credit.tagbar.cfg.create_chance_value",
                        String.format("%.3f", c.chanceMille() / 1000.0)).withStyle(ChatFormatting.GRAY));
                } else {
                    lines.add(Component.translatable("gui.credit.tagbar.cfg.chance_values",
                        c.chanceMille(), c.tierBoost()).withStyle(ChatFormatting.GRAY));
                }
            }
        }
        g.renderComponentTooltip(font, lines, mouseX, mouseY);
    }

    private static Component describeContent(IngredientSpec spec) {
        IngredientSpec base = spec.unwrap();
        if (base instanceof IngredientSpec.Item it && !it.stack().isEmpty()) {
            return it.stack().getHoverName().copy().withStyle(ChatFormatting.WHITE);
        }
        if (base instanceof IngredientSpec.Fluid fl && !fl.stack().isEmpty()) {
            return fl.stack().getDisplayName().copy().withStyle(ChatFormatting.WHITE);
        }
        if (base instanceof IngredientSpec.Gas gas && gas.gasId() != null) {
            return Component.literal(gas.gasId().toString()).withStyle(ChatFormatting.WHITE);
        }
        return Component.literal("?");
    }

    /**
     * 利用可能 ItemOption。判定基準:
     *   1. カテゴリ namespace（vanilla/gtceu/その他）
     *   2. cfg 内 spec の性質（VANILLA_DAMAGE は耐久値のあるアイテムのみ等）
     * カテゴリ未設定 / cfg 空 時は空。
     */
    private List<IngredientSpec.ItemOption> optionsForCategory() {
        if (categoryNamespace == null || cfgContent.isEmpty()) return List.of();
        return switch (categoryNamespace) {
            case "minecraft" -> {
                java.util.List<IngredientSpec.ItemOption> opts = new java.util.ArrayList<>(2);
                if (isDamageableItem(cfgContent)) opts.add(IngredientSpec.ItemOption.VANILLA_DAMAGE);
                opts.add(IngredientSpec.ItemOption.VANILLA_KEEP);
                yield opts;
            }
            case "gtceu" -> List.of(
                IngredientSpec.ItemOption.GT_CHANCE,
                IngredientSpec.ItemOption.GT_CATALYST);
            case "create" -> List.of(
                IngredientSpec.ItemOption.CREATE_CHANCE);
            // mekanism 含む他 namespace は対応機能なし
            default -> List.of();
        };
    }

    /** 耐久値を持つ Item か（Tag/Fluid/Gas は対象外）。 */
    private static boolean isDamageableItem(IngredientSpec spec) {
        IngredientSpec base = spec.unwrap();
        if (base instanceof IngredientSpec.Item it && !it.stack().isEmpty()) {
            return it.stack().getItem().canBeDepleted();
        }
        return false;
    }

    private static String nameKey(IngredientSpec.ItemOption opt) {
        return switch (opt) {
            case NONE           -> "gui.credit.tagbar.cfg.opt.none";
            case VANILLA_DAMAGE -> "gui.credit.tagbar.cfg.opt.vanilla_damage";
            case VANILLA_KEEP   -> "gui.credit.tagbar.cfg.opt.vanilla_keep";
            case GT_CATALYST    -> "gui.credit.tagbar.cfg.opt.gt_catalyst";
            case GT_CHANCE      -> "gui.credit.tagbar.cfg.opt.gt_chance";
            case CREATE_CHANCE  -> "gui.credit.tagbar.cfg.opt.create_chance";
        };
    }

    /**
     * Dropdown / cfg slot 関連のクリック処理。BuilderScreen から先に呼ぶ。
     * 戻り値 true なら BuilderScreen は他処理スキップ。
     */
    public boolean handleDropdownClick(double mx, double my, int button) {
        if (!visible) return false;
        if (button != 0) return false;
        if (cfgContent.isEmpty()) return false;
        var opts = optionsForCategory();
        // dropdown が開いてる時：オプション行クリックで選択 + close
        if (dropdownOpen && !opts.isEmpty()) {
            int rowH = OPT_ROW_H;
            int listY = boxY + 12 + 1;
            int listH = opts.size() * rowH;
            if (mx >= boxX && mx < boxX + boxW && my >= listY && my < listY + listH) {
                int idx = (int) ((my - listY) / rowH);
                if (idx >= 0 && idx < opts.size()) {
                    cfgContent = cfgContent.withOption(opts.get(idx));
                    applyBoxMode();
                }
                dropdownOpen = false;
                return true;
            }
            // 外側クリック → close
            dropdownOpen = false;
        }
        // CHANCE モード時：右端 ▾ ボタンクリックで dropdown 再オープン
        if (boxMode == BoxMode.CHANCE && reopenBtnX >= 0
            && mx >= reopenBtnX && mx < reopenBtnX + REOPEN_BTN_W
            && my >= reopenBtnY && my < reopenBtnY + 11) {
            if (!opts.isEmpty()) {
                dropdownOpen = !dropdownOpen;
                return true;
            }
        }
        // HIDDEN モード時：header（box 領域）クリックで toggle
        if (boxMode == BoxMode.HIDDEN) {
            boolean overHeader = mx >= boxX && mx < boxX + boxW
                              && my >= boxY - 1 && my < boxY + 12;
            if (overHeader && !opts.isEmpty()) {
                dropdownOpen = !dropdownOpen;
                return true;
            }
        }
        return false;
    }

    /** dropdown 状態を強制 close（screen 切替時用）。 */
    public void closeDropdown() {
        dropdownOpen = false;
    }

    public boolean isDropdownOpen() {
        return dropdownOpen;
    }

    /**
     * Result slot finder のクリック処理。CFG dropdown より先に処理する。
     * 戻り値 true → 他のクリックハンドラには流さない。
     */
    public boolean handleFinderClick(double mx, double my, int button) {
        if (!visible || button != 0) return false;
        // dropdown が開いてる時：タグ行クリックで pick + close
        if (finderDropdownOpen && !finderTags.isEmpty() && finderListX >= 0) {
            if (mx >= finderListX && mx < finderListX + finderListW
                && my >= finderListY && my < finderListY + finderListH) {
                int idx = (int) ((my - finderListY) / FINDER_ROW_H);
                if (idx >= 0 && idx < finderTags.size()) {
                    pickFinderTag(finderTags.get(idx));
                }
                return true;
            }
            // 外側クリック → close（finderSource は維持）
            finderDropdownOpen = false;
        }
        // finder アクティブ時、result slot クリックで dropdown 再オープン
        if (!finderSource.isEmpty() && isOverResultSlot(mx, my)) {
            finderDropdownOpen = !finderDropdownOpen;
            return true;
        }
        return false;
    }

    /** タグ選択時：EditBox に tag id を流し onChange 経由で通常 name_tag 生成へ。 */
    private void pickFinderTag(ResourceLocation tag) {
        finderSource = IngredientSpec.EMPTY;
        finderTags.clear();
        finderDropdownOpen = false;
        if (box != null) box.setValue(tag.toString());
        // box.setResponder が onBoxChanged → onChange を呼び resultStack 更新
    }

    /** EditBox 入力イベントの dispatcher。mode に応じて分岐。 */
    private void onBoxChanged(String s) {
        if (boxMode == BoxMode.CHANCE) {
            onChanceTyped(s);
        } else {
            onChange(s);
        }
    }

    /** "chance,boost" 形式を解析して Configured に書き戻す。Create は boost 無視。 */
    private void onChanceTyped(String s) {
        if (!(cfgContent instanceof IngredientSpec.Configured c)) return;
        if (c.opt() != IngredientSpec.ItemOption.GT_CHANCE
            && c.opt() != IngredientSpec.ItemOption.CREATE_CHANCE) return;
        String[] parts = (s == null ? "" : s).split(",", -1);
        int chance = parts.length > 0 ? parseIntSafe(parts[0], c.chanceMille()) : c.chanceMille();
        int boost  = parts.length > 1 ? parseIntSafe(parts[1], c.tierBoost())  : c.tierBoost();
        IngredientSpec.Configured next = c.withChance(chance, boost);
        if (next.chanceMille() != c.chanceMille() || next.tierBoost() != c.tierBoost()) {
            this.cfgContent = next;
        }
    }

    private static int parseIntSafe(String s, int fallback) {
        if (s == null || s.isBlank()) return fallback;
        try { return Integer.parseInt(s.trim()); } catch (NumberFormatException e) { return fallback; }
    }

    private void onChange(String s) {
        String input = s == null ? "" : s.trim();
        if (input.isEmpty()) {
            resultStack = ItemStack.EMPTY;
            return;
        }

        // 例外処理: GT programmed circuit。例: "gtceu:circuit(7)" or "circuit(7)"
        ItemStack circuit = tryProgrammedCircuit(input);
        if (!circuit.isEmpty()) {
            resultStack = circuit;
            return;
        }

        if (input.startsWith("#")) input = input.substring(1);

        ResourceLocation rl;
        if (input.contains(":")) {
            rl = ResourceLocation.tryParse(input);
        } else {
            rl = ResourceLocation.tryBuild(currentNamespace, input);
        }

        // 自動判定：item tag を優先、なければ fluid tag
        if (rl != null && TagItemHelper.isKnownItemTag(rl)) {
            resultStack = TagItemHelper.createTagNameTag(rl);
        } else if (rl != null && TagItemHelper.isKnownFluidTag(rl)) {
            resultStack = TagItemHelper.createFluidTagNameTag(rl, 1000);
        } else {
            resultStack = ItemStack.EMPTY;
        }
    }

    private static final Pattern CIRCUIT_PATTERN =
        Pattern.compile("^(?:gtceu:)?circuit\\((\\d{1,2})\\)$");
    private static final int CIRCUIT_MAX = 32;
    private static final ResourceLocation CIRCUIT_ITEM_ID =
        new ResourceLocation("gtceu", "programmed_circuit");

    /** "gtceu:circuit(N)" or "circuit(N)" → 該当 configuration の programmed circuit ItemStack。 */
    private static ItemStack tryProgrammedCircuit(String input) {
        if (!ModList.get().isLoaded("gtceu")) return ItemStack.EMPTY;
        Matcher m = CIRCUIT_PATTERN.matcher(input);
        if (!m.matches()) return ItemStack.EMPTY;
        int config;
        try { config = Integer.parseInt(m.group(1)); } catch (NumberFormatException e) { return ItemStack.EMPTY; }
        if (config < 0 || config > CIRCUIT_MAX) return ItemStack.EMPTY;
        Item item = BuiltInRegistries.ITEM.get(CIRCUIT_ITEM_ID);
        if (item == Items.AIR) return ItemStack.EMPTY;
        ItemStack stack = new ItemStack(item);
        stack.getOrCreateTag().putInt("Configuration", config);
        return stack;
    }
}
