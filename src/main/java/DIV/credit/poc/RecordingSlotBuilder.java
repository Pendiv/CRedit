package DIV.credit.poc;

import mezz.jei.api.gui.builder.IRecipeSlotBuilder;
import mezz.jei.api.gui.drawable.IDrawable;
import mezz.jei.api.gui.ingredient.IRecipeSlotRichTooltipCallback;
import mezz.jei.api.gui.ingredient.IRecipeSlotTooltipCallback;
import mezz.jei.api.ingredients.IIngredientRenderer;
import mezz.jei.api.ingredients.IIngredientType;
import mezz.jei.api.ingredients.ITypedIngredient;
import mezz.jei.api.recipe.RecipeIngredientRole;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.material.Fluid;
import net.minecraftforge.fluids.FluidStack;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * IRecipeSlotBuilder の記録モック。setRecipe() の呼び出しを蓄積し、後で読み出せるようにする。
 * 装飾系メソッドは記録のみで戻り値は this。
 */
public class RecordingSlotBuilder implements IRecipeSlotBuilder {

    public record IngredientEntry(IIngredientType<?> type, Object value) {}

    public final RecipeIngredientRole role;
    public final boolean invisible;
    public int x = -1;
    public int y = -1;
    public String slotName;
    public final List<IngredientEntry> ingredients = new ArrayList<>();
    public final List<String> notes = new ArrayList<>();

    public RecordingSlotBuilder(RecipeIngredientRole role, boolean invisible) {
        this.role = role;
        this.invisible = invisible;
    }

    // ───── IPlaceable ─────

    @Override
    public IRecipeSlotBuilder setPosition(int xPos, int yPos) {
        this.x = xPos;
        this.y = yPos;
        return this;
    }

    @Override
    public int getWidth() { return 16; }

    @Override
    public int getHeight() { return 16; }

    // ───── IIngredientAcceptor (abstract methods) ─────

    @Override
    public <I> IRecipeSlotBuilder addIngredients(IIngredientType<I> ingredientType, List<I> list) {
        for (I i : list) {
            if (i != null) ingredients.add(new IngredientEntry(ingredientType, i));
        }
        return this;
    }

    @Override
    public <I> IRecipeSlotBuilder addIngredient(IIngredientType<I> ingredientType, I ingredient) {
        if (ingredient != null) ingredients.add(new IngredientEntry(ingredientType, ingredient));
        return this;
    }

    @Override
    public IRecipeSlotBuilder addIngredientsUnsafe(List<?> list) {
        for (Object o : list) {
            if (o != null) ingredients.add(new IngredientEntry(null, o));
        }
        return this;
    }

    @Override
    public IRecipeSlotBuilder addTypedIngredients(List<ITypedIngredient<?>> list) {
        for (ITypedIngredient<?> ti : list) {
            ingredients.add(new IngredientEntry(ti.getType(), ti.getIngredient()));
        }
        return this;
    }

    @Override
    public IRecipeSlotBuilder addOptionalTypedIngredients(List<Optional<ITypedIngredient<?>>> list) {
        for (Optional<ITypedIngredient<?>> opt : list) {
            opt.ifPresent(ti -> ingredients.add(new IngredientEntry(ti.getType(), ti.getIngredient())));
        }
        return this;
    }

    @Override
    public IRecipeSlotBuilder addFluidStack(Fluid fluid) {
        ingredients.add(new IngredientEntry(null, new FluidStack(fluid, 1000)));
        return this;
    }

    @Override
    public IRecipeSlotBuilder addFluidStack(Fluid fluid, long amount) {
        ingredients.add(new IngredientEntry(null, new FluidStack(fluid, (int) amount)));
        return this;
    }

    @Override
    public IRecipeSlotBuilder addFluidStack(Fluid fluid, long amount, CompoundTag tag) {
        FluidStack fs = new FluidStack(fluid, (int) amount);
        fs.setTag(tag);
        ingredients.add(new IngredientEntry(null, fs));
        return this;
    }

    // ───── IRecipeSlotBuilder (decorations: just record-and-return) ─────

    @SuppressWarnings({"deprecation", "removal"})
    @Override
    public IRecipeSlotBuilder addTooltipCallback(IRecipeSlotTooltipCallback cb) {
        notes.add("tooltipCallback");
        return this;
    }

    @Override
    public IRecipeSlotBuilder addRichTooltipCallback(IRecipeSlotRichTooltipCallback cb) {
        notes.add("richTooltipCallback");
        return this;
    }

    @Override
    public IRecipeSlotBuilder setSlotName(String name) {
        this.slotName = name;
        return this;
    }

    @Override
    public IRecipeSlotBuilder setStandardSlotBackground() {
        notes.add("standardBg");
        return this;
    }

    @Override
    public IRecipeSlotBuilder setOutputSlotBackground() {
        notes.add("outputBg");
        return this;
    }

    @Override
    public IRecipeSlotBuilder setBackground(IDrawable bg, int xOff, int yOff) {
        notes.add("background");
        return this;
    }

    @Override
    public IRecipeSlotBuilder setOverlay(IDrawable overlay, int xOff, int yOff) {
        notes.add("overlay");
        return this;
    }

    @Override
    public IRecipeSlotBuilder setFluidRenderer(long capacity, boolean showCap, int w, int h) {
        notes.add("fluidRenderer(cap=" + capacity + ")");
        return this;
    }

    @Override
    public <T> IRecipeSlotBuilder setCustomRenderer(IIngredientType<T> type, IIngredientRenderer<T> renderer) {
        notes.add("customRenderer");
        return this;
    }

    /** Human-readable summary for chat dump. */
    public String summary() {
        StringBuilder sb = new StringBuilder();
        sb.append('[').append(role).append(']');
        if (invisible) sb.append(" INVISIBLE");
        sb.append(" @(").append(x).append(',').append(y).append(')');
        if (slotName != null) sb.append(" name=").append(slotName);
        if (!ingredients.isEmpty()) {
            sb.append(" ingredients=");
            for (int i = 0; i < ingredients.size(); i++) {
                if (i > 0) sb.append(", ");
                if (i >= 4) { sb.append("…(+").append(ingredients.size() - 4).append(')'); break; }
                IngredientEntry e = ingredients.get(i);
                Object v = e.value();
                if (v instanceof ItemStack is && !is.isEmpty()) {
                    sb.append(is.getCount()).append('x').append(is.getItem().builtInRegistryHolder().key().location());
                } else if (v != null) {
                    sb.append(v);
                } else {
                    sb.append("null");
                }
            }
        }
        if (!notes.isEmpty()) sb.append(" notes=").append(notes);
        return sb.toString();
    }
}