package DIV.credit.client.recipe;

import DIV.credit.Credit;
import DIV.credit.client.draft.IngredientSpec;
import DIV.credit.client.draft.RecipeDraft;
import DIV.credit.client.fluid.FluidItemHelper;
import DIV.credit.client.tag.TagItemHelper;
import DIV.credit.jei.CraftPatternJeiPlugin;
import mezz.jei.api.forge.ForgeTypes;
import mezz.jei.api.gui.IRecipeLayoutDrawable;
import mezz.jei.api.gui.ingredient.IRecipeSlotDrawable;
import mezz.jei.api.gui.ingredient.IRecipeSlotView;
import mezz.jei.api.gui.inputs.RecipeSlotUnderMouse;
import mezz.jei.api.ingredients.ITypedIngredient;
import mezz.jei.api.recipe.IFocusGroup;
import mezz.jei.api.recipe.IRecipeManager;
import mezz.jei.api.recipe.RecipeIngredientRole;
import mezz.jei.api.recipe.RecipeType;
import mezz.jei.api.recipe.category.IRecipeCategory;
import mezz.jei.api.runtime.IJeiRuntime;
import java.lang.reflect.Field;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.renderer.Rect2i;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.fluids.FluidStack;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 中央レシピ表示 + 編集領域。
 * draft != null: draft の状態を Recipe 化して JEI に描画させる。クリックで編集。
 * draft == null: 既存レシピ 1 件を読み取り専用表示。
 */
public class RecipeArea {

    private IRecipeLayoutDrawable<?> drawable;
    private IRecipeCategory<?> category;
    private RecipeDraft draft;
    private int areaLeft, areaTop, areaWidth, areaHeight;
    private String statusMessage;
    /** Sampling-based slot screen-bounds cache (recomputed each rebuild). */
    private Map<IRecipeSlotView, int[]> sampledBoundsCache = new HashMap<>();
    /** DRAFT (user edits drive recipe), FALLBACK (draft incomplete → showing existing), READONLY, EMPTY, NONE */
    private String currentMode = "NONE";

    public void setBounds(int left, int top, int width, int height) {
        this.areaLeft   = left;
        this.areaTop    = top;
        this.areaWidth  = width;
        this.areaHeight = height;
        repositionDrawable();
    }

    public void setCategory(IRecipeCategory<?> cat, @Nullable RecipeDraft draft) {
        this.category = cat;
        this.draft    = draft;
        rebuildDrawable();
    }

    public RecipeDraft getDraft() {
        return draft;
    }

    /** "DRAFT" / "FALLBACK" / "READONLY" / "EMPTY" / "NONE" */
    public String getMode() {
        return currentMode;
    }

    public void rebuild() {
        rebuildDrawable();
    }

    private void rebuildDrawable() {
        this.drawable = null;
        this.statusMessage = null;
        this.currentMode = "NONE";
        IJeiRuntime rt = CraftPatternJeiPlugin.runtime;
        if (rt == null || category == null) {
            statusMessage = "(JEI not ready)";
            return;
        }
        IRecipeManager rm = rt.getRecipeManager();
        IFocusGroup empty = rt.getJeiHelpers().getFocusFactory().getEmptyFocusGroup();
        try {
            if (draft != null) {
                this.drawable = createDrawableFromDraft(rm, category, draft, empty);
                if (drawable != null) {
                    currentMode = "DRAFT";
                } else {
                    // Draft が不完全 (toRecipeInstance が null) のときは既存レシピを表示してスロット位置だけ見せる
                    drawable = createDrawableFromExisting(rm, category, empty);
                    currentMode = (drawable != null) ? "FALLBACK" : "EMPTY";
                }
            } else {
                this.drawable = createDrawableFromExisting(rm, category, empty);
                currentMode = (drawable != null) ? "READONLY" : "EMPTY";
            }
        } catch (Exception e) {
            Credit.LOGGER.error("[CraftPattern] Failed to create drawable for {}",
                category.getRecipeType().getUid(), e);
            statusMessage = "Error: " + e.getClass().getSimpleName();
            return;
        }
        if (drawable == null) {
            statusMessage = "(no recipes for " + category.getRecipeType().getUid() + ")";
            sampledBoundsCache = new HashMap<>();
            originalOverlays.clear();
        } else {
            repositionDrawable();
            try { drawable.tick(); } catch (Exception ignored) {}
            sampledBoundsCache = sampleSlotBounds();
            captureOriginalOverlays(); // updateSlotDisplays が overlay を null/復元するため事前にキャプチャ
            updateSlotDisplays();  // slot ごと: 編集 spec を JEI に native 描画させる / 空はクリア
            logSlotKindMapping();
        }
    }

