package DIV.credit.client.draft.ae2;

import DIV.credit.Credit;
import DIV.credit.client.draft.RecipeDraft;
import DIV.credit.jei.CraftPatternJeiPlugin;
import mezz.jei.api.recipe.RecipeType;
import mezz.jei.api.recipe.category.IRecipeCategory;
import mezz.jei.api.runtime.IJeiRuntime;
import net.minecraft.core.NonNullList;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.level.material.Fluid;
import org.jetbrains.annotations.Nullable;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;

/**
 * AE2 detection + draft factory。v2.1.0。
 * <p>カテゴリ UID で判定:
 * <ul>
 *   <li>ae2:inscriber              → InscriberDraft</li>
 *   <li>ae2:charger                → ChargerDraft</li>
 *   <li>ae2:item_transformation    → TransformDraft (★ RecipeType UID は ae2:transform)</li>
 * </ul>
 * <p>v3.0.1: ae2:entropy は状態 transition (= 通常 ingredient 構造と異なる) のため IRREGULAR で
 * unsupported に。 EntropyDraft 削除済。 DraftStore.EXPLICIT_UNSUPPORTED で扱う。
 * <p>ae2:matter_cannon は JEI 非登録、credit 対象外。
 */
public final class AE2Support {

    private AE2Support() {}

    /** AE2 関連カテゴリ判定 (UID ベース)。 */
    public static boolean isAe2Category(@Nullable IRecipeCategory<?> cat) {
        if (cat == null) return false;
        RecipeType<?> rt = cat.getRecipeType();
        if (rt == null) return false;
        if (!"ae2".equals(rt.getUid().getNamespace())) return false;
        String path = rt.getUid().getPath();
        return "inscriber".equals(path)
            || "charger".equals(path)
            || "item_transformation".equals(path);
    }

    @Nullable
    public static RecipeDraft tryCreate(IRecipeCategory<?> cat) {
        if (!isAe2Category(cat)) return null;
        IJeiRuntime rt = CraftPatternJeiPlugin.runtime;
        if (rt == null) return null;
        String path = cat.getRecipeType().getUid().getPath();
        return switch (path) {
            case "inscriber"           -> InscriberDraft.tryCreate(cat, rt.getRecipeManager());
            case "charger"             -> ChargerDraft.tryCreate(cat, rt.getRecipeManager());
            case "item_transformation" -> TransformDraft.tryCreate(cat, rt.getRecipeManager());
            default -> null;
        };
    }

    // ───────────── v4.1.x: preview 用 AE2 recipe reflection builder ─────────────
    // AE2 は runtimeOnly 依存 → compile time に class 不在のため reflection 経由で construct。
    // failure 時は null 返却で preview 側 fallback。

    private static final Map<String, Constructor<?>> CTOR_CACHE = new HashMap<>();
    private static final Map<String, Boolean> LOOKUP_FAILED = new HashMap<>();

    /** appeng.recipes.handlers.ChargerRecipe(ResourceLocation, Ingredient, Item) を構築。 */
    @Nullable
    public static Recipe<?> buildChargerRecipe(ResourceLocation id, Ingredient ingredient, Item result) {
        Constructor<?> c = lookupCtor("appeng.recipes.handlers.ChargerRecipe",
            ResourceLocation.class, Ingredient.class, Item.class);
        if (c == null) return null;
        try { return (Recipe<?>) c.newInstance(id, ingredient, result); }
        catch (Throwable t) {
            Credit.LOGGER.debug("[CraftPattern] AE2Support.buildChargerRecipe failed: {}", t.toString());
            return null;
        }
    }

