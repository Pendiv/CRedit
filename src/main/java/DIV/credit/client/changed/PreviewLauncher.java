package DIV.credit.client.changed;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.Nullable;

/**
 * v3.8: ViewMode 1 つから対応する preview Screen を開く共通エントリ。
 *  ChangedJeiScreen / EditComparisonScreen / CreditCommand 全部ここを呼ぶ。
 */
public final class PreviewLauncher {

    private PreviewLauncher() {}

    /** v3.8 互換: 未 push view を開く。 */
    public static void open(@Nullable Screen parent, ChangedRecipeCollector.ViewMode mode) {
        open(parent, mode, null);
    }

    /**
     * v3.10: 時間軸付き起動。
     * @param pushTs null なら未 push view、 non-null なら過去 push の payload から表示。
     */
    public static void open(@Nullable Screen parent, ChangedRecipeCollector.ViewMode mode,
                            @Nullable Long pushTs) {
        var mc = Minecraft.getInstance();
        mc.tell(() -> {
            var data = ChangedRecipeCollector.collect(mode.kind(), pushTs);
            Component title = Component.translatable(switch (mode) {
                case USER_EDIT   -> "gui.credit.preview.edit.title";
                case USER_ADD    -> "gui.credit.preview.add.title";
                case IMPORT_ADD  -> "gui.credit.preview.add_import.title";
                case IMPORT_EDIT -> "gui.credit.preview.edit_import.title";
            });
            Screen next = mode.kind() == DIV.credit.client.io.ScriptWriter.OperationKind.EDIT
                ? new EditComparisonScreen(parent, title, data, mode, pushTs)
                : new ChangedJeiScreen(parent, title, data, mode, pushTs);
            mc.setScreen(next);
        });
    }
}
