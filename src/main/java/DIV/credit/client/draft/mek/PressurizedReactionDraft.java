package DIV.credit.client.draft.mek;

import DIV.credit.Credit;
import DIV.credit.client.draft.IngredientSpec;
import DIV.credit.client.draft.RecipeDraft;
import DIV.credit.client.jei.mek.MekanismIngredientAdapter;
import mekanism.api.chemical.gas.GasStack;
import mekanism.api.math.FloatingLong;
import mekanism.api.recipes.PressurizedReactionRecipe;
import mekanism.api.recipes.ingredients.ChemicalStackIngredient.GasStackIngredient;
import mekanism.api.recipes.ingredients.FluidStackIngredient;
import mekanism.api.recipes.ingredients.ItemStackIngredient;
import mekanism.api.recipes.ingredients.creator.IngredientCreatorAccess;
import mekanism.common.recipe.impl.PressurizedReactionIRecipe;
import mekanism.common.registries.MekanismGases;
import mezz.jei.api.recipe.RecipeType;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.level.material.Fluids;
import net.minecraftforge.fluids.FluidStack;

import java.util.List;

/**
 * Mekanism Pressurized Reaction Chamber 用 Draft。
 * JEI スロット並び（PressurizedReactionRecipeCategory.setRecipe より）：
 *   0 = item input, 1 = fluid input, 2 = gas input,
 *   3 = item output, 4 = gas output
 */
public class PressurizedReactionDraft implements RecipeDraft {

    public static final int IDX_INPUT_ITEM   = 0;
    public static final int IDX_INPUT_FLUID  = 1;
    public static final int IDX_INPUT_GAS    = 2;
    public static final int IDX_OUTPUT_ITEM  = 3;
    public static final int IDX_OUTPUT_GAS   = 4;
    public static final int SLOT_COUNT       = 5;

    private static long DRAFT_COUNTER = 0;

    private final RecipeType<?>     jeiType;
    private final IngredientSpec[]  slots = new IngredientSpec[SLOT_COUNT];
    private int  duration       = 100;
    private long energyRequired = 0;

    public PressurizedReactionDraft(RecipeType<?> jeiType) {
        this.jeiType = jeiType;
        for (int i = 0; i < SLOT_COUNT; i++) slots[i] = IngredientSpec.EMPTY;
    }

    @Override public int slotCount() { return SLOT_COUNT; }
    @Override public IngredientSpec getSlot(int i) { return slots[i]; }
    @Override public IngredientSpec getOutput() { return slots[IDX_OUTPUT_ITEM]; }
    @Override public RecipeType<?> recipeType() { return jeiType; }

    @Override
    public SlotKind slotKind(int i) {
        return switch (i) {
            case IDX_INPUT_ITEM   -> SlotKind.ITEM_INPUT;
            case IDX_INPUT_FLUID  -> SlotKind.FLUID_INPUT;
            case IDX_INPUT_GAS    -> SlotKind.GAS_INPUT;
            case IDX_OUTPUT_ITEM  -> SlotKind.ITEM_OUTPUT;
            case IDX_OUTPUT_GAS   -> SlotKind.GAS_OUTPUT;
            default -> SlotKind.ITEM_INPUT;
        };
    }

    @Override
    public void setSlot(int i, IngredientSpec s) {
        if (i < 0 || i >= SLOT_COUNT) return;
        if (s == null) s = IngredientSpec.EMPTY;
        if (!acceptsAt(i, s)) return;
        slots[i] = s;
    }

    @Override
    public List<NumericField> numericFields() {
        return List.of(
            new NumericField("Duration", NumericField.Kind.INT, () -> duration,       v -> duration = (int) v,       1, 1_000_000),
            new NumericField("Energy",   NumericField.Kind.INT, () -> energyRequired, v -> energyRequired = (long) v, 0, Integer.MAX_VALUE)
        );
    }

