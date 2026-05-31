package DIV.credit.client.runtime.emi;

import dev.emi.emi.api.widget.SlotWidget;
import dev.emi.emi.api.widget.Widget;
import dev.emi.emi.api.widget.WidgetHolder;

import java.util.ArrayList;
import java.util.List;

/**
 * EmiRecipe.addWidgets 呼出時に追加される widget を記録する WidgetHolder impl。
 * <p>EMI recipe の slot 構造を probe するために使う:
 * <ol>
 *   <li>{@code recorder = new WidgetHolderRecorder(recipe.getDisplayWidth(), recipe.getDisplayHeight())}</li>
 *   <li>{@code recipe.addWidgets(recorder)} 呼出</li>
 *   <li>{@code recorder.getSlots()} で SlotWidget 一覧取得 → CreditSlot に変換</li>
 * </ol>
 * <p>レンダリングは行わない、 単に add 呼出を record する受動的 holder。
 * その他 widget (= TextureWidget / ArrowWidget 等) は無視 (= SlotWidget のみ probe 対象)。
 */
public final class WidgetHolderRecorder implements WidgetHolder {

    private final int width, height;
    private final List<Widget> widgets = new ArrayList<>();

    public WidgetHolderRecorder(int width, int height) {
        this.width = width;
        this.height = height;
    }

    @Override public int getWidth()  { return width; }
    @Override public int getHeight() { return height; }

    @Override
    public <T extends Widget> T add(T widget) {
        if (widget != null) widgets.add(widget);
        return widget;
    }

    /** 記録した全 widget。 read-only view 推奨。 */
    public List<Widget> getWidgets() {
        return widgets;
    }

    /** SlotWidget のみ抽出 (= 順序維持、 元 addSlot 順)。 */
    public List<SlotWidget> getSlots() {
        List<SlotWidget> out = new ArrayList<>();
        for (Widget w : widgets) {
            if (w instanceof SlotWidget sw) out.add(sw);
        }
        return out;
    }
}
