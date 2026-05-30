package DIV.credit.client.draft.ie;

import DIV.credit.client.draft.RecipeDraft;
import mezz.jei.api.recipe.category.IRecipeCategory;
import org.jetbrains.annotations.Nullable;

/**
 * Immersive Engineering 関連 Draft 生成のゲートウェイ。
 *
 * 「IE システムを使うカテゴリ」は modId 関係なく
 * `blusunrize.immersiveengineering.common.util.compat.jei.IERecipeCategory`
 * を継承している。
 *
 * Mek/GT と同じく、IE 未ロード環境では BASE_CATEGORY_CLASS が null になり
 * isIeCategory は常に false を返す。
 */
public final class IESupport {

    private static final Class<?> BASE_CATEGORY_CLASS =
        tryLoad("blusunrize.immersiveengineering.common.util.compat.jei.IERecipeCategory");

    private IESupport() {}

    private static Class<?> tryLoad(String name) {
        try { return Class.forName(name); } catch (ClassNotFoundException e) { return null; }
    }

    /** IE システムカテゴリ判定（modId に依存しない）。 */
    public static boolean isIeCategory(@Nullable IRecipeCategory<?> cat) {
        if (cat == null || BASE_CATEGORY_CLASS == null) return false;
        return BASE_CATEGORY_CLASS.isInstance(cat);
    }

    /**
     * hand-written draft があればここで返す。現状は無し → 全カテゴリが GenericDraft 経路に流れる。
     * Mek/GT 同様、必要になったらここに追加。
     */
    @Nullable
    public static RecipeDraft tryCreate(IRecipeCategory<?> cat) {
        return null;
    }
}
