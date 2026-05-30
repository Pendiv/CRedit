package DIV.credit.client.jei;

import DIV.credit.Credit;
import DIV.credit.client.io.ScriptWriter;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvents;
import org.jetbrains.annotations.Nullable;

/**
 * DELETE ボタンクリック処理: event.remove({id:...}) を delete.js に追記。
 * id が null の場合（dynamic / 取得失敗）は警告チャットで終了。
 */
public final class DeleteHandler {

    private DeleteHandler() {}

    public static void handle(@Nullable ResourceLocation recipeId) {
        if (recipeId == null) {
            chat(Component.translatable("gui.credit.delete.no_id").withStyle(ChatFormatting.RED));
            return;
        }
        String code = ScriptWriter.buildDeleteCode(recipeId.toString());
        // v2.2.0 immediate apply 判定
        if (DIV.credit.CreditConfig.shouldApplyImmediately(ScriptWriter.OperationKind.DELETE)) {
            ScriptWriter.DumpResult r = ScriptWriter.writeStagedCode(
                ScriptWriter.OperationKind.DELETE, recipeId.getNamespace(), code);
            if (r instanceof ScriptWriter.DumpResult.Failure f) {
                chat(Component.literal("[CraftPattern] " + f.message()).withStyle(ChatFormatting.RED));
                return;
            }
            String filePath = r.path() != null ? r.path().toString() : null;
            DIV.credit.client.history.ImmediateHistorySession.INSTANCE.addItem(
                new DIV.credit.client.history.HistoryEntry.Item(
                    ScriptWriter.OperationKind.DELETE, recipeId.getNamespace(),
                    recipeId.toString(), filePath, true));
            chat(Component.translatable("gui.credit.immediate.applied",
                "DELETE", recipeId.toString()).withStyle(ChatFormatting.GOLD));
            chat(Component.translatable("gui.credit.dump.reload_hint").withStyle(ChatFormatting.GRAY));
        } else {
            // DELETE は category 不明なので jeiCategoryUid=null。GT prefix variant は試せないが、
            // DELETE で recipe lookup が要らない (JEI 飛びは DELETE には適用されない) のでこれでよい。
            DIV.credit.client.staging.StagingArea.INSTANCE.stage(
                ScriptWriter.OperationKind.DELETE, recipeId.getNamespace(), recipeId.toString(), null, code, null);
            DIV.credit.client.staging.StagingPersistence.save();
            chat(Component.translatable("gui.credit.staging.staged",
                "DELETE", recipeId.toString()).withStyle(ChatFormatting.AQUA));
            chat(Component.translatable("gui.credit.staging.commit_hint")
                .withStyle(ChatFormatting.DARK_GRAY));
        }
        // v2.0.11: mark deleted → JEI overlay で再 click block
        EditDeleteTracker.INSTANCE.markDeleted(recipeId);
        playClick();
        Credit.LOGGER.info("[CraftPattern] {} DELETE {}",
            DIV.credit.CreditConfig.shouldApplyImmediately(ScriptWriter.OperationKind.DELETE)
                ? "IMMEDIATE" : "STAGE", recipeId);
    }

    private static void chat(Component msg) {
        var p = Minecraft.getInstance().player;
        if (p != null) p.displayClientMessage(msg, false);
    }

    private static void playClick() {
        Minecraft.getInstance().getSoundManager().play(
            SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK, 1.0F));
    }
}
