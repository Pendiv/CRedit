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
 * 設定画面（gear ボタン + /credit setting + /craftpattern_setting から開く）。
 * 値の永続化は Forge ConfigSpec 経由で <game>/config/credit-client.toml に。
 */
public class SettingsScreen extends Screen {

    private final Screen parent;
    private EditBox dumpRootBox;
    private EditBox fluidDefaultBox;
    private EditBox gasDefaultBox;

    private CreditConfig.EditPersistence persistence;
    private boolean craftingShareSlots;
    private boolean undoEnabled;

    // v2.2.0
    private boolean immediateMaster, immediateAdd, immediateEdit, immediateDelete;
    private CreditConfig.HistoryMax historyMax;
    private boolean autoReload;
    // v2.2.2
    private boolean omitModidPrefix;
    // v2.0.9
    private boolean preserveEditGrid;

    private int persistenceSlotX = -1, persistenceSlotY = -1;
    private int shareSlotX = -1, shareSlotY = -1;
    private int undoSlotX = -1, undoSlotY = -1;
    private int immMasterSX = -1, immMasterSY = -1;
    private int immAddSX = -1, immAddSY = -1;
    private int immEditSX = -1, immEditSY = -1;
    private int immDelSX = -1, immDelSY = -1;
    private int historySX = -1, historySY = -1;
    private int autoReloadSX = -1, autoReloadSY = -1;
    private int omitPrefixSX = -1, omitPrefixSY = -1;
    private int preserveGridSX = -1, preserveGridSY = -1;
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

        // ─── 既存項目 ───
        int y = 50;
        this.dumpRootBox = new EditBox(this.font, leftX, y, boxW, 16, Component.literal("dumpRoot"));
        this.dumpRootBox.setMaxLength(200);
        this.dumpRootBox.setValue(CreditConfig.DUMP_ROOT.get());
        addRenderableWidget(this.dumpRootBox);

        y = 92;
        this.fluidDefaultBox = new EditBox(this.font, leftX, y, smallBoxW, 16, Component.literal("fluidDefault"));
        this.fluidDefaultBox.setMaxLength(10);
        this.fluidDefaultBox.setValue(String.valueOf(CreditConfig.FLUID_DEFAULT_AMOUNT.get()));
        addRenderableWidget(this.fluidDefaultBox);

        y = 124;
        this.gasDefaultBox = new EditBox(this.font, leftX, y, smallBoxW, 16, Component.literal("gasDefault"));
        this.gasDefaultBox.setMaxLength(10);
        this.gasDefaultBox.setValue(String.valueOf(CreditConfig.GAS_DEFAULT_AMOUNT.get()));
        addRenderableWidget(this.gasDefaultBox);

        this.persistence = CreditConfig.EDIT_PERSISTENCE.get();
        Component persistLabel = Component.translatable("gui.credit.settings.persistence.label");
        this.persistenceSlotX = leftX + font.width(persistLabel) + 6;
        this.persistenceSlotY = 152;

        this.craftingShareSlots = CreditConfig.CRAFTING_SHARE_SLOTS.get();
        Component shareLabel = Component.translatable("gui.credit.settings.crafting_share.label");
        this.shareSlotX = leftX + font.width(shareLabel) + 6;
        this.shareSlotY = 174;

        this.undoEnabled = CreditConfig.UNDO_ENABLED.get();
        Component undoLabel = Component.translatable("gui.credit.settings.undo.label");
        this.undoSlotX = leftX + font.width(undoLabel) + 6;
        this.undoSlotY = 196;

        // ─── v2.2.0 immediate apply ───
        this.immediateMaster = CreditConfig.IMMEDIATE_APPLY_MASTER.get();
        this.immediateAdd    = CreditConfig.IMMEDIATE_APPLY_ADD.get();
        this.immediateEdit   = CreditConfig.IMMEDIATE_APPLY_EDIT.get();
        this.immediateDelete = CreditConfig.IMMEDIATE_APPLY_DELETE.get();

