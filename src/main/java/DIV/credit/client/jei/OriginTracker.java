package DIV.credit.client.jei;

import mezz.jei.api.recipe.RecipeType;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import org.jetbrains.annotations.Nullable;

/**
 * 「JEI が credit から開かれた」flag をプロセスグローバルに保持。
 * <p>edit/delete ボタンの表示条件 + JEI close 時の parent screen 復元に使う。
 */
public final class OriginTracker {

    private static volatile boolean active = false;
    @Nullable private static volatile RecipeType<?> originCategory = null;
    /** v2.2.5: enter 時に保存した parent screen。ESC で JEI 閉じた時に復元する。 */
    @Nullable private static volatile Screen parentScreen = null;

    private OriginTracker() {}

    /** BuilderScreen 側 Shift+click または HistoryScreen からの recipe 飛びで呼ぶ。 */
    public static void enter(@Nullable RecipeType<?> originCategory) {
        active = true;
        OriginTracker.originCategory = originCategory;
        // 現在の screen (JEI 起動の直前) を parent として記憶。
        // JEI 閉じた後に setScreen(null) になったらここに戻す。
        Minecraft mc = Minecraft.getInstance();
        if (mc != null) parentScreen = mc.screen;
    }

    /** JEI overlay の完全 close を検知した時に呼ぶ。flag 解除。parentScreen は consumeParent で使い終わるまで残す。 */
    public static void exit() {
        active = false;
        originCategory = null;
    }

    /** parentScreen を使い終わった (or 不要になった) 時に呼ぶ。 */
    public static void clearParent() {
        parentScreen = null;
    }

    public static boolean isActive() { return active; }

    @Nullable
    public static RecipeType<?> getOriginCategory() { return originCategory; }

    @Nullable
    public static Screen getParentScreen() { return parentScreen; }
}
