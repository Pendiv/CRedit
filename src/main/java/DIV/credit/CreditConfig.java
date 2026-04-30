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

    public static final ForgeConfigSpec SPEC;
    public static final ForgeConfigSpec.ConfigValue<String> DUMP_ROOT;
    public static final ForgeConfigSpec.IntValue FLUID_DEFAULT_AMOUNT;
    public static final ForgeConfigSpec.IntValue GAS_DEFAULT_AMOUNT;
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

    // ─── v2.2.2 modid prefix omitted commands ───
    public static final ForgeConfigSpec.BooleanValue OMIT_MODID_PREFIX;

    // ─── v2.0.9 edit mode → category 切替時にグリッド内容保持するか ───
    public static final ForgeConfigSpec.BooleanValue PRESERVE_EDIT_GRID_ON_SWITCH;

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
        b.pop();
        b.push("push");
        AUTO_RELOAD_AFTER_PUSH = b.comment(
                "When true, /credit push automatically runs '/reload' after writing files.")
            .define("autoReloadAfterPush", false);
        b.pop();

        // v2.2.2 commands
        b.push("commands");
        OMIT_MODID_PREFIX = b.comment(
                "When true, also register short top-level commands without the /credit prefix:",
                "/commit, /push, /history, /setting, /status",
                "Changes take effect on next world join (or game restart).")
            .define("omitModidPrefix", true);
        b.pop();

        // v2.0.9 edit mode 終了時の grid 保持
        b.push("editor2");
        PRESERVE_EDIT_GRID_ON_SWITCH = b.comment(
                "When false (default), switching recipe type while in edit mode clears the loaded grid",
                "to avoid accidentally creating unintended recipes.",
                "When true, the loaded recipe contents are preserved across category switches.")
            .define("preserveEditGridOnSwitch", false);
        b.pop();

        SPEC = b.build();
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
