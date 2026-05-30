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

/**
 * credit の JEI plugin。 {@link #runtime} 経由で他層 (DraftStore / GenericDraft / 各 mod Support) が
 * JEI runtime (RecipeManager 等) に到達する。
 *
 * <p><b>【1.21 移植・基幹版 — 2026-05-31】</b> 現状は runtime 受領のみ。 1.20.1 の
 * GUI handler 登録 (BuilderScreen の ghost / container handler) と backend smoke test は、
 * screens / runtime backend 層を移植した時点で復元する。
 */
@JeiPlugin
public class CraftPatternJeiPlugin implements IModPlugin {

    private static final ResourceLocation UID = ResourceLocation.fromNamespaceAndPath(Credit.MODID, "craftpattern");

    public static volatile IJeiRuntime runtime;

    @Override
    public ResourceLocation getPluginUid() {
        return UID;
    }

    @Override
    public void registerGuiHandlers(IGuiHandlerRegistration registration) {
        // JEI 右パネルから BuilderScreen への drag-drop (ghost ingredient) + exclusion area。
        registration.addGhostIngredientHandler(BuilderScreen.class, new BuilderGhostHandler());
        registration.addGuiContainerHandler(BuilderScreen.class, new BuilderGuiContainerHandler());
    }

    @Override
    public void onRuntimeAvailable(IJeiRuntime jeiRuntime) {
        runtime = jeiRuntime;
        Credit.LOGGER.info("[CraftPattern] JEI runtime available");
    }
}
