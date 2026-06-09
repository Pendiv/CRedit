package DIV.credit.client.input;

import DIV.credit.jei.CraftPatternJeiPlugin;
import mezz.jei.api.runtime.IIngredientListOverlay;
import mezz.jei.api.runtime.IJeiRuntime;

/**
 * JEI ingredient list overlay の検索 bar が focus 状態か判定する。
 * <p>v4.1.x: 旧 reflection 実装 (= {@code searchField} private field を name ヒューリスティックで探索) を
 * 撤廃し、 JEI 公式 API {@link IIngredientListOverlay#hasKeyboardFocus()} に置換。
 * <p>旧実装は focus 判定が外れる場合があり、 その瞬間「文字入力 (charTyped) は通るが Backspace/Delete
 * (keyPressed) だけ {@link KeyInterceptor} に cancel される」= 検索文字の削除のみ不能、 という症状を招いた。
 * 公式 API は JEI 自身が key 消費判定に使う focus 状態をそのまま返すため、 我々の gate が JEI の挙動と完全同期する。
 * <p>取得不能時 (= runtime/overlay 未初期化) は false (= safe default、 credit 側 keybind 継続)。
 */
public final class JeiSearchFocusHelper {

    private JeiSearchFocusHelper() {}

    public static boolean isJeiSearchFocused() {
        try {
            IJeiRuntime rt = CraftPatternJeiPlugin.runtime;
            if (rt == null) return false;
            IIngredientListOverlay overlay = rt.getIngredientListOverlay();
            return overlay != null && overlay.hasKeyboardFocus();
        } catch (Throwable t) {
            return false;
        }
    }
}
