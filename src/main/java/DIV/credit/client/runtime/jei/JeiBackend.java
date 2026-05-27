package DIV.credit.client.runtime.jei;

import DIV.credit.Credit;
import DIV.credit.client.draft.IngredientSpec;
import DIV.credit.client.runtime.CreditCategory;
import DIV.credit.client.runtime.CreditRecipe;
import DIV.credit.client.runtime.CreditRuntimeBackend;
import DIV.credit.client.runtime.CreditSlot;
import DIV.credit.jei.CraftPatternJeiPlugin;
import mezz.jei.api.gui.IRecipeLayoutDrawable;
import mezz.jei.api.recipe.IRecipeManager;
import mezz.jei.api.recipe.RecipeType;
import mezz.jei.api.recipe.category.IRecipeCategory;
import mezz.jei.api.runtime.IJeiRuntime;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.fml.ModList;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

/**
 * JEI backend 実装 (= Phase 2a: 列挙 + probe path)。
 * <p>Phase 2 以降の各 segment で残メソッド (= search/hover/drag-drop/overlay/navigation) を順次実装。
 * <p>runtime は {@link CraftPatternJeiPlugin#runtime} (= JEI 側 callback で set) を参照。
 * 未 set の場合 isAvailable=false で safe fallback。
 */
public final class JeiBackend implements CreditRuntimeBackend {

    @Override public String id() { return "jei"; }

    /** JEI mod load + runtime hook 完了の両方が必要。 早期呼出時は false 返却で stub 動作。 */
    @Override
    public boolean isAvailable() {
        return ModList.get().isLoaded("jei") && CraftPatternJeiPlugin.runtime != null;
    }

    @Nullable
    private IJeiRuntime rt() { return CraftPatternJeiPlugin.runtime; }

    // ──────────────── Category / Recipe 列挙 (Phase 2a) ────────────────

    @Override
    public List<CreditCategory> getCategories() {
        IJeiRuntime r = rt();
        if (r == null) return Collections.emptyList();
        try {
            List<CreditCategory> out = new ArrayList<>();
            r.getRecipeManager().createRecipeCategoryLookup().get()
                .forEach(cat -> out.add(JeiAdapters.toCreditCategory(cat)));
            return out;
        } catch (Exception e) {
            Credit.LOGGER.warn("[C2001] JeiBackend.getCategories failed: {}", e.toString());
            return Collections.emptyList();
        }
    }

    @Override
    public List<CreditRecipe> getRecipes(CreditCategory category) {
        IJeiRuntime r = rt();
        if (r == null || category == null) return Collections.emptyList();
        if (!(category.nativeRef() instanceof IRecipeCategory<?> jeiCat)) return Collections.emptyList();
        try {
            return collectRecipes(r.getRecipeManager(), jeiCat);
        } catch (Exception e) {
            Credit.LOGGER.warn("[C2002] JeiBackend.getRecipes({}) failed: {}",
                category.uid(), e.toString());
            return Collections.emptyList();
        }
    }

    /** raw types 経由で IRecipeCategory&lt;T&gt; → recipe instance 列を吸い出す helper。 */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private static List<CreditRecipe> collectRecipes(IRecipeManager rm, IRecipeCategory<?> jeiCat) {
        List<CreditRecipe> out = new ArrayList<>();
        IRecipeCategory raw = jeiCat;
        RecipeType<?> rt = jeiCat.getRecipeType();
        rm.createRecipeLookup(rt).includeHidden().get()
            .forEach(recipe -> out.add(JeiAdapters.toCreditRecipe(raw, recipe)));
        return out;
    }

    @Override
    public Optional<CreditCategory> findCategory(ResourceLocation uid) {
        IJeiRuntime r = rt();
        if (r == null || uid == null) return Optional.empty();
        try {
            return r.getRecipeManager().createRecipeCategoryLookup().get()
                .filter(cat -> uid.equals(cat.getRecipeType().getUid()))
                .findFirst()
                .map(JeiAdapters::toCreditCategory);
        } catch (Exception e) {
            Credit.LOGGER.warn("[C2003] JeiBackend.findCategory({}) failed: {}", uid, e.toString());
            return Optional.empty();
        }
    }

    @Override
    public List<CreditSlot> probeSlots(CreditRecipe recipe) {
        IJeiRuntime r = rt();
        if (r == null || recipe == null) return Collections.emptyList();
        if (!(recipe.category().nativeRef() instanceof IRecipeCategory<?> jeiCat)) return Collections.emptyList();
        try {
            IRecipeLayoutDrawable<?> drawable = JeiAdapters.buildDrawable(r, jeiCat, recipe.nativeRef());
            if (drawable == null) return Collections.emptyList();
            return JeiAdapters.toCreditSlots(drawable.getRecipeSlotsView().getSlotViews());
        } catch (Exception e) {
            Credit.LOGGER.warn("[C2004] JeiBackend.probeSlots({}) failed: {}",
                recipe.id(), e.toString());
            return Collections.emptyList();
        }
    }

