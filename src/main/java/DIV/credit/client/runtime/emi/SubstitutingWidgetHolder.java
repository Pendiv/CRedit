package DIV.credit.client.runtime.emi;

import DIV.credit.Credit;
import dev.emi.emi.api.stack.EmiIngredient;
import dev.emi.emi.api.widget.Bounds;
import dev.emi.emi.api.widget.SlotWidget;
import dev.emi.emi.api.widget.TankWidget;
import dev.emi.emi.api.widget.Widget;
import dev.emi.emi.api.widget.WidgetHolder;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * v3.4.x: {@code EmiRecipe.addWidgets(WidgetHolder)} 呼出時に、 SlotWidget / TankWidget の
 * 中身を {@code substitutions} (= slot index → EmiIngredient) で差替える WidgetHolder impl。
 *
 * <h3>動作:</h3>
 * <ol>
 *   <li>{@link #add} が呼ばれる度に {@code slotIndex} を進めながら widget 種別判定</li>
 *   <li>{@link SlotWidget} (= {@link TankWidget} 含む) で substitution が存在する場合、
 *       同位置に新 widget を構築して return (= 呼出側 chain で .output(true) 等の設定が
 *       新 widget に乗る)</li>
 *   <li>substitution 無し or 他 widget は pass-through で {@link #widgets} に記録</li>
 * </ol>
 *
 * <h3>制約:</h3>
 * <ul>
 *   <li>SlotWidget の {@code stack} は final → 直接書換不可、 同位置で再構築方式を採用</li>
 *   <li>TankWidget の capacity は private、 reflection 失敗時は SlotWidget で fallback</li>
 *   <li>Mek chemical は EmiStack 化不可 → substitution null 戻り→ 元 widget 維持 (= SubstitutingWidgetHolder で pass-through)</li>
 *   <li>slotIndex は addSlot 呼出順 (= 元 category の layout 順) を前提</li>
 * </ul>
 */
public final class SubstitutingWidgetHolder implements WidgetHolder {

    private final int width;
    private final int height;
    /** slot index → 上書き用 EmiIngredient。 null 値 / map に key 無し → 上書きせず元のまま。 */
    private final Map<Integer, EmiIngredient> substitutions;
    /** 出力 widget list (= 元順、 substitute 済の物含む)。 */
    private final List<Widget> widgets = new ArrayList<>();
    /** 次の SlotWidget に与える index (= addSlot 呼出順)。 */
    private int slotIndex = 0;

    public SubstitutingWidgetHolder(int width, int height, Map<Integer, EmiIngredient> substitutions) {
        this.width  = width;
        this.height = height;
        this.substitutions = substitutions;
    }

    @Override public int getWidth()  { return width; }
    @Override public int getHeight() { return height; }

    @Override
    public <T extends Widget> T add(T widget) {
        if (widget == null) return null;

        // TankWidget → SlotWidget 順 (= TankWidget extends SlotWidget なので先判定必須)
        if (widget instanceof TankWidget tw) {
            int idx = slotIndex++;
            EmiIngredient subst = substitutions.get(idx);
            if (subst != null) {
                T newWidget = trySubstituteTank(tw, subst, idx);
                if (newWidget != null) { widgets.add(newWidget); return newWidget; }
            }
            widgets.add(tw);
            return widget;
        }

        if (widget instanceof SlotWidget sw) {
            int idx = slotIndex++;
            EmiIngredient subst = substitutions.get(idx);
            if (subst != null) {
                T newWidget = trySubstituteSlot(sw, subst, idx);
                if (newWidget != null) { widgets.add(newWidget); return newWidget; }
            }
            widgets.add(sw);
            return widget;
        }

        widgets.add(widget);
        return widget;
    }

    @SuppressWarnings("unchecked")
    private <T extends Widget> T trySubstituteSlot(SlotWidget original, EmiIngredient subst, int idx) {
        try {
            Bounds b = original.getBounds();
            SlotWidget fresh = new SlotWidget(subst, b.x(), b.y());
            // 元 widget の output/catalyst flag を反映 (= preview の表示で「output 大 slot」 を維持)
            if (isOutput(original))   fresh = fresh.large(true);
            if (isCatalyst(original)) fresh = fresh.catalyst(true);
            return (T) fresh;
        } catch (Throwable t) {
            Credit.LOGGER.debug("[CraftPattern] SubstitutingWidgetHolder.trySubstituteSlot idx={} failed: {}", idx, t.toString());
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    private <T extends Widget> T trySubstituteTank(TankWidget original, EmiIngredient subst, int idx) {
        try {
            Bounds b = original.getBounds();
            // capacity は private final → reflection 失敗時 SlotWidget で fallback
            long capacity = readLongFieldQuiet(original, "capacity", 1000L);
            TankWidget fresh = new TankWidget(subst, b.x(), b.y(), b.width(), b.height(), capacity);
            return (T) fresh;
        } catch (Throwable t) {
            // fallback: SlotWidget で代替 (= 容量 bar 失うが visual はある程度維持)
            try {
                Bounds b = original.getBounds();
                SlotWidget fresh = new SlotWidget(subst, b.x(), b.y());
                return (T) fresh;
            } catch (Throwable t2) {
                Credit.LOGGER.debug("[CraftPattern] SubstitutingWidgetHolder.trySubstituteTank idx={} fallback failed: {}", idx, t2.toString());
                return null;
            }
        }
    }

    private static boolean isOutput(SlotWidget sw) {
        try {
            var f = SlotWidget.class.getDeclaredField("output");
            f.setAccessible(true);
            return f.getBoolean(sw);
        } catch (Throwable t) { return false; }
    }

    private static boolean isCatalyst(SlotWidget sw) {
        try {
            var f = SlotWidget.class.getDeclaredField("catalyst");
            f.setAccessible(true);
            return f.getBoolean(sw);
        } catch (Throwable t) { return false; }
    }

    private static long readLongFieldQuiet(Object obj, String name, long fallback) {
        try {
            var f = obj.getClass().getDeclaredField(name);
            f.setAccessible(true);
            return f.getLong(obj);
        } catch (Throwable t) { return fallback; }
    }

    /** 全 widget (= 元順、 substitute 済) 読み取り。 */
    public List<Widget> getWidgets() {
        return widgets;
    }

    /** addSlot 呼出回数 (= 描画 slot 総数)。 */
    public int slotCount() {
        return slotIndex;
    }
}
