package DIV.credit.client.screen;

import DIV.credit.Credit;
import DIV.credit.CreditConfig;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.List;

/**
 * 設定画面（gear ボタンから開く）。
 * 値の永続化は Forge ConfigSpec 経由で <game>/config/credit-client.toml に。
 */
public class SettingsScreen extends Screen {

    private final Screen parent;
    private EditBox dumpRootBox;
    private EditBox fluidDefaultBox;
    private EditBox gasDefaultBox;

    /** 現在の編集中の persistence 値。Save 押下で永続化。 */
    private CreditConfig.EditPersistence persistence;
    /** SHAPED/SHAPELESS スロット共有モード。 */
    private boolean craftingShareSlots;
    /** Undo tracking on/off。 */
    private boolean undoEnabled;

    private int persistenceSlotX = -1, persistenceSlotY = -1;
    private int shareSlotX = -1, shareSlotY = -1;
    private int undoSlotX = -1, undoSlotY = -1;
    private static final int SLOT_SIZE = 16;

    public SettingsScreen(Screen parent) {
        super(Component.literal("Credit Settings"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        super.init();
        int cx = this.width / 2;
        int boxW = 240;
        int leftX = cx - boxW / 2;
        int smallBoxW = 80;

        // Dump root field
        int y = 50;
        this.dumpRootBox = new EditBox(this.font, leftX, y, boxW, 16, Component.literal("dumpRoot"));
        this.dumpRootBox.setMaxLength(200);
        this.dumpRootBox.setValue(CreditConfig.DUMP_ROOT.get());
        addRenderableWidget(this.dumpRootBox);

        // Fluid default amount
        y = 100;
        this.fluidDefaultBox = new EditBox(this.font, leftX, y, smallBoxW, 16, Component.literal("fluidDefault"));
        this.fluidDefaultBox.setMaxLength(10);
        this.fluidDefaultBox.setValue(String.valueOf(CreditConfig.FLUID_DEFAULT_AMOUNT.get()));
        addRenderableWidget(this.fluidDefaultBox);

        // Gas default amount
        y = 140;
        this.gasDefaultBox = new EditBox(this.font, leftX, y, smallBoxW, 16, Component.literal("gasDefault"));
        this.gasDefaultBox.setMaxLength(10);
        this.gasDefaultBox.setValue(String.valueOf(CreditConfig.GAS_DEFAULT_AMOUNT.get()));
        addRenderableWidget(this.gasDefaultBox);

        // Edit persistence row
        this.persistence = CreditConfig.EDIT_PERSISTENCE.get();
        Component persistLabel = Component.translatable("gui.credit.settings.persistence.label");
        this.persistenceSlotX = leftX + font.width(persistLabel) + 6;
        this.persistenceSlotY = 178;

        // Crafting share row
        this.craftingShareSlots = CreditConfig.CRAFTING_SHARE_SLOTS.get();
        Component shareLabel = Component.translatable("gui.credit.settings.crafting_share.label");
        this.shareSlotX = leftX + font.width(shareLabel) + 6;
        this.shareSlotY = 200;

        // Undo enabled row
        this.undoEnabled = CreditConfig.UNDO_ENABLED.get();
        Component undoLabel = Component.translatable("gui.credit.settings.undo.label");
        this.undoSlotX = leftX + font.width(undoLabel) + 6;
        this.undoSlotY = 222;

        // Destructive action buttons (red label)
        int destBtnW = 220;
        int destBtnX = cx - destBtnW / 2;
        addRenderableWidget(Button.builder(
                Component.translatable("gui.credit.settings.delete_data.label")
                    .withStyle(ChatFormatting.RED),
                b -> tryDeleteEditData())
            .bounds(destBtnX, 246, destBtnW, 20)
            .build());
        addRenderableWidget(Button.builder(
                Component.translatable("gui.credit.settings.reset.label")
                    .withStyle(ChatFormatting.RED),
                b -> tryResetSettings())
            .bounds(destBtnX, 270, destBtnW, 20)
            .build());

        // Save + Cancel buttons
        int btnY = 296;
        addRenderableWidget(Button.builder(Component.literal("Save"), b -> save())
            .bounds(cx - 80, btnY, 70, 20).build());
        addRenderableWidget(Button.builder(Component.literal("Cancel"), b -> onClose())
            .bounds(cx + 10, btnY, 70, 20).build());
    }

    // ───── Save / Reset / Delete ─────

    private void save() {
        String d = dumpRootBox.getValue().trim();
        if (d.isBlank()) d = "kubejs/server_scripts";
        CreditConfig.DUMP_ROOT.set(d);
        CreditConfig.DUMP_ROOT.save();

        try {
            int f = Integer.parseInt(fluidDefaultBox.getValue().trim());
            if (f < 1) f = 1;
            CreditConfig.FLUID_DEFAULT_AMOUNT.set(f);
            CreditConfig.FLUID_DEFAULT_AMOUNT.save();
        } catch (NumberFormatException ignored) {}
        try {
            int g = Integer.parseInt(gasDefaultBox.getValue().trim());
            if (g < 1) g = 1;
            CreditConfig.GAS_DEFAULT_AMOUNT.set(g);
            CreditConfig.GAS_DEFAULT_AMOUNT.save();
        } catch (NumberFormatException ignored) {}

        if (persistence != CreditConfig.EDIT_PERSISTENCE.get()) {
            CreditConfig.EDIT_PERSISTENCE.set(persistence);
            CreditConfig.EDIT_PERSISTENCE.save();
        }
        if (craftingShareSlots != CreditConfig.CRAFTING_SHARE_SLOTS.get()) {
            CreditConfig.CRAFTING_SHARE_SLOTS.set(craftingShareSlots);
            CreditConfig.CRAFTING_SHARE_SLOTS.save();
        }
        if (undoEnabled != CreditConfig.UNDO_ENABLED.get()) {
            CreditConfig.UNDO_ENABLED.set(undoEnabled);
            CreditConfig.UNDO_ENABLED.save();
        }

        Credit.LOGGER.info("[CraftPattern] Saved settings: dumpRoot={} fluid={} gas={} persistence={} share={} undo={}",
            CreditConfig.DUMP_ROOT.get(), CreditConfig.FLUID_DEFAULT_AMOUNT.get(),
            CreditConfig.GAS_DEFAULT_AMOUNT.get(), CreditConfig.EDIT_PERSISTENCE.get(),
            CreditConfig.CRAFTING_SHARE_SLOTS.get(), CreditConfig.UNDO_ENABLED.get());
        onClose();
    }

    /** 編集データ削除：Shift+クリック必須。draft 全消去 + 永続化ファイル削除 + undo 履歴クリア。 */
    private void tryDeleteEditData() {
        if (!Screen.hasShiftDown()) {
            announce(Component.translatable("gui.credit.settings.delete_data.confirm")
                .withStyle(ChatFormatting.YELLOW));
            return;
        }
        BuilderScreen.clearAllDraftData();
        DIV.credit.client.undo.UndoHistory.INSTANCE.clear();
        // 永続化ファイル削除（再起動時にゴーストが復活しないよう）
        try {
            java.nio.file.Path file = DIV.credit.client.draft.DraftPersistence.file();
            if (java.nio.file.Files.exists(file)) {
                java.nio.file.Files.delete(file);
                Credit.LOGGER.info("[CraftPattern] Deleted persistence file: {}", file);
            }
        } catch (Exception e) {
            Credit.LOGGER.warn("[CraftPattern] Failed to delete persistence file: {}", e.toString());
        }
        announce(Component.translatable("gui.credit.settings.delete_data.done")
            .withStyle(ChatFormatting.GREEN));
    }

    /** 設定リセット：Shift+クリック必須。すべての ConfigValue を default 値に。 */
    private void tryResetSettings() {
        if (!Screen.hasShiftDown()) {
            announce(Component.translatable("gui.credit.settings.reset.confirm")
                .withStyle(ChatFormatting.YELLOW));
            return;
        }
        CreditConfig.DUMP_ROOT.set(CreditConfig.DUMP_ROOT.getDefault());
        CreditConfig.DUMP_ROOT.save();
        CreditConfig.FLUID_DEFAULT_AMOUNT.set(CreditConfig.FLUID_DEFAULT_AMOUNT.getDefault());
        CreditConfig.FLUID_DEFAULT_AMOUNT.save();
        CreditConfig.GAS_DEFAULT_AMOUNT.set(CreditConfig.GAS_DEFAULT_AMOUNT.getDefault());
        CreditConfig.GAS_DEFAULT_AMOUNT.save();
        CreditConfig.EDIT_PERSISTENCE.set(CreditConfig.EDIT_PERSISTENCE.getDefault());
        CreditConfig.EDIT_PERSISTENCE.save();
        CreditConfig.CRAFTING_SHARE_SLOTS.set(CreditConfig.CRAFTING_SHARE_SLOTS.getDefault());
        CreditConfig.CRAFTING_SHARE_SLOTS.save();
        CreditConfig.UNDO_ENABLED.set(CreditConfig.UNDO_ENABLED.getDefault());
        CreditConfig.UNDO_ENABLED.save();
        // UI も新値で再描画
        Minecraft.getInstance().setScreen(new SettingsScreen(parent));
        announce(Component.translatable("gui.credit.settings.reset.done")
            .withStyle(ChatFormatting.GREEN));
    }

    private static void announce(Component msg) {
        var p = Minecraft.getInstance().player;
        if (p != null) p.displayClientMessage(msg, false);
    }

    // ───── Mouse handling ─────

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        if (button == 0 && persistenceSlotX >= 0
            && mx >= persistenceSlotX && mx < persistenceSlotX + SLOT_SIZE
            && my >= persistenceSlotY && my < persistenceSlotY + SLOT_SIZE) {
            cyclePersistence();
            return true;
        }
        if (button == 0 && shareSlotX >= 0
            && mx >= shareSlotX && mx < shareSlotX + SLOT_SIZE
            && my >= shareSlotY && my < shareSlotY + SLOT_SIZE) {
            craftingShareSlots = !craftingShareSlots;
            return true;
        }
        if (button == 0 && undoSlotX >= 0
            && mx >= undoSlotX && mx < undoSlotX + SLOT_SIZE
            && my >= undoSlotY && my < undoSlotY + SLOT_SIZE) {
            undoEnabled = !undoEnabled;
            return true;
        }
        return super.mouseClicked(mx, my, button);
    }

    private void cyclePersistence() {
        var values = CreditConfig.EditPersistence.values();
        int idx = (persistence.ordinal() + 1) % values.length;
        persistence = values[idx];
    }

    // ───── Render ─────

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        renderBackground(g);
        super.render(g, mouseX, mouseY, partialTick);
        int cx = this.width / 2;
        int leftX = cx - 120;
        // Title
        g.drawCenteredString(this.font, this.title, cx, 20, 0xFFFFFFFF);
        // Labels above each box
        g.drawString(this.font, Component.literal("Dump destination (relative to game dir):")
            .withStyle(ChatFormatting.GRAY), leftX, 38, 0xFFAAAAAA, false);
        g.drawString(this.font, Component.literal("Default fluid amount on JEI drag (mB):")
            .withStyle(ChatFormatting.GRAY), leftX, 88, 0xFFAAAAAA, false);
        g.drawString(this.font, Component.literal("Default gas amount on JEI drag (mB):")
            .withStyle(ChatFormatting.GRAY), leftX, 128, 0xFFAAAAAA, false);
        // Hint
        g.drawString(this.font, Component.literal("Files: <root>/generated/<namespace>/<path>.js")
            .withStyle(ChatFormatting.DARK_GRAY), leftX, 70, 0xFF777777, false);

        // Persistence row: label + slot
        Component persistLabel = Component.translatable("gui.credit.settings.persistence.label");
        int labelY = persistenceSlotY + (SLOT_SIZE - font.lineHeight) / 2;
        g.drawString(this.font, persistLabel, leftX, labelY, 0xFFE0E0E0, false);
        renderPersistenceSlot(g, mouseX, mouseY);
        // Crafting share row
        Component shareLabel = Component.translatable("gui.credit.settings.crafting_share.label");
        int shareLabelY = shareSlotY + (SLOT_SIZE - font.lineHeight) / 2;
        g.drawString(this.font, shareLabel, leftX, shareLabelY, 0xFFE0E0E0, false);
        renderShareSlot(g, mouseX, mouseY);
        // Undo enabled row
        Component undoLabel = Component.translatable("gui.credit.settings.undo.label");
        int undoLabelY = undoSlotY + (SLOT_SIZE - font.lineHeight) / 2;
        g.drawString(this.font, undoLabel, leftX, undoLabelY, 0xFFE0E0E0, false);
        renderUndoSlot(g, mouseX, mouseY);
    }

