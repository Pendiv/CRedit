package DIV.credit.client.draft.create;

import DIV.credit.client.draft.IngredientSpec;
import DIV.credit.client.draft.RecipeDraft.SlotKind;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.fluids.FluidStack;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Create の event.recipes.create.<id>({...}) 形式 emit。
 *
 * Create の特徴 (他 mod と異なる第 3 パターン):
 *   - item と fluid が同じ `ingredients` / `results` 配列に混在する
 *   - item: `{item:'id'}` or `{tag:'id'}` (vanilla 形式、IE と同じ)
 *   - fluid: `{fluid:'id', amount:N}` or `{fluidTag:'id', amount:N}` (キー名 fluidTag に注意)
 *   - output item の chance は default 1.0 で省略
 *
 * 対応 13 schemas (Tier-A、KubeJS-Create plugin が schema を提供):
 *   crushing / milling / cutting           — processingTime 必須
 *   pressing / sandpaper_polishing /
 *     splashing / haunting /
 *     filling / emptying                   — processingTime 任意
 *   mixing / compacting                    — heatRequirement 任意
 *   deploying / item_application           — keepHeldItem 任意
 *
 * 今回は default 値で出力 (heatRequirement / keepHeldItem は省略 = NONE/false)。
 * 将来的に CFG dropdown 等で UI 編集可にする余地あり。
 *
 * 対応外: mechanical_crafting (vanilla shaped 拡張、別途 Tier-B で対応予定),
 * sequenced_assembly (UI 複雑度大、defer)。
 */
public final class CreateKubeJSEmitter {

    /** crushing/cutting/milling 用 processingTime デフォルト (KubeJS schema 上 required)。 */
    private static final int DEFAULT_PROCESSING_TIME = 200;

    private CreateKubeJSEmitter() {}

    /**
     * JEI category UID path → 実 KubeJS recipe name のマッピング。
     * Create は category 名と recipe type 名が一致しないことが多く、要 alias 解決。
     * (例: JEI category "packing" の中身は実は "compacting" 系レシピ)
     */
    private static String resolveRecipeName(String jeiPath) {
        return switch (jeiPath) {
            case "fan_washing"   -> "splashing";
            case "fan_haunting"  -> "haunting";
            case "packing"       -> "compacting";
            case "sawing"        -> "cutting";
            case "spout_filling" -> "filling";
            case "draining"      -> "emptying";
            // direct (JEI 名 == recipe type 名)
            default              -> jeiPath;
        };
    }

    @Nullable
    public static String emit(String recipeId, ResourceLocation jeiUid, IngredientSpec[] slots, SlotKind[] kinds,
                              HeatLevel heat, boolean keepHeldItem) {
        if (!"create".equals(jeiUid.getNamespace())) return null;
        String name = resolveRecipeName(jeiUid.getPath());

        // processingTime 必須グループ (KubeJS schema 上 alwaysWrite)
        if ("crushing".equals(name) || "milling".equals(name) || "cutting".equals(name)) {
            return emitProcessing(recipeId, name, slots, kinds, DEFAULT_PROCESSING_TIME, HeatLevel.NONE, false);
        }
        // 標準 ProcessingRecipe (default schema)
        switch (name) {
            case "pressing":
            case "splashing":
            case "haunting":
            case "filling":
            case "emptying":
            case "sandpaper_polishing":
                return emitProcessing(recipeId, name, slots, kinds, 0, HeatLevel.NONE, false);
            case "mixing":
            case "compacting":
                // heatRequirement: NONE 以外なら出力。
                return emitProcessing(recipeId, name, slots, kinds, 0,
                    heat == null ? HeatLevel.NONE : heat, false);
            case "deploying":
            case "item_application":
                // keepHeldItem true なら出力 (false は default で省略)
                return emitProcessing(recipeId, name, slots, kinds, 0, HeatLevel.NONE, keepHeldItem);
        }
        return null;
    }

    /**
     * 全 ProcessingRecipe 共通 emit。time > 0 の時だけ processingTime を出す。
     * heat != NONE の時だけ heatRequirement を出す (mixing/compacting でのみ意味あり)。
     * keepHeldItem true の時だけ出力 (item_application/deploying でのみ意味あり)。
     */
    @Nullable
    private static String emitProcessing(String recipeId, String typeName,
                                         IngredientSpec[] slots, SlotKind[] kinds, int time,
                                         HeatLevel heat, boolean keepHeldItem) {
        String ins  = collectArray(slots, kinds, true);
        String outs = collectArray(slots, kinds, false);
        if (ins == null || outs == null) return null;
        LinkedHashMap<String, String> f = new LinkedHashMap<>();
        f.put("ingredients", ins);
        f.put("results", outs);
        if (time > 0) f.put("processingTime", String.valueOf(time));
        if (heat != null && heat != HeatLevel.NONE) {
            f.put("heatRequirement", "'" + heat.emitName() + "'");
        }
        if (keepHeldItem) {
            f.put("keepHeldItem", "true");
        }
        return buildEventRecipe(recipeId, typeName, f);
    }

