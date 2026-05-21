package DIV.credit.client.draft;

import mezz.jei.api.gui.IRecipeLayoutDrawable;
import mezz.jei.api.recipe.RecipeType;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.DoubleConsumer;
import java.util.function.DoubleSupplier;

/**
 * カテゴリごとのレシピ Draft。
 */
public interface RecipeDraft {

    int slotCount();

    IngredientSpec getSlot(int index);

    /** Tag を出力スロットに置く等の不正は実装側で弾くこと。 */
    void setSlot(int index, IngredientSpec spec);

    /** drawable 再生成用の Recipe 実体。 */
    Recipe<?> toRecipeInstance();

    /**
     * v3.0.0: preview 描画用の Recipe 実体。
     * default 実装 = {@link #toRecipeInstance()} を委譲。
     * <p>preview 専用に高品質合成 (= placeholder 不使用) や、 toRecipeInstance() が
     * null 返す draft で preview だけ別経路で合成したいケースは override する。
     * <p>戻りが null なら preview スコープ外 (= PreviewBus が silent fail + chat 通知)。
     */
    @Nullable
    default Recipe<?> synthesizePreviewRecipe() {
        return toRecipeInstance();
    }

    RecipeType<?> recipeType();

    /**
     * @deprecated v2.0.0: ScriptWriter は modid + operation で path を集中管理するようになり、
     * このメソッドは呼ばれなくなった。各 Draft の override は残してあるが安全に削除可能。
     */
    @Deprecated
    String relativeOutputPath();

    /** KubeJS 用 1 レシピ分のコード。 */
    @Nullable String emit(String recipeId);

    /**
     * 既存レシピの内容をこの draft にロード（編集モード用）。v2.0.0。
     * 成功時 true、未対応 / 認識不能で false。default は false (= 各 Draft が override)。
     * <p>layout.getRecipe() で Recipe<?> 取得 + slot views も使える。
     */
    default boolean loadFromRecipe(IRecipeLayoutDrawable<?> layout) {
        return false;
    }

    /** スロットの種別。出力 / 流体 / 気体スロット判定に使う。 */
    enum SlotKind { ITEM_INPUT, ITEM_OUTPUT, FLUID_INPUT, FLUID_OUTPUT, GAS_INPUT, GAS_OUTPUT }

    /** スロットごとの種別。デフォルトは isOutputSlot と等価で ITEM 系。 */
    default SlotKind slotKind(int slotIndex) {
        return isOutputSlot(slotIndex) ? SlotKind.ITEM_OUTPUT : SlotKind.ITEM_INPUT;
    }

    /** 出力スロットなら true。slotKind の便利ヘルパ。 */
    default boolean isOutputSlot(int slotIndex) {
        SlotKind k = slotKind(slotIndex);
        return k == SlotKind.ITEM_OUTPUT || k == SlotKind.FLUID_OUTPUT || k == SlotKind.GAS_OUTPUT;
    }

    /** spec をこのスロットに置けるかどうか。型ミスマッチを silently 弾く。 */
    default boolean acceptsAt(int slotIndex, IngredientSpec spec) {
        if (spec == null || spec.isEmpty()) return true;
        // GT_CHANCE / CREATE_CHANCE は output 限定。それ以外でも OK な base 型でも CHANCE が乗ってたら拒否
        var opt = spec.option();
        if ((opt == IngredientSpec.ItemOption.GT_CHANCE
            || opt == IngredientSpec.ItemOption.CREATE_CHANCE)
            && !isOutputSlot(slotIndex)) {
            return false;
        }
        IngredientSpec base = spec.unwrap();
        SlotKind k = slotKind(slotIndex);
        return switch (k) {
            case ITEM_INPUT   -> base instanceof IngredientSpec.Item || base instanceof IngredientSpec.Tag;
            case ITEM_OUTPUT  -> base instanceof IngredientSpec.Item;
            case FLUID_INPUT  -> base instanceof IngredientSpec.Fluid || base instanceof IngredientSpec.FluidTag;
            case FLUID_OUTPUT -> base instanceof IngredientSpec.Fluid;
            case GAS_INPUT    -> base instanceof IngredientSpec.Gas;
            case GAS_OUTPUT   -> base instanceof IngredientSpec.Gas;
        };
    }

    /**
     * UI が動的に EditBox を生成するための数値フィールド一覧。
     * 各フィールドは独自の getter/setter/range を持ち、書き換え後 caller (BuilderScreen) が
     * recipeArea.rebuild() を呼ぶ。
     */
    default List<NumericField> numericFields() { return List.of(); }

    /**
     * GT 電圧 tier × アンペア helper を表示する適格性。
     * GT 電気機械（EU 消費する）のみ true。Mek/vanilla や GT の非電気（primitive_blast 等）は false。
     */
    default boolean usesGtElectricity() { return false; }

    /**
     * Create の heat 切替 UI を表示する適格性。
     * mixing / compacting / packing draft のみ true。それ以外 false。
     */
    default boolean canRequireHeat() { return false; }