    // ───── Slot renderers ─────

    private void renderPersistenceSlot(GuiGraphics g, int mouseX, int mouseY) {
        int sx = persistenceSlotX, sy = persistenceSlotY;
        boolean hover = isInRect(mouseX, mouseY, sx, sy, SLOT_SIZE, SLOT_SIZE);
        g.fill(sx - 1, sy - 1, sx + SLOT_SIZE + 1, sy + SLOT_SIZE + 1, 0xFF8B8B8B);
        g.fill(sx,     sy,     sx + SLOT_SIZE,     sy + SLOT_SIZE,     0xFF373737);
        int level = persistence.ordinal() + 1;
        for (int i = 0; i < 3; i++) {
            int barX = sx + 3 + i * 4;
            int barH = 3 + i * 3;
            int barY = sy + SLOT_SIZE - 2 - barH;
            int color = (i < level) ? colorFor(persistence) : 0xFF555555;
            g.fill(barX, barY, barX + 3, barY + barH, color);
        }
        if (hover) {
            g.fill(sx, sy, sx + SLOT_SIZE, sy + SLOT_SIZE, 0x33FFFFFF);
            renderPersistenceTooltip(g, mouseX, mouseY);
        }
    }

    private void renderShareSlot(GuiGraphics g, int mouseX, int mouseY) {
        renderToggleSlot(g, mouseX, mouseY, shareSlotX, shareSlotY, craftingShareSlots,
            "gui.credit.settings.crafting_share.current",
            "gui.credit.settings.crafting_share.desc");
    }

