package DIV.credit.client.screen;

import DIV.credit.client.staging.StagedChange;
import DIV.credit.client.staging.StagingArea;
import DIV.credit.client.staging.StagingPersistence;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.Nullable;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * /credit commit で開く画面。staged の取捨選択 UI。
 * <ul>
 *   <li>各行: [チェックボックス] HH:MM:SS [KIND] recipe:id [×]</li>
 *   <li>チェックボックス → committed toggle</li>
 *   <li>[×]              → reject (staging から削除)</li>
 *   <li>Approve All / Reject All ボタンで一括</li>
 *   <li>scroll wheel で画面外行をスクロール</li>
 * </ul>
 * 閉じる時 staging.dat に save。
 */
public class CommitScreen extends Screen {

    private static final DateTimeFormatter TIME_FMT =
        DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault());

    private static final int ROW_H        = 18;
    private static final int CHECK_SIZE   = 12;
    private static final int X_SIZE       = 12;
    private static final int LIST_PAD     = 8;
    private static final int HEADER_H     = 50;
    private static final int FOOTER_H     = 50;

    @Nullable private final Screen parent;
    private int listX, listY, listW, listH;
    private int visibleRows;
    private int scrollOffset = 0;

    public CommitScreen(@Nullable Screen parent) {
        super(Component.translatable("gui.credit.commit.title"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        super.init();
        int margin = 16;
        this.listX = margin;
        this.listY = HEADER_H;
        this.listW = this.width - 2 * margin;
        this.listH = this.height - HEADER_H - FOOTER_H;
        this.visibleRows = Math.max(1, this.listH / ROW_H);

        int btnW = 100;
        int btnY = this.height - FOOTER_H + 8;
        addRenderableWidget(Button.builder(
                Component.translatable("gui.credit.commit.approve_all"),
                b -> approveAll())
            .bounds(this.width / 2 - btnW - 110, btnY, btnW, 20)
            .build());
        addRenderableWidget(Button.builder(
                Component.translatable("gui.credit.commit.reject_all"),
                b -> rejectAll())
            .bounds(this.width / 2 - btnW / 2, btnY, btnW, 20)
            .build());
        addRenderableWidget(Button.builder(
                Component.translatable("gui.credit.commit.close"),
                b -> onClose())
            .bounds(this.width / 2 + 110, btnY, btnW, 20)
            .build());
    }

    private void approveAll() {
        for (StagedChange c : StagingArea.INSTANCE.all()) c.committed = true;
        StagingPersistence.save();
    }

    private void rejectAll() {
        StagingArea.INSTANCE.clear();
        StagingPersistence.save();
        scrollOffset = 0;
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        renderBackground(g);
        super.render(g, mouseX, mouseY, partialTick);

        // Header
        Component title = getTitle();
        g.drawCenteredString(font, title, this.width / 2, 16, 0xFFFFFFFF);
        int total = StagingArea.INSTANCE.size();
        int committed = StagingArea.INSTANCE.committedCount();
        Component summary = Component.translatable("gui.credit.commit.summary",
            total, committed, total - committed);
        g.drawCenteredString(font, summary, this.width / 2, 30, 0xFFAAAAAA);

        // List background
        g.fill(listX - 2, listY - 2, listX + listW + 2, listY + listH + 2, 0xFF1A1A2E);

        List<StagedChange> all = StagingArea.INSTANCE.all();
        if (all.isEmpty()) {
            Component empty = Component.translatable("gui.credit.commit.empty")
                .withStyle(ChatFormatting.DARK_GRAY);
            g.drawCenteredString(font, empty, this.width / 2, listY + listH / 2, 0xFF666666);
            renderHint(g);
            return;
        }
        // Clamp scroll
        int maxScroll = Math.max(0, all.size() - visibleRows);
        if (scrollOffset > maxScroll) scrollOffset = maxScroll;
        if (scrollOffset < 0) scrollOffset = 0;

        for (int i = 0; i < visibleRows; i++) {
            int idx = scrollOffset + i;
            if (idx >= all.size()) break;
            renderRow(g, all.get(idx), listY + i * ROW_H, mouseX, mouseY);
        }

        // Scrollbar (簡素)
        if (all.size() > visibleRows) {
            int sbX = listX + listW - 4;
            int sbTop = listY;
            int sbH = listH;
            g.fill(sbX, sbTop, sbX + 3, sbTop + sbH, 0xFF333344);
            int knobH = Math.max(8, sbH * visibleRows / all.size());
            int knobY = sbTop + sbH * scrollOffset / Math.max(1, all.size());
            g.fill(sbX, knobY, sbX + 3, knobY + knobH, 0xFF8888AA);
        }

        renderHint(g);
    }

    private void renderRow(GuiGraphics g, StagedChange c, int y, int mouseX, int mouseY) {
        boolean rowHover = mouseX >= listX && mouseX < listX + listW
                        && mouseY >= y && mouseY < y + ROW_H;
        if (rowHover) g.fill(listX, y, listX + listW, y + ROW_H, 0x33FFFFFF);

        // Checkbox
        int cbX = listX + LIST_PAD;
        int cbY = y + (ROW_H - CHECK_SIZE) / 2;
        boolean cbHover = mouseX >= cbX && mouseX < cbX + CHECK_SIZE
                       && mouseY >= cbY && mouseY < cbY + CHECK_SIZE;
        int cbBg = cbHover ? 0xFFFFFFFF : (c.committed ? 0xFF44CC44 : 0xFF555566);
        g.fill(cbX, cbY, cbX + CHECK_SIZE, cbY + CHECK_SIZE, cbBg);
        if (c.committed) {
            g.drawString(font, "✓", cbX + 2, cbY + 2, 0xFF000000, false);
        }

        // Time
        int tx = cbX + CHECK_SIZE + 8;
        String time = TIME_FMT.format(Instant.ofEpochMilli(c.timestamp));
        g.drawString(font, time, tx, y + 5, 0xFFAAAAAA, false);

        // Kind tag
        int kindX = tx + font.width(time) + 8;
        int kindColor = switch (c.kind) {
            case ADD    -> 0xFF66FF66;
            case EDIT   -> 0xFF66CCFF;
            case DELETE -> 0xFFFF6666;
        };
        String kindStr = "[" + c.kind.name() + "]";
        g.drawString(font, kindStr, kindX, y + 5, kindColor, false);

        // Recipe ID (truncate if too long)
        int idX = kindX + font.width(kindStr) + 8;
        int idMaxW = listX + listW - idX - X_SIZE - 16;
        String recipeStr = c.recipeId;
        if (font.width(recipeStr) > idMaxW) {
            // ellipsize from middle
            while (font.width(recipeStr + "...") > idMaxW && recipeStr.length() > 4) {
                int mid = recipeStr.length() / 2;
                recipeStr = recipeStr.substring(0, mid - 1) + recipeStr.substring(mid + 1);
            }
            recipeStr = recipeStr + "...";
        }
        g.drawString(font, recipeStr, idX, y + 5, 0xFFFFFFFF, false);

        // X button
        int xX = listX + listW - X_SIZE - 4;
        int xY = y + (ROW_H - X_SIZE) / 2;
        boolean xHover = mouseX >= xX && mouseX < xX + X_SIZE
                      && mouseY >= xY && mouseY < xY + X_SIZE;
        g.fill(xX, xY, xX + X_SIZE, xY + X_SIZE, xHover ? 0xFFFF6666 : 0xFF552222);
        g.drawString(font, "×", xX + 3, xY + 2, 0xFFFFFFFF, false);
    }

    private void renderHint(GuiGraphics g) {
        Component hint = Component.translatable("gui.credit.commit.hint")
            .withStyle(ChatFormatting.DARK_GRAY);
        g.drawCenteredString(font, hint, this.width / 2, this.height - FOOTER_H + 32, 0xFF666666);
    }

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        if (button == 0) {
            List<StagedChange> all = StagingArea.INSTANCE.all();
            for (int i = 0; i < visibleRows; i++) {
                int idx = scrollOffset + i;
                if (idx >= all.size()) break;
                int y = listY + i * ROW_H;
                StagedChange c = all.get(idx);
                // Checkbox click
                int cbX = listX + LIST_PAD;
                int cbY = y + (ROW_H - CHECK_SIZE) / 2;
                if (mx >= cbX && mx < cbX + CHECK_SIZE && my >= cbY && my < cbY + CHECK_SIZE) {
                    c.committed = !c.committed;
                    StagingPersistence.save();
                    return true;
                }
                // X click
                int xX = listX + listW - X_SIZE - 4;
                int xY = y + (ROW_H - X_SIZE) / 2;
                if (mx >= xX && mx < xX + X_SIZE && my >= xY && my < xY + X_SIZE) {
                    StagingArea.INSTANCE.remove(c.id);
                    StagingPersistence.save();
                    return true;
                }
            }
        }
        return super.mouseClicked(mx, my, button);
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double delta) {
        if (mx >= listX && mx < listX + listW && my >= listY && my < listY + listH) {
            scrollOffset -= (int) delta;
            int maxScroll = Math.max(0, StagingArea.INSTANCE.size() - visibleRows);
            scrollOffset = Math.max(0, Math.min(scrollOffset, maxScroll));
            return true;
        }
        return super.mouseScrolled(mx, my, delta);
    }

    @Override
    public void onClose() {
        StagingPersistence.save();
        if (this.minecraft != null) this.minecraft.setScreen(parent);
    }

    @Override
    public boolean isPauseScreen() { return false; }
}
