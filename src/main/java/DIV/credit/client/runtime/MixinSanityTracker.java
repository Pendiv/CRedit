package DIV.credit.client.runtime;

import DIV.credit.Credit;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraftforge.client.event.ScreenEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.fml.common.Mod;

/**
 * Mixin 動作可否検知トラッカー。
 * <p>本 class は mixin package (= {@code DIV.credit.mixin.*}) の外に配置 (= mixin processor が
 * 同 package 内の非 mixin class への参照を禁じるため)。
 *
 * <h3>誤検知回避 (v3.3.x 修正)</h3>
 * 初版は player login + 5s で check してたが、 ユーザーが EMI RecipeScreen を開く前に
 * 走るため mixin 未発火で誤警告頻発。 修正版: <b>EMI RecipeScreen 初回 open 時に trigger</b>。
 * 3s 遅延後 hit=0 なら mixin 失効と判断。 RecipeScreen 開かない限り無音。
 */
@Mod.EventBusSubscriber(modid = Credit.MODID, bus = Mod.EventBusSubscriber.Bus.FORGE,
    value = net.minecraftforge.api.distmarker.Dist.CLIENT)
public final class MixinSanityTracker {

    private MixinSanityTracker() {}

    public static volatile int emiFillerHits = 0;

    private static volatile boolean warnedThisSession = false;
    private static volatile boolean checkScheduled = false;

    public static void markEmiFillerHit() {
        emiFillerHits++;
    }

    /** RecipeScreen 初回 open 時に check schedule (= login 時ではない、 誤検知回避)。 */
    @SubscribeEvent
    public static void onScreenInit(ScreenEvent.Init.Post event) {
        if (warnedThisSession || checkScheduled) return;
        if (!ModList.get().isLoaded("emi")) return;
        // EMI RecipeScreen 判定 (= reflection で class name match、 EMI 未読込環境でも safe)
        if (!"dev.emi.emi.screen.RecipeScreen".equals(event.getScreen().getClass().getName())) return;
        checkScheduled = true;
        // 3 秒待ってから hit 数 check (= mixin が呼ばれるはずの状況時間)
        new Thread(() -> {
            try { Thread.sleep(3000); } catch (InterruptedException ignored) {}
            checkAndWarn();
        }, "credit-mixin-sanity").start();
    }

    private static void checkAndWarn() {
        if (warnedThisSession) return;
        warnedThisSession = true;
        if (emiFillerHits == 0) {
            Credit.LOGGER.warn("[C9001] Mixin sanity: EmiRecipeFiller mixin hit=0 after 3s of RecipeScreen open. " +
                "Mixin may have failed to apply (EMI version mismatch?). EMI \"+\" button hijack inactive.");
            var p = Minecraft.getInstance().player;
            if (p != null) {
                p.displayClientMessage(Component.translatable("gui.credit.mixin.emi_filler_inactive")
                    .withStyle(ChatFormatting.YELLOW), false);
            }
        } else {
            Credit.LOGGER.info("[CraftPattern] Mixin sanity: EmiRecipeFiller mixin hits={} (= working)", emiFillerHits);
        }
    }
}
