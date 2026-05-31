package DIV.credit.client.draft.mek;

import DIV.credit.client.draft.IngredientSpec;
import DIV.credit.client.draft.RecipeDraft.SlotKind;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.fluids.FluidStack;
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
        // 1.21: JEI category uid path = recipe-type 名 (= kjsName) で、 1.20.1 の machine 名ではない。
        //   各 schema を kjsName でも引けるよう別名登録 (rotary は condensentrating/decondensentrating で既登録済)。
        for (SchemaInfo si : new java.util.ArrayList<>(SCHEMA_BY_UID.values())) {
            SCHEMA_BY_UID.putIfAbsent(si.kjsName(), si);
        }
    }

    /**
     * 対応カテゴリなら KubeJS recipe 文字列を返す。未対応 / 必須 field 不足なら null。
     *
     * 経路:
     *  - mekanism:* 標準 schema → event.recipes.mekanism.<name>({...}) (KubeJS Mek plugin 経由)
     *  - evolvedmekanism:* 一部 → event.custom({type:..., ...}).id() で raw JSON
     */
    @Nullable
    public static String emit(String recipeId, ResourceLocation jeiUid, IngredientSpec[] slots, SlotKind[] kinds) {
        // EvolvedMek: event.custom 経路（KubeJS schema が存在しないため raw JSON で投げる）
        if ("evolvedmekanism".equals(jeiUid.getNamespace())) {
            return emitEvolvedMek(recipeId, jeiUid.getPath(), slots, kinds);
        }
        // mekanism:* 標準
        SchemaInfo info = SCHEMA_BY_UID.get(jeiUid.getPath());
        if (info == null) return null;

        LinkedHashMap<String, String> fields = new LinkedHashMap<>();
        if (!buildFields(info.pattern, slots, kinds, fields)) return null;
        if (fields.isEmpty()) return null;
        // 1.21: KubeJS Mek アドオン (event.recipes.mekanism.*) に依存せず、 event.custom 生JSON で出力。
        //   Mek の RecipeSerializer が直接消費する形 (= /reload で適用)。 type は recipe-type uid。
        return buildEventCustom(recipeId, "mekanism:" + info.kjsName, fields);
    }

    private static boolean buildFields(Pattern p, IngredientSpec[] slots, SlotKind[] kinds, LinkedHashMap<String, String> out) {
        switch (p) {
            case ITEM_TO_ITEM: {  // input + output (item→item / item→chemical / chemical→item など単一 i/o)
                IngredientSpec in = firstInput(slots, kinds);
                IngredientSpec op = firstOutput(slots, kinds);
                if (in == null || op == null) return false;
                out.put("input",  jsonOf(in, false));
                out.put("output", jsonOf(op, true));
                return true;
            }
            case SAWING: {
                IngredientSpec in = firstSpec(slots, kinds, SlotKind.ITEM_INPUT);
                List<IngredientSpec> outs = allSpecs(slots, kinds, SlotKind.ITEM_OUTPUT);
                if (in == null || outs.isEmpty()) return false;
                out.put("input", jsonOf(in, false));
                out.put("main_output", jsonOf(outs.get(0), true));
                if (outs.size() >= 2) out.put("secondary_output", jsonOf(outs.get(1), true));
                return true;
            }
            case COMBINING: {
                List<IngredientSpec> ins = allSpecs(slots, kinds, SlotKind.ITEM_INPUT);
                IngredientSpec op = firstSpec(slots, kinds, SlotKind.ITEM_OUTPUT);
                if (ins.size() < 2 || op == null) return false;
                out.put("main_input",  jsonOf(ins.get(0), false));
                out.put("extra_input", jsonOf(ins.get(1), false));
                out.put("output", jsonOf(op, true));
                return true;
            }
            case TWO_GAS_TO_GAS: {  // chemical_infusing / pigment_mixing
                List<IngredientSpec> ins = allSpecs(slots, kinds, SlotKind.GAS_INPUT);
                IngredientSpec op = firstSpec(slots, kinds, SlotKind.GAS_OUTPUT);
                if (ins.size() < 2 || op == null) return false;
                out.put("left_input",  jsonOf(ins.get(0), false));
                out.put("right_input", jsonOf(ins.get(1), false));
                out.put("output", jsonOf(op, true));
                return true;
            }
            case ITEM_AND_CHEMICAL: {  // purifying / injecting / compressing / painting / metallurgic_infusing
                IngredientSpec item = firstSpec(slots, kinds, SlotKind.ITEM_INPUT);
                IngredientSpec chem = firstSpec(slots, kinds, SlotKind.GAS_INPUT);
                IngredientSpec op   = firstSpec(slots, kinds, SlotKind.ITEM_OUTPUT);
                if (item == null || chem == null || op == null) return false;
                out.put("item_input", jsonOf(item, false));
                out.put("chemical_input", jsonOf(chem, false));
                out.put("output", jsonOf(op, true));
                out.put("per_tick_usage", "false");
                return true;
            }
            case DISSOLUTION: {  // item + chemical → chemical (best-effort, JSON 例が data 内に無いため未確証)
                IngredientSpec item = firstSpec(slots, kinds, SlotKind.ITEM_INPUT);
                IngredientSpec gas  = firstSpec(slots, kinds, SlotKind.GAS_INPUT);
                IngredientSpec op   = firstSpec(slots, kinds, SlotKind.GAS_OUTPUT);
                if (item == null || gas == null || op == null) return false;
                out.put("item_input", jsonOf(item, false));
                out.put("chemical_input", jsonOf(gas, false));
                out.put("output", jsonOf(op, true));
                out.put("per_tick_usage", "false");
                return true;
            }
            case NUCLEOSYNTHESIZING: {  // item_input + chemical_input + output + duration
                IngredientSpec item = firstSpec(slots, kinds, SlotKind.ITEM_INPUT);
                IngredientSpec gas  = firstSpec(slots, kinds, SlotKind.GAS_INPUT);
                IngredientSpec op   = firstSpec(slots, kinds, SlotKind.ITEM_OUTPUT);
                if (item == null || gas == null || op == null) return false;
                out.put("item_input", jsonOf(item, false));
                out.put("chemical_input", jsonOf(gas, false));
                out.put("output", jsonOf(op, true));
                out.put("duration", "200");  // TODO numeric 連携 (実値はレシピ依存)
                out.put("per_tick_usage", "false");
                return true;
            }
            case WASHING: {  // fluid + chemical → chemical (best-effort)
                IngredientSpec fluid = firstSpec(slots, kinds, SlotKind.FLUID_INPUT);
                IngredientSpec chem  = firstSpec(slots, kinds, SlotKind.GAS_INPUT);
                IngredientSpec op    = firstSpec(slots, kinds, SlotKind.GAS_OUTPUT);
                if (fluid == null || chem == null || op == null) return false;
                out.put("fluid_input", jsonOf(fluid, false));
                out.put("chemical_input", jsonOf(chem, false));
                out.put("output", jsonOf(op, true));
                return true;
            }
            case SEPARATING: {
                // electrolytic_separator: JSON 例が data 内に無くフィールド名未確証 → skeleton fallback に委ねる
                return false;
            }
            case ROTARY: {
                // condensentrating: fluid→chemical / decondensentrating: chemical→fluid
                IngredientSpec fluidIn = firstSpec(slots, kinds, SlotKind.FLUID_INPUT);
                IngredientSpec gasIn   = firstSpec(slots, kinds, SlotKind.GAS_INPUT);
                IngredientSpec fluidOut = firstSpec(slots, kinds, SlotKind.FLUID_OUTPUT);
                IngredientSpec gasOut   = firstSpec(slots, kinds, SlotKind.GAS_OUTPUT);
                if (fluidIn != null && gasOut != null) {
                    out.put("fluid_input", jsonOf(fluidIn, false));
                    out.put("chemical_output", jsonOf(gasOut, true));
                    return true;
                }
                if (gasIn != null && fluidOut != null) {
                    out.put("chemical_input", jsonOf(gasIn, false));
                    out.put("fluid_output", jsonOf(fluidOut, true));
                    return true;
                }
                return false;
            }
            case REACTION: {
                // pressurized_reaction: item_input + fluid_input + chemical_input → item_output / chemical_output (+ duration)
                IngredientSpec item  = firstSpec(slots, kinds, SlotKind.ITEM_INPUT);
                IngredientSpec fluid = firstSpec(slots, kinds, SlotKind.FLUID_INPUT);
                IngredientSpec chem  = firstSpec(slots, kinds, SlotKind.GAS_INPUT);
                IngredientSpec itemOut = firstSpec(slots, kinds, SlotKind.ITEM_OUTPUT);
                IngredientSpec chemOut = firstSpec(slots, kinds, SlotKind.GAS_OUTPUT);
                if (item == null || fluid == null || chem == null) return false;
                if (itemOut == null && chemOut == null) return false;
                out.put("item_input", jsonOf(item, false));
                out.put("fluid_input", jsonOf(fluid, false));
                out.put("chemical_input", jsonOf(chem, false));
                if (itemOut != null) out.put("item_output", jsonOf(itemOut, true));
                if (chemOut != null) out.put("chemical_output", jsonOf(chemOut, true));
                out.put("duration", "200");  // TODO numeric 連携 (実値はレシピ依存)
                return true;
            }
            case CRYSTALLIZING: {  // chemical → item
                IngredientSpec gas = firstSpec(slots, kinds, SlotKind.GAS_INPUT);
                IngredientSpec op  = firstSpec(slots, kinds, SlotKind.ITEM_OUTPUT);
                if (gas == null || op == null) return false;
                out.put("input",  jsonOf(gas, false));
                out.put("output", jsonOf(op, true));
                return true;
            }
        }
        return false;
    }

    // ───── 1.21 spec finders + raw-JSON value builder ─────

    @Nullable
    private static IngredientSpec firstSpec(IngredientSpec[] slots, SlotKind[] kinds, SlotKind want) {
        for (int i = 0; i < slots.length; i++) if (kinds[i] == want && !slots[i].isEmpty()) return slots[i];
        return null;
    }

    private static List<IngredientSpec> allSpecs(IngredientSpec[] slots, SlotKind[] kinds, SlotKind want) {
        List<IngredientSpec> r = new ArrayList<>();
        for (int i = 0; i < slots.length; i++) if (kinds[i] == want && !slots[i].isEmpty()) r.add(slots[i]);
        return r;
    }

    @Nullable
    private static IngredientSpec firstInput(IngredientSpec[] slots, SlotKind[] kinds) {
        for (int i = 0; i < slots.length; i++) if (!slots[i].isEmpty() && isInputKind(kinds[i])) return slots[i];
        return null;
    }

    @Nullable
    private static IngredientSpec firstOutput(IngredientSpec[] slots, SlotKind[] kinds) {
        for (int i = 0; i < slots.length; i++) if (!slots[i].isEmpty() && !isInputKind(kinds[i])) return slots[i];
        return null;
    }

    private static boolean isInputKind(SlotKind k) {
        return k == SlotKind.ITEM_INPUT || k == SlotKind.FLUID_INPUT || k == SlotKind.GAS_INPUT;
    }

    /**
     * 1.21 Mek raw-JSON 値オブジェクト (event.custom 用)。 入力は item/fluid/chemical/tag キー、 出力は id キー。
     * <ul>
     *   <li>item:  入力 {@code {count:N,item:'id'}} / 出力 {@code {count:N,id:'id'}} / tag {@code {count:N,tag:'id'}}</li>
     *   <li>fluid: 入力 {@code {amount:N,fluid:'id'}} / 出力 {@code {amount:N,id:'id'}}</li>
     *   <li>chemical: 入力 {@code {amount:N,chemical:'id'}} / 出力 {@code {amount:N,id:'id'}}</li>
     * </ul>
     */
    @Nullable
    private static String jsonOf(IngredientSpec s, boolean output) {
        if (s == null) return null;
        s = s.unwrap();
        if (s instanceof IngredientSpec.Item it && !it.stack().isEmpty()) {
            ResourceLocation rl = BuiltInRegistries.ITEM.getKey(it.stack().getItem());
            int c = Math.max(1, it.stack().getCount());
            return output ? "{ count: " + c + ", id: '" + rl + "' }"
                          : "{ count: " + c + ", item: '" + rl + "' }";
        }
        if (s instanceof IngredientSpec.Tag tg && tg.tagId() != null) {
            if (output) return null;  // tag は出力にできない
            return "{ count: " + Math.max(1, tg.count()) + ", tag: '" + tg.tagId() + "' }";
        }
        if (s instanceof IngredientSpec.Fluid fl && !fl.stack().isEmpty()) {
            ResourceLocation rl = BuiltInRegistries.FLUID.getKey(fl.stack().getFluid());
            int amt = Math.max(1, fl.stack().getAmount());
            return output ? "{ amount: " + amt + ", id: '" + rl + "' }"
                          : "{ amount: " + amt + ", fluid: '" + rl + "' }";
        }
        if (s instanceof IngredientSpec.FluidTag ft && ft.tagId() != null) {
            if (output) return null;
            return "{ amount: " + Math.max(1, ft.amount()) + ", tag: '" + ft.tagId() + "' }";
        }
        if (s instanceof IngredientSpec.Gas g && g.gasId() != null) {
            int amt = Math.max(1, g.amount());
            return output ? "{ amount: " + amt + ", id: '" + g.gasId() + "' }"
                          : "{ amount: " + amt + ", chemical: '" + g.gasId() + "' }";
        }
        return null;
    }

    // ───── EvolvedMekanism event.custom emit ─────
    //
    // EvolvedMek の RecipeSerializer は KubeJS Mek plugin に schema が無いため、
    // event.custom({type, fields...}).id() で raw JSON を投げる必要がある。
    // フィールド名は EvolvedMek serializer 源 + 手書き参考 (kubejs/server_scripts/.../evolved/APT.js) で確定済み。
    //
    // JEI category UID パターン:
    //   evolvedmekanism:ingot_better_gold → APT (item + chemical → item)
    //   evolvedmekanism:alloyer           → Alloying (3 items → item)
    //   evolvedmekanism:chemixer          → Chemixing (2 items + gas → item)

    @Nullable
    private static String emitEvolvedMek(String recipeId, String path, IngredientSpec[] slots, SlotKind[] kinds) {
        return switch (path) {
            case "ingot_better_gold" -> emitAPT(recipeId, slots, kinds);
            case "alloyer"           -> emitAlloyer(recipeId, slots, kinds);
            case "chemixer"          -> emitChemixer(recipeId, slots, kinds);
            default -> null; // thermalizer / solidification_chamber 等は EXPLICIT_UNSUPPORTED で先に弾かれる想定
        };
    }

    /** evolvedmekanism:apt — Mek 標準 ItemStackGasToItemStack 流用、フィールドは itemInput/chemicalInput/output。 */
    @Nullable
    private static String emitAPT(String recipeId, IngredientSpec[] slots, SlotKind[] kinds) {
        String item = findFirst(slots, kinds, SlotKind.ITEM_INPUT,  MekanismKubeJSEmitter::itemIngredientJson);
        String gas  = findFirst(slots, kinds, SlotKind.GAS_INPUT,   MekanismKubeJSEmitter::gasIngredientJson);
        String op   = findFirst(slots, kinds, SlotKind.ITEM_OUTPUT, MekanismKubeJSEmitter::itemOutputJson);
        if (item == null || gas == null || op == null) return null;
        LinkedHashMap<String, String> f = new LinkedHashMap<>();
        f.put("itemInput", item);
        f.put("chemicalInput", gas);  // Mek 標準は chemicalInput
        f.put("output", op);
        return buildEventCustom(recipeId, "evolvedmekanism:apt", f);
    }

    /** evolvedmekanism:alloying — 3 item input + 1 item output。 */
    @Nullable
    private static String emitAlloyer(String recipeId, IngredientSpec[] slots, SlotKind[] kinds) {
        List<String> ins = collectAll(slots, kinds, SlotKind.ITEM_INPUT, MekanismKubeJSEmitter::itemIngredientJson);
        String op = findFirst(slots, kinds, SlotKind.ITEM_OUTPUT, MekanismKubeJSEmitter::itemOutputJson);
        if (ins.size() < 3 || op == null) return null;
        LinkedHashMap<String, String> f = new LinkedHashMap<>();
        f.put("mainInput", ins.get(0));
        f.put("extraInput", ins.get(1));
        f.put("secondExtraInput", ins.get(2));
        f.put("output", op);
        return buildEventCustom(recipeId, "evolvedmekanism:alloying", f);
    }

    /** evolvedmekanism:chemixing — 2 item input + 1 gas input + 1 item output。EvolvedMek 独自で field 名は gasInput。 */
    @Nullable
    private static String emitChemixer(String recipeId, IngredientSpec[] slots, SlotKind[] kinds) {
        List<String> ins = collectAll(slots, kinds, SlotKind.ITEM_INPUT, MekanismKubeJSEmitter::itemIngredientJson);
        String gas = findFirst(slots, kinds, SlotKind.GAS_INPUT, MekanismKubeJSEmitter::gasIngredientJson);
        String op  = findFirst(slots, kinds, SlotKind.ITEM_OUTPUT, MekanismKubeJSEmitter::itemOutputJson);
        if (ins.size() < 2 || gas == null || op == null) return null;
        LinkedHashMap<String, String> f = new LinkedHashMap<>();
        f.put("mainInput", ins.get(0));
        f.put("extraInput", ins.get(1));
        f.put("gasInput", gas);  // EvolvedMek 独自は gasInput
        f.put("output", op);
        return buildEventCustom(recipeId, "evolvedmekanism:chemixing", f);
    }

    private static String buildEventCustom(String recipeId, String type, LinkedHashMap<String, String> fields) {
        StringBuilder sb = new StringBuilder();
        sb.append("    event.custom({\n");
        sb.append("        type: '").append(type).append("',\n");
        int i = 0, n = fields.size();
        for (Map.Entry<String, String> e : fields.entrySet()) {
            sb.append("        ").append(e.getKey()).append(": ").append(e.getValue());
            if (++i < n) sb.append(",");
            sb.append("\n");
        }
        sb.append("    }).id('").append(recipeId).append("');\n");
        return sb.toString();
    }

    // ───── JSON value builders for event.custom ─────

    /** Mek ItemStackIngredient JSON: { ingredient: { item: 'id' }, amount: N } または tag 版。amount=1 は省略。 */
    @Nullable
    private static String itemIngredientJson(IngredientSpec s) {
        if (s == null || s.isEmpty()) return null;
        IngredientSpec base = s.unwrap();
        if (base instanceof IngredientSpec.Item it && !it.stack().isEmpty()) {
            ResourceLocation rl = BuiltInRegistries.ITEM.getKey(it.stack().getItem());
            int count = it.stack().getCount();
            String inner = "{ item: '" + rl + "' }";
            return count <= 1 ? "{ ingredient: " + inner + " }"
                              : "{ ingredient: " + inner + ", amount: " + count + " }";
        }
        if (base instanceof IngredientSpec.Tag tg && tg.tagId() != null) {
            String inner = "{ tag: '" + tg.tagId() + "' }";
            int count = Math.max(1, tg.count());
            return count <= 1 ? "{ ingredient: " + inner + " }"
                              : "{ ingredient: " + inner + ", amount: " + count + " }";
        }
        return null;
    }

    /**
     * Mek ChemicalStackIngredient JSON: {@code { amount: N, <key>: 'id' }}。
     * <p>chemicalType に応じて key 名 (= gas / infuse_type / pigment / slurry) 切替。
     */
    @Nullable
    private static String gasIngredientJson(IngredientSpec s) {
        if (s == null || s.isEmpty()) return null;
        IngredientSpec base = s.unwrap();
        if (base instanceof IngredientSpec.Gas g && g.gasId() != null) {
            String key = switch (g.chemicalType()) {
                case GAS      -> "gas";
                case INFUSION -> "infuse_type";
                case PIGMENT  -> "pigment";
                case SLURRY   -> "slurry";
            };
            return "{ amount: " + g.amount() + ", " + key + ": '" + g.gasId() + "' }";
        }
        return null;
    }

    /** ItemStack output JSON: { item: 'id', count: N }。count=1 は省略。 */
    @Nullable
    private static String itemOutputJson(IngredientSpec s) {
        if (s == null || s.isEmpty()) return null;
        IngredientSpec base = s.unwrap();
        if (base instanceof IngredientSpec.Item it && !it.stack().isEmpty()) {
            ResourceLocation rl = BuiltInRegistries.ITEM.getKey(it.stack().getItem());
            int count = it.stack().getCount();
            return count <= 1 ? "{ item: '" + rl + "' }"
                              : "{ item: '" + rl + "', count: " + count + " }";
        }
        return null;
    }

    @Nullable
    private static String findFirst(IngredientSpec[] slots, SlotKind[] kinds, SlotKind want,
                                    java.util.function.Function<IngredientSpec, String> formatter) {
        for (int i = 0; i < slots.length; i++) {
            if (kinds[i] == want && !slots[i].isEmpty()) {
                String s = formatter.apply(slots[i]);
                if (s != null) return s;
            }
        }
        return null;
    }

    private static List<String> collectAll(IngredientSpec[] slots, SlotKind[] kinds, SlotKind want,
                                            java.util.function.Function<IngredientSpec, String> formatter) {
        List<String> result = new ArrayList<>();
        for (int i = 0; i < slots.length; i++) {
            if (kinds[i] == want && !slots[i].isEmpty()) {
                String s = formatter.apply(slots[i]);
                if (s != null) result.add(s);
            }
        }
        return result;
    }
}
