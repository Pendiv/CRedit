package DIV.credit.client.draft.ie;

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
 * Immersive Engineering の event.custom 形式 emit。
 *
 * IE は KubeJS plugin が無いため event.recipes.immersiveengineering.X が使えず、
 * event.custom({type:'immersiveengineering:X', ...}).id() で raw JSON を投げる。
 *
 * IE の JSON は Mek と違い vanilla Forge ingredient 形式 (`{"item":"..."}` / `{"tag":"..."}`)
 * を直接使う。amount / count が必要な場合は wrapper を被せる:
 *   `{ "base_ingredient": {item/tag}, "count": N }` (item input)
 *   `{ "amount": N, "fluid"/"tag": "..." }` (fluid)
 *
 * 対応 12 schemas: alloy / arc_furnace / blast_furnace / bottling_machine /
 * coke_oven / crusher / fermenter / metal_press / mixer / refinery / sawmill / squeezer
 *
 * 対応外 (EXPLICIT_UNSUPPORTED 行き): blast_furnace_fuel / fertilizer (info pages),
 * blueprint / arc_recycling / cloche (slot configuration が UI 外/複雑)
 */
public final class IEKubeJSEmitter {

    private IEKubeJSEmitter() {}

    @Nullable
    public static String emit(String recipeId, ResourceLocation jeiUid, IngredientSpec[] slots, SlotKind[] kinds) {
        if (!"immersiveengineering".equals(jeiUid.getNamespace())) return null;
        return switch (jeiUid.getPath()) {
            case "alloy"             -> emitAlloy(recipeId, slots, kinds);
            case "arc_furnace"       -> emitArcFurnace(recipeId, slots, kinds);
            case "blast_furnace"     -> emitBlastFurnace(recipeId, slots, kinds);
            case "bottling_machine"  -> emitBottling(recipeId, slots, kinds);
            case "coke_oven"         -> emitCokeOven(recipeId, slots, kinds);
            case "crusher"           -> emitCrusher(recipeId, slots, kinds);
            case "fermenter"         -> emitFermenter(recipeId, slots, kinds);
            case "metal_press"       -> emitMetalPress(recipeId, slots, kinds);
            case "mixer"             -> emitMixer(recipeId, slots, kinds);
            case "refinery"          -> emitRefinery(recipeId, slots, kinds);
            case "sawmill"           -> emitSawmill(recipeId, slots, kinds);
            case "squeezer"          -> emitSqueezer(recipeId, slots, kinds);
            default -> null;
        };
    }

    // ───── 各 emitter ─────

    /** alloy: 2 item input + 1 item output。time デフォルト 100。 */
    @Nullable
    private static String emitAlloy(String recipeId, IngredientSpec[] slots, SlotKind[] kinds) {
        List<String> ins = collectAll(slots, kinds, SlotKind.ITEM_INPUT, IEKubeJSEmitter::itemIngredient);
        String op = findFirst(slots, kinds, SlotKind.ITEM_OUTPUT, IEKubeJSEmitter::itemOutputWithCount);
        if (ins.size() < 2 || op == null) return null;
        LinkedHashMap<String, String> f = new LinkedHashMap<>();
        f.put("input0", ins.get(0));
        f.put("input1", ins.get(1));
        f.put("result", op);
        f.put("time", "100");
        return buildEventCustom(recipeId, "immersiveengineering:alloy", f);
    }

