package DIV.credit.client.tab;

import DIV.credit.Credit;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvents;

import java.util.function.BooleanSupplier;
import java.util.function.IntSupplier;

/**
 * 上部ナビゲーション帯：← / 1/N / → を描画。JEI の PageNavigation に相当する自前実装。
 */
public class PageNav {

    public static final int H         = 20;
    public static final int ARROW_W   = 9;
    public static final int ARROW_H   = 9;
    public static final int ARROW_HIT = 18;

    private static final ResourceLocation TEX_PREV = new ResourceLocation(Credit.MODID, "textures/ui/icons/arrow_previous.png");
    private static final ResourceLocation TEX_NEXT = new ResourceLocation(Credit.MODID, "textures/ui/icons/arrow_next.png");

    private final IntSupplier     pageNumber;
    private final IntSupplier     pageCount;
    private final BooleanSupplier hasPrev;
    private final BooleanSupplier hasNext;
    private final Runnable        onPrev;
    private final Runnable        onNext;

    private int x, y, width;

    public PageNav(IntSupplier pageNumber, IntSupplier pageCount,
                   BooleanSupplier hasPrev, BooleanSupplier hasNext,
                   Runnable onPrev, Runnable onNext) {
        this.pageNumber = pageNumber;
        this.pageCount  = pageCount;
        this.hasPrev    = hasPrev;
        this.hasNext    = hasNext;
        this.onPrev     = onPrev;
        this.onNext     = onNext;
    }

    public void setBounds(int x, int y, int width) {
        this.x = x;
        this.y = y;
        this.width = width;
    }

    public boolean isVisible() {
        return pageCount.getAsInt() > 1;
    }

    public void draw(GuiGraphics g) {
        if (!isVisible()) return;
        Font font = Minecraft.getInstance().font;

        g.fill(x + ARROW_HIT, y, x + width - ARROW_HIT, y + H, 0x30000000);

        String txt = (pageNumber.getAsInt() + 1) + "/" + pageCount.getAsInt();
        int tx = x + (width - font.width(txt)) / 2;
        int ty = y + (H - font.lineHeight) / 2;
        g.drawString(font, txt, tx, ty, 0xFFFFFFFF);

        if (hasPrev.getAsBoolean()) {
            g.blit(TEX_PREV,
                x + (ARROW_HIT - ARROW_W) / 2,
                y + (H - ARROW_H) / 2,
                0, 0, ARROW_W, ARROW_H, ARROW_W, ARROW_H);
        }
        if (hasNext.getAsBoolean()) {
            g.blit(TEX_NEXT,
                x + width - ARROW_HIT + (ARROW_HIT - ARROW_W) / 2,
                y + (H - ARROW_H) / 2,
                0, 0, ARROW_W, ARROW_H, ARROW_W, ARROW_H);
        }
    }

    public boolean mouseClicked(double mx, double my, int button) {
        if (!isVisible() || button != 0) return false;
        if (my < y || my >= y + H) return false;
        if (mx >= x && mx < x + ARROW_HIT && hasPrev.getAsBoolean()) {
            onPrev.run();
            playClick();
            return true;
        }
        if (mx >= x + width - ARROW_HIT && mx < x + width && hasNext.getAsBoolean()) {
            onNext.run();
            playClick();
            return true;
        }
        return false;
    }

    private static void playClick() {
        Minecraft.getInstance().getSoundManager().play(
            SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK, 1.0F));
    }
}