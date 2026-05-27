package DIV.credit.client.draft.ae2;

import DIV.credit.Credit;
import DIV.credit.client.draft.IngredientSpec;
import DIV.credit.client.draft.RecipeDraft;
import DIV.credit.jei.CraftPatternJeiPlugin;
import mezz.jei.api.gui.IRecipeLayoutDrawable;
import mezz.jei.api.gui.ingredient.IRecipeSlotView;
import mezz.jei.api.ingredients.ITypedIngredient;
import mezz.jei.api.recipe.IFocusGroup;
import mezz.jei.api.recipe.IRecipeManager;
import mezz.jei.api.recipe.RecipeIngredientRole;
import mezz.jei.api.recipe.RecipeType;
import mezz.jei.api.recipe.category.IRecipeCategory;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Optional;

/**
 * AE2 Transform 用 draft (ae2:transform / JEI category UID は ae2:item_transformation)。
 * <p>可変長 ingredients + 1 output。circumstance (EXPLOSION/FLUID) は canCycleTier API。
 * <p>v2.1: fluid tag は minecraft:water 固定 (編集 UI は v2.2+)。
 */
public final class TransformDraft implements RecipeDraft {

    public enum Circumstance {
        EXPLOSION("Explosion", 0xFFFF8800),
        FLUID    ("Fluid",     0xFF44AAFF);
        public final String displayName;
        public final int color;
        Circumstance(String n, int c) { displayName = n; color = c; }
        public Circumstance cycle()     { return values()[(ordinal() + 1) % values().length]; }
        public Circumstance cycleBack() { Circumstance[] v = values(); return v[(ordinal() - 1 + v.length) % v.length]; }
    }

    private final RecipeType<?> jeiType;
    private final IngredientSpec[] slots;
    private final int inputSlotCount;
    private final int catalystIndex;   // -1 if no catalyst slot
    private final int outputIndex;     // last OUTPUT slot
    private Circumstance circumstance = Circumstance.FLUID;
    private String fluidTag = "minecraft:water";

    private TransformDraft(RecipeType<?> rt, int slotCount, int inputCount, int catalystIdx, int outIdx) {
        this.jeiType = rt;
        this.slots = new IngredientSpec[Math.max(slotCount, 2)];
        this.inputSlotCount = Math.max(1, inputCount);
        this.catalystIndex = catalystIdx;
        this.outputIndex = outIdx >= 0 ? outIdx : slots.length - 1;
        for (int i = 0; i < slots.length; i++) slots[i] = IngredientSpec.EMPTY;
    }

    @Nullable
    public static <T> TransformDraft tryCreate(IRecipeCategory<T> cat, IRecipeManager rm) {
        try {
            int slotCount  = 4;     // ingredient(1) + catalyst(1) + output(1) min, padding 1
            int inputCount = 2;
            int catalystIdx = -1;
            int outIdx     = -1;
            Optional<T> sample = rm.createRecipeLookup(cat.getRecipeType()).includeHidden().get().findFirst();
            if (sample.isPresent() && CraftPatternJeiPlugin.runtime != null) {
                IFocusGroup empty = CraftPatternJeiPlugin.runtime.getJeiHelpers().getFocusFactory().getEmptyFocusGroup();
                IRecipeLayoutDrawable<?> drawable = rm.createRecipeLayoutDrawable(cat, sample.get(), empty).orElse(null);
                if (drawable != null) {
                    List<IRecipeSlotView> views = drawable.getRecipeSlotsView().getSlotViews();
                    slotCount = views.size();
                    int in = 0;
                    for (int i = 0; i < views.size(); i++) {
                        var role = views.get(i).getRole();
                        if (role == RecipeIngredientRole.INPUT) in++;
                        else if (role == RecipeIngredientRole.CATALYST && catalystIdx < 0) catalystIdx = i;
                        else if (role == RecipeIngredientRole.OUTPUT) outIdx = i;  // last OUTPUT
                    }
                    inputCount = Math.max(1, in);
                }
            }
            Credit.LOGGER.info("[CraftPattern] TransformDraft created (slots={}, input={}, catalyst={}, output={})",
                slotCount, inputCount, catalystIdx, outIdx);
            return new TransformDraft(cat.getRecipeType(), slotCount, inputCount, catalystIdx, outIdx);
        } catch (Exception e) {
            Credit.LOGGER.warn("[C4012] TransformDraft probe failed: {}", e.toString());
            return new TransformDraft(cat.getRecipeType(), 4, 2, 2, 3);
        }
    }

    @Override public int            slotCount()         { return slots.length; }
    @Override public IngredientSpec getSlot(int i)      { return slots[i]; }
    @Override public RecipeType<?>  recipeType()        { return jeiType; }
    @Override public boolean        isOutputSlot(int i) { return i == outputIndex; }
    @Override public IngredientSpec getOutput()         { return slots[outputIndex]; }

    /** catalyst slot は credit からは編集不可 (circumstance トグルで表現)。 */
    @Override
    public boolean acceptsAt(int slotIndex, IngredientSpec spec) {
        if (slotIndex == catalystIndex) return false;
        return RecipeDraft.super.acceptsAt(slotIndex, spec);
    }

    @Override
    public void setSlot(int i, IngredientSpec s) {
        if (i < 0 || i >= slots.length) return;
        if (s == null) s = IngredientSpec.EMPTY;
        if (!acceptsAt(i, s)) return;
        slots[i] = s;
    }

