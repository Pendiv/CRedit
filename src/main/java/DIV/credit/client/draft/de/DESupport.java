package DIV.credit.client.draft.de;

import DIV.credit.Credit;
import DIV.credit.client.draft.RecipeDraft;
import DIV.credit.jei.CraftPatternJeiPlugin;
import mezz.jei.api.recipe.RecipeType;
import mezz.jei.api.recipe.category.IRecipeCategory;
import mezz.jei.api.runtime.IJeiRuntime;
import org.jetbrains.annotations.Nullable;

/**
 * Draconic Evolution detection + draft factory。v2.0.0。
 * <p>DE には共通 base class が無いので、namespace + IFusionRecipe interface 片方のチェック。
 */
public final class DESupport {

    private DESupport() {}

    private static Class<?> FUSION_INTERFACE;
    private static boolean lookupFailed = false;

    /** category が DE fusion_crafting か判定。 */
    public static boolean isDeCategory(IRecipeCategory<?> cat) {
        if (cat == null) return false;
        RecipeType<?> rt = cat.getRecipeType();
        if (rt == null) return false;
        return "draconicevolution".equals(rt.getUid().getNamespace())
            && "fusion_crafting".equals(rt.getUid().getPath());
    }

    /** Recipe instance が DE IFusionRecipe か判定（後方互換 reflection）。 */
    public static boolean isFusionRecipe(@Nullable Object recipe) {
        if (recipe == null) return false;
        if (FUSION_INTERFACE == null && !lookupFailed) {
            try {
                FUSION_INTERFACE = Class.forName(
                    "com.brandon3055.draconicevolution.api.crafting.IFusionRecipe");
            } catch (ClassNotFoundException e) {
                lookupFailed = true;
                return false;
            }
        }
        return FUSION_INTERFACE != null && FUSION_INTERFACE.isInstance(recipe);
    }

    /** DraftStore.create() から呼ぶ。 */
    @Nullable
    public static RecipeDraft tryCreate(IRecipeCategory<?> cat) {
        if (!isDeCategory(cat)) return null;
        IJeiRuntime rt = CraftPatternJeiPlugin.runtime;
        if (rt == null) return null;
        return FusionCraftingDraft.tryCreate(cat, rt.getRecipeManager());
    }
}