    /**
     * appeng.recipes.handlers.InscriberRecipe(ResourceLocation, Ingredient middle, ItemStack output,
     * Ingredient top, Ingredient bottom, InscriberProcessType processType) を構築。
     */
    @Nullable
    public static Recipe<?> buildInscriberRecipe(ResourceLocation id, Ingredient middle, ItemStack output,
                                                   Ingredient top, Ingredient bottom, String processTypeName) {
        Object processType = lookupEnumValue("appeng.recipes.handlers.InscriberProcessType", processTypeName);
        if (processType == null) return null;
        Class<?> processTypeClass = processType.getClass();
        Constructor<?> c = lookupCtor("appeng.recipes.handlers.InscriberRecipe",
            ResourceLocation.class, Ingredient.class, ItemStack.class, Ingredient.class, Ingredient.class, processTypeClass);
        if (c == null) return null;
        try { return (Recipe<?>) c.newInstance(id, middle, output, top, bottom, processType); }
        catch (Throwable t) {
            Credit.LOGGER.debug("[CraftPattern] AE2Support.buildInscriberRecipe failed: {}", t.toString());
            return null;
        }
    }

    /**
     * appeng.recipes.transform.TransformRecipe(ResourceLocation, NonNullList&lt;Ingredient&gt;,
     * ItemStack, TransformCircumstance) を構築。
     */
    @Nullable
    public static Recipe<?> buildTransformRecipe(ResourceLocation id, NonNullList<Ingredient> ingredients,
                                                  ItemStack output, boolean isExplosion,
                                                  @Nullable ResourceLocation fluidTagRl) {
        Object circumstance = buildTransformCircumstance(isExplosion, fluidTagRl);
        if (circumstance == null) return null;
        Class<?> circClass;
        try { circClass = Class.forName("appeng.recipes.transform.TransformCircumstance"); }
        catch (Throwable t) { return null; }
        Constructor<?> c = lookupCtor("appeng.recipes.transform.TransformRecipe",
            ResourceLocation.class, NonNullList.class, ItemStack.class, circClass);
        if (c == null) return null;
        try { return (Recipe<?>) c.newInstance(id, ingredients, output, circumstance); }
        catch (Throwable t) {
            Credit.LOGGER.debug("[CraftPattern] AE2Support.buildTransformRecipe failed: {}", t.toString());
            return null;
        }
    }

    @Nullable
    private static Object buildTransformCircumstance(boolean isExplosion, @Nullable ResourceLocation fluidTagRl) {
        try {
            Class<?> circClass = Class.forName("appeng.recipes.transform.TransformCircumstance");
            if (isExplosion) {
                Object expl = circClass.getField("EXPLOSION").get(null);
                if (expl != null) return expl;
                Method m = circClass.getMethod("explosion");
                return m.invoke(null);
            } else {
                if (fluidTagRl == null) fluidTagRl = ResourceLocation.fromNamespaceAndPath("minecraft", "water");
                TagKey<Fluid> tag = TagKey.create(Registries.FLUID, fluidTagRl);
                Method m = circClass.getMethod("fluid", TagKey.class);
                return m.invoke(null, tag);
            }
        } catch (Throwable t) {
            Credit.LOGGER.debug("[CraftPattern] AE2Support.buildTransformCircumstance failed: {}", t.toString());
            return null;
        }
    }

    @Nullable
    private static Constructor<?> lookupCtor(String className, Class<?>... paramTypes) {
        String key = className + sig(paramTypes);
        if (LOOKUP_FAILED.containsKey(key)) return null;
        Constructor<?> cached = CTOR_CACHE.get(key);
        if (cached != null) return cached;
        try {
            Class<?> cls = Class.forName(className);
            Constructor<?> c = cls.getDeclaredConstructor(paramTypes);
            c.setAccessible(true);
            CTOR_CACHE.put(key, c);
            return c;
        } catch (Throwable t) {
            LOOKUP_FAILED.put(key, Boolean.TRUE);
            Credit.LOGGER.debug("[CraftPattern] AE2Support.lookupCtor {} failed: {}", className, t.toString());
            return null;
        }
    }

    @Nullable
    private static Object lookupEnumValue(String enumClassName, String valueName) {
        try {
            Class<?> cls = Class.forName(enumClassName);
            for (Object v : cls.getEnumConstants()) {
                if (valueName.equalsIgnoreCase(((Enum<?>) v).name())) return v;
            }
        } catch (Throwable ignored) {}
        return null;
    }

    private static String sig(Class<?>... ps) {
        StringBuilder sb = new StringBuilder("(");
        for (Class<?> p : ps) sb.append(p.getName()).append(';');
        return sb.append(')').toString();
    }
}
