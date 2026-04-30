package DIV.credit.client.jei;

import DIV.credit.Credit;
import DIV.credit.jei.CraftPatternJeiPlugin;
import mezz.jei.api.constants.VanillaTypes;
import mezz.jei.api.recipe.IFocus;
import mezz.jei.api.recipe.RecipeIngredientRole;
import mezz.jei.api.recipe.RecipeType;
import mezz.jei.api.recipe.category.IRecipeCategory;
import mezz.jei.api.runtime.IJeiRuntime;
import mezz.jei.api.runtime.IRecipesGui;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Recipe;

import java.util.List;
import java.util.Optional;

/**
 * BuilderScreen → JEI 遷移ヘルパ。OriginTracker を立てて JEI を開く。
 */
public final class JeiNavigation {

    private JeiNavigation() {}

    /**
     * 指定カテゴリの JEI ビューを開く。OriginTracker = active になる。
     * @return true なら open 成功、false なら JEI 未準備等で失敗
     */
    public static boolean openCategory(IRecipeCategory<?> category) {
        if (category == null) return false;
        IJeiRuntime rt = CraftPatternJeiPlugin.runtime;
        if (rt == null) {
            Credit.LOGGER.warn("[CraftPattern] JEI not ready, cannot open category");
            return false;
        }
        RecipeType<?> type = category.getRecipeType();
        OriginTracker.enter(type);
        try {
            IRecipesGui gui = rt.getRecipesGui();
            gui.showTypes(List.of(type));
            Credit.LOGGER.info("[CraftPattern] Opened JEI for {} (origin=CRpattern)", type.getUid());
            return true;
        } catch (Exception e1) {
            Credit.LOGGER.error("[CraftPattern] showTypes failed for {}", type.getUid(), e1);
            OriginTracker.exit();
            return false;
        }
    }

    /**
     * 指定 recipe ID のレシピを JEI で開く。HistoryScreen から飛ぶ用。
     * <p>戦略 (精度順):
     * <ol>
     *   <li>Recipe<?> を level の RecipeManager から解決</li>
     *   <li>JEI categories を逆引きして該当 category 特定</li>
     *   <li>{@code showRecipes(cat, [recipe], [outputFocus])} で specific recipe 表示</li>
     *   <li>category 逆引き失敗 → output focus で全 category 表示 (fallback)</li>
     *   <li>recipe 解決失敗 → false (chat 警告は呼出側で表示)</li>
     * </ol>
     */
    public static boolean openRecipeId(ResourceLocation recipeId) {
        return openRecipeId(recipeId, null);
    }

    /**
     * jeiCategoryUid を渡せる版。GT 等の場合 lookup 失敗時に
     * {@code <recipeId.ns>:<categoryPath>/<recipeId.path>} 形式で再試行する。
     */
    public static boolean openRecipeId(ResourceLocation recipeId,
                                       @org.jetbrains.annotations.Nullable String jeiCategoryUid) {
        if (recipeId == null) return false;
        IJeiRuntime rt = CraftPatternJeiPlugin.runtime;
        if (rt == null) {
            Credit.LOGGER.warn("[CraftPattern] JEI not ready, cannot open recipe");
            return false;
        }
        Minecraft mc = Minecraft.getInstance();
        Recipe<?> recipe = null;
        ResourceLocation foundId = null;
        if (mc.level != null) {
            // 1. 元 ID で lookup
            Optional<? extends Recipe<?>> opt = mc.level.getRecipeManager().byKey(recipeId);
            if (opt.isPresent()) { recipe = opt.get(); foundId = recipeId; }
            // 2. 失敗 + jeiCategoryUid あり → GT 流の prefix 変形を試す
            if (recipe == null && jeiCategoryUid != null) {
                ResourceLocation variant = buildPrefixVariant(recipeId, jeiCategoryUid);
                if (variant != null) {
                    Optional<? extends Recipe<?>> opt2 = mc.level.getRecipeManager().byKey(variant);
                    if (opt2.isPresent()) {
                        recipe = opt2.get();
                        foundId = variant;
                        Credit.LOGGER.info("[CraftPattern] Recipe found via prefix variant: {} → {}", recipeId, variant);
                    }
                }
            }
        }
        if (recipe == null) {
            Credit.LOGGER.info("[CraftPattern] Recipe not found in RecipeManager: {} (cat={}, try /reload)",
                recipeId, jeiCategoryUid);
            return false;
        }
        // 以降の表示で variant ID を使う (showSpecificRecipe の filter で getId() 比較するため)
        final ResourceLocation effectiveId = foundId;
        recipeId = effectiveId;
        ItemStack output = recipe.getResultItem(mc.level.registryAccess());
        // diagnostic — recipe class + output 判明
        Credit.LOGGER.info("[CraftPattern] openRecipeId {} → recipe class={}, output={}, vanilla type={}",
            recipeId,
            recipe.getClass().getName(),
            (output == null || output.isEmpty()) ? "EMPTY" : output.getItem().builtInRegistryHolder().key().location(),
            recipe.getType());
        IFocus<ItemStack> focus = (output != null && !output.isEmpty())
            ? rt.getJeiHelpers().getFocusFactory()
                .createFocus(RecipeIngredientRole.OUTPUT, VanillaTypes.ITEM_STACK, output)
            : null;

        // 1. category 逆引き → showRecipes で specific 表示 (precise)
        OriginTracker.enter(null);
        try {
            if (showSpecificRecipe(rt, recipe, focus, recipeId)) return true;
        } catch (Exception e) {
            Credit.LOGGER.error("[CraftPattern] showSpecificRecipe failed for {}", recipeId, e);
        }
        // 2. fallback: output focus
        if (focus != null) {
            try {
                rt.getRecipesGui().show(focus);
                Credit.LOGGER.info("[CraftPattern] Opened JEI for {} (output focus FALLBACK)", recipeId);
                return true;
            } catch (Exception e) {
                Credit.LOGGER.error("[CraftPattern] output focus fallback failed for {}", recipeId, e);
            }
        }
        OriginTracker.exit();
        return false;
    }

