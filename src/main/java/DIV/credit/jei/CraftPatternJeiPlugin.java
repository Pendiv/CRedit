package DIV.credit.jei;

import DIV.credit.Credit;
import DIV.credit.client.jei.BuilderGhostHandler;
import DIV.credit.client.screen.BuilderScreen;
import mezz.jei.api.IModPlugin;
import mezz.jei.api.JeiPlugin;
import mezz.jei.api.registration.IGuiHandlerRegistration;
import mezz.jei.api.runtime.IJeiRuntime;
import net.minecraft.resources.ResourceLocation;

@JeiPlugin
public class CraftPatternJeiPlugin implements IModPlugin {

    private static final ResourceLocation UID = new ResourceLocation(Credit.MODID, "craftpattern");

    public static volatile IJeiRuntime runtime;

    @Override
    public ResourceLocation getPluginUid() {
        return UID;
    }

    @Override
    public void registerGuiHandlers(IGuiHandlerRegistration registration) {
        registration.addGhostIngredientHandler(BuilderScreen.class, new BuilderGhostHandler());
    }

    @Override
    public void onRuntimeAvailable(IJeiRuntime jeiRuntime) {
        runtime = jeiRuntime;
        Credit.LOGGER.info("[CraftPattern] JEI runtime available");
    }
}