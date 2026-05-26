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
 * 設定画面 (gear ボタン + /credit setting + /craftpattern_setting から開く)。
 * <p>v3.0.1: 4 タブに分割 (便利機能 / 重大内容 / コマンド / その他)。
 * <p>値の永続化は Forge ConfigSpec 経由で {@code <game>/config/credit-client.toml} に。
 * <p>State はタブ切替で消えないように field で持つ (load on ctor、 save on save button)。
 * EditBox は rebuildWidgets() で再生成されるため、 切替前に値を state へ吸い上げる。
 */
public class SettingsScreen extends Screen {

    public enum Tab { CONVENIENCE, CRITICAL, COMMANDS, MISC }

    private final Screen parent;
    private Tab activeTab = Tab.CONVENIENCE;

    // ─── state (永続化対象、 ctor で config から load、 save で書き戻し) ───
    private String dumpRoot;
    private int fluidDefault, gasDefault;
    private CreditConfig.EditPersistence persistence;
    private CreditConfig.HistoryMax historyMax;
    private boolean craftingShareSlots, undoEnabled, autoReload, preserveEditGrid, unifiedEditFiles;
    private boolean immediateMaster, immediateAdd, immediateEdit, immediateDelete;
    private boolean omitModidPrefix;
    private boolean sCmdPush, sCmdCommit, sCmdHistory, sCmdSetting,
                    sCmdImport, sCmdReconstruction, sCmdPreview, sCmdStatus;
    // v3.0.1 keybind/clipboard
    private boolean specialKeybindsEnabled, quickAddHotbar, clipboardEnabled, clipboardMulti;
    private CreditConfig.ClipboardPersistence clipboardPersistence;
    // v3.2.x chance default
    private int chanceDefaultMille, chanceDefaultBoost;

    // ─── transient widgets (タブ切替で破棄、 再生成時に state から復元) ───
    private EditBox dumpRootBox, fluidDefaultBox, gasDefaultBox, chanceMilleBox, chanceBoostBox;

    // ─── hit boxes (active tab 内のみ有効、 それ以外は -1) ───
    private int persistenceSlotX = -1, persistenceSlotY;
    private int shareSlotX = -1, shareSlotY;
    private int undoSlotX = -1, undoSlotY;
    private int immMasterSX = -1, immMasterSY;
    private int immAddSX = -1, immAddSY;
    private int immEditSX = -1, immEditSY;
    private int immDelSX = -1, immDelSY;
    private int historySX = -1, historySY;
    private int autoReloadSX = -1, autoReloadSY;
    private int omitPrefixSX = -1, omitPrefixSY;
    private int preserveGridSX = -1, preserveGridSY;
    private int unifiedFilesSX = -1, unifiedFilesSY;
    private int sCmdSX[] = new int[8], sCmdSY[] = new int[8];
    private int specialKeySX = -1, specialKeySY;
    private int quickAddSX = -1, quickAddSY;
    private int clipboardEnabledSX = -1, clipboardEnabledSY;
    private int clipboardMultiSX = -1, clipboardMultiSY;
    private int clipboardPersistenceSX = -1, clipboardPersistenceSY;

    private static final int SLOT_SIZE = 16;
    private static final int TAB_W = 96, TAB_H = 20, TAB_Y = 38;

    public SettingsScreen(Screen parent) {
        super(Component.literal("Credit Settings"));
        this.parent = parent;
        loadStateFromConfig();
    }

    private void loadStateFromConfig() {
        dumpRoot          = CreditConfig.DUMP_ROOT.get();
        fluidDefault      = CreditConfig.FLUID_DEFAULT_AMOUNT.get();
        gasDefault        = CreditConfig.GAS_DEFAULT_AMOUNT.get();
        persistence       = CreditConfig.EDIT_PERSISTENCE.get();
        historyMax        = CreditConfig.HISTORY_MAX.get();
        craftingShareSlots = CreditConfig.CRAFTING_SHARE_SLOTS.get();
        undoEnabled       = CreditConfig.UNDO_ENABLED.get();
        autoReload        = CreditConfig.AUTO_RELOAD_AFTER_PUSH.get();
        preserveEditGrid  = CreditConfig.PRESERVE_EDIT_GRID_ON_SWITCH.get();
        unifiedEditFiles  = CreditConfig.UNIFIED_EDIT_FILES.get();
        immediateMaster   = CreditConfig.IMMEDIATE_APPLY_MASTER.get();
        immediateAdd      = CreditConfig.IMMEDIATE_APPLY_ADD.get();
        immediateEdit     = CreditConfig.IMMEDIATE_APPLY_EDIT.get();
        immediateDelete   = CreditConfig.IMMEDIATE_APPLY_DELETE.get();
        omitModidPrefix   = CreditConfig.OMIT_MODID_PREFIX.get();
        sCmdPush          = CreditConfig.SHORT_CMD_PUSH.get();
        sCmdCommit        = CreditConfig.SHORT_CMD_COMMIT.get();
        sCmdHistory       = CreditConfig.SHORT_CMD_HISTORY.get();
        sCmdSetting       = CreditConfig.SHORT_CMD_SETTING.get();
        sCmdImport        = CreditConfig.SHORT_CMD_IMPORT.get();
        sCmdReconstruction = CreditConfig.SHORT_CMD_RECONSTRUCTION.get();
        sCmdPreview       = CreditConfig.SHORT_CMD_PREVIEW.get();
        sCmdStatus        = CreditConfig.SHORT_CMD_STATUS.get();
        specialKeybindsEnabled = CreditConfig.SPECIAL_KEYBINDS_ENABLED.get();
        quickAddHotbar    = CreditConfig.QUICK_ADD_HOTBAR.get();
        clipboardEnabled  = CreditConfig.CLIPBOARD_ENABLED.get();
        clipboardMulti    = CreditConfig.CLIPBOARD_MULTI.get();
        clipboardPersistence = CreditConfig.CLIPBOARD_PERSISTENCE.get();
        chanceDefaultMille = CreditConfig.CHANCE_DEFAULT_MILLE.get();
        chanceDefaultBoost = CreditConfig.CHANCE_DEFAULT_BOOST.get();
    }

