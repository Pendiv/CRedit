package DIV.credit.client.draft.avaritia;

import DIV.credit.Credit;
import DIV.credit.client.draft.IngredientSpec;
import DIV.credit.client.draft.RecipeDraft;
import DIV.credit.client.draft.ae2.AE2EmitHelper;
import mezz.jei.api.gui.IRecipeLayoutDrawable;
import mezz.jei.api.gui.ingredient.IRecipeSlotView;
import mezz.jei.api.ingredients.ITypedIngredient;
import mezz.jei.api.recipe.IRecipeManager;
import mezz.jei.api.recipe.RecipeIngredientRole;
import mezz.jei.api.recipe.RecipeType;
import mezz.jei.api.recipe.category.IRecipeCategory;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * Re-Avaritia Extreme Smithing 用 draft (avaritia:extreme_smithing)。
 * <p>SLOT_COUNT = 6 (JEI ExtremeSmithingRecipeCategory と 1:1):
 * <ul>
 *   <li>0 = template (INPUT)</li>
 *   <li>1 = base (INPUT)</li>
 *   <li>2 = addition[0] (INPUT, 上)</li>
 *   <li>3 = addition[1] (INPUT, 右)</li>
 *   <li>4 = addition[2] (INPUT, 下)</li>
 *   <li>5 = output (OUTPUT)</li>
 * </ul>
 * <p>v2.1.2-fix-E: addition は 3 つの異なる items の配列で emit する必要がある
 * (Avaritia ExtremeSmithingRecipeCategory が {@code recipe.additions.getItems().get(0/1/2)} を読むため)。
 * setSlot で 3 slot を同期させない、emit で配列形式 {@code addition:[{...},{...},{...}]}。
 */
public final class ExtremeSmithingDraft implements RecipeDraft {

    public static final int IDX_TEMPLATE   = 0;
    public static final int IDX_BASE       = 1;
    public static final int IDX_ADDITION_0 = 2;
    public static final int IDX_ADDITION_1 = 3;
    public static final int IDX_ADDITION_2 = 4;
    public static final int IDX_OUTPUT     = 5;
    public static final int SLOT_COUNT     = 6;

    private final RecipeType<?> jeiType;
    private final IngredientSpec[] slots = new IngredientSpec[SLOT_COUNT];

    private ExtremeSmithingDraft(RecipeType<?> rt) {
        this.jeiType = rt;
        for (int i = 0; i < SLOT_COUNT; i++) slots[i] = IngredientSpec.EMPTY;
    }

    @Nullable
    public static <T> ExtremeSmithingDraft tryCreate(IRecipeCategory<T> cat, IRecipeManager rm) {
        return new ExtremeSmithingDraft(cat.getRecipeType());
    }

    @Override public int            slotCount()         { return SLOT_COUNT; }
    @Override public IngredientSpec getSlot(int i)      { return slots[i]; }
    @Override public RecipeType<?>  recipeType()        { return jeiType; }
    @Override public boolean        isOutputSlot(int i) { return i == IDX_OUTPUT; }
    @Override public IngredientSpec getOutput()         { return slots[IDX_OUTPUT]; }

    @Override
    public void setSlot(int i, IngredientSpec s) {
        if (i < 0 || i >= SLOT_COUNT) return;
        if (s == null) s = IngredientSpec.EMPTY;
        if (!acceptsAt(i, s)) return;
        // v2.1.2-fix-E: addition 3 slot は独立 (Avaritia の JEI category が 3 distinct items を要求)
        slots[i] = s;
    }

    @Override
    public int slotMaxCount(int slotIndex) {
        return slotIndex == IDX_OUTPUT ? Integer.MAX_VALUE : 1;
    }

    @Override
    public net.minecraft.world.item.crafting.Recipe<?> toRecipeInstance() {
        return null;
    }

