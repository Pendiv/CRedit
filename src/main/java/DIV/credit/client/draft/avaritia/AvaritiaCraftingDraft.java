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

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Re-Avaritia Shaped/Shapeless Crafting Table 用 draft (tier 1-3)。
 * <p>category UID:
 * <ul>
 *   <li>avaritia:sculk_craft  → tier 1, grid 3x3</li>
 *   <li>avaritia:nether_craft → tier 2, grid 5x5</li>
 *   <li>avaritia:end_craft    → tier 3, grid 7x7</li>
 * </ul>
 * <p>1 カテゴリに shaped + shapeless が混在するため、Mode は canCycleTier API で切替。
 * <p>tier 4 (extreme_craft, 9x9) は EXPLICIT_UNSUPPORTED で対応外。
 */
public final class AvaritiaCraftingDraft implements RecipeDraft {

    public enum Mode {
        SHAPED   ("Shaped",    0xFFAAFFAA),
        SHAPELESS("Shapeless", 0xFFFFCC66);
        public final String displayName;
        public final int color;
        Mode(String n, int c) { displayName = n; color = c; }
        public Mode cycle()     { return values()[(ordinal() + 1) % values().length]; }
        public Mode cycleBack() { Mode[] v = values(); return v[(ordinal() - 1 + v.length) % v.length]; }
    }

    private final RecipeType<?> jeiType;
    private final int tier;        // 1 / 2 / 3
    private final int gridSize;    // 3 / 5 / 7
    private final int outputIndex; // = gridSize²
    private final IngredientSpec[] slots;
    private Mode mode = Mode.SHAPED;

    private AvaritiaCraftingDraft(RecipeType<?> rt, int tier) {
        this.jeiType = rt;
        this.tier = tier;
        this.gridSize = switch (tier) {
            case 1 -> 3;
            case 2 -> 5;
            case 3 -> 7;
            default -> 3;
        };
        int gridArea = gridSize * gridSize;
        this.outputIndex = gridArea;
        this.slots = new IngredientSpec[gridArea + 1];
        for (int i = 0; i < slots.length; i++) slots[i] = IngredientSpec.EMPTY;
    }

    @Nullable
    public static <T> AvaritiaCraftingDraft tryCreate(IRecipeCategory<T> cat, IRecipeManager rm) {
        int tier = AvaritiaSupport.tierForCategoryUid(cat.getRecipeType().getUid().getPath());
        if (tier < 1 || tier > 3) return null;
        return new AvaritiaCraftingDraft(cat.getRecipeType(), tier);
    }

    public int getTier() { return tier; }
    public int getGridSize() { return gridSize; }
    public Mode getMode() { return mode; }
    public void setMode(Mode m) { if (m != null) mode = m; }

    @Override public int            slotCount()         { return slots.length; }
    @Override public IngredientSpec getSlot(int i)      { return slots[i]; }
    @Override public RecipeType<?>  recipeType()        { return jeiType; }
    @Override public boolean        isOutputSlot(int i) { return i == outputIndex; }
    @Override public IngredientSpec getOutput()         { return slots[outputIndex]; }

    @Override
    public void setSlot(int i, IngredientSpec s) {
        if (i < 0 || i >= slots.length) return;
        if (s == null) s = IngredientSpec.EMPTY;
        if (!acceptsAt(i, s)) return;
        slots[i] = s;
    }

    /** input slot は count 1 lock (vanilla shaped と同様)、output は無制限。 */
    @Override
    public int slotMaxCount(int slotIndex) {
        return isOutputSlot(slotIndex) ? Integer.MAX_VALUE : 1;
    }

    @Override public boolean canCycleTier()           { return true; }
    @Override public String  getTierLabel()           { return mode.displayName; }
    @Override public int     getTierColor()           { return mode.color; }
    @Override public void    cycleTier(boolean forward) {
        mode = forward ? mode.cycle() : mode.cycleBack();
    }

