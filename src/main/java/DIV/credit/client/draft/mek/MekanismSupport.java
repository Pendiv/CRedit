package DIV.credit.client.draft.mek;

import DIV.credit.client.draft.RecipeDraft;
import mezz.jei.api.recipe.RecipeType;
import mezz.jei.api.recipe.category.IRecipeCategory;
import org.jetbrains.annotations.Nullable;

/**
 * Mekanism 関連 Draft 生成のゲートウェイ。
 *
 * 「Mek システムを使うカテゴリ」は modId 関係なく `mekanism.client.jei.BaseRecipeCategory`
 * を継承している。EvolvedMekanism 等の addon もこれに準拠するため、namespace 文字列ではなく
 * クラス継承で判定する。Mek 未ロード環境では BASE_CATEGORY_CLASS が null になり、isMekCategory
 * は常に false を返す。
 */
public final class MekanismSupport {

    private static final Class<?> BASE_CATEGORY_CLASS = tryLoad("mekanism.client.jei.BaseRecipeCategory");

    private MekanismSupport() {}

    private static Class<?> tryLoad(String name) {
        try { return Class.forName(name); } catch (ClassNotFoundException e) { return null; }
    }

    /** Mek システムカテゴリ判定（modId に依存しない）。 */
    public static boolean isMekCategory(@Nullable IRecipeCategory<?> cat) {
        if (cat == null || BASE_CATEGORY_CLASS == null) return false;
        return BASE_CATEGORY_CLASS.isInstance(cat);
    }

    @Nullable
    public static RecipeDraft tryCreate(IRecipeCategory<?> cat) {
        RecipeType<?> rt = cat.getRecipeType();
        String path = rt.getUid().getPath();
        if ("pressurized_reaction_chamber".equals(path)) {
            return new PressurizedReactionDraft(rt);
        }
        return null;
    }
}