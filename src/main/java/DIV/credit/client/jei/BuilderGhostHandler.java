package DIV.credit.client.jei;

import DIV.credit.Credit;
import DIV.credit.client.draft.IngredientSpec;
import DIV.credit.client.recipe.RecipeArea;
import DIV.credit.client.screen.BuilderScreen;
import mezz.jei.api.constants.VanillaTypes;
import mezz.jei.api.forge.ForgeTypes;
import mezz.jei.api.gui.handlers.IGhostIngredientHandler;
import mezz.jei.api.ingredients.IIngredientType;
import mezz.jei.api.ingredients.ITypedIngredient;
import net.minecraft.client.renderer.Rect2i;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.fluids.FluidStack;
import net.minecraftforge.fml.ModList;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

/**
 * JEI 右パネルからアイテム/流体を drag → BuilderScreen の対応スロットに drop。
 * Item と Fluid 両対応。タグはタグバー経由（ここでは扱わない）。
 */
public class BuilderGhostHandler implements IGhostIngredientHandler<BuilderScreen> {

    @Override
    public <I> List<Target<I>> getTargetsTyped(BuilderScreen gui, ITypedIngredient<I> ingredient, boolean doStart) {
        IngredientSpec testSpec = toSpec(ingredient.getType(), ingredient.getIngredient());
        // doStart=true は drag 開始時の 1 回。doStart=false は hover 中。drag 開始時のみログ。
        if (doStart) {
            Credit.LOGGER.info("[CraftPattern] ghost drag START: ingredientType={} (class={}) → spec={}",
                ingredient.getType().getUid(),
                ingredient.getIngredient().getClass().getSimpleName(),
                testSpec.isEmpty() ? "EMPTY" : testSpec.getClass().getSimpleName());
        }
        if (testSpec.isEmpty()) {
            if (doStart) Credit.LOGGER.info("[CraftPattern] no spec for type → 0 targets");
            return Collections.emptyList();
        }

        RecipeArea area = gui.getRecipeArea();
        if (area.getDraft() == null) {
            if (doStart) Credit.LOGGER.info("[CraftPattern] no draft for current category → 0 targets");
            return Collections.emptyList();
        }

        List<Target<I>> targets = new ArrayList<>();
        int rejected = 0;
        for (RecipeArea.GhostTargetInfo info : area.collectGhostTargets()) {
            if (!area.getDraft().acceptsAt(info.slotIndex(), testSpec)) { rejected++; continue; }
            targets.add(new SlotTarget<>(info.screenArea(), ingredient.getType(), info.slotIndex(), area));
        }
        if (doStart) {
            Credit.LOGGER.info("[CraftPattern] ghost drag offered {} targets ({} rejected by acceptsAt)",
                targets.size(), rejected);
        }
        return targets;
    }

    @Override
    public void onComplete() {}

    private static final boolean HAS_MEK = ModList.get().isLoaded("mekanism");

    /** I 型 → IngredientSpec 変換（item / fluid / gas 対応）。 */
    private static <T> IngredientSpec toSpec(IIngredientType<T> type, T value) {
        Optional<ItemStack> itemOpt = VanillaTypes.ITEM_STACK.castIngredient(value);
        if (itemOpt.isPresent()) return RecipeArea.ingredientFromCursor(itemOpt.get());
        Optional<FluidStack> fluidOpt = ForgeTypes.FLUID_STACK.castIngredient(value);
        if (fluidOpt.isPresent()) return IngredientSpec.ofFluid(fluidOpt.get());
        if (HAS_MEK) {
            IngredientSpec gasSpec = DIV.credit.client.jei.mek.MekanismIngredientAdapter.tryGas(type, value);
            if (gasSpec != null && !gasSpec.isEmpty()) return gasSpec;
        }
        return IngredientSpec.EMPTY;
    }

    private static final class SlotTarget<I> implements Target<I> {
        private final Rect2i area;
        private final IIngredientType<I> type;
        private final int slotIndex;
        private final RecipeArea recipeArea;

        SlotTarget(Rect2i area, IIngredientType<I> type, int slotIndex, RecipeArea recipeArea) {
            this.area       = area;
            this.type       = type;
            this.slotIndex  = slotIndex;
            this.recipeArea = recipeArea;
        }

        @Override public Rect2i getArea() { return area; }

        @Override
        public void accept(I ingredient) {
            IngredientSpec spec = toSpec(type, ingredient);
            Credit.LOGGER.info("[CraftPattern] ghost drag ACCEPT: slot[{}] spec={}",
                slotIndex, spec.isEmpty() ? "EMPTY" : spec.getClass().getSimpleName());
            if (!spec.isEmpty()) recipeArea.setSlotIngredient(slotIndex, spec);
        }
    }
}