    /** タブ切替前に、 EditBox の現値を state に吸い上げ。 widget 破棄で値が失われるのを防ぐ。 */
    private void persistEditBoxesToState() {
        if (dumpRootBox != null)     dumpRoot     = dumpRootBox.getValue();
        if (fluidDefaultBox != null) {
            try { fluidDefault = Math.max(1, Integer.parseInt(fluidDefaultBox.getValue().trim())); } catch (NumberFormatException ignored) {}
        }
        if (gasDefaultBox != null) {
            try { gasDefault = Math.max(1, Integer.parseInt(gasDefaultBox.getValue().trim())); } catch (NumberFormatException ignored) {}
        }
        if (chanceMilleBox != null) {
            try { chanceDefaultMille = Math.max(0, Math.min(1000, Integer.parseInt(chanceMilleBox.getValue().trim()))); } catch (NumberFormatException ignored) {}
        }
        if (chanceBoostBox != null) {
            try { chanceDefaultBoost = Math.max(0, Integer.parseInt(chanceBoostBox.getValue().trim())); } catch (NumberFormatException ignored) {}
        }
    }

    @Override
    protected void init() {
        super.init();
        // タブ毎の hit box reset
        persistenceSlotX = shareSlotX = undoSlotX = -1;
        immMasterSX = immAddSX = immEditSX = immDelSX = -1;
        historySX = autoReloadSX = omitPrefixSX = preserveGridSX = unifiedFilesSX = -1;
        specialKeySX = quickAddSX = clipboardEnabledSX = clipboardMultiSX = clipboardPersistenceSX = -1;
        for (int i = 0; i < 8; i++) sCmdSX[i] = -1;
        dumpRootBox = fluidDefaultBox = gasDefaultBox = chanceMilleBox = chanceBoostBox = null;

        int cx = this.width / 2;
        // ─── タブ bar ───
        int tabBarTotalW = TAB_W * 4;
        int tabBarLeft = cx - tabBarTotalW / 2;
        for (int i = 0; i < 4; i++) {
            Tab t = Tab.values()[i];
            int tx = tabBarLeft + TAB_W * i;
            String labelKey = "gui.credit.settings.tab." + tabKey(t);
            addRenderableWidget(Button.builder(
                    Component.translatable(labelKey)
                        .withStyle(t == activeTab ? ChatFormatting.YELLOW : ChatFormatting.GRAY),
                    b -> switchTab(t))
                .bounds(tx, TAB_Y, TAB_W, TAB_H)
                .build());
        }

        // ─── 各タブの content ───
        switch (activeTab) {
            case CONVENIENCE -> initConvenience(cx);
            case CRITICAL    -> initCritical(cx);
            case COMMANDS    -> initCommands(cx);
            case MISC        -> initMisc(cx);
        }

        // ─── 共通: destructive actions + Save/Cancel (画面下) ───
        initBottomActions(cx);
    }

    private void switchTab(Tab t) {
        persistEditBoxesToState();
        activeTab = t;
        rebuildWidgets();
    }

    // ─── タブごとの init ───

