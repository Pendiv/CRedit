package DIV.credit.client.jei;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.crafting.Recipe;
import org.jetbrains.annotations.Nullable;

import java.util.Set;

/**
 * 動的生成レシピ（runtime で再生成されるタイプ）の検出。
 * これらに対する remove/edit はクラッシュや予期せぬ再生成を起こすため除外する。
 * <p>方針: 保守的に namespace + class 名 prefix の二段階。新規 MOD 追加時はこの blocklist を拡張。
 * <p>登録例: re_avaritia (Avaritia の compressor / extreme crafting は dynamic regen)
 */
public final class DynamicRecipeDetector {

    /** Namespace blocklist: この modid のレシピは全て dynamic 扱い。 */
    private static final Set<String> DYNAMIC_NAMESPACES = Set.of(
        "re_avaritia",     // Avaritia: ICompressorRecipe / ITierCraftingRecipe は data 駆動の動的生成
        "avaritia"         // 旧 Avaritia 系を念のため
    );

    /** クラス名 prefix blocklist: getClass().getName() がこの prefix で始まれば dynamic。 */
    private static final Set<String> DYNAMIC_CLASS_PREFIXES = Set.of(
        "committee.nova.mods.avaritia.common.crafting.recipe.CompressorRecipe",
        "committee.nova.mods.avaritia.common.crafting.recipe.ExtremeShapedRecipe",
        "committee.nova.mods.avaritia.common.crafting.recipe.ExtremeShapelessRecipe"
    );

    private DynamicRecipeDetector() {}

    /** Recipe ID または Recipe class から dynamic か判定。null safe。 */
    public static boolean isDynamic(@Nullable Recipe<?> recipe, @Nullable ResourceLocation recipeId) {
        if (recipeId != null && DYNAMIC_NAMESPACES.contains(recipeId.getNamespace())) {
            return true;
        }
        if (recipe != null) {
            String cls = recipe.getClass().getName();
            for (String prefix : DYNAMIC_CLASS_PREFIXES) {
                if (cls.startsWith(prefix)) return true;
            }
        }
        return false;
    }

    /** ID なしレシピも edit/delete 不能扱い（remove 対象が確定できないため）。 */
    public static boolean isEditableOrDeletable(@Nullable Recipe<?> recipe, @Nullable ResourceLocation recipeId) {
        if (recipeId == null) return false;
        return !isDynamic(recipe, recipeId);
    }
}