    // ──────────────── Search / Hover (Phase 2b) ────────────────

    /** 既存 {@link DIV.credit.client.input.JeiSearchFocusHelper} に委譲 (= JEI 内部 reflection)。 */
    @Override
    public boolean isSearchFocused() {
        if (!isAvailable()) return false;
        return DIV.credit.client.input.JeiSearchFocusHelper.isJeiSearchFocused();
    }

    /** JEI IIngredientFilter 経由で検索文字列取得。 取得不能なら空文字。 */
    @Override
    public String getSearchText() {
        IJeiRuntime r = rt();
        if (r == null) return "";
        try {
            var filter = r.getIngredientFilter();
            return filter == null ? "" : filter.getFilterText();
        } catch (Throwable t) {
            return "";
        }
    }

    /** 検索文字列を上書き。 不可なら noop。 */
    @Override
    public void setSearchText(String text) {
        IJeiRuntime r = rt();
        if (r == null || text == null) return;
        try {
            var filter = r.getIngredientFilter();
            if (filter != null) filter.setFilterText(text);
        } catch (Throwable t) {
            Credit.LOGGER.debug("[CraftPattern] JeiBackend.setSearchText failed: {}", t.toString());
        }
    }

    /**
     * JEI ingredient list overlay の mouse 下 ingredient を IngredientSpec で返す。
     * <p>JEI 15.x の {@code getIngredientUnderMouse(type)} は @Nullable V 直接返却 (= Optional 非対応)。
     * Item / Fluid の順で試行、 chemical 等は対応外 (= JEI 経由では拾えないため、 EmiBackend 側で別途扱う)。
     * <p>mouseX/mouseY は無視 (= JEI 内部の current mouse 状態を使う)。 EMI 側は座標を直接受ける。
     */
    @Override
    public @Nullable IngredientSpec hoveredIngredient(int mouseX, int mouseY) {
        IJeiRuntime r = rt();
        if (r == null) return null;
        try {
            var overlay = r.getIngredientListOverlay();
            if (overlay == null) return null;
            net.minecraft.world.item.ItemStack item =
                overlay.getIngredientUnderMouse(mezz.jei.api.constants.VanillaTypes.ITEM_STACK);
            if (item != null && !item.isEmpty()) {
                net.minecraft.world.item.ItemStack copy = item.copy();
                copy.setCount(1);
                return new IngredientSpec.Item(copy);
            }
            net.minecraftforge.fluids.FluidStack fluid =
                overlay.getIngredientUnderMouse(mezz.jei.api.forge.ForgeTypes.FLUID_STACK);
            if (fluid != null && !fluid.isEmpty()) {
                return new IngredientSpec.Fluid(fluid.copy());
            }
        } catch (Exception ignored) {}
        return null;
    }

    // ──────────────── Navigation (Phase 2d) ────────────────

    /** showRecipesFor: spec を生み出す recipe 一覧を JEI で focus 表示。 */
    @Override
    public void showRecipesFor(IngredientSpec spec) {
        showWithRole(spec, mezz.jei.api.recipe.RecipeIngredientRole.OUTPUT);
    }

    /** showUsesOf: spec を消費する recipe 一覧を JEI で focus 表示。 */
    @Override
    public void showUsesOf(IngredientSpec spec) {
        showWithRole(spec, mezz.jei.api.recipe.RecipeIngredientRole.INPUT);
    }