    private void initConvenience(int cx) {
        int leftX = cx - 140;
        // fluid + gas default
        int y = 80;
        this.fluidDefaultBox = new EditBox(font, leftX + 200, y, 80, 16, Component.literal("fluidDefault"));
        this.fluidDefaultBox.setMaxLength(10);
        this.fluidDefaultBox.setValue(String.valueOf(fluidDefault));
        addRenderableWidget(this.fluidDefaultBox);
        y = 108;
        this.gasDefaultBox = new EditBox(font, leftX + 200, y, 80, 16, Component.literal("gasDefault"));
        this.gasDefaultBox.setMaxLength(10);
        this.gasDefaultBox.setValue(String.valueOf(gasDefault));
        addRenderableWidget(this.gasDefaultBox);
        // crafting share + auto reload toggles
        Component shareLabel = Component.translatable("gui.credit.settings.crafting_share.label");
        this.shareSlotX = leftX + font.width(shareLabel) + 6;
        this.shareSlotY = 138;
        Component arLabel = Component.translatable("gui.credit.settings.auto_reload");
        this.autoReloadSX = leftX + font.width(arLabel) + 6;
        this.autoReloadSY = 162;
        // v3.0.1 keybind / clipboard
        Component skLabel = Component.translatable("gui.credit.settings.special_keybinds");
        this.specialKeySX = leftX + font.width(skLabel) + 6;
        this.specialKeySY = 190;
        int childX = leftX + 12;
        Component qaLabel = Component.translatable("gui.credit.settings.quick_add_hotbar");
        this.quickAddSX = childX + font.width(qaLabel) + 6;
        this.quickAddSY = 212;
        Component clEnabledLabel = Component.translatable("gui.credit.settings.clipboard.enabled");
        this.clipboardEnabledSX = childX + font.width(clEnabledLabel) + 6;
        this.clipboardEnabledSY = 234;
        Component clMultiLabel = Component.translatable("gui.credit.settings.clipboard.multi");
        this.clipboardMultiSX = childX + font.width(clMultiLabel) + 6;
        this.clipboardMultiSY = 256;
        Component clPersistLabel = Component.translatable("gui.credit.settings.clipboard.persistence");
        this.clipboardPersistenceSX = childX + font.width(clPersistLabel) + 6;
        this.clipboardPersistenceSY = 278;
        // v3.2.x: chance default 2 行 (= 1000分率 + tier boost)
        int y2 = 300;
        this.chanceMilleBox = new EditBox(font, leftX + 200, y2, 60, 16, Component.literal("chanceMille"));
        this.chanceMilleBox.setMaxLength(4);
        this.chanceMilleBox.setValue(String.valueOf(chanceDefaultMille));
        addRenderableWidget(this.chanceMilleBox);
        y2 = 322;
        this.chanceBoostBox = new EditBox(font, leftX + 200, y2, 60, 16, Component.literal("chanceBoost"));
        this.chanceBoostBox.setMaxLength(5);
        this.chanceBoostBox.setValue(String.valueOf(chanceDefaultBoost));
        addRenderableWidget(this.chanceBoostBox);
    }

    private void initCritical(int cx) {
        int leftX = cx - 140;
        // EDIT_PERSISTENCE (3-level slot)
        Component persistLabel = Component.translatable("gui.credit.settings.persistence.label");
        this.persistenceSlotX = leftX + font.width(persistLabel) + 6;
        this.persistenceSlotY = 80;
        // UNDO
        Component undoLabel = Component.translatable("gui.credit.settings.undo.label");
        this.undoSlotX = leftX + font.width(undoLabel) + 6;
        this.undoSlotY = 104;
        // PRESERVE_EDIT_GRID
        Component pgLabel = Component.translatable("gui.credit.settings.preserve_edit_grid");
        this.preserveGridSX = leftX + font.width(pgLabel) + 6;
        this.preserveGridSY = 128;
        // IMMEDIATE master + children (= DANGEROUS、 赤系で警告)
        Component masterLabel = Component.translatable("gui.credit.settings.imm.master");
        this.immMasterSX = leftX + font.width(masterLabel) + 6;
        this.immMasterSY = 160;
        int childX = leftX + 12;
        Component immAddLabel = Component.translatable("gui.credit.settings.imm.add");
        this.immAddSX = childX + font.width(immAddLabel) + 6;
        this.immAddSY = 182;
        Component immEditLabel = Component.translatable("gui.credit.settings.imm.edit");
        this.immEditSX = childX + font.width(immEditLabel) + 6;
        this.immEditSY = 204;
        Component immDelLabel = Component.translatable("gui.credit.settings.imm.delete");
        this.immDelSX = childX + font.width(immDelLabel) + 6;
        this.immDelSY = 226;
    }

    private void initCommands(int cx) {
        int leftX = cx - 140;
        // master OMIT_MODID_PREFIX
        Component opLabel = Component.translatable("gui.credit.settings.omit_prefix");
        this.omitPrefixSX = leftX + font.width(opLabel) + 6;
        this.omitPrefixSY = 80;
        // 8 短縮 cmd toggles
        int childX = leftX + 12;
        String[] keys = shortCmdKeys();
        for (int i = 0; i < 8; i++) {
            Component lbl = Component.translatable(keys[i]);
            sCmdSX[i] = childX + font.width(lbl) + 6;
            sCmdSY[i] = 110 + i * 22;
        }
    }

