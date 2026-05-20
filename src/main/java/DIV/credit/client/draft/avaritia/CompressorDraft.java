package DIV.credit.client.draft.avaritia;

import DIV.credit.Credit;
import DIV.credit.client.draft.IngredientSpec;
import DIV.credit.client.draft.RecipeDraft;
import DIV.credit.client.draft.ae2.AE2EmitHelper;
import mezz.jei.api.gui.IRecipeLayoutDrawable;
import mezz.jei.api.gui.ingredient.IRecipeSlotView;
import mezz.jei.api.ingredients.ITypedIngredient;
import mezz.jei.api.recipe.IRecipeManager;
import mezz.jei.api.recipe.RecipeType;
import mezz.jei.api.recipe.category.IRecipeCategory;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * Re-Avaritia Compressor 用 draft (avaritia:compressor)。
 * <p>1 input + 1 output + numeric fields (inputCount=1000, timeCost=240)。
 * <p>output が avaritia:singularity の場合は emit() で null 返す
 * (Singularity 生成は v2.1 対応外、ユーザー指示)。
 */
public final class CompressorDraft implements RecipeDraft {

    public static final int IDX_INPUT  = 0;
    public static final int IDX_OUTPUT = 1;
    public static final int SLOT_COUNT = 2;

    private static final ResourceLocation SINGULARITY_ID = new ResourceLocation("avaritia", "singularity");

    private final RecipeType<?> jeiType;
    private final IngredientSpec[] slots = new IngredientSpec[SLOT_COUNT];
    // v2.1.3: NullableLong に統一。"null" 入力で行ごと省略 (= avaritia 側 default 採用)。
    private final RecipeDraft.NullableLong inputCount = new RecipeDraft.NullableLong(1000);
    private final RecipeDraft.NullableLong timeCost   = new RecipeDraft.NullableLong(240);

    private CompressorDraft(RecipeType<?> rt) {
        this.jeiType = rt;
        for (int i = 0; i < SLOT_COUNT; i++) slots[i] = IngredientSpec.EMPTY;
    }

    @Nullable
    public static <T> CompressorDraft tryCreate(IRecipeCategory<T> cat, IRecipeManager rm) {
        return new CompressorDraft(cat.getRecipeType());
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

    @Override
    public List<NumericField> numericFields() {
        return List.of(
            inputCount.toField("InputCount", NumericField.Kind.INT, 1, Integer.MAX_VALUE),
            timeCost  .toField("TimeCost",   NumericField.Kind.INT, 1, Integer.MAX_VALUE)
        );
    }

    @Override
    public net.minecraft.world.item.crafting.Recipe<?> toRecipeInstance() {
        return null;
    }

    @Override
    public boolean loadFromRecipe(IRecipeLayoutDrawable<?> layout) {
        try {
            List<IRecipeSlotView> views = layout.getRecipeSlotsView().getSlotViews();
            for (int i = 0; i < Math.min(views.size(), SLOT_COUNT); i++) {
                IngredientSpec spec = readSpecFromView(views.get(i));
                if (!spec.isEmpty()) slots[i] = spec;
            }
            // inputCount / timeCost を reflection で取得
            Object recipe = layout.getRecipe();
            if (recipe != null) {
                tryReadInt(recipe, "getInputCount", v -> inputCount.set(v));
                tryReadInt(recipe, "getTimeCost",   v -> timeCost.set(v));
            }
            return true;
        } catch (Exception e) {
            Credit.LOGGER.warn("[CraftPattern] CompressorDraft.loadFromRecipe failed: {}", e.toString());
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

    private static void tryReadInt(Object recipe, String methodName, java.util.function.IntConsumer setter) {
        try {
            var m = recipe.getClass().getMethod(methodName);
            Object res = m.invoke(recipe);
            if (res instanceof Integer i) setter.accept(i);
        } catch (Exception ignored) {}
    }

    @Override
    public String emit(String recipeId) {
        IngredientSpec input  = slots[IDX_INPUT].unwrap();
        IngredientSpec result = slots[IDX_OUTPUT].unwrap();
        if (input.isEmpty()) {
            Credit.LOGGER.info("[CraftPattern] CompressorDraft.emit({}) → null: input empty", recipeId);
            return null;
        }
        if (!(result instanceof IngredientSpec.Item resIt) || resIt.stack().isEmpty()) {
            Credit.LOGGER.info("[CraftPattern] CompressorDraft.emit({}) → null: output empty/non-item", recipeId);
            return null;
        }

        // output が Singularity (avaritia:singularity) の場合は対応外
        ResourceLocation resRl = BuiltInRegistries.ITEM.getKey(resIt.stack().getItem());
        if (SINGULARITY_ID.equals(resRl)) {
            Credit.LOGGER.warn("[CraftPattern] CompressorDraft.emit: output is Singularity, not supported in v2.1");
            return null;
        }

        StringBuilder sb = new StringBuilder();
        sb.append("    event.custom({\n");
        sb.append("        type: 'avaritia:compressor',\n");
        sb.append("        ingredient: ").append(AE2EmitHelper.ingredientJson(input)).append(",\n");
        sb.append("        result: { item: '").append(resRl).append("'");
        if (resIt.stack().getCount() > 1) sb.append(", count: ").append(resIt.stack().getCount());
        sb.append(" }");
        if (inputCount.isPresent()) sb.append(",\n        inputCount: ").append(inputCount.get());
        if (timeCost.isPresent())   sb.append(",\n        timeCost: ").append(timeCost.get());
        sb.append("\n    }).id('").append(recipeId).append("');\n");
        return sb.toString();
    }

    @Override
    public String relativeOutputPath() {
        return "generated/avaritia/compressor.js";
    }
}
