package DIV.credit.client.draft.de;

import DIV.credit.Credit;
import DIV.credit.client.draft.IngredientSpec;
import DIV.credit.client.draft.RecipeDraft;
import mezz.jei.api.gui.IRecipeLayoutDrawable;
import mezz.jei.api.gui.ingredient.IRecipeSlotView;
import mezz.jei.api.ingredients.ITypedIngredient;
import mezz.jei.api.recipe.IFocusGroup;
import mezz.jei.api.recipe.IRecipeManager;
import mezz.jei.api.recipe.RecipeIngredientRole;
import mezz.jei.api.recipe.RecipeType;
import mezz.jei.api.recipe.category.IRecipeCategory;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Optional;

/**
 * Draconic Evolution: draconicevolution:fusion_crafting 用 Draft。
 * <ul>
 *   <li>slot[0] = catalyst (中央上、INPUT)</li>
 *   <li>slot[1] = result (中央下、OUTPUT)</li>
 *   <li>slot[2..] = ingredients (周辺、INPUT)</li>
 * </ul>
 * Tier: DETechLevel enum (DRACONIUM/WYVERN/DRACONIC/CHAOTIC)。BuilderScreen で click cycle。
 * total_energy: long、numericFields に Long 値として expose。
 * <p>v2.0.0 注意: スロット数は最初に probe した sample 由来で固定。可変化は v2.1+ enhancement。
 */
public final class FusionCraftingDraft implements RecipeDraft {

    public static final int IDX_CATALYST = 0;
    public static final int IDX_RESULT   = 1;
    public static final int IDX_INPUT_0  = 2;

    private final RecipeType<?> jeiType;
    private final IngredientSpec[] slots;
    private DETechLevel tier = DETechLevel.WYVERN;
    private long totalEnergy = 1_000_000L;

    private FusionCraftingDraft(RecipeType<?> rt, int slotCount) {
        this.jeiType = rt;
        this.slots = new IngredientSpec[Math.max(slotCount, 3)];  // catalyst+result+ingredient 最低3
        for (int i = 0; i < slots.length; i++) slots[i] = IngredientSpec.EMPTY;
    }

    /** sample レシピを 1 件取って slot 数決定。 */
    @Nullable
    public static <T> FusionCraftingDraft tryCreate(IRecipeCategory<T> cat, IRecipeManager rm) {
        try {
            RecipeType<T> rt = cat.getRecipeType();
            Optional<T> sample = rm.createRecipeLookup(rt).includeHidden().get().findFirst();
            int slotCount = 3;  // catalyst + result + 1 ingredient minimum
            if (sample.isPresent()) {
                IFocusGroup empty = DIV.credit.jei.CraftPatternJeiPlugin.runtime
                    .getJeiHelpers().getFocusFactory().getEmptyFocusGroup();
                IRecipeLayoutDrawable<?> drawable = rm.createRecipeLayoutDrawable(cat, sample.get(), empty).orElse(null);
                if (drawable != null) {
                    slotCount = drawable.getRecipeSlotsView().getSlotViews().size();
                }
            }
            Credit.LOGGER.info("[CraftPattern] FusionCraftingDraft created for {} ({} slots)",
                rt.getUid(), slotCount);
            return new FusionCraftingDraft(rt, slotCount);
        } catch (Exception e) {
            Credit.LOGGER.warn("[CraftPattern] FusionCraftingDraft probe failed: {}", e.toString());
            return null;
        }
    }

    @Override public int slotCount() { return slots.length; }
    @Override public IngredientSpec getSlot(int i) { return slots[i]; }
    @Override public RecipeType<?> recipeType() { return jeiType; }

    @Override
    public SlotKind slotKind(int i) {
        if (i == IDX_RESULT) return SlotKind.ITEM_OUTPUT;
        return SlotKind.ITEM_INPUT;
    }

    /**
     * v2.0.12: DE fusion_crafting の slot count 制限。
     * - catalyst (0)  : 無制限 (IngredientStack で count 表現可能)
     * - result (1)    : 無制限 (count 指定可能)
     * - ingredients[] : count=1 lock (FusionIngredient JSON は count フィールド非対応)
     */
    @Override
    public int slotMaxCount(int slotIndex) {
        if (slotIndex == IDX_CATALYST || slotIndex == IDX_RESULT) {
            return Integer.MAX_VALUE;
        }
        if (slotIndex >= IDX_INPUT_0 && slotIndex < slots.length) {
            return 1;  // ingredients は count 指定不可
        }
        return Integer.MAX_VALUE;
    }

    @Override public boolean isOutputSlot(int i) { return i == IDX_RESULT; }

    @Override
    public IngredientSpec getOutput() { return slots[IDX_RESULT]; }

    @Override
    public void setSlot(int i, IngredientSpec s) {
        if (i < 0 || i >= slots.length) return;
        if (s == null) s = IngredientSpec.EMPTY;
        if (!acceptsAt(i, s)) return;
        slots[i] = s;
    }

    public DETechLevel getTier() { return tier; }
    public void setTier(DETechLevel t) { if (t != null) this.tier = t; }
    public long getTotalEnergy() { return totalEnergy; }
    public void setTotalEnergy(long e) { this.totalEnergy = Math.max(0, e); }

    @Override public boolean canCycleTier() { return true; }
    @Override public String  getTierLabel() { return tier.displayName; }
    @Override public int     getTierColor() { return tier.color; }
    @Override public void    cycleTier(boolean forward) {
        this.tier = forward ? tier.cycle() : tier.cycleBack();
    }

