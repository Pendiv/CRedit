package DIV.credit.client.runtime;

import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

/**
 * recipe category の backend 非依存表現。
 * <p>{@code nativeRef} は JEI {@code IRecipeCategory<?>} or EMI {@code EmiRecipeCategory} を保持。
 * mod 固有 isXxxCategory 判定 (= GTSupport.isGtCategory 等) は backend ごとに nativeRef を
 * cast して判定する。 一般 code は uid / title のみ参照すれば backend 非依存に書ける。
 *
 * @param uid     category 一意 ID (= JEI RecipeType.getUid / EMI EmiRecipeCategory.getId)
 * @param title   表示名 (= JEI getTitle / EMI getName 経由)
 * @param nativeRef backend 固有 category instance (= 動的 cast 用、 直接触らない場合は null check 不要)
 */
public record CreditCategory(ResourceLocation uid, Component title, Object nativeRef) {
}
