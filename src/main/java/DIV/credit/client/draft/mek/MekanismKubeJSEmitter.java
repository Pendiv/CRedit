package DIV.credit.client.draft.mek;

import DIV.credit.client.draft.IngredientSpec;
import DIV.credit.client.draft.RecipeDraft.SlotKind;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.fluids.FluidStack;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Mekanism KubeJS recipe 形式の emit。
 * 全 Mek recipe schema は event.recipes.mekanism.<name>({field: value, ...}).id('...') 形式。
 * field 名は schema ごと違うので、JEI uid → schema 種別マッピングして対応する。
 *
 * 対応外（schema が KubeJS 側に存在しない）：boiler_casing, sps_casing, nutritional_liquifier
 */
public final class MekanismKubeJSEmitter {

    private MekanismKubeJSEmitter() {}

    /** JEI uid path → KubeJS recipe name + schema pattern。 */
    private record SchemaInfo(String kjsName, Pattern pattern) {}

    private enum Pattern {
        ITEM_TO_ITEM,           // input + output (item→item, fluid→fluid, gas→gas など単一 i/o)
        SAWING,                 // input + mainOutput + secondaryOutput
        COMBINING,              // mainInput + extraInput + output
        TWO_GAS_TO_GAS,         // leftInput + rightInput + output
        ITEM_AND_CHEMICAL,      // itemInput + chemicalInput + output
        DISSOLUTION,            // itemInput + gasInput + output (output 型は recipe 依存)
        NUCLEOSYNTHESIZING,     // itemInput + gasInput + output + duration
        WASHING,                // fluidInput + slurryInput + output
        SEPARATING,             // input + leftGasOutput + rightGasOutput
        ROTARY,                 // condensentrating: fluidInput + gasOutput / decondensentrating: gasInput + fluidOutput
        REACTION,               // 加圧反応（hand-written PressurizedReactionDraft が担当、ここは保険）
        CRYSTALLIZING,          // input(gas) + output(item)
    }

