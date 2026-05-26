package DIV.credit.client.runtime.emi;

import DIV.credit.client.draft.IngredientSpec;
import DIV.credit.client.runtime.CreditCategory;
import DIV.credit.client.runtime.CreditRecipe;
import DIV.credit.client.runtime.CreditRuntimeBackend;
import DIV.credit.client.runtime.CreditSlot;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.fml.ModList;
import org.jetbrains.annotations.Nullable;

import java.util.Collections;
import java.util.List;
import java.util.Optional;

/**
 * EMI backend stub (Phase 1)。
 * <p>Phase 3 で実装: {@code EmiApi} 経由で recipe / category / slot probe (= WidgetHolder recorder) を行い、
 * {@code EmiPlugin} entry も別 file ({@code EmiCreditPlugin}) で追加する。
 * Phase 1 では isAvailable のみ正しく返す stub。
 */
public final class EmiBackend implements CreditRuntimeBackend {

    @Override public String  id()           { return "emi"; }

    @Override public boolean isAvailable()  { return ModList.get().isLoaded("emi"); }

    // ──────────────── Phase 3 で実装 ────────────────

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
