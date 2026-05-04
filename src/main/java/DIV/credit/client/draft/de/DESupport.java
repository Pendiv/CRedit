package DIV.credit.client.draft.de;

import DIV.credit.Credit;
import DIV.credit.client.draft.RecipeDraft;
import DIV.credit.jei.CraftPatternJeiPlugin;
import mezz.jei.api.recipe.RecipeType;
import mezz.jei.api.recipe.category.IRecipeCategory;
import mezz.jei.api.runtime.IJeiRuntime;
import org.jetbrains.annotations.Nullable;

/**
 * Draconic Evolution detection + draft factory。v2.0.0。
 * <p>DE には共通 base class が無いので、namespace + IFusionRecipe interface 片方のチェック。
 */
public final class DESupport {

    private DESupport() {}

    private static Class<?> FUSION_INTERFACE;
    private static boolean lookupFailed = false;

    /** category が DE fusion_crafting か判定。 */
    public static boolean isDeCategory(IRecipeCategory<?> cat) {
        if (cat == null) return false;
        RecipeType<?> rt = cat.getRecipeType();
        if (rt == null) return false;
        return "draconicevolution".equals(rt.getUid().getNamespace())
            && "fusion_crafting".equals(rt.getUid().getPath());
    }

    /** Recipe instance が DE IFusionRecipe か判定（後方互換 reflection）。 */
    public static boolean isFusionRecipe(@Nullable Object recipe) {
        if (recipe == null) return false;
        if (FUSION_INTERFACE == null && !lookupFailed) {
            try {
                FUSION_INTERFACE = Class.forName(
                    "com.brandon3055.draconicevolution.api.crafting.IFusionRecipe");
            } catch (ClassNotFoundException e) {
                lookupFailed = true;
                return false;
            }
        }
        return FUSION_INTERFACE != null && FUSION_INTERFACE.isInstance(recipe);
    }

    /** DraftStore.create() から呼ぶ。 */
    @Nullable
    public static RecipeDraft tryCreate(IRecipeCategory<?> cat) {
        if (!isDeCategory(cat)) return null;
        IJeiRuntime rt = CraftPatternJeiPlugin.runtime;
        if (rt == null) return null;
        return FusionCraftingDraft.tryCreate(cat, rt.getRecipeManager());
    }

    /**
     * v2.0.13: 指定 tier で空の FusionRecipe を reflection 構築。
     * DE が JEI で tier 表示 (Wyvern等) を自前描画するための仕掛け。
     * 構築失敗時 null → credit は FALLBACK で sample recipe 使うので tier 表示は元のまま。
     */
    @Nullable
    public static net.minecraft.world.item.crafting.Recipe<?> tryBuildEmptyFusionRecipe(
            String cleanroomNotUsed, String tierName, long energy) {
        try {
            // TechLevel enum 値取得
            Class<?> techLevelCls = Class.forName(
                "com.brandon3055.draconicevolution.api.TechLevel");
            Object tierEnum = null;
            for (Object e : techLevelCls.getEnumConstants()) {
                if (((Enum<?>) e).name().equalsIgnoreCase(tierName)) {
                    tierEnum = e; break;
                }
            }
            if (tierEnum == null) {
                tierEnum = techLevelCls.getEnumConstants()[0];  // fallback
            }
            // FusionRecipe constructor
            Class<?> fusionRecipeCls = Class.forName(
                "com.brandon3055.draconicevolution.api.crafting.FusionRecipe");
            // Constructor: (ResourceLocation id, ItemStack result, Ingredient catalyst,
            //               long energy, TechLevel tier, Collection<FusionIngredient> ingredients)
            // 引数順は class により異なるかも、reflection で signature 確認
            var ctors = fusionRecipeCls.getConstructors();
            for (var c : ctors) {
                Class<?>[] params = c.getParameterTypes();
                if (params.length < 5) continue;  // too short
                try {
                    Object[] args = new Object[params.length];
                    for (int i = 0; i < params.length; i++) {
                        Class<?> p = params[i];
                        if (p == net.minecraft.resources.ResourceLocation.class) {
                            args[i] = new net.minecraft.resources.ResourceLocation(
                                "credit", "draft_fusion_" + System.currentTimeMillis() % 100000);
                        } else if (p == net.minecraft.world.item.ItemStack.class) {
                            args[i] = net.minecraft.world.item.ItemStack.EMPTY;
                        } else if (p == net.minecraft.world.item.crafting.Ingredient.class) {
                            args[i] = net.minecraft.world.item.crafting.Ingredient.EMPTY;
                        } else if (p == long.class || p == Long.class) {
                            args[i] = energy;
                        } else if (p == techLevelCls) {
                            args[i] = tierEnum;
                        } else if (java.util.Collection.class.isAssignableFrom(p)) {
                            args[i] = new java.util.ArrayList<>();
                        } else {
                            args[i] = null;  // 未知 type
                        }
                    }
                    return (net.minecraft.world.item.crafting.Recipe<?>) c.newInstance(args);
                } catch (Exception ignored) {
                    // try next ctor
                }
            }
            return null;
        } catch (Exception e) {
            DIV.credit.Credit.LOGGER.debug("[CraftPattern] tryBuildEmptyFusionRecipe failed: {}", e.toString());
            return null;
        }
    }
}
