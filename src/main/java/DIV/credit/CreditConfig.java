package DIV.credit;

import net.minecraftforge.common.ForgeConfigSpec;

/**
 * credit MOD の設定。Forge ConfigSpec 経由で TOML ファイル
 * (`<game>/config/credit-client.toml`) に永続化。
 *
 * 各値は ConfigValue で公開し、設定 UI から書き換え可能。
 */
public final class CreditConfig {

    /**
     * 編集内容（draft）の保存強度。
     * MIN    : エディタを閉じると破棄（現状より弱）
     * NORMAL : ゲーム終了まで保持（既定）
     * MAX    : ファイルに保存しゲーム再起動後も復元
     */
    public enum EditPersistence { MIN, NORMAL, MAX }

    /**
     * 編集履歴 (push 履歴 + 即時適応履歴) の保持上限。
     * UNLIMITED は trim しない。
     */
    public enum HistoryMax {
        N_10(10), N_20(20), N_30(30), N_40(40), N_50(50),
        N_100(100), N_200(200), N_300(300), UNLIMITED(-1);

        public final int limit; // -1 で unlimited
        HistoryMax(int v) { this.limit = v; }
    }

    /**
     * v3.0.1: クリップボード履歴の保存強度。
     * TRANSIENT  : BuilderScreen を閉じると消える
     * SESSION    : ゲーム終了まで保持 (default)
     * PERSISTENT : ファイルに保存、 ゲーム再起動後も復元
     */
    public enum ClipboardPersistence { TRANSIENT, SESSION, PERSISTENT }

    public static final ForgeConfigSpec SPEC;
    public static final ForgeConfigSpec.ConfigValue<String> DUMP_ROOT;
    public static final ForgeConfigSpec.IntValue FLUID_DEFAULT_AMOUNT;
    public static final ForgeConfigSpec.IntValue GAS_DEFAULT_AMOUNT;
    public static final ForgeConfigSpec.IntValue INFUSION_DEFAULT_AMOUNT;
    public static final ForgeConfigSpec.EnumValue<EditPersistence> EDIT_PERSISTENCE;
    public static final ForgeConfigSpec.BooleanValue CRAFTING_SHARE_SLOTS;
    public static final ForgeConfigSpec.BooleanValue UNDO_ENABLED;

    // ─── v2.2.0 immediate apply (commit/push 貫通) ───
    public static final ForgeConfigSpec.BooleanValue IMMEDIATE_APPLY_MASTER;
    public static final ForgeConfigSpec.BooleanValue IMMEDIATE_APPLY_ADD;
    public static final ForgeConfigSpec.BooleanValue IMMEDIATE_APPLY_EDIT;
    public static final ForgeConfigSpec.BooleanValue IMMEDIATE_APPLY_DELETE;

    // ─── v2.2.0 history retention + push 後 reload ───
    public static final ForgeConfigSpec.EnumValue<HistoryMax> HISTORY_MAX;
    public static final ForgeConfigSpec.BooleanValue AUTO_RELOAD_AFTER_PUSH;

    // ─── v3.10 push payload (= preview 過去世代用) ───
    public static final ForgeConfigSpec.IntValue HISTORY_MAX_PUSH_PAYLOAD_GENERATIONS;
    public static final ForgeConfigSpec.IntValue HISTORY_MAX_PUSH_PAYLOAD_SIZE_KB;

    // ─── v2.2.2 modid prefix omitted commands ───
    public static final ForgeConfigSpec.BooleanValue OMIT_MODID_PREFIX;

    // ─── v3.0.1: 短縮コマンド個別 on/off (master = OMIT_MODID_PREFIX) ───
    public static final ForgeConfigSpec.BooleanValue SHORT_CMD_PUSH;
    public static final ForgeConfigSpec.BooleanValue SHORT_CMD_COMMIT;
    public static final ForgeConfigSpec.BooleanValue SHORT_CMD_HISTORY;
    public static final ForgeConfigSpec.BooleanValue SHORT_CMD_SETTING;
    public static final ForgeConfigSpec.BooleanValue SHORT_CMD_IMPORT;
    public static final ForgeConfigSpec.BooleanValue SHORT_CMD_RECONSTRUCTION;
    public static final ForgeConfigSpec.BooleanValue SHORT_CMD_PREVIEW;
    public static final ForgeConfigSpec.BooleanValue SHORT_CMD_STATUS;