    /** arc_furnace: input + additives[] + results[main] + energy + time。 */
    @Nullable
    private static String emitArcFurnace(String recipeId, IngredientSpec[] slots, SlotKind[] kinds) {
        List<String> ins = collectAll(slots, kinds, SlotKind.ITEM_INPUT, IEKubeJSEmitter::itemIngredient);
        String op = findFirst(slots, kinds, SlotKind.ITEM_OUTPUT, IEKubeJSEmitter::itemOutputWithCount);
        if (ins.isEmpty() || op == null) return null;
        // 1 番目 = main input、それ以降 = additives
        String mainInput = ins.get(0);
        StringBuilder add = new StringBuilder("[");
        for (int i = 1; i < ins.size(); i++) {
            if (i > 1) add.append(", ");
            add.append(ins.get(i));
        }
        add.append("]");
        LinkedHashMap<String, String> f = new LinkedHashMap<>();
        f.put("input", mainInput);
        f.put("additives", add.toString());
        f.put("results", "[" + op + "]");
        f.put("energy", "51200");
        f.put("time", "100");
        return buildEventCustom(recipeId, "immersiveengineering:arc_furnace", f);
    }

    /** blast_furnace: input + result + slag + time。slag は output が 2 個目以降にあれば使う。 */
    @Nullable
    private static String emitBlastFurnace(String recipeId, IngredientSpec[] slots, SlotKind[] kinds) {
        String in = findFirst(slots, kinds, SlotKind.ITEM_INPUT, IEKubeJSEmitter::itemIngredient);
        List<String> outs = collectAll(slots, kinds, SlotKind.ITEM_OUTPUT, IEKubeJSEmitter::itemOutputSimple);
        if (in == null || outs.isEmpty()) return null;
        LinkedHashMap<String, String> f = new LinkedHashMap<>();
        f.put("input", in);
        f.put("result", outs.get(0));
        if (outs.size() >= 2) {
            f.put("slag", outs.get(1));
        } else {
            // slag が無いと recipe が拒否されることが多いので default tag をフォールバック
            f.put("slag", "{ tag: 'forge:slag' }");
        }
        f.put("time", "1200");
        return buildEventCustom(recipeId, "immersiveengineering:blast_furnace", f);
    }

    /** bottling_machine: inputs[] + fluid + results[]。 */
    @Nullable
    private static String emitBottling(String recipeId, IngredientSpec[] slots, SlotKind[] kinds) {
        List<String> ins = collectAll(slots, kinds, SlotKind.ITEM_INPUT, IEKubeJSEmitter::itemIngredient);
        String fluid = findFirst(slots, kinds, SlotKind.FLUID_INPUT, IEKubeJSEmitter::fluidJson);
        List<String> ops = collectAll(slots, kinds, SlotKind.ITEM_OUTPUT, IEKubeJSEmitter::itemOutputSimple);
        if (ins.isEmpty() || fluid == null || ops.isEmpty()) return null;
        LinkedHashMap<String, String> f = new LinkedHashMap<>();
        f.put("inputs", "[" + String.join(", ", ins) + "]");
        f.put("fluid", fluid);
        f.put("results", "[" + String.join(", ", ops) + "]");
        return buildEventCustom(recipeId, "immersiveengineering:bottling_machine", f);
    }

    /** coke_oven: input + result + creosote (number) + time。 */
    @Nullable
    private static String emitCokeOven(String recipeId, IngredientSpec[] slots, SlotKind[] kinds) {
        String in = findFirst(slots, kinds, SlotKind.ITEM_INPUT, IEKubeJSEmitter::itemIngredient);
        String op = findFirst(slots, kinds, SlotKind.ITEM_OUTPUT, IEKubeJSEmitter::itemOutputSimple);
        if (in == null || op == null) return null;
        LinkedHashMap<String, String> f = new LinkedHashMap<>();
        f.put("input", in);
        f.put("result", op);
        // creosote は fluid output だが量だけ。default 250mB。
        f.put("creosote", "250");
        f.put("time", "900");
        return buildEventCustom(recipeId, "immersiveengineering:coke_oven", f);
    }

