package DIV.credit.client.draft.gt;

import DIV.credit.Credit;
import DIV.credit.client.draft.IngredientSpec;
import DIV.credit.client.draft.RecipeDraft;
import com.gregtechceu.gtceu.api.recipe.GTRecipe;
import com.gregtechceu.gtceu.api.recipe.ingredient.FluidIngredient;
import com.gregtechceu.gtceu.common.data.GTRecipeTypes;
import com.gregtechceu.gtceu.data.recipe.builder.GTRecipeBuilder;
import mezz.jei.api.recipe.RecipeType;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.level.material.Fluid;

import java.util.ArrayList;
import java.util.List;

/**
 * GTCEu ASSEMBLER_RECIPES 用 Draft。
 * setMaxIOSize(9, 1, 1, 0) より：9 item input + 1 item output + 1 fluid input。
 * 想定 JEI スロット並び：0..8 = item inputs、9 = item output、10 = fluid input
 * （ズレた場合は実機ログで slotKind と JEI 順を比較して直す）。
 */
public class GTAssemblerDraft implements RecipeDraft {

    public static final int ITEM_INPUT_COUNT = 9;
    // GT JEI スロット並び（実機ログで判明）：0..8 = item inputs, 9 = fluid input, 10 = item output
    public static final int IDX_FLUID_INPUT  = 9;
    public static final int IDX_ITEM_OUTPUT  = 10;
    public static final int SLOT_COUNT       = 11;

    /** Unique-id counter — JEI/LdLib が ID で recipe drawable をキャッシュする可能性に対応。 */
    private static long DRAFT_COUNTER = 0;

    private final RecipeType<?>     jeiType;
    private final IngredientSpec[]  slots = new IngredientSpec[SLOT_COUNT];
    private int  duration = 200;
    private long EUt      = 30; // LV default

    public GTAssemblerDraft(RecipeType<?> jeiType) {
        this.jeiType = jeiType;
        for (int i = 0; i < SLOT_COUNT; i++) slots[i] = IngredientSpec.EMPTY;
    }

    @Override public int slotCount() { return SLOT_COUNT; }
    @Override public boolean usesGtElectricity() { return true; }
    @Override public IngredientSpec getSlot(int i) { return slots[i]; }
    @Override public IngredientSpec getOutput() { return slots[IDX_ITEM_OUTPUT]; }
    @Override public RecipeType<?> recipeType() { return jeiType; }

    @Override
    public SlotKind slotKind(int i) {
        if (i == IDX_FLUID_INPUT) return SlotKind.FLUID_INPUT;
        if (i == IDX_ITEM_OUTPUT) return SlotKind.ITEM_OUTPUT;
        return SlotKind.ITEM_INPUT;
    }

    @Override
    public void setSlot(int i, IngredientSpec s) {
        if (i < 0 || i >= SLOT_COUNT) return;
        if (s == null) s = IngredientSpec.EMPTY;
        if (!acceptsAt(i, s)) return;
        slots[i] = s;
        // Assembler は shapeless 系。GT widget が recipe input 配列を順序通りに
        // 視覚スロット 0..N-1 に表示するため、draft 側でも item input を pack して
        // 「draft slot index = 視覚 slot index」を維持する。
        if (i >= 0 && i < ITEM_INPUT_COUNT) packItemInputs();
    }

