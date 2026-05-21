package DIV.credit.client.input;

import DIV.credit.Credit;
import DIV.credit.CreditConfig;
import DIV.credit.client.screen.BuilderScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ScreenEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * v3.0.1: BuilderScreen 内のキーイベント横取り。
 * <p>{@link CreditConfig#SPECIAL_KEYBINDS_ENABLED} が ON の時:
 * <ul>
 *   <li>screen.keyPressed (= BuilderScreen 自前 handler + vanilla 共通処理) を手動で呼ぶ</li>
 *   <li>その後 {@code event.setCanceled(true)} で他 MOD listener (JEI quick-lookup 等) を遮断</li>
 * </ul>
 * <p>Pre を cancel すると Screen.keyPressed が走らないため自前で先に呼ぶ。
 * Post も Pre cancel に連動して fire されないので、 これで Pre/Post 両方の他 MOD listener を一括遮断。
 */
@Mod.EventBusSubscriber(modid = Credit.MODID, value = Dist.CLIENT)
public final class KeyInterceptor {

    private KeyInterceptor() {}

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onKeyPressedPre(ScreenEvent.KeyPressed.Pre event) {
        Screen screen = event.getScreen();
        if (!(screen instanceof BuilderScreen)) return;
        if (!safeMaster()) return;
        try {
            screen.keyPressed(event.getKeyCode(), event.getScanCode(), event.getModifiers());
        } catch (Exception e) {
            Credit.LOGGER.warn("[CraftPattern] manual keyPressed dispatch failed", e);
        }
        event.setCanceled(true);
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onKeyReleasedPre(ScreenEvent.KeyReleased.Pre event) {
        Screen screen = event.getScreen();
        if (!(screen instanceof BuilderScreen)) return;
        if (!safeMaster()) return;
        try {
            screen.keyReleased(event.getKeyCode(), event.getScanCode(), event.getModifiers());
        } catch (Exception ignored) {}
        event.setCanceled(true);
    }

    private static boolean safeMaster() {
        try { return CreditConfig.SPECIAL_KEYBINDS_ENABLED.get(); } catch (Exception e) { return true; }
    }
}
