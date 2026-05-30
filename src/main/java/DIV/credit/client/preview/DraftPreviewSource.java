package DIV.credit.client.preview;

import DIV.credit.client.draft.RecipeDraft;
import mezz.jei.api.recipe.category.IRecipeCategory;
import net.minecraft.network.chat.Component;

/**
 * v3.0.0 (S12): 編集中 draft を preview 表示する PreviewSource。
 *
 * <p>{@link RecipeDraft#synthesizePreviewRecipe()} を呼んで Recipe<?> を合成。
 * default 実装は {@code toRecipeInstance()} 委譲なので、各 draft の既存
 * 描画用 instance がそのまま preview にも流用される。
 *
 * <p>null 返却時は PreviewSource として無効 → PreviewBus が silent fail + chat 通知。
 */
public final class DraftPreviewSource implements PreviewSource {

    private final RecipeDraft draft;
    private final IRecipeCategory<?> category;

    public DraftPreviewSource(RecipeDraft draft, IRecipeCategory<?> category) {
        this.draft = draft;
        this.category = category;
    }

    /** v3.4.x: EMI fallback path で draft の slot 内容を直接読むため公開。 */
    public RecipeDraft getDraft() {
        return draft;
    }

    @Override
    public IRecipeCategory<?> getCategory() {
        return category;
    }

    @Override
    public Object getRecipeObject() {
        return draft.synthesizePreviewRecipe();
    }

    @Override
    public Component getLabel() {
        String uid = category.getRecipeType().getUid().toString();
        return Component.translatable("gui.credit.preview.draft.label", uid);
    }
}
