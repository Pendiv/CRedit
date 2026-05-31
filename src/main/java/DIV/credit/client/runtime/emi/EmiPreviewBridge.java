package DIV.credit.client.runtime.emi;

import DIV.credit.Credit;
import DIV.credit.client.draft.IngredientSpec;
import DIV.credit.client.draft.RecipeDraft;
import DIV.credit.client.preview.EmiPreviewRenderable;
import dev.emi.emi.api.EmiApi;
import dev.emi.emi.api.recipe.EmiRecipe;
import dev.emi.emi.api.recipe.EmiRecipeCategory;
import dev.emi.emi.api.recipe.EmiRecipeManager;
import dev.emi.emi.api.stack.EmiIngredient;
import dev.emi.emi.jemi.JemiCategory;
import dev.emi.emi.jemi.JemiRecipe;
import mezz.jei.api.recipe.RecipeType;
import mezz.jei.api.recipe.category.IRecipeCategory;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.crafting.Recipe;
import net.neoforged.fml.ModList;
import org.jetbrains.annotations.Nullable;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * v3.4.x: EMI 側 preview の入口。 {@link DIV.credit.client.preview.JeiRenderBridge} の対 EMI 版。
 *
 * <h3>挙動:</h3>
 * <ol>
 *   <li>JEI category UID から {@link EmiRecipeCategory} を解決 (= JemiCategory or native EMI)</li>
 *   <li>当該 category に属する EmiRecipe 群から sample を 1 個 pick (= category 内 layout 同一前提)</li>
 *   <li>sample.addWidgets を {@link SubstitutingWidgetHolder} に流して draft slot で置換</li>
 *   <li>{@link EmiPreviewRenderable} に包んで返す</li>
 * </ol>
 *
 * <h3>limitation v1:</h3>
 * <ul>
 *   <li>category 内で recipe ごとに layout が変わる場合 (= shaped/shapeless 混在等)、 sample 選定が
 *       draft と一致しない可能性。 vanilla crafting は別 category 化で問題なし。</li>
 *   <li>Mek chemical の substitution 不可 → 該当 slot は元 widget 維持 (= cycle 表示のまま)</li>
 *   <li>numeric field (= EU/duration 等) は EMI 側 layout に従って表示、 draft 値は反映しない (= sample のまま)</li>
 * </ul>
 */
public final class EmiPreviewBridge {

    private EmiPreviewBridge() {}

    /**
     * 主 entry。 失敗時は null + warn log。
     *
     * @param draft        編集中 draft (= slot 内容を substitution に変換して overlay)
     * @param jeiCategory  draft の元 JEI category (= EMI 側 category 解決のための ID source)
     */
    @Nullable
    public static EmiPreviewRenderable build(RecipeDraft draft, IRecipeCategory<?> jeiCategory) {
        if (!isEmiAvailable()) {
            Credit.LOGGER.debug("[CraftPattern] EmiPreviewBridge.build: EMI not loaded");
            return null;
        }
        if (draft == null || jeiCategory == null) {
            Credit.LOGGER.warn("[C1010] EmiPreviewBridge.build: null arg (draft={}, cat={})", draft, jeiCategory);
            return null;
        }

        EmiRecipeManager mgr;
        try {
            mgr = EmiApi.getRecipeManager();
            if (mgr == null) {
                Credit.LOGGER.warn("[C110] EmiPreviewBridge.build: EmiRecipeManager null");
                return null;
            }
        } catch (Throwable t) {
            Credit.LOGGER.warn("[C1011] EmiPreviewBridge.build: EmiApi.getRecipeManager failed: {}", t.toString());
            return null;
        }

        ResourceLocation jeiUid = jeiCategory.getRecipeType().getUid();
        EmiRecipeCategory emiCat = findEmiCategory(mgr, jeiCategory, jeiUid);
        if (emiCat == null) {
            Credit.LOGGER.warn("[C111] EmiPreviewBridge.build: EMI category not found for uid={}", jeiUid);
            return null;
        }

        // Stage 1: draft.synthesizePreviewRecipe() で合成 Recipe<?> が得られる draft (= ほとんどの hand-written
        // draft) は、 自作 JemiRecipe にラップして addWidgets を直接 invoke。 これで numeric field +
        // Mek chemical の表示まで JEI plugin 経由で正規描画される (= substitution 不要、 fidelity 最大)。
        EmiPreviewRenderable synthBased = trySynthesizedPath(draft, jeiCategory, emiCat, jeiUid);
        if (synthBased != null) return synthBased;

        // Stage 2: synth が null (= toRecipeInstance 未実装の draft) のみ、 既存 sample + substitution
        // 経路に fallback (= Mek chemical 等は元 cycling 表示のまま、 numeric は sample 値)。
        return fallbackSubstitutionPath(mgr, emiCat, jeiUid, draft);
    }

