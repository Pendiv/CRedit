package DIV.credit.client.runtime.emi;

import DIV.credit.CreditConfig;
import DIV.credit.client.draft.IngredientSpec;
import dev.emi.emi.api.stack.EmiIngredient;
import dev.emi.emi.api.stack.EmiStack;
import dev.emi.emi.api.stack.FluidEmiStack;
import dev.emi.emi.api.stack.ItemEmiStack;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.fluids.FluidStack;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * EmiIngredient / EmiStack → {@link IngredientSpec} 変換 helper。
 * <p>2 mode 提供:
 * <ul>
 *   <li>{@link #toSpecForHover(EmiIngredient)}: count=1 で取得 (= hover query 用、 quantity 情報落とす)</li>
 *   <li>{@link #toSpecForDrop(EmiIngredient)}: config default amount を反映 (= drag-drop 用、 将来 Phase 4 で使用)</li>
 * </ul>
 * <p>cycling list (= tag 等で複数 stack 含む EmiIngredient) は先頭 stack を採用。
 * <p>Mek chemical 4 種は pure-EMI 環境では Mek+EMI integration 不在のため対応外。
 * JEMI bridge 経由なら JemiStack 形式で来るので別 path 必要。
 */
public final class EmiIngredientAdapter {

    private EmiIngredientAdapter() {}

    /** hover query 向け: count=1 / amount=1mB で正規化。 */
    @Nullable
    public static IngredientSpec toSpecForHover(EmiIngredient ingredient) {
        EmiStack stack = firstStack(ingredient);
        if (stack == null) return null;
        if (stack instanceof ItemEmiStack item) {
            ItemStack is = item.getItemStack().copy();
            if (is.isEmpty()) return null;
            is.setCount(1);
            return new IngredientSpec.Item(is);
        }
        if (stack instanceof FluidEmiStack fls) {
            Object key = fls.getKey();
            if (!(key instanceof net.minecraft.world.level.material.Fluid f)) return null;
            FluidStack fs = new FluidStack(f, 1);
            // 1.21: FluidStack の NBT は DataComponents 化。 EMI FluidEmiStack の hasNbt/getNbt 廃止。
            return new IngredientSpec.Fluid(fs);
        }
        return null;
    }

    /** drag-drop 向け: config default で count 正規化。 */
    @Nullable
    public static IngredientSpec toSpecForDrop(EmiIngredient ingredient) {
        EmiStack stack = firstStack(ingredient);
        if (stack == null) return null;
        if (stack instanceof ItemEmiStack item) {
            ItemStack is = item.getItemStack().copy();
            if (is.isEmpty()) return null;
            is.setCount(1);  // item slot 既定は 1
            return new IngredientSpec.Item(is);
        }
        if (stack instanceof FluidEmiStack fls) {
            Object key = fls.getKey();
            if (!(key instanceof net.minecraft.world.level.material.Fluid f)) return null;
            int amt = safeFluidDefault();
            FluidStack fs = new FluidStack(f, amt);
            // 1.21: FluidStack の NBT は DataComponents 化。 EMI FluidEmiStack の hasNbt/getNbt 廃止。
            return new IngredientSpec.Fluid(fs);
        }
        return null;
    }

    @Nullable
    private static EmiStack firstStack(EmiIngredient ing) {
        if (ing == null || ing.isEmpty()) return null;
        List<EmiStack> stacks = ing.getEmiStacks();
        if (stacks.isEmpty()) return null;
        EmiStack s = stacks.get(0);
        return (s == null || s.isEmpty()) ? null : s;
    }

    private static int safeFluidDefault() {
        try { return CreditConfig.FLUID_DEFAULT_AMOUNT.get(); }
        catch (Exception e) { return 1000; }
    }
}