    private void renderUndoSlot(GuiGraphics g, int mouseX, int mouseY) {
        renderToggleSlot(g, mouseX, mouseY, undoSlotX, undoSlotY, undoEnabled,
            "gui.credit.settings.undo.current",
            "gui.credit.settings.undo.desc");
    }

    /** ON/OFF 二値スロット共通描画。チェックマーク (ON) / dash (OFF) + hover tooltip。 */
    private void renderToggleSlot(GuiGraphics g, int mouseX, int mouseY,
                                  int sx, int sy, boolean on,
                                  String currentKey, String descKey) {
        boolean hover = isInRect(mouseX, mouseY, sx, sy, SLOT_SIZE, SLOT_SIZE);
        g.fill(sx - 1, sy - 1, sx + SLOT_SIZE + 1, sy + SLOT_SIZE + 1, 0xFF8B8B8B);
        g.fill(sx,     sy,     sx + SLOT_SIZE,     sy + SLOT_SIZE,     0xFF373737);
        if (on) {
            g.fill(sx + 4, sy + 8,  sx + 6,  sy + 12, 0xFF66FF88);
            g.fill(sx + 6, sy + 10, sx + 8,  sy + 13, 0xFF66FF88);
            g.fill(sx + 8, sy + 4,  sx + 13, sy + 11, 0xFF66FF88);
        } else {
            g.fill(sx + 4, sy + 7, sx + 12, sy + 9, 0xFF777777);
        }
        if (hover) {
            g.fill(sx, sy, sx + SLOT_SIZE, sy + SLOT_SIZE, 0x33FFFFFF);
            Component header = Component.translatable(currentKey,
                Component.literal(on ? "ON" : "OFF")
                    .withStyle(on ? ChatFormatting.GREEN : ChatFormatting.GRAY))
                .withStyle(ChatFormatting.WHITE);
            Component desc = Component.translatable(descKey).withStyle(ChatFormatting.GRAY);
            Component hint = Component.translatable("gui.credit.settings.persistence.hint")
                .withStyle(ChatFormatting.DARK_GRAY);
            g.renderComponentTooltip(font, List.of(header, desc, hint), mouseX, mouseY);
        }
    }

