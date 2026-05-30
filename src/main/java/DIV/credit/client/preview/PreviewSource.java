package DIV.credit.client.preview;

import mezz.jei.api.recipe.category.IRecipeCategory;
import net.minecraft.network.chat.Component;

/**
 * v3.0.0: Preview ウィンドウに流すデータの抽象。
 * <p>制約 C-1 (= データ層 ⊥ JEI 描画層) の中核 interface。実装側は
 * 「自分が何の category で、 どの recipe object を持ってるか」 を返すだけ。
 * {@link PreviewBus} / {@link JeiRenderBridge} は実装の中身を一切知らない。
 *
 * <h3>同梱 implement (v3.0.0)</h3>
 * <ul>
 *   <li>{@code DraftPreviewSource}    : {@code RecipeDraft.synthesizePreviewRecipe()} を呼ぶ</li>
 *   <li>{@code ImportedPreviewSource} : ImportedRecipe を Recipe&lt;?&gt; に復元して保持</li>
 * </ul>
 *
 * <h3>契約 (= 実装側の責務)</h3>
 * <ul>
 *   <li>{@link #getCategory()}     non-null 保証。null なら PreviewBus が silent fail + chat 通知</li>
 *   <li>{@link #getRecipeObject()} の型 = getCategory().getRecipeType().getRecipeClass() の subtype。
 *       違う型を返すと JeiRenderBridge で ClassCastException → silent fail</li>
 *   <li>{@link #getLabel()}        non-null 保証。null なら fallback で "untitled preview"</li>
 *   <li><b>idempotent</b>: 各 method は複数回呼ばれても同じ値を返すこと (side effect なし)</li>
 * </ul>
 */
public interface PreviewSource {

    /** どの JEI category で描画するか。 non-null。 */
    IRecipeCategory<?> getCategory();

    /**
     * category の R 型に対応する recipe object。
     * raw Object で返すが、 JeiRenderBridge 内で casting が走る (= 型不一致 silent fail)。
     */
    Object getRecipeObject();

    /**
     * PreviewWindow の title bar 表示用。 non-null。
     * 例: "Draft: gtceu:compressor" / "Import: foo.js : kubejs:recipe_001"
     */
    Component getLabel();
}
