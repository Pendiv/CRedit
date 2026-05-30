package DIV.credit.client.draft.botania;

import DIV.credit.client.draft.IngredientSpec;
import DIV.credit.client.draft.RecipeDraft.SlotKind;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;

/**
 * Botania 5 schema を emit。
 * <p>v4.1.x: {@code kubejs_botania} addon 検知時は addon shortcut syntax
 * ({@code event.recipes.botania.<type>({...})}) で emit、 不在時は generic
 * {@code event.custom({type:'botania:<type>', ...})} に fallback。
 * <p>JEI category UID → recipe TYPE id 変換は {@link BotaniaSupport#JEI_UID_TO_TYPE} 参照。
 */
public final class BotaniaKubeJSEmitter {

    private BotaniaKubeJSEmitter() {}

    /** kubejs_botania addon load 状態を 1 回 cache (= 起動中変わらないため)。 */
    private static Boolean ADDON_LOADED_CACHE;

    /** addon 検知 (= kubejs_botania mod 在 + load 完了)。 */
    private static boolean isAddonLoaded() {
        Boolean c = ADDON_LOADED_CACHE;
        if (c != null) return c;
        try {
            boolean r = net.neoforged.fml.ModList.get().isLoaded("kubejs_botania");
            ADDON_LOADED_CACHE = r;
            return r;
        } catch (Throwable t) { return false; }
    }

    @Nullable
    public static String emit(String recipeId, ResourceLocation jeiUid, IngredientSpec[] slots, SlotKind[] kinds, long mana) {
        String typeId = BotaniaSupport.JEI_UID_TO_TYPE.get(jeiUid.toString());
        if (typeId == null) return null;
        return switch (jeiUid.getPath()) {
            case "elven_trade"  -> emitElvenTrade(recipeId, typeId, slots, kinds);
            case "mana_pool"    -> emitManaInfusion(recipeId, typeId, slots, kinds, mana);
            case "petals"       -> emitPetalApothecary(recipeId, typeId, slots, kinds);
            case "runic_altar"  -> emitRunicAltar(recipeId, typeId, slots, kinds, mana);
            case "terra_plate"  -> emitTerraPlate(recipeId, typeId, slots, kinds, mana);
            default -> null;
        };
    }

    /** elven_trade: ingredients[] + output[]。 */
    @Nullable
    private static String emitElvenTrade(String recipeId, String type, IngredientSpec[] slots, SlotKind[] kinds) {
        List<String> ins  = collectAll(slots, kinds, SlotKind.ITEM_INPUT,  BotaniaKubeJSEmitter::itemIngredient);
        List<String> outs = collectAll(slots, kinds, SlotKind.ITEM_OUTPUT, BotaniaKubeJSEmitter::itemOutput);
        if (ins.isEmpty() || outs.isEmpty()) return null;
        LinkedHashMap<String, String> f = new LinkedHashMap<>();
        f.put("ingredients", "[" + String.join(", ", ins) + "]");
        f.put("output",      "[" + String.join(", ", outs) + "]");
        return buildEventCustom(recipeId, type, f);
    }

    /** mana_infusion: input + mana + output。 catalyst (block state) は emit 対象外 (= 基本 infusion)。 */
    @Nullable
    private static String emitManaInfusion(String recipeId, String type, IngredientSpec[] slots, SlotKind[] kinds, long mana) {
        String in  = findFirst(slots, kinds, SlotKind.ITEM_INPUT,  BotaniaKubeJSEmitter::itemIngredient);
        String out = findFirst(slots, kinds, SlotKind.ITEM_OUTPUT, BotaniaKubeJSEmitter::itemOutput);
        if (in == null || out == null) return null;
        LinkedHashMap<String, String> f = new LinkedHashMap<>();
        f.put("input",  in);
        f.put("mana",   String.valueOf(mana > 0 ? mana : 1000));
        f.put("output", out);
        return buildEventCustom(recipeId, type, f);
    }

    /** petal_apothecary: ingredients[] + output + reagent (default tag)。 */
    @Nullable
    private static String emitPetalApothecary(String recipeId, String type, IngredientSpec[] slots, SlotKind[] kinds) {
        List<String> ins = collectAll(slots, kinds, SlotKind.ITEM_INPUT, BotaniaKubeJSEmitter::itemIngredient);
        String out = findFirst(slots, kinds, SlotKind.ITEM_OUTPUT, BotaniaKubeJSEmitter::itemOutput);
        if (ins.isEmpty() || out == null) return null;
        LinkedHashMap<String, String> f = new LinkedHashMap<>();
        f.put("ingredients", "[" + String.join(", ", ins) + "]");
        f.put("output",      out);
        f.put("reagent",     "{ tag: 'botania:seed_apothecary_reagent' }");
        return buildEventCustom(recipeId, type, f);
    }

