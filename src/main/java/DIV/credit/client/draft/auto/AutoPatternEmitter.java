package DIV.credit.client.draft.auto;

import DIV.credit.Credit;
import DIV.credit.client.draft.IngredientSpec;
import DIV.credit.client.draft.RecipeDraft.SlotKind;
import DIV.credit.client.io.EmitSelfTest;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.fluids.FluidStack;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;

/**
 * 案 E 「multi-pattern emit dispatcher」。
 * 未知 mod の recipe 編集時、 N 個の代表的 field 形式を順試行し
 * {@link EmitSelfTest#tryVerifyFields} で OK が返った最初の形式を採用する。
 * <p>対象は item slot のみ (= input/output)。 fluid 含むレシピは false negative になりやすいので
 * 別 pattern 群を後段で試す ({@link #emitWithFluids})。
 * <p>採用形式は呼出側 ({@link LearnedSchemaCache}) で persist することで次回以降の試行を skip 可能。
 */
public final class AutoPatternEmitter {

    private AutoPatternEmitter() {}

    /** 試行結果。 採用された pattern と最終 JS code を保持。 fail 時 null。 */
    public record Result(String jsCode, String patternId) {}

    /** 試行する field shape 1 個。 */
    private record FieldShape(String id, String inputField, String outputField,
                              boolean inputArray, boolean outputArray) {}

    /** item 専用 shape (= fluid 無し時の試行リスト)。 順序は採用優先度 = 命中率の経験則。 */
    private static final List<FieldShape> ITEM_SHAPES = List.of(
        // 最頻形 (Thermal / 多くの forge mod)
        new FieldShape("ingredients+results", "ingredients", "results", true, true),
        new FieldShape("ingredient+result",   "ingredient",  "result",  false, false),
        new FieldShape("ingredient+results",  "ingredient",  "results", false, true),
        new FieldShape("ingredients+result",  "ingredients", "result",  true, false),
        // alt 命名 (input/output)
        new FieldShape("inputs+outputs",      "inputs",      "outputs", true, true),
        new FieldShape("input+output",        "input",       "output",  false, false),
        new FieldShape("input+result",        "input",       "result",  false, false),
        new FieldShape("ingredient+output",   "ingredient",  "output",  false, false)
    );

    /**
     * 案 E メイン entry。 全 shape を試行、 最初に verify pass したものを採用。
     * @return 採用 code + pattern id、 失敗時 null
     */
    @Nullable
    public static Result tryEmit(String recipeId, ResourceLocation jeiUid,
                                 IngredientSpec[] slots, SlotKind[] kinds) {
        String typeId = jeiUid.toString();

        // 1) item 入出力を JS literal 化
        List<String> itemIns  = collectItemIns(slots, kinds);
        List<String> itemOuts = collectItemOuts(slots, kinds);
        List<String> fluidIns  = collectFluidIns(slots, kinds);
        List<String> fluidOuts = collectFluidOuts(slots, kinds);

        // 出力が無いレシピは事実上構成不能 → skip (= fall back to skeleton)
        if (itemIns.isEmpty() && fluidIns.isEmpty()) return null;
        if (itemOuts.isEmpty() && fluidOuts.isEmpty()) return null;

        // 2) fluid を含むなら fluid-shape 経由優先 (= ITEM_SHAPES では fluid 落ちる)
        if (!fluidIns.isEmpty() || !fluidOuts.isEmpty()) {
            Result fr = emitWithFluids(typeId, recipeId, itemIns, itemOuts, fluidIns, fluidOuts);
            if (fr != null) return fr;
        }

        // 3) item 専用 shape 試行
        if (!itemIns.isEmpty() && !itemOuts.isEmpty()) {
            for (FieldShape shape : ITEM_SHAPES) {
                Result r = tryShape(typeId, recipeId, shape, itemIns, itemOuts);
                if (r != null) return r;
            }
        }
        return null;
    }

    private static Result tryShape(String typeId, String recipeId, FieldShape shape,
                                   List<String> ins, List<String> outs) {
        LinkedHashMap<String, String> fields = new LinkedHashMap<>();
        fields.put(shape.inputField,  shape.inputArray  ? toArray(ins)  : ins.get(0));
        fields.put(shape.outputField, shape.outputArray ? toArray(outs) : outs.get(0));
        EmitSelfTest.VerifyResult vr = EmitSelfTest.tryVerifyFields(typeId, fields, recipeId);
        if (vr.status() != EmitSelfTest.Status.OK) return null;
        Credit.LOGGER.info("[CraftPattern] AutoPattern OK type={} pattern={}", typeId, shape.id());
        return new Result(buildEventCustom(recipeId, typeId, fields), shape.id());
    }

