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
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * AE2 Inscriber 用 draft (ae2:inscriber)。
 * <p>4 slot: top(0) / middle(1, 必須) / bottom(2) / output(3)
 * <p>mode: INSCRIBE (top/bottom 消費なし、Printed Circuits 等) / PRESS (top/bottom も消費、Processor 等)
 * <p>mode は canCycleTier API で表現 (BuilderScreen の tier widget に乗る)。
 */
public final class InscriberDraft implements RecipeDraft {

    public static final int IDX_TOP    = 0;
    public static final int IDX_MIDDLE = 1;
    public static final int IDX_BOTTOM = 2;
    public static final int IDX_OUTPUT = 3;
    public static final int SLOT_COUNT = 4;

    public enum Mode {
        INSCRIBE("Inscribe", 0xFF6699FF),
        PRESS   ("Press",    0xFFFF9933);

        public final String displayName;
        public final int color;

        Mode(String n, int c) { displayName = n; color = c; }

        public Mode cycle()     { return values()[(ordinal() + 1) % values().length]; }
        public Mode cycleBack() { Mode[] v = values(); return v[(ordinal() - 1 + v.length) % v.length]; }
    }

    private final RecipeType<?> jeiType;
    private final IngredientSpec[] slots = new IngredientSpec[SLOT_COUNT];
    private Mode mode = Mode.INSCRIBE;

    private InscriberDraft(RecipeType<?> rt) {
        this.jeiType = rt;
        for (int i = 0; i < SLOT_COUNT; i++) slots[i] = IngredientSpec.EMPTY;
    }

    @Nullable
    public static <T> InscriberDraft tryCreate(IRecipeCategory<T> cat, IRecipeManager rm) {
        return new InscriberDraft(cat.getRecipeType());
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

    public Mode getMode() { return mode; }
    public void setMode(Mode m) { if (m != null) mode = m; }

    @Override public boolean canCycleTier()           { return true; }
    @Override public String  getTierLabel()           { return mode.displayName; }
    @Override public int     getTierColor()           { return mode.color; }
    @Override public void    cycleTier(boolean forward) {
        mode = forward ? mode.cycle() : mode.cycleBack();
    }

    @Override
    public net.minecraft.world.item.crafting.Recipe<?> toRecipeInstance() {
        return null;
    }

    @Override
    public boolean loadFromRecipe(IRecipeLayoutDrawable<?> layout) {
        try {
            List<IRecipeSlotView> views = layout.getRecipeSlotsView().getSlotViews();
            int n = Math.min(views.size(), SLOT_COUNT);
            for (int i = 0; i < n; i++) {
                IngredientSpec spec = readSpecFromView(views.get(i));
                if (!spec.isEmpty()) slots[i] = spec;
            }
            Object recipe = layout.getRecipe();
            if (recipe != null) tryReadMode(recipe);
            return true;
        } catch (Exception e) {
            Credit.LOGGER.warn("[C4010] InscriberDraft.loadFromRecipe failed: {}", e.toString());
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

    private void tryReadMode(Object recipe) {
        for (String mn : new String[]{"getProcessType", "processType"}) {
            try {
                var m = recipe.getClass().getMethod(mn);
                Object res = m.invoke(recipe);
                if (res != null) {
                    String name = res.toString();
                    if ("INSCRIBE".equalsIgnoreCase(name)) { mode = Mode.INSCRIBE; return; }
                    if ("PRESS".equalsIgnoreCase(name))    { mode = Mode.PRESS;    return; }
                }
            } catch (Exception ignored) {}
        }
    }

    @Override
    public String emit(String recipeId) {
        IngredientSpec result = slots[IDX_OUTPUT].unwrap();
        if (!(result instanceof IngredientSpec.Item resIt) || resIt.stack().isEmpty()) {
            Credit.LOGGER.info("[CraftPattern] InscriberDraft.emit({}) → null: output empty/non-item", recipeId);
            return null;
        }
        IngredientSpec mid = slots[IDX_MIDDLE].unwrap();
        if (mid.isEmpty()) {
            Credit.LOGGER.info("[CraftPattern] InscriberDraft.emit({}) → null: middle slot empty (required)", recipeId);
            return null;
        }

        ResourceLocation resRl = BuiltInRegistries.ITEM.getKey(resIt.stack().getItem());
        StringBuilder sb = new StringBuilder();
        sb.append("    event.custom({\n");
        sb.append("        type: 'ae2:inscriber',\n");
        sb.append("        mode: '").append(mode.name().toLowerCase()).append("',\n");
        sb.append("        ingredients: {\n");
        sb.append("            middle: ").append(AE2EmitHelper.ingredientJson(mid));
        IngredientSpec top = slots[IDX_TOP].unwrap();
        IngredientSpec bot = slots[IDX_BOTTOM].unwrap();
        if (!top.isEmpty()) sb.append(",\n            top: ").append(AE2EmitHelper.ingredientJson(top));
        if (!bot.isEmpty()) sb.append(",\n            bottom: ").append(AE2EmitHelper.ingredientJson(bot));
        sb.append("\n        },\n");
        sb.append("        result: { item: '").append(resRl).append("'");
        if (resIt.stack().getCount() > 1) sb.append(", count: ").append(resIt.stack().getCount());
        sb.append(" }\n");
        sb.append("    }).id('").append(recipeId).append("');\n");
        return sb.toString();
    }

    @Override
    public String relativeOutputPath() {
        return "generated/ae2/inscriber.js";
    }
}