    @Override
    public net.minecraft.world.item.crafting.Recipe<?> toRecipeInstance() {
        // v2.1.2: draft slot 内容を ShapedTable / ShapelessTable recipe として reflection 構築。
        // output が空だと JEI が drawable 作成失敗するため placeholder (Items.BARRIER) で埋める。
        int total = gridSize * gridSize;
        var inputs = net.minecraft.core.NonNullList.withSize(
            total, net.minecraft.world.item.crafting.Ingredient.EMPTY);
        for (int i = 0; i < Math.min(total, outputIndex); i++) {
            var s = slots[i].unwrap();
            if (s instanceof IngredientSpec.Item it && !it.stack().isEmpty()) {
                inputs.set(i, net.minecraft.world.item.crafting.Ingredient.of(it.stack()));
            } else if (s instanceof IngredientSpec.Tag tg && tg.tagId() != null) {
                var tk = net.minecraft.tags.TagKey.create(
                    net.minecraft.core.registries.Registries.ITEM, tg.tagId());
                inputs.set(i, net.minecraft.world.item.crafting.Ingredient.of(tk));
            }
        }
        var outSpec = slots[outputIndex].unwrap();
        net.minecraft.world.item.ItemStack outStack;
        if (outSpec instanceof IngredientSpec.Item it && !it.stack().isEmpty()) {
            outStack = it.stack().copy();
        } else {
            // placeholder — JEI drawable 作成のため non-empty stack 必須
            outStack = new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.BARRIER);
        }
        var built = mode == Mode.SHAPED
            ? AvaritiaSupport.tryBuildShapedRecipe(tier, inputs, outStack)
            : AvaritiaSupport.tryBuildShapelessRecipe(tier, inputs, outStack);
        Credit.LOGGER.info("[CraftPattern] AvaritiaCraftingDraft.toRecipeInstance(tier={}, mode={}, outputEmpty={}) → {}",
            tier, mode, outSpec.isEmpty(),
            built == null ? "null (FALLBACK)" : built.getClass().getSimpleName());
        return built;
    }

    @Override
    public boolean loadFromRecipe(IRecipeLayoutDrawable<?> layout) {
        try {
            List<IRecipeSlotView> views = layout.getRecipeSlotsView().getSlotViews();
            int n = Math.min(views.size(), slots.length);
            for (int i = 0; i < n; i++) {
                IngredientSpec spec = readSpecFromView(views.get(i));
                if (!spec.isEmpty()) slots[i] = spec;
            }
            // shaped vs shapeless を recipe class 名で判定
            Object recipe = layout.getRecipe();
            if (recipe != null) {
                String cn = recipe.getClass().getName();
                if (cn.contains("Shapeless")) mode = Mode.SHAPELESS;
                else mode = Mode.SHAPED;
            }
            return true;
        } catch (Exception e) {
            Credit.LOGGER.warn("[CraftPattern] AvaritiaCraftingDraft.loadFromRecipe failed: {}", e.toString());
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
        IngredientSpec result = slots[outputIndex].unwrap();
        if (!(result instanceof IngredientSpec.Item resIt) || resIt.stack().isEmpty()) {
            Credit.LOGGER.info("[CraftPattern] AvaritiaCraftingDraft.emit({}) → null: output empty/non-item (tier={}, mode={}, outputIndex={})",
                recipeId, tier, mode, outputIndex);
            return null;
        }

        ResourceLocation resRl = BuiltInRegistries.ITEM.getKey(resIt.stack().getItem());
        StringBuilder sb = new StringBuilder();
        sb.append("    event.custom({\n");
        if (mode == Mode.SHAPED) {
            sb.append("        type: 'avaritia:shaped_table',\n");
            sb.append("        tier: ").append(tier).append(",\n");
            emitShaped(sb);
        } else {
            sb.append("        type: 'avaritia:shapeless_table',\n");
            sb.append("        tier: ").append(tier).append(",\n");
            emitShapeless(sb);
        }
        sb.append("        result: { item: '").append(resRl).append("'");
        if (resIt.stack().getCount() > 1) sb.append(", count: ").append(resIt.stack().getCount());
        sb.append(" }\n");
        sb.append("    }).id('").append(recipeId).append("');\n");
        return sb.toString();
    }

    /** Shaped: pattern + key 形式。各 unique ingredient に文字を割当。 */
    private void emitShaped(StringBuilder sb) {
        // 1. unique ingredient -> key 文字 (A, B, C, ...)
        Map<String, Character> ingToKey = new LinkedHashMap<>();
        char nextKey = 'A';
        char[][] grid = new char[gridSize][gridSize];
        // 2. 各 input slot を grid に展開、key 割当
        for (int row = 0; row < gridSize; row++) {
            for (int col = 0; col < gridSize; col++) {
                int idx = row * gridSize + col;
                IngredientSpec s = slots[idx].unwrap();
                if (s.isEmpty()) {
                    grid[row][col] = ' ';
                } else {
                    String json = AE2EmitHelper.ingredientJson(s);
                    Character k = ingToKey.get(json);
                    if (k == null) {
                        k = nextKey++;
                        ingToKey.put(json, k);
                    }
                    grid[row][col] = k;
                }
            }
        }
        // 3. trim 周囲の空 row/col (shaped でも valid な最小 grid)
        int rowFrom = 0, rowTo = gridSize - 1;
        int colFrom = 0, colTo = gridSize - 1;
        while (rowFrom <= rowTo && isRowEmpty(grid[rowFrom])) rowFrom++;
        while (rowTo >= rowFrom && isRowEmpty(grid[rowTo])) rowTo--;
        while (colFrom <= colTo && isColEmpty(grid, colFrom, rowFrom, rowTo)) colFrom++;
        while (colTo >= colFrom && isColEmpty(grid, colTo, rowFrom, rowTo)) colTo--;
        if (rowFrom > rowTo || colFrom > colTo) {
            // 全空 → fallback で minimal pattern
            sb.append("        pattern: [' '],\n");
            sb.append("        key: {},\n");
            return;
        }
        // 4. pattern 出力
        sb.append("        pattern: [\n");
        for (int r = rowFrom; r <= rowTo; r++) {
            sb.append("            \"");
            for (int c = colFrom; c <= colTo; c++) sb.append(grid[r][c]);
            sb.append("\"");
            if (r < rowTo) sb.append(",");
            sb.append("\n");
        }
        sb.append("        ],\n");
        // 5. key 出力
        sb.append("        key: {\n");
        int i = 0;
        int total = ingToKey.size();
        for (Map.Entry<String, Character> e : ingToKey.entrySet()) {
            sb.append("            ").append(e.getValue()).append(": ").append(e.getKey());
            if (i < total - 1) sb.append(",");
            sb.append("\n");
            i++;
        }
        sb.append("        },\n");
    }

    private static boolean isRowEmpty(char[] row) {
        for (char c : row) if (c != ' ') return false;
        return true;
    }

    private static boolean isColEmpty(char[][] grid, int col, int rowFrom, int rowTo) {
        for (int r = rowFrom; r <= rowTo; r++) if (grid[r][col] != ' ') return false;
        return true;
    }

    /** Shapeless: ingredients 配列形式。 */
    private void emitShapeless(StringBuilder sb) {
        List<String> ings = new ArrayList<>();
        for (int i = 0; i < outputIndex; i++) {
            IngredientSpec s = slots[i].unwrap();
            if (s.isEmpty()) continue;
            ings.add(AE2EmitHelper.ingredientJson(s));
        }
        if (ings.isEmpty()) {
            sb.append("        ingredients: [],\n");
            return;
        }
        sb.append("        ingredients: [\n");
        for (int i = 0; i < ings.size(); i++) {
            sb.append("            ").append(ings.get(i));
            if (i < ings.size() - 1) sb.append(",");
            sb.append("\n");
        }
        sb.append("        ],\n");
    }

    @Override
    public String relativeOutputPath() {
        return "generated/avaritia/crafting_tier" + tier + ".js";
    }
}
