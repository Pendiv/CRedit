package DIV.credit.client.runtime.jei;

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
 * JEI backend stub (Phase 1)。
 * <p>Phase 2 で実装: {@code CraftPatternJeiPlugin.runtime} を経由して JEI runtime API を呼出、
 * 既存 55 file の {@code mezz.jei.*} 直参照をこの backend 経由に整理する。
 * Phase 1 の現状は全 method noop で、 既存 code は依然として直接 JEI を呼ぶ (= regression なし)。
 */
public final class JeiBackend implements CreditRuntimeBackend {

    @Override public String  id()           { return "jei"; }

    /**
     * JEI mod が読み込まれてれば available。 runtime (= IJeiRuntime) 取得は addRuntime callback 経由なので
     * mod load 直後だと runtime 未 set の場合あり。 Phase 2 で runtime 設定済 check を追加。
     */
    @Override public boolean isAvailable() { return ModList.get().isLoaded("jei"); }

    // ──────────────── Phase 2 で実装 ────────────────

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
