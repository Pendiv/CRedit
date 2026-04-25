package DIV.credit.poc;

import mezz.jei.api.gui.builder.IIngredientAcceptor;
import mezz.jei.api.gui.builder.IRecipeLayoutBuilder;
import mezz.jei.api.gui.builder.IRecipeSlotBuilder;
import mezz.jei.api.gui.widgets.ISlottedWidgetFactory;
import mezz.jei.api.recipe.RecipeIngredientRole;

import java.util.ArrayList;
import java.util.List;

/**
 * IRecipeLayoutBuilder の記録モック。IRecipeCategory.setRecipe() に渡して、
 * カテゴリが宣言したスロット情報を吸い出す。
 */
public class RecordingLayoutBuilder implements IRecipeLayoutBuilder {

    public final List<RecordingSlotBuilder> slots = new ArrayList<>();
    public boolean shapeless = false;
    public Integer shapelessX;
    public Integer shapelessY;
    public Integer transferButtonX;
    public Integer transferButtonY;
    public int focusLinkCount = 0;

    @Override
    public IRecipeSlotBuilder addSlot(RecipeIngredientRole role) {
        RecordingSlotBuilder s = new RecordingSlotBuilder(role, false);
        slots.add(s);
        return s;
    }

    @SuppressWarnings({"deprecation", "removal"})
    @Override
    public IRecipeSlotBuilder addSlotToWidget(RecipeIngredientRole role, ISlottedWidgetFactory<?> widgetFactory) {
        RecordingSlotBuilder s = new RecordingSlotBuilder(role, false);
        s.notes.add("widget=" + widgetFactory.getClass().getSimpleName());
        slots.add(s);
        return s;
    }

    @Override
    public IIngredientAcceptor<?> addInvisibleIngredients(RecipeIngredientRole role) {
        RecordingSlotBuilder s = new RecordingSlotBuilder(role, true);
        slots.add(s);
        return s;
    }

    @Override
    public void moveRecipeTransferButton(int posX, int posY) {
        this.transferButtonX = posX;
        this.transferButtonY = posY;
    }

    @Override
    public void setShapeless() {
        this.shapeless = true;
    }

    @Override
    public void setShapeless(int posX, int posY) {
        this.shapeless = true;
        this.shapelessX = posX;
        this.shapelessY = posY;
    }

    @Override
    public void createFocusLink(IIngredientAcceptor<?>... linkedSlots) {
        focusLinkCount++;
    }
}