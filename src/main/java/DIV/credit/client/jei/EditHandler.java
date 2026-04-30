package DIV.credit.client.jei;

import DIV.credit.Credit;
import DIV.credit.client.draft.DraftStore;
import DIV.credit.client.draft.RecipeDraft;
import DIV.credit.client.screen.BuilderScreen;
import mezz.jei.api.gui.IRecipeLayoutDrawable;
import mezz.jei.api.recipe.category.IRecipeCategory;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvents;
import org.jetbrains.annotations.Nullable;

/**
 * "+" ハイジャック時の EDIT 遷移ハンドラ。v2.0.0。
 * <ol>
 *   <li>DraftStore から category 用 draft を取得 / 作成</li>
 *   <li>draft.loadFromRecipe で既存レシピ内容を流し込む</li>
 *   <li>BuilderScreen の edit mode を ON + lastCategory も合わせる</li>
 *   <li>BuilderScreen を開き直す（init で lastCategory が選ばれる）</li>
 * </ol>
 */
public final class EditHandler {

    private EditHandler() {}

    public static <R> void handle(IRecipeLayoutDrawable<R> layout, IRecipeCategory<R> category,
                                  R recipe, @Nullable ResourceLocation recipeId) {
        if (recipeId == null) {
            chat(Component.translatable("gui.credit.edit.load_failed").withStyle(ChatFormatting.RED));
            return;
        }
        DraftStore store = BuilderScreen.getDraftStore();
        RecipeDraft draft = store.getOrCreate(category);
        if (draft == null) {
            chat(Component.translatable("gui.credit.edit.load_failed").withStyle(ChatFormatting.RED));
            Credit.LOGGER.warn("[CraftPattern] EDIT: DraftStore returned null for {}",
                category.getRecipeType().getUid());
            return;
        }
        boolean ok;
        try {
            ok = draft.loadFromRecipe(layout);
        } catch (Exception e) {
            Credit.LOGGER.error("[CraftPattern] EDIT: loadFromRecipe threw", e);
            ok = false;
        }
        if (!ok) {
            chat(Component.translatable("gui.credit.edit.load_failed").withStyle(ChatFormatting.RED));
            return;
        }
        // Edit mode 開始 + JEI 起点フラグ解除（編集モード遷移は OriginTracker と独立）
        BuilderScreen.enterEditMode(recipeId.toString(), category);
        OriginTracker.exit();
        Credit.LOGGER.info("[CraftPattern] EDIT: opening BuilderScreen for {} (orig id {})",
            category.getRecipeType().getUid(), recipeId);
        Minecraft.getInstance().setScreen(BuilderScreen.open());
        playClick();
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
