package DIV.credit.client.tab;

import mezz.jei.api.gui.drawable.IDrawable;
import mezz.jei.api.helpers.IGuiHelper;
import mezz.jei.api.ingredients.ITypedIngredient;
import mezz.jei.api.recipe.IRecipeManager;
import mezz.jei.api.recipe.RecipeType;
import mezz.jei.api.recipe.category.IRecipeCategory;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;

import java.util.Optional;

/**
 * Adapted from JEI's RecipeCategoryIconUtil (MIT, mezz).
 * Same resolution priority: getIcon() → first catalyst → text fallback.
 */
public final class CategoryIconResolver {

    private CategoryIconResolver() {}

    public static <T> IDrawable resolve(IRecipeCategory<T> category, IRecipeManager rm, IGuiHelper gh) {
        IDrawable icon = category.getIcon();
        if (icon != null) return icon;

        RecipeType<T> rt = category.getRecipeType();
        Optional<ITypedIngredient<?>> first = rm.createRecipeCatalystLookup(rt).get().findFirst();
        if (first.isPresent()) {
            return gh.createDrawableIngredient(first.get());
        }
        return textFallback(category.getTitle());
    }

    private static IDrawable textFallback(Component title) {
        String src = title == null ? "?" : title.getString();
        String text = src.length() >= 2 ? src.substring(0, 2) : (src.isEmpty() ? "?" : src);
        return new IDrawable() {
            @Override public int getWidth()  { return 16; }
            @Override public int getHeight() { return 16; }
            @Override public void draw(GuiGraphics g, int x, int y) {
                g.drawString(Minecraft.getInstance().font, text, x + 1, y + 4, 0xFFE0E0E0, false);
            }
        };
    }
}