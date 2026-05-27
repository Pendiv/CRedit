package DIV.credit.client.runtime.emi;

import DIV.credit.Credit;
import DIV.credit.client.draft.IngredientSpec;
import DIV.credit.client.runtime.CreditCategory;
import DIV.credit.client.runtime.CreditRecipe;
import DIV.credit.client.runtime.CreditRuntimeBackend;
import DIV.credit.client.runtime.CreditSlot;
import dev.emi.emi.api.EmiApi;
import dev.emi.emi.api.stack.EmiIngredient;
import dev.emi.emi.api.stack.EmiStack;
import dev.emi.emi.api.stack.EmiStackInteraction;
import dev.emi.emi.api.stack.FluidEmiStack;
import dev.emi.emi.api.stack.ItemEmiStack;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.fluids.FluidStack;
import net.minecraftforge.fml.ModList;
import org.jetbrains.annotations.Nullable;

import java.util.Collections;
import java.util.List;
import java.util.Optional;

/**
 * EMI backend 実装 (= Phase 2b: search/hover、 Phase 3 で probe/enumerate)。
 * <p>EMI runtime は static {@link EmiApi} 経由 (= JEI と違い callback で受け取らない)。
 * <p>未実装 method は stub のまま (= Phase 3 以降で順次)。
 */
public final class EmiBackend implements CreditRuntimeBackend {

    @Override public String id() { return "emi"; }

    @Override public boolean isAvailable()  { return ModList.get().isLoaded("emi"); }

    // ──────────────── Search / Hover (Phase 2b 整合性、 EMI 側を JEI と同等に) ────────────────

    @Override
    public boolean isSearchFocused() {
        if (!isAvailable()) return false;
        try { return EmiApi.isSearchFocused(); }
        catch (Throwable t) { return false; }
    }

    @Override
    public String getSearchText() {
        if (!isAvailable()) return "";
        try { String s = EmiApi.getSearchText(); return s == null ? "" : s; }
        catch (Throwable t) { return ""; }
    }

    @Override
    public void setSearchText(String text) {
        if (!isAvailable() || text == null) return;
        try { EmiApi.setSearchText(text); }
        catch (Throwable t) { Credit.LOGGER.debug("[CraftPattern] EmiBackend.setSearchText failed: {}", t.toString()); }
    }

    /**
     * EMI sidebar 上 hover ingredient → IngredientSpec 変換。
     * <p>{@code includeStandard=false} で sidebar 専用 (= 普通の inventory slot は除外)。
     * 仕様は {@link DIV.credit.client.runtime.jei.JeiBackend#hoveredIngredient} と同等。
     */
    @Override
    public @Nullable IngredientSpec hoveredIngredient(int mouseX, int mouseY) {
        if (!isAvailable()) return null;
        try {
            EmiStackInteraction inter = EmiApi.getHoveredStack(mouseX, mouseY, false);
            if (inter == null) return null;
            EmiIngredient ing = inter.getStack();
            if (ing == null || ing.isEmpty()) return null;
            // EmiIngredient は複数 EmiStack を含む (= cycling list)、 先頭採用
            List<EmiStack> stacks = ing.getEmiStacks();
            if (stacks.isEmpty()) return null;
            return toSpec(stacks.get(0));
        } catch (Throwable t) {
            return null;
        }
    }

    /** EmiStack → IngredientSpec 変換。 Item / Fluid 対応、 chemical (= Mek 等) は別 phase で。 */
    @Nullable
    private static IngredientSpec toSpec(EmiStack stack) {
        if (stack == null || stack.isEmpty()) return null;
        if (stack instanceof ItemEmiStack) {
            ItemStack is = ((ItemEmiStack) stack).getItemStack().copy();
            if (is.isEmpty()) return null;
            is.setCount(1);
            return new IngredientSpec.Item(is);
        }
        if (stack instanceof FluidEmiStack fls) {
            Object key = fls.getKey();
            if (!(key instanceof net.minecraft.world.level.material.Fluid f)) return null;
            int amount = (int) Math.min(Integer.MAX_VALUE, fls.getAmount());
            FluidStack fs = new FluidStack(f, amount);
            if (fls.hasNbt()) fs.setTag(fls.getNbt());
            return new IngredientSpec.Fluid(fs);
        }
        return null;
    }

    // ──────────────── Probe path (Phase 3) ────────────────

    /** EMI 全 category 列挙 → CreditCategory wrap。 EmiApi 経由 (= plugin 不要)。 */
    @Override
    public List<CreditCategory> getCategories() {
        if (!isAvailable()) return Collections.emptyList();
        try {
            List<CreditCategory> out = new java.util.ArrayList<>();
            for (var emiCat : EmiApi.getRecipeManager().getCategories()) {
                out.add(new CreditCategory(emiCat.getId(), emiCat.getName(), emiCat));
            }
            return out;
        } catch (Throwable t) {
            Credit.LOGGER.warn("[C1001] EmiBackend.getCategories failed: {}", t.toString());
            return Collections.emptyList();
        }
    }

