package DIV.credit.client.undo;

import DIV.credit.client.draft.DraftStore;
import DIV.credit.client.draft.IngredientSpec;
import DIV.credit.client.draft.RecipeDraft;
import DIV.credit.client.screen.BuilderScreen;
import org.jetbrains.annotations.Nullable;

import java.util.HashMap;
import java.util.Map;

/**
 * BuilderScreen の編集状態 1 時点ぶんの immutable snapshot。
 *
 * 保存内容:
 *   - 各 category draft の slots + numeric values
 *   - DraftStore.craftingVariant
 *   - 選択中カテゴリ UID（タブ遷移も undo できるため）
 *   - TagBar: cfgContent / finderSource / EditBox value
 *   - StackBuilder: content / baseAmount / multiplier
 *   - EnergyHelper の per-category state map
 */
public final class Snapshot {

    public final Map<String, DraftState> draftStates;
    public final DraftStore.CraftingVariant craftingVariant;
    @Nullable public final String selectedCategoryUid;

    public final IngredientSpec tagBarCfg;
    public final IngredientSpec tagBarFinder;
    public final String         tagBarBoxValue;

    public final IngredientSpec sbContent;
    public final long           sbBaseAmount;
    public final int            sbMultiplier;

    public final Map<String, int[]> energyHelperByCategory;

    private Snapshot(Map<String, DraftState> drafts, DraftStore.CraftingVariant variant,
                     @Nullable String selectedCategoryUid,
                     IngredientSpec tagBarCfg, IngredientSpec tagBarFinder, String tagBarBoxValue,
                     IngredientSpec sbContent, long sbBaseAmount, int sbMultiplier,
                     Map<String, int[]> energyHelperByCategory) {
        this.draftStates = drafts;
        this.craftingVariant = variant;
        this.selectedCategoryUid = selectedCategoryUid;
        this.tagBarCfg = tagBarCfg;
        this.tagBarFinder = tagBarFinder;
        this.tagBarBoxValue = tagBarBoxValue;
        this.sbContent = sbContent;
        this.sbBaseAmount = sbBaseAmount;
        this.sbMultiplier = sbMultiplier;
        this.energyHelperByCategory = energyHelperByCategory;
    }

    public static Snapshot capture(DraftStore store, BuilderScreen screen) {
        Map<String, DraftState> map = new HashMap<>();
        for (var e : store.snapshotDrafts().entrySet()) {
            map.put(e.getKey(), DraftState.capture(e.getValue()));
        }
        // EnergyHelper map は浅 copy + 各 int[] も copy（後の変更から守る）
        Map<String, int[]> energyMap = new HashMap<>();
        for (var e : screen.getEnergyHelperStateMap().entrySet()) {
            energyMap.put(e.getKey(), e.getValue().clone());
        }
        var tagBar       = screen.getTagBar();
        var stackBuilder = screen.getStackBuilder();
        return new Snapshot(
            map,
            store.getCraftingVariant(),
            screen.getCurrentCategoryUid(),
            tagBar.getCfgContent(),
            tagBar.getFinderSource(),
            tagBar.getBoxValue(),
            stackBuilder.getContent(),
            stackBuilder.getBaseAmount(),
            stackBuilder.getMultiplier(),
            energyMap
        );
    }

    public void applyTo(DraftStore store, BuilderScreen screen) {
        // 1. DraftStore: variant + 各 draft の slots/numerics を復元
        store.setCraftingVariant(craftingVariant);
        for (var e : draftStates.entrySet()) {
            RecipeDraft d = store.snapshotDrafts().get(e.getKey());
            if (d == null) continue;
            e.getValue().applyTo(d);
        }
        // 2. EnergyHelper map: snapshot のものを丸ごと差し戻し
        screen.getEnergyHelperStateMap().clear();
        for (var e : energyHelperByCategory.entrySet()) {
            screen.getEnergyHelperStateMap().put(e.getKey(), e.getValue().clone());
        }
        // 3. カテゴリ遷移: 違うなら tabBar.select。同じなら applyCategory のみで再描画。
        screen.restoreSelectedCategory(selectedCategoryUid);
        // 4. Widget state restore（applyCategory が走った後の上書き）
        var tagBar = screen.getTagBar();
        tagBar.setCfgContent(tagBarCfg);
        tagBar.setFinderSource(tagBarFinder);
        tagBar.setBoxValue(tagBarBoxValue);
        var sb = screen.getStackBuilder();
        sb.restoreState(sbContent, sbBaseAmount, sbMultiplier);
    }

    /** UndoHistory.computeHash から呼ばれる。 */
    static long draftHash(RecipeDraft d) {
        long h = 0;
        for (int i = 0; i < d.slotCount(); i++) {
            IngredientSpec s = d.getSlot(i);
            h = h * 31 + (s == null ? 0 : s.hashCode());
        }
        for (var f : d.numericFields()) {
            h = h * 31 + Double.hashCode(f.getter().getAsDouble());
        }
        return h;
    }

    public static final class DraftState {
        public final IngredientSpec[] slots;
        public final Map<String, Double> numericValues;

        private DraftState(IngredientSpec[] slots, Map<String, Double> numerics) {
            this.slots = slots;
            this.numericValues = numerics;
        }

        public static DraftState capture(RecipeDraft d) {
            IngredientSpec[] s = new IngredientSpec[d.slotCount()];
            for (int i = 0; i < d.slotCount(); i++) s[i] = d.getSlot(i);
            Map<String, Double> nv = new HashMap<>();
            for (var f : d.numericFields()) {
                nv.put(f.label(), f.getter().getAsDouble());
            }
            return new DraftState(s, nv);
        }

        public void applyTo(RecipeDraft d) {
            for (int i = 0; i < Math.min(slots.length, d.slotCount()); i++) {
                IngredientSpec spec = slots[i] == null ? IngredientSpec.EMPTY : slots[i];
                d.setSlot(i, spec);
            }
            for (var f : d.numericFields()) {
                Double v = numericValues.get(f.label());
                if (v != null) f.setter().accept(v);
            }
        }
    }
}
