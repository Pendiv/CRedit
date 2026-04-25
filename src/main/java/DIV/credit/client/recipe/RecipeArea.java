package DIV.credit.client.recipe;

import DIV.credit.Credit;
import DIV.credit.client.draft.IngredientSpec;
import DIV.credit.client.draft.RecipeDraft;
import DIV.credit.client.fluid.FluidItemHelper;
import DIV.credit.client.tag.TagItemHelper;
import DIV.credit.jei.CraftPatternJeiPlugin;
import mezz.jei.api.gui.IRecipeLayoutDrawable;
import mezz.jei.api.gui.ingredient.IRecipeSlotDrawable;
import mezz.jei.api.gui.ingredient.IRecipeSlotView;
import mezz.jei.api.gui.inputs.RecipeSlotUnderMouse;
import mezz.jei.api.recipe.IFocusGroup;
import mezz.jei.api.recipe.IRecipeManager;
import mezz.jei.api.recipe.RecipeIngredientRole;
import mezz.jei.api.recipe.RecipeType;
import mezz.jei.api.recipe.category.IRecipeCategory;
import mezz.jei.api.runtime.IJeiRuntime;
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
        } else {
            repositionDrawable();
            try { drawable.tick(); } catch (Exception ignored) {}
            sampledBoundsCache = sampleSlotBounds();
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
        } else if (statusMessage != null) {
            var font = Minecraft.getInstance().font;
            int tx = areaLeft + (areaWidth - font.width(statusMessage)) / 2;
            int ty = areaTop  + (areaHeight - font.lineHeight) / 2;
            g.drawString(font, Component.literal(statusMessage), tx, ty, 0xFFAAAAAA, false);
        }
    }

    public void renderOverlays(GuiGraphics g, int mouseX, int mouseY) {
        if (drawable != null) drawable.drawOverlays(g, mouseX, mouseY);
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

        if (button == 0) {
            if (cursor != null && !cursor.isEmpty()) {
                IngredientSpec spec = ingredientFromCursor(cursor);
                if (!draft.acceptsAt(slotIndex, spec)) {
                    Credit.LOGGER.info("[CraftPattern] slot[{}] REJECTED: spec={} kind={}",
                        slotIndex, spec.getClass().getSimpleName(), draft.slotKind(slotIndex).name());
                    return true;
                }
                draft.setSlot(slotIndex, spec);
                rebuildDrawable();
            }
        } else if (button == 1) {
            if (Screen.hasShiftDown()) {
                for (int i = 0; i < draft.slotCount(); i++) {
                    draft.setSlot(i, IngredientSpec.EMPTY);
                }
                rebuildDrawable();
            } else if (Screen.hasControlDown()) {
                draft.setSlot(slotIndex, IngredientSpec.EMPTY);
                rebuildDrawable();
            } else {
                // bare right-click: increment count by spec step (wrap to step at max)
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

    /** JEI Ghost Drag accept から呼ばれる：指定スロットに spec を配置（型ミスマッチは silently 拒否）。 */
    public void setSlotIngredient(int slotIndex, IngredientSpec spec) {
        if (draft == null || slotIndex < 0 || slotIndex >= draft.slotCount()) return;
        if (!draft.acceptsAt(slotIndex, spec)) return;
        draft.setSlot(slotIndex, spec);
        rebuildDrawable();
    }

    /** 後方互換：ItemStack 渡し → spec 変換 → 配置。 */
    public void setSlotItem(int slotIndex, ItemStack stack) {
        setSlotIngredient(slotIndex, ingredientFromCursor(stack));
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