    private static final Map<String, SchemaInfo> SCHEMA_BY_UID = new HashMap<>();
    static {
        // input + output 単純系
        SCHEMA_BY_UID.put("crusher",                          new SchemaInfo("crushing",            Pattern.ITEM_TO_ITEM));
        SCHEMA_BY_UID.put("enrichment_chamber",               new SchemaInfo("enriching",           Pattern.ITEM_TO_ITEM));
        SCHEMA_BY_UID.put("energized_smelter",                new SchemaInfo("smelting",            Pattern.ITEM_TO_ITEM));
        SCHEMA_BY_UID.put("chemical_oxidizer",                new SchemaInfo("oxidizing",           Pattern.ITEM_TO_ITEM));
        SCHEMA_BY_UID.put("thermal_evaporation_controller",   new SchemaInfo("evaporating",         Pattern.ITEM_TO_ITEM));
        SCHEMA_BY_UID.put("solar_neutron_activator",          new SchemaInfo("activating",          Pattern.ITEM_TO_ITEM));
        SCHEMA_BY_UID.put("isotopic_centrifuge",              new SchemaInfo("centrifuging",        Pattern.ITEM_TO_ITEM));
        SCHEMA_BY_UID.put("pigment_extractor",                new SchemaInfo("pigment_extracting",  Pattern.ITEM_TO_ITEM));
        // energy_conversion: KubeJS 側 schema 上 output は FloatingLong（EU 量数値）。
        // 我々の IngredientSpec が数値出力を扱えないため対応外。コメント skeleton で出る。
        SCHEMA_BY_UID.put("gas_conversion",                   new SchemaInfo("gas_conversion",      Pattern.ITEM_TO_ITEM));
        SCHEMA_BY_UID.put("infusion_conversion",              new SchemaInfo("infusion_conversion", Pattern.ITEM_TO_ITEM));
        // sawing
        SCHEMA_BY_UID.put("precision_sawmill",                new SchemaInfo("sawing",              Pattern.SAWING));
        // combining
        SCHEMA_BY_UID.put("combiner",                         new SchemaInfo("combining",           Pattern.COMBINING));
        // 2 chemical → 1 chemical
        SCHEMA_BY_UID.put("chemical_infuser",                 new SchemaInfo("chemical_infusing",   Pattern.TWO_GAS_TO_GAS));
        SCHEMA_BY_UID.put("pigment_mixer",                    new SchemaInfo("pigment_mixing",      Pattern.TWO_GAS_TO_GAS));
        // item + chemical → item
        SCHEMA_BY_UID.put("osmium_compressor",                new SchemaInfo("compressing",         Pattern.ITEM_AND_CHEMICAL));
        SCHEMA_BY_UID.put("purification_chamber",             new SchemaInfo("purifying",           Pattern.ITEM_AND_CHEMICAL));
        SCHEMA_BY_UID.put("chemical_injection_chamber",       new SchemaInfo("injecting",           Pattern.ITEM_AND_CHEMICAL));
        SCHEMA_BY_UID.put("painting_machine",                 new SchemaInfo("painting",            Pattern.ITEM_AND_CHEMICAL));
        SCHEMA_BY_UID.put("metallurgic_infuser",              new SchemaInfo("metallurgic_infusing",Pattern.ITEM_AND_CHEMICAL));
        // dissolution
        SCHEMA_BY_UID.put("chemical_dissolution_chamber",     new SchemaInfo("dissolution",         Pattern.DISSOLUTION));
        // nucleosynthesizing
        SCHEMA_BY_UID.put("antiprotonic_nucleosynthesizer",   new SchemaInfo("nucleosynthesizing",  Pattern.NUCLEOSYNTHESIZING));
        // washing
        SCHEMA_BY_UID.put("chemical_washer",                  new SchemaInfo("washing",             Pattern.WASHING));
        // separating
        SCHEMA_BY_UID.put("electrolytic_separator",           new SchemaInfo("separating",          Pattern.SEPARATING));
        // rotary（両方向）
        SCHEMA_BY_UID.put("condensentrating",                 new SchemaInfo("rotary",              Pattern.ROTARY));
        SCHEMA_BY_UID.put("decondensentrating",               new SchemaInfo("rotary",              Pattern.ROTARY));
        // reaction（保険、通常は PressurizedReactionDraft が処理）
        SCHEMA_BY_UID.put("pressurized_reaction_chamber",     new SchemaInfo("reaction",            Pattern.REACTION));
        // crystallizing
        SCHEMA_BY_UID.put("chemical_crystallizer",            new SchemaInfo("crystallizing",       Pattern.CRYSTALLIZING));
    }

    /**
     * 対応カテゴリなら KubeJS recipe 文字列を返す。未対応 / 必須 field 不足なら null。
     */
    @Nullable
    public static String emit(String recipeId, ResourceLocation jeiUid, IngredientSpec[] slots, SlotKind[] kinds) {
        SchemaInfo info = SCHEMA_BY_UID.get(jeiUid.getPath());
        if (info == null) return null;

        LinkedHashMap<String, String> fields = new LinkedHashMap<>();
        if (!buildFields(info.pattern, slots, kinds, fields)) return null;
        if (fields.isEmpty()) return null;

        StringBuilder sb = new StringBuilder();
        sb.append("    event.recipes.mekanism.").append(info.kjsName).append("({\n");
        int i = 0, n = fields.size();
        for (Map.Entry<String, String> e : fields.entrySet()) {
            sb.append("        ").append(e.getKey()).append(": ").append(e.getValue());
            if (++i < n) sb.append(",");
            sb.append("\n");
        }
        sb.append("    }).id('").append(recipeId).append("');\n");
        return sb.toString();
    }

