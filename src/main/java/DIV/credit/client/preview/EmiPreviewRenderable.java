package DIV.credit.client.preview;

import DIV.credit.Credit;
import dev.emi.emi.api.widget.Bounds;
import dev.emi.emi.api.widget.Widget;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.tooltip.ClientTooltipComponent;
import net.minecraft.client.gui.screens.inventory.tooltip.DefaultTooltipPositioner;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

/**
 * v3.4.x: EMI {@code List<Widget>} (= {@code EmiRecipe.addWidgets} 産物) を
 * {@link PreviewRenderable} として描画する impl。
 *
 * <h3>描画:</h3>
 * <ol>
 *   <li>{@link #setPosition(int, int)} で抽出した offset を保持</li>
 *   <li>{@link #render} で pose stack 平行移動 → 各 widget の {@code render} 呼出 → 復元</li>
 *   <li>tooltip は最上層 hover で 1 個だけ描画 (= 通常の EMI 挙動と同じ)</li>
 * </ol>
 *
 * <h3>contract:</h3>
 * <ul>
 *   <li>widgets の各 Bounds の x/y は recipe 内 ローカル座標 (= 0-based、 0..displayWidth)</li>
 *   <li>setPosition で受けた offset を pose stack で平行移動して描画する</li>
 *   <li>mouseClicked 等は dispatch しない (= preview は表示専用)</li>
 * </ul>
 */
public final class EmiPreviewRenderable implements PreviewRenderable {

    private static final int PADDING = 6;

    private final List<Widget> widgets;
    private final int recipeWidth;
    private final int recipeHeight;
    private int posX, posY;

    public EmiPreviewRenderable(List<Widget> widgets, int recipeWidth, int recipeHeight) {
        this.widgets      = widgets != null ? widgets : List.of();
        this.recipeWidth  = recipeWidth;
        this.recipeHeight = recipeHeight;
    }

    @Override public int getWidth()  { return recipeWidth  + PADDING * 2; }
    @Override public int getHeight() { return recipeHeight + PADDING * 2; }

    @Override
    public void setPosition(int x, int y) {
        this.posX = x;
        this.posY = y;
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        // pose stack で local 座標系 (= recipe (0,0) が posX+PADDING, posY+PADDING) に平行移動
        var pose = g.pose();
        int ox = posX + PADDING;
        int oy = posY + PADDING;
        pose.pushPose();
        pose.translate(ox, oy, 0);

        // mouse 座標も local 化
        int localMouseX = mouseX - ox;
        int localMouseY = mouseY - oy;

        try {
            // pass 1: widget 群本体 (= slot/text/texture/arrow 等の通常描画)
            for (Widget w : widgets) {
                try { w.render(g, localMouseX, localMouseY, partialTick); }
                catch (Throwable t) {
                    Credit.LOGGER.debug("[CraftPattern] EmiPreviewRenderable.render widget {} failed: {}",
                        w.getClass().getSimpleName(), t.toString());
                }
            }
        } finally {
            pose.popPose();
        }

        // pass 2: tooltip (= 最上層 hover 1 個のみ。 local 座標で hit test、 描画は absolute)
        Widget hovered = findHovered(localMouseX, localMouseY);
        if (hovered != null) {
            try {
                List<ClientTooltipComponent> tooltip = hovered.getTooltip(localMouseX, localMouseY);
                if (tooltip != null && !tooltip.isEmpty()) {
                    renderClientTooltip(g, net.minecraft.client.Minecraft.getInstance().font,
                        tooltip, mouseX, mouseY);
                }
            } catch (Throwable t) {
                Credit.LOGGER.debug("[CraftPattern] EmiPreviewRenderable.tooltip failed: {}", t.toString());
            }
        }
    }

    /**
     * {@code GuiGraphics.renderTooltipInternal} (= private/package, 1.20.1) を reflection で呼ぶ。
     * 失敗時は何もしない (= preview の本体描画は OK のため tooltip 不在で degrade)。
     */
    private static Method renderTooltipInternalMethod;
    private static boolean renderTooltipInternalResolved = false;

    private static void renderClientTooltip(GuiGraphics g, Font font,
                                            List<ClientTooltipComponent> components,
                                            int x, int y) {
        Method m = resolveRenderTooltipInternal();
        if (m == null) return;
        try {
            m.invoke(g, font, components, x, y, DefaultTooltipPositioner.INSTANCE);
        } catch (Throwable t) {
            Credit.LOGGER.debug("[CraftPattern] renderTooltipInternal invoke failed: {}", t.toString());
        }
    }

    private static synchronized Method resolveRenderTooltipInternal() {
        if (renderTooltipInternalResolved) return renderTooltipInternalMethod;
        renderTooltipInternalResolved = true;
        try {
            // 1.20.1 Mojmap: GuiGraphics.renderTooltipInternal(Font, List<ClientTooltipComponent>, int, int, ClientTooltipPositioner)
            Class<?> positioner = Class.forName("net.minecraft.client.gui.screens.inventory.tooltip.ClientTooltipPositioner");
            Method m = GuiGraphics.class.getDeclaredMethod(
                "renderTooltipInternal", Font.class, List.class, int.class, int.class, positioner);
            m.setAccessible(true);
            renderTooltipInternalMethod = m;
        } catch (Throwable t) {
            Credit.LOGGER.warn("[C1014] EmiPreviewRenderable: renderTooltipInternal not found ({}). Tooltip disabled in preview.", t.toString());
        }
        return renderTooltipInternalMethod;
    }

    /** widgets を後ろから (= 上層から) 走査、 bounds.contains で最初に hit した widget を返す。 */
    private Widget findHovered(int lx, int ly) {
        // 上層優先 = list の終端から走査
        for (int i = widgets.size() - 1; i >= 0; i--) {
            Widget w = widgets.get(i);
            try {
                Bounds b = w.getBounds();
                if (b == null) continue;
                if (b.contains(lx, ly)) return w;
            } catch (Throwable ignored) {}
        }
        return null;
    }

    /** 描画 widget 数 (= 主に debug 用)。 */
    public int widgetCount() {
        return widgets.size();
    }

    /** internal: widget list の defensive copy。 */
    public List<Widget> getWidgets() {
        return new ArrayList<>(widgets);
    }
}