    /** drawable 全体をサンプリングして slot → screen-bounds を返す。 */
    private Map<IRecipeSlotView, int[]> sampleSlotBounds() {
        Map<IRecipeSlotView, int[]> bounds = new HashMap<>();
        if (drawable == null) return bounds;
        Rect2i lr = drawable.getRect();
        final int step = 2;
        int x0 = lr.getX(), y0 = lr.getY();
        int x1 = x0 + lr.getWidth(), y1 = y0 + lr.getHeight();
        for (int sy = y0; sy < y1; sy += step) {
            for (int sx = x0; sx < x1; sx += step) {
                Optional<RecipeSlotUnderMouse> hit = drawable.getSlotUnderMouse(sx, sy);
                if (hit.isEmpty()) continue;
                IRecipeSlotView slot = hit.get().slot();
                int[] b = bounds.computeIfAbsent(slot, s ->
                    new int[]{Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MIN_VALUE, Integer.MIN_VALUE});
                if (sx < b[0]) b[0] = sx;
                if (sy < b[1]) b[1] = sy;
                if (sx > b[2]) b[2] = sx;
                if (sy > b[3]) b[3] = sy;
            }
        }
        return bounds;
    }

    /** デバッグ用：layout 矩形と各スロットの rel rect / sampled bounds を出力。 */
    private void logSlotKindMapping() {
        if (draft == null) return;
        var views = drawable.getRecipeSlotsView().getSlotViews();
        Rect2i lr  = drawable.getRect();
        Rect2i lrb = drawable.getRectWithBorder();
        Credit.LOGGER.info("[CraftPattern] drawable rebuilt for {} mode={} | layoutRect=({},{},{}x{}) | layoutRectWithBorder=({},{},{}x{}) | views={} draftSlots={}",
            category.getRecipeType().getUid(), currentMode,
            lr.getX(),  lr.getY(),  lr.getWidth(),  lr.getHeight(),
            lrb.getX(), lrb.getY(), lrb.getWidth(), lrb.getHeight(),
            views.size(), draft.slotCount());
        for (int i = 0; i < views.size(); i++) {
            var v = views.get(i);
            String draftKind = i < draft.slotCount() ? draft.slotKind(i).name() : "(out-of-range)";
            String relStr = "";
            if (v instanceof IRecipeSlotDrawable sd) {
                Rect2i r = sd.getRect();
                relStr = " rel=(" + r.getX() + "," + r.getY() + "," + r.getWidth() + "x" + r.getHeight()
                    + ") expectedScreen=(" + (lr.getX() + r.getX()) + "," + (lr.getY() + r.getY()) + ")";
            }
            int[] b = sampledBoundsCache.get(v);
            String sampleStr = b != null
                ? " sampled=(" + b[0] + "," + b[1] + "→" + b[2] + "," + b[3] + ")"
                : " sampled=NONE";
            Credit.LOGGER.info("  slot[{}] role={} kind={}{}{}", i, v.getRole(), draftKind, relStr, sampleStr);
        }
    }

    @SuppressWarnings("unchecked")
    private static <T> IRecipeLayoutDrawable<?> createDrawableFromDraft(IRecipeManager rm,
                                                                         IRecipeCategory<T> cat,
                                                                         RecipeDraft draft,
                                                                         IFocusGroup empty) {
        Object raw = draft.toRecipeInstance();
        if (raw == null) return null;  // draft incomplete; caller falls back
        T recipe = (T) raw;
        return rm.createRecipeLayoutDrawable(cat, recipe, empty).orElse(null);
    }

    private static <T> IRecipeLayoutDrawable<?> createDrawableFromExisting(IRecipeManager rm,
                                                                            IRecipeCategory<T> cat,
                                                                            IFocusGroup empty) {
        RecipeType<T> type = cat.getRecipeType();
        Optional<T> recipe = rm.createRecipeLookup(type).includeHidden().get().findFirst();
        if (recipe.isEmpty()) return null;
        return rm.createRecipeLayoutDrawable(cat, recipe.get(), empty).orElse(null);
    }