    private void initMisc(int cx) {
        int leftX = cx - 140;
        // DUMP_ROOT
        int y = 80;
        this.dumpRootBox = new EditBox(font, leftX, y, 280, 16, Component.literal("dumpRoot"));
        this.dumpRootBox.setMaxLength(200);
        this.dumpRootBox.setValue(dumpRoot);
        addRenderableWidget(this.dumpRootBox);
        // HISTORY_MAX (number cycle)
        Component histLabel = Component.translatable("gui.credit.settings.history.max");
        this.historySX = leftX + font.width(histLabel) + 6;
        this.historySY = 116;
        // UNIFIED_EDIT_FILES
        Component ueLabel = Component.translatable("gui.credit.settings.unified_files");
        this.unifiedFilesSX = leftX + font.width(ueLabel) + 6;
        this.unifiedFilesSY = 144;
    }

    private void initBottomActions(int cx) {
        // v3.0.1: 削除/リセット系は重大内容タブ限定
        if (activeTab == Tab.CRITICAL) {
            int destBtnW = 220;
            int destBtnX = cx - destBtnW / 2;
            int dy = this.height - 90;
            addRenderableWidget(Button.builder(
                    Component.translatable("gui.credit.settings.delete_data.label")
                        .withStyle(ChatFormatting.RED),
                    b -> tryDeleteEditData())
                .bounds(destBtnX, dy, destBtnW, 20).build());
            addRenderableWidget(Button.builder(
                    Component.translatable("gui.credit.settings.delete_history.label")
                        .withStyle(ChatFormatting.RED),
                    b -> tryDeleteHistory())
                .bounds(destBtnX, dy + 22, destBtnW, 20).build());
            addRenderableWidget(Button.builder(
                    Component.translatable("gui.credit.settings.reset.label")
                        .withStyle(ChatFormatting.RED),
                    b -> tryResetSettings())
                .bounds(destBtnX, dy + 44, destBtnW, 20).build());
        }
        // Save + Cancel は常時表示
        int btnY = this.height - 22;
        addRenderableWidget(Button.builder(Component.literal("Save"), b -> save())
            .bounds(cx - 80, btnY, 70, 20).build());
        addRenderableWidget(Button.builder(Component.literal("Cancel"), b -> onClose())
            .bounds(cx + 10, btnY, 70, 20).build());
    }

    // ─── Save / Reset / Delete ───

    private void save() {
        persistEditBoxesToState();
        String d = dumpRoot.trim();
        if (d.isBlank()) d = "kubejs/server_scripts";
        if (!d.equals(CreditConfig.DUMP_ROOT.get())) {
            CreditConfig.DUMP_ROOT.set(d);
            CreditConfig.DUMP_ROOT.save();
        }
        saveInt(CreditConfig.FLUID_DEFAULT_AMOUNT, Math.max(1, fluidDefault));
        saveInt(CreditConfig.GAS_DEFAULT_AMOUNT,   Math.max(1, gasDefault));
        if (persistence != CreditConfig.EDIT_PERSISTENCE.get()) {
            CreditConfig.EDIT_PERSISTENCE.set(persistence);
            CreditConfig.EDIT_PERSISTENCE.save();
        }
        if (historyMax != CreditConfig.HISTORY_MAX.get()) {
            CreditConfig.HISTORY_MAX.set(historyMax);
            CreditConfig.HISTORY_MAX.save();
        }
        saveBool(CreditConfig.CRAFTING_SHARE_SLOTS, craftingShareSlots);
        saveBool(CreditConfig.UNDO_ENABLED, undoEnabled);
        saveBool(CreditConfig.AUTO_RELOAD_AFTER_PUSH, autoReload);
        saveBool(CreditConfig.PRESERVE_EDIT_GRID_ON_SWITCH, preserveEditGrid);
        saveBool(CreditConfig.UNIFIED_EDIT_FILES, unifiedEditFiles);
        saveBool(CreditConfig.IMMEDIATE_APPLY_MASTER, immediateMaster);
        saveBool(CreditConfig.IMMEDIATE_APPLY_ADD,    immediateAdd);
        saveBool(CreditConfig.IMMEDIATE_APPLY_EDIT,   immediateEdit);
        saveBool(CreditConfig.IMMEDIATE_APPLY_DELETE, immediateDelete);
        saveBool(CreditConfig.OMIT_MODID_PREFIX,      omitModidPrefix);
        saveBool(CreditConfig.SHORT_CMD_PUSH,           sCmdPush);
        saveBool(CreditConfig.SHORT_CMD_COMMIT,         sCmdCommit);
        saveBool(CreditConfig.SHORT_CMD_HISTORY,        sCmdHistory);
        saveBool(CreditConfig.SHORT_CMD_SETTING,        sCmdSetting);
        saveBool(CreditConfig.SHORT_CMD_IMPORT,         sCmdImport);
        saveBool(CreditConfig.SHORT_CMD_RECONSTRUCTION, sCmdReconstruction);
        saveBool(CreditConfig.SHORT_CMD_PREVIEW,        sCmdPreview);
        saveBool(CreditConfig.SHORT_CMD_STATUS,         sCmdStatus);
        saveBool(CreditConfig.SPECIAL_KEYBINDS_ENABLED, specialKeybindsEnabled);
        saveBool(CreditConfig.QUICK_ADD_HOTBAR,         quickAddHotbar);
        saveBool(CreditConfig.CLIPBOARD_ENABLED,        clipboardEnabled);
        saveBool(CreditConfig.CLIPBOARD_MULTI,          clipboardMulti);
        if (clipboardPersistence != CreditConfig.CLIPBOARD_PERSISTENCE.get()) {
            CreditConfig.CLIPBOARD_PERSISTENCE.set(clipboardPersistence);
            CreditConfig.CLIPBOARD_PERSISTENCE.save();
        }
        saveInt(CreditConfig.CHANCE_DEFAULT_MILLE, Math.max(0, Math.min(1000, chanceDefaultMille)));
        saveInt(CreditConfig.CHANCE_DEFAULT_BOOST, Math.max(0, chanceDefaultBoost));
        Credit.LOGGER.info("[CraftPattern] Saved settings (v3.0.1, tabs)");
        onClose();
    }