    @Override
    public Recipe<?> toRecipeInstance() {
        ItemStack inItem  = extractItem(slots[IDX_INPUT_ITEM]);
        FluidStack inFluid = extractFluid(slots[IDX_INPUT_FLUID]);
        GasStack inGas    = MekanismIngredientAdapter.toGasStack(slots[IDX_INPUT_GAS]);
        ItemStack outItem = extractItem(slots[IDX_OUTPUT_ITEM]);
        GasStack  outGas  = MekanismIngredientAdapter.toGasStack(slots[IDX_OUTPUT_GAS]);

        // 何も編集してない → fallback で既存レシピを表示させる
        boolean anyEdit = !inItem.isEmpty() || !inFluid.isEmpty() || !inGas.isEmpty()
                       || !outItem.isEmpty() || !outGas.isEmpty();
        if (!anyEdit) return null;

        try {
            // Mek の IngredientCreator は empty を弾くので placeholder を入れる (BARRIER/water/hydrogen)。
            // ユーザーが触ったスロットだけは本物が入り、未編集はプレースホルダで「空」と一目で分かる。
            ItemStackIngredient  itemIng  = IngredientCreatorAccess.item().from(inItem.isEmpty()
                ? new ItemStack(Items.BARRIER) : inItem);
            FluidStackIngredient fluidIng = IngredientCreatorAccess.fluid().from(inFluid.isEmpty()
                ? new FluidStack(Fluids.WATER, 1) : inFluid);
            GasStackIngredient   gasIng   = IngredientCreatorAccess.gas().from(inGas.isEmpty()
                ? new GasStack(MekanismGases.HYDROGEN.get(), 1) : inGas);

            // PressurizedReactionRecipe は出力 1 個以上必須。両方空なら preview 用に barrier を出力に。
            ItemStack outItemForRecipe = outItem;
            GasStack  outGasForRecipe  = outGas;
            if (outItemForRecipe.isEmpty() && outGasForRecipe.isEmpty()) {
                outItemForRecipe = new ItemStack(Items.BARRIER);
            }

            return new PressurizedReactionIRecipe(

                    new ResourceLocation(Credit.MODID, "draft_reaction_" + (++DRAFT_COUNTER)),
                itemIng, fluidIng, gasIng,
                FloatingLong.create(energyRequired),
                duration,
                outItemForRecipe,
                outGasForRecipe);
        } catch (Exception e) {
            Credit.LOGGER.warn("[CraftPattern] PressurizedReactionDraft toRecipeInstance failed: {}", e.toString());
            return null; // fallback to existing recipe
        }
    }

    @Override
    public String relativeOutputPath() {
        return "generated/mekanism/reaction.js";
    }

    @Override
    public String emit(String recipeId) {
        ItemStack inItem  = extractItem(slots[IDX_INPUT_ITEM]);
        FluidStack inFluid = extractFluid(slots[IDX_INPUT_FLUID]);
        IngredientSpec inGasSpec = slots[IDX_INPUT_GAS];
        ItemStack outItem = extractItem(slots[IDX_OUTPUT_ITEM]);
        IngredientSpec outGasSpec = slots[IDX_OUTPUT_GAS];

        if (inItem.isEmpty() || inFluid.isEmpty()
            || !(inGasSpec instanceof IngredientSpec.Gas inG) || inG.gasId() == null) return null;
        if (outItem.isEmpty()
            && !(outGasSpec instanceof IngredientSpec.Gas oG && oG.gasId() != null)) return null;

        ResourceLocation inItemId  = BuiltInRegistries.ITEM.getKey(inItem.getItem());
        ResourceLocation inFluidId = BuiltInRegistries.FLUID.getKey(inFluid.getFluid());

        StringBuilder sb = new StringBuilder();
        sb.append("    event.recipes.mekanism.reaction({\n");
        sb.append("        itemInput: '").append(inItemId).append("',\n");
        sb.append("        fluidInput: Fluid.of('").append(inFluidId).append("', ").append(inFluid.getAmount()).append("),\n");
        sb.append("        gasInput: '").append(inG.gasId()).append(" ").append(inG.amount()).append("',\n");
        if (!outItem.isEmpty()) {
            ResourceLocation outItemId = BuiltInRegistries.ITEM.getKey(outItem.getItem());
            String outStr = outItem.getCount() <= 1 ? "'" + outItemId + "'"
                : "Item.of('" + outItemId + "', " + outItem.getCount() + ")";
            sb.append("        itemOutput: ").append(outStr).append(",\n");
        }
        if (outGasSpec instanceof IngredientSpec.Gas oG && oG.gasId() != null) {
            sb.append("        gasOutput: '").append(oG.gasId()).append(" ").append(oG.amount()).append("',\n");
        }
        sb.append("        duration: ").append(duration).append(",\n");
        sb.append("        energyRequired: ").append(energyRequired).append("\n");
        sb.append("    }).id('").append(recipeId).append("');\n");
        return sb.toString();
    }

    private static ItemStack extractItem(IngredientSpec s) {
        if (s instanceof IngredientSpec.Item it && !it.stack().isEmpty()) return it.stack().copy();
        return ItemStack.EMPTY;
    }

    private static FluidStack extractFluid(IngredientSpec s) {
        if (s instanceof IngredientSpec.Fluid fl && !fl.stack().isEmpty()) return fl.stack().copy();
        return FluidStack.EMPTY;
    }
}