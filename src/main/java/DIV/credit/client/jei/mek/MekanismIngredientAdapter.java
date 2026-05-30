package DIV.credit.client.jei.mek;

import DIV.credit.client.draft.IngredientSpec;
import mezz.jei.api.ingredients.IIngredientType;
import org.jetbrains.annotations.Nullable;

/**
 * JEI ingredient ⇔ Mekanism chemical (gas/infusion/pigment/slurry) の変換アダプタ。
 *
 * <p><b>【1.21 移植・一時 stub — 2026-05-31】</b> Mekanism は直接 API (GasStack 等) を使うため、
 * mekanism 依存を入れるまで chemical 変換を無効化している。 {@link #tryChemical} は null を返し、
 * GenericDraft の chemical 抽出は no-op になる (item / fluid slot は通常動作)。
 *
 * <p>screens / runtime 層 (TagBar / RecipeArea / JeiBackend / IngredientSpecToEmi) が使う
 * {@code toGasStack / toInfusionStack / toPigmentStack / toSlurryStack} (= IngredientSpec.Gas → Mek Stack)
 * は Mek 型を返すため stub では提供できない。 それらの層を移植する際に Mekanism 1.21 依存を追加し、
 * 1.20.1 の本実装 ({@code credit/src/.../jei/mek/MekanismIngredientAdapter.java}) ごと復元する。
 */
public final class MekanismIngredientAdapter {

    private MekanismIngredientAdapter() {}

    /** stub: mekanism 依存が無い間は chemical 判定不可 → null (item/fluid は別経路で処理)。 */
    @Nullable
    public static <T> IngredientSpec tryChemical(IIngredientType<T> type, T value) {
        return null;
    }

    /** 旧 API 名 stub (= gas のみ判定だった)。 */
    @Nullable
    @Deprecated
    public static <T> IngredientSpec tryGas(IIngredientType<T> type, T value) {
        return null;
    }
}
