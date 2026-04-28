package DIV.credit.client.draft.create;

import DIV.credit.client.draft.RecipeDraft;
import mezz.jei.api.recipe.category.IRecipeCategory;
import org.jetbrains.annotations.Nullable;

/**
 * Create 関連 Draft 生成のゲートウェイ。
 *
 * 「Create システムを使うカテゴリ」は modId 関係なく
 * `com.simibubi.create.compat.jei.category.CreateRecipeCategory` を継承している。
 *
 * これは Create の全 22 JEI カテゴリをカバーする。Mek/IE と同じく
 * Create 未ロード環境では BASE_CATEGORY_CLASS が null になり
 * isCreateCategory は常に false を返す。
 */
public final class CreateSupport {

    private static final Class<?> BASE_CATEGORY_CLASS =
        tryLoad("com.simibubi.create.compat.jei.category.CreateRecipeCategory");

    private CreateSupport() {}

    private static Class<?> tryLoad(String name) {
        try { return Class.forName(name); } catch (ClassNotFoundException e) { return null; }
    }

    /** Create システムカテゴリ判定（modId に依存しない）。 */
    public static boolean isCreateCategory(@Nullable IRecipeCategory<?> cat) {
        if (cat == null || BASE_CATEGORY_CLASS == null) return false;
        return BASE_CATEGORY_CLASS.isInstance(cat);
    }

    /** hand-written draft があればここで返す。現状は無し → 全カテゴリが GenericDraft 経路に流れる。 */
    @Nullable
    public static RecipeDraft tryCreate(IRecipeCategory<?> cat) {
        return null;
    }
}