    // ─── v3.0.1: keybind / clipboard (= BuilderScreen 内挙動の便利機能) ───
    public static final ForgeConfigSpec.BooleanValue SPECIAL_KEYBINDS_ENABLED;
    public static final ForgeConfigSpec.BooleanValue QUICK_ADD_HOTBAR;
    public static final ForgeConfigSpec.BooleanValue CLIPBOARD_ENABLED;
    public static final ForgeConfigSpec.BooleanValue CLIPBOARD_MULTI;
    public static final ForgeConfigSpec.EnumValue<ClipboardPersistence> CLIPBOARD_PERSISTENCE;

    // ─── v3.2.x: GT / Create 確率 output の初期値 (右クリで CFG セットした時の default) ───
    public static final ForgeConfigSpec.IntValue CHANCE_DEFAULT_MILLE;
    public static final ForgeConfigSpec.IntValue CHANCE_DEFAULT_BOOST;

    // ─── v2.0.9 edit mode → category 切替時にグリッド内容保持するか ───
    public static final ForgeConfigSpec.BooleanValue PRESERVE_EDIT_GRID_ON_SWITCH;

    // ─── v2.1.2 出力ファイル構造の切替 ───
    public static final ForgeConfigSpec.BooleanValue UNIFIED_EDIT_FILES;

    // ─── v3.4.x: EMI viewer integration on/off (= 在時 only 有効、 runtime 反映) ───
    public static final ForgeConfigSpec.BooleanValue EMI_VIEWER_INTEGRATION;

