package DIV.credit.client.draft.gt;

import DIV.credit.Credit;
import DIV.credit.client.draft.IngredientSpec;
import DIV.credit.client.draft.RecipeDraft;
import com.gregtechceu.gtceu.api.recipe.GTRecipe;
import com.gregtechceu.gtceu.common.data.GTRecipeTypes;
import com.gregtechceu.gtceu.data.recipe.builder.GTRecipeBuilder;
import mezz.jei.api.recipe.RecipeType;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.crafting.Recipe;

import java.util.List;

/**
 * GTCEu COMPRESSOR_RECIPES 用 Draft（最 simple な GT レシピ）。
 * 1 item input + 1 item output + duration + EUt（流体・特殊条件なし）。
 * このクラスは GT がロードされている前提。GTSupport 経由でのみ生成される。
 */
public class GTCompressorDraft implements RecipeDraft {

    public static final int IDX_INPUT  = 0;
    public static final int IDX_OUTPUT = 1;
    public static final int SLOT_COUNT = 2;

    private static long DRAFT_COUNTER = 0;

    private final RecipeType<?>     jeiType;
    private final IngredientSpec[]  slots = new IngredientSpec[SLOT_COUNT];
    private int  duration = 200;
    private long EUt      = 2;

    public GTCompressorDraft(RecipeType<?> jeiType) {
        this.jeiType = jeiType;
        for (int i = 0; i < SLOT_COUNT; i++) slots[i] = IngredientSpec.EMPTY;
    }

    @Override public int slotCount() { return SLOT_COUNT; }
    @Override public boolean usesGtElectricity() { return true; }
    @Override public IngredientSpec getSlot(int i) { return slots[i]; }
    @Override public boolean isOutputSlot(int i) { return i == IDX_OUTPUT; }
    @Override public IngredientSpec getOutput() { return slots[IDX_OUTPUT]; }
    @Override public RecipeType<?> recipeType() { return jeiType; }

    @Override
    public void setSlot(int i, IngredientSpec s) {
        if (i < 0 || i >= SLOT_COUNT) return;
        if (s == null) s = IngredientSpec.EMPTY;
        if (isOutputSlot(i) && s instanceof IngredientSpec.Tag) return;
        slots[i] = s;
    }

    @Override
    public List<NumericField> numericFields() {
        return List.of(
            new NumericField("Duration", NumericField.Kind.INT, () -> duration, v -> duration = (int) v, 1, Integer.MAX_VALUE),
            new NumericField("EUt",      NumericField.Kind.INT, () -> EUt,      v -> EUt      = (long) v, 0, Integer.MAX_VALUE)
        );
    }

    @Override
    public Recipe<?> toRecipeInstance() {
        GTRecipeBuilder b = GTRecipeTypes.COMPRESSOR_RECIPES.recipeBuilder(
            new ResourceLocation(Credit.MODID, "draft_compressor_" + (++DRAFT_COUNTER)));

        // input
        IngredientSpec in = slots[IDX_INPUT];
        if (in instanceof IngredientSpec.Item it && !it.stack().isEmpty()) {
            b.inputItems(it.stack());
        } else if (in instanceof IngredientSpec.Tag tg && tg.tagId() != null) {
            TagKey<Item> tk = TagKey.create(Registries.ITEM, tg.tagId());
            b.inputItems(tk, Math.max(1, tg.count()));
        }

        // output
        IngredientSpec out = slots[IDX_OUTPUT];
        if (out instanceof IngredientSpec.Item it && !it.stack().isEmpty()) {
            b.outputItems(it.stack());
        }

        b.duration(duration);
        b.EUt(EUt);

        GTRecipe r = b.buildRawRecipe();
        return r;
    }

    @Override
    public String relativeOutputPath() {
        return "generated/gtceu/compressor.js";
    }

    @Override
    public String emit(String recipeId) {
        IngredientSpec in  = slots[IDX_INPUT];
        IngredientSpec out = slots[IDX_OUTPUT];
        String inJs  = GTEmitFormat.formatItem(in);
        String outJs = GTEmitFormat.formatItem(out);
        if (inJs == null || outJs == null) return null;
        StringBuilder sb = new StringBuilder();
        sb.append("    event.recipes.gtceu.compressor('").append(recipeId).append("')\n");
        if (GTEmitFormat.isCatalyst(in)) {
            // 入力 1 つしかない compressor で catalyst だと recipe として成立しないが、
            // ユーザー指定通り emit する（KubeJS 側でエラーになるなら user の判断）
            sb.append("        .notConsumable(").append(inJs).append(")\n");
        } else {
            sb.append("        .itemInputs(").append(inJs).append(")\n");
        }
        if (GTEmitFormat.isChance(out)) {
            sb.append("        .chancedOutput(").append(outJs).append(", ")
              .append(GTEmitFormat.chanceArgs(out)).append(")\n");
        } else {
            sb.append("        .itemOutputs(").append(outJs).append(")\n");
        }
        sb.append("        .duration(").append(duration).append(")\n");
        sb.append("        .EUt(").append(EUt).append(");\n");
        return sb.toString();
    }
}