package DIV.credit;

import net.minecraftforge.common.ForgeConfigSpec;

/**
 * credit MOD の設定。Forge ConfigSpec 経由で TOML ファイル
 * (`<game>/config/credit-client.toml`) に永続化。
 *
 * 各値は ConfigValue で公開し、設定 UI から書き換え可能。
 */
public final class CreditConfig {

    public static final ForgeConfigSpec SPEC;
    public static final ForgeConfigSpec.ConfigValue<String> DUMP_ROOT;
    public static final ForgeConfigSpec.IntValue FLUID_DEFAULT_AMOUNT;
    public static final ForgeConfigSpec.IntValue GAS_DEFAULT_AMOUNT;

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
        SPEC = b.build();
    }

    private CreditConfig() {}
}