    @Override public int slotMaxCount(int slotIndex) {
        return isOutputSlot(slotIndex) ? Integer.MAX_VALUE : 1;
    }

    @Override public boolean canCycleTier()           { return true; }
    @Override public String  getTierLabel()           { return circumstance.displayName; }
    @Override public int     getTierColor()           { return circumstance.color; }
    @Override public void    cycleTier(boolean forward) {
        circumstance = forward ? circumstance.cycle() : circumstance.cycleBack();
    }

    @Override
    public net.minecraft.world.item.crafting.Recipe<?> toRecipeInstance() {
        return null;
    }

    @Override
    public boolean loadFromRecipe(IRecipeLayoutDrawable<?> layout) {
        try {
            List<IRecipeSlotView> views = layout.getRecipeSlotsView().getSlotViews();
            int n = Math.min(views.size(), slots.length);
            for (int i = 0; i < n; i++) {
                // catalyst slot は読み込まない (circumstance で表現するため)
                if (i == catalystIndex) continue;
                IngredientSpec spec = readSpecFromView(views.get(i));
                if (!spec.isEmpty()) slots[i] = spec;
            }
            Object recipe = layout.getRecipe();
            if (recipe != null) tryReadCircumstance(recipe);
            return true;
        } catch (Exception e) {
            Credit.LOGGER.warn("[C4013] TransformDraft.loadFromRecipe failed: {}", e.toString());
            return false;
        }
    }

    private static IngredientSpec readSpecFromView(IRecipeSlotView view) {
        var displayed = view.getDisplayedIngredient();
        ITypedIngredient<?> ti = displayed.orElse(null);
        if (ti == null) {
            ti = view.getAllIngredients()
                .filter(ITypedIngredient.class::isInstance)
                .map(o -> (ITypedIngredient<?>) o)
                .findFirst().orElse(null);
        }
        if (ti == null) return IngredientSpec.EMPTY;
        Object obj = ti.getIngredient();
        if (obj instanceof ItemStack stack && !stack.isEmpty()) {
            return new IngredientSpec.Item(stack.copy());
        }
        return IngredientSpec.EMPTY;
    }

    private void tryReadCircumstance(Object recipe) {
        try {
            var m = recipe.getClass().getMethod("getCircumstance");
            Object c = m.invoke(recipe);
            if (c == null) return;
            // TransformCircumstance に isFluid() / isExplosion() / getFluidTag() があると想定
            try {
                var isExp = c.getClass().getMethod("isExplosion");
                Object res = isExp.invoke(c);
                if (res instanceof Boolean b && b) {
                    circumstance = Circumstance.EXPLOSION;
                    return;
                }
            } catch (Exception ignored) {}
            // それ以外は fluid 扱い
            circumstance = Circumstance.FLUID;
            // tag 取得 (推測)
            try {
                var getTag = c.getClass().getMethod("getFluidTag");
                Object tagObj = getTag.invoke(c);
                if (tagObj != null) {
                    // TagKey から ResourceLocation 取得
                    try {
                        var loc = tagObj.getClass().getMethod("location");
                        Object rl = loc.invoke(tagObj);
                        if (rl != null) fluidTag = rl.toString();
                    } catch (Exception ignored) {}
                }
            } catch (Exception ignored) {}
        } catch (Exception ignored) {}
    }

    @Override
    public String emit(String recipeId) {
        IngredientSpec result = slots[outputIndex].unwrap();
        if (!(result instanceof IngredientSpec.Item resIt) || resIt.stack().isEmpty()) {
            Credit.LOGGER.info("[CraftPattern] TransformDraft.emit({}) → null: output empty/non-item (slot[{}])", recipeId, outputIndex);
            return null;
        }

        StringBuilder sb = new StringBuilder();
        sb.append("    event.custom({\n");
        sb.append("        type: 'ae2:transform',\n");
        sb.append("        ingredients: [\n");
        boolean any = false;
        for (int i = 0; i < inputSlotCount; i++) {
            IngredientSpec s = slots[i].unwrap();
            if (s.isEmpty()) continue;
            if (any) sb.append(",\n");
            sb.append("            ").append(AE2EmitHelper.ingredientJson(s));
            any = true;
        }
        if (!any) {
            Credit.LOGGER.info("[CraftPattern] TransformDraft.emit({}) → null: no input ingredients (inputSlotCount={})", recipeId, inputSlotCount);
            return null;
        }
        sb.append("\n        ],\n");
        sb.append("        circumstance: ");
        if (circumstance == Circumstance.EXPLOSION) {
            sb.append("{ type: 'explosion' },\n");
        } else {
            sb.append("{ type: 'fluid', tag: '").append(fluidTag).append("' },\n");
        }
        ResourceLocation resRl = BuiltInRegistries.ITEM.getKey(resIt.stack().getItem());
        sb.append("        result: { item: '").append(resRl).append("'");
        if (resIt.stack().getCount() > 1) sb.append(", count: ").append(resIt.stack().getCount());
        sb.append(" }\n");
        sb.append("    }).id('").append(recipeId).append("');\n");
        return sb.toString();
    }

    @Override
    public String relativeOutputPath() {
        return "generated/ae2/transform.js";
    }
}
