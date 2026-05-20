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
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Optional;

/**
 * AE2 Entropy Manipulator 用 draft (ae2:entropy)。
 * <p>v2.1 範囲: block input + 出力 (block / drops 可変長) + mode (HEAT/COOL)。
 * <p>v2.1.3: slot role を probe 時に保存し、slotKind/acceptsAt/isOutputSlot を動的化。
 * RENDER_ONLY (destroyed input overlay) や CATALYST は編集不可として扱う。
 * <p>fluid input/output、block state properties は v2.2+。
 */
public final class EntropyDraft implements RecipeDraft {

    public enum Mode {
        HEAT("Heat", 0xFFFF6644),
        COOL("Cool", 0xFF66AAFF);
        public final String displayName;
        public final int color;
        Mode(String n, int c) { displayName = n; color = c; }
        public Mode cycle()     { return values()[(ordinal() + 1) % values().length]; }
        public Mode cycleBack() { Mode[] v = values(); return v[(ordinal() - 1 + v.length) % v.length]; }
    }

    /** SlotRole 内部表現: 0=INPUT, 1=OUTPUT, 2=非編集 (RENDER_ONLY / CATALYST 等)。 */
    private static final int ROLE_INPUT     = 0;
    private static final int ROLE_OUTPUT    = 1;
    private static final int ROLE_READONLY  = 2;

    private final RecipeType<?> jeiType;
    private final IngredientSpec[] slots;
    private final int[] slotRoles;          // probe 時に決定
    private final int firstInputIndex;
    private Mode mode = Mode.HEAT;

    private EntropyDraft(RecipeType<?> rt, int[] roles) {
        this.jeiType = rt;
        this.slotRoles = roles;
        this.slots = new IngredientSpec[roles.length];
        for (int i = 0; i < slots.length; i++) slots[i] = IngredientSpec.EMPTY;
        int firstInput = -1;
        for (int i = 0; i < roles.length; i++) {
            if (roles[i] == ROLE_INPUT) { firstInput = i; break; }
        }
        this.firstInputIndex = firstInput;
    }

    @Nullable
    public static <T> EntropyDraft tryCreate(IRecipeCategory<T> cat, IRecipeManager rm) {
        try {
            int[] roles;
            // sample から slot 数 + role を probe
            Optional<T> sample = rm.createRecipeLookup(cat.getRecipeType()).includeHidden().get().findFirst();
            if (sample.isPresent() && CraftPatternJeiPlugin.runtime != null) {
                IFocusGroup empty = CraftPatternJeiPlugin.runtime.getJeiHelpers().getFocusFactory().getEmptyFocusGroup();
                IRecipeLayoutDrawable<?> drawable = rm.createRecipeLayoutDrawable(cat, sample.get(), empty).orElse(null);
                if (drawable != null) {
                    List<IRecipeSlotView> views = drawable.getRecipeSlotsView().getSlotViews();
                    roles = new int[Math.max(views.size(), 2)];
                    for (int i = 0; i < views.size(); i++) {
                        var role = views.get(i).getRole();
                        roles[i] = role == RecipeIngredientRole.INPUT ? ROLE_INPUT
                                 : role == RecipeIngredientRole.OUTPUT ? ROLE_OUTPUT
                                 : ROLE_READONLY;
                    }
                    // padding (views.size() < 2 だった場合)
                    for (int i = views.size(); i < roles.length; i++) roles[i] = ROLE_OUTPUT;
                } else {
                    roles = new int[]{ROLE_INPUT, ROLE_OUTPUT};
                }
            } else {
                roles = new int[]{ROLE_INPUT, ROLE_OUTPUT};
            }
            StringBuilder rolesStr = new StringBuilder();
            for (int r : roles) rolesStr.append(r == ROLE_INPUT ? "I" : r == ROLE_OUTPUT ? "O" : "R");
            Credit.LOGGER.info("[CraftPattern] EntropyDraft created (slots={}, roles={})", roles.length, rolesStr);
            return new EntropyDraft(cat.getRecipeType(), roles);
        } catch (Exception e) {
            Credit.LOGGER.warn("[CraftPattern] EntropyDraft probe failed: {}", e.toString());
            return new EntropyDraft(cat.getRecipeType(), new int[]{ROLE_INPUT, ROLE_OUTPUT});
        }
    }

    @Override public int            slotCount()         { return slots.length; }
    @Override public IngredientSpec getSlot(int i)      { return slots[i]; }
    @Override public RecipeType<?>  recipeType()        { return jeiType; }

    @Override
    public boolean isOutputSlot(int i) {
        return i >= 0 && i < slotRoles.length && slotRoles[i] == ROLE_OUTPUT;
    }

    @Override
    public SlotKind slotKind(int i) {
        if (i < 0 || i >= slotRoles.length) return SlotKind.ITEM_INPUT;
        return slotRoles[i] == ROLE_OUTPUT ? SlotKind.ITEM_OUTPUT : SlotKind.ITEM_INPUT;
    }