    /**
     * GT/KubeJS が登録時に挿入する type prefix を再現:
     * {@code credit:generated/recipe_X} + cat {@code gtceu:primitive_blast_furnace}
     * → {@code credit:primitive_blast_furnace/generated/recipe_X}
     * <p>カテゴリ UID の path 部分を recipeId.namespace と path の間に挿入。
     */
    @org.jetbrains.annotations.Nullable
    private static ResourceLocation buildPrefixVariant(ResourceLocation recipeId, String jeiCategoryUid) {
        try {
            ResourceLocation catRl = new ResourceLocation(jeiCategoryUid);
            String catPath = catRl.getPath();
            if (catPath == null || catPath.isEmpty()) return null;
            // 二重 prefix 防止: 既に <catPath>/ で始まってたら skip
            if (recipeId.getPath().startsWith(catPath + "/")) return null;
            return new ResourceLocation(recipeId.getNamespace(), catPath + "/" + recipeId.getPath());
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * JEI category を逆引きして specific recipe 1 件にフォーカスして表示。
     * 各 category の recipe stream に該当 recipe が含まれてるか 3 通りで scan する:
     * <ol>
     *   <li>instance equality (==) — JEI と MC が同 instance を共有してる場合最速</li>
     *   <li>cat.getRegistryName(r) == recipeId — Mek/Create/IE 等で動く</li>
     *   <li>r.getId() == recipeId — recipe 自身が持つ ID を見る (GT で getRegistryName が null/別物の時)</li>
     * </ol>
     */
    private static <R> boolean showSpecificRecipe(IJeiRuntime rt, Recipe<?> recipe,
                                                  @org.jetbrains.annotations.Nullable IFocus<ItemStack> focus,
                                                  ResourceLocation recipeId) {
        var categories = rt.getRecipeManager().createRecipeCategoryLookup().get().toList();
        int catsScanned = 0;
        for (IRecipeCategory<?> cat : categories) {
            catsScanned++;
            if (tryShowInCategory(rt, cat, recipe, focus, recipeId)) return true;
        }
        Credit.LOGGER.info("[CraftPattern] Recipe {} not found in any JEI category (scanned {})",
            recipeId, catsScanned);
        return false;
    }

    @SuppressWarnings("unchecked")
    private static <R> boolean tryShowInCategory(IJeiRuntime rt, IRecipeCategory<R> cat,
                                                  Recipe<?> recipe, @org.jetbrains.annotations.Nullable IFocus<ItemStack> focus,
                                                  ResourceLocation recipeId) {
        try {
            RecipeType<R> type = cat.getRecipeType();
            // 推奨: type.getRecipeClass() が recipe と互換でなければ skip。GT で false が返るケースもありうるので、
            // 互換性チェックは「ヒント」として使い、失敗してもフルスキャンへ降ろす。
            boolean classCompatible = type.getRecipeClass().isInstance(recipe);

            // diagnostic: GT category だけ詳細 log (gtceu namespace + 派生)
            String catNs = type.getUid().getNamespace();
            boolean isGtCat = "gtceu".equals(catNs) || "gtcsolo".equals(catNs);

            var allInCat = rt.getRecipeManager().createRecipeLookup(type).get().toList();
            var matches = allInCat.stream()
                .filter(r -> {
                    if (r == recipe) return true;  // (1) instance equality
                    ResourceLocation rn = cat.getRegistryName(r);
                    if (rn != null && rn.equals(recipeId)) return true;  // (2) registry name
                    if (r instanceof Recipe<?> rr && recipeId.equals(rr.getId())) return true;  // (3) recipe.getId()
                    return false;
                })
                .toList();
            if (matches.isEmpty()) {
                if (classCompatible || isGtCat) {
                    // INFO log で何が中に居るか可視化 (上位 3 件 + 該当 ID 風と完全マッチ無いか)
                    Credit.LOGGER.info("[CraftPattern] NO MATCH cat={} (class-compat={}, count={}) for {}",
                        type.getUid(), classCompatible, allInCat.size(), recipeId);
                    int sample = 0;
                    for (R r : allInCat) {
                        if (sample >= 3) break;
                        ResourceLocation rn = cat.getRegistryName(r);
                        String rid = (r instanceof Recipe<?> rr) ? String.valueOf(rr.getId()) : "(not Recipe)";
                        Credit.LOGGER.info("    [{}] cls={} regName={} recipeId={}",
                            sample++, r.getClass().getSimpleName(), rn, rid);
                    }
                }
                return false;
            }
            List<IFocus<?>> focuses = (focus != null) ? List.of(focus) : List.of();
            rt.getRecipesGui().showRecipes(cat, matches, focuses);
            Credit.LOGGER.info("[CraftPattern] Opened JEI for {} in category {} (precise, classCompat={})",
                recipeId, type.getUid(), classCompatible);
            return true;
        } catch (Exception e) {
            Credit.LOGGER.debug("[CraftPattern] tryShowInCategory failed for {} {}: {}",
                cat.getRecipeType().getUid(), recipeId, e.toString());
            return false;
        }
    }
}