    private static boolean buildFields(Pattern p, IngredientSpec[] slots, SlotKind[] kinds, LinkedHashMap<String, String> out) {
        switch (p) {
            case ITEM_TO_ITEM: {
                String in  = firstValue(slots, kinds, true);
                String op  = firstValue(slots, kinds, false);
                if (in == null || op == null) return false;
                out.put("input", in);
                out.put("output", op);
                return true;
            }
            case SAWING: {
                String in   = firstOfKind(slots, kinds, SlotKind.ITEM_INPUT);
                List<String> outs = allOfKind(slots, kinds, SlotKind.ITEM_OUTPUT);
                if (in == null || outs.isEmpty()) return false;
                out.put("input", in);
                out.put("mainOutput", outs.get(0));
                if (outs.size() >= 2) out.put("secondaryOutput", outs.get(1));
                return true;
            }
            case COMBINING: {
                List<String> ins = allOfKind(slots, kinds, SlotKind.ITEM_INPUT);
                String op  = firstOfKind(slots, kinds, SlotKind.ITEM_OUTPUT);
                if (ins.size() < 2 || op == null) return false;
                out.put("mainInput",  ins.get(0));
                out.put("extraInput", ins.get(1));
                out.put("output", op);
                return true;
            }
            case TWO_GAS_TO_GAS: {
                List<String> ins = allOfKind(slots, kinds, SlotKind.GAS_INPUT);
                String op  = firstOfKind(slots, kinds, SlotKind.GAS_OUTPUT);
                if (ins.size() < 2 || op == null) return false;
                out.put("leftInput",  ins.get(0));
                out.put("rightInput", ins.get(1));
                out.put("output", op);
                return true;
            }
            case ITEM_AND_CHEMICAL: {
                String item = firstOfKind(slots, kinds, SlotKind.ITEM_INPUT);
                String chem = firstOfKind(slots, kinds, SlotKind.GAS_INPUT);
                String op   = firstOfKind(slots, kinds, SlotKind.ITEM_OUTPUT);
                if (item == null || chem == null || op == null) return false;
                out.put("itemInput", item);
                out.put("chemicalInput", chem);
                out.put("output", op);
                return true;
            }
            case DISSOLUTION: {
                String item = firstOfKind(slots, kinds, SlotKind.ITEM_INPUT);
                String gas  = firstOfKind(slots, kinds, SlotKind.GAS_INPUT);
                // dissolution は output が GAS なので GAS_OUTPUT を見る
                String op   = firstOfKind(slots, kinds, SlotKind.GAS_OUTPUT);
                if (item == null || gas == null || op == null) return false;
                out.put("itemInput", item);
                out.put("gasInput", gas);
                out.put("output", op);
                return true;
            }
            case NUCLEOSYNTHESIZING: {
                String item = firstOfKind(slots, kinds, SlotKind.ITEM_INPUT);
                String gas  = firstOfKind(slots, kinds, SlotKind.GAS_INPUT);
                String op   = firstOfKind(slots, kinds, SlotKind.ITEM_OUTPUT);
                if (item == null || gas == null || op == null) return false;
                out.put("itemInput", item);
                out.put("gasInput", gas);
                out.put("output", op);
                out.put("duration", "100");  // TODO numeric 連携
                return true;
            }
            case WASHING: {
                String fluid  = firstOfKind(slots, kinds, SlotKind.FLUID_INPUT);
                String slurryIn = firstOfKind(slots, kinds, SlotKind.GAS_INPUT);
                String slurryOut = firstOfKind(slots, kinds, SlotKind.GAS_OUTPUT);
                if (fluid == null || slurryIn == null || slurryOut == null) return false;
                out.put("fluidInput", fluid);
                out.put("slurryInput", slurryIn);
                out.put("output", slurryOut);
                return true;
            }
            case SEPARATING: {
                String fluid = firstOfKind(slots, kinds, SlotKind.FLUID_INPUT);
                List<String> gases = allOfKind(slots, kinds, SlotKind.GAS_OUTPUT);
                if (fluid == null || gases.size() < 2) return false;
                out.put("input", fluid);
                out.put("leftGasOutput",  gases.get(0));
                out.put("rightGasOutput", gases.get(1));
                return true;
            }
            case ROTARY: {
                // どちら方向か：FLUID_INPUT があれば condensentrating、GAS_INPUT があれば decondensentrating
                String fluidIn = firstOfKind(slots, kinds, SlotKind.FLUID_INPUT);
                String gasIn   = firstOfKind(slots, kinds, SlotKind.GAS_INPUT);
                String fluidOut = firstOfKind(slots, kinds, SlotKind.FLUID_OUTPUT);
                String gasOut   = firstOfKind(slots, kinds, SlotKind.GAS_OUTPUT);
                if (fluidIn != null && gasOut != null) {
                    out.put("fluidInput", fluidIn);
                    out.put("gasOutput", gasOut);
                    return true;
                }
                if (gasIn != null && fluidOut != null) {
                    out.put("gasInput", gasIn);
                    out.put("fluidOutput", fluidOut);
                    return true;
                }
                return false;
            }
            case REACTION: {
                String item = firstOfKind(slots, kinds, SlotKind.ITEM_INPUT);
                String fluid = firstOfKind(slots, kinds, SlotKind.FLUID_INPUT);
                String gas  = firstOfKind(slots, kinds, SlotKind.GAS_INPUT);
                String itemOut = firstOfKind(slots, kinds, SlotKind.ITEM_OUTPUT);
                String gasOut  = firstOfKind(slots, kinds, SlotKind.GAS_OUTPUT);
                if (item == null || fluid == null || gas == null) return false;
                if (itemOut == null && gasOut == null) return false;
                out.put("itemInput", item);
                out.put("fluidInput", fluid);
                out.put("gasInput", gas);
                if (itemOut != null) out.put("itemOutput", itemOut);
                if (gasOut != null)  out.put("gasOutput",  gasOut);
                out.put("duration", "100");
                out.put("energyRequired", "0");
                return true;
            }
            case CRYSTALLIZING: {
                String gas = firstOfKind(slots, kinds, SlotKind.GAS_INPUT);
                String op  = firstOfKind(slots, kinds, SlotKind.ITEM_OUTPUT);
                if (gas == null || op == null) return false;
                out.put("chemicalType", "'gas'");  // 我々の IngredientSpec.Gas は GAS のみ対応
                out.put("input", gas);
                out.put("output", op);
                return true;
            }
        }
        return false;
    }