    private static void saveBool(net.minecraftforge.common.ForgeConfigSpec.BooleanValue v, boolean val) {
        if (val != v.get()) { v.set(val); v.save(); }
    }

    private static void saveInt(net.minecraftforge.common.ForgeConfigSpec.IntValue v, int val) {
        if (val != v.get()) { v.set(val); v.save(); }
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
        resetDef(CreditConfig.SHORT_CMD_PUSH);
        resetDef(CreditConfig.SHORT_CMD_COMMIT);
        resetDef(CreditConfig.SHORT_CMD_HISTORY);
        resetDef(CreditConfig.SHORT_CMD_SETTING);
        resetDef(CreditConfig.SHORT_CMD_IMPORT);
        resetDef(CreditConfig.SHORT_CMD_RECONSTRUCTION);
        resetDef(CreditConfig.SHORT_CMD_PREVIEW);
        resetDef(CreditConfig.SHORT_CMD_STATUS);
        resetDef(CreditConfig.PRESERVE_EDIT_GRID_ON_SWITCH);
        resetDef(CreditConfig.UNIFIED_EDIT_FILES);
        resetDef(CreditConfig.SPECIAL_KEYBINDS_ENABLED);
        resetDef(CreditConfig.QUICK_ADD_HOTBAR);
        resetDef(CreditConfig.CLIPBOARD_ENABLED);
        resetDef(CreditConfig.CLIPBOARD_MULTI);
        resetDef(CreditConfig.CLIPBOARD_PERSISTENCE);
        resetDef(CreditConfig.CHANCE_DEFAULT_MILLE);
        resetDef(CreditConfig.CHANCE_DEFAULT_BOOST);
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

    // ─── Mouse ───

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        if (button == 0) {
            if (hit(mx, my, persistenceSlotX, persistenceSlotY))   { cyclePersistence(); return true; }
            if (hit(mx, my, shareSlotX, shareSlotY))               { craftingShareSlots = !craftingShareSlots; return true; }
            if (hit(mx, my, undoSlotX, undoSlotY))                 { undoEnabled = !undoEnabled; return true; }
            if (hit(mx, my, immMasterSX, immMasterSY))             { immediateMaster = !immediateMaster; return true; }
            if (hit(mx, my, immAddSX, immAddSY))                   { immediateAdd    = !immediateAdd; return true; }
            if (hit(mx, my, immEditSX, immEditSY))                 { immediateEdit   = !immediateEdit; return true; }
            if (hit(mx, my, immDelSX, immDelSY))                   { immediateDelete = !immediateDelete; return true; }
            if (hit(mx, my, historySX, historySY, SLOT_SIZE * 2 + 8, SLOT_SIZE)) { cycleHistoryMax(); return true; }
            if (hit(mx, my, autoReloadSX, autoReloadSY))           { autoReload = !autoReload; return true; }
            if (hit(mx, my, omitPrefixSX, omitPrefixSY))           { omitModidPrefix = !omitModidPrefix; return true; }
            if (hit(mx, my, preserveGridSX, preserveGridSY))       { preserveEditGrid = !preserveEditGrid; return true; }
            if (hit(mx, my, unifiedFilesSX, unifiedFilesSY))       { unifiedEditFiles = !unifiedEditFiles; return true; }
            if (hit(mx, my, specialKeySX, specialKeySY))           { specialKeybindsEnabled = !specialKeybindsEnabled; return true; }
            if (hit(mx, my, quickAddSX, quickAddSY))               { quickAddHotbar = !quickAddHotbar; return true; }
            if (hit(mx, my, clipboardEnabledSX, clipboardEnabledSY)) { clipboardEnabled = !clipboardEnabled; return true; }
            if (hit(mx, my, clipboardMultiSX, clipboardMultiSY))   { clipboardMulti = !clipboardMulti; return true; }
            if (hit(mx, my, clipboardPersistenceSX, clipboardPersistenceSY)) { cycleClipboardPersistence(); return true; }
            // short cmd toggles
            for (int i = 0; i < 8; i++) {
                if (hit(mx, my, sCmdSX[i], sCmdSY[i])) {
                    toggleShortCmd(i);
                    return true;
                }
            }
        }
        return super.mouseClicked(mx, my, button);
    }