    @Override
    public boolean loadFromRecipe(IRecipeLayoutDrawable<?> layout) {
        try {
            List<IRecipeSlotView> views = layout.getRecipeSlotsView().getSlotViews();
            int inputCount = 0;
            for (IRecipeSlotView v : views) {
                if (v.getRole() == RecipeIngredientRole.INPUT) {
                    IngredientSpec spec = readSpecFromView(v);
                    switch (inputCount) {
                        case 0 -> slots[IDX_TEMPLATE]   = spec;
                        case 1 -> slots[IDX_BASE]       = spec;
                        case 2 -> slots[IDX_ADDITION_0] = spec;
                        case 3 -> slots[IDX_ADDITION_1] = spec;
                        case 4 -> slots[IDX_ADDITION_2] = spec;
                        default -> { /* 想定外余剰 input: 無視 */ }
                    }
                    inputCount++;
                } else if (v.getRole() == RecipeIngredientRole.OUTPUT) {
                    IngredientSpec spec = readSpecFromView(v);
                    if (!spec.isEmpty()) slots[IDX_OUTPUT] = spec;
                }
            }
            return true;
        } catch (Exception e) {
            Credit.LOGGER.warn("[C4011] ExtremeSmithingDraft.loadFromRecipe failed: {}", e.toString());
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

    @Override
    public String emit(String recipeId) {
        IngredientSpec template = slots[IDX_TEMPLATE].unwrap();
        IngredientSpec base     = slots[IDX_BASE].unwrap();
        IngredientSpec add0     = slots[IDX_ADDITION_0].unwrap();
        IngredientSpec add1     = slots[IDX_ADDITION_1].unwrap();
        IngredientSpec add2     = slots[IDX_ADDITION_2].unwrap();
        IngredientSpec result   = slots[IDX_OUTPUT].unwrap();
        if (template.isEmpty()) {
            Credit.LOGGER.info("[CraftPattern] ExtremeSmithingDraft.emit({}) → null: template empty", recipeId);
            return null;
        }
        if (base.isEmpty()) {
            Credit.LOGGER.info("[CraftPattern] ExtremeSmithingDraft.emit({}) → null: base empty", recipeId);
            return null;
        }
        if (add0.isEmpty() || add1.isEmpty() || add2.isEmpty()) {
            Credit.LOGGER.info("[CraftPattern] ExtremeSmithingDraft.emit({}) → null: addition slot empty (need all 3)", recipeId);
            return null;
        }
        if (!(result instanceof IngredientSpec.Item resIt) || resIt.stack().isEmpty()) {
            Credit.LOGGER.info("[CraftPattern] ExtremeSmithingDraft.emit({}) → null: result empty/non-item", recipeId);
            return null;
        }

        ResourceLocation resRl = BuiltInRegistries.ITEM.getKey(resIt.stack().getItem());
        StringBuilder sb = new StringBuilder();
        sb.append("    event.custom({\n");
        sb.append("        type: 'avaritia:extreme_smithing',\n");
        sb.append("        template: ").append(AE2EmitHelper.ingredientJson(template)).append(",\n");
        sb.append("        base: ").append(AE2EmitHelper.ingredientJson(base)).append(",\n");
        // v2.1.2-fix-E: 3 distinct items の配列必須 (Avaritia JEI category 要件)
        sb.append("        addition: [\n");
        sb.append("            ").append(AE2EmitHelper.ingredientJson(add0)).append(",\n");
        sb.append("            ").append(AE2EmitHelper.ingredientJson(add1)).append(",\n");
        sb.append("            ").append(AE2EmitHelper.ingredientJson(add2)).append("\n");
        sb.append("        ],\n");
        sb.append("        result: { item: '").append(resRl).append("'");
        if (resIt.stack().getCount() > 1) sb.append(", count: ").append(resIt.stack().getCount());
        sb.append(" }\n");
        sb.append("    }).id('").append(recipeId).append("');\n");
        return sb.toString();
    }

    @Override
    public String relativeOutputPath() {
        return "generated/avaritia/extreme_smithing.js";
    }
}