    /** 1 番目に見つかった input slot 値（どの kind でも可）→ 値文字列。 */
    @Nullable
    private static String firstValue(IngredientSpec[] slots, SlotKind[] kinds, boolean wantInput) {
        for (int i = 0; i < slots.length; i++) {
            if (slots[i].isEmpty()) continue;
            boolean isInput = isInputKind(kinds[i]);
            if (isInput != wantInput) continue;
            return formatValue(slots[i]);
        }
        return null;
    }

    @Nullable
    private static String firstOfKind(IngredientSpec[] slots, SlotKind[] kinds, SlotKind want) {
        for (int i = 0; i < slots.length; i++) {
            if (kinds[i] == want && !slots[i].isEmpty()) {
                return formatValue(slots[i]);
            }
        }
        return null;
    }

    private static List<String> allOfKind(IngredientSpec[] slots, SlotKind[] kinds, SlotKind want) {
        List<String> result = new ArrayList<>();
        for (int i = 0; i < slots.length; i++) {
            if (kinds[i] == want && !slots[i].isEmpty()) {
                String v = formatValue(slots[i]);
                if (v != null) result.add(v);
            }
        }
        return result;
    }

    private static boolean isInputKind(SlotKind k) {
        return k == SlotKind.ITEM_INPUT || k == SlotKind.FLUID_INPUT || k == SlotKind.GAS_INPUT;
    }

    /** Mek KubeJS 値表記：item は単数 'id' / 複数 Item.of('id', N)、fluid は Fluid.of、gas は 'id N'。 */
    @Nullable
    private static String formatValue(IngredientSpec s) {
        if (s.isEmpty()) return null;
        if (s instanceof IngredientSpec.Item it && !it.stack().isEmpty()) {
            ResourceLocation rl = BuiltInRegistries.ITEM.getKey(it.stack().getItem());
            int c = it.stack().getCount();
            return c <= 1 ? "'" + rl + "'" : "Item.of('" + rl + "', " + c + ")";
        }
        if (s instanceof IngredientSpec.Tag tg && tg.tagId() != null) {
            return "'#" + tg.tagId() + "'";
        }
        if (s instanceof IngredientSpec.Fluid fl && !fl.stack().isEmpty()) {
            FluidStack fs = fl.stack();
            ResourceLocation rl = BuiltInRegistries.FLUID.getKey(fs.getFluid());
            return "Fluid.of('" + rl + "', " + fs.getAmount() + ")";
        }
        if (s instanceof IngredientSpec.FluidTag ft && ft.tagId() != null) {
            return "Fluid.of('#" + ft.tagId() + "', " + ft.amount() + ")";
        }
        if (s instanceof IngredientSpec.Gas g && g.gasId() != null) {
            return "'" + g.gasId() + " " + g.amount() + "'";
        }
        return null;
    }
}
