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
import net.neoforged.neoforge.fluids.FluidStack;
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
            // 1.21: FluidStack の NBT tag は DataComponents 化。 component 付き fluid は稀なので amount のみで表現。
            return EmiStack.of(f, fs.getAmount());
        }

        if (unwrapped instanceof IngredientSpec.FluidTag ft) {
            ResourceLocation tagId = ft.tagId();
            if (tagId == null) return EmiStack.EMPTY;
            TagKey<Fluid> key = TagKey.create(net.minecraft.core.registries.Registries.FLUID, tagId);
            return EmiIngredient.of(key, Math.max(1, ft.count()));
        }

        if (unwrapped instanceof IngredientSpec.Gas g) {
            // v4.1.x: Mek chemical を JemiStack 化 (= JEI Mek plugin の IIngredientType + helper + renderer 経由)。
            // JEI runtime + Mek 在時のみ機能、 失敗時 null → 呼出側で元 widget 維持。
            EmiStack jemi = tryBuildJemiChemicalStack(g);
            if (jemi != null) return jemi;
            return null;
        }

        Credit.LOGGER.debug("[CraftPattern] IngredientSpecToEmi: unsupported spec type {}", unwrapped.getClass().getSimpleName());
        return EmiStack.EMPTY;
    }

    /**
     * Mek chemical → JemiStack 化。 失敗時 null。
     * <ol>
     *   <li>Mek + JEI runtime 利用可能 check</li>
     *   <li>chemicalType に応じた IIngredientType (= MekanismJEI.TYPE_GAS 等) 取得</li>
     *   <li>{@link DIV.credit.client.jei.mek.MekanismIngredientAdapter} で IngredientSpec.Gas → ChemicalStack 変換</li>
     *   <li>JEI mgr から IIngredientHelper / IIngredientRenderer 取得</li>
     *   <li>{@code new JemiStack(type, helper, renderer, stack).setAmount(amount)} で構築</li>
     * </ol>
     */
    @Nullable
    private static EmiStack tryBuildJemiChemicalStack(IngredientSpec.Gas g) {
        if (g == null || g.gasId() == null) return null;
        try {
            if (!net.neoforged.fml.ModList.get().isLoaded("mekanism")) return null;
            if (!net.neoforged.fml.ModList.get().isLoaded("jei")) return null;
            var jeiRt = DIV.credit.jei.CraftPatternJeiPlugin.runtime;
            if (jeiRt == null) return null;

            // 1. 1.21: Mek chemical は単一 ChemicalStack に統合 → TYPE_CHEMICAL (chemicalType 別分岐は廃止)
            mezz.jei.api.ingredients.IIngredientType<?> type =
                DIV.credit.client.jei.mek.MekanismIngredientAdapter.chemicalType();
            mekanism.api.chemical.ChemicalStack stack =
                DIV.credit.client.jei.mek.MekanismIngredientAdapter.toChemicalStack(g);
            if (type == null || stack.isEmpty()) return null;

            // 2. JEI mgr から helper / renderer 取得
            var ingMgr = jeiRt.getIngredientManager();
            @SuppressWarnings({"unchecked","rawtypes"})
            var helper   = ingMgr.getIngredientHelper((mezz.jei.api.ingredients.IIngredientType) type);
            @SuppressWarnings({"unchecked","rawtypes"})
            var renderer = ingMgr.getIngredientRenderer((mezz.jei.api.ingredients.IIngredientType) type);
            if (helper == null || renderer == null) return null;

            // 3. JemiStack 構築 (= reflection で ctor 解決、 EMI version 差吸収)
            return constructJemiStack(type, helper, renderer, stack, g.amount());
        } catch (Throwable t) {
            Credit.LOGGER.debug("[CraftPattern] tryBuildJemiChemicalStack failed: {}", t.toString());
            return null;
        }
    }

    /** dev.emi.emi.jemi.JemiStack(IIngredientType, IIngredientHelper, IIngredientRenderer, Object) 構築 + amount。 */
    @Nullable
    private static EmiStack constructJemiStack(Object type, Object helper, Object renderer, Object ingredient, long amount) {
        try {
            Class<?> jemiStackCls = Class.forName("dev.emi.emi.jemi.JemiStack");
            // ctor 探索: 4-arg (type, helper, renderer, ingredient) または 5-arg (amount 含む)
            for (var ctor : jemiStackCls.getDeclaredConstructors()) {
                Class<?>[] ps = ctor.getParameterTypes();
                if (ps.length == 4) {
                    try {
                        ctor.setAccessible(true);
                        EmiStack js = (EmiStack) ctor.newInstance(type, helper, renderer, ingredient);
                        if (amount > 0) js.setAmount(amount);
                        return js;
                    } catch (Throwable ignored) {}
                }
            }
            return null;
        } catch (Throwable t) {
            Credit.LOGGER.debug("[CraftPattern] constructJemiStack failed: {}", t.toString());
            return null;
        }
    }

    /**
     * 呼出側用の判定 helper。 v4.1.x: Mek chemical も substitution 可能になったため常に true。
     * (= 実 substitution が失敗した場合は toEmi が null 返却で呼出側が skip)。
     */
    public static boolean canSubstitute(@Nullable IngredientSpec spec) {
        return true;
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