    /** crusher: input + result + secondaries[] + energy。 */
    @Nullable
    private static String emitCrusher(String recipeId, IngredientSpec[] slots, SlotKind[] kinds) {
        String in = findFirst(slots, kinds, SlotKind.ITEM_INPUT, IEKubeJSEmitter::itemIngredient);
        List<String> outs = collectAll(slots, kinds, SlotKind.ITEM_OUTPUT, IEKubeJSEmitter::itemOutputSimple);
        if (in == null || outs.isEmpty()) return null;
        LinkedHashMap<String, String> f = new LinkedHashMap<>();
        f.put("energy", "3200");
        f.put("input", in);
        f.put("result", outs.get(0));
        f.put("secondaries", "[]"); // secondaries (確率 output) は UI 編集対象外。空で出す
        return buildEventCustom(recipeId, "immersiveengineering:crusher", f);
    }

    /** fermenter: input + fluid (output) + 任意 result (itemOutput) + energy。 */
    @Nullable
    private static String emitFermenter(String recipeId, IngredientSpec[] slots, SlotKind[] kinds) {
        String in = findFirst(slots, kinds, SlotKind.ITEM_INPUT, IEKubeJSEmitter::itemIngredient);
        String fluid = findFirst(slots, kinds, SlotKind.FLUID_OUTPUT, IEKubeJSEmitter::fluidJson);
        if (in == null || fluid == null) return null;
        // ITEM_OUTPUT は optional。serializer 側のフィールド名は "result"。
        String itemOut = findFirst(slots, kinds, SlotKind.ITEM_OUTPUT, IEKubeJSEmitter::itemOutputSimple);
        LinkedHashMap<String, String> f = new LinkedHashMap<>();
        f.put("energy", "6400");
        f.put("input", in);
        f.put("fluid", fluid);
        if (itemOut != null) f.put("result", itemOut);
        return buildEventCustom(recipeId, "immersiveengineering:fermenter", f);
    }

    /** metal_press: input (count 可) + mold (item id 文字列) + result + energy。 */
    @Nullable
    private static String emitMetalPress(String recipeId, IngredientSpec[] slots, SlotKind[] kinds) {
        List<String> ins = collectAll(slots, kinds, SlotKind.ITEM_INPUT, IEKubeJSEmitter::itemIngredient);
        String op = findFirst(slots, kinds, SlotKind.ITEM_OUTPUT, IEKubeJSEmitter::itemOutputSimple);
        if (ins.size() < 2 || op == null) return null;
        // ins[0] = main input、ins[1] = mold
        String mold = extractItemIdFromIngredient(slots, kinds);
        if (mold == null) return null;
        LinkedHashMap<String, String> f = new LinkedHashMap<>();
        f.put("energy", "3200");
        f.put("input", ins.get(0));
        f.put("mold", "'" + mold + "'");
        f.put("result", op);
        return buildEventCustom(recipeId, "immersiveengineering:metal_press", f);
    }

    /** mixer: inputs[] (item) + fluid (input) + result (fluid) + energy。 */
    @Nullable
    private static String emitMixer(String recipeId, IngredientSpec[] slots, SlotKind[] kinds) {
        List<String> ins = collectAll(slots, kinds, SlotKind.ITEM_INPUT, IEKubeJSEmitter::itemIngredient);
        String fluidIn = findFirst(slots, kinds, SlotKind.FLUID_INPUT, IEKubeJSEmitter::fluidJson);
        String fluidOut = findFirst(slots, kinds, SlotKind.FLUID_OUTPUT, IEKubeJSEmitter::fluidJson);
        if (ins.isEmpty() || fluidIn == null || fluidOut == null) return null;
        LinkedHashMap<String, String> f = new LinkedHashMap<>();
        f.put("energy", "3200");
        f.put("fluid", fluidIn);
        f.put("inputs", "[" + String.join(", ", ins) + "]");
        f.put("result", fluidOut);
        return buildEventCustom(recipeId, "immersiveengineering:mixer", f);
    }

