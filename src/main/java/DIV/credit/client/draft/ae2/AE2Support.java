package DIV.credit.client.draft.ae2;

import DIV.credit.client.draft.RecipeDraft;
import DIV.credit.jei.CraftPatternJeiPlugin;
import mezz.jei.api.recipe.RecipeType;
import mezz.jei.api.recipe.category.IRecipeCategory;
import mezz.jei.api.runtime.IJeiRuntime;
import org.jetbrains.annotations.Nullable;

/**
 * AE2 detection + draft factory。v2.1.0。
 * <p>カテゴリ UID で判定:
 * <ul>
 *   <li>ae2:inscriber              → InscriberDraft</li>
 *   <li>ae2:charger                → ChargerDraft</li>
 *   <li>ae2:entropy                → EntropyDraft (簡略版)</li>
 *   <li>ae2:item_transformation    → TransformDraft (★ RecipeType UID は ae2:transform)</li>
 * </ul>
 * <p>ae2:matter_cannon は JEI 非登録、credit 対象外。
 */
public final class AE2Support {

    private AE2Support() {}

    /** AE2 関連カテゴリ判定 (UID ベース)。 */
    public static boolean isAe2Category(@Nullable IRecipeCategory<?> cat) {
        if (cat == null) return false;
        RecipeType<?> rt = cat.getRecipeType();
        if (rt == null) return false;
        if (!"ae2".equals(rt.getUid().getNamespace())) return false;
        String path = rt.getUid().getPath();
        return "inscriber".equals(path)
            || "charger".equals(path)
            || "entropy".equals(path)
            || "item_transformation".equals(path);
    }

    @Nullable
    public static RecipeDraft tryCreate(IRecipeCategory<?> cat) {
        if (!isAe2Category(cat)) return null;
        IJeiRuntime rt = CraftPatternJeiPlugin.runtime;
        if (rt == null) return null;
        String path = cat.getRecipeType().getUid().getPath();
        return switch (path) {
            case "inscriber"           -> InscriberDraft.tryCreate(cat, rt.getRecipeManager());
            case "charger"             -> ChargerDraft.tryCreate(cat, rt.getRecipeManager());
            case "entropy"             -> EntropyDraft.tryCreate(cat, rt.getRecipeManager());
            case "item_transformation" -> TransformDraft.tryCreate(cat, rt.getRecipeManager());
            default -> null;
        };
    }
}