    private void repositionDrawable() {
        if (drawable == null) return;
        Rect2i rect = drawable.getRect();
        int dw = rect.getWidth();
        int dh = rect.getHeight();
        int x = areaLeft + (areaWidth  - dw) / 2;
        int y = areaTop  + (areaHeight - dh) / 2;
        drawable.setPosition(x, y);
    }

    public void render(GuiGraphics g, int mouseX, int mouseY) {
        if (drawable != null) {
            drawable.drawRecipe(g, mouseX, mouseY);
            renderUserEditOverlay(g);
        } else if (statusMessage != null) {
            var font = Minecraft.getInstance().font;
            int tx = areaLeft + (areaWidth - font.width(statusMessage)) / 2;
            int ty = areaTop  + (areaHeight - font.lineHeight) / 2;
            g.drawString(font, Component.literal(statusMessage), tx, ty, 0xFFAAAAAA, false);
        }
    }

    /**
     * FALLBACK / DRAFT 中、ユーザーが編集したスロットには既存表示を上書きして
     * ユーザーの ingredient を JEI renderer で描く。
     */
    /**
     * JEI RecipeSlot の displayIngredients を reflection で空にする。
     * これで slot は カテゴリ固有の background + overlay だけ描画して中身を skip。
     * カテゴリの slot 枠（GT/Mek の固有テクスチャ等）は尊重される。
     */
    private static Field DISPLAY_INGREDIENTS_FIELD;
    private static Field OVERLAY_FIELD;
    private static final String LDLIB_WRAPPER_CLASS = "com.lowdragmc.lowdraglib.jei.IRecipeIngredientSlotWrapper";
    static {
        try {
            Class<?> cls = Class.forName("mezz.jei.library.gui.ingredients.RecipeSlot");
            DISPLAY_INGREDIENTS_FIELD = cls.getDeclaredField("displayIngredients");
            DISPLAY_INGREDIENTS_FIELD.setAccessible(true);
            OVERLAY_FIELD = cls.getDeclaredField("overlay");
            OVERLAY_FIELD.setAccessible(true);
        } catch (Exception e) {
            Credit.LOGGER.warn("[CraftPattern] Cannot access RecipeSlot fields via reflection: {}", e.toString());
        }
    }

    /** RecipeSlot.overlay が LDLib の IRecipeIngredientSlotWrapper か判定。LDLib 未ロード環境でも CCE 回避。 */
    private static boolean isLDLibWrapped(IRecipeSlotView view) {
        if (OVERLAY_FIELD == null) return false;
        try {
            Object overlay = OVERLAY_FIELD.get(view);
            return overlay != null && overlay.getClass().getName().equals(LDLIB_WRAPPER_CLASS);
        } catch (Exception e) {
            return false;
        }
    }

    /** drawable 再構築毎に slot view → original overlay を保存。Tag spec 時のみ null 化、それ以外は復元。 */
    private final Map<IRecipeSlotView, Object> originalOverlays = new HashMap<>();

    private void captureOriginalOverlays() {
        originalOverlays.clear();
        if (drawable == null || OVERLAY_FIELD == null) return;
        for (IRecipeSlotView v : drawable.getRecipeSlotsView().getSlotViews()) {
            try {
                Object orig = OVERLAY_FIELD.get(v);
                originalOverlays.put(v, orig);
            } catch (Exception ignored) {}
        }
    }

