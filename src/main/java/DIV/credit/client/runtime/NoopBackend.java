package DIV.credit.client.runtime;

import DIV.credit.client.draft.IngredientSpec;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.renderer.Rect2i;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.Nullable;

import java.util.Collections;
import java.util.List;
import java.util.Optional;

/**
 * fallback backend (= JEI も EMI も無い時)。
 * 全 method 安全 default 返却。 credit 機能は事実上 disable される。
 * runtime check (= {@link DIV.credit.RuntimeViewerCheck}) が player join 時に chat warn を出す。
 */
public final class NoopBackend implements CreditRuntimeBackend {

    public static final NoopBackend INSTANCE = new NoopBackend();

    private NoopBackend() {}

    @Override public String  id()           { return "noop"; }
    @Override public boolean isAvailable()  { return false; }

    @Override public List<CreditCategory> getCategories()                                   { return Collections.emptyList(); }
    @Override public List<CreditRecipe>   getRecipes(CreditCategory category)               { return Collections.emptyList(); }
    @Override public Optional<CreditCategory> findCategory(ResourceLocation uid)            { return Optional.empty(); }
    @Override public List<CreditSlot>     probeSlots(CreditRecipe recipe)                   { return Collections.emptyList(); }

    @Override public boolean isSearchFocused()                  { return false; }
    @Override public String  getSearchText()                    { return ""; }
    @Override public void    setSearchText(String text)         {}
    @Override public @Nullable IngredientSpec hoveredIngredient(int mouseX, int mouseY) { return null; }

    @Override public void showRecipesFor(IngredientSpec spec) {}
    @Override public void showUsesOf(IngredientSpec spec) {}

    @Override public void registerExclusionArea(Class<? extends Screen> screenClass, ExclusionAreaProvider provider) {}
}
