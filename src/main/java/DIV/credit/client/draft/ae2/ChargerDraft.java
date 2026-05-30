package DIV.credit.client.draft.ae2;

import DIV.credit.Credit;
import DIV.credit.client.draft.IngredientSpec;
import DIV.credit.client.draft.RecipeDraft;
import mezz.jei.api.gui.IRecipeLayoutDrawable;
import mezz.jei.api.gui.ingredient.IRecipeSlotView;
import mezz.jei.api.ingredients.ITypedIngredient;
import mezz.jei.api.recipe.IRecipeManager;
import mezz.jei.api.recipe.RecipeType;
import mezz.jei.api.recipe.category.IRecipeCategory;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * AE2 Charger 用 draft (ae2:charger)。1 input + 1 output。
 * <p>JEI には crank slot もあるが RENDER_ONLY なので無視。result は Item type (count なし)。
 */
public final class ChargerDraft implements RecipeDraft {

    public static final int IDX_INPUT  = 0;
    public static final int IDX_OUTPUT = 1;
    public static final int SLOT_COUNT = 2;

    private final RecipeType<?> jeiType;
    private final IngredientSpec[] slots = new IngredientSpec[SLOT_COUNT];

    private ChargerDraft(RecipeType<?> rt) {
        this.jeiType = rt;
        for (int i = 0; i < SLOT_COUNT; i++) slots[i] = IngredientSpec.EMPTY;
    }

    @Nullable
    public static <T> ChargerDraft tryCreate(IRecipeCategory<T> cat, IRecipeManager rm) {
        return new ChargerDraft(cat.getRecipeType());
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
        slots[i] = s;
    }

    @Override
    public int slotMaxCount(int slotIndex) {
        return slotIndex == IDX_OUTPUT ? Integer.MAX_VALUE : 1;
    }

    /**
     * v4.1.x: preview 用 ChargerRecipe を AE2Support reflection で構築。
     * input 空 / output 非 Item / output 空 → null (= preview fallback)。
     */
    @Override
    public net.minecraft.world.item.crafting.Recipe<?> toRecipeInstance() {
        IngredientSpec inSpec = slots[IDX_INPUT].unwrap();
        IngredientSpec outSpec = slots[IDX_OUTPUT].unwrap();
        net.minecraft.world.item.crafting.Ingredient ing = RecipeDraft.toIngredient(inSpec);
        if (ing == null || ing.isEmpty()) return null;
        if (!(outSpec instanceof IngredientSpec.Item outIt) || outIt.stack().isEmpty()) return null;
        net.minecraft.resources.ResourceLocation id =
            net.minecraft.resources.ResourceLocation.fromNamespaceAndPath(Credit.MODID, "draft/charger");
        return AE2Support.buildChargerRecipe(id, ing, outIt.stack().getItem());
    }

    @Override
    public boolean loadFromRecipe(IRecipeLayoutDrawable<?> layout) {
        try {
            List<IRecipeSlotView> views = layout.getRecipeSlotsView().getSlotViews();
            int loaded = 0;
            for (int i = 0; i < Math.min(views.size(), SLOT_COUNT); i++) {
                IngredientSpec spec = readSpecFromView(views.get(i));
                if (!spec.isEmpty()) {
                    slots[i] = spec;
                    loaded++;
                }
            }
            return loaded > 0;
        } catch (Exception e) {
            Credit.LOGGER.warn("[C4006] ChargerDraft.loadFromRecipe failed: {}", e.toString());
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
        IngredientSpec input  = slots[IDX_INPUT].unwrap();
        IngredientSpec result = slots[IDX_OUTPUT].unwrap();
        if (input.isEmpty()) {
            Credit.LOGGER.info("[CraftPattern] ChargerDraft.emit({}) → null: input empty", recipeId);
            return null;
        }
        if (!(result instanceof IngredientSpec.Item resIt) || resIt.stack().isEmpty()) {
            Credit.LOGGER.info("[CraftPattern] ChargerDraft.emit({}) → null: output empty/non-item", recipeId);
            return null;
        }

        StringBuilder sb = new StringBuilder();
        sb.append("    event.custom({\n");
        sb.append("        type: 'ae2:charger',\n");
        sb.append("        ingredient: ").append(AE2EmitHelper.ingredientJson(input)).append(",\n");
        // Charger result は item のみ (count なし)
        sb.append("        result: { item: '")
          .append(net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(resIt.stack().getItem()))
          .append("' }\n");
        sb.append("    }).id('").append(recipeId).append("');\n");
        return sb.toString();
    }

    @Override
    public String relativeOutputPath() {
        return "generated/ae2/charger.js";
    }
}