    /** 指定 category の EmiRecipe 群 → CreditRecipe wrap。 backingRecipe は EmiRecipe.getBackingRecipe で取得。 */
    @Override
    public List<CreditRecipe> getRecipes(CreditCategory category) {
        if (!isAvailable() || category == null) return Collections.emptyList();
        if (!(category.nativeRef() instanceof dev.emi.emi.api.recipe.EmiRecipeCategory emiCat)) {
            return Collections.emptyList();
        }
        try {
            List<CreditRecipe> out = new java.util.ArrayList<>();
            for (var emiRecipe : EmiApi.getRecipeManager().getRecipes(emiCat)) {
                net.minecraft.world.item.crafting.Recipe<?> backing = null;
                try { backing = emiRecipe.getBackingRecipe(); }
                catch (Throwable ignored) {}
                out.add(new CreditRecipe(emiRecipe.getId(), category, backing, emiRecipe));
            }
            return out;
        } catch (Throwable t) {
            Credit.LOGGER.warn("[C1002] EmiBackend.getRecipes({}) failed: {}",
                category.uid(), t.toString());
            return Collections.emptyList();
        }
    }

    @Override
    public Optional<CreditCategory> findCategory(ResourceLocation uid) {
        if (!isAvailable() || uid == null) return Optional.empty();
        try {
            for (var emiCat : EmiApi.getRecipeManager().getCategories()) {
                if (uid.equals(emiCat.getId())) {
                    return Optional.of(new CreditCategory(emiCat.getId(), emiCat.getName(), emiCat));
                }
            }
        } catch (Throwable t) {
            Credit.LOGGER.warn("[C1003] EmiBackend.findCategory({}) failed: {}", uid, t.toString());
        }
        return Optional.empty();
    }

    /**
     * EmiRecipe.addWidgets を WidgetHolderRecorder に記録、 SlotWidget 列を CreditSlot に変換。
     * <p>role 判定: 各 slot の EmiIngredient を recipe.getCatalysts() / getOutputs() に照合:
     * <ul>
     *   <li>catalysts に含まれる → CATALYST</li>
     *   <li>outputs に含まれる   → OUTPUT</li>
     *   <li>それ以外             → INPUT</li>
     * </ul>
     * sample ingredient は cycling list 先頭 (= EmiIngredientAdapter.toSpecForHover) を採用。
     */
    @Override
    public List<CreditSlot> probeSlots(CreditRecipe recipe) {
        if (!isAvailable() || recipe == null) return Collections.emptyList();
        if (!(recipe.nativeRef() instanceof dev.emi.emi.api.recipe.EmiRecipe emiRecipe)) {
            return Collections.emptyList();
        }
        try {
            WidgetHolderRecorder recorder =
                new WidgetHolderRecorder(emiRecipe.getDisplayWidth(), emiRecipe.getDisplayHeight());
            emiRecipe.addWidgets(recorder);
            java.util.Set<dev.emi.emi.api.stack.EmiIngredient> catalysts =
                java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
            catalysts.addAll(emiRecipe.getCatalysts());
            java.util.Set<dev.emi.emi.api.stack.EmiStack> outputs =
                java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
            outputs.addAll(emiRecipe.getOutputs());

            List<CreditSlot> out = new java.util.ArrayList<>();
            int idx = 0;
            for (var sw : recorder.getSlots()) {
                var bounds = sw.getBounds();
                int x = (bounds != null) ? bounds.x() : 0;
                int y = (bounds != null) ? bounds.y() : 0;
                var stack = sw.getStack();
                CreditSlot.Role role = CreditSlot.Role.INPUT;
                if (catalysts.contains(stack)) {
                    role = CreditSlot.Role.CATALYST;
                } else {
                    // outputs 含むか check (= EmiStack 単独で比較)
                    for (var s : stack.getEmiStacks()) {
                        if (outputs.contains(s)) { role = CreditSlot.Role.OUTPUT; break; }
                    }
                }
                IngredientSpec sample = EmiIngredientAdapter.toSpecForHover(stack);
                if (sample == null) sample = IngredientSpec.EMPTY;
                out.add(new CreditSlot(idx++, x, y, role, sample, sw));
            }
            return out;
        } catch (Throwable t) {
            Credit.LOGGER.warn("[C1004] EmiBackend.probeSlots({}) failed: {}",
                recipe.id(), t.toString());
            return Collections.emptyList();
        }
    }

    /** spec を生み出す recipe を EMI で表示 (= EmiApi.displayRecipes)。 */
    @Override
    public void showRecipesFor(IngredientSpec spec) {
        if (!isAvailable() || spec == null) return;
        EmiIngredient emi = IngredientSpecToEmi.toEmi(spec);
        if (emi == null || emi.isEmpty()) return;
        try { EmiApi.displayRecipes(emi); }
        catch (Throwable t) { Credit.LOGGER.warn("[C1015] EmiBackend.showRecipesFor failed: {}", t.toString()); }
    }

