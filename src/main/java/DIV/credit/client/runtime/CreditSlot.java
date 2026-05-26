package DIV.credit.client.runtime;

import DIV.credit.client.draft.IngredientSpec;
import org.jetbrains.annotations.Nullable;

/**
 * recipe layout 中の slot 1 個を backend 非依存で表す。
 * <p>JEI {@link mezz.jei.api.gui.ingredient.IRecipeSlotView} と EMI {@code SlotWidget} を
 * 統一した形。 sample probe (= GenericDraft.tryCreate 等) はこの CreditSlot 列を返す。
 *
 * @param index      slot index (= 順序、 0 始まり、 同 role 内で安定順)
 * @param x          category 左上 origin からの x 相対 (= JEI Rect2i.getX or EMI SlotWidget.x)
 * @param y          同 y 相対
 * @param role       INPUT / OUTPUT / CATALYST
 * @param sample     sample 時の displayed ingredient (= 初回 probe 時の 1 ingredient、 cycling list の先頭)、
 *                   slot が空なら IngredientSpec.EMPTY
 * @param nativeRef  backend 固有 slot instance (= JEI IRecipeSlotView or EMI SlotWidget)、 二次的に cast 可
 */
public record CreditSlot(
    int index,
    int x,
    int y,
    Role role,
    IngredientSpec sample,
    @Nullable Object nativeRef
) {
    public enum Role { INPUT, OUTPUT, CATALYST }
}
