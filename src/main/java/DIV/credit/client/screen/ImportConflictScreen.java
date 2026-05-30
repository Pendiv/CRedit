package DIV.credit.client.screen;

import DIV.credit.client.importer.ImportedRecipe;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * v2.1.2: /credit import の重複解消 UI。
 * <p>conflict が 1 件以上ある場合に開かれる。1 画面に 1 conflict、左 = import 側、右 = existing 側。
 * 各 conflict に対し Keep import / Keep existing / Skip を選択し、最後に "Apply" で
 * caller (ImportRunner) に決定マップを返す。non-conflict は自動 accept される (このスクリーンでは扱わない)。
 */
public class ImportConflictScreen extends Screen {
    /** 1.21: renderBackground は常に blur(renderBlurredBackground)+menu背景を描くため、 1.20.1 相当の透明 dark gradient に差し替え(曇り回避)。 */
    @Override
    public void renderBackground(net.minecraft.client.gui.GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        this.renderTransparentBackground(g);
    }


    /** 1 conflict 単位の決定。 */
    public enum Decision { KEEP_IMPORT, KEEP_EXISTING, SKIP }

    private static final int HEADER_H = 50;
    private static final int FOOTER_H = 64;

    private final List<Conflict> conflicts;
    private final int nonConflictCount;
    private final Map<Conflict, Decision> decisions = new HashMap<>();
    private final Consumer<Map<Conflict, Decision>> onApply;
    @Nullable private final Screen parent;
    private int currentIndex = 0;

    /** UI に渡すデータ。 */
    public record Conflict(ImportedRecipe imported, List<ImportedRecipe> existing) {}

    public ImportConflictScreen(@Nullable Screen parent, List<Conflict> conflicts,
                                int nonConflictCount,
                                Consumer<Map<Conflict, Decision>> onApply) {
        super(Component.translatable("gui.credit.import.conflict.title"));
        this.parent = parent;
        this.conflicts = conflicts;
        this.nonConflictCount = nonConflictCount;
        this.onApply = onApply;
        for (Conflict c : conflicts) decisions.put(c, Decision.KEEP_IMPORT);
    }

    @Override
    protected void init() {
        super.init();
        rebuildButtons();
    }

    private void rebuildButtons() {
        clearWidgets();
        int btnY1 = this.height - FOOTER_H + 4;
        int btnY2 = this.height - 28;

        // 決定ボタン (3 つ、現在の conflict 用)
        int decW = 110;
        int decGap = 6;
        int totalDecW = decW * 3 + decGap * 2;
        int decX0 = (this.width - totalDecW) / 2;
        addRenderableWidget(Button.builder(
                Component.translatable("gui.credit.import.conflict.keep_import"),
                b -> setDecision(Decision.KEEP_IMPORT))
            .bounds(decX0, btnY1, decW, 20).build());
        addRenderableWidget(Button.builder(
                Component.translatable("gui.credit.import.conflict.keep_existing"),
                b -> setDecision(Decision.KEEP_EXISTING))
            .bounds(decX0 + decW + decGap, btnY1, decW, 20).build());
        addRenderableWidget(Button.builder(
                Component.translatable("gui.credit.import.conflict.skip"),
                b -> setDecision(Decision.SKIP))
            .bounds(decX0 + 2 * (decW + decGap), btnY1, decW, 20).build());

        // ナビ + apply
        int navW = 70;
        int applyW = 110;
        int cancelW = 70;
        int totalNavW = navW + applyW + cancelW + navW + 18;
        int navX0 = (this.width - totalNavW) / 2;

        addRenderableWidget(Button.builder(
                Component.literal("< Prev"),
                b -> { if (currentIndex > 0) { currentIndex--; rebuildButtons(); } })
            .bounds(navX0, btnY2, navW, 20).build());
        addRenderableWidget(Button.builder(
                Component.translatable("gui.credit.import.conflict.apply",
                    countByDecision(Decision.KEEP_IMPORT) + nonConflictCount),
                b -> { onApply.accept(decisions); onClose(); })
            .bounds(navX0 + navW + 6, btnY2, applyW, 20).build());
        addRenderableWidget(Button.builder(
                Component.translatable("gui.credit.import.conflict.cancel"),
                b -> onClose())
            .bounds(navX0 + navW + 6 + applyW + 6, btnY2, cancelW, 20).build());
        addRenderableWidget(Button.builder(
                Component.literal("Next >"),
                b -> { if (currentIndex < conflicts.size() - 1) { currentIndex++; rebuildButtons(); } })
            .bounds(navX0 + navW + 6 + applyW + 6 + cancelW + 6, btnY2, navW, 20).build());
    }

