package DIV.credit.client.preview;

import DIV.credit.Credit;
import mezz.jei.api.gui.IRecipeLayoutDrawable;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.Rect2i;

/**
 * v3.4.x: 既存 JEI {@link IRecipeLayoutDrawable} を {@link PreviewRenderable} に薄く wrap。
 * <p>render フローは旧 PreviewWindow.render 内の drawable.tick / drawRecipe / drawOverlays をそのまま移植。
 */
public final class JeiPreviewRenderable implements PreviewRenderable {

    private final IRecipeLayoutDrawable<?> drawable;
    private final int width;
    private final int height;

    public JeiPreviewRenderable(IRecipeLayoutDrawable<?> drawable) {
        this.drawable = drawable;
        Rect2i rect = drawable.getRectWithBorder();
        this.width  = rect.getWidth();
        this.height = rect.getHeight();
    }

    @Override public int getWidth()  { return width; }
    @Override public int getHeight() { return height; }

    @Override
    public void setPosition(int x, int y) {
        drawable.setPosition(x, y);
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        try { drawable.tick(); }
        catch (Exception e) { Credit.LOGGER.warn("[C3001] JeiPreviewRenderable.tick failed: {}", e.getMessage()); }
        try { drawable.drawRecipe(g, mouseX, mouseY); }
        catch (Exception e) { Credit.LOGGER.warn("[C3002] JeiPreviewRenderable.drawRecipe failed: {}", e.getMessage()); }
        try { drawable.drawOverlays(g, mouseX, mouseY); }
        catch (Exception e) { Credit.LOGGER.warn("[C3003] JeiPreviewRenderable.drawOverlays failed: {}", e.getMessage()); }
    }
}
