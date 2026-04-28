package DIV.credit.client.draft.gt;

import DIV.credit.client.draft.IngredientSpec;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.fluids.FluidStack;
import org.jetbrains.annotations.Nullable;

/**
 * GT KubeJS emit ヘルパ。
 * Configured wrapper を unwrap して `'ns:path'` / `Fluid.of(...)` 形式に整形。
 * GT_CHANCE は内部 1000分率 → GT 公開 API の 0..10000 へ ×10 でスケール変換。
 */
public final class GTEmitFormat {

    /** GT_CHANCE のデフォルト chance（100%） */
    public static final int GT_CHANCE_FULL = 10_000;

    private GTEmitFormat() {}

    public static boolean isCatalyst(IngredientSpec s) {
        return s != null && s.option() == IngredientSpec.ItemOption.GT_CATALYST;
    }

    public static boolean isChance(IngredientSpec s) {
        return s != null && s.option() == IngredientSpec.ItemOption.GT_CHANCE;
    }

    /** chancedOutput(stack, chance, boost) の "chance, boost" 部分。 */
    public static String chanceArgs(IngredientSpec s) {
        if (s instanceof IngredientSpec.Configured c
            && c.opt() == IngredientSpec.ItemOption.GT_CHANCE) {
            return (c.chanceMille() * 10) + ", " + (c.tierBoost() * 10);
        }
        return GT_CHANCE_FULL + ", 0";
    }

    /** GT 用 item 表記: "Nx ns:path" or "ns:path"。Tag は "Nx #ns:path"。Configured は unwrap。 */
    @Nullable
    public static String formatItem(IngredientSpec s) {
        if (s == null || s.isEmpty()) return null;
        int c = Math.max(1, s.count());
        IngredientSpec base = s.unwrap();
        if (base instanceof IngredientSpec.Item it && !it.stack().isEmpty()) {
            ResourceLocation rl = BuiltInRegistries.ITEM.getKey(it.stack().getItem());
            return c <= 1 ? "'" + rl + "'" : "'" + c + "x " + rl + "'";
        }
        if (base instanceof IngredientSpec.Tag tg && tg.tagId() != null) {
            return c <= 1 ? "'#" + tg.tagId() + "'" : "'" + c + "x #" + tg.tagId() + "'";
        }
        return null;
    }

    /** GT 用 fluid 表記: Fluid.of('ns:path', amount) or Fluid.of('#tag', amount)。 */
    @Nullable
    public static String formatFluid(IngredientSpec s) {
        if (s == null || s.isEmpty()) return null;
        IngredientSpec base = s.unwrap();
        if (base instanceof IngredientSpec.Fluid fl && !fl.stack().isEmpty()) {
            FluidStack fs = fl.stack();
            ResourceLocation rl = BuiltInRegistries.FLUID.getKey(fs.getFluid());
            return "Fluid.of('" + rl + "', " + fs.getAmount() + ")";
        }
        if (base instanceof IngredientSpec.FluidTag ft && ft.tagId() != null) {
            return "Fluid.of('#" + ft.tagId() + "', " + ft.amount() + ")";
        }
        return null;
    }
}
