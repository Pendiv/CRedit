package DIV.credit.client.importer;

import DIV.credit.client.io.ScriptWriter.OperationKind;
import org.jetbrains.annotations.Nullable;

import java.nio.file.Path;
import java.util.List;

/**
 * v2.1.2: import 元 .js から 1 statement 単位で抽出したレシピ操作。
 * <ul>
 *   <li>{@link OperationKind#ADD}  — event.shaped/.shapeless/.custom 単独</li>
 *   <li>{@link OperationKind#EDIT} — // EDIT of original + event.remove + event.add/custom の三連</li>
 *   <li>{@link OperationKind#DELETE} — event.remove 単独 (EDIT セットに属さない)</li>
 * </ul>
 *
 * @param kind         操作種別
 * @param modid        出力先 modid (kubejs/server_scripts/generated/&lt;modid&gt;/imported_*.js)
 * @param recipeId     ADD/EDIT の新 ID、または DELETE の対象 ID
 * @param origRecipeId EDIT のみ、置換元 ID。それ以外は null
 * @param outputId     ADD/EDIT で生成される output アイテムの id (conflict 判定 / id 推測用)。不明なら null
 * @param ingredients  ADD/EDIT の入力 ingredient id 一覧 (conflict 判定用)。空可
 * @param sourceFile   抽出元 .js のパス (UI 表示 / debug 用)
 * @param codeBody     .js に再書き出すための原文ブロック (末尾改行付き)
 * @param recipeType   "shaped"/"shapeless"/"custom"/"remove" 等 (conflict 判定 type 比較)
 * @param recipeTypeId event.custom の type フィールド値 (例 "ae2:charger") — shaped/shapeless では null
 */
public record ImportedRecipe(
    OperationKind kind,
    String modid,
    String recipeId,
    @Nullable String origRecipeId,
    @Nullable String outputId,
    List<String> ingredients,
    Path sourceFile,
    String codeBody,
    String recipeType,
    @Nullable String recipeTypeId
) {

    /** UI 表示用の短い見出し。 */
    public String displayLabel() {
        return switch (kind) {
            case ADD    -> "ADD " + (outputId != null ? outputId : recipeId);
            case EDIT   -> "EDIT " + (origRecipeId != null ? origRecipeId : recipeId);
            case DELETE -> "DEL " + recipeId;
        };
    }
}