    private boolean hit(double mx, double my, int sx, int sy) {
        return hit(mx, my, sx, sy, SLOT_SIZE, SLOT_SIZE);
    }

    private boolean hit(double mx, double my, int sx, int sy, int w, int h) {
        return sx >= 0 && mx >= sx && mx < sx + w && my >= sy && my < sy + h;
    }

    private void cyclePersistence() {
        var values = CreditConfig.EditPersistence.values();
        persistence = values[(persistence.ordinal() + 1) % values.length];
    }

    private void cycleHistoryMax() {
        var values = CreditConfig.HistoryMax.values();
        historyMax = values[(historyMax.ordinal() + 1) % values.length];
    }

    private void cycleClipboardPersistence() {
        var values = CreditConfig.ClipboardPersistence.values();
        clipboardPersistence = values[(clipboardPersistence.ordinal() + 1) % values.length];
    }

    private void toggleShortCmd(int i) {
        switch (i) {
            case 0 -> sCmdPush = !sCmdPush;
            case 1 -> sCmdCommit = !sCmdCommit;
            case 2 -> sCmdHistory = !sCmdHistory;
            case 3 -> sCmdSetting = !sCmdSetting;
            case 4 -> sCmdImport = !sCmdImport;
            case 5 -> sCmdReconstruction = !sCmdReconstruction;
            case 6 -> sCmdPreview = !sCmdPreview;
            case 7 -> sCmdStatus = !sCmdStatus;
        }
    }

    // ─── Render ───

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        renderBackground(g);
        super.render(g, mouseX, mouseY, partialTick);
        int cx = this.width / 2;
        int leftX = cx - 140;
        g.drawCenteredString(this.font, this.title, cx, 20, 0xFFFFFFFF);
        // active tab tip (under tab bar)
        Component tip = Component.translatable("gui.credit.settings.tab." + tabKey(activeTab) + ".tip")
            .withStyle(ChatFormatting.DARK_GRAY);
        g.drawCenteredString(font, tip, cx, TAB_Y + TAB_H + 4, 0xFF888888);

