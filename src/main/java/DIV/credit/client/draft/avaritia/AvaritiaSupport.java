package DIV.credit.client.draft.avaritia;

import DIV.credit.client.draft.RecipeDraft;
import mezz.jei.api.recipe.RecipeType;
import mezz.jei.api.recipe.category.IRecipeCategory;
import org.jetbrains.annotations.Nullable;

/**
 * Re-Avaritia detection + draft factory。v2.1.0。
 * <p>カテゴリ UID で判定:
 * <ul>
 *   <li>avaritia:compressor                                  → CompressorDraft</li>
 *   <li>avaritia:sculk_craft / nether_craft / end_craft     → ShapedTableDraft / ShapelessTableDraft (tier 1-3)</li>
 *   <li>avaritia:extreme_smithing                            → ExtremeSmithingDraft</li>
 * </ul>
 * <p>avaritia:extreme_craft (9x9 tier 4) は EXPLICIT_UNSUPPORTED で対応外。
 * <p>compressor で result が avaritia:singularity のレシピは emit 不可 (CompressorDraft 内で skip)。
 */
public final class AvaritiaSupport {

    private AvaritiaSupport() {}

    /** Re-Avaritia 関連カテゴリ判定 (UID ベース)。 */
    public static boolean isAvaritiaCategory(@Nullable IRecipeCategory<?> cat) {
        if (cat == null) return false;
        RecipeType<?> rt = cat.getRecipeType();
        if (rt == null) return false;
        if (!"avaritia".equals(rt.getUid().getNamespace())) return false;
        String path = rt.getUid().getPath();
        return "compressor".equals(path)
            || "sculk_craft".equals(path)
            || "nether_craft".equals(path)
            || "end_craft".equals(path)
            || "extreme_smithing".equals(path);
    }

    /** Tier 推定: JEI category UID から (1=sculk, 2=nether, 3=end, 4=extreme)。 */
    public static int tierForCategoryUid(String path) {
        return switch (path) {
            case "sculk_craft" -> 1;
            case "nether_craft" -> 2;
            case "end_craft" -> 3;
            case "extreme_craft" -> 4;
            default -> 0;
        };
    }

    @Nullable
    public static RecipeDraft tryCreate(IRecipeCategory<?> cat) {
        if (!isAvaritiaCategory(cat)) return null;
        var rt = DIV.credit.jei.CraftPatternJeiPlugin.runtime;
        if (rt == null) return null;
        String path = cat.getRecipeType().getUid().getPath();
        return switch (path) {
            case "compressor"       -> CompressorDraft.tryCreate(cat, rt.getRecipeManager());
            case "sculk_craft", "nether_craft", "end_craft"
                                    -> AvaritiaCraftingDraft.tryCreate(cat, rt.getRecipeManager());
            case "extreme_smithing" -> ExtremeSmithingDraft.tryCreate(cat, rt.getRecipeManager());
            default -> null;
        };
    }

