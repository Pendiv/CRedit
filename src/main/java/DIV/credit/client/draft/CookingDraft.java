package DIV.credit.client.draft;

import DIV.credit.Credit;
import mezz.jei.api.constants.RecipeTypes;
import mezz.jei.api.recipe.RecipeType;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.crafting.AbstractCookingRecipe;
import net.minecraft.world.item.crafting.BlastingRecipe;
import net.minecraft.world.item.crafting.CampfireCookingRecipe;
import net.minecraft.world.item.crafting.CookingBookCategory;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.SmeltingRecipe;
import net.minecraft.world.item.crafting.SmokingRecipe;

import java.util.List;

/**
 * AbstractCookingCategory を共通レイアウトとする 4 種：smelting / blasting / smoking / campfireCooking。
 * JEI スロット並び：index 0 = INPUT、index 1 = RENDER_ONLY (flame)、index 2 = OUTPUT。
 */
public class CookingDraft implements RecipeDraft {

    public enum Type {
        SMELTING(RecipeTypes.SMELTING, "smelting", CookingBookCategory.MISC, 200, 0.7f),
        BLASTING(RecipeTypes.BLASTING, "blasting", CookingBookCategory.MISC, 100, 0.7f),
        SMOKING(RecipeTypes.SMOKING, "smoking", CookingBookCategory.FOOD, 100, 0.35f),
        CAMPFIRE(RecipeTypes.CAMPFIRE_COOKING, "campfireCooking", CookingBookCategory.FOOD, 600, 0.35f);

        public final RecipeType<?>          jeiType;
        public final String                 kjsMethod;
        public final CookingBookCategory    cookCategory;
        public final int                    defaultTime;
        public final float                  defaultXp;

        Type(RecipeType<?> jt, String km, CookingBookCategory cc, int dt, float dx) {
            this.jeiType = jt; this.kjsMethod = km; this.cookCategory = cc;
            this.defaultTime = dt; this.defaultXp = dx;
        }
    }

    public static final int SLOT_COUNT = 3;
    public static final int IDX_INPUT  = 0;
    public static final int IDX_FLAME  = 1;
    public static final int IDX_OUTPUT = 2;

    private final Type type;
    private final IngredientSpec[] slots = new IngredientSpec[SLOT_COUNT];
    private float xp;
    private int   cookingTime;

    public CookingDraft(Type type) {
        this.type = type;
        this.xp          = type.defaultXp;
        this.cookingTime = type.defaultTime;
        for (int i = 0; i < SLOT_COUNT; i++) slots[i] = IngredientSpec.EMPTY;
    }

    public Type  getType()         { return type; }
    public float getXp()           { return xp; }
    public void  setXp(float v)    { this.xp = v; }
    public int   getCookingTime()  { return cookingTime; }
    public void  setCookingTime(int v) { this.cookingTime = Math.max(1, v); }

    @Override
    public List<NumericField> numericFields() {
        return List.of(
            new NumericField("XP",   NumericField.Kind.FLOAT, () -> xp,          v -> setXp((float) v),     0, 100_000),
            new NumericField("Time", NumericField.Kind.INT,   () -> cookingTime, v -> setCookingTime((int) v), 1, 100_000)
        );
    }

    @Override public int slotCount() { return SLOT_COUNT; }
    @Override public IngredientSpec getSlot(int i) { return slots[i]; }
    @Override public boolean isOutputSlot(int i) { return i == IDX_OUTPUT; }
    @Override public IngredientSpec getOutput() { return slots[IDX_OUTPUT]; }
    @Override public RecipeType<?> recipeType() { return type.jeiType; }

    @Override
    public void setSlot(int i, IngredientSpec s) {
        if (i == IDX_FLAME) return; // RENDER_ONLY
        if (i < 0 || i >= SLOT_COUNT) return;
        if (s == null) s = IngredientSpec.EMPTY;
        if (isOutputSlot(i) && s instanceof IngredientSpec.Tag) return;
        slots[i] = s;
    }

    @Override
    public Recipe<?> toRecipeInstance() {
        Ingredient ing = RecipeDraft.toIngredient(slots[IDX_INPUT]);
        ResourceLocation id = new ResourceLocation(Credit.MODID, "draft/" + type.name().toLowerCase());
        return switch (type) {
            case SMELTING -> new SmeltingRecipe(id, "", type.cookCategory, ing, RecipeDraft.toOutputStack(slots[IDX_OUTPUT]), xp, cookingTime);
            case BLASTING -> new BlastingRecipe(id, "", type.cookCategory, ing, RecipeDraft.toOutputStack(slots[IDX_OUTPUT]), xp, cookingTime);
            case SMOKING  -> new SmokingRecipe(id,  "", type.cookCategory, ing, RecipeDraft.toOutputStack(slots[IDX_OUTPUT]), xp, cookingTime);
            case CAMPFIRE -> new CampfireCookingRecipe(id, "", type.cookCategory, ing, RecipeDraft.toOutputStack(slots[IDX_OUTPUT]), xp, cookingTime);
        };
    }

    @Override
    public String relativeOutputPath() {
        return "generated/" + type.name().toLowerCase() + ".js";
    }

    @Override
    public String emit(String recipeId) {
        String inJs  = RecipeDraft.formatIngredientString(slots[IDX_INPUT]);
        String outJs = RecipeDraft.formatIngredientWithCount(slots[IDX_OUTPUT]);
        if (inJs == null || outJs == null) return null;
        return "    event." + type.kjsMethod + "(\n"
            + "        " + outJs + ",\n"
            + "        " + inJs + "\n"
            + "    ).xp(" + xp + ").cookingTime(" + cookingTime + ")\n"
            + "    .id('" + recipeId + "');\n";
    }
}