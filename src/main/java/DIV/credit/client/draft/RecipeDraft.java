package DIV.credit.client.draft;

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

    RecipeType<?> recipeType();

    /** kubejs/server_scripts/ 以下の相対パス。例 "generated/crafting_shaped.js"。 */
    String relativeOutputPath();

    /** KubeJS 用 1 レシピ分のコード。 */
    @Nullable String emit(String recipeId);

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

    record NumericField(
        String label,
        Kind kind,
        DoubleSupplier getter,
        DoubleConsumer setter,
        double min,
        double max
    ) {
        public enum Kind { INT, FLOAT }
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

    /** KubeJS 単数文字列（count 無視）：Item は 'ns:path'、Tag は '#ns:path'。 */
    @Nullable
    static String formatIngredientString(IngredientSpec s) {
        if (s != null) s = s.unwrap();
        if (s instanceof IngredientSpec.Item it && !it.stack().isEmpty()) {
            ResourceLocation rl = BuiltInRegistries.ITEM.getKey(it.stack().getItem());
            return "'" + rl + "'";
        }
        if (s instanceof IngredientSpec.Tag tg && tg.tagId() != null) {
            return "'#" + tg.tagId() + "'";
        }
        return null;
    }

    /** count > 1 のとき Item.of(id, count)。それ以外は単数。Tag は count 表現が無いので単数のまま。 */
    @Nullable
    static String formatIngredientWithCount(IngredientSpec s) {
        if (s == null || s.isEmpty()) return null;
        int c = s.count();
        s = s.unwrap();
        if (s instanceof IngredientSpec.Item it && !it.stack().isEmpty()) {
            ResourceLocation rl = BuiltInRegistries.ITEM.getKey(it.stack().getItem());
            return c <= 1 ? "'" + rl + "'" : "Item.of('" + rl + "', " + c + ")";
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
        ResourceLocation rl = BuiltInRegistries.ITEM.getKey(stack.getItem());
        return "'" + rl + "'";
    }
}