    /** preview 用に毎回 unique な id (= EMI 内部 bookmark 等の混乱回避)。 */
    private static final java.util.concurrent.atomic.AtomicLong PREVIEW_ID_COUNTER =
        new java.util.concurrent.atomic.AtomicLong();

    /**
     * Stage 1: draft → synth Recipe → fresh JemiRecipe → addWidgets。
     *
     * <p>JemiRecipe.addWidgets は内部で {@code JEIRecipeManager.createRecipeLayoutDrawable} を呼んで
     * full JEI render path (= category.draw / drawExtras / IIngredientRenderer 含む) を経由するため、
     * 成功すれば JEI preview と完全同等の品質。
     *
     * <p>事前 check:
     * <ol>
     *   <li>{@code JemiPlugin.runtime} (= JEI runtime) 非 null (= JEMI bridge ready)</li>
     *   <li>synth が non-null + category の recipe class と instance compatible</li>
     *   <li>JEI mgr が当該 recipe で {@link mezz.jei.api.gui.IRecipeLayoutDrawable} 構築可能 (= JemiRecipe 内部失敗の早期検出)</li>
     * </ol>
     */
    @Nullable
    private static EmiPreviewRenderable trySynthesizedPath(RecipeDraft draft, IRecipeCategory<?> jeiCategory,
                                                            EmiRecipeCategory emiCat, ResourceLocation jeiUid) {
        // 1. JEMI bridge ready check (= JemiRecipe ctor / addWidgets が JemiPlugin.runtime に依存)
        mezz.jei.api.runtime.IJeiRuntime jeiRt;
        try { jeiRt = dev.emi.emi.jemi.JemiPlugin.runtime; }
        catch (Throwable t) {
            Credit.LOGGER.debug("[CraftPattern] synth path: JemiPlugin.runtime access threw: {}", t.toString());
            return null;
        }
        if (jeiRt == null) {
            Credit.LOGGER.debug("[CraftPattern] synth path: JemiPlugin.runtime not ready (cat={})", jeiUid);
            return null;
        }

        // 2. synth recipe 構築
        Recipe<?> synth;
        try { synth = draft.synthesizePreviewRecipe(); }
        catch (Throwable t) {
            Credit.LOGGER.debug("[CraftPattern] synth path: synthesizePreviewRecipe threw for {}: {}", jeiUid, t.toString());
            return null;
        }
        if (synth == null) {
            Credit.LOGGER.debug("[CraftPattern] synth path: draft.synthesizePreviewRecipe returned null for {} → fallback", jeiUid);
            return null;
        }

        // 3. category の recipe class と synth class が compatible か (= 不一致 → addWidgets 内 ClassCastException 防止)
        try {
            Class<?> expected = jeiCategory.getRecipeType().getRecipeClass();
            if (!expected.isInstance(synth)) {
                Credit.LOGGER.warn("[C114] synth path class mismatch: cat={} expects {} but synth={}",
                    jeiUid, expected.getName(), synth.getClass().getName());
                return null;
            }
        } catch (Throwable t) {
            Credit.LOGGER.warn("[C1020] synth path: recipe class check threw for {}: {}", jeiUid, t.toString());
            return null;
        }

        // 4. JEI 側で IRecipeLayoutDrawable 構築可能か事前 check (= JemiRecipe 内部 silent fail 防止)
        if (!canBuildJeiDrawable(jeiRt, jeiCategory, synth, jeiUid)) {
            return null;
        }

        // 5. JemiRecipe 構築 + post-set unique id + addWidgets
        try {
            @SuppressWarnings({"unchecked","rawtypes"})
            JemiRecipe<?> fresh = new JemiRecipe(emiCat, jeiCategory, synth);
            // id 上書き (= 元 recipe の getRegistryName が null だと fresh.id も null になり EMI 側で hashing 等に問題が出る可能性)
            try {
                fresh.id = ResourceLocation.fromNamespaceAndPath(DIV.credit.Credit.MODID,
                    "preview/" + jeiUid.getNamespace() + "/" + jeiUid.getPath() + "/" + PREVIEW_ID_COUNTER.incrementAndGet());
            } catch (Throwable ignored) {}

            int w = fresh.getDisplayWidth();
            int h = fresh.getDisplayHeight();
            SubstitutingWidgetHolder holder = new SubstitutingWidgetHolder(w, h, java.util.Collections.emptyMap());
            fresh.addWidgets(holder);
            int widgetCount = holder.getWidgets().size();
            if (widgetCount == 0) {
                // addWidgets が無音で空を返した = JemiRecipe 内部の createRecipeLayoutDrawable が Optional.empty を返した
                Credit.LOGGER.warn("[C115] synth path: addWidgets produced 0 widgets for {} (= JEI refused to build drawable, recipe rejected by manager)", jeiUid);
                return null;
            }
            Credit.LOGGER.info("[CraftPattern] EmiPreviewBridge synth-path: cat={} widgets={} slots={}",
                jeiUid, widgetCount, holder.slotCount());
            return new EmiPreviewRenderable(holder.getWidgets(), w, h);
        } catch (Throwable t) {
            Credit.LOGGER.warn("[C1021] synth path: JemiRecipe construct/addWidgets failed for {}: {}",
                jeiUid, t.toString());
            return null;
        }
    }