    private void packItemInputs() {
        int writeIdx = 0;
        for (int j = 0; j < ITEM_INPUT_COUNT; j++) {
            if (!slots[j].isEmpty()) {
                if (writeIdx != j) {
                    slots[writeIdx] = slots[j];
                    slots[j] = IngredientSpec.EMPTY;
                }
                writeIdx++;
            }
        }
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
        // 毎回ユニーク ID。同一 ID → 同一レシピ扱いされて UI が更新されない問題を回避。
        GTRecipeBuilder b = GTRecipeTypes.ASSEMBLER_RECIPES.recipeBuilder(
            new ResourceLocation(Credit.MODID, "draft_assembler_" + (++DRAFT_COUNTER)));

        for (int i = 0; i < ITEM_INPUT_COUNT; i++) {
            IngredientSpec spec = slots[i];
            if (spec instanceof IngredientSpec.Item it && !it.stack().isEmpty()) {
                b.inputItems(it.stack());
            } else if (spec instanceof IngredientSpec.Tag tg && tg.tagId() != null) {
                TagKey<Item> tk = TagKey.create(Registries.ITEM, tg.tagId());
                b.inputItems(tk, Math.max(1, tg.count()));
            }
        }

        IngredientSpec out = slots[IDX_ITEM_OUTPUT];
        if (out instanceof IngredientSpec.Item it && !it.stack().isEmpty()) {
            b.outputItems(it.stack());
        }

        IngredientSpec fluid = slots[IDX_FLUID_INPUT];
        if (fluid instanceof IngredientSpec.Fluid fl && !fl.stack().isEmpty()) {
            b.inputFluids(fl.stack());
        } else if (fluid instanceof IngredientSpec.FluidTag ftag && ftag.tagId() != null) {
            TagKey<Fluid> tk = TagKey.create(Registries.FLUID, ftag.tagId());
            b.inputFluids(FluidIngredient.of(tk, ftag.amount()));
        }

        b.duration(duration);
        b.EUt(EUt);
        return b.buildRawRecipe();
    }

    @Override
    public String relativeOutputPath() {
        return "generated/gtceu/assembler.js";
    }

    @Override
    public String emit(String recipeId) {
        IngredientSpec out = slots[IDX_ITEM_OUTPUT];
        String outJs = formatGtItem(out);
        if (outJs == null) return null;

        List<String> itemInputs = new ArrayList<>();
        for (int i = 0; i < ITEM_INPUT_COUNT; i++) {
            String s = formatGtItem(slots[i]);
            if (s != null) itemInputs.add(s);
        }
        if (itemInputs.isEmpty()) return null;

        StringBuilder sb = new StringBuilder();
        sb.append("    event.recipes.gtceu.assembler('").append(recipeId).append("')\n");
        sb.append("        .itemInputs(").append(String.join(", ", itemInputs)).append(")\n");
        IngredientSpec fluid = slots[IDX_FLUID_INPUT];
        if (fluid instanceof IngredientSpec.Fluid fl && !fl.stack().isEmpty()) {
            ResourceLocation rl = BuiltInRegistries.FLUID.getKey(fl.stack().getFluid());
            sb.append("        .inputFluids(Fluid.of('").append(rl).append("', ").append(fl.stack().getAmount()).append("))\n");
        } else if (fluid instanceof IngredientSpec.FluidTag ftag && ftag.tagId() != null) {
            sb.append("        .inputFluids('#").append(ftag.tagId()).append(" ").append(ftag.amount()).append("')\n");
        }
        sb.append("        .itemOutputs(").append(outJs).append(")\n");
        sb.append("        .duration(").append(duration).append(")\n");
        sb.append("        .EUt(").append(EUt).append(");\n");
        return sb.toString();
    }

    /** GT KubeJS 'Nx id' / '#tag' 短形。 */
    private static String formatGtItem(IngredientSpec s) {
        if (s == null || s.isEmpty()) return null;
        int c = Math.max(1, s.count());
        if (s instanceof IngredientSpec.Item it && !it.stack().isEmpty()) {
            ResourceLocation rl = BuiltInRegistries.ITEM.getKey(it.stack().getItem());
            return c <= 1 ? "'" + rl + "'" : "'" + c + "x " + rl + "'";
        }
        if (s instanceof IngredientSpec.Tag tg && tg.tagId() != null) {
            return c <= 1 ? "'#" + tg.tagId() + "'" : "'" + c + "x #" + tg.tagId() + "'";
        }
        return null;
    }
}