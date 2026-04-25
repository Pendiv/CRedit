package DIV.credit.client.jei.mek;

import DIV.credit.client.draft.IngredientSpec;
import mekanism.api.MekanismAPI;
import mekanism.api.chemical.gas.Gas;
import mekanism.api.chemical.gas.GasStack;
import mekanism.client.jei.MekanismJEI;
import mezz.jei.api.ingredients.IIngredientType;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.Nullable;

import java.util.Optional;

/**
 * JEI から渡された ingredient が Mekanism の Gas かを判定し IngredientSpec.Gas に変換。
 * Mek クラスを直接参照するので、呼び出し側が ModList.isLoaded("mekanism") を確認してから呼ぶこと。
 */
public final class MekanismIngredientAdapter {

    private MekanismIngredientAdapter() {}

    @Nullable
    public static <T> IngredientSpec tryGas(IIngredientType<T> type, T value) {
        Optional<GasStack> opt = MekanismJEI.TYPE_GAS.castIngredient(value);
        if (opt.isEmpty()) return null;
        GasStack gs = opt.get();
        if (gs.isEmpty()) return null;
        ResourceLocation id = MekanismAPI.gasRegistry().getKey(gs.getType());
        if (id == null) return null;
        return IngredientSpec.ofGas(id, (int) Math.min(Integer.MAX_VALUE, gs.getAmount()));
    }

    /** IngredientSpec.Gas → GasStack 変換。null/empty なら GasStack.EMPTY。 */
    public static GasStack toGasStack(IngredientSpec spec) {
        if (!(spec instanceof IngredientSpec.Gas g) || g.gasId() == null) return GasStack.EMPTY;
        Gas gas = MekanismAPI.gasRegistry().getValue(g.gasId());
        if (gas == null) return GasStack.EMPTY;
        return new GasStack(gas, g.amount());
    }
}