        switch (activeTab) {
            case CONVENIENCE -> renderConvenience(g, mouseX, mouseY, leftX);
            case CRITICAL    -> renderCritical(g, mouseX, mouseY, leftX);
            case COMMANDS    -> renderCommands(g, mouseX, mouseY, leftX);
            case MISC        -> renderMisc(g, mouseX, mouseY, leftX);
        }
    }

    private void renderConvenience(GuiGraphics g, int mouseX, int mouseY, int leftX) {
        g.drawString(font, Component.literal("Default fluid amount on JEI drag (mB):")
            .withStyle(ChatFormatting.GRAY), leftX, 68, 0xFFAAAAAA, false);
        g.drawString(font, Component.literal("Default gas amount on JEI drag (mB):")
            .withStyle(ChatFormatting.GRAY), leftX, 96, 0xFFAAAAAA, false);
        drawLabelAt(g, "gui.credit.settings.crafting_share.label", leftX, shareSlotY);
        renderToggleSlot(g, mouseX, mouseY, shareSlotX, shareSlotY, craftingShareSlots,
            "gui.credit.settings.crafting_share.current", "gui.credit.settings.crafting_share.desc", false);
        drawLabelAt(g, "gui.credit.settings.auto_reload", leftX, autoReloadSY);
        renderToggleSlot(g, mouseX, mouseY, autoReloadSX, autoReloadSY, autoReload,
            "gui.credit.settings.auto_reload.current", "gui.credit.settings.auto_reload.desc", false);
        // v3.0.1 keybind / clipboard
        drawLabelAt(g, "gui.credit.settings.special_keybinds", leftX, specialKeySY);
        renderToggleSlot(g, mouseX, mouseY, specialKeySX, specialKeySY, specialKeybindsEnabled,
            "gui.credit.settings.special_keybinds.current",
            "gui.credit.settings.special_keybinds.desc", false);
        int childX = leftX + 12;
        drawLabelAt(g, "gui.credit.settings.quick_add_hotbar", childX, quickAddSY);
        renderToggleSlot(g, mouseX, mouseY, quickAddSX, quickAddSY, quickAddHotbar,
            "gui.credit.settings.quick_add_hotbar.current",
            "gui.credit.settings.quick_add_hotbar.desc", false);
        drawLabelAt(g, "gui.credit.settings.clipboard.enabled", childX, clipboardEnabledSY);
        renderToggleSlot(g, mouseX, mouseY, clipboardEnabledSX, clipboardEnabledSY, clipboardEnabled,
            "gui.credit.settings.clipboard.enabled.current",
            "gui.credit.settings.clipboard.enabled.desc", false);
        drawLabelAt(g, "gui.credit.settings.clipboard.multi", childX, clipboardMultiSY);
        renderToggleSlot(g, mouseX, mouseY, clipboardMultiSX, clipboardMultiSY, clipboardMulti,
            "gui.credit.settings.clipboard.multi.current",
            "gui.credit.settings.clipboard.multi.desc", false);
        drawLabelAt(g, "gui.credit.settings.clipboard.persistence", childX, clipboardPersistenceSY);
        renderClipboardPersistenceSlot(g, mouseX, mouseY);
        // v3.2.x: chance default labels (= EditBox は右側、 label は左)
        g.drawString(font, Component.translatable("gui.credit.settings.chance.default_mille")
            .withStyle(ChatFormatting.GRAY), leftX, 304, 0xFFAAAAAA, false);
        g.drawString(font, Component.translatable("gui.credit.settings.chance.default_boost")
            .withStyle(ChatFormatting.GRAY), leftX, 326, 0xFFAAAAAA, false);
    }

    /** 3-level enum slot for ClipboardPersistence (TRANSIENT/SESSION/PERSISTENT)。 */
    private void renderClipboardPersistenceSlot(GuiGraphics g, int mouseX, int mouseY) {
        int sx = clipboardPersistenceSX, sy = clipboardPersistenceSY;
        if (sx < 0) return;
        boolean hover = isInRect(mouseX, mouseY, sx, sy, SLOT_SIZE, SLOT_SIZE);
        g.fill(sx - 1, sy - 1, sx + SLOT_SIZE + 1, sy + SLOT_SIZE + 1, 0xFF8B8B8B);
        g.fill(sx,     sy,     sx + SLOT_SIZE,     sy + SLOT_SIZE,     0xFF373737);
        int level = clipboardPersistence.ordinal() + 1;
        int color = switch (clipboardPersistence) {
            case TRANSIENT  -> 0xFFFF6666;
            case SESSION    -> 0xFFFFEE66;
            case PERSISTENT -> 0xFF66FF88;
        };
        for (int i = 0; i < 3; i++) {
            int barX = sx + 3 + i * 4;
            int barH = 3 + i * 3;
            int barY = sy + SLOT_SIZE - 2 - barH;
            g.fill(barX, barY, barX + 3, barY + barH, i < level ? color : 0xFF555555);
        }
        if (hover) {
            g.fill(sx, sy, sx + SLOT_SIZE, sy + SLOT_SIZE, 0x33FFFFFF);
            String nameKey = "gui.credit.settings.clipboard.persistence." + clipboardPersistence.name().toLowerCase() + ".name";
            String descKey = "gui.credit.settings.clipboard.persistence." + clipboardPersistence.name().toLowerCase() + ".desc";
            Component header = Component.translatable("gui.credit.settings.clipboard.persistence.current",
                Component.translatable(nameKey)).withStyle(ChatFormatting.WHITE);
            Component desc = Component.translatable(descKey).withStyle(ChatFormatting.GRAY);
            Component hint = Component.translatable("gui.credit.settings.persistence.hint")
                .withStyle(ChatFormatting.DARK_GRAY);
            g.renderComponentTooltip(font, List.of(header, desc, hint), mouseX, mouseY);
        }
    }

    private void renderCritical(GuiGraphics g, int mouseX, int mouseY, int leftX) {
        drawLabelAt(g, "gui.credit.settings.persistence.label", leftX, persistenceSlotY);
        renderPersistenceSlot(g, mouseX, mouseY);
        drawLabelAt(g, "gui.credit.settings.undo.label", leftX, undoSlotY);
        renderToggleSlot(g, mouseX, mouseY, undoSlotX, undoSlotY, undoEnabled,
            "gui.credit.settings.undo.current", "gui.credit.settings.undo.desc", false);
        drawLabelAt(g, "gui.credit.settings.preserve_edit_grid", leftX, preserveGridSY);
        renderToggleSlot(g, mouseX, mouseY, preserveGridSX, preserveGridSY, preserveEditGrid,
            "gui.credit.settings.preserve_edit_grid.current",
            "gui.credit.settings.preserve_edit_grid.desc", false);
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
    }

    private void renderCommands(GuiGraphics g, int mouseX, int mouseY, int leftX) {
        drawLabelAt(g, "gui.credit.settings.omit_prefix", leftX, omitPrefixSY);
        renderOmitPrefixSlot(g, mouseX, mouseY);
        // 8 short cmd toggles (master が OFF なら disabled tooltip)
        int childX = leftX + 12;
        String[] keys = shortCmdKeys();
        boolean[] vals = { sCmdPush, sCmdCommit, sCmdHistory, sCmdSetting,
                           sCmdImport, sCmdReconstruction, sCmdPreview, sCmdStatus };
        for (int i = 0; i < 8; i++) {
            drawLabelAt(g, keys[i], childX, sCmdSY[i]);
            renderShortCmdSlot(g, mouseX, mouseY, sCmdSX[i], sCmdSY[i], vals[i], keys[i]);
        }
    }

    private void renderMisc(GuiGraphics g, int mouseX, int mouseY, int leftX) {
        g.drawString(font, Component.literal("Dump destination (relative to game dir):")
            .withStyle(ChatFormatting.GRAY), leftX, 68, 0xFFAAAAAA, false);
        g.drawString(font, Component.literal("Files: <root>/generated/<modid>/{add,edit,delete}.js")
            .withStyle(ChatFormatting.DARK_GRAY), leftX, 100, 0xFF777777, false);
        drawLabelAt(g, "gui.credit.settings.history.max", leftX, historySY);
        renderHistorySlot(g, mouseX, mouseY);
        drawLabelAt(g, "gui.credit.settings.unified_files", leftX, unifiedFilesSY);
        renderToggleSlot(g, mouseX, mouseY, unifiedFilesSX, unifiedFilesSY, unifiedEditFiles,
            "gui.credit.settings.unified_files.current",
            "gui.credit.settings.unified_files.desc", false);
    }

    /** OMIT_MODID_PREFIX (master): 二値スロット + 次回起動反映の警告 tooltip。 */
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

    /** 短縮コマンド個別 toggle。 master が OFF だと disabled (= grey)。 */
    private void renderShortCmdSlot(GuiGraphics g, int mouseX, int mouseY,
                                     int sx, int sy, boolean on, String labelKey) {
        boolean disabled = !omitModidPrefix;
        boolean hover = isInRect(mouseX, mouseY, sx, sy, SLOT_SIZE, SLOT_SIZE);
        g.fill(sx - 1, sy - 1, sx + SLOT_SIZE + 1, sy + SLOT_SIZE + 1,
            disabled ? 0xFF555555 : 0xFF8B8B8B);
        g.fill(sx,     sy,     sx + SLOT_SIZE,     sy + SLOT_SIZE,
            disabled ? 0xFF222222 : 0xFF373737);
        if (on) {
            int c = disabled ? 0xFF666666 : 0xFF66CCFF;
            g.fill(sx + 4, sy + 8,  sx + 6,  sy + 12, c);
            g.fill(sx + 6, sy + 10, sx + 8,  sy + 13, c);
            g.fill(sx + 8, sy + 4,  sx + 13, sy + 11, c);
        } else {
            g.fill(sx + 4, sy + 7, sx + 12, sy + 9, 0xFF555555);
        }
        if (hover) {
            g.fill(sx, sy, sx + SLOT_SIZE, sy + SLOT_SIZE, 0x33FFFFFF);
            Component header = Component.translatable(labelKey).withStyle(ChatFormatting.WHITE);
            Component state = Component.literal(on ? "ON" : "OFF")
                .withStyle(on ? ChatFormatting.GREEN : ChatFormatting.GRAY);
            Component desc = Component.translatable("gui.credit.settings.short_cmd.desc")
                .withStyle(ChatFormatting.GRAY);
            List<Component> lines = new java.util.ArrayList<>();
            lines.add(header);
            lines.add(state);
            if (disabled) lines.add(Component.translatable("gui.credit.settings.short_cmd.master_off")
                .withStyle(ChatFormatting.RED));
            lines.add(desc);
            g.renderComponentTooltip(font, lines, mouseX, mouseY);
        }
    }

    private void drawLabelAt(GuiGraphics g, String key, int x, int slotY) {
        Component label = Component.translatable(key);
        int labelY = slotY + (SLOT_SIZE - font.lineHeight) / 2;
        g.drawString(this.font, label, x, labelY, 0xFFE0E0E0, false);
    }

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

    private void renderHistorySlot(GuiGraphics g, int mouseX, int mouseY) {
        int sx = historySX, sy = historySY;
        int w = SLOT_SIZE * 2 + 8;
        boolean hover = isInRect(mouseX, mouseY, sx, sy, w, SLOT_SIZE);
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

    private static String tabKey(Tab t) {
        return switch (t) {
            case CONVENIENCE -> "convenience";
            case CRITICAL    -> "critical";
            case COMMANDS    -> "commands";
            case MISC        -> "misc";
        };
    }

    private static String[] shortCmdKeys() {
        return new String[] {
            "gui.credit.settings.short_cmd.push",
            "gui.credit.settings.short_cmd.commit",
            "gui.credit.settings.short_cmd.history",
            "gui.credit.settings.short_cmd.setting",
            "gui.credit.settings.short_cmd.import",
            "gui.credit.settings.short_cmd.reconstruction",
            "gui.credit.settings.short_cmd.preview",
            "gui.credit.settings.short_cmd.status",
        };
    }

    @Override
    public void onClose() {
        Minecraft.getInstance().setScreen(parent);
    }
}