    /** 現在の heat 設定 (NONE / HEATED / SUPERHEATED)。canRequireHeat() = false なら常に NONE。 */
    default DIV.credit.client.draft.create.HeatLevel getHeatLevel() {
        return DIV.credit.client.draft.create.HeatLevel.NONE;
    }

    /** heat 設定。canRequireHeat() = false な draft では no-op。 */
    default void setHeatLevel(DIV.credit.client.draft.create.HeatLevel level) {}

    /**
     * Create item_application / deploying の keepHeldItem 切替適格性。
     * true な draft のみ UI 上に keepHeldItem トグルを出す。
     */
    default boolean canKeepHeldItem() { return false; }

    /** keepHeldItem 値 (default false)。 */
    default boolean isKeepHeldItem() { return false; }

    /** keepHeldItem 設定。canKeepHeldItem() = false な draft では no-op。 */
    default void setKeepHeldItem(boolean value) {}

    /**
     * v2.0.12: スロットが count > 1 を持てるか。default は無制限。
     * 1 を返すと RecipeArea の scroll / right-click による count 増加が block される。
     * 主な用途: DE ingredients[] (count 指定不可)、vanilla crafting input slot 等。
     * <p>fluid/gas slot は通常無制限のまま使える。
     */
    default int slotMaxCount(int slotIndex) {
        return Integer.MAX_VALUE;
    }

    // ─── v2.0.0 DE: tier toggle (DRACONIUM/WYVERN/DRACONIC/CHAOTIC) ───
    /** tier toggle UI を表示する適格性。FusionCraftingDraft のみ true。 */
    default boolean canCycleTier() { return false; }
    /** 現在の tier 表示名（例 "Wyvern"）。canCycleTier=false なら null。 */
    default @Nullable String getTierLabel() { return null; }
    /** 現在の tier カラー (ARGB)。canCycleTier=false なら 0xFFFFFFFF。 */
    default int getTierColor() { return 0xFFFFFFFF; }
    /** tier を 1 段切替。forward=true で次、false で前。canCycleTier=false なら no-op。 */
    default void cycleTier(boolean forward) {}

    /**
     * 数値入力フィールド宣言。
     * <p>v2.1.3: nullable 拡張。{@link #nullable} = true の field は UI で "null" 入力受付、
     * {@link #isPresent} が false を返している間は emit から行ごと省略される。
     * {@link #clearer} は null 化操作。nullable=false な field では旧挙動 (= 常に present)。
     */
    record NumericField(
        String label,
        Kind kind,
        DoubleSupplier getter,
        DoubleConsumer setter,
        double min,
        double max,
        boolean nullable,
        BooleanSupplier isPresent,
        Runnable clearer
    ) {
        public enum Kind { INT, FLOAT }

        /** 旧 6-arg 互換: nullable=false / always-present / no-op clearer。既存 draft はこれで動く。 */
        public NumericField(String label, Kind kind, DoubleSupplier getter, DoubleConsumer setter,
                            double min, double max) {
            this(label, kind, getter, setter, min, max, false, () -> true, () -> {});
        }
    }

    /**
     * v2.1.3: long 値 + present フラグの軽量コンテナ。
     * <ul>
     *   <li>{@link #set} で値設定 → 自動 present 化 (= "触ったら復活")</li>
     *   <li>{@link #clear} で null 化</li>
     *   <li>{@link #toField} で NumericField (nullable=true) 直接生成</li>
     * </ul>
     * 各 draft の long primitive フィールドを置換して使う。
     */
    final class NullableLong {
        private long value;
        private boolean present = true;

        public NullableLong() {}
        public NullableLong(long initial) { this.value = initial; this.present = true; }

        public long get()         { return value; }
        public boolean isPresent(){ return present; }
        public void set(long v)   { this.value = v; this.present = true; }
        public void clear()       { this.present = false; this.value = 0; }
        /** present 状態は触らず、内部値だけ書く (初期化 / probe からの populate 用)。 */
        public void setSilently(long v) { this.value = v; }
        /** present フラグだけ書く (NBT 復元用)。 */
        public void setPresent(boolean p) { this.present = p; if (!p) this.value = 0; }

        public NumericField toField(String label, NumericField.Kind kind, double min, double max) {
            return new NumericField(label, kind,
                () -> (double) value,
                v -> set((long) v),
                min, max,
                true,
                () -> present,
                this::clear);
        }
    }

    /**
     * v2.1.3: {@link java.util.Map} key 1 個分を nullable NumericField として公開。
     * present 判定 = map.containsKey(key) / setter = put / clearer = remove。
     */
    static NumericField intDataField(String key, String label,
                                     java.util.Map<String, Long> map,
                                     double min, double max) {
        return new NumericField(label, NumericField.Kind.INT,
            () -> (double) map.getOrDefault(key, 0L),
            v -> map.put(key, (long) v),
            min, max,
            true,
            () -> map.containsKey(key),
            () -> map.remove(key));
    }

