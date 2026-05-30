package DIV.credit.client.preview;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.Nullable;

/**
 * v3.0.0 (S09): BuilderScreen が開いてない時に {@link PreviewWindowManager} の
 * host になる専用 Screen。
 *
 * <ul>
 *   <li>背景: 半透明 dim (vanilla pause menu 風)</li>
 *   <li>ESC: onClose → closeAll + parent screen 復帰</li>
 *   <li>「全部閉じる」 button: title bar 風に上端右に配置</li>
 *   <li>{@code isPauseScreen=false} (= server 動作を止めない)</li>
 * </ul>
 */
public class PreviewHost extends Screen {

    private static final int BG_COLOR = 0x80101020;
    private static final int CLOSE_ALL_W = 80;
    private static final int CLOSE_ALL_H = 20;
    private static final int CLOSE_ALL_MARGIN = 6;

    @Nullable
    private final Screen parent;

    public PreviewHost(@Nullable Screen parent) {
        super(Component.translatable("gui.credit.preview_host.title"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        super.init();
        int bx = this.width - CLOSE_ALL_W - CLOSE_ALL_MARGIN;
        int by = CLOSE_ALL_MARGIN;
        addRenderableWidget(Button.builder(
                Component.translatable("gui.credit.preview_host.close_all"),
                b -> PreviewWindowManager.INSTANCE.closeAll())
            .bounds(bx, by, CLOSE_ALL_W, CLOSE_ALL_H)
            .build());
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        // dim 背景
        g.fill(0, 0, this.width, this.height, BG_COLOR);
        // hint
        Component hint = Component.translatable("gui.credit.preview_host.hint");
        int hintW = font.width(hint);
        g.drawString(font, hint,
            (this.width - hintW) / 2, CLOSE_ALL_MARGIN + 6, 0xFFC0C0C0, false);
        // close all button 等
        super.render(g, mouseX, mouseY, partialTick);
        // preview windows
        PreviewWindowManager.INSTANCE.renderAll(g, mouseX, mouseY, partialTick);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        // 前面 window 優先
        if (PreviewWindowManager.INSTANCE.mouseClickedAny(mouseX, mouseY, button)) {
            return true;
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public void onClose() {
        PreviewWindowManager.INSTANCE.clearAll();
        Minecraft.getInstance().setScreen(parent);
    }

    @Override
    public boolean isPauseScreen() { return false; }
}