        Component masterLabel = Component.translatable("gui.credit.settings.imm.master");
        this.immMasterSX = leftX + font.width(masterLabel) + 6;
        this.immMasterSY = 220;

        // 子設定はインデント
        int childX = leftX + 12;
        Component immAddLabel = Component.translatable("gui.credit.settings.imm.add");
        this.immAddSX = childX + font.width(immAddLabel) + 6;
        this.immAddSY = 240;
        Component immEditLabel = Component.translatable("gui.credit.settings.imm.edit");
        this.immEditSX = childX + font.width(immEditLabel) + 6;
        this.immEditSY = 258;
        Component immDelLabel = Component.translatable("gui.credit.settings.imm.delete");
        this.immDelSX = childX + font.width(immDelLabel) + 6;
        this.immDelSY = 276;

        // History max
        this.historyMax = CreditConfig.HISTORY_MAX.get();
        Component histLabel = Component.translatable("gui.credit.settings.history.max");
        this.historySX = leftX + font.width(histLabel) + 6;
        this.historySY = 300;

        // Auto reload
        this.autoReload = CreditConfig.AUTO_RELOAD_AFTER_PUSH.get();
        Component arLabel = Component.translatable("gui.credit.settings.auto_reload");
        this.autoReloadSX = leftX + font.width(arLabel) + 6;
        this.autoReloadSY = 322;

        // v2.2.2 Omit modid prefix
        this.omitModidPrefix = CreditConfig.OMIT_MODID_PREFIX.get();
        Component opLabel = Component.translatable("gui.credit.settings.omit_prefix");
        this.omitPrefixSX = leftX + font.width(opLabel) + 6;
        this.omitPrefixSY = 344;

        // v2.0.9 preserve edit grid on switch
        this.preserveEditGrid = CreditConfig.PRESERVE_EDIT_GRID_ON_SWITCH.get();
        Component pgLabel = Component.translatable("gui.credit.settings.preserve_edit_grid");
        this.preserveGridSX = leftX + font.width(pgLabel) + 6;
        this.preserveGridSY = 366;

        // ─── 破壊的アクションボタン ───
        int destBtnW = 220;
        int destBtnX = cx - destBtnW / 2;
        addRenderableWidget(Button.builder(
                Component.translatable("gui.credit.settings.delete_data.label")
                    .withStyle(ChatFormatting.RED),
                b -> tryDeleteEditData())
            .bounds(destBtnX, 392, destBtnW, 20)
            .build());
        addRenderableWidget(Button.builder(
                Component.translatable("gui.credit.settings.delete_history.label")
                    .withStyle(ChatFormatting.RED),
                b -> tryDeleteHistory())
            .bounds(destBtnX, 416, destBtnW, 20)
            .build());
        addRenderableWidget(Button.builder(
                Component.translatable("gui.credit.settings.reset.label")
                    .withStyle(ChatFormatting.RED),
                b -> tryResetSettings())
            .bounds(destBtnX, 440, destBtnW, 20)
            .build());

        // Save + Cancel
        int btnY = 466;
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
        saveBool(CreditConfig.CRAFTING_SHARE_SLOTS, craftingShareSlots);
        saveBool(CreditConfig.UNDO_ENABLED, undoEnabled);
        saveBool(CreditConfig.IMMEDIATE_APPLY_MASTER, immediateMaster);
        saveBool(CreditConfig.IMMEDIATE_APPLY_ADD,    immediateAdd);
        saveBool(CreditConfig.IMMEDIATE_APPLY_EDIT,   immediateEdit);
        saveBool(CreditConfig.IMMEDIATE_APPLY_DELETE, immediateDelete);
        saveBool(CreditConfig.AUTO_RELOAD_AFTER_PUSH, autoReload);
        saveBool(CreditConfig.OMIT_MODID_PREFIX,      omitModidPrefix);
        saveBool(CreditConfig.PRESERVE_EDIT_GRID_ON_SWITCH, preserveEditGrid);
        if (historyMax != CreditConfig.HISTORY_MAX.get()) {
            CreditConfig.HISTORY_MAX.set(historyMax);
            CreditConfig.HISTORY_MAX.save();
        }

