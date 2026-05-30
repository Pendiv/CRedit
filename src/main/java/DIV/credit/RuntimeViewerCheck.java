package DIV.credit;

import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModList;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;

/**
 * v3.3.x: JEI / EMI のどちらかが必要、 両方無いなら credit 機能 noop + user に通知。
 * <p>mods.toml で両方 mandatory=false にしたため、 forge 自体は止めない。
 * このクラスが起動時 / player join 時に check + warn を出す。
 */
@EventBusSubscriber(modid = Credit.MODID, bus = EventBusSubscriber.Bus.MOD)
public final class RuntimeViewerCheck {

    private RuntimeViewerCheck() {}

    /** どちらか 1 つでも入っていれば true。 backend 選定 / chat warn 判定に使う。 */
    public static boolean hasAnyViewer() {
        return ModList.get().isLoaded("jei") || ModList.get().isLoaded("emi");
    }

    @SubscribeEvent
    public static void onClientSetup(FMLClientSetupEvent event) {
        boolean jei = ModList.get().isLoaded("jei");
        boolean emi = ModList.get().isLoaded("emi");
        if (jei && emi) {
            Credit.LOGGER.info("[CraftPattern] viewer detection: JEI + EMI both loaded (= EMI preferred for UI)");
        } else if (jei) {
            Credit.LOGGER.info("[CraftPattern] viewer detection: JEI only");
        } else if (emi) {
            Credit.LOGGER.info("[CraftPattern] viewer detection: EMI only");
        } else {
            Credit.LOGGER.error("[C901] viewer detection: NEITHER JEI NOR EMI installed. credit features will be disabled.");
        }
        // Phase 1: backend abstraction 初期化 (= active backend 選定)
        // 既存 code path はまだ backend 経由でないので、 init するだけで挙動変化なし。
        DIV.credit.client.runtime.BackendRegistry.init();
    }

    /** player join 時に chat warn 出す (= client 視点)。 viewer 無し時のみ 1 回。 */
    @EventBusSubscriber(modid = Credit.MODID, bus = EventBusSubscriber.Bus.GAME)
    public static final class ChatNotifier {
        @SubscribeEvent
        public static void onPlayerLogin(ClientPlayerNetworkEvent.LoggingIn event) {
            if (hasAnyViewer()) return;
            var p = Minecraft.getInstance().player;
            if (p == null) return;
            p.displayClientMessage(Component.translatable("gui.credit.runtime.no_viewer")
                .withStyle(ChatFormatting.RED), false);
        }
    }
}
