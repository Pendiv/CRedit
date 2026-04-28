
package DIV.credit;

import com.mojang.logging.LogUtils;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.config.ModConfig;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import org.slf4j.Logger;

@Mod(Credit.MODID)
public class Credit {

    public static final String MODID = "credit";
    public static final Logger LOGGER = LogUtils.getLogger();

    public Credit() {
        IEventBus modEventBus = FMLJavaModLoadingContext.get().getModEventBus();
        ModLoadingContext.get().registerConfig(ModConfig.Type.CLIENT, CreditConfig.SPEC, "credit-client.toml");
        // Forge runtime event bus: ワールド退出で undo 履歴クリア
        MinecraftForge.EVENT_BUS.register(WorldEvents.class);
    }

    /** Forge event subscriber: client が world を抜けた時 UndoHistory を空にする。 */
    public static final class WorldEvents {
        @SubscribeEvent
        public static void onLogout(ClientPlayerNetworkEvent.LoggingOut event) {
            DIV.credit.client.undo.UndoHistory.INSTANCE.clear();
        }
    }
}