    private void setDecision(Decision d) {
        if (conflicts.isEmpty()) return;
        decisions.put(conflicts.get(currentIndex), d);
        rebuildButtons();
    }

    private int countByDecision(Decision d) {
        int n = 0;
        for (Decision v : decisions.values()) if (v == d) n++;
        return n;
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        renderBackground(g, mouseX, mouseY, partialTick);  // 1.21: 4 引数
        super.render(g, mouseX, mouseY, partialTick);

        // Header
        g.drawCenteredString(font, getTitle(), this.width / 2, 8, 0xFFFFFFFF);
        Component sub = Component.translatable("gui.credit.import.conflict.summary",
            currentIndex + 1, conflicts.size(), nonConflictCount);
        g.drawCenteredString(font, sub, this.width / 2, 22, 0xFFAAAAAA);

        if (conflicts.isEmpty()) {
            g.drawCenteredString(font, Component.translatable("gui.credit.import.conflict.none"),
                this.width / 2, this.height / 2, 0xFF808080);
            return;
        }

        Conflict cur = conflicts.get(currentIndex);
        Decision dec = decisions.getOrDefault(cur, Decision.KEEP_IMPORT);

        // Decision indicator
        Component decLabel = Component.translatable("gui.credit.import.conflict.current_decision",
            Component.translatable(switch (dec) {
                case KEEP_IMPORT -> "gui.credit.import.conflict.keep_import";
                case KEEP_EXISTING -> "gui.credit.import.conflict.keep_existing";
                case SKIP -> "gui.credit.import.conflict.skip";
            }));
        int decColor = switch (dec) {
            case KEEP_IMPORT -> 0xFF55FF55;
            case KEEP_EXISTING -> 0xFFFFAA55;
            case SKIP -> 0xFFAAAAAA;
        };
        g.drawCenteredString(font, decLabel, this.width / 2, 36, decColor);

        // 並べて比較
        int halfW = (this.width - 24) / 2;
        int paneY = HEADER_H + 4;
        int paneH = this.height - HEADER_H - FOOTER_H - 4;
        int leftX = 8;
        int rightX = this.width - 8 - halfW;

        drawPane(g, leftX, paneY, halfW, paneH,
            Component.translatable("gui.credit.import.conflict.side_import"),
            cur.imported(), ChatFormatting.GREEN);

        // existing 側は 1 つ以上ある。最初の 1 件のみ展示 (簡易)。複数あれば notice 行表示。
        ImportedRecipe ex = cur.existing().isEmpty() ? null : cur.existing().get(0);
        drawPane(g, rightX, paneY, halfW, paneH,
            Component.translatable("gui.credit.import.conflict.side_existing"),
            ex, ChatFormatting.GOLD);
        if (cur.existing().size() > 1) {
            g.drawString(font, Component.translatable(
                "gui.credit.import.conflict.multi_existing", cur.existing().size()),
                rightX + 4, paneY + paneH - 12, 0xFFFFAA00, false);
        }
    }

