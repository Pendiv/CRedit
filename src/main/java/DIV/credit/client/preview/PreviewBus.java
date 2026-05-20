package DIV.credit.client.preview;

import DIV.credit.Credit;
import mezz.jei.api.gui.IRecipeLayoutDrawable;
import mezz.jei.api.recipe.category.IRecipeCategory;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;

/**
 * v3.0.0 (P1): Preview の dispatch entry。 {@link PreviewSource} を受け取って:
 * <ol>
 *   <li>category + recipe を取得 (= source の責務)</li>
 *   <li>{@link JeiRenderBridge#build} で drawable 生成</li>
 *   <li>{@link PreviewWindowManager#open} に渡して window として表示</li>
 * </ol>
 *
 * <p>state を持たない (= state は WindowManager 側)。 失敗時は user に chat 通知。
 * 全 method は main thread (= render thread) からの呼び出しを想定。
 */
public final class PreviewBus {

    private PreviewBus() {}

    /** 主 entry point。 user-facing なので chat 通知あり。 */
    public static void show(PreviewSource source) {
        if (source == null) { notifyFail("PreviewSource is null"); return; }

        IRecipeCategory<?> category = source.getCategory();
        if (category == null) {
            notifyFail("category 解決失敗: " + safeLabel(source));
            return;
        }
        Object recipe = source.getRecipeObject();
        if (recipe == null) {
            notifyFail("recipe 合成失敗: " + safeLabel(source));
            return;
        }
        IRecipeLayoutDrawable<?> drawable = JeiRenderBridge.build(category, recipe);
        if (drawable == null) {
            notifyFail("drawable 生成失敗: " + safeLabel(source));
            return;
        }
        Component label = source.getLabel();
        if (label == null) label = Component.literal("untitled preview");
        PreviewWindowManager.INSTANCE.open(label, drawable);
    }

    /** 失敗時の user 通知。 chat + log。 */
    private static void notifyFail(String reason) {
        Credit.LOGGER.info("[CraftPattern] preview fail: {}", reason);
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        if (player != null) {
            player.displayClientMessage(
                Component.literal("[CraftPattern] preview 失敗: " + reason)
                    .withStyle(ChatFormatting.RED),
                false);
        }
    }

    private static String safeLabel(PreviewSource source) {
        try {
            Component c = source.getLabel();
            return c != null ? c.getString() : "(no label)";
        } catch (Exception e) {
            return "(label error: " + e.getMessage() + ")";
        }
    }
}
