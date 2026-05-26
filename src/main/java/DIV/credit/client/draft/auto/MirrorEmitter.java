package DIV.credit.client.draft.auto;

import DIV.credit.Credit;
import DIV.credit.client.draft.IngredientSpec;
import DIV.credit.client.draft.RecipeDraft.SlotKind;
import DIV.credit.client.io.EmitSelfTest;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.fluids.FluidStack;
import org.jetbrains.annotations.Nullable;

import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * 案 D 「JSON mirror」: sample 1 個の JSON template を雛形にして、 user 編集 slot を substitute。
 * <p>動作:
 * <ol>
 *   <li>{@link CodecExtractor} で sample から template JSON 取得</li>
 *   <li>template から item/tag/fluid id を leaf 検出</li>
 *   <li>各 leaf id を sample slot id と照合 → slot index 紐付け</li>
 *   <li>user edit があれば該当 leaf を書き換え、 無編集 slot は template 値維持</li>
 *   <li>type field を除いた残りで {@link EmitSelfTest#tryVerifyFields} verify、 OK なら event.custom 生成</li>
 * </ol>
 * <p>mana / energy / time 等の数値 field は template そのまま (= user 編集 UI 未対応)。
 */
public final class MirrorEmitter {

    private MirrorEmitter() {}

    public record Result(String jsCode) {}

    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();

    /**
     * @param sampleTemplate {@link CodecExtractor} 出力 JSON (= 元 recipe の dump)
     * @param sampleIds      sample probe 時に slot ごと記録した base id (= item: minecraft:iron_ingot 等)。
     *                       index は slots[] と一致、 null = 不明 slot
     */
    @Nullable
    public static Result tryEmit(String recipeId, ResourceLocation jeiUid,
                                 IngredientSpec[] slots, SlotKind[] kinds,
                                 JsonElement sampleTemplate, String[] sampleIds) {
        if (sampleTemplate == null || !sampleTemplate.isJsonObject()) return null;
        String typeId = jeiUid.toString();

        // 深いコピー (= original template を壊さない)
        JsonObject mutated = sampleTemplate.deepCopy().getAsJsonObject();
        // type field を確実に上書き / 補完
        mutated.addProperty("type", typeId);

        IdentityHashMap<JsonObject, Boolean> used = new IdentityHashMap<>();
        boolean anyChange = false;

        for (int i = 0; i < slots.length; i++) {
            IngredientSpec spec = slots[i];
            if (spec == null || spec.isEmpty()) continue;
            if (sampleIds[i] == null) continue;
            // template から sample id にマッチする leaf object を 1 つ探す (= used 済はスキップ)
            JsonObject target = findIdLeaf(mutated, sampleIds[i], used);
            if (target == null) continue;
            // 該当 leaf を user spec で書き換え
            if (substituteLeaf(target, spec, kinds[i])) {
                used.put(target, Boolean.TRUE);
                anyChange = true;
            }
        }
        if (!anyChange) return null;

        // type は別途 emit 時に頭出しするので fields からは除外
        LinkedHashMap<String, String> fields = new LinkedHashMap<>();
        for (var e : mutated.entrySet()) {
            if ("type".equals(e.getKey())) continue;
            fields.put(e.getKey(), GSON.toJson(e.getValue()));
        }
        EmitSelfTest.VerifyResult vr = EmitSelfTest.tryVerifyFields(typeId, fields, recipeId);
        if (vr.status() != EmitSelfTest.Status.OK) {
            Credit.LOGGER.debug("[CraftPattern] MirrorEmitter verify {} type={} status={} msg={}",
                jeiUid, typeId, vr.status(), vr.message());
            return null;
        }
        Credit.LOGGER.info("[CraftPattern] Mirror OK type={}", typeId);
        return new Result(buildEventCustom(recipeId, typeId, fields));
    }

    /**
     * template を再帰探索し、 leaf JsonObject で id field (= item / tag / fluid) の値が target に一致するものを返す。
     * used 済みのものは skip (= 同 id が複数現れる時の二重置換防止)。
     */
    @Nullable
    private static JsonObject findIdLeaf(JsonElement node, String targetId,
                                         Map<JsonObject, Boolean> used) {
        if (node == null) return null;
        if (node.isJsonObject()) {
            JsonObject obj = node.getAsJsonObject();
            if (!used.containsKey(obj)) {
                String id = extractLeafId(obj);
                if (targetId.equals(id)) return obj;
            }
            for (var e : obj.entrySet()) {
                JsonObject r = findIdLeaf(e.getValue(), targetId, used);
                if (r != null) return r;
            }
        } else if (node.isJsonArray()) {
            for (JsonElement el : node.getAsJsonArray()) {
                JsonObject r = findIdLeaf(el, targetId, used);
                if (r != null) return r;
            }
        }
        return null;
    }

    /** leaf object から id 文字列を抽出 (= "item", "fluid", "tag" key の string value)。 無ければ null。 */
    @Nullable
    private static String extractLeafId(JsonObject obj) {
        for (String key : new String[]{"item", "fluid", "tag"}) {
            if (obj.has(key) && obj.get(key).isJsonPrimitive()) {
                return obj.getAsJsonPrimitive(key).getAsString();
            }
        }
        return null;
    }

    /**
     * target leaf を user spec の値で書き換え。 count/amount は user 編集を優先、 spec 不一致 (= 例えば
     * leaf が item で user が fluid) なら false 返却。
     */
    private static boolean substituteLeaf(JsonObject leaf, IngredientSpec spec, SlotKind kind) {
        IngredientSpec base = spec.unwrap();
        if (base instanceof IngredientSpec.Item it && !it.stack().isEmpty()) {
            if (!leaf.has("item") && !leaf.has("tag")) return false;
            ResourceLocation rl = BuiltInRegistries.ITEM.getKey(it.stack().getItem());
            // tag を item に置換可: tag 削除、 item 設定
            leaf.remove("tag");
            leaf.addProperty("item", rl.toString());
            int c = it.stack().getCount();
            if (c > 1) leaf.addProperty("count", c);
            else       leaf.remove("count");
            return true;
        }
        if (base instanceof IngredientSpec.Tag tg && tg.tagId() != null) {
            if (!leaf.has("item") && !leaf.has("tag")) return false;
            leaf.remove("item");
            leaf.addProperty("tag", String.valueOf(tg.tagId()));
            int c = Math.max(1, tg.count());
            if (c > 1) leaf.addProperty("count", c);
            else       leaf.remove("count");
            return true;
        }
        if (base instanceof IngredientSpec.Fluid fl && !fl.stack().isEmpty()) {
            if (!leaf.has("fluid")) return false;
            FluidStack fs = fl.stack();
            ResourceLocation rl = BuiltInRegistries.FLUID.getKey(fs.getFluid());
            leaf.addProperty("fluid", rl.toString());
            leaf.addProperty("amount", fs.getAmount());
            return true;
        }
        if (base instanceof IngredientSpec.FluidTag ft && ft.tagId() != null) {
            if (!leaf.has("fluid") && !leaf.has("tag")) return false;
            leaf.remove("fluid");
            leaf.addProperty("tag", String.valueOf(ft.tagId()));
            leaf.addProperty("amount", ft.amount());
            return true;
        }
        return false;
    }

    private static String buildEventCustom(String recipeId, String typeId,
                                           LinkedHashMap<String, String> fields) {
        StringBuilder sb = new StringBuilder();
        sb.append("    // mirror emit (= MirrorEmitter, template-based)\n");
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

    /** unused suppress 用 dummy (= JsonArray/JsonPrimitive import が IDE で「使ってない」 警告出さないため)。 */
    @SuppressWarnings("unused")
    private static void __keep_imports(JsonArray a, JsonPrimitive p, Set<?> s) {}
}