    /** recipeId 自動生成用：出力アイテムのレジストリ ID パス。 */
    @Nullable
    default String outputItemPath() {
        IngredientSpec out = getOutput();
        if (out != null) out = out.unwrap();
        if (out instanceof IngredientSpec.Item it && !it.stack().isEmpty()) {
            ResourceLocation rl = BuiltInRegistries.ITEM.getKey(it.stack().getItem());
            return rl != null ? rl.getPath() : null;
        }
        return null;
    }

    /**
     * v3.0.1: recipeId 自動生成用 path。 default は {@link #outputItemPath()} (= 出力 path のみ)。
     * 同 output で複数 recipe を持ち得る draft (Cooking 4 種 / Stonecutting) は override して
     * type / input を絡めた一意 path を返す。 戻りはそのまま {@code credit:generated/<ここ>} になる。
     */
    @Nullable
    default String autoIdPath() {
        return outputItemPath();
    }

    /** {@link #autoIdPath()} の override 用 helper: Item / Tag の path を取り出す。 */
    @Nullable
    static String ingredientIdPath(IngredientSpec s) {
        if (s == null) return null;
        s = s.unwrap();
        if (s instanceof IngredientSpec.Item it && !it.stack().isEmpty()) {
            ResourceLocation rl = BuiltInRegistries.ITEM.getKey(it.stack().getItem());
            return rl != null ? rl.getPath() : null;
        }
        if (s instanceof IngredientSpec.Tag tg && tg.tagId() != null) {
            return tg.tagId().getPath();
        }
        return null;
    }

    @Nullable default IngredientSpec getOutput() { return null; }

    // ───── helpers for impls ─────

    static Ingredient toIngredient(IngredientSpec s) {
        if (s != null) s = s.unwrap();
        if (s instanceof IngredientSpec.Item it && !it.stack().isEmpty()) {
            return Ingredient.of(it.stack());
        }
        if (s instanceof IngredientSpec.Tag tg && tg.tagId() != null) {
            TagKey<Item> tk = TagKey.create(Registries.ITEM, tg.tagId());
            return Ingredient.of(tk);
        }
        return Ingredient.EMPTY;
    }

    /** 出力アイテム抽出（Tag は不可なので Item のみ）。 */
    static ItemStack toOutputStack(IngredientSpec s) {
        if (s != null) s = s.unwrap();
        if (s instanceof IngredientSpec.Item it && !it.stack().isEmpty()) {
            return it.stack().copy();
        }
        return ItemStack.EMPTY;
    }

    /** KubeJS 単数文字列（count 無視）：Item は 'ns:path'、Tag は '#ns:path'。NBT 持ちは Item.of(...).strongNBT()。 */
    @Nullable
    static String formatIngredientString(IngredientSpec s) {
        if (s != null) s = s.unwrap();
        if (s instanceof IngredientSpec.Item it && !it.stack().isEmpty()) {
            return formatItemStringWithNbt(it.stack(), 1);
        }
        if (s instanceof IngredientSpec.Tag tg && tg.tagId() != null) {
            return "'#" + tg.tagId() + "'";
        }
        return null;
    }

    /** count > 1 のとき Item.of(id, count)。NBT 持ちは Item.of(id, count, '<nbt>').strongNBT()。 */
    @Nullable
    static String formatIngredientWithCount(IngredientSpec s) {
        if (s == null || s.isEmpty()) return null;
        int c = s.count();
        s = s.unwrap();
        if (s instanceof IngredientSpec.Item it && !it.stack().isEmpty()) {
            return formatItemStringWithNbt(it.stack(), c);
        }
        if (s instanceof IngredientSpec.Tag tg && tg.tagId() != null) {
            // Tag with count > 1: KubeJS で 1 文字列表現できないので、shapeless 等は emitter 側で複製
            return "'#" + tg.tagId() + "'";
        }
        return null;
    }

    @Nullable
    static String formatItemString(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return null;
        return formatItemStringWithNbt(stack, stack.getCount());
    }

    /**
     * v2.1.5: ItemStack を KubeJS ingredient 文字列に変換。NBT 対応。
     * - 単純 (NBT 無し, count=1): 'ns:path'
     * - count > 1, NBT 無し:    Item.of('ns:path', count)
     * - NBT 持ち (Singularity 等): Item.of('ns:path', count, '<nbt>').strongNBT()
     */
    static String formatItemStringWithNbt(ItemStack stack, int count) {
        ResourceLocation rl = BuiltInRegistries.ITEM.getKey(stack.getItem());
        if (stack.hasTag()) {
            String nbtStr = stack.getTag().toString();
            // single-quoted JS string: backslash, single quote をエスケープ
            String escaped = nbtStr.replace("\\", "\\\\").replace("'", "\\'");
            if (count > 1) {
                return "Item.of('" + rl + "', " + count + ", '" + escaped + "').strongNBT()";
            }
            return "Item.of('" + rl + "', '" + escaped + "').strongNBT()";
        }
        if (count > 1) {
            return "Item.of('" + rl + "', " + count + ")";
        }
        return "'" + rl + "'";
    }
}