    /**
     * 各 slot の displayIngredients を更新：
     * - 編集なし → 空リスト（JEI は背景のみ描画 → カテゴリ固有の空スロット枠が見える）
     * - 編集あり → ユーザー spec を ITypedIngredient で設定 → JEI が slot サイズで native 描画
     *   （Mek の tank slot 16x58 等で fluid/gas が正しいサイズで描画される）
     */
    private void updateSlotDisplays() {
        if (drawable == null || DISPLAY_INGREDIENTS_FIELD == null || draft == null) return;
        IJeiRuntime rt = CraftPatternJeiPlugin.runtime;
        if (rt == null) return;
        var im = rt.getIngredientManager();
        var views = drawable.getRecipeSlotsView().getSlotViews();
        int limit = Math.min(views.size(), draft.slotCount());
        for (int i = 0; i < views.size(); i++) {
            IngredientSpec spec = (i < limit) ? draft.getSlot(i) : IngredientSpec.EMPTY;
            List<Optional<ITypedIngredient<?>>> displayList = List.of();
            if (!spec.isEmpty()) {
                ITypedIngredient<?> ti = specToTypedIngredient(im, spec);
                if (ti != null) displayList = List.of(Optional.of(ti));
            }
            IRecipeSlotView view = views.get(i);
            try {
                DISPLAY_INGREDIENTS_FIELD.set(view, displayList);
            } catch (Exception ignored) {}
            // Tag spec の slot は GT/LdLib の cycler overlay を抑制（毎秒チカチカ防止）。
            // 非 Tag spec / 空は original overlay を復元（GT/Mek 固有の slot 装飾を尊重）。
            if (OVERLAY_FIELD != null) {
                try {
                    IngredientSpec base = spec.unwrap();
                    boolean isTag = base instanceof IngredientSpec.Tag
                                 || base instanceof IngredientSpec.FluidTag;
                    if (isTag) {
                        OVERLAY_FIELD.set(view, null);
                    } else {
                        OVERLAY_FIELD.set(view, originalOverlays.get(view));
                    }
                } catch (Exception ignored) {}
            }
        }
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static ITypedIngredient<?> specToTypedIngredient(mezz.jei.api.runtime.IIngredientManager im, IngredientSpec spec) {
        try {
            // Configured wrapper は base に unwrap してから JEI ingredient へ
            if (spec != null) spec = spec.unwrap();
            if (spec instanceof IngredientSpec.Item it && !it.stack().isEmpty()) {
                return im.createTypedIngredient(mezz.jei.api.constants.VanillaTypes.ITEM_STACK, it.stack()).orElse(null);
            }
            if (spec instanceof IngredientSpec.Fluid fl && !fl.stack().isEmpty()) {
                return im.createTypedIngredient(ForgeTypes.FLUID_STACK, fl.stack()).orElse(null);
            }
            if (spec instanceof IngredientSpec.Gas g && g.gasId() != null) {
                if (!net.minecraftforge.fml.ModList.get().isLoaded("mekanism")) return null;
                var gasStack = DIV.credit.client.jei.mek.MekanismIngredientAdapter.toGasStack(g);
                if (gasStack.isEmpty()) return null;
                return im.createTypedIngredient(mekanism.client.jei.MekanismJEI.TYPE_GAS, gasStack).orElse(null);
            }
            // Tag / FluidTag: convert to first matching item/fluid for display
            if (spec instanceof IngredientSpec.Tag tg && tg.tagId() != null) {
                ItemStack icon = TagItemHelper.createTagNameTag(tg.tagId());
                if (!icon.isEmpty()) return im.createTypedIngredient(mezz.jei.api.constants.VanillaTypes.ITEM_STACK, icon).orElse(null);
            }
            if (spec instanceof IngredientSpec.FluidTag ft && ft.tagId() != null) {
                ItemStack icon = TagItemHelper.createFluidTagNameTag(ft.tagId(), ft.amount());
                if (!icon.isEmpty()) return im.createTypedIngredient(mezz.jei.api.constants.VanillaTypes.ITEM_STACK, icon).orElse(null);
            }
        } catch (Exception ignored) {}
        return null;
    }

    /**
     * Create heat icon の screen rect を返す。
     * Create MixingCategory.draw() の line 47 で blaze burner が
     * `(width/2 + 3, 55)` 相対位置に描画される。20x20 サイズで近似。
     * drawable 未準備時は null。
     */
    @Nullable
    public Rect2i getHeatIconRect() {
        if (drawable == null) return null;
        Rect2i layout = drawable.getRect();
        int x = layout.getX() + layout.getWidth() / 2 + 3;
        int y = layout.getY() + 55;
        return new Rect2i(x, y, 20, 20);
    }

    /**
     * カテゴリ描画（drawRecipe 経由）後、編集済みスロットだけユーザー ingredient を上書き描画。
     * slot 中身は clearAllSlotIngredients で既に空になってるので、
     * 編集なしスロットはカテゴリ固有の空スロット枠が見える。
     */
    private void renderUserEditOverlay(GuiGraphics g) {
        if (draft == null) return;
        IJeiRuntime rt = CraftPatternJeiPlugin.runtime;
        if (rt == null) return;
        Rect2i layoutRect = drawable.getRect();
        var views = drawable.getRecipeSlotsView().getSlotViews();
        int limit = Math.min(views.size(), draft.slotCount());
        for (int i = 0; i < limit; i++) {
            IngredientSpec spec = draft.getSlot(i);
            if (spec.isEmpty()) continue;
            int sx, sy;
            var view = views.get(i);
            if (view instanceof IRecipeSlotDrawable sd) {
                Rect2i r = sd.getRect();
                sx = layoutRect.getX() + r.getX();
                sy = layoutRect.getY() + r.getY();
            } else {
                int[] b = sampledBoundsCache.get(view);
                if (b == null) continue;
                sx = b[0]; sy = b[1];
            }
            // Tag/FluidTag: LdLib widget tree が独立に tag list を cycler 描画してるため、
            // 我々の name_tag アイコン背後にも cycling item が透けてしまう。
            // 16x16 の不透明 fill で下層を覆って隠す。それ以外の spec は単一アイテムなので
            // 我々の icon が自然に上書きするので fill 不要。
            IngredientSpec base = spec.unwrap();
            if (base instanceof IngredientSpec.Tag || base instanceof IngredientSpec.FluidTag) {
                g.fill(sx, sy, sx + 16, sy + 16, 0xFF373737);
            }
            renderSpec(g, rt, spec, sx, sy);
        }
    }

    /** mouse 位置の slot index を返す。drag drop 着地判定用。-1 = no slot。 */
    public int findSlotIndexAt(double mx, double my) {
        if (drawable == null || draft == null) return -1;
        Optional<RecipeSlotUnderMouse> slotOpt = drawable.getSlotUnderMouse(mx, my);
        if (slotOpt.isEmpty()) return -1;
        int idx = findSlotIndex(slotOpt.get().slot());
        return (idx >= 0 && idx < draft.slotCount()) ? idx : -1;
    }

    /** mouse 位置の slot に編集済み spec があれば返す。drag 開始判定用。 */
    @Nullable
    public IngredientSpec getEditedSpecAt(double mx, double my) {
        if (drawable == null || draft == null) return null;
        Optional<RecipeSlotUnderMouse> slotOpt = drawable.getSlotUnderMouse(mx, my);
        if (slotOpt.isEmpty()) return null;
        int idx = findSlotIndex(slotOpt.get().slot());
        if (idx < 0 || idx >= draft.slotCount()) return null;
        IngredientSpec spec = draft.getSlot(idx);
        return spec.isEmpty() ? null : spec;
    }

    public static void renderSpecAt(GuiGraphics g, IngredientSpec spec, int x, int y) {
        IJeiRuntime rt = CraftPatternJeiPlugin.runtime;
        if (rt == null) return;
        renderSpec(g, rt, spec, x, y);
    }

    private static void renderSpec(GuiGraphics g, IJeiRuntime rt, IngredientSpec spec, int x, int y) {
        try {
            // Configured wrapper は base に unwrap してから render
            if (spec != null) spec = spec.unwrap();
            if (spec instanceof IngredientSpec.Item it && !it.stack().isEmpty()) {
                g.renderItem(it.stack(), x, y);
                g.renderItemDecorations(Minecraft.getInstance().font, it.stack(), x, y);
                return;
            }
            if (spec instanceof IngredientSpec.Tag tg && tg.tagId() != null) {
                ItemStack icon = TagItemHelper.createTagNameTag(tg.tagId());
                if (!icon.isEmpty()) g.renderItem(icon, x, y);
                return;
            }
            if (spec instanceof IngredientSpec.FluidTag ft && ft.tagId() != null) {
                ItemStack icon = TagItemHelper.createFluidTagNameTag(ft.tagId(), ft.amount());
                if (!icon.isEmpty()) g.renderItem(icon, x, y);
                return;
            }
            if (spec instanceof IngredientSpec.Fluid fl && !fl.stack().isEmpty()) {
                rt.getIngredientManager().getIngredientRenderer(ForgeTypes.FLUID_STACK)
                    .render(g, fl.stack(), x, y);
                return;
            }
            if (spec instanceof IngredientSpec.Gas gas && gas.gasId() != null) {
                renderGas(g, rt, gas, x, y);
                return;
            }
        } catch (Exception ignored) {}
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static void renderGas(GuiGraphics g, IJeiRuntime rt, IngredientSpec.Gas gas, int x, int y) {
        if (!net.minecraftforge.fml.ModList.get().isLoaded("mekanism")) return;
        DIV.credit.client.jei.mek.MekanismIngredientAdapter adapter = null;  // touch-load
        var gasStack = DIV.credit.client.jei.mek.MekanismIngredientAdapter.toGasStack(gas);
        if (gasStack.isEmpty()) return;
        var type = mekanism.client.jei.MekanismJEI.TYPE_GAS;
        var renderer = (mezz.jei.api.ingredients.IIngredientRenderer) rt.getIngredientManager()
            .getIngredientRenderer(type);
        renderer.render(g, gasStack, x, y);
    }

    public void renderOverlays(GuiGraphics g, int mouseX, int mouseY) {
        if (drawable != null) drawable.drawOverlays(g, mouseX, mouseY);
    }

    /**
     * ユーザー編集スロット上にホバー時、中身の名前 + 量を tooltip 表示。
     * LDLib-wrap slot のみ対象 (非ラップは JEI 自身が displayIngredients 経由で
     * tooltip 出すので、ここで出すと名前が二重になる)。
     */
    public void renderUserEditTooltip(GuiGraphics g, int mouseX, int mouseY) {
        if (drawable == null || draft == null) return;
        Optional<RecipeSlotUnderMouse> slotOpt = drawable.getSlotUnderMouse(mouseX, mouseY);
        if (slotOpt.isEmpty()) return;
        IRecipeSlotDrawable hoverSlot = slotOpt.get().slot();
        int idx = findSlotIndex(hoverSlot);
        if (idx < 0 || idx >= draft.slotCount()) return;
        IngredientSpec spec = draft.getSlot(idx);
        if (spec.isEmpty()) return;
        if (!isLDLibWrapped(hoverSlot)) return;
        g.renderTooltip(Minecraft.getInstance().font,
            Component.literal(describeForTooltip(spec)), mouseX, mouseY);
    }

    private static String describeForTooltip(IngredientSpec s) {
        if (s instanceof IngredientSpec.Item it && !it.stack().isEmpty()) {
            return it.stack().getDisplayName().getString() + " ×" + it.stack().getCount();
        }
        if (s instanceof IngredientSpec.Tag tg && tg.tagId() != null) {
            return "#" + tg.tagId() + " ×" + tg.count();
        }
        if (s instanceof IngredientSpec.Fluid fl && !fl.stack().isEmpty()) {
            return fl.stack().getDisplayName().getString() + " " + fl.stack().getAmount() + "mB";
        }
        if (s instanceof IngredientSpec.FluidTag ft && ft.tagId() != null) {
            return "#" + ft.tagId() + " " + ft.amount() + "mB";
        }
        if (s instanceof IngredientSpec.Gas g && g.gasId() != null) {
            return g.gasId() + " " + g.amount() + "mB";
        }
        return s.toString();
    }

    public void tick() {
        if (drawable != null) drawable.tick();
    }

    /** カーソル ItemStack から spec へ変換：item tag → Tag、fluid tag → FluidTag、fluid → Fluid、その他 → Item。 */
    public static IngredientSpec ingredientFromCursor(ItemStack cursor) {
        if (cursor == null || cursor.isEmpty()) return IngredientSpec.EMPTY;
        ResourceLocation tagId = TagItemHelper.extractTagId(cursor);
        if (tagId != null) return IngredientSpec.ofTag(tagId);
        ResourceLocation fluidTagId = TagItemHelper.extractFluidTagId(cursor);
        if (fluidTagId != null) {
            int amount = TagItemHelper.extractFluidTagAmount(cursor, 1000);
            return IngredientSpec.ofFluidTag(fluidTagId, amount);
        }
        FluidStack fs = FluidItemHelper.extractFluid(cursor);
        if (fs != null && !fs.isEmpty()) return IngredientSpec.ofFluid(fs);
        ItemStack copy = cursor.copy();
        copy.setCount(1);
        return IngredientSpec.ofItem(copy);
    }

    /**
     * クリック挙動：
     * - 左クリ + cursor 非空 → 配置（出力スロットに Tag は不可で silently 拒否）
     * - 右クリ（修飾なし） → no-op
     * - Ctrl+右 → このスロットだけクリア
     * - Shift+右 → 全スロットクリア
     */
    /** RecipeArea の矩形に mx,my が含まれるか。Shift+click 等の hit-test 用。 */
    public boolean isInside(double mx, double my) {
        return mx >= areaLeft && mx < areaLeft + areaWidth
            && my >= areaTop  && my < areaTop  + areaHeight;
    }

    public boolean mouseClicked(double mx, double my, int button, ItemStack cursor) {
        if (drawable == null) return false;
        Optional<RecipeSlotUnderMouse> slotOpt = drawable.getSlotUnderMouse(mx, my);
        if (slotOpt.isEmpty()) {
            Credit.LOGGER.info("[CraftPattern] click@({},{}) → no slot", (int) mx, (int) my);
            return false;
        }

        if (draft == null) return true; // read-only category

        IRecipeSlotDrawable clickedSlot = slotOpt.get().slot();
        int slotIndex = findSlotIndex(clickedSlot);
        // Diagnostics: include the slot rect (relative-to-parent) JEI thinks the clicked slot has
        Rect2i layoutRect = drawable.getRect();
        Rect2i r = clickedSlot.getRect();
        String slotRectStr = "rel=(" + r.getX() + "," + r.getY() + "," + r.getWidth() + "x" + r.getHeight()
            + ") expectedScreen=(" + (layoutRect.getX() + r.getX()) + "," + (layoutRect.getY() + r.getY()) + ")";
        Credit.LOGGER.info("[CraftPattern] click@({},{}) → slot[{}] button={} cursor={} draftKind={} {}",
            (int) mx, (int) my, slotIndex, button,
            (cursor == null || cursor.isEmpty()) ? "empty" : cursor.getItem().builtInRegistryHolder().key().location(),
            slotIndex >= 0 && slotIndex < draft.slotCount() ? draft.slotKind(slotIndex).name() : "(out)",
            slotRectStr);
        if (slotIndex < 0 || slotIndex >= draft.slotCount()) return true;

        boolean cursorEmpty = (cursor == null || cursor.isEmpty());

        if (button == 0) {
            if (!cursorEmpty) {
                IngredientSpec spec = ingredientFromCursor(cursor);
                if (!draft.acceptsAt(slotIndex, spec)) {
                    Credit.LOGGER.info("[CraftPattern] slot[{}] REJECTED: spec={} kind={}",
                        slotIndex, spec.getClass().getSimpleName(), draft.slotKind(slotIndex).name());
                    return true;
                }
                IngredientSpec prev = draft.getSlot(slotIndex);
                if (sameSpec(prev, spec)) return true;  // skip noop rebuild
                draft.setSlot(slotIndex, spec);
                rebuildDrawable();
            }
        } else if (button == 1) {
            if (Screen.hasShiftDown()) {
                boolean anyChange = false;
                for (int i = 0; i < draft.slotCount(); i++) {
                    if (!draft.getSlot(i).isEmpty()) {
                        draft.setSlot(i, IngredientSpec.EMPTY);
                        anyChange = true;
                    }
                }
                if (anyChange) rebuildDrawable();
            } else if (Screen.hasControlDown()) {
                if (!draft.getSlot(slotIndex).isEmpty()) {
                    draft.setSlot(slotIndex, IngredientSpec.EMPTY);
                    rebuildDrawable();
                }
            } else if (cursorEmpty) {
                // bare right-click WITH EMPTY CURSOR: increment count by spec step
                // (cursor 持ったままの右クリは無視 — vanilla では別アクションだから)
                IngredientSpec current = draft.getSlot(slotIndex);
                if (!current.isEmpty()) {
                    int step = current.incrementStep();
                    int next = current.count() + step;
                    if (next > current.maxCount()) next = step;
                    draft.setSlot(slotIndex, IngredientSpec.withCount(current, next));
                    rebuildDrawable();
                }
            }
        }
        return true;
    }

    /** spec の等価判定（rebuild スキップ用）。 */
    private static boolean sameSpec(IngredientSpec a, IngredientSpec b) {
        if (a == b) return true;
        if (a == null || b == null) return false;
        if (a.getClass() != b.getClass()) return false;
        if (a instanceof IngredientSpec.Item ia && b instanceof IngredientSpec.Item ib)
            return ItemStack.matches(ia.stack(), ib.stack());
        if (a instanceof IngredientSpec.Tag ta && b instanceof IngredientSpec.Tag tb)
            return java.util.Objects.equals(ta.tagId(), tb.tagId()) && ta.count() == tb.count();
        if (a instanceof IngredientSpec.Fluid fa && b instanceof IngredientSpec.Fluid fb)
            return fa.stack().isFluidStackIdentical(fb.stack());
        if (a instanceof IngredientSpec.FluidTag fta && b instanceof IngredientSpec.FluidTag ftb)
            return java.util.Objects.equals(fta.tagId(), ftb.tagId()) && fta.amount() == ftb.amount();
        if (a instanceof IngredientSpec.Gas ga && b instanceof IngredientSpec.Gas gb)
            return java.util.Objects.equals(ga.gasId(), gb.gasId()) && ga.amount() == gb.amount();
        return false;
    }

    /** スロット上のスクロールで count ±1 (Shift で ±8)。 */
    public boolean mouseScrolled(double mx, double my, double delta) {
        if (drawable == null || draft == null) return false;
        Optional<RecipeSlotUnderMouse> slotOpt = drawable.getSlotUnderMouse(mx, my);
        if (slotOpt.isEmpty()) return false;
        int slotIndex = findSlotIndex(slotOpt.get().slot());
        if (slotIndex < 0 || slotIndex >= draft.slotCount()) return false;

        IngredientSpec current = draft.getSlot(slotIndex);
        if (current.isEmpty()) return false;

        int baseStep = current.incrementStep();
        int multiplier = Screen.hasShiftDown() ? 8 : 1;
        int adjust = (delta > 0 ? baseStep : -baseStep) * multiplier;
        int newCount = Math.max(1, Math.min(current.maxCount(), current.count() + adjust));
        if (newCount != current.count()) {
            draft.setSlot(slotIndex, IngredientSpec.withCount(current, newCount));
            rebuildDrawable();
        }
        return true;
    }

    /**
     * JEI Ghost Drag 用：cache 済みのサンプリング bounds から (index, screen-rect) を返す。
     * 重い sampling は rebuildDrawable で 1 回行い、ここでは cache を読むだけ。
     */
    public List<GhostTargetInfo> collectGhostTargets() {
        List<GhostTargetInfo> result = new ArrayList<>();
        if (drawable == null || draft == null) return result;
        List<IRecipeSlotView> views = drawable.getRecipeSlotsView().getSlotViews();
        Rect2i layoutRect = drawable.getRect();

        final int step = 2;
        int sampled  = 0;
        int fallback = 0;
        int skipped  = 0;
        int limit = Math.min(views.size(), draft.slotCount());
        for (int i = 0; i < limit; i++) {
            IRecipeSlotView view = views.get(i);
            int[] b = sampledBoundsCache.get(view);
            Rect2i screen;
            if (b != null) {
                screen = new Rect2i(b[0], b[1], b[2] - b[0] + step, b[3] - b[1] + step);
                sampled++;
            } else if (view instanceof IRecipeSlotDrawable sd) {
                Rect2i rel = sd.getRect();
                screen = new Rect2i(
                    layoutRect.getX() + rel.getX(),
                    layoutRect.getY() + rel.getY(),
                    rel.getWidth(),
                    rel.getHeight());
                fallback++;
            } else {
                skipped++;
                continue;
            }
            result.add(new GhostTargetInfo(i, screen));
        }
        return result;
    }

    /**
     * JEI Ghost Drag accept から呼ばれる：指定スロットに spec を配置。
     * 戻り値: 配置成功なら true。範囲外/型ミスマッチ等で拒否されたら false。
     */
    public boolean setSlotIngredient(int slotIndex, IngredientSpec spec) {
        if (draft == null || slotIndex < 0 || slotIndex >= draft.slotCount()) return false;
        if (!draft.acceptsAt(slotIndex, spec)) return false;
        draft.setSlot(slotIndex, spec);
        rebuildDrawable();
        return true;
    }

    /** 後方互換：ItemStack 渡し → spec 変換 → 配置。 */
    public boolean setSlotItem(int slotIndex, ItemStack stack) {
        return setSlotIngredient(slotIndex, ingredientFromCursor(stack));
    }

    private int findSlotIndex(IRecipeSlotDrawable target) {
        if (drawable == null) return -1;
        List<IRecipeSlotView> views = drawable.getRecipeSlotsView().getSlotViews();
        for (int i = 0; i < views.size(); i++) {
            if (views.get(i) == target) return i;
        }
        return -1;
    }

    public record GhostTargetInfo(int slotIndex, Rect2i screenArea) {}
}