package DIV.credit.client.screen;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;

import java.util.function.LongConsumer;

/**
 * GT EUt 編集行の上に置く「電圧 tier × アンペア」ヘルパー。
 * 2 スロット：
 *  - 左：電圧 tier（ULV..MAX）。クリックで cycle、右クリで逆 cycle。
 *  - 右：アンペア（1/2/4/8/16/32/64/128）。同様に cycle。
 * 値変更時 EUt = V[tier] × amperage を callback で EditBox に書き込む。
 */
public class EnergyHelperWidget {

    public static final int SLOT_W = 18;
    public static final int H      = 18;

    /** GTValues.V と同等。GT クラスを参照しないようローカル定義。 */
    private static final long[] V = {
        8L, 32L, 128L, 512L, 2048L, 8192L, 32768L, 131072L, 524288L,
        2097152L, 8388608L, 33554432L, 134217728L, 536870912L, 2147483648L
    };
    private static final String[] VN = {
        "ULV", "LV", "MV", "HV", "EV", "IV", "LuV", "ZPM", "UV",
        "UHV", "UEV", "UIV", "UXV", "OpV", "MAX"
    };
    private static final int[] AMPERAGES = { 1, 2, 4, 8, 16, 32, 64, 128 };

    private int tierIdx = 1;        // default LV
    private int ampIdxs = 0;        // index into AMPERAGES
    private int tierX, tierY;
    private int ampX,  ampY;
    private boolean visible = false;
    private LongConsumer onEUtChanged;

    public void setBounds(int x, int y) {
        this.tierX = x;
        this.tierY = y;
        this.ampX  = x + SLOT_W + 2;
        this.ampY  = y;
    }

    public void setVisible(boolean v) {
        this.visible = v;
    }

    public boolean isVisible() {
        return visible;
    }

    public void setOnEUtChanged(LongConsumer cb) {
        this.onEUtChanged = cb;
    }

    public void setState(int tierIdx, int ampIdx) {
        this.tierIdx = Math.max(0, Math.min(V.length - 1, tierIdx));
        this.ampIdxs = Math.max(0, Math.min(AMPERAGES.length - 1, ampIdx));
    }

    public int getTierIdx() { return tierIdx; }
    public int getAmpIdx()  { return ampIdxs; }

    /** 現在の EUt 値（V[tier] × amp）。 */
    public long currentEUt() {
        return V[tierIdx] * AMPERAGES[ampIdxs];
    }

    public boolean isOverTier(double mx, double my) {
        return visible && mx >= tierX && mx < tierX + SLOT_W && my >= tierY && my < tierY + SLOT_W;
    }

    public boolean isOverAmp(double mx, double my) {
        return visible && mx >= ampX && mx < ampX + SLOT_W && my >= ampY && my < ampY + SLOT_W;
    }

    /** クリック処理。BuilderScreen から呼ばれる。 */
    public boolean mouseClicked(double mx, double my, int button) {
        if (!visible) return false;
        if (isOverTier(mx, my)) {
            if (button == 0) tierIdx = (tierIdx + 1) % V.length;
            else if (button == 1) tierIdx = (tierIdx - 1 + V.length) % V.length;
            else return false;
            notifyChange();
            return true;
        }
        if (isOverAmp(mx, my)) {
            if (button == 0) ampIdxs = (ampIdxs + 1) % AMPERAGES.length;
            else if (button == 1) ampIdxs = (ampIdxs - 1 + AMPERAGES.length) % AMPERAGES.length;
            else return false;
            notifyChange();
            return true;
        }
        return false;
    }

    private void notifyChange() {
        if (onEUtChanged == null) return;
        try {
            onEUtChanged.accept(currentEUt());
        } catch (Exception ex) {
            // 古い callback が空 list を参照する等のケース（カテゴリ遷移後の残留 click 等）。
            // クラッシュさせず、widget を畳んでログだけ残す。
            org.slf4j.LoggerFactory.getLogger("credit").warn(
                "[CraftPattern] EnergyHelper callback failed; hiding widget. ({})", ex.toString());
            this.visible = false;
            this.onEUtChanged = null;
        }
    }

    public void render(GuiGraphics g, Font font, int mouseX, int mouseY) {
        if (!visible) return;
        drawSlot(g, tierX, tierY, isOverTier(mouseX, mouseY));
        drawSlot(g, ampX,  ampY,  isOverAmp(mouseX, mouseY));
        // ラベル：tier 名
        String tierLabel = VN[tierIdx];
        int tw = font.width(tierLabel);
        g.drawString(font, tierLabel, tierX + (SLOT_W - tw) / 2, tierY + (SLOT_W - font.lineHeight) / 2 + 1, 0xFFFFFFFF, true);
        // ラベル：アンペア "Nx" or "NA"
        String ampLabel = AMPERAGES[ampIdxs] + "A";
        int aw = font.width(ampLabel);
        g.drawString(font, ampLabel, ampX + (SLOT_W - aw) / 2, ampY + (SLOT_W - font.lineHeight) / 2 + 1, 0xFFFFFF55, true);
    }

    private static void drawSlot(GuiGraphics g, int sx, int sy, boolean hover) {
        g.fill(sx - 1, sy - 1, sx + SLOT_W + 1, sy + SLOT_W + 1, 0xFF373737);
        g.fill(sx,     sy,     sx + SLOT_W,     sy + SLOT_W,     0xFF555555);
        if (hover) g.fill(sx, sy, sx + SLOT_W, sy + SLOT_W, 0x55FFFFFF);
    }

    public void renderTooltip(GuiGraphics g, Font font, int mouseX, int mouseY) {
        if (!visible) return;
        if (isOverTier(mouseX, mouseY)) {
            g.renderTooltip(font, Component.literal("Voltage tier: " + VN[tierIdx] + " (" + V[tierIdx] + " EU/t)"), mouseX, mouseY);
        } else if (isOverAmp(mouseX, mouseY)) {
            g.renderTooltip(font, Component.literal("Amperage: " + AMPERAGES[ampIdxs] + "A → EUt = " + currentEUt()), mouseX, mouseY);
        }
    }
}
