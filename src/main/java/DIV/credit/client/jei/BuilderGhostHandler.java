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
        // StackBuilder helper widget も drop ターゲットに（item/fluid/gas 全部受け入れ）
        Rect2i sbArea = gui.getStackBuilderArea();
        if (sbArea != null) {
            targets.add(new StackBuilderTarget<>(sbArea, ingredient.getType(), gui));
        }
        // TagBar の CFG slot も drop ターゲットに（item/fluid/gas 全部受け入れ。option は未設定状態で）
        Rect2i cfgArea = gui.getTagBarCfgArea();
        if (cfgArea != null) {
            targets.add(new TagBarCfgTarget<>(cfgArea, ingredient.getType(), gui));
        }
        // TagBar の Result slot も drop ターゲットに（finder mode 起動: item/fluid のみ）
        Rect2i resultArea = gui.getTagBarResultArea();
        if (resultArea != null
            && (testSpec instanceof IngredientSpec.Item || testSpec instanceof IngredientSpec.Fluid)) {
            targets.add(new TagBarResultTarget<>(resultArea, ingredient.getType(), gui));
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

    /** I 型 → IngredientSpec 変換（item / fluid / gas 対応）。fluid/gas 量は config の default で上書き。 */
    private static <T> IngredientSpec toSpec(IIngredientType<T> type, T value) {
        Optional<ItemStack> itemOpt = VanillaTypes.ITEM_STACK.castIngredient(value);
        if (itemOpt.isPresent()) return RecipeArea.ingredientFromCursor(itemOpt.get());
        Optional<FluidStack> fluidOpt = ForgeTypes.FLUID_STACK.castIngredient(value);
        if (fluidOpt.isPresent()) {
            FluidStack fs = fluidOpt.get().copy();
            fs.setAmount(DIV.credit.CreditConfig.FLUID_DEFAULT_AMOUNT.get());
            return IngredientSpec.ofFluid(fs);
        }
        if (HAS_MEK) {
            IngredientSpec gasSpec = DIV.credit.client.jei.mek.MekanismIngredientAdapter.tryGas(type, value);
            if (gasSpec instanceof IngredientSpec.Gas g && g.gasId() != null) {
                return IngredientSpec.ofGas(g.gasId(), DIV.credit.CreditConfig.GAS_DEFAULT_AMOUNT.get());
            }
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

    /** TagBar Result slot への ghost drop target（finder mode 起動）。 */
    private static final class TagBarResultTarget<I> implements Target<I> {
        private final Rect2i area;
        private final IIngredientType<I> type;
        private final BuilderScreen gui;

        TagBarResultTarget(Rect2i area, IIngredientType<I> type, BuilderScreen gui) {
            this.area = area;
            this.type = type;
            this.gui  = gui;
        }

        @Override public Rect2i getArea() { return area; }

        @Override
        public void accept(I ingredient) {
            IngredientSpec spec = toSpec(type, ingredient);
            Credit.LOGGER.info("[CraftPattern] ghost drag ACCEPT: tagBarResult spec={}",
                spec.isEmpty() ? "EMPTY" : spec.getClass().getSimpleName());
            if (!spec.isEmpty()) gui.getTagBar().setFinderSource(spec);
        }
    }

    /** TagBar CFG slot への ghost drop target。 */
    private static final class TagBarCfgTarget<I> implements Target<I> {
        private final Rect2i area;
        private final IIngredientType<I> type;
        private final BuilderScreen gui;

        TagBarCfgTarget(Rect2i area, IIngredientType<I> type, BuilderScreen gui) {
            this.area = area;
            this.type = type;
            this.gui  = gui;
        }

        @Override public Rect2i getArea() { return area; }

        @Override
        public void accept(I ingredient) {
            IngredientSpec spec = toSpec(type, ingredient);
            Credit.LOGGER.info("[CraftPattern] ghost drag ACCEPT: tagBarCfg spec={}",
                spec.isEmpty() ? "EMPTY" : spec.getClass().getSimpleName());
            if (!spec.isEmpty()) gui.getTagBar().setCfgContent(spec);
        }
    }

    /** StackBuilder helper widget への ghost drop target。 */
    private static final class StackBuilderTarget<I> implements Target<I> {
        private final Rect2i area;
        private final IIngredientType<I> type;
        private final BuilderScreen gui;

        StackBuilderTarget(Rect2i area, IIngredientType<I> type, BuilderScreen gui) {
            this.area = area;
            this.type = type;
            this.gui  = gui;
        }

        @Override public Rect2i getArea() { return area; }

        @Override
        public void accept(I ingredient) {
            IngredientSpec spec = toSpec(type, ingredient);
            Credit.LOGGER.info("[CraftPattern] ghost drag ACCEPT: stackBuilder spec={}",
                spec.isEmpty() ? "EMPTY" : spec.getClass().getSimpleName());
            if (!spec.isEmpty()) gui.getStackBuilder().setContent(spec);
        }
    }
}