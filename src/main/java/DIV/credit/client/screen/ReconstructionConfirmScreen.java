package DIV.credit.client.screen;

import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.Nullable;

/**
 * v2.1.4: /credit reconstruction の確認 dialog。
 * <p>scan 結果サマリ (= 件数) を表示して、 [Run] [Cancel] を user に選ばせる。
 * Run 時は callback を呼び、 caller (ReconstructionRunner.execute) が実処理を進める。
 */
public class ReconstructionConfirmScreen extends Screen {

    @Nullable private final Screen parent;
    private final int fileCount;
    private final int recipeCount;
    private final int conflictCount;
    private final int silentDupSkipped;
    private final Runnable onConfirm;

    public ReconstructionConfirmScreen(@Nullable Screen parent,
                                       int fileCount, int recipeCount,
                                       int conflictCount, int silentDupSkipped,
                                       Runnable onConfirm) {
        super(Component.translatable("gui.credit.recon.confirm.title"));
        this.parent = parent;
        this.fileCount = fileCount;
        this.recipeCount = recipeCount;
        this.conflictCount = conflictCount;
        this.silentDupSkipped = silentDupSkipped;
        this.onConfirm = onConfirm;
    }

    @Override
    protected void init() {
        super.init();
        int cx = this.width / 2;
        int btnY = this.height / 2 + 40;
        int btnW = 100;

        addRenderableWidget(Button.builder(
                Component.translatable("gui.credit.recon.confirm.run")
                    .withStyle(ChatFormatting.GREEN),
                b -> { onConfirm.run(); onClose(); })
            .bounds(cx - btnW - 6, btnY, btnW, 20).build());

        addRenderableWidget(Button.builder(
                Component.translatable("gui.credit.recon.confirm.cancel"),
                b -> onClose())
            .bounds(cx + 6, btnY, btnW, 20).build());
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        renderBackground(g, mouseX, mouseY, partialTick);
        super.render(g, mouseX, mouseY, partialTick);

        int cx = this.width / 2;
        int y = this.height / 2 - 50;

        g.drawCenteredString(font, getTitle().copy().withStyle(ChatFormatting.GOLD),
            cx, y, 0xFFFFFFFF); y += 18;
        g.drawCenteredString(font, Component.translatable("gui.credit.recon.confirm.l1", fileCount),
            cx, y, 0xFFAAAAAA); y += 12;
        g.drawCenteredString(font, Component.translatable("gui.credit.recon.confirm.l2", recipeCount),
            cx, y, 0xFFAAAAAA); y += 12;
        if (conflictCount > 0) {
            g.drawCenteredString(font, Component.translatable("gui.credit.recon.confirm.l3", conflictCount),
                cx, y, 0xFFFFAA00); y += 12;
        }
        if (silentDupSkipped > 0) {
            g.drawCenteredString(font, Component.translatable("gui.credit.recon.confirm.l4", silentDupSkipped),
                cx, y, 0xFFAAAAAA); y += 12;
        }
        y += 6;
        g.drawCenteredString(font, Component.translatable("gui.credit.recon.confirm.l5"),
            cx, y, 0xFFFFFFFF); y += 12;
        g.drawCenteredString(font, Component.translatable("gui.credit.recon.confirm.l6"),
            cx, y, 0xFF888888);
    }

    @Override
    public void onClose() {
        if (this.minecraft != null) this.minecraft.setScreen(parent);
    }
}
