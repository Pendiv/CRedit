package DIV.credit.client.input;

import DIV.credit.Credit;
import DIV.credit.client.screen.BuilderScreen;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RegisterKeyMappingsEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.lwjgl.glfw.GLFW;

/**
 * v3.0.1: vanilla MC KeyMapping (= 設定 > キー割り当て で credit カテゴリ表示)。
 * <ul>
 *   <li>{@code KEY_OPEN_CRAFTPATTERN} (default O): {@link BuilderScreen} を開く</li>
 *   <li>{@code KEY_PUSH} (default P): {@code /credit push} を発動</li>
 * </ul>
 * <p>vanilla KeyMapping は in-world (= 画面なし) でしか fire しないので
 * BuilderScreen 内挙動 (= SPECIAL_KEYBINDS_ENABLED 経路) とは独立。
 * player は controls 画面で自由に rebind / unbind 可能。
 */
public final class CreditKeyMappings {

    public static final String CATEGORY = "key.categories.credit";

    public static final KeyMapping KEY_OPEN_CRAFTPATTERN = new KeyMapping(
        "key.credit.open", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_O, CATEGORY);
    public static final KeyMapping KEY_PUSH = new KeyMapping(
        "key.credit.push", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_P, CATEGORY);

    private CreditKeyMappings() {}

    @Mod.EventBusSubscriber(modid = Credit.MODID, value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.MOD)
    public static final class ModBus {
        @SubscribeEvent
        public static void onRegisterKeys(RegisterKeyMappingsEvent event) {
            event.register(KEY_OPEN_CRAFTPATTERN);
            event.register(KEY_PUSH);
        }
    }

    @Mod.EventBusSubscriber(modid = Credit.MODID, value = Dist.CLIENT)
    public static final class ForgeBus {
        @SubscribeEvent
        public static void onClientTick(TickEvent.ClientTickEvent event) {
            if (event.phase != TickEvent.Phase.END) return;
            Minecraft mc = Minecraft.getInstance();
            while (KEY_OPEN_CRAFTPATTERN.consumeClick()) {
                mc.setScreen(BuilderScreen.open());
            }
            while (KEY_PUSH.consumeClick()) {
                if (mc.player != null && mc.player.connection != null) {
                    mc.player.connection.sendCommand("credit push");
                }
            }
        }
    }
}
