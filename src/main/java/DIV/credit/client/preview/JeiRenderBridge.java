package DIV.credit.client.preview;

import DIV.credit.Credit;
import DIV.credit.jei.CraftPatternJeiPlugin;
import mezz.jei.api.gui.IRecipeLayoutDrawable;
import mezz.jei.api.recipe.IFocusGroup;
import mezz.jei.api.recipe.IRecipeManager;
import mezz.jei.api.recipe.category.IRecipeCategory;
import mezz.jei.api.runtime.IJeiRuntime;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.crafting.Recipe;
import org.jetbrains.annotations.Nullable;

/**
 * v3.0.0 (P1): JEI の {@link IRecipeManager#createRecipeLayoutDrawable} を
 * 呼んで {@link IRecipeLayoutDrawable} を生成する薄いラッパ。
 *
 * <p>制約 C-1 (= データ層 ⊥ 描画層) における 「描画層への入口」。
 * ここから先は JEI の世界、 ここまでは PreviewSource + raw Object の世界。
 *
 * <p>既存 {@code RecipeReEmitter#createDrawable} / {@code findCategoryForRecipe}
 * のロジックを汎用化して持ち上げたもの。 RecipeReEmitter は本クラスに委譲する。
 *
 * <p><b>chat 通知はしない</b> (log warn のみ)。 user 向け通知は呼び出し側
 * ({@link PreviewBus}) で実施。 役割分離。
 */
public final class JeiRenderBridge {

    private JeiRenderBridge() {}

    /**
     * PreviewSource の (category, recipe) ペアから drawable を構築。
     * 失敗時は null + log warn。
     */
    @Nullable
    public static IRecipeLayoutDrawable<?> build(IRecipeCategory<?> category, Object recipe) {
        if (category == null || recipe == null) {
            Credit.LOGGER.warn("[C3004] JeiRenderBridge.build: null arg (category={}, recipe={})",
                category, recipe);
            return null;
        }
        IJeiRuntime runtime = CraftPatternJeiPlugin.runtime;
        if (runtime == null) {
            Credit.LOGGER.warn("[C301] JeiRenderBridge.build: JEI runtime 未準備");
            return null;
        }
        try {
            return buildTyped(runtime, category, recipe);
        } catch (ClassCastException e) {
            Credit.LOGGER.warn("[C3005] JeiRenderBridge.build: type mismatch (category={}, recipe={}): {}",
                category.getRecipeType().getUid(), recipe.getClass().getName(), e.getMessage());
            return null;
        } catch (Exception e) {
            Credit.LOGGER.warn("[C3006] JeiRenderBridge.build: unexpected error", e);
            return null;
        }
    }

    /** ジェネリック helper — raw Object を T にキャスト。 */
    private static <T> IRecipeLayoutDrawable<T> buildTyped(IJeiRuntime runtime,
                                                            IRecipeCategory<?> category,
                                                            Object recipe) {
        @SuppressWarnings("unchecked")
        IRecipeCategory<T> cat = (IRecipeCategory<T>) category;
        @SuppressWarnings("unchecked")
        T typed = (T) recipe;
        IFocusGroup empty = runtime.getJeiHelpers().getFocusFactory().getEmptyFocusGroup();
        return runtime.getRecipeManager()
                      .createRecipeLayoutDrawable(cat, typed, empty)
                      .orElse(null);
    }

    /**
     * vanilla {@link Recipe} から JEI category を heuristic 検索。
     * <p>検索順:
     * <ol>
     *   <li>UID match (= BuiltInRegistries.RECIPE_TYPE から得た uid と category.getRecipeType().getUid() 一致)
     *       AND class match</li>
     *   <li>class match のみ、 単一カテゴリがマッチした場合のみ採用</li>
     * </ol>
     * いずれもマッチしない場合 null。
     */
    @Nullable
    public static IRecipeCategory<?> findCategoryForRecipe(Recipe<?> recipe) {
        IJeiRuntime runtime = CraftPatternJeiPlugin.runtime;
        if (runtime == null || recipe == null) return null;
        IRecipeManager rm = runtime.getRecipeManager();
        ResourceLocation vanillaTypeRl = BuiltInRegistries.RECIPE_TYPE.getKey(recipe.getType());
        // 1. UID + class 両方マッチ
        for (IRecipeCategory<?> cat : rm.createRecipeCategoryLookup().get().toList()) {
            var jeiType = cat.getRecipeType();
            boolean uidMatch = vanillaTypeRl != null && jeiType.getUid().equals(vanillaTypeRl);
            boolean classMatch = jeiType.getRecipeClass().isInstance(recipe);
            if (uidMatch && classMatch) return cat;
        }
        // 2. class match のみ、 unique なら採用
        IRecipeCategory<?> classOnlyHit = null;
        int classHits = 0;
        for (IRecipeCategory<?> cat : rm.createRecipeCategoryLookup().get().toList()) {
            if (cat.getRecipeType().getRecipeClass().isInstance(recipe)) {
                classOnlyHit = cat;
                classHits++;
            }
        }
        return classHits == 1 ? classOnlyHit : null;
    }

    /**
     * recipe type UID から JEI category を直接引く。 ImportedRecipe が recipeTypeId を持ってる時の最短経路。
     */
    @Nullable
    public static IRecipeCategory<?> findCategoryByUid(ResourceLocation uid) {
        IJeiRuntime runtime = CraftPatternJeiPlugin.runtime;
        if (runtime == null || uid == null) return null;
        return runtime.getRecipeManager()
                      .getRecipeType(uid)
                      .map(t -> runtime.getRecipeManager().getRecipeCategory(t))
                      .orElse(null);
    }
}