    /** RENDER_ONLY / CATALYST は編集不可。それ以外は default ロジック。 */
    @Override
    public boolean acceptsAt(int slotIndex, IngredientSpec spec) {
        if (slotIndex < 0 || slotIndex >= slotRoles.length) return false;
        if (slotRoles[slotIndex] == ROLE_READONLY) return false;
        return RecipeDraft.super.acceptsAt(slotIndex, spec);
    }

    @Override
    public IngredientSpec getOutput() {
        for (int i = 0; i < slotRoles.length; i++) {
            if (slotRoles[i] == ROLE_OUTPUT && !slots[i].isEmpty()) return slots[i];
        }
        return IngredientSpec.EMPTY;
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
            int n = Math.min(views.size(), slots.length);
            for (int i = 0; i < n; i++) {
                // RENDER_ONLY / CATALYST は読み込まない (destroyed overlay などは emit に乗せたくない)
                if (slotRoles[i] == ROLE_READONLY) continue;
                IngredientSpec spec = readSpecFromView(views.get(i));
                if (!spec.isEmpty()) slots[i] = spec;
            }
            Object recipe = layout.getRecipe();
            if (recipe != null) tryReadMode(recipe);
            return true;
        } catch (Exception e) {
            Credit.LOGGER.warn("[CraftPattern] EntropyDraft.loadFromRecipe failed: {}", e.toString());
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
        for (String mn : new String[]{"getMode", "mode"}) {
            try {
                var m = recipe.getClass().getMethod(mn);
                Object res = m.invoke(recipe);
                if (res != null) {
                    String name = res.toString();
                    if ("HEAT".equalsIgnoreCase(name)) { mode = Mode.HEAT; return; }
                    if ("COOL".equalsIgnoreCase(name)) { mode = Mode.COOL; return; }
                }
            } catch (Exception ignored) {}
        }
    }

    @Override
    public String emit(String recipeId) {
        // 入力 (= 最初の INPUT slot)
        if (firstInputIndex < 0) {
            Credit.LOGGER.info("[CraftPattern] EntropyDraft.emit({}) → null: no INPUT slot in layout", recipeId);
            return null;
        }
        IngredientSpec input = slots[firstInputIndex].unwrap();
        if (input.isEmpty() || !(input instanceof IngredientSpec.Item inIt) || inIt.stack().isEmpty()) {
            Credit.LOGGER.info("[CraftPattern] EntropyDraft.emit({}) → null: input slot[{}] empty", recipeId, firstInputIndex);
            return null;
        }

        // input が BlockItem なら block、それ以外は未対応 (fluid は v2.2+)
        ResourceLocation inputId = BuiltInRegistries.ITEM.getKey(inIt.stack().getItem());
        boolean inputIsBlock = inIt.stack().getItem() instanceof BlockItem;

        StringBuilder sb = new StringBuilder();
        sb.append("    event.custom({\n");
        sb.append("        type: 'ae2:entropy',\n");
        sb.append("        mode: '").append(mode.name().toLowerCase()).append("',\n");
        if (inputIsBlock) {
            sb.append("        input: { block: { id: '").append(inputId).append("' } },\n");
        } else {
            Credit.LOGGER.warn("[CraftPattern] EntropyDraft.emit({}) → null: input '{}' is not a BlockItem (fluid 未対応)",
                recipeId, inputId);
            return null;
        }
        // output slot (ROLE_OUTPUT のみ収集)
        sb.append("        output: {\n");
        boolean firstOutputDone = false;
        StringBuilder drops = new StringBuilder();
        boolean dropsStarted = false;
        for (int i = 0; i < slotRoles.length; i++) {
            if (slotRoles[i] != ROLE_OUTPUT) continue;
            IngredientSpec s = slots[i].unwrap();
            if (s.isEmpty() || !(s instanceof IngredientSpec.Item it) || it.stack().isEmpty()) continue;
            ResourceLocation rl = BuiltInRegistries.ITEM.getKey(it.stack().getItem());
            if (!firstOutputDone && it.stack().getItem() instanceof BlockItem) {
                sb.append("            block: { id: '").append(rl).append("' },\n");
                firstOutputDone = true;
            } else {
                if (dropsStarted) drops.append(",\n");
                drops.append("                ").append(AE2EmitHelper.resultJson(it.stack()));
                dropsStarted = true;
            }
        }
        if (dropsStarted) {
            sb.append("            drops: [\n").append(drops).append("\n            ]\n");
        } else {
            int idx = sb.lastIndexOf(",");
            if (idx > 0) sb.deleteCharAt(idx);
        }
        sb.append("        }\n");
        sb.append("    }).id('").append(recipeId).append("');\n");
        return sb.toString();
    }

    @Override
    public String relativeOutputPath() {
        return "generated/ae2/entropy.js";
    }
}
