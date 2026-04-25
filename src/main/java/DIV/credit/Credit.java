
package DIV.credit;

import com.mojang.logging.LogUtils;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import org.slf4j.Logger;

@Mod(Credit.MODID)
public class Credit {

    public static final String MODID = "credit";
    public static final Logger LOGGER = LogUtils.getLogger();

    public Credit() {
        IEventBus modEventBus = FMLJavaModLoadingContext.get().getModEventBus();
    }
}