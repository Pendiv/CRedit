package DIV.credit;

import com.mojang.logging.LogUtils;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import org.slf4j.Logger;

/**
 * credit MOD エントリポイント (NeoForge)。
 *
 * <p>1.20.1 (Forge) からの移植・基幹版: client config の登録のみ。
 * 1.20.1 の runtime event-bus 登録 (world logout → UndoHistory.clear /
 * ImmediateHistorySession.Hook / EditDeleteTracker.Hook) は、対応する
 * undo / history / jei subsystem を移植した時点で復元する。
 */
@Mod(Credit.MODID)
public class Credit {

    public static final String MODID = "credit";
    public static final Logger LOGGER = LogUtils.getLogger();

    // FML が IEventBus / ModContainer を constructor に注入する (NeoForge 形式。Forge の no-arg ctor から変更)。
    public Credit(IEventBus modEventBus, ModContainer modContainer) {
        // Forge: ModLoadingContext.get().registerConfig(...) → NeoForge: modContainer.registerConfig(...)
        modContainer.registerConfig(ModConfig.Type.CLIENT, CreditConfig.SPEC, "credit-client.toml");

        // TODO(1.21-port): undo/history/jei subsystem 移植後に復元する runtime event-bus 登録:
        //   NeoForge.EVENT_BUS.register(WorldEvents.class);                                  // logout → UndoHistory.clear()
        //   NeoForge.EVENT_BUS.register(client.history.ImmediateHistorySession.Hook.class);  // immediate-apply flush
        //   NeoForge.EVENT_BUS.register(client.jei.EditDeleteTracker.Hook.class);            // /reload + logout で clear
    }
}