    /**
     * JEI side で当該 recipe の {@link mezz.jei.api.gui.IRecipeLayoutDrawable} が構築可能か事前 check。
     * JemiRecipe.addWidgets が内部で同じ呼出をするが silent fail なので、 事前に試して早期 return。
     */
    private static boolean canBuildJeiDrawable(mezz.jei.api.runtime.IJeiRuntime jeiRt, IRecipeCategory<?> jeiCategory,
                                               Recipe<?> synth, ResourceLocation jeiUid) {
        try {
            var emptyFocus = jeiRt.getJeiHelpers().getFocusFactory().getEmptyFocusGroup();
            @SuppressWarnings({"unchecked","rawtypes"})
            var drawableOpt = jeiRt.getRecipeManager()
                .createRecipeLayoutDrawable((IRecipeCategory) jeiCategory, synth, emptyFocus);
            if (drawableOpt == null || drawableOpt.isEmpty()) {
                Credit.LOGGER.warn("[C116] JEI refused to build drawable for synth recipe (cat={})", jeiUid);
                return false;
            }
            return true;
        } catch (Throwable t) {
            Credit.LOGGER.warn("[C1022] synth path: canBuildJeiDrawable threw for {}: {}", jeiUid, t.toString());
            return false;
        }
    }

    /** Stage 2: sample 取得 + draft slot で SubstitutingWidgetHolder 経由置換 (= 旧経路)。 */
    @Nullable
    private static EmiPreviewRenderable fallbackSubstitutionPath(EmiRecipeManager mgr, EmiRecipeCategory emiCat,
                                                                  ResourceLocation jeiUid, RecipeDraft draft) {
        List<EmiRecipe> recipes;
        try { recipes = mgr.getRecipes(emiCat); }
        catch (Throwable t) {
            Credit.LOGGER.warn("[C1012] EmiPreviewBridge.build: getRecipes failed for {}: {}", jeiUid, t.toString());
            return null;
        }
        if (recipes == null || recipes.isEmpty()) {
            Credit.LOGGER.warn("[C112] EmiPreviewBridge.build: no EMI recipes in category {}", jeiUid);
            return null;
        }
        EmiRecipe sample = pickSample(recipes, draft);
        if (sample == null) {
            Credit.LOGGER.warn("[C113] EmiPreviewBridge.build: no usable sample for {}", jeiUid);
            return null;
        }
        Map<Integer, EmiIngredient> substitutions = buildSubstitutions(draft);
        SubstitutingWidgetHolder holder = new SubstitutingWidgetHolder(
            sample.getDisplayWidth(), sample.getDisplayHeight(), substitutions);
        try { sample.addWidgets(holder); }
        catch (Throwable t) {
            Credit.LOGGER.warn("[C1013] EmiPreviewBridge.build: sample.addWidgets({}) failed: {}",
                sample.getId(), t.toString());
            return null;
        }
        Credit.LOGGER.info("[CraftPattern] EmiPreviewBridge subst-path: cat={} sample={} widgets={} slots={} subst={}",
            jeiUid, sample.getId(), holder.getWidgets().size(), holder.slotCount(), substitutions.size());
        return new EmiPreviewRenderable(holder.getWidgets(), sample.getDisplayWidth(), sample.getDisplayHeight());
    }