    /** runic_altar: ingredients[] + mana + output。 */
    @Nullable
    private static String emitRunicAltar(String recipeId, String type, IngredientSpec[] slots, SlotKind[] kinds, long mana) {
        List<String> ins = collectAll(slots, kinds, SlotKind.ITEM_INPUT, BotaniaKubeJSEmitter::itemIngredient);
        String out = findFirst(slots, kinds, SlotKind.ITEM_OUTPUT, BotaniaKubeJSEmitter::itemOutput);
        if (ins.isEmpty() || out == null) return null;
        LinkedHashMap<String, String> f = new LinkedHashMap<>();
        f.put("ingredients", "[" + String.join(", ", ins) + "]");
        f.put("mana",        String.valueOf(mana > 0 ? mana : 5000));
        f.put("output",      out);
        return buildEventCustom(recipeId, type, f);
    }

    /** terra_plate: ingredients[] + mana + result。 */
    @Nullable
    private static String emitTerraPlate(String recipeId, String type, IngredientSpec[] slots, SlotKind[] kinds, long mana) {
        List<String> ins = collectAll(slots, kinds, SlotKind.ITEM_INPUT, BotaniaKubeJSEmitter::itemIngredient);
        String out = findFirst(slots, kinds, SlotKind.ITEM_OUTPUT, BotaniaKubeJSEmitter::itemOutput);
        if (ins.isEmpty() || out == null) return null;
        LinkedHashMap<String, String> f = new LinkedHashMap<>();
        f.put("ingredients", "[" + String.join(", ", ins) + "]");
        f.put("mana",        String.valueOf(mana > 0 ? mana : 500000));
        f.put("result",      out);
        return buildEventCustom(recipeId, type, f);
    }

    // ─── helpers ───

    /**
     * Botania recipe を emit。
     * <p>{@code kubejs_botania} 在時: {@code event.recipes.botania.<typePath>({...}).id('...')}
     * <p>不在時: {@code event.custom({type:'<type>', ...}).id('...')} (= 既存)
     *
     * <p>両 path とも field 内容は identical (= addon schema が generic JSON 受けるため)。
     */
    private static String buildEventCustom(String recipeId, String type, LinkedHashMap<String, String> fields) {
        StringBuilder sb = new StringBuilder();
        if (isAddonLoaded()) {
            // type は "botania:mana_infusion" 形式 → path 部分 (= "mana_infusion") 抽出
            String typePath = type.contains(":") ? type.substring(type.indexOf(':') + 1) : type;
            sb.append("    event.recipes.botania.").append(typePath).append("({\n");
            int i = 0, n = fields.size();
            for (var e : fields.entrySet()) {
                sb.append("        ").append(e.getKey()).append(": ").append(e.getValue());
                if (++i < n) sb.append(",");
                sb.append("\n");
            }
            sb.append("    }).id('").append(recipeId).append("');\n");
        } else {
            sb.append("    event.custom({\n");
            sb.append("        type: '").append(type).append("',\n");
            int i = 0, n = fields.size();
            for (var e : fields.entrySet()) {
                sb.append("        ").append(e.getKey()).append(": ").append(e.getValue());
                if (++i < n) sb.append(",");
                sb.append("\n");
            }
            sb.append("    }).id('").append(recipeId).append("');\n");
        }
        DIV.credit.client.io.EmitSelfTest.verifyFields(type, fields, recipeId);
        return sb.toString();
    }

    @Nullable
    private static String itemIngredient(IngredientSpec s) {
        if (s == null || s.isEmpty()) return null;
        IngredientSpec base = s.unwrap();
        if (base instanceof IngredientSpec.Item it && !it.stack().isEmpty()) {
            return "{ item: '" + BuiltInRegistries.ITEM.getKey(it.stack().getItem()) + "' }";
        }
        if (base instanceof IngredientSpec.Tag tg && tg.tagId() != null) {
            return "{ tag: '" + tg.tagId() + "' }";
        }
        return null;
    }

    @Nullable
    private static String itemOutput(IngredientSpec s) {
        if (s == null || s.isEmpty()) return null;
        IngredientSpec base = s.unwrap();
        if (base instanceof IngredientSpec.Item it && !it.stack().isEmpty()) {
            ResourceLocation rl = BuiltInRegistries.ITEM.getKey(it.stack().getItem());
            int c = it.stack().getCount();
            return c <= 1 ? "{ item: '" + rl + "' }"
                          : "{ item: '" + rl + "', count: " + c + " }";
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