    @Override
    public List<NumericField> numericFields() {
        return List.of(
            new NumericField("OP", NumericField.Kind.INT,
                () -> (double) totalEnergy,
                v -> totalEnergy = (long) v,
                0, Long.MAX_VALUE)
        );
    }

    @Override
    public net.minecraft.world.item.crafting.Recipe<?> toRecipeInstance() {
        // v2.0.13: 現 tier で空 FusionRecipe を構築 → DE が JEI で tier 表示を自前描画
        // 失敗時 null → FALLBACK で sample 描画 (tier 表示は元のまま)
        var built = DESupport.tryBuildEmptyFusionRecipe(null, tier.name(), totalEnergy);
        return built;  // null も許容
    }

    /** 既存 IFusionRecipe からスロット内容を流し込む（reflection、DE class 依存しない）。 */
    @Override
    public boolean loadFromRecipe(IRecipeLayoutDrawable<?> layout) {
        try {
            // slot view 経由（ITypedIngredient → ItemStack）が一番 robust
            List<IRecipeSlotView> views = layout.getRecipeSlotsView().getSlotViews();
            int n = Math.min(views.size(), slots.length);
            int loaded = 0;
            for (int i = 0; i < n; i++) {
                IngredientSpec spec = readSpecFromView(views.get(i));
                if (!spec.isEmpty()) {
                    slots[i] = spec;
                    loaded++;
                }
            }
            // tier / total_energy は recipe instance から reflection で取り出す
            Object recipe = layout.getRecipe();
            if (recipe != null) {
                tryReadTier(recipe);
                tryReadEnergy(recipe);
            }
            Credit.LOGGER.info("[CraftPattern] FusionCraftingDraft.loadFromRecipe → {}/{} slots, tier={} energy={}",
                loaded, n, tier, totalEnergy);
            return loaded > 0;
        } catch (Exception e) {
            Credit.LOGGER.warn("[CraftPattern] FusionCraftingDraft.loadFromRecipe failed: {}", e.toString());
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

    private void tryReadTier(Object recipe) {
        for (String methodName : new String[]{"getRecipeTier", "getTier", "techLevel"}) {
            try {
                var m = recipe.getClass().getMethod(methodName);
                Object res = m.invoke(recipe);
                if (res != null) {
                    String name = res.toString();
                    for (DETechLevel t : DETechLevel.values()) {
                        if (t.name().equalsIgnoreCase(name)) {
                            this.tier = t;
                            return;
                        }
                    }
                }
            } catch (Exception ignored) {}
        }
    }

    private void tryReadEnergy(Object recipe) {
        for (String methodName : new String[]{"getEnergyCost", "getTotalEnergy", "totalEnergy"}) {
            try {
                var m = recipe.getClass().getMethod(methodName);
                Object res = m.invoke(recipe);
                if (res instanceof Long l) { this.totalEnergy = l; return; }
                if (res instanceof Integer i) { this.totalEnergy = i; return; }
            } catch (Exception ignored) {}
        }
    }

    @Override
    public String emit(String recipeId) {
        IngredientSpec result   = slots[IDX_RESULT].unwrap();
        IngredientSpec catalyst = slots[IDX_CATALYST].unwrap();
        if (!(result instanceof IngredientSpec.Item resIt) || resIt.stack().isEmpty()) return null;
        if (!(catalyst instanceof IngredientSpec.Item catIt) || catIt.stack().isEmpty()) return null;

        ResourceLocation resRl = BuiltInRegistries.ITEM.getKey(resIt.stack().getItem());
        ResourceLocation catRl = BuiltInRegistries.ITEM.getKey(catIt.stack().getItem());

        StringBuilder sb = new StringBuilder();
        sb.append("    event.custom({\n");
        sb.append("        type: 'draconicevolution:fusion_crafting',\n");
        sb.append("        result: { item: '").append(resRl);
        if (resIt.stack().getCount() > 1) sb.append("', count: ").append(resIt.stack().getCount());
        else sb.append("'");
        sb.append(" },\n");
        sb.append("        catalyst: { item: '").append(catRl).append("' },\n");
        sb.append("        total_energy: ").append(totalEnergy).append(",\n");
        sb.append("        tier: '").append(tier.name()).append("',\n");
        sb.append("        ingredients: [\n");
        boolean any = false;
        for (int i = IDX_INPUT_0; i < slots.length; i++) {
            String ingJs = ingredientLine(slots[i]);
            if (ingJs == null) continue;
            if (any) sb.append(",\n");
            sb.append("            ").append(ingJs);
            any = true;
        }
        sb.append("\n        ]\n");
        sb.append("    }).id('").append(recipeId).append("');\n");
        return sb.toString();
    }

    /** ingredient 1 行: { ingredient: { item: 'xxx' } } または tag 形式。 */
    @Nullable
    private static String ingredientLine(IngredientSpec spec) {
        if (spec == null || spec.isEmpty()) return null;
        spec = spec.unwrap();
        if (spec instanceof IngredientSpec.Item it && !it.stack().isEmpty()) {
            ResourceLocation rl = BuiltInRegistries.ITEM.getKey(it.stack().getItem());
            return "{ ingredient: { item: '" + rl + "' } }";
        }
        if (spec instanceof IngredientSpec.Tag tg && tg.tagId() != null) {
            return "{ ingredient: { tag: '" + tg.tagId() + "' } }";
        }
        return null;
    }

    @Override
    public String relativeOutputPath() {
        // v2.0.0 ScriptWriter 集中管理に移行済。このメソッドは呼ばれない。
        return "generated/draconicevolution/fusion_crafting.js";
    }
}