    /**
     * EMI category 解決。
     * <ol>
     *   <li>JemiCategory の field {@code category} が引数 JEI category と一致するもの (= identity match)</li>
     *   <li>JemiCategory.category.getRecipeType().getUid() が jeiUid と一致</li>
     *   <li>EmiRecipeCategory.id が jeiUid と一致 (= native EMI category で偶然 ID 同じ場合)</li>
     * </ol>
     */
    @Nullable
    private static EmiRecipeCategory findEmiCategory(EmiRecipeManager mgr, IRecipeCategory<?> jeiCat, ResourceLocation jeiUid) {
        List<EmiRecipeCategory> all;
        try { all = mgr.getCategories(); }
        catch (Throwable t) { return null; }
        if (all == null) return null;
        EmiRecipeCategory fallbackById = null;
        for (EmiRecipeCategory cat : all) {
            if (cat instanceof JemiCategory jc) {
                // 1. identity match (= 高速、 確実)
                if (jc.category == jeiCat) return cat;
                // 2. UID match via jc.category.getRecipeType
                try {
                    RecipeType<?> rt = jc.category.getRecipeType();
                    if (rt != null && jeiUid.equals(rt.getUid())) return cat;
                } catch (Throwable ignored) {}
            }
            // 3. ID match (= native EMI category)
            if (cat.id != null && cat.id.equals(jeiUid)) {
                fallbackById = cat;
            }
        }
        return fallbackById;
    }

    /**
     * sample recipe 選定。 v1 は先頭。 将来は draft の slot 数等で「より一致する」 recipe を選ぶ余地。
     */
    @Nullable
    private static EmiRecipe pickSample(List<EmiRecipe> recipes, RecipeDraft draft) {
        if (recipes.isEmpty()) return null;
        // v1: 先頭
        return recipes.get(0);
    }

    private static Map<Integer, EmiIngredient> buildSubstitutions(RecipeDraft draft) {
        Map<Integer, EmiIngredient> map = new HashMap<>();
        int n = draft.slotCount();
        for (int i = 0; i < n; i++) {
            IngredientSpec spec = draft.getSlot(i);
            if (spec == null || spec.isEmpty()) continue;
            if (!IngredientSpecToEmi.canSubstitute(spec)) continue; // chemical 等は skip
            EmiIngredient emi = IngredientSpecToEmi.toEmi(spec);
            if (emi == null) continue;
            map.put(i, emi);
        }
        return map;
    }

    private static boolean isEmiAvailable() {
        try { return ModList.get().isLoaded("emi"); }
        catch (Throwable t) { return false; }
    }
}