    /**
     * spec → JEI ITypedIngredient + role → focus → recipesGui.show(focus)。
     * Item/Fluid/Gas 対応。 backend 不在 / 変換不能時 noop。
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private void showWithRole(IngredientSpec spec, mezz.jei.api.recipe.RecipeIngredientRole role) {
        IJeiRuntime r = rt();
        if (r == null || spec == null || spec.isEmpty()) return;
        try {
            IngredientSpec base = spec.unwrap();
            mezz.jei.api.ingredients.IIngredientType type = null;
            Object value = null;
            if (base instanceof IngredientSpec.Item it && !it.stack().isEmpty()) {
                type  = mezz.jei.api.constants.VanillaTypes.ITEM_STACK;
                value = it.stack();
            } else if (base instanceof IngredientSpec.Fluid fl && !fl.stack().isEmpty()) {
                type  = mezz.jei.api.forge.ForgeTypes.FLUID_STACK;
                value = fl.stack();
            } else if (base instanceof IngredientSpec.Gas g && g.gasId() != null
                && net.minecraftforge.fml.ModList.get().isLoaded("mekanism")) {
                // Mek chemical (= 4 種共用) → 該当する MekanismJEI TYPE_xxx + stack
                Object stack = null;
                switch (g.chemicalType()) {
                    case GAS -> { type = mekanism.client.jei.MekanismJEI.TYPE_GAS;
                        stack = DIV.credit.client.jei.mek.MekanismIngredientAdapter.toGasStack(g); }
                    case INFUSION -> { type = mekanism.client.jei.MekanismJEI.TYPE_INFUSION;
                        stack = DIV.credit.client.jei.mek.MekanismIngredientAdapter.toInfusionStack(g); }
                    case PIGMENT -> { type = mekanism.client.jei.MekanismJEI.TYPE_PIGMENT;
                        stack = DIV.credit.client.jei.mek.MekanismIngredientAdapter.toPigmentStack(g); }
                    case SLURRY -> { type = mekanism.client.jei.MekanismJEI.TYPE_SLURRY;
                        stack = DIV.credit.client.jei.mek.MekanismIngredientAdapter.toSlurryStack(g); }
                }
                value = stack;
            }
            if (type == null || value == null) return;
            var focus = r.getJeiHelpers().getFocusFactory().createFocus(role, type, value);
            r.getRecipesGui().show(focus);
        } catch (Throwable t) {
            Credit.LOGGER.warn("[C2005] JeiBackend.show{} failed: {}",
                role == mezz.jei.api.recipe.RecipeIngredientRole.OUTPUT ? "RecipesFor" : "UsesOf",
                t.toString());
        }
    }

    /**
     * category UID 指定で JEI 開く。 既存 {@link DIV.credit.client.jei.JeiNavigation#openCategory} と同等。
     * Phase 4 で EMI 側 entry point から共通呼出可能化。
     */
    @Override
    public boolean openCategoryByUid(ResourceLocation uid) {
        IJeiRuntime r = rt();
        if (r == null || uid == null) return false;
        try {
            mezz.jei.api.recipe.RecipeType<?> type = r.getJeiHelpers().getRecipeType(uid).orElse(null);
            if (type == null) return false;
            DIV.credit.client.jei.OriginTracker.enter(type);
            r.getRecipesGui().showTypes(java.util.List.of(type));
            return true;
        } catch (Throwable t) {
            Credit.LOGGER.warn("[C2006] JeiBackend.openCategoryByUid({}) failed: {}", uid, t.toString());
            return false;
        }
    }

    /**
     * recipe ID 指定で JEI 開く。 既存 {@link DIV.credit.client.jei.JeiNavigation#openRecipeId} に delegate。
     */
    @Override
    public boolean openRecipeId(ResourceLocation recipeId, @Nullable String categoryHint) {
        if (recipeId == null) return false;
        try {
            return DIV.credit.client.jei.JeiNavigation.openRecipeId(recipeId, categoryHint);
        } catch (Throwable t) {
            Credit.LOGGER.warn("[C2007] JeiBackend.openRecipeId({}) failed: {}", recipeId, t.toString());
            return false;
        }
    }

    @Override public void registerExclusionArea(Class<? extends Screen> screenClass, ExclusionAreaProvider provider) {}

    /**
     * Mek chemical 描画 (= Phase 2e で 3 widget file の renderGas 重複から集約)。
     * chemicalType に応じて TYPE_GAS / TYPE_INFUSION / TYPE_PIGMENT / TYPE_SLURRY に振分け、
     * Mek の JEI ingredient renderer に delegate。
     */
    @Override
    @SuppressWarnings({"unchecked", "rawtypes"})
    public void renderChemical(net.minecraft.client.gui.GuiGraphics g,
                               IngredientSpec.Gas chemical, int x, int y) {
        if (!net.minecraftforge.fml.ModList.get().isLoaded("mekanism")) return;
        if (chemical == null || chemical.gasId() == null) return;
        IJeiRuntime r = rt();
        if (r == null) return;
        try {
            Object stack;
            mezz.jei.api.ingredients.IIngredientType<?> type;
            switch (chemical.chemicalType()) {
                case GAS -> {
                    var st = DIV.credit.client.jei.mek.MekanismIngredientAdapter.toGasStack(chemical);
                    if (st.isEmpty()) return;
                    stack = st; type = mekanism.client.jei.MekanismJEI.TYPE_GAS;
                }
                case INFUSION -> {
                    var st = DIV.credit.client.jei.mek.MekanismIngredientAdapter.toInfusionStack(chemical);
                    if (st.isEmpty()) return;
                    stack = st; type = mekanism.client.jei.MekanismJEI.TYPE_INFUSION;
                }
                case PIGMENT -> {
                    var st = DIV.credit.client.jei.mek.MekanismIngredientAdapter.toPigmentStack(chemical);
                    if (st.isEmpty()) return;
                    stack = st; type = mekanism.client.jei.MekanismJEI.TYPE_PIGMENT;
                }
                case SLURRY -> {
                    var st = DIV.credit.client.jei.mek.MekanismIngredientAdapter.toSlurryStack(chemical);
                    if (st.isEmpty()) return;
                    stack = st; type = mekanism.client.jei.MekanismJEI.TYPE_SLURRY;
                }
                default -> { return; }
            }
            var renderer = (mezz.jei.api.ingredients.IIngredientRenderer) r.getIngredientManager().getIngredientRenderer(type);
            renderer.render(g, stack, x, y);
        } catch (Throwable t) {
            Credit.LOGGER.debug("[CraftPattern] JeiBackend.renderChemical failed: {}", t.toString());
        }
    }
}