    /** refinery: input0 + input1 (fluids) + 任意 catalyst (item) + result (fluid) + energy。 */
    @Nullable
    private static String emitRefinery(String recipeId, IngredientSpec[] slots, SlotKind[] kinds) {
        List<String> fluidIns = collectAll(slots, kinds, SlotKind.FLUID_INPUT, IEKubeJSEmitter::fluidJson);
        String catalyst = findFirst(slots, kinds, SlotKind.ITEM_INPUT, IEKubeJSEmitter::itemIngredient);
        String op = findFirst(slots, kinds, SlotKind.FLUID_OUTPUT, IEKubeJSEmitter::fluidJson);
        if (fluidIns.size() < 2 || op == null) return null;
        LinkedHashMap<String, String> f = new LinkedHashMap<>();
        f.put("energy", "120");
        f.put("input0", fluidIns.get(0));
        f.put("input1", fluidIns.get(1));
        if (catalyst != null) f.put("catalyst", catalyst);
        f.put("result", op);
        return buildEventCustom(recipeId, "immersiveengineering:refinery", f);
    }

    /** sawmill: input + result + secondaries[] + energy。 */
    @Nullable
    private static String emitSawmill(String recipeId, IngredientSpec[] slots, SlotKind[] kinds) {
        String in = findFirst(slots, kinds, SlotKind.ITEM_INPUT, IEKubeJSEmitter::itemIngredient);
        List<String> outs = collectAll(slots, kinds, SlotKind.ITEM_OUTPUT, IEKubeJSEmitter::itemOutputSimple);
        if (in == null || outs.isEmpty()) return null;
        // sawmill: 視覚 slot は stripped → result → secondaries。
        // ここでは 1 番目 output のみ result として扱い、stripped/secondaries は省略。
        LinkedHashMap<String, String> f = new LinkedHashMap<>();
        f.put("energy", "800");
        f.put("input", in);
        f.put("result", outs.get(0));
        f.put("secondaries", "[]");
        return buildEventCustom(recipeId, "immersiveengineering:sawmill", f);
    }

    /** squeezer: input + fluid (output) + 任意 result (itemOutput) + energy。 */
    @Nullable
    private static String emitSqueezer(String recipeId, IngredientSpec[] slots, SlotKind[] kinds) {
        String in = findFirst(slots, kinds, SlotKind.ITEM_INPUT, IEKubeJSEmitter::itemIngredient);
        String fluid = findFirst(slots, kinds, SlotKind.FLUID_OUTPUT, IEKubeJSEmitter::fluidJson);
        if (in == null || fluid == null) return null;
        // ITEM_OUTPUT は optional。serializer 側のフィールド名は "result"。
        String itemOut = findFirst(slots, kinds, SlotKind.ITEM_OUTPUT, IEKubeJSEmitter::itemOutputSimple);
        LinkedHashMap<String, String> f = new LinkedHashMap<>();
        f.put("energy", "6400");
        f.put("input", in);
        f.put("fluid", fluid);
        if (itemOut != null) f.put("result", itemOut);
        return buildEventCustom(recipeId, "immersiveengineering:squeezer", f);
    }

    // ───── builder ─────

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

    // ───── JSON helpers ─────

    /**
     * IE 入力 ingredient: 単数なら `{"item":"..."}` or `{"tag":"..."}`、
     * count > 1 なら `{"base_ingredient": {...}, "count": N}` で wrap。
     */
    @Nullable
    private static String itemIngredient(IngredientSpec s) {
        if (s == null || s.isEmpty()) return null;
        IngredientSpec base = s.unwrap();
        if (base instanceof IngredientSpec.Item it && !it.stack().isEmpty()) {
            ResourceLocation rl = BuiltInRegistries.ITEM.getKey(it.stack().getItem());
            int c = it.stack().getCount();
            String inner = "{ item: '" + rl + "' }";
            return c <= 1 ? inner : "{ base_ingredient: " + inner + ", count: " + c + " }";
        }
        if (base instanceof IngredientSpec.Tag tg && tg.tagId() != null) {
            String inner = "{ tag: '" + tg.tagId() + "' }";
            int c = Math.max(1, tg.count());
            return c <= 1 ? inner : "{ base_ingredient: " + inner + ", count: " + c + " }";
        }
        return null;
    }

