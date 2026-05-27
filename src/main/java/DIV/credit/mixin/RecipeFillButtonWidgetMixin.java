package DIV.credit.mixin;

import dev.emi.emi.api.recipe.EmiRecipe;
import dev.emi.emi.api.widget.RecipeFillButtonWidget;
import dev.emi.emi.jemi.JemiRecipe;
import net.minecraft.client.gui.screens.inventory.tooltip.ClientTooltipComponent;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.List;

// 上の import 自動置換 (ClientTooltipComponent → ClientTooltipComponent) で、 戻り値型表記も置換される
// ことに注意。 getTooltip(II)Ljava/util/List; method 内 List<ClientTooltipComponent> はこの class。

/**
 * RecipeFillButtonWidget (= EMI の「+」 button) を credit-editable recipe で
 * active 状態 + edit 用 tooltip に上書き。
 *
 * <h3>背景</h3>
 * {@link DIV.credit.mixin.EmiRecipeFillerMixin} で {@code isSupported} を true にすると
 * 「+」 button 自体は recipe display に追加されるが、 button 内部の
 * {@code canFill} field が constructor 内で handler.canCraft 確認に失敗して false になる
 * (= credit は EmiRecipeHandler を register してないため)。 結果として
 * grayed (= 「配置できない」 状態) で「emi.inapplicable」 tooltip が出てしまう。
 *
 * <h3>本 mixin の修正</h3>
 * <ul>
 *   <li>constructor TAIL: credit-editable なら {@code canFill = true} に上書き → 緑 active 表示</li>
 *   <li>getTooltip HEAD cancellable: credit-editable なら「Edit」 tooltip 返却 (= EMI の inapplicable 抑制)</li>
 * </ul>
 *
 * click 自体は credit の ScreenEvent intercept で EditHandler に dispatch、 EMI の
 * fill 機構は呼ばれない。
 */
@Mixin(RecipeFillButtonWidget.class)
public class RecipeFillButtonWidgetMixin {

    @Shadow private boolean canFill;

    /** mixin 内追加 field: recipe instance を constructor で store して getTooltip で参照。 */
    @Unique
    private EmiRecipe credit$recipe;

    @Inject(method = "<init>", at = @At("TAIL"), remap = false)
    private void credit$overrideForEditable(int x, int y, EmiRecipe recipe, CallbackInfo ci) {
        this.credit$recipe = recipe;
        // v3.4.x: setting OFF で no-op (= canFill 上書きせず EMI 既定の grayed 表示)
        if (!DIV.credit.CreditConfig.isEmiIntegrationEnabled()) return;
        if (isCreditEditable(recipe)) {
            this.canFill = true;
            DIV.credit.client.runtime.MixinSanityTracker.markEmiFillerHit();
        }
    }

    @Inject(method = "getTooltip(II)Ljava/util/List;",
            at = @At("HEAD"),
            cancellable = true,
            remap = false)
    private void credit$dynamicTooltip(int mouseX, int mouseY,
                                       CallbackInfoReturnable<List<ClientTooltipComponent>> cir) {
        // v3.4.x: setting OFF で no-op (= EMI 既定の inapplicable tooltip)
        if (!DIV.credit.CreditConfig.isEmiIntegrationEnabled()) return;
        if (this.credit$recipe instanceof JemiRecipe<?> jemi
            && jemi.category != null
            && jemi.originalId != null) {
            try {
                if (DIV.credit.client.draft.DraftStore.isSupportedCategory(jemi.category)) {
                    boolean edited = DIV.credit.client.jei.EditDeleteTracker.INSTANCE.isEdited(jemi.originalId);
                    String key = edited ? "gui.credit.edit.already" : "gui.credit.edit.button";
                    cir.setReturnValue(List.of(
                        ClientTooltipComponent.create(Component.translatable(key).getVisualOrderText()),
                        ClientTooltipComponent.create(Component.literal(String.valueOf(jemi.originalId))
                            .withStyle(net.minecraft.ChatFormatting.GRAY).getVisualOrderText())
                    ));
                }
            } catch (Throwable ignored) {}
        }
    }

    @Unique
    private static boolean isCreditEditable(EmiRecipe recipe) {
        if (!(recipe instanceof JemiRecipe<?> jemi)) return false;
        if (jemi.category == null || jemi.originalId == null) return false;
        try {
            return DIV.credit.client.draft.DraftStore.isSupportedCategory(jemi.category);
        } catch (Throwable t) {
            return false;
        }
    }
}