    /** spec を消費する recipe を EMI で表示 (= EmiApi.displayUses)。 */
    @Override
    public void showUsesOf(IngredientSpec spec) {
        if (!isAvailable() || spec == null) return;
        EmiIngredient emi = IngredientSpecToEmi.toEmi(spec);
        if (emi == null || emi.isEmpty()) return;
        try { EmiApi.displayUses(emi); }
        catch (Throwable t) { Credit.LOGGER.warn("[C1016] EmiBackend.showUsesOf failed: {}", t.toString()); }
    }

    /** category UID 指定で EMI を開く。 native EMI category と JemiCategory 両対応。 */
    @Override
    public boolean openCategoryByUid(ResourceLocation uid) {
        if (!isAvailable() || uid == null) return false;
        try {
            for (var cat : EmiApi.getRecipeManager().getCategories()) {
                if (uid.equals(cat.getId())) {
                    EmiApi.displayRecipeCategory(cat);
                    return true;
                }
                // JemiCategory: 内部 JEI category の uid と照合
                if (cat instanceof dev.emi.emi.jemi.JemiCategory jc && jc.category != null) {
                    try {
                        if (uid.equals(jc.category.getRecipeType().getUid())) {
                            EmiApi.displayRecipeCategory(cat);
                            return true;
                        }
                    } catch (Throwable ignored) {}
                }
            }
            return false;
        } catch (Throwable t) {
            Credit.LOGGER.warn("[C1017] EmiBackend.openCategoryByUid({}) failed: {}", uid, t.toString());
            return false;
        }
    }

    /**
     * recipe ID 指定で EMI を開く。
     * <p>JemiRecipe の originalId (= JEI 元 ID) と一致する recipe を全 categories から探す。
     * EMI native recipe の場合は EmiRecipe.getId() と直接一致。
     */
    @Override
    public boolean openRecipeId(ResourceLocation recipeId, @Nullable String categoryHint) {
        if (!isAvailable() || recipeId == null) return false;
        try {
            var mgr = EmiApi.getRecipeManager();
            // 1. EMI 直 ID 一致 (= native EMI recipe)
            var direct = mgr.getRecipe(recipeId);
            if (direct != null) { EmiApi.displayRecipe(direct); return true; }
            // 2. JemiRecipe.originalId 一致 を全探索 (= O(n) だが command 用途で許容)
            for (var r : mgr.getRecipes()) {
                if (r instanceof dev.emi.emi.jemi.JemiRecipe<?> jemi
                    && recipeId.equals(jemi.originalId)) {
                    EmiApi.displayRecipe(r);
                    return true;
                }
            }
            return false;
        } catch (Throwable t) {
            Credit.LOGGER.warn("[C1018] EmiBackend.openRecipeId({}) failed: {}", recipeId, t.toString());
            return false;
        }
    }

    /**
     * v3.4.x: exclusion area 登録は JEI 側 ({@code CraftPatternJeiPlugin.registerGuiHandlers} の
     * {@code IGuiContainerHandler.getGuiExtraAreas}) で行う → JEMI bridge が EMI 側に auto-forward する
     * ため、 こちらは intentionally noop。 EmiPlugin での addExclusionArea path は @JeiPlugin 衝突
     * リスク回避のため不採用 (= claudecode/EMI_PLUGIN_CONFLICT.txt 参照)。
     */
    @Override public void registerExclusionArea(Class<? extends Screen> screenClass, ExclusionAreaProvider provider) {}

    /**
     * EMI 検索 bar の上端 Y。 {@code EmiScreenManager.search.y} 直接 read (= public static)。
     * 取れない場合 (= screen init 前等) は EMI default 計算式 (= height - 21) で fallback。
     */
    /**
     * EMI 検索 bar の上端 Y。
     * <p>計算は formula 直接 ({@code screenHeight - 21}、 EMI {@code EmiScreenManager} line 913 と一致) で
     * resize 時に常に最新値を返す。 {@code EmiScreenManager.search.getY()} の dynamic read は init order
     * 依存で resize 時に stale 値返すため不使用。
     * <p>例外:
     * <ul>
     *   <li>EMI 全体無効化 ({@code EmiConfig.enabled = false}) → reservation なし</li>
     *   <li>screenHeight が小さすぎる場合 → 0 でクランプ</li>
     * </ul>
     * <p>未対応 (= 将来要追加):
     * <ul>
     *   <li>EMI sidebar layout で search bar 2 行構成 ({@code screen.height - 42}) の case</li>
     * </ul>
     */
    @Override
    public int getBottomReservedTopY(int screenHeight) {
        if (!isAvailable()) return screenHeight;
        try {
            if (!dev.emi.emi.config.EmiConfig.enabled) return screenHeight;
        } catch (Throwable ignored) {}
        return Math.max(0, screenHeight - 21);
    }
}
