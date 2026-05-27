package DIV.credit.client.runtime.emi;

import DIV.credit.Credit;
import DIV.credit.client.draft.IngredientSpec;
import dev.emi.emi.api.stack.EmiIngredient;
import dev.emi.emi.api.stack.EmiStack;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.material.Fluid;
import net.minecraftforge.fluids.FluidStack;
import org.jetbrains.annotations.Nullable;

/**
 * v3.4.x: {@link IngredientSpec} → {@link EmiIngredient} 変換 (= preview 描画用 substitution)。
 *
 * <p>{@link EmiIngredientAdapter} の逆方向。 EMI native stack 経由なので Mek chemical 4 種は
 * pure-EMI 環境では空表示 (= JEMI bridge 経由でも JemiStack 化のため別 path 必要)。
 */
public final class IngredientSpecToEmi {

    private IngredientSpecToEmi() {}

    /**
     * IngredientSpec → EmiIngredient。 EMPTY / null / unsupported → {@link EmiStack#EMPTY}。
     *
     * <p>v1 対応:
     * <ul>
     *   <li>Item        → ItemEmiStack (count 反映)</li>
     *   <li>Tag (item)  → EmiIngredient.of(TagKey, amount)</li>
     *   <li>Fluid       → FluidEmiStack (amount mB 反映)</li>
     *   <li>FluidTag    → EmiIngredient.of(TagKey&lt;Fluid&gt;, amount)</li>
     *   <li>Gas (chemical) → 空 (= 未対応、 元 widget 維持)</li>
     *   <li>Configured  → 内側 unwrap で再帰</li>
     * </ul>
     */
    public static EmiIngredient toEmi(@Nullable IngredientSpec spec) {
        if (spec == null) return EmiStack.EMPTY;
        IngredientSpec unwrapped = spec.unwrap();
        if (unwrapped == null || unwrapped.isEmpty()) return EmiStack.EMPTY;

        if (unwrapped instanceof IngredientSpec.Item it) {
            ItemStack stack = it.stack();
            if (stack == null || stack.isEmpty()) return EmiStack.EMPTY;
            // count 反映: EmiStack.of(ItemStack, amount)
            return EmiStack.of(stack, Math.max(1, stack.getCount()));
        }

        if (unwrapped instanceof IngredientSpec.Tag tg) {
            ResourceLocation tagId = tg.tagId();
            if (tagId == null) return EmiStack.EMPTY;
            TagKey<Item> key = TagKey.create(net.minecraft.core.registries.Registries.ITEM, tagId);
            return EmiIngredient.of(key, Math.max(1, tg.count()));
        }

        if (unwrapped instanceof IngredientSpec.Fluid fl) {
            FluidStack fs = fl.stack();
            if (fs == null || fs.isEmpty()) return EmiStack.EMPTY;
            Fluid f = fs.getFluid();
            if (fs.hasTag()) {
                return EmiStack.of(f, fs.getTag(), fs.getAmount());
            }
            return EmiStack.of(f, fs.getAmount());
        }

        if (unwrapped instanceof IngredientSpec.FluidTag ft) {
            ResourceLocation tagId = ft.tagId();
            if (tagId == null) return EmiStack.EMPTY;
            TagKey<Fluid> key = TagKey.create(net.minecraft.core.registries.Registries.FLUID, tagId);
            return EmiIngredient.of(key, Math.max(1, ft.count()));
        }

        if (unwrapped instanceof IngredientSpec.Gas) {
            // v1: Mek chemical は pure-EMI native 表示不可。 substitution skip (= 元 widget 維持)。
            // 呼び出し側で null 判定 → 元 SlotWidget をそのまま描画する想定。
            return null;
        }

        Credit.LOGGER.debug("[CraftPattern] IngredientSpecToEmi: unsupported spec type {}", unwrapped.getClass().getSimpleName());
        return EmiStack.EMPTY;
    }

    /**
     * 呼出側用の判定 helper。 chemical 等 substitution 不可な spec → 元 widget 維持を意味する。
     */
    public static boolean canSubstitute(@Nullable IngredientSpec spec) {
        if (spec == null) return true; // null = 空 = 空で substitute OK
        IngredientSpec u = spec.unwrap();
        return !(u instanceof IngredientSpec.Gas);
    }

    /** debug / log 用の short label。 */
    public static String describe(@Nullable IngredientSpec spec) {
        if (spec == null) return "null";
        IngredientSpec u = spec.unwrap();
        if (u.isEmpty()) return "EMPTY";
        if (u instanceof IngredientSpec.Item it) {
            ResourceLocation id = BuiltInRegistries.ITEM.getKey(it.stack().getItem());
            return id + "x" + it.stack().getCount();
        }
        return u.getClass().getSimpleName();
    }
}
