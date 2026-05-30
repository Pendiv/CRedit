package DIV.credit.client.input;

import DIV.credit.Credit;
import DIV.credit.jei.CraftPatternJeiPlugin;
import net.minecraft.client.gui.components.EditBox;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * v3.2.x: JEI ingredient list overlay の検索 bar が focus 状態か判定する。
 * <p>JEI 15.x の {@code mezz.jei.gui.overlay.IngredientListOverlay} に {@code searchField} private
 * field を期待。 reflection で取得、 isFocused() 呼ぶ。 失敗時は false (= safe default、 我々の挙動継続)。
 * <p>用途: BuilderScreen 内で JEI 検索クリック → 入力中の文字が credit 特殊キー (= 数字 / Ctrl+C/V /
 * ↑↓) と混線しないように、 focus 中は credit 側を抑制する。
 */
public final class JeiSearchFocusHelper {

    private static Class<?> OVERLAY_CLASS;
    private static Field    SEARCH_FIELD;
    private static Method   IS_FOCUSED;
    private static boolean  initFailed = false;

    private JeiSearchFocusHelper() {}

    public static boolean isJeiSearchFocused() {
        if (initFailed) return false;
        try {
            var rt = CraftPatternJeiPlugin.runtime;
            if (rt == null) return false;
            Object overlay = rt.getIngredientListOverlay();
            if (overlay == null) return false;
            // 1 回目だけ reflection lookup
            if (OVERLAY_CLASS == null) {
                if (!tryInitReflection(overlay)) {
                    initFailed = true;
                    return false;
                }
            }
            if (!OVERLAY_CLASS.isInstance(overlay)) return false;
            Object field = SEARCH_FIELD.get(overlay);
            if (field == null) return false;
            // EditBox direct
            if (field instanceof EditBox eb) return eb.isFocused();
            // GuiTextFieldFilter 等の wrapper class — isFocused() method 経由
            if (IS_FOCUSED == null) {
                try {
                    IS_FOCUSED = field.getClass().getMethod("isFocused");
                } catch (NoSuchMethodException e) {
                    return false;
                }
            }
            Object r = IS_FOCUSED.invoke(field);
            return r instanceof Boolean b && b;
        } catch (Throwable t) {
            return false;
        }
    }

    /** overlay impl class + searchField を初回 lookup。 候補 class 名を順に試す。 */
    private static boolean tryInitReflection(Object overlay) {
        Class<?> cls = overlay.getClass();
        // JEI overlay class が見つかるまで階層を上がる
        while (cls != null && cls != Object.class) {
            for (Field f : cls.getDeclaredFields()) {
                String name = f.getName().toLowerCase();
                if (name.contains("search") || name.equals("textfield") || name.equals("filtertextfield")) {
                    try {
                        f.setAccessible(true);
                        OVERLAY_CLASS = cls;
                        SEARCH_FIELD = f;
                        Credit.LOGGER.info("[CraftPattern] JeiSearchFocusHelper: found {} in {}",
                            f.getName(), cls.getName());
                        return true;
                    } catch (Throwable ignored) {}
                }
            }
            cls = cls.getSuperclass();
        }
        Credit.LOGGER.info("[CraftPattern] JeiSearchFocusHelper: search field not found in {}",
            overlay.getClass().getName());
        return false;
    }
}