        Credit.LOGGER.info("[CraftPattern] Saved settings (v2.2.0)");
        onClose();
    }

    private static void saveBool(net.minecraftforge.common.ForgeConfigSpec.BooleanValue v, boolean val) {
        if (val != v.get()) {
            v.set(val);
            v.save();
        }
    }

    private void tryDeleteEditData() {
        if (!Screen.hasShiftDown()) {
            announce(Component.translatable("gui.credit.settings.delete_data.confirm")
                .withStyle(ChatFormatting.YELLOW));
            return;
        }
        BuilderScreen.clearAllDraftData();
        DIV.credit.client.undo.UndoHistory.INSTANCE.clear();
        try {
            java.nio.file.Path file = DIV.credit.client.draft.DraftPersistence.file();
            if (java.nio.file.Files.exists(file)) java.nio.file.Files.delete(file);
        } catch (Exception ignored) {}
        announce(Component.translatable("gui.credit.settings.delete_data.done")
            .withStyle(ChatFormatting.GREEN));
    }

    /** v2.2.0 履歴削除: Shift+クリック必須。HistoryStore + history.dat 両方クリア。 */
    private void tryDeleteHistory() {
        if (!Screen.hasShiftDown()) {
            announce(Component.translatable("gui.credit.settings.delete_history.confirm")
                .withStyle(ChatFormatting.YELLOW));
            return;
        }
        DIV.credit.client.history.HistoryStore.INSTANCE.clear();
        DIV.credit.client.history.ImmediateHistorySession.INSTANCE.flush();
        try {
            java.nio.file.Path file = DIV.credit.client.history.HistoryPersistence.file();
            if (java.nio.file.Files.exists(file)) java.nio.file.Files.delete(file);
        } catch (Exception ignored) {}
        announce(Component.translatable("gui.credit.settings.delete_history.done")
            .withStyle(ChatFormatting.GREEN));
    }

    private void tryResetSettings() {
        if (!Screen.hasShiftDown()) {
            announce(Component.translatable("gui.credit.settings.reset.confirm")
                .withStyle(ChatFormatting.YELLOW));
            return;
        }
        resetDef(CreditConfig.DUMP_ROOT);
        resetDef(CreditConfig.FLUID_DEFAULT_AMOUNT);
        resetDef(CreditConfig.GAS_DEFAULT_AMOUNT);
        resetDef(CreditConfig.EDIT_PERSISTENCE);
        resetDef(CreditConfig.CRAFTING_SHARE_SLOTS);
        resetDef(CreditConfig.UNDO_ENABLED);
        resetDef(CreditConfig.IMMEDIATE_APPLY_MASTER);
        resetDef(CreditConfig.IMMEDIATE_APPLY_ADD);
        resetDef(CreditConfig.IMMEDIATE_APPLY_EDIT);
        resetDef(CreditConfig.IMMEDIATE_APPLY_DELETE);
        resetDef(CreditConfig.HISTORY_MAX);
        resetDef(CreditConfig.AUTO_RELOAD_AFTER_PUSH);
        resetDef(CreditConfig.OMIT_MODID_PREFIX);
        resetDef(CreditConfig.PRESERVE_EDIT_GRID_ON_SWITCH);
        Minecraft.getInstance().setScreen(new SettingsScreen(parent));
        announce(Component.translatable("gui.credit.settings.reset.done")
            .withStyle(ChatFormatting.GREEN));
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static void resetDef(net.minecraftforge.common.ForgeConfigSpec.ConfigValue v) {
        v.set(v.getDefault());
        v.save();
    }

    private static void announce(Component msg) {
        var p = Minecraft.getInstance().player;
        if (p != null) p.displayClientMessage(msg, false);
    }

    // ───── Mouse handling ─────

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        if (button == 0) {
            if (hit(mx, my, persistenceSlotX, persistenceSlotY)) { cyclePersistence(); return true; }
            if (hit(mx, my, shareSlotX, shareSlotY))             { craftingShareSlots = !craftingShareSlots; return true; }
            if (hit(mx, my, undoSlotX, undoSlotY))               { undoEnabled = !undoEnabled; return true; }
            if (hit(mx, my, immMasterSX, immMasterSY))           { immediateMaster = !immediateMaster; return true; }
            if (hit(mx, my, immAddSX, immAddSY))                 { immediateAdd    = !immediateAdd; return true; }
            if (hit(mx, my, immEditSX, immEditSY))               { immediateEdit   = !immediateEdit; return true; }
            if (hit(mx, my, immDelSX, immDelSY))                 { immediateDelete = !immediateDelete; return true; }
            if (hit(mx, my, historySX, historySY))               { cycleHistoryMax(); return true; }
            if (hit(mx, my, autoReloadSX, autoReloadSY))         { autoReload = !autoReload; return true; }
            if (hit(mx, my, omitPrefixSX, omitPrefixSY))         { omitModidPrefix = !omitModidPrefix; return true; }
            if (hit(mx, my, preserveGridSX, preserveGridSY))     { preserveEditGrid = !preserveEditGrid; return true; }
        }
        return super.mouseClicked(mx, my, button);
    }

    private boolean hit(double mx, double my, int sx, int sy) {
        return sx >= 0 && mx >= sx && mx < sx + SLOT_SIZE && my >= sy && my < sy + SLOT_SIZE;
    }

    private void cyclePersistence() {
        var values = CreditConfig.EditPersistence.values();
        persistence = values[(persistence.ordinal() + 1) % values.length];
    }

    private void cycleHistoryMax() {
        var values = CreditConfig.HistoryMax.values();
        historyMax = values[(historyMax.ordinal() + 1) % values.length];
    }

    // ───── Render ─────

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        renderBackground(g);
        super.render(g, mouseX, mouseY, partialTick);
        int cx = this.width / 2;
        int leftX = cx - 120;
        g.drawCenteredString(this.font, this.title, cx, 20, 0xFFFFFFFF);

        g.drawString(this.font, Component.literal("Dump destination (relative to game dir):")
            .withStyle(ChatFormatting.GRAY), leftX, 38, 0xFFAAAAAA, false);
        g.drawString(this.font, Component.literal("Files: <root>/generated/<modid>/{add,edit,delete}.js")
            .withStyle(ChatFormatting.DARK_GRAY), leftX, 72, 0xFF777777, false);
        g.drawString(this.font, Component.literal("Default fluid amount on JEI drag (mB):")
            .withStyle(ChatFormatting.GRAY), leftX, 80, 0xFFAAAAAA, false);
        g.drawString(this.font, Component.literal("Default gas amount on JEI drag (mB):")
            .withStyle(ChatFormatting.GRAY), leftX, 112, 0xFFAAAAAA, false);

        // Existing rows
        drawLabelAt(g, "gui.credit.settings.persistence.label", leftX, persistenceSlotY);
        renderPersistenceSlot(g, mouseX, mouseY);
        drawLabelAt(g, "gui.credit.settings.crafting_share.label", leftX, shareSlotY);
        renderToggleSlot(g, mouseX, mouseY, shareSlotX, shareSlotY, craftingShareSlots,
            "gui.credit.settings.crafting_share.current", "gui.credit.settings.crafting_share.desc", false);
        drawLabelAt(g, "gui.credit.settings.undo.label", leftX, undoSlotY);
        renderToggleSlot(g, mouseX, mouseY, undoSlotX, undoSlotY, undoEnabled,
            "gui.credit.settings.undo.current", "gui.credit.settings.undo.desc", false);

        // v2.2.0 immediate apply (赤色警告で目立たせる)
        drawLabelAt(g, "gui.credit.settings.imm.master", leftX, immMasterSY);
        renderToggleSlot(g, mouseX, mouseY, immMasterSX, immMasterSY, immediateMaster,
            "gui.credit.settings.imm.master.current", "gui.credit.settings.imm.master.desc", true);

        int childX = leftX + 12;
        drawLabelAt(g, "gui.credit.settings.imm.add", childX, immAddSY);
        renderToggleSlot(g, mouseX, mouseY, immAddSX, immAddSY, immediateAdd,
            "gui.credit.settings.imm.add.current", "gui.credit.settings.imm.add.desc", true);
        drawLabelAt(g, "gui.credit.settings.imm.edit", childX, immEditSY);
        renderToggleSlot(g, mouseX, mouseY, immEditSX, immEditSY, immediateEdit,
            "gui.credit.settings.imm.edit.current", "gui.credit.settings.imm.edit.desc", true);
        drawLabelAt(g, "gui.credit.settings.imm.delete", childX, immDelSY);
        renderToggleSlot(g, mouseX, mouseY, immDelSX, immDelSY, immediateDelete,
            "gui.credit.settings.imm.delete.current", "gui.credit.settings.imm.delete.desc", true);

        // History max (number cycle)
        drawLabelAt(g, "gui.credit.settings.history.max", leftX, historySY);
        renderHistorySlot(g, mouseX, mouseY);

        // Auto reload
        drawLabelAt(g, "gui.credit.settings.auto_reload", leftX, autoReloadSY);
        renderToggleSlot(g, mouseX, mouseY, autoReloadSX, autoReloadSY, autoReload,
            "gui.credit.settings.auto_reload.current", "gui.credit.settings.auto_reload.desc", false);

        // v2.2.2 omit modid prefix (next-launch warning)
        drawLabelAt(g, "gui.credit.settings.omit_prefix", leftX, omitPrefixSY);
        renderOmitPrefixSlot(g, mouseX, mouseY);

        // v2.0.9 preserve edit grid on switch
        drawLabelAt(g, "gui.credit.settings.preserve_edit_grid", leftX, preserveGridSY);
        renderToggleSlot(g, mouseX, mouseY, preserveGridSX, preserveGridSY, preserveEditGrid,
            "gui.credit.settings.preserve_edit_grid.current",
            "gui.credit.settings.preserve_edit_grid.desc", false);
    }

    /** OMIT_MODID_PREFIX: 二値スロット + 次回起動反映の警告 tooltip。 */
    private void renderOmitPrefixSlot(GuiGraphics g, int mouseX, int mouseY) {
        int sx = omitPrefixSX, sy = omitPrefixSY;
        boolean hover = isInRect(mouseX, mouseY, sx, sy, SLOT_SIZE, SLOT_SIZE);
        g.fill(sx - 1, sy - 1, sx + SLOT_SIZE + 1, sy + SLOT_SIZE + 1, 0xFF8B8B8B);
        g.fill(sx,     sy,     sx + SLOT_SIZE,     sy + SLOT_SIZE,     0xFF373737);
        if (omitModidPrefix) {
            g.fill(sx + 4, sy + 8,  sx + 6,  sy + 12, 0xFF66FF88);
            g.fill(sx + 6, sy + 10, sx + 8,  sy + 13, 0xFF66FF88);
            g.fill(sx + 8, sy + 4,  sx + 13, sy + 11, 0xFF66FF88);
        } else {
            g.fill(sx + 4, sy + 7, sx + 12, sy + 9, 0xFF777777);
        }
        if (hover) {
            g.fill(sx, sy, sx + SLOT_SIZE, sy + SLOT_SIZE, 0x33FFFFFF);
            Component header = Component.translatable("gui.credit.settings.omit_prefix.current",
                Component.literal(omitModidPrefix ? "ON" : "OFF")
                    .withStyle(omitModidPrefix ? ChatFormatting.GREEN : ChatFormatting.GRAY))
                .withStyle(ChatFormatting.WHITE);
            Component desc  = Component.translatable("gui.credit.settings.omit_prefix.desc")
                .withStyle(ChatFormatting.GRAY);
            Component warn  = Component.translatable("gui.credit.settings.omit_prefix.warn")
                .withStyle(ChatFormatting.RED);
            Component hint  = Component.translatable("gui.credit.settings.persistence.hint")
                .withStyle(ChatFormatting.DARK_GRAY);
            g.renderComponentTooltip(font, List.of(header, desc, warn, hint), mouseX, mouseY);
        }
    }

    private void drawLabelAt(GuiGraphics g, String key, int x, int slotY) {
        Component label = Component.translatable(key);
        int labelY = slotY + (SLOT_SIZE - font.lineHeight) / 2;
        g.drawString(this.font, label, x, labelY, 0xFFE0E0E0, false);
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

    /** 数値表示スロット (history max)。中央に N を描画。 */
    private void renderHistorySlot(GuiGraphics g, int mouseX, int mouseY) {
        int sx = historySX, sy = historySY;
        boolean hover = isInRect(mouseX, mouseY, sx, sy, SLOT_SIZE * 2, SLOT_SIZE);
        // 横長スロット (数字表示用)
        int w = SLOT_SIZE * 2 + 8;
        g.fill(sx - 1, sy - 1, sx + w + 1, sy + SLOT_SIZE + 1, 0xFF8B8B8B);
        g.fill(sx,     sy,     sx + w,     sy + SLOT_SIZE,     0xFF373737);
        String txt = historyMax == CreditConfig.HistoryMax.UNLIMITED ? "∞" : String.valueOf(historyMax.limit);
        int tw = font.width(txt);
        g.drawString(font, txt, sx + (w - tw) / 2, sy + (SLOT_SIZE - font.lineHeight) / 2 + 1, 0xFFFFEE77, false);
        if (hover) {
            g.fill(sx, sy, sx + w, sy + SLOT_SIZE, 0x33FFFFFF);
            Component header = Component.translatable("gui.credit.settings.history.max.current",
                txt).withStyle(ChatFormatting.WHITE);
            Component desc = Component.translatable("gui.credit.settings.history.max.desc")
                .withStyle(ChatFormatting.GRAY);
            Component hint = Component.translatable("gui.credit.settings.persistence.hint")
                .withStyle(ChatFormatting.DARK_GRAY);
            g.renderComponentTooltip(font, List.of(header, desc, hint), mouseX, mouseY);
        }
    }

    /** ON/OFF 二値スロット共通。dangerous=true なら hover tooltip に赤文字警告追加。 */
    private void renderToggleSlot(GuiGraphics g, int mouseX, int mouseY,
                                  int sx, int sy, boolean on,
                                  String currentKey, String descKey, boolean dangerous) {
        boolean hover = isInRect(mouseX, mouseY, sx, sy, SLOT_SIZE, SLOT_SIZE);
        g.fill(sx - 1, sy - 1, sx + SLOT_SIZE + 1, sy + SLOT_SIZE + 1, 0xFF8B8B8B);
        g.fill(sx,     sy,     sx + SLOT_SIZE,     sy + SLOT_SIZE,     0xFF373737);
        if (on) {
            int onColor = dangerous ? 0xFFFF6666 : 0xFF66FF88;
            g.fill(sx + 4, sy + 8,  sx + 6,  sy + 12, onColor);
            g.fill(sx + 6, sy + 10, sx + 8,  sy + 13, onColor);
            g.fill(sx + 8, sy + 4,  sx + 13, sy + 11, onColor);
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
            if (dangerous) {
                Component warn = Component.translatable("gui.credit.settings.imm.warning")
                    .withStyle(ChatFormatting.RED);
                g.renderComponentTooltip(font, List.of(header, warn, desc, hint), mouseX, mouseY);
            } else {
                g.renderComponentTooltip(font, List.of(header, desc, hint), mouseX, mouseY);
            }
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
        Component desc = Component.translatable(descKey(persistence)).withStyle(ChatFormatting.GRAY);
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
