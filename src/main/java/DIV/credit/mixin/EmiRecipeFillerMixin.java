package DIV.credit.mixin;

import dev.emi.emi.api.recipe.EmiRecipe;
import dev.emi.emi.jemi.JemiRecipe;
import dev.emi.emi.registry.EmiRecipeFiller;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * EMI {@code EmiRecipeFiller.isSupported(EmiRecipe)} に inject。
 * credit-editable recipe (= JemiRecipe with credit-supported category) なら true 返却で
 * EMI に「これ fill 可能や」 と思わせる → recipe display に「+」 ボタン表示される。
 * <p>click 自体は credit 側の ScreenEvent ({@link DIV.credit.client.runtime.emi.EmiOverlayHandler})
 * で intercept されて {@link DIV.credit.client.jei.EditHandler} に dispatch される。
 * EMI の craft 機構は呼ばれない。
 *
 * <h3>narrow scope</h3>
 * 単一 class / 単一 method の pinpoint mixin。 他 mod / 他 class への影響ゼロ。
 *
 * <h3>失敗検知</h3>
 * EMI 更新で signature 変わった場合 mixin apply に失敗する可能性あり。
 * 動作確認は {@link DIV.credit.client.runtime.MixinSanityTracker#emiFillerHits} カウンタで実施、
 * player login 後 5 秒で 0 のままなら chat warn。
 */
@Mixin(EmiRecipeFiller.class)
public class EmiRecipeFillerMixin {

    @Inject(method = "isSupported(Ldev/emi/emi/api/recipe/EmiRecipe;)Z",
            at = @At("HEAD"),
            cancellable = true,
            remap = false)
    private static void credit$forceSupportEditableRecipes(EmiRecipe recipe, CallbackInfoReturnable<Boolean> cir) {
        DIV.credit.client.runtime.MixinSanityTracker.markEmiFillerHit();
        // v3.4.x: setting OFF で no-op (= EMI 既定挙動に委ねる)
        if (!DIV.credit.CreditConfig.isEmiIntegrationEnabled()) return;
        if (recipe instanceof JemiRecipe<?> jemi
            && jemi.category != null
            && jemi.originalId != null) {
            try {
                if (DIV.credit.client.draft.DraftStore.isSupportedCategory(jemi.category)) {
                    cir.setReturnValue(true);
                }
            } catch (Throwable ignored) {
                // DraftStore check 失敗時は default 動作 (= EMI のオリジナル isSupported に委ねる)
            }
        }
    }
}
