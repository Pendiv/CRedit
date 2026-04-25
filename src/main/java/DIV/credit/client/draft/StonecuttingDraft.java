package DIV.credit.client.draft;

import DIV.credit.Credit;
import mezz.jei.api.constants.RecipeTypes;
import mezz.jei.api.recipe.RecipeType;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.StonecutterRecipe;

/**
 * minecraft:stonecutting カテゴリ用 Draft。
 * JEI スロット並び：index 0 = INPUT、index 1 = OUTPUT（StoneCuttingRecipeCategory.setRecipe より）。
 */
public class StonecuttingDraft implements RecipeDraft {

    public static final int SLOT_COUNT = 2;
    public static final int IDX_INPUT  = 0;
    public static final int IDX_OUTPUT = 1;

    private final IngredientSpec[] slots = new IngredientSpec[SLOT_COUNT];

    public StonecuttingDraft() {
        for (int i = 0; i < SLOT_COUNT; i++) slots[i] = IngredientSpec.EMPTY;
    }

    @Override public int slotCount() { return SLOT_COUNT; }
    @Override public IngredientSpec getSlot(int i) { return slots[i]; }
    @Override public boolean isOutputSlot(int i) { return i == IDX_OUTPUT; }
    @Override public IngredientSpec getOutput() { return slots[IDX_OUTPUT]; }
    @Override public RecipeType<?> recipeType() { return RecipeTypes.STONECUTTING; }

    @Override
    public void setSlot(int i, IngredientSpec s) {
        if (i < 0 || i >= SLOT_COUNT) return;
        if (s == null) s = IngredientSpec.EMPTY;
        if (isOutputSlot(i) && s instanceof IngredientSpec.Tag) return;
        slots[i] = s;
    }

    @Override
    public Recipe<?> toRecipeInstance() {
        Ingredient ing = RecipeDraft.toIngredient(slots[IDX_INPUT]);
        return new StonecutterRecipe(
            new ResourceLocation(Credit.MODID, "draft/stonecutting"),
            "", ing, RecipeDraft.toOutputStack(slots[IDX_OUTPUT]));
    }

    @Override
    public String relativeOutputPath() {
        return "generated/stonecutting.js";
    }

    @Override
    public String emit(String recipeId) {
        String inJs  = RecipeDraft.formatIngredientString(slots[IDX_INPUT]);
        String outJs = RecipeDraft.formatIngredientWithCount(slots[IDX_OUTPUT]);
        if (inJs == null || outJs == null) return null;
        return "    event.stonecutting(\n"
            + "        " + outJs + ",\n"
            + "        " + inJs + "\n"
            + "    ).id('" + recipeId + "');\n";
    }
}