package DIV.credit.client.changed;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;

import java.util.List;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * v3.7: 汎用 HTML &lt;select&gt; 風 dropdown。 任意の T 値リストから 1 つ選ぶ。
 *  閉: 現在値 + ▼ を 1 box で。
 *  開: 直下に values を縦に展開、 click で選択 → 閉じる。
 *  Screen から render/click を委譲。
 */
public class Dropdown<T> {

    private static final int H = 14;
    private static final int ITEM_H = 12;

    private static final int BG_PLATE   = 0xFFC6C6C6;
    private static final int EDGE_LIGHT = 0xFFFFFFFF;
    private static final int EDGE_DARK  = 0xFF555555;
    private static final int TEXT_COLOR = 0xFF202020;
    private static final int HOVER_BG   = 0xFFE0E0FF;

    private final List<T> values;
    private final Function<T, Component> labelFn;
    private final Consumer<T> onChange;
    private final int width;

    private int x, y;
    private boolean open = false;
    private T value;

    public Dropdown(List<T> values, T initial, int width,
                    Function<T, Component> labelFn, Consumer<T> onChange) {
        if (values == null || values.isEmpty()) throw new IllegalArgumentException("values empty");
        this.values = values;
        this.value = initial != null ? initial : values.get(0);
        this.width = width;
        this.labelFn = labelFn;
        this.onChange = onChange;
    }

    public void setPos(int x, int y) { this.x = x; this.y = y; }
    public int width()  { return width; }
    public int height() { return H; }
    public boolean isOpen() { return open; }
    public T value() { return value; }

    public void render(GuiGraphics g, int mouseX, int mouseY) {
        Font font = Minecraft.getInstance().font;
        drawPanel(g, x, y, width, H, !open);
        Component lbl = labelFn.apply(value);
        g.drawString(font, lbl, x + 4, y + (H - font.lineHeight) / 2 + 1, TEXT_COLOR, false);
        String chev = open ? "▲" : "▼";
        int cw = font.width(chev);
        g.drawString(font, chev, x + width - cw - 3, y + (H - font.lineHeight) / 2 + 1, TEXT_COLOR, false);

        if (open) {
            int listH = ITEM_H * values.size() + 2;
            int listY = y + H;
            drawPanel(g, x, listY, width, listH, false);
            for (int i = 0; i < values.size(); i++) {
                int iy = listY + 1 + i * ITEM_H;
                boolean hover = inRect(mouseX, mouseY, x + 1, iy, width - 2, ITEM_H);
                if (hover) g.fill(x + 1, iy, x + width - 1, iy + ITEM_H, HOVER_BG);
                g.drawString(font, labelFn.apply(values.get(i)), x + 4, iy + 2, TEXT_COLOR, false);
            }
        }
    }

    public boolean mouseClicked(double mx, double my) {
        if (open) {
            int listY = y + H;
            for (int i = 0; i < values.size(); i++) {
                int iy = listY + 1 + i * ITEM_H;
                if (inRect(mx, my, x + 1, iy, width - 2, ITEM_H)) {
                    T picked = values.get(i);
                    open = false;
                    if (!picked.equals(value)) {
                        value = picked;
                        if (onChange != null) onChange.accept(picked);
                    }
                    return true;
                }
            }
            if (inRect(mx, my, x, y, width, H)) { open = false; return true; }
            open = false;
            return false;
        }
        if (inRect(mx, my, x, y, width, H)) { open = true; return true; }
        return false;
    }

    private static void drawPanel(GuiGraphics g, int x, int y, int w, int h, boolean raised) {
        int light = raised ? EDGE_LIGHT : EDGE_DARK;
        int dark  = raised ? EDGE_DARK  : EDGE_LIGHT;
        g.fill(x, y, x + w, y + h, BG_PLATE);
        g.fill(x,         y,         x + w,     y + 1,     light);
        g.fill(x,         y,         x + 1,     y + h,     light);
        g.fill(x + w - 1, y,         x + w,     y + h,     dark);
        g.fill(x,         y + h - 1, x + w,     y + h,     dark);
    }

    private static boolean inRect(double mx, double my, int x, int y, int w, int h) {
        return mx >= x && mx < x + w && my >= y && my < y + h;
    }
}
