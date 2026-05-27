package DIV.credit.client.preview;

import DIV.credit.Credit;
import DIV.credit.jei.CraftPatternJeiPlugin;
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

    /**
     * 主 entry point。 user-facing なので chat 通知あり。
     *
     * <p>v3.4.x: JEI を base 経路 (= mandatory)。 EMI viewer 経路は
     * {@code CreditConfig.EMI_VIEWER_INTEGRATION} が ON + EMI 在 + JEI 経路失敗時 fallback。
     * 通常運用では JEI 経路で常に成功する想定。
     */
    public static void show(PreviewSource source) {
        if (source == null) { notifyFail("PreviewSource is null"); return; }

        IRecipeCategory<?> category = source.getCategory();
        if (category == null) {
            notifyFail("category 解決失敗: " + safeLabel(source));
            return;
        }
        Component label = source.getLabel();
        if (label == null) label = Component.literal("untitled preview");

        // 1. JEI path (= base、 通常ここで成功)
        if (isJeiReady()) {
            Object recipe = source.getRecipeObject();
            if (recipe != null) {
                IRecipeLayoutDrawable<?> drawable = JeiRenderBridge.build(category, recipe);
                if (drawable != null) {
                    PreviewWindowManager.INSTANCE.open(label, new JeiPreviewRenderable(drawable));
                    return;
                }
                Credit.LOGGER.info("[CraftPattern] PreviewBus: JEI drawable build failed, trying EMI fallback");
            } else {
                Credit.LOGGER.info("[CraftPattern] PreviewBus: recipe object null, trying EMI fallback");
            }
        }

        // 2. EMI fallback (= EMI 在 + viewer integration ON 時のみ)
        if (DIV.credit.CreditConfig.isEmiIntegrationEnabled()
            && source instanceof DraftPreviewSource dps) {
            try {
                PreviewRenderable emi = DIV.credit.client.runtime.emi.EmiPreviewBridge.build(
                    dps.getDraft(), category);
                if (emi != null) {
                    PreviewWindowManager.INSTANCE.open(label, emi);
                    return;
                }
            } catch (Throwable t) {
                Credit.LOGGER.warn("[C3007] PreviewBus EMI path threw: {}", t.toString());
            }
        }

        notifyFail("preview 構築失敗: " + safeLabel(source));
    }

    private static boolean isJeiReady() {
        try { return CraftPatternJeiPlugin.runtime != null; }
        catch (Throwable t) { return false; }
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
