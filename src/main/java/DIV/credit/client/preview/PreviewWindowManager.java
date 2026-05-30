package DIV.credit.client.preview;

import DIV.credit.Credit;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * v3.0.0 (S08): Preview window の lifecycle と配置を管理する singleton。
 * <p>host Screen (= BuilderScreen or PreviewHost) は本 manager の
 * renderAll / mouseClickedAny を delegation で呼ぶ。
 */
public final class PreviewWindowManager {

    public static final PreviewWindowManager INSTANCE = new PreviewWindowManager();

    private static final int CASCADE_DX = 24;
    private static final int CASCADE_DY = 24;
    private static final int MARGIN_FROM_SCREEN_EDGE = 8;

    private final List<PreviewWindow> windows = new ArrayList<>();

    private PreviewWindowManager() {}

    // ─── public API (PreviewBus から呼ばれる) ───

    public void open(Component title, PreviewRenderable renderable) {
        PreviewWindow win = new PreviewWindow(title, renderable);
        windows.add(win);
        repositionAll();
        ensureHostScreen();
        Credit.LOGGER.info("[CraftPattern] PreviewWindow open: {} (count={})",
            title != null ? title.getString() : "(null)", windows.size());
    }

    /** PreviewWindow.mouseClicked の close button から。 */
    public void close(PreviewWindow win) {
        if (windows.remove(win)) {
            Credit.LOGGER.info("[CraftPattern] PreviewWindow closed (remaining={})", windows.size());
            repositionAll();
            if (windows.isEmpty()) {
                Minecraft mc = Minecraft.getInstance();
                if (mc.screen instanceof PreviewHost) {
                    mc.setScreen(null);
                }
            }
        }
    }

    /** PreviewHost の「全部閉じる」 button / ESC から。 */
    public void closeAll() {
        int n = windows.size();
        windows.clear();
        Credit.LOGGER.info("[CraftPattern] All {} preview windows closed", n);
        Minecraft mc = Minecraft.getInstance();
        if (mc.screen instanceof PreviewHost) {
            mc.setScreen(null);
        }
    }

    /** host Screen 外部 close 時の cleanup。 */
    public void clearAll() {
        windows.clear();
    }

    // ─── host Screen からの delegation ───

    public void renderAll(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        for (PreviewWindow w : windows) {
            w.render(g, mouseX, mouseY, partialTick);
        }
    }

    /** 最前面 (= List 末尾) から hit test、 click 通れば前面化。 */
    public boolean mouseClickedAny(double mx, double my, int button) {
        for (int i = windows.size() - 1; i >= 0; i--) {
            PreviewWindow w = windows.get(i);
            if (w.mouseClicked(mx, my, button)) {
                if (windows.contains(w)) {
                    windows.remove(w);
                    windows.add(w);
                }
                return true;
            }
        }
        return false;
    }

    public boolean hasWindows() { return !windows.isEmpty(); }
    public int windowCount()     { return windows.size(); }

    // ─── 配置ロジック ───

    private void repositionAll() {
        Minecraft mc = Minecraft.getInstance();
        Screen screen = mc.screen;
        if (screen == null) return;

        int baseX, baseY;
        if (screen instanceof DIV.credit.client.screen.BuilderScreen builder) {
            // vanilla AbstractContainerScreen の public getter を直接利用
            baseX = builder.getGuiLeft() + builder.getXSize() + MARGIN_FROM_SCREEN_EDGE;
            baseY = MARGIN_FROM_SCREEN_EDGE + 20;
        } else {
            baseX = MARGIN_FROM_SCREEN_EDGE;
            baseY = MARGIN_FROM_SCREEN_EDGE;
        }

        for (int i = 0; i < windows.size(); i++) {
            PreviewWindow w = windows.get(i);
            int x = baseX + CASCADE_DX * i;
            int y = baseY + CASCADE_DY * i;
            int maxX = screen.width  - w.getWidth()  - MARGIN_FROM_SCREEN_EDGE;
            int maxY = screen.height - w.getHeight() - MARGIN_FROM_SCREEN_EDGE;
            if (x > maxX) x = Math.max(MARGIN_FROM_SCREEN_EDGE, maxX);
            if (y > maxY) y = Math.max(MARGIN_FROM_SCREEN_EDGE, maxY);
            w.setPosition(x, y);
        }
    }

    /** host Screen が無ければ PreviewHost を push (parent 保持)。 */
    private void ensureHostScreen() {
        Minecraft mc = Minecraft.getInstance();
        Screen current = mc.screen;
        boolean hostOk = current instanceof DIV.credit.client.screen.BuilderScreen
                      || current instanceof PreviewHost;
        if (!hostOk) {
            mc.setScreen(new PreviewHost(current));
        }
    }
}