    /**
     * v2.1.2: 指定 tier + inputs + output で ShapedTableCraftingRecipe を reflection 構築。
     * <p>output が空だと JEI が drawable 作成を拒否する場合があるため、
     * 上位で placeholder (Items.BARRIER 等) を渡すこと。
     * <p>戻り値が null のとき draft は FALLBACK で sample に依存する (slot ズレ発生)。
     */
    @Nullable
    public static net.minecraft.world.item.crafting.Recipe<?> tryBuildShapedRecipe(
            int tier,
            net.minecraft.core.NonNullList<net.minecraft.world.item.crafting.Ingredient> inputs,
            net.minecraft.world.item.ItemStack output) {
        int gridSize = switch (tier) { case 1 -> 3; case 2 -> 5; case 3 -> 7; default -> 9; };
        var id = new net.minecraft.resources.ResourceLocation(
            "credit", "draft_avaritia_shaped_t" + tier);
        try {
            Class<?> recCls = Class.forName(
                "committee.nova.mods.avaritia.common.crafting.recipe.ShapedTableCraftingRecipe");
            for (var ctor : recCls.getConstructors()) {
                Class<?>[] params = ctor.getParameterTypes();
                try {
                    Object[] args = new Object[params.length];
                    for (int i = 0; i < params.length; i++) {
                        Class<?> p = params[i];
                        if (p == net.minecraft.resources.ResourceLocation.class) args[i] = id;
                        else if (p == int.class || p == Integer.class) {
                            int intCountSoFar = 0;
                            for (int k = 0; k < i; k++) if (params[k] == int.class || params[k] == Integer.class) intCountSoFar++;
                            args[i] = switch (intCountSoFar) {
                                case 0 -> gridSize;  // width
                                case 1 -> gridSize;  // height
                                default -> tier;
                            };
                        }
                        else if (java.util.List.class.isAssignableFrom(p) || net.minecraft.core.NonNullList.class.isAssignableFrom(p)) {
                            args[i] = inputs;
                        }
                        else if (p == net.minecraft.world.item.ItemStack.class) args[i] = output;
                        else if (p == boolean.class || p == Boolean.class) args[i] = false;
                        else args[i] = null;
                    }
                    return (net.minecraft.world.item.crafting.Recipe<?>) ctor.newInstance(args);
                } catch (Exception ctorEx) {
                    DIV.credit.Credit.LOGGER.warn("[C4002] tryBuildShapedRecipe ctor[{}] failed: {}",
                        params.length, ctorEx.toString());
                }
            }
            DIV.credit.Credit.LOGGER.warn("[C401] tryBuildShapedRecipe: no compatible constructor (tried {})",
                recCls.getConstructors().length);
            return null;
        } catch (Exception e) {
            DIV.credit.Credit.LOGGER.warn("[C4003] tryBuildShapedRecipe failed: {}", e.toString());
            return null;
        }
    }

    /**
     * v2.1.2: 指定 tier + inputs + output で ShapelessTableCraftingRecipe を reflection 構築。
     */
    @Nullable
    public static net.minecraft.world.item.crafting.Recipe<?> tryBuildShapelessRecipe(
            int tier,
            net.minecraft.core.NonNullList<net.minecraft.world.item.crafting.Ingredient> inputs,
            net.minecraft.world.item.ItemStack output) {
        var id = new net.minecraft.resources.ResourceLocation(
            "credit", "draft_avaritia_shapeless_t" + tier);
        try {
            Class<?> recCls = Class.forName(
                "committee.nova.mods.avaritia.common.crafting.recipe.ShapelessTableCraftingRecipe");
            for (var ctor : recCls.getConstructors()) {
                Class<?>[] params = ctor.getParameterTypes();
                try {
                    Object[] args = new Object[params.length];
                    for (int i = 0; i < params.length; i++) {
                        Class<?> p = params[i];
                        if (p == net.minecraft.resources.ResourceLocation.class) args[i] = id;
                        else if (p == int.class || p == Integer.class) args[i] = tier;
                        else if (java.util.List.class.isAssignableFrom(p) || net.minecraft.core.NonNullList.class.isAssignableFrom(p)) {
                            args[i] = inputs;
                        }
                        else if (p == net.minecraft.world.item.ItemStack.class) args[i] = output;
                        else args[i] = null;
                    }
                    return (net.minecraft.world.item.crafting.Recipe<?>) ctor.newInstance(args);
                } catch (Exception ctorEx) {
                    DIV.credit.Credit.LOGGER.warn("[C4004] tryBuildShapelessRecipe ctor[{}] failed: {}",
                        params.length, ctorEx.toString());
                }
            }
            DIV.credit.Credit.LOGGER.warn("[C402] tryBuildShapelessRecipe: no compatible constructor");
            return null;
        } catch (Exception e) {
            DIV.credit.Credit.LOGGER.warn("[C4005] tryBuildShapelessRecipe failed: {}", e.toString());
            return null;
        }
    }
}
