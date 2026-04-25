package DIV.credit.client.draft.mek;

import DIV.credit.client.draft.RecipeDraft;
import mezz.jei.api.recipe.RecipeType;
import mezz.jei.api.recipe.category.IRecipeCategory;
import org.jetbrains.annotations.Nullable;

/**
 * Mekanism 関連 Draft 生成のゲートウェイ。
 * Mek クラスを直接参照するので、呼び出し側 (DraftStore) は ModList で gating すること。
 */
public final class MekanismSupport {

    private MekanismSupport() {}

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