package DIV.credit.client.tab;

import DIV.credit.Credit;
import mezz.jei.api.gui.drawable.IDrawable;
import mezz.jei.api.helpers.IGuiHelper;
import mezz.jei.api.recipe.IRecipeManager;
import mezz.jei.api.recipe.category.IRecipeCategory;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

import java.util.List;

public class CategoryTab {

    public static final int W = 24;
    public static final int H = 24;

    private static final ResourceLocation TEX_SELECTED   = new ResourceLocation(Credit.MODID, "textures/ui/tab_selected.png");
    private static final ResourceLocation TEX_UNSELECTED = new ResourceLocation(Credit.MODID, "textures/ui/tab_unselected.png");

    public final IRecipeCategory<?> category;
    private final IDrawable icon;
    private int x, y;

    public CategoryTab(IRecipeCategory<?> cat, IRecipeManager rm, IGuiHelper gh) {
        this.category = cat;
        this.icon = CategoryIconResolver.resolve(cat, rm, gh);
    }

    public void setPos(int x, int y) {
        this.x = x;
        this.y = y;
    }

    public boolean isMouseOver(double mx, double my) {
        return mx >= x && my >= y && mx < x + W && my < y + H;
    }

    public void draw(GuiGraphics g, boolean selected) {
        ResourceLocation tex = selected ? TEX_SELECTED : TEX_UNSELECTED;
        g.blit(tex, x, y, 0, 0, W, H, W, H);
        int iconX = x + (W - icon.getWidth()) / 2;
        int iconY = y + (H - icon.getHeight()) / 2;
        icon.draw(g, iconX, iconY);
    }

    public List<Component> tooltip() {
        Component title = category.getTitle();
        ResourceLocation uid = category.getRecipeType().getUid();
        return List.of(
            title != null ? title : Component.literal(uid.toString()),
            Component.literal(uid.toString()).withStyle(net.minecraft.ChatFormatting.DARK_GRAY)
        );
    }
}