    private static int colorFor(CreditConfig.EditPersistence p) {
        return switch (p) {
            case MIN    -> 0xFFFF6666;
            case NORMAL -> 0xFFFFEE66;
            case MAX    -> 0xFF66FF88;
        };
    }

    private void renderPersistenceTooltip(GuiGraphics g, int mouseX, int mouseY) {
        Component name = Component.translatable(nameKey(persistence));
        Component header = Component.translatable("gui.credit.settings.persistence.current", name)
            .withStyle(ChatFormatting.WHITE);
        Component desc = Component.translatable(descKey(persistence))
            .withStyle(ChatFormatting.GRAY);
        Component hint = Component.translatable("gui.credit.settings.persistence.hint")
            .withStyle(ChatFormatting.DARK_GRAY);
        g.renderComponentTooltip(font, List.of(header, desc, hint), mouseX, mouseY);
    }

    private static String nameKey(CreditConfig.EditPersistence p) {
        return switch (p) {
            case MIN    -> "gui.credit.settings.persistence.min.name";
            case NORMAL -> "gui.credit.settings.persistence.normal.name";
            case MAX    -> "gui.credit.settings.persistence.max.name";
        };
    }

    private static String descKey(CreditConfig.EditPersistence p) {
        return switch (p) {
            case MIN    -> "gui.credit.settings.persistence.min.desc";
            case NORMAL -> "gui.credit.settings.persistence.normal.desc";
            case MAX    -> "gui.credit.settings.persistence.max.desc";
        };
    }

    private static boolean isInRect(double mx, double my, int x, int y, int w, int h) {
        return mx >= x && mx < x + w && my >= y && my < y + h;
    }

    @Override
    public void onClose() {
        Minecraft.getInstance().setScreen(parent);
    }
}