    private void drawPane(GuiGraphics g, int x, int y, int w, int h,
                          Component title, @Nullable ImportedRecipe r, ChatFormatting accent) {
        g.fill(x, y, x + w, y + h, 0x80000000);
        g.fill(x, y, x + w, y + 14, 0xC0000000);
        g.drawString(font, title.copy().withStyle(accent), x + 4, y + 3, 0xFFFFFFFF, false);

        if (r == null) {
            g.drawString(font, "(none)", x + 6, y + 22, 0xFF808080, false);
            return;
        }

        int cy = y + 18;
        line(g, x + 6, cy, "kind   : " + r.kind().name()); cy += 10;
        line(g, x + 6, cy, "modid  : " + r.modid()); cy += 10;
        line(g, x + 6, cy, "id     : " + abbreviate(r.recipeId(), 36)); cy += 10;
        if (r.origRecipeId() != null) {
            line(g, x + 6, cy, "orig   : " + abbreviate(r.origRecipeId(), 36)); cy += 10;
        }
        if (r.outputId() != null) {
            line(g, x + 6, cy, "output : " + abbreviate(r.outputId(), 36)); cy += 10;
        }
        if (r.recipeTypeId() != null) {
            line(g, x + 6, cy, "type   : " + abbreviate(r.recipeTypeId(), 36)); cy += 10;
        }
        cy += 4;
        line(g, x + 6, cy, "── codeBody ──"); cy += 10;
        int maxLines = Math.max(1, (y + h - 8 - cy) / 9);
        String[] lines = r.codeBody().split("\n", -1);
        int n = Math.min(lines.length, maxLines);
        for (int i = 0; i < n; i++) {
            String s = lines[i].length() > (w / 5) ? lines[i].substring(0, w / 5) + "…" : lines[i];
            g.drawString(font, s, x + 6, cy, 0xFFCCCCCC, false);
            cy += 9;
        }
        if (lines.length > n) {
            g.drawString(font, "… (+" + (lines.length - n) + " lines)", x + 6, cy, 0xFF888888, false);
        }
    }

    private void line(GuiGraphics g, int x, int y, String s) {
        g.drawString(font, s, x, y, 0xFFDDDDDD, false);
    }

    private static String abbreviate(String s, int max) {
        if (s == null) return "";
        if (s.length() <= max) return s;
        return "…" + s.substring(s.length() - max + 1);
    }

    @Override
    public boolean keyPressed(int key, int scan, int mods) {
        // 左右キーで前後移動
        if (key == 263 /* LEFT */ && currentIndex > 0) { currentIndex--; rebuildButtons(); return true; }
        if (key == 262 /* RIGHT */ && currentIndex < conflicts.size() - 1) { currentIndex++; rebuildButtons(); return true; }
        // 1 / 2 / 3 で決定
        if (key == 49 /* 1 */) { setDecision(Decision.KEEP_IMPORT); return true; }
        if (key == 50 /* 2 */) { setDecision(Decision.KEEP_EXISTING); return true; }
        if (key == 51 /* 3 */) { setDecision(Decision.SKIP); return true; }
        return super.keyPressed(key, scan, mods);
    }

    @Override
    public void onClose() {
        if (this.minecraft != null) this.minecraft.setScreen(parent);
    }

    /**
     * 決定マップから採用する import recipe 一覧を生成。
     * <ul>
     *   <li>KEEP_IMPORT  → conflict.imported を採用</li>
     *   <li>KEEP_EXISTING → existing[] のうち import 由来の最初の 1 件を採用 (in-source dup ケース)。
     *       generated/ 由来 (= 既に file にある) なら何もしない (既存維持)。</li>
     *   <li>SKIP → 何もしない</li>
     * </ul>
     */
    public static List<ImportedRecipe> acceptedFromDecisions(
        List<Conflict> conflicts, Map<Conflict, Decision> decisions) {
        List<ImportedRecipe> out = new ArrayList<>();
        for (Conflict c : conflicts) {
            Decision d = decisions.getOrDefault(c, Decision.KEEP_IMPORT);
            switch (d) {
                case KEEP_IMPORT -> out.add(c.imported());
                case KEEP_EXISTING -> {
                    for (ImportedRecipe ex : c.existing()) {
                        if (isFromImportFolder(ex)) { out.add(ex); break; }
                    }
                }
                case SKIP -> {}
            }
        }
        return out;
    }

    /** sourceFile が <minecraft>/credit/import/ 配下 = in-source 由来。 */
    private static boolean isFromImportFolder(ImportedRecipe r) {
        if (r.sourceFile() == null) return false;
        String s = r.sourceFile().toString().replace('\\', '/');
        return s.contains("/credit/import/");
    }
}