    /**
     * fluid 混在時の shape 試行。 item 用 field + fluid 用別 field の組合せ。
     * fluid を持つ mod recipe (= Thermal 系 brewer, refinery) で命中。
     */
    @Nullable
    private static Result emitWithFluids(String typeId, String recipeId,
                                        List<String> itemIns, List<String> itemOuts,
                                        List<String> fluidIns, List<String> fluidOuts) {
        // 単一 ingredient / result 系で items + fluids を同一 array に詰める shape (= Thermal pattern)
        if ((!itemIns.isEmpty() || !fluidIns.isEmpty()) && (!itemOuts.isEmpty() || !fluidOuts.isEmpty())) {
            List<String> allIns = new ArrayList<>(itemIns); allIns.addAll(fluidIns);
            List<String> allOuts = new ArrayList<>(itemOuts); allOuts.addAll(fluidOuts);
            for (FieldShape shape : ITEM_SHAPES) {
                Result r = tryShape(typeId, recipeId, shape, allIns, allOuts);
                if (r != null) return r;
            }
        }
        return null;
    }

    // ─── item / fluid → JS literal ───

    private static List<String> collectItemIns(IngredientSpec[] slots, SlotKind[] kinds) {
        List<String> out = new ArrayList<>();
        for (int i = 0; i < slots.length; i++) {
            if (kinds[i] != SlotKind.ITEM_INPUT) continue;
            String s = itemInJson(slots[i]);
            if (s != null) out.add(s);
        }
        return out;
    }

    private static List<String> collectItemOuts(IngredientSpec[] slots, SlotKind[] kinds) {
        List<String> out = new ArrayList<>();
        for (int i = 0; i < slots.length; i++) {
            if (kinds[i] != SlotKind.ITEM_OUTPUT) continue;
            String s = itemOutJson(slots[i]);
            if (s != null) out.add(s);
        }
        return out;
    }

    private static List<String> collectFluidIns(IngredientSpec[] slots, SlotKind[] kinds) {
        List<String> out = new ArrayList<>();
        for (int i = 0; i < slots.length; i++) {
            if (kinds[i] != SlotKind.FLUID_INPUT) continue;
            String s = fluidJson(slots[i]);
            if (s != null) out.add(s);
        }
        return out;
    }

    private static List<String> collectFluidOuts(IngredientSpec[] slots, SlotKind[] kinds) {
        List<String> out = new ArrayList<>();
        for (int i = 0; i < slots.length; i++) {
            if (kinds[i] != SlotKind.FLUID_OUTPUT) continue;
            String s = fluidJson(slots[i]);
            if (s != null) out.add(s);
        }
        return out;
    }

    @Nullable
    private static String itemInJson(IngredientSpec s) {
        if (s == null || s.isEmpty()) return null;
        IngredientSpec base = s.unwrap();
        if (base instanceof IngredientSpec.Item it && !it.stack().isEmpty()) {
            ResourceLocation rl = BuiltInRegistries.ITEM.getKey(it.stack().getItem());
            int c = it.stack().getCount();
            return c <= 1 ? "{ item: '" + rl + "' }"
                          : "{ item: '" + rl + "', count: " + c + " }";
        }
        if (base instanceof IngredientSpec.Tag tg && tg.tagId() != null) {
            int c = Math.max(1, tg.count());
            return c <= 1 ? "{ tag: '" + tg.tagId() + "' }"
                          : "{ tag: '" + tg.tagId() + "', count: " + c + " }";
        }
        return null;
    }

    @Nullable
    private static String itemOutJson(IngredientSpec s) {
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

    private static String toArray(List<String> xs) {
        return "[" + String.join(", ", xs) + "]";
    }

    private static String buildEventCustom(String recipeId, String typeId,
                                           LinkedHashMap<String, String> fields) {
        StringBuilder sb = new StringBuilder();
        sb.append("    // auto-pattern emit (= AutoPatternEmitter)\n");
        sb.append("    event.custom({\n");
        sb.append("        type: '").append(typeId).append("',\n");
        int i = 0, n = fields.size();
        for (var e : fields.entrySet()) {
            sb.append("        ").append(e.getKey()).append(": ").append(e.getValue());
            if (++i < n) sb.append(",");
            sb.append("\n");
        }
        sb.append("    }).id('").append(recipeId).append("');\n");
        return sb.toString();
    }
}
