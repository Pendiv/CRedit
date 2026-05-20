package DIV.credit.client.preview;

import DIV.credit.Credit;
import mezz.jei.api.gui.IRecipeLayoutDrawable;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Renderable;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.narration.NarratableEntry;
import net.minecraft.client.gui.narration.NarratedElementType;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.renderer.Rect2i;
import net.minecraft.network.chat.Component;

/**
 * v3.0.0 (S07): 1 つの recipe preview を host する Widget。
 * <pre>
 *   ┌──────────────────────────────────┐
 *   │ Title bar                     [X]│  ← title + close
 *   ├──────────────────────────────────┤
 *   │   IRecipeLayoutDrawable          │  ← JEI 描画 (drawRecipe + drawOverlays)
 *   └──────────────────────────────────┘
 * </pre>
 * <p>Screen に直接 add せず {@link PreviewWindowManager} 経由で管理。
 * host Screen は {@code Manager.renderAll(...)} を render() で呼ぶだけ。
 */
public class PreviewWindow implements Renderable, GuiEventListener, NarratableEntry {

    private static final int TITLE_BAR_H = 12;
    private static final int PADDING = 4;
    private static final int CLOSE_BTN_W = 9;
    private static final int CLOSE_BTN_H = 9;
    private static final int BG_COLOR     = 0xCC1A1A2E;
    private static final int BORDER_COLOR = 0xFF4040A0;
    private static final int TITLE_COLOR  = 0xFFE0E0FF;
    private static final int CLOSE_HOVER  = 0xFFFF6060;
    private static final int CLOSE_NORMAL = 0xFFA0A0A0;

    private final Component title;
    private final IRecipeLayoutDrawable<?> drawable;
    private int windowX, windowY;
    private final int windowW, windowH;
    private final int innerX, innerY;
    private boolean focused = false;

    public PreviewWindow(Component title, IRecipeLayoutDrawable<?> drawable) {
        this.title = title;
        this.drawable = drawable;
        Rect2i rect = drawable.getRectWithBorder();
        this.windowW = rect.getWidth() + PADDING * 2;
        this.windowH = rect.getHeight() + TITLE_BAR_H + PADDING * 2;
        this.innerX  = PADDING;
        this.innerY  = TITLE_BAR_H + PADDING;
    }

    public void setPosition(int x, int y) {
        this.windowX = x;
        this.windowY = y;
        drawable.setPosition(x + innerX, y + innerY);
    }

    public int getWidth()  { return windowW; }
    public int getHeight() { return windowH; }
    public int getX()      { return windowX; }
    public int getY()      { return windowY; }

    private Rect2i getCloseButtonRect() {
        int bx = windowX + windowW - CLOSE_BTN_W - 2;
        int by = windowY + (TITLE_BAR_H - CLOSE_BTN_H) / 2;
        return new Rect2i(bx, by, CLOSE_BTN_W, CLOSE_BTN_H);
    }

    private boolean isCloseHovered(double mx, double my) {
        Rect2i r = getCloseButtonRect();
        return mx >= r.getX() && mx < r.getX() + r.getWidth()
            && my >= r.getY() && my < r.getY() + r.getHeight();
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        // 1. 背景 + 枠
        g.fill(windowX, windowY, windowX + windowW, windowY + windowH, BG_COLOR);
        g.renderOutline(windowX, windowY, windowW, windowH, BORDER_COLOR);

        // 2. title bar separator
        g.fill(windowX, windowY + TITLE_BAR_H - 1,
               windowX + windowW, windowY + TITLE_BAR_H, BORDER_COLOR);

        // 3. title text
        Font font = Minecraft.getInstance().font;
        int titleX = windowX + PADDING;
        int titleY = windowY + (TITLE_BAR_H - font.lineHeight) / 2;
        Component truncated = truncateTitle(font, title, windowW - PADDING * 2 - CLOSE_BTN_W - 4);
        g.drawString(font, truncated, titleX, titleY, TITLE_COLOR, false);

        // 4. close button (✕ 文字)
        Rect2i closeR = getCloseButtonRect();
        int closeColor = isCloseHovered(mouseX, mouseY) ? CLOSE_HOVER : CLOSE_NORMAL;
        g.drawString(font, "✕", closeR.getX() + 1, closeR.getY(), closeColor, false);

        // 5. JEI drawable: tick → drawRecipe → drawOverlays
        try { drawable.tick(); }
        catch (Exception e) { Credit.LOGGER.warn("[CraftPattern] PreviewWindow.tick failed: {}", e.getMessage()); }
        try { drawable.drawRecipe(g, mouseX, mouseY); }
        catch (Exception e) { Credit.LOGGER.warn("[CraftPattern] PreviewWindow.drawRecipe failed: {}", e.getMessage()); }
        try { drawable.drawOverlays(g, mouseX, mouseY); }
        catch (Exception e) { Credit.LOGGER.warn("[CraftPattern] PreviewWindow.drawOverlays failed: {}", e.getMessage()); }
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button != 0) return false;
        if (isCloseHovered(mouseX, mouseY)) {
            PreviewWindowManager.INSTANCE.close(this);
            return true;
        }
        // window 内部 click は drawable に渡さない (= 見るだけ)、 ただし窓内は capture
        return isMouseOver(mouseX, mouseY);
    }

    public boolean isMouseOver(double mx, double my) {
        return mx >= windowX && mx < windowX + windowW
            && my >= windowY && my < windowY + windowH;
    }

    @Override public void setFocused(boolean f) { this.focused = f; }
    @Override public boolean isFocused()        { return focused; }

    @Override public NarratableEntry.NarrationPriority narrationPriority() {
        return focused ? NarrationPriority.FOCUSED : NarrationPriority.NONE;
    }
    @Override public void updateNarration(NarrationElementOutput out) {
        out.add(NarratedElementType.TITLE, title);
    }

    private static Component truncateTitle(Font font, Component src, int maxW) {
        String s = src.getString();
        if (font.width(s) <= maxW) return src;
        while (s.length() > 3 && font.width(s + "...") > maxW) {
            s = s.substring(0, s.length() - 1);
        }
        return Component.literal(s + "...");
    }
}
