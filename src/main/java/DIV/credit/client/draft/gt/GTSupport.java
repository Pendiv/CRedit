package DIV.credit.client.draft.gt;

import DIV.credit.client.draft.RecipeDraft;
import mezz.jei.api.recipe.RecipeType;
import mezz.jei.api.recipe.category.IRecipeCategory;
import org.jetbrains.annotations.Nullable;

/**
 * GT 関連 Draft 生成のゲートウェイ。
 * このクラスは GT のクラスを直接参照するので、呼び出し側 (DraftStore) は GT がロードされている
 * ことを namespace チェック等で確認してから呼ぶこと。
 */
public final class GTSupport {

    private GTSupport() {}

    @Nullable
    public static RecipeDraft tryCreate(IRecipeCategory<?> cat) {
        RecipeType<?> rt = cat.getRecipeType();
        String path = rt.getUid().getPath();
        // First target: compressor only. Add more cases as we generalize.
        if ("compressor".equals(path)) {
            return new GTCompressorDraft(rt);
        }
        if ("assembler".equals(path)) {
            return new GTAssemblerDraft(rt);
        }
        return null;
    }
}