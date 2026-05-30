package DIV.credit.client.jei.mek;

import DIV.credit.client.draft.IngredientSpec;
import mekanism.api.MekanismAPI;
import mekanism.api.chemical.Chemical;
import mekanism.api.chemical.ChemicalStack;
import mekanism.client.recipe_viewer.jei.MekanismJEI;
import mezz.jei.api.ingredients.IIngredientType;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.Nullable;

/**
 * JEI ingredient ⇔ Mekanism chemical の変換アダプタ。
 *
 * <p><b>1.21:</b> Mekanism は Gas/Infusion/Pigment/Slurry を単一 {@link ChemicalStack} (= {@link Chemical})
 * に統合した。 そのため 1.20.1 の 4 種別経路は廃止し、 単一 ChemicalStack で扱う。
 * JEI ingredient type も単一 {@link MekanismJEI#TYPE_CHEMICAL}。
 *
 * <p>本 class は mekanism 直接 API を参照するため、 {@code ModList.isLoaded("mekanism")} が真の時のみ
 * touch される (= class isolation。 Mek 非導入環境では load されず安全)。
 * {@link IngredientSpec.Gas#chemicalType()} は 1.21 では識別子としては無意味だが、 model 互換のため保持。
 */
public final class MekanismIngredientAdapter {

    private MekanismIngredientAdapter() {}

    /** JEI ChemicalStack ingredient type (= 4 種統合の単一型)。 */
    public static IIngredientType<ChemicalStack> chemicalType() {
        return MekanismJEI.TYPE_CHEMICAL;
    }

    /** JEI ingredient が ChemicalStack なら {@link IngredientSpec.Gas} へ変換。 そうでなければ null。 */
    @Nullable
    public static <T> IngredientSpec tryChemical(IIngredientType<T> type, T value) {
        if (value instanceof ChemicalStack cs && !cs.isEmpty()) {
            ResourceLocation id = MekanismAPI.CHEMICAL_REGISTRY.getKey(cs.getChemical());
            if (id == null) return null;
            return IngredientSpec.ofGas(id, (int) cs.getAmount());
        }
        return null;
    }

    /** 旧 API 名 (= gas のみ判定だった)。 1.21 では tryChemical に集約。 */
    @Nullable
    @Deprecated
    public static <T> IngredientSpec tryGas(IIngredientType<T> type, T value) {
        return tryChemical(type, value);
    }

    /** {@link IngredientSpec.Gas} → Mek {@link ChemicalStack}。 不正 / 未登録 id は {@link ChemicalStack#EMPTY}。 */
    public static ChemicalStack toChemicalStack(@Nullable IngredientSpec.Gas g) {
        if (g == null || g.gasId() == null) return ChemicalStack.EMPTY;
        Chemical chem = MekanismAPI.CHEMICAL_REGISTRY.get(g.gasId());
        if (chem == null) return ChemicalStack.EMPTY;
        ChemicalStack cs = new ChemicalStack(chem, Math.max(1L, g.amount()));
        return cs.isEmpty() ? ChemicalStack.EMPTY : cs;
    }
}
