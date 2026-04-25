package DIV.credit.client.draft;

import DIV.credit.Credit;
import mezz.jei.api.constants.RecipeTypes;
import mezz.jei.api.recipe.RecipeType;
import net.minecraft.core.NonNullList;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.crafting.CraftingBookCategory;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.ShapedRecipe;
import net.minecraft.world.item.crafting.ShapelessRecipe;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * minecraft:crafting カテゴリ用 Draft。SHAPED と SHAPELESS の両モードを持つ。
 * JEI スロット並び：index 0 = OUTPUT、index 1..9 = INPUT (row-major)。
 */
public class CraftingDraft implements RecipeDraft {

    public enum Mode { SHAPED, SHAPELESS }

    public static final int WIDTH       = 3;
    public static final int HEIGHT      = 3;
    public static final int INPUT_COUNT = WIDTH * HEIGHT;
    public static final int SLOT_COUNT  = 1 + INPUT_COUNT;
    public static final int IDX_OUTPUT  = 0;
    public static final int IDX_INPUT_0 = 1;

    private final IngredientSpec[] slots = new IngredientSpec[SLOT_COUNT];
    private Mode mode = Mode.SHAPED;

    public CraftingDraft() {
        for (int i = 0; i < SLOT_COUNT; i++) slots[i] = IngredientSpec.EMPTY;
    }

    public Mode getMode() { return mode; }
    public void setMode(Mode m) { this.mode = m; }

    @Override public int slotCount() { return SLOT_COUNT; }
    @Override public IngredientSpec getSlot(int i) { return slots[i]; }
    @Override public boolean isOutputSlot(int i) { return i == IDX_OUTPUT; }
    @Override public IngredientSpec getOutput() { return slots[IDX_OUTPUT]; }
    @Override public RecipeType<?> recipeType() { return RecipeTypes.CRAFTING; }

    @Override
    public void setSlot(int i, IngredientSpec s) {
        if (i < 0 || i >= SLOT_COUNT) return;
        if (s == null) s = IngredientSpec.EMPTY;
        if (isOutputSlot(i) && s instanceof IngredientSpec.Tag) return; // tag forbidden in output
        slots[i] = s;
    }

    @Override
    public Recipe<?> toRecipeInstance() {
        return mode == Mode.SHAPED ? buildShaped() : buildShapeless();
    }

    @Override
    public String relativeOutputPath() {
        return mode == Mode.SHAPED
            ? "generated/crafting_shaped.js"
            : "generated/crafting_shapeless.js";
    }

    private ShapedRecipe buildShaped() {
        NonNullList<Ingredient> ings = NonNullList.withSize(INPUT_COUNT, Ingredient.EMPTY);
        for (int i = 0; i < INPUT_COUNT; i++) {
            ings.set(i, RecipeDraft.toIngredient(slots[IDX_INPUT_0 + i]));
        }
        return new ShapedRecipe(
            new ResourceLocation(Credit.MODID, "draft/shaped"),
            "", CraftingBookCategory.MISC,
            WIDTH, HEIGHT, ings, RecipeDraft.toOutputStack(slots[IDX_OUTPUT]));
    }

    private ShapelessRecipe buildShapeless() {
        NonNullList<Ingredient> ings = NonNullList.create();
        for (int i = 0; i < INPUT_COUNT; i++) {
            IngredientSpec spec = slots[IDX_INPUT_0 + i];
            if (spec.isEmpty()) continue;
            Ingredient ing = RecipeDraft.toIngredient(spec);
            int count = Math.max(1, spec.count());
            for (int k = 0; k < count; k++) ings.add(ing);
        }
        return new ShapelessRecipe(
            new ResourceLocation(Credit.MODID, "draft/shapeless"),
            "", CraftingBookCategory.MISC,
            RecipeDraft.toOutputStack(slots[IDX_OUTPUT]), ings);
    }

    @Override
    public String emit(String recipeId) {
        String outJs = RecipeDraft.formatIngredientWithCount(slots[IDX_OUTPUT]);
        if (outJs == null) return null;
        return mode == Mode.SHAPED ? emitShaped(recipeId, outJs) : emitShapeless(recipeId, outJs);
    }

    private String emitShaped(String recipeId, String outJs) {
        int minR = HEIGHT, maxR = -1, minC = WIDTH, maxC = -1;
        for (int r = 0; r < HEIGHT; r++) for (int c = 0; c < WIDTH; c++) {
            if (!slots[IDX_INPUT_0 + r * WIDTH + c].isEmpty()) {
                if (r < minR) minR = r;
                if (r > maxR) maxR = r;
                if (c < minC) minC = c;
                if (c > maxC) maxC = c;
            }
        }
        if (maxR < 0) return null;

        Map<String, Character> charByKey = new LinkedHashMap<>();
        char nextChar = 'A';
        for (int r = minR; r <= maxR; r++) for (int c = minC; c <= maxC; c++) {
            String key = ingredientKey(slots[IDX_INPUT_0 + r * WIDTH + c]);
            if (key != null && !charByKey.containsKey(key)) {
                charByKey.put(key, nextChar++);
            }
        }

        StringBuilder pattern = new StringBuilder("[");
        for (int r = minR; r <= maxR; r++) {
            if (r > minR) pattern.append(", ");
            pattern.append("'");
            for (int c = minC; c <= maxC; c++) {
                String key = ingredientKey(slots[IDX_INPUT_0 + r * WIDTH + c]);
                pattern.append(key == null ? ' ' : charByKey.get(key));
            }
            pattern.append("'");
        }
        pattern.append("]");

        StringBuilder keyMap = new StringBuilder("{");
        boolean first = true;
        for (Map.Entry<String, Character> e : charByKey.entrySet()) {
            if (!first) keyMap.append(", ");
            first = false;
            keyMap.append(e.getValue()).append(": '").append(e.getKey()).append("'");
        }
        keyMap.append("}");

        return "    event.shaped(\n"
            + "        " + outJs + ",\n"
            + "        " + pattern + ",\n"
            + "        " + keyMap + "\n"
            + "    ).id('" + recipeId + "');\n";
    }

    private String emitShapeless(String recipeId, String outJs) {
        List<String> ings = new ArrayList<>();
        for (int i = 0; i < INPUT_COUNT; i++) {
            IngredientSpec spec = slots[IDX_INPUT_0 + i];
            String s = RecipeDraft.formatIngredientString(spec);
            if (s == null) continue;
            int count = Math.max(1, spec.count());
            for (int k = 0; k < count; k++) ings.add(s);
        }
        if (ings.isEmpty()) return null;
        return "    event.shapeless(\n"
            + "        " + outJs + ",\n"
            + "        [" + String.join(", ", ings) + "]\n"
            + "    ).id('" + recipeId + "');\n";
    }

    /** key map 用：Item は 'ns:path'、Tag は '#ns:path'。空は null。 */
    private static String ingredientKey(IngredientSpec s) {
        if (s instanceof IngredientSpec.Item it && !it.stack().isEmpty()) {
            return it.stack().getItem().builtInRegistryHolder().key().location().toString();
        }
        if (s instanceof IngredientSpec.Tag tg && tg.tagId() != null) {
            return "#" + tg.tagId();
        }
        return null;
    }
}