    /** event.recipes.create.<typeName>({...}).id() 形式。 */
    private static String buildEventRecipe(String recipeId, String typeName, LinkedHashMap<String, String> fields) {
        StringBuilder sb = new StringBuilder();
        sb.append("    event.recipes.create.").append(typeName).append("({\n");
        int i = 0, n = fields.size();
        for (Map.Entry<String, String> e : fields.entrySet()) {
            sb.append("        ").append(e.getKey()).append(": ").append(e.getValue());
            if (++i < n) sb.append(",");
            sb.append("\n");
        }
        sb.append("    }).id('").append(recipeId).append("');\n");
        return sb.toString();
    }

    /**
     * Create 流 mixed array: input/output 両方とも item/fluid を 1 配列に混ぜる。
     * 空配列なら null 返してエミット失敗扱い (どちらか少なくとも 1 entry 必須)。
     */
    @Nullable
    private static String collectArray(IngredientSpec[] slots, SlotKind[] kinds, boolean wantInput) {
        List<String> entries = new ArrayList<>();
        for (int i = 0; i < slots.length; i++) {
            if (slots[i].isEmpty()) continue;
            if (isInputKind(kinds[i]) != wantInput) continue;
            String entry = formatEntry(slots[i], kinds[i], wantInput);
            if (entry != null) entries.add(entry);
        }
        if (entries.isEmpty()) return null;
        return "[" + String.join(", ", entries) + "]";
    }

    private static boolean isInputKind(SlotKind k) {
        return k == SlotKind.ITEM_INPUT || k == SlotKind.FLUID_INPUT || k == SlotKind.GAS_INPUT;
    }

    /**
     * 1 entry の JSON 化。kind と input/output で形式分岐:
     *   ITEM_INPUT  : `{ item: 'id' }` or `{ tag: 'id' }`  (vanilla ingredient)
     *   FLUID_INPUT : `{ fluid: 'id', amount: N }` or `{ fluidTag: 'id', amount: N }`
     *   ITEM_OUTPUT : `{ item: 'id', count: N }` (count > 1 時のみ)
     *   FLUID_OUTPUT: `{ fluid: 'id', amount: N }`
     *   GAS_*       : Create は gas を扱わないので null (skip)
     */
    @Nullable
    private static String formatEntry(IngredientSpec spec, SlotKind kind, boolean isInput) {
        IngredientSpec base = spec.unwrap();
        // CREATE_CHANCE が乗ってる output item は chance: X.XX を付与
        String chanceField = "";
        if (!isInput && spec.option() == IngredientSpec.ItemOption.CREATE_CHANCE
            && spec instanceof IngredientSpec.Configured cc && cc.chanceMille() < 1000) {
            double prob = Math.max(0, Math.min(1.0, cc.chanceMille() / 1000.0));
            chanceField = ", chance: " + String.format("%.3f", prob);
        }
        if (kind == SlotKind.ITEM_INPUT || kind == SlotKind.ITEM_OUTPUT) {
            if (base instanceof IngredientSpec.Item it && !it.stack().isEmpty()) {
                ResourceLocation rl = BuiltInRegistries.ITEM.getKey(it.stack().getItem());
                int c = it.stack().getCount();
                if (isInput) {
                    // input: count は ingredient 側では表現しない (Create は重複 entry で count 扱い)
                    return "{ item: '" + rl + "' }";
                } else {
                    String body = c <= 1 ? "item: '" + rl + "'"
                                         : "item: '" + rl + "', count: " + c;
                    return "{ " + body + chanceField + " }";
                }
            }
            if (base instanceof IngredientSpec.Tag tg && tg.tagId() != null) {
                // 出力に tag は通常使わないが、念のため対応
                return "{ tag: '" + tg.tagId() + "'" + chanceField + " }";
            }
            return null;
        }
        if (kind == SlotKind.FLUID_INPUT || kind == SlotKind.FLUID_OUTPUT) {
            if (base instanceof IngredientSpec.Fluid fl && !fl.stack().isEmpty()) {
                FluidStack fs = fl.stack();
                ResourceLocation rl = BuiltInRegistries.FLUID.getKey(fs.getFluid());
                return "{ fluid: '" + rl + "', amount: " + fs.getAmount() + " }";
            }
            if (base instanceof IngredientSpec.FluidTag ft && ft.tagId() != null) {
                // Create 独特: tag キーは "fluidTag" (item の "tag" と区別)
                return "{ fluidTag: '" + ft.tagId() + "', amount: " + ft.amount() + " }";
            }
            return null;
        }
        // GAS_INPUT / GAS_OUTPUT: Create では使わないので skip
        return null;
    }
}