    static {
        ForgeConfigSpec.Builder b = new ForgeConfigSpec.Builder();
        b.push("dump");
        DUMP_ROOT = b.comment(
                "Dump destination root directory (relative to game directory).",
                "Files are placed at <dumpRoot>/generated/<namespace>/<path>.js")
            .define("dumpRoot", "kubejs/server_scripts");
        b.pop();
        b.push("drag");
        FLUID_DEFAULT_AMOUNT = b.comment(
                "Default fluid amount (mB) when dragged from JEI.",
                "Overrides the source amount so behavior is consistent.")
            .defineInRange("fluidDefault", 1000, 1, Integer.MAX_VALUE);
        GAS_DEFAULT_AMOUNT = b.comment(
                "Default gas amount (mB) when dragged from JEI.")
            .defineInRange("gasDefault", 1000, 1, Integer.MAX_VALUE);
        INFUSION_DEFAULT_AMOUNT = b.comment(
                "Default infusion (Mekanism) amount when dragged from JEI.",
                "Infusion typically uses smaller amounts than gas (e.g., 10 for redstone infusion).")
            .defineInRange("infusionDefault", 10, 1, Integer.MAX_VALUE);
        b.pop();
        b.push("editor");
        EDIT_PERSISTENCE = b.comment(
                "How long edits (drafts) survive.",
                "MIN: discarded when editor closes.",
                "NORMAL: kept until the game exits (default).",
                "MAX: saved to <gameDir>/config/credit-drafts.dat and restored on restart.")
            .defineEnum("editPersistence", EditPersistence.NORMAL);
        CRAFTING_SHARE_SLOTS = b.comment(
                "When true, vanilla crafting SHAPED and SHAPELESS share the same slot edits.",
                "Toggling between modes preserves the current grid contents.")
            .define("craftingShareSlots", false);
        UNDO_ENABLED = b.comment(
                "Track edit operations for Ctrl+Z / Ctrl+Y undo/redo while CraftPattern is open.",
                "Disable to skip snapshot capture if you suspect performance issues.")
            .define("undoEnabled", true);
        b.pop();

        // v2.2.0 immediate apply: commit/push を貫通して即書き込み (DANGEROUS)
        b.push("immediateApply");
        IMMEDIATE_APPLY_MASTER = b.comment(
                "DANGEROUS: When true, all edits are written to .js files immediately,",
                "bypassing /credit commit and /credit push.",
                "Useful for rapid iteration but loses the safety net of commit review.")
            .define("master", false);
        IMMEDIATE_APPLY_ADD = b.comment(
                "DANGEROUS: ADD operations bypass commit/push (master overrides).")
            .define("add", false);
        IMMEDIATE_APPLY_EDIT = b.comment(
                "DANGEROUS: EDIT operations bypass commit/push (master overrides).")
            .define("edit", false);
        IMMEDIATE_APPLY_DELETE = b.comment(
                "DANGEROUS: DELETE operations bypass commit/push (master overrides).")
            .define("delete", false);
        b.pop();

        // v2.2.0 history + push behaviour
        b.push("history");
        HISTORY_MAX = b.comment(
                "Maximum number of push entries to keep (10/20/30/40/50/100/200/300/UNLIMITED).",
                "Older entries are dropped when the limit is reached.")
            .defineEnum("historyMax", HistoryMax.N_30);
        HISTORY_MAX_PUSH_PAYLOAD_GENERATIONS = b.comment(
                "v3.10: Maximum push payload generations kept under <gameDir>/credit/history/.",
                "Each push writes one push_<ts>.nbt for preview-of-past-pushes.",
                "Older files are deleted on each push. 0 = keep none, -1 = unlimited.")
            .defineInRange("maxPushPayloadGenerations", 20, -1, Integer.MAX_VALUE);
        HISTORY_MAX_PUSH_PAYLOAD_SIZE_KB = b.comment(
                "v3.10: Per-push payload size cap (KB). Pushes exceeding this are dropped",
                "(metadata-only fallback, no past-preview). 0 = no cap.")
            .defineInRange("maxPushPayloadSizeKb", 1024, 0, Integer.MAX_VALUE);
        b.pop();
        b.push("push");
        AUTO_RELOAD_AFTER_PUSH = b.comment(
                "When true, /credit push automatically runs '/reload' after writing files.")
            .define("autoReloadAfterPush", false);
        b.pop();

        // v2.2.2 commands
        b.push("commands");
        OMIT_MODID_PREFIX = b.comment(
                "Master toggle for short top-level commands (without /credit prefix).",
                "When false, no short commands are registered.",
                "When true, each command is registered if its individual toggle below is true.",
                "Changes take effect on next world join (or game restart).")
            .define("omitModidPrefix", true);
        // v3.0.1: 個別 toggle。 master = true 時のみ効く。
        SHORT_CMD_PUSH           = b.comment("Register /push as alias for /credit push.").define("shortCmdPush", true);
        SHORT_CMD_COMMIT         = b.comment("Register /commit.").define("shortCmdCommit", true);
        SHORT_CMD_HISTORY        = b.comment("Register /history.").define("shortCmdHistory", true);
        SHORT_CMD_SETTING        = b.comment("Register /setting.").define("shortCmdSetting", true);
        SHORT_CMD_IMPORT         = b.comment("Register /import.").define("shortCmdImport", true);
        SHORT_CMD_RECONSTRUCTION = b.comment("Register /reconstruction.").define("shortCmdReconstruction", true);
        SHORT_CMD_PREVIEW        = b.comment("Register /preview.").define("shortCmdPreview", true);
        SHORT_CMD_STATUS         = b.comment("Register /status.").define("shortCmdStatus", true);
        b.pop();

        // v3.0.1 keybind / clipboard (CONVENIENCE タブ)
        b.push("keybind");
        SPECIAL_KEYBINDS_ENABLED = b.comment(
                "Master toggle for CraftPattern-only keybind features.",
                "When ON: enables our special keys (digits 1-9, Ctrl+C/V, arrows)",
                "         AND blocks other mods' keybinds (JEI lookup, minimap, etc.) from firing.",
                "When OFF: normal screen behavior (other mod keys go through, our keys do nothing).")
            .define("specialKeybindsEnabled", true);
        QUICK_ADD_HOTBAR = b.comment(
                "Pressing 1-9 inside CraftPattern places the item from that hotbar slot into the slot under mouse.",
                "Copies item id + NBT (e.g. avaritia:singularity), count=1.")
            .define("quickAddHotbar", true);
        CLIPBOARD_ENABLED = b.comment(
                "Enable Ctrl+C / Ctrl+V clipboard with a slot next to the inventory.",
                "Copy targets: recipe area, player inventory, JEI ingredients.")
            .define("clipboardEnabled", true);
        CLIPBOARD_MULTI = b.comment(
                "Keep up to 100 clipboard entries. Use Up/Down arrows to scroll the history",
                "(↑ = newer side / ↓ = older side). OFF = single-slot, no arrows.")
            .define("clipboardMulti", true);
        CLIPBOARD_PERSISTENCE = b.comment(
                "How long the clipboard history survives.",
                "TRANSIENT: cleared when BuilderScreen closes.",
                "SESSION: kept until game exits (default).",
                "PERSISTENT: saved to <gameDir>/config/credit-clipboard.dat, restored on restart.")
            .defineEnum("clipboardPersistence", ClipboardPersistence.SESSION);
        b.pop();

        // v3.2.x: chance default
        b.push("chance");
        CHANCE_DEFAULT_MILLE = b.comment(
                "Default chance (per-thousand) when right-clicking an output to apply GT_CHANCE / CREATE_CHANCE.",
                "Range: 0..1000 (= 0%..100%). Default 1000 (= 100%).")
            .defineInRange("chanceDefaultMille", 1000, 0, 1000);
        CHANCE_DEFAULT_BOOST = b.comment(
                "Default GT chance tier boost. Range: >= 0. Default 0.")
            .defineInRange("chanceDefaultBoost", 0, 0, Integer.MAX_VALUE);
        b.pop();

        // v2.0.9 edit mode 終了時の grid 保持
        b.push("editor2");
        PRESERVE_EDIT_GRID_ON_SWITCH = b.comment(
                "When false (default), switching recipe type while in edit mode clears the loaded grid",
                "to avoid accidentally creating unintended recipes.",
                "When true, the loaded recipe contents are preserved across category switches.")
            .define("preserveEditGridOnSwitch", false);
        b.pop();

        // v2.1.2 出力ファイル構造
        b.push("output");
        UNIFIED_EDIT_FILES = b.comment(
                "When true (default), all recipes of a mod go into <modid>/{add,edit,delete}.js",
                "(unified per mod, current behavior).",
                "When false, recipes are split by recipe type:",
                "<modid>/<recipetype_path>/{add,edit,delete}.js",
                "Toggle does NOT migrate existing files; only future output uses the new layout.",
                "Import scans recursively so both layouts are accepted as input.")
            .define("unifiedEditFiles", true);
        b.pop();

        // v3.4.x: EMI viewer integration (= EMI 在時 only 意味あり)
        b.push("emi");
        EMI_VIEWER_INTEGRATION = b.comment(
                "Enable credit features inside EMI's recipe viewer (= '+' Edit button,",
                "delete overlay, preview wired through EMI widgets).",
                "When OFF: credit ignores EMI viewer entirely; JEI viewer continues to work normally.",
                "When ON (default): both JEI and EMI viewers expose credit Edit/Delete + preview.",
                "Effect is immediate at runtime (no restart needed).",
                "Has no effect if EMI is not installed (JEI is mandatory).")
            .define("viewerIntegration", true);
        b.pop();

        SPEC = b.build();
    }

    /** v3.4.x: EMI viewer integration が有効 (= EMI 在 + 設定 ON) かを判定する shortcut。 */
    public static boolean isEmiIntegrationEnabled() {
        if (!net.minecraftforge.fml.ModList.get().isLoaded("emi")) return false;
        try { return EMI_VIEWER_INTEGRATION.get(); }
        catch (Throwable t) { return true; } // config 未 init → default ON
    }

    /**
     * 指定 OperationKind が即時適応対象か。master ON or 該当 child ON で true。
     */
    public static boolean shouldApplyImmediately(DIV.credit.client.io.ScriptWriter.OperationKind kind) {
        if (IMMEDIATE_APPLY_MASTER.get()) return true;
        return switch (kind) {
            case ADD    -> IMMEDIATE_APPLY_ADD.get();
            case EDIT   -> IMMEDIATE_APPLY_EDIT.get();
            case DELETE -> IMMEDIATE_APPLY_DELETE.get();
        };
    }

    private CreditConfig() {}
}
