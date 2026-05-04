package DIV.credit.client.draft;

import mezz.jei.api.recipe.RecipeType;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * jei:fuel カテゴリの draft。input item + 燃焼時間 (tick)。
 * <p>KubeJS emit: {@code event.fuel('item:id', burnTime)}
 * <p>JEI 表示は item icon + 燃焼時間 — recipe ではないが KubeJS は単独 fuel 登録 API を持つ。
 */
public final class FuelDraft implements RecipeDraft {

    public static final int IDX_INPUT  = 0;
    public static final int IDX_OUTPUT = 1;  // result slot 用 (実は使わないが JEI layout に合わせる)
    public static final int SLOT_COUNT = 2;

    /** JEI category UID — credit が認識する形 (実物は jei:fuel)。 */
    public static final ResourceLocation JEI_TYPE = new ResourceLocation("jei", "fuel");

    /** 仮の RecipeType. credit 内部判定用、JEI には影響しない。 */
    public static final RecipeType<?> RECIPE_TYPE = new RecipeType<>(JEI_TYPE, Object.class);

    private final IngredientSpec[] slots = new IngredientSpec[SLOT_COUNT];
    private int burnTime = 200;  // default 1 石炭 = 1600 tick だが控えめに

    public FuelDraft() {
        for (int i = 0; i < SLOT_COUNT; i++) slots[i] = IngredientSpec.EMPTY;
    }

    @Override public int            slotCount()          { return SLOT_COUNT; }
    @Override public IngredientSpec getSlot(int i)       { return slots[i]; }
    @Override public boolean        isOutputSlot(int i)  { return false; }  // fuel に output 概念なし
    @Override public IngredientSpec getOutput()          { return slots[IDX_INPUT]; }  // recipeId 自動生成用に input を返す
    @Override public RecipeType<?>  recipeType()         { return RECIPE_TYPE; }
    @Override public SlotKind       slotKind(int i)      { return SlotKind.ITEM_INPUT; }

    @Override
    public void setSlot(int i, IngredientSpec s) {
        if (i < 0 || i >= SLOT_COUNT) return;
        if (s == null) s = IngredientSpec.EMPTY;
        if (s instanceof IngredientSpec.Tag) return;  // fuel は item 限定
        slots[i] = s;
    }

    @Override
    public int slotMaxCount(int slotIndex) {
        return 1;  // fuel input は count 概念なし
    }

    /** 燃焼時間 (tick) を numeric field として expose。label「Burn Time」。 */
    @Override
    public List<NumericField> numericFields() {
        return List.of(
            new NumericField("BurnTime", NumericField.Kind.INT,
                () -> (double) burnTime,
                v -> burnTime = (int) Math.max(1, v),
                1, Integer.MAX_VALUE)
        );
    }

    @Override
    public net.minecraft.world.item.crafting.Recipe<?> toRecipeInstance() {
        return null;  // FALLBACK 表示で OK (JEI fuel category が default 描画)
    }

    @Override
    public String emit(String recipeId) {
        IngredientSpec input = slots[IDX_INPUT].unwrap();
        if (!(input instanceof IngredientSpec.Item it) || it.stack().isEmpty()) return null;
        ResourceLocation rl = BuiltInRegistries.ITEM.getKey(it.stack().getItem());
        return "    event.fuel('" + rl + "', " + burnTime + ");\n";
    }

    @Override
    public String relativeOutputPath() {
        return "generated/jei/fuel.js";
    }

    @Override
    public boolean loadFromRecipe(mezz.jei.api.gui.IRecipeLayoutDrawable<?> layout) {
        // JEI fuel recipe は IFuelRecipe 形式。reflection で input + burnTime 抽出。
        Object recipe = layout.getRecipe();
        if (recipe == null) return false;
        // IRecipeSlotsView から input item 取得
        try {
            var views = layout.getRecipeSlotsView().getSlotViews();
            if (!views.isEmpty()) {
                var v = views.get(0);
                v.getDisplayedIngredient().ifPresent(ti -> {
                    Object obj = ti.getIngredient();
                    if (obj instanceof net.minecraft.world.item.ItemStack stack && !stack.isEmpty()) {
                        slots[IDX_INPUT] = new IngredientSpec.Item(stack.copy());
                    }
                });
            }
            // burnTime — JEI fuel recipe は class に getBurnTime() を持つ (mezz.jei.library.plugins.vanilla.fuel.FuelRecipe)
            try {
                var m = recipe.getClass().getMethod("getBurnTime");
                Object res = m.invoke(recipe);
                if (res instanceof Integer bt) burnTime = bt;
            } catch (Exception ignored) {}
            return true;
        } catch (Exception e) {
            return false;
        }
    }
}
