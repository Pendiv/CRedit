package DIV.credit.jei;

import DIV.credit.Credit;
import DIV.credit.client.jei.BuilderGhostHandler;
import DIV.credit.client.jei.BuilderGuiContainerHandler;
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
        // v3.4.x: BuilderScreen + preview windows を JEI sidebar 描画から exclude
        // JEMI bridge が EMI 側にも auto-forward (= 両 viewer 共通対応)
        registration.addGuiContainerHandler(BuilderScreen.class, new BuilderGuiContainerHandler());
    }

    @Override
    public void onRuntimeAvailable(IJeiRuntime jeiRuntime) {
        runtime = jeiRuntime;
        Credit.LOGGER.info("[CraftPattern] JEI runtime available");
        // Phase 2a 検証: backend が runtime 受領後に正しく category 列挙できるか
        try {
            var backend = DIV.credit.client.runtime.BackendRegistry.active();
            if ("jei".equals(backend.id())) {
                int n = backend.getCategories().size();
                Credit.LOGGER.info("[CraftPattern] JeiBackend smoke test: {} categories enumerated", n);
            }
        } catch (Throwable t) {
            Credit.LOGGER.warn("[C2014] CraftPatternJeiPlugin: JeiBackend smoke test failed: {}", t.toString());
        }
    }
}
