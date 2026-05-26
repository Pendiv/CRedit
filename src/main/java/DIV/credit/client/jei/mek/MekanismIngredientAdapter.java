package DIV.credit.client.jei.mek;

import DIV.credit.client.draft.IngredientSpec;
import mekanism.api.MekanismAPI;
import mekanism.api.chemical.gas.Gas;
import mekanism.api.chemical.gas.GasStack;
import mekanism.api.chemical.infuse.InfuseType;
import mekanism.api.chemical.infuse.InfusionStack;
import mekanism.api.chemical.pigment.Pigment;
import mekanism.api.chemical.pigment.PigmentStack;
import mekanism.api.chemical.slurry.Slurry;
import mekanism.api.chemical.slurry.SlurryStack;
import mekanism.client.jei.MekanismJEI;
import mezz.jei.api.ingredients.IIngredientType;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.Nullable;

import java.util.Optional;

/**
 * JEI から渡された ingredient が Mekanism の chemical (gas/infusion/pigment/slurry) かを判定し
 * {@link IngredientSpec.Gas} (= chemical 全般を表す record) に変換。
 * <p>Mek クラスを直接参照するので、 呼び出し側が {@code ModList.isLoaded("mekanism")} を確認してから呼ぶこと。
 */
public final class MekanismIngredientAdapter {

    private MekanismIngredientAdapter() {}

    /** JEI ingredient → IngredientSpec.Gas (= 4 種 chemical 自動判別)。 該当なし null。 */
    @Nullable
    public static <T> IngredientSpec tryChemical(IIngredientType<T> type, T value) {
        // GAS
        Optional<GasStack> gOpt = MekanismJEI.TYPE_GAS.castIngredient(value);
        if (gOpt.isPresent() && !gOpt.get().isEmpty()) {
            GasStack gs = gOpt.get();
            ResourceLocation id = MekanismAPI.gasRegistry().getKey(gs.getType());
            if (id == null) return null;
            return IngredientSpec.ofGas(id, (int) Math.min(Integer.MAX_VALUE, gs.getAmount()));
        }
        // INFUSION
        Optional<InfusionStack> iOpt = MekanismJEI.TYPE_INFUSION.castIngredient(value);
        if (iOpt.isPresent() && !iOpt.get().isEmpty()) {
            InfusionStack is = iOpt.get();
            ResourceLocation id = MekanismAPI.infuseTypeRegistry().getKey(is.getType());
            if (id == null) return null;
            return IngredientSpec.ofInfusion(id, (int) Math.min(Integer.MAX_VALUE, is.getAmount()));
        }
        // PIGMENT
        Optional<PigmentStack> pOpt = MekanismJEI.TYPE_PIGMENT.castIngredient(value);
        if (pOpt.isPresent() && !pOpt.get().isEmpty()) {
            PigmentStack ps = pOpt.get();
            ResourceLocation id = MekanismAPI.pigmentRegistry().getKey(ps.getType());
            if (id == null) return null;
            return IngredientSpec.ofPigment(id, (int) Math.min(Integer.MAX_VALUE, ps.getAmount()));
        }
        // SLURRY
        Optional<SlurryStack> sOpt = MekanismJEI.TYPE_SLURRY.castIngredient(value);
        if (sOpt.isPresent() && !sOpt.get().isEmpty()) {
            SlurryStack ss = sOpt.get();
            ResourceLocation id = MekanismAPI.slurryRegistry().getKey(ss.getType());
            if (id == null) return null;
            return IngredientSpec.ofSlurry(id, (int) Math.min(Integer.MAX_VALUE, ss.getAmount()));
        }
        return null;
    }

    /** 旧 API 名 (= gas のみ判定)。 backward compat、 内部は tryChemical へ delegate。 */
    @Nullable
    @Deprecated
    public static <T> IngredientSpec tryGas(IIngredientType<T> type, T value) {
        return tryChemical(type, value);
    }

    // ───── IngredientSpec.Gas → 各 Mek Stack 変換 (= chemicalType で分岐) ─────

    /** IngredientSpec.Gas (chemicalType=GAS) → GasStack。 非 GAS / null / empty なら EMPTY。 */
    public static GasStack toGasStack(IngredientSpec spec) {
        if (!(spec instanceof IngredientSpec.Gas g) || g.gasId() == null) return GasStack.EMPTY;
        if (g.chemicalType() != IngredientSpec.ChemicalType.GAS) return GasStack.EMPTY;
        Gas gas = MekanismAPI.gasRegistry().getValue(g.gasId());
        if (gas == null) return GasStack.EMPTY;
        return new GasStack(gas, g.amount());
    }

    public static InfusionStack toInfusionStack(IngredientSpec spec) {
        if (!(spec instanceof IngredientSpec.Gas g) || g.gasId() == null) return InfusionStack.EMPTY;
        if (g.chemicalType() != IngredientSpec.ChemicalType.INFUSION) return InfusionStack.EMPTY;
        InfuseType it = MekanismAPI.infuseTypeRegistry().getValue(g.gasId());
        if (it == null) return InfusionStack.EMPTY;
        return new InfusionStack(it, g.amount());
    }

    public static PigmentStack toPigmentStack(IngredientSpec spec) {
        if (!(spec instanceof IngredientSpec.Gas g) || g.gasId() == null) return PigmentStack.EMPTY;
        if (g.chemicalType() != IngredientSpec.ChemicalType.PIGMENT) return PigmentStack.EMPTY;
        Pigment p = MekanismAPI.pigmentRegistry().getValue(g.gasId());
        if (p == null) return PigmentStack.EMPTY;
        return new PigmentStack(p, g.amount());
    }

    public static SlurryStack toSlurryStack(IngredientSpec spec) {
        if (!(spec instanceof IngredientSpec.Gas g) || g.gasId() == null) return SlurryStack.EMPTY;
        if (g.chemicalType() != IngredientSpec.ChemicalType.SLURRY) return SlurryStack.EMPTY;
        Slurry s = MekanismAPI.slurryRegistry().getValue(g.gasId());
        if (s == null) return SlurryStack.EMPTY;
        return new SlurryStack(s, g.amount());
    }
}