    /** Item output 簡易版: `{"item":"..."}`。count > 1 なら `{"item":..., "count": N}`。 */
    @Nullable
    private static String itemOutputSimple(IngredientSpec s) {
        if (s == null || s.isEmpty()) return null;
        IngredientSpec base = s.unwrap();
        if (base instanceof IngredientSpec.Item it && !it.stack().isEmpty()) {
            ResourceLocation rl = BuiltInRegistries.ITEM.getKey(it.stack().getItem());
            int c = it.stack().getCount();
            return c <= 1 ? "{ item: '" + rl + "' }"
                          : "{ item: '" + rl + "', count: " + c + " }";
        }
        // tag based output (blast_furnace の slag 等で使う): { tag: '...' } or wrapped
        if (base instanceof IngredientSpec.Tag tg && tg.tagId() != null) {
            int c = Math.max(1, tg.count());
            String inner = "{ tag: '" + tg.tagId() + "' }";
            return c <= 1 ? inner : "{ base_ingredient: " + inner + ", count: " + c + " }";
        }
        return null;
    }

    /** Item output (count 必須形式): `{"base_ingredient":..., "count": N}` 強制 wrap。alloy/arc_furnace 等で使用。 */
    @Nullable
    private static String itemOutputWithCount(IngredientSpec s) {
        if (s == null || s.isEmpty()) return null;
        IngredientSpec base = s.unwrap();
        int c = 1;
        String inner = null;
        if (base instanceof IngredientSpec.Item it && !it.stack().isEmpty()) {
            ResourceLocation rl = BuiltInRegistries.ITEM.getKey(it.stack().getItem());
            c = it.stack().getCount();
            inner = "{ item: '" + rl + "' }";
        } else if (base instanceof IngredientSpec.Tag tg && tg.tagId() != null) {
            c = Math.max(1, tg.count());
            inner = "{ tag: '" + tg.tagId() + "' }";
        }
        if (inner == null) return null;
        return "{ base_ingredient: " + inner + ", count: " + c + " }";
    }

    /** Fluid (input/output 共通): `{"amount": N, "fluid": "..."}` or `{"amount": N, "tag": "..."}`。 */
    @Nullable
    private static String fluidJson(IngredientSpec s) {
        if (s == null || s.isEmpty()) return null;
        IngredientSpec base = s.unwrap();
        if (base instanceof IngredientSpec.Fluid fl && !fl.stack().isEmpty()) {
            FluidStack fs = fl.stack();
            ResourceLocation rl = BuiltInRegistries.FLUID.getKey(fs.getFluid());
            return "{ amount: " + fs.getAmount() + ", fluid: '" + rl + "' }";
        }
        if (base instanceof IngredientSpec.FluidTag ft && ft.tagId() != null) {
            return "{ amount: " + ft.amount() + ", tag: '" + ft.tagId() + "' }";
        }
        return null;
    }

    /** metal_press の mold 用: 2 番目の ITEM_INPUT slot から item id を取り出す。tag は不可。 */
    @Nullable
    private static String extractItemIdFromIngredient(IngredientSpec[] slots, SlotKind[] kinds) {
        int found = 0;
        for (int i = 0; i < slots.length; i++) {
            if (kinds[i] == SlotKind.ITEM_INPUT && !slots[i].isEmpty()) {
                found++;
                if (found == 2) {
                    IngredientSpec base = slots[i].unwrap();
                    if (base instanceof IngredientSpec.Item it && !it.stack().isEmpty()) {
                        return BuiltInRegistries.ITEM.getKey(it.stack().getItem()).toString();
                    }
                    return null;
                }
            }
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
