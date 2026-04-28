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

    public static final ForgeConfigSpec SPEC;
    public static final ForgeConfigSpec.ConfigValue<String> DUMP_ROOT;
    public static final ForgeConfigSpec.IntValue FLUID_DEFAULT_AMOUNT;
    public static final ForgeConfigSpec.IntValue GAS_DEFAULT_AMOUNT;
    public static final ForgeConfigSpec.EnumValue<EditPersistence> EDIT_PERSISTENCE;
    public static final ForgeConfigSpec.BooleanValue CRAFTING_SHARE_SLOTS;
    public static final ForgeConfigSpec.BooleanValue UNDO_ENABLED;

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
        SPEC = b.build();
    }

    private CreditConfig() {}
}
