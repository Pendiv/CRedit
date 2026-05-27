package DIV.credit.client.io;

import DIV.credit.Credit;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.crafting.RecipeSerializer;

import java.util.LinkedHashMap;

/**
 * v3.2.x: event.custom 経由で emit した JSON を {@link RecipeSerializer#fromJson} に通して
 *  schema 一致を verify。 失敗時は warn log のみ (= emit 自体は止めない)。
 * <p>対象: IE / IF / Botania / Thermal 等の event.custom emitter。
 *  GT / Mek / Create は KubeJS schema 経由 (= KubeJS が schema 知ってる) ので対象外。
 * <p>JS literal → JSON 変換が NBT 文字列等の edge case で失敗する場合は silent skip
 *  (= 検証不能扱い、 false positive 出さない)。
 */
public final class EmitSelfTest {

    private EmitSelfTest() {}

    /**
     * fields LinkedHashMap (= 各 value が JS literal の string) を JsonObject 化して fromJson に通す。
     * @param typeId   recipe type id (= "modid:type")
     * @param fields   key = field 名、 value = JS literal (= {item:..} / [..] / 'foo' / 数値等)
     * @param recipeId verify 失敗時 log に出すための id (= "credit:generated/...")
     */
    public static void verifyFields(String typeId, LinkedHashMap<String, String> fields, String recipeId) {
        VerifyResult r = tryVerifyFields(typeId, fields, recipeId);
        switch (r.status) {
            case OK    -> {}
            case SKIP  -> Credit.LOGGER.debug("[CraftPattern] EmitSelfTest skip type={}: {}", typeId, r.message);
            case FAIL  -> Credit.LOGGER.warn("[C403] EmitSelfTest FAILED type={} id={}: {}",
                            typeId, recipeId, r.message);
        }
    }

    /** verify 結果。 OK/SKIP/FAIL。 silent (= log なし)。 auto pipeline で「次の pattern を試す」 判定に使う。 */
    public enum Status { OK, SKIP, FAIL }
    public record VerifyResult(Status status, String message) {
        public static final VerifyResult OK_R = new VerifyResult(Status.OK, "");
        public static VerifyResult skip(String m) { return new VerifyResult(Status.SKIP, m); }
        public static VerifyResult fail(String m) { return new VerifyResult(Status.FAIL, m); }
    }

    /**
     * verifyFields の silent 版。 log 出さず {@link VerifyResult} で結果を返す。
     * SKIP は「検証不能」 (= JS→JSON 変換 / RecipeSerializer 不在)、 auto pipeline では FAIL 同等に扱う。
     */
    public static VerifyResult tryVerifyFields(String typeId, LinkedHashMap<String, String> fields, String recipeId) {
        try {
            JsonObject json = new JsonObject();
            json.addProperty("type", typeId);
            for (var e : fields.entrySet()) {
                JsonElement val = parseJsValue(e.getValue());
                if (val == null) {
                    return VerifyResult.skip("JS→JSON conversion failed: field " + e.getKey());
                }
                json.add(e.getKey(), val);
            }
            ResourceLocation typeRl;
            try { typeRl = new ResourceLocation(typeId); }
            catch (Exception e) { return VerifyResult.fail("invalid type id: " + typeId); }
            RecipeSerializer<?> ser = BuiltInRegistries.RECIPE_SERIALIZER.get(typeRl);
            if (ser == null) return VerifyResult.skip("RecipeSerializer not registered: " + typeId);
            ResourceLocation idRl;
            try { idRl = new ResourceLocation(recipeId); }
            catch (Exception e) { idRl = new ResourceLocation(Credit.MODID, "selftest"); }
            ser.fromJson(idRl, json);
            return VerifyResult.OK_R;
        } catch (Exception e) {
            return VerifyResult.fail(String.valueOf(e.getMessage()));
        }
    }

    /** 1 つの JS literal value を JsonElement に。 失敗時 null。 */
    private static JsonElement parseJsValue(String jsValue) {
        if (jsValue == null) return null;
        String trimmed = jsValue.trim();
        if (trimmed.isEmpty()) return null;
        try {
            return JsonParser.parseString(jsLiteralToJson(trimmed));
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * JS object literal → JSON 文字列。
     * 規則: 単一引用符 → 二重、 unquoted key → quoted。
     * NBT 文字列 (= IF の {@code '{Amount:N,FluidName:"id"}'}) 等、 inner double quote 含む string は
     * 単純 replace で壊れるので呼出側で skip 扱い。
     */
    private static String jsLiteralToJson(String js) {
        String s = js;
        // 文字列 (= 単一引用符で囲まれた) の内部に inner double quote があると変換崩れる
        // → 検出したら null 返却で skip させる
        if (containsAmbiguousString(s)) {
            throw new IllegalArgumentException("inner double quote in single-quoted string");
        }
        s = s.replace('\'', '"');
        // unquoted key: `{key:` / `,key:` / `\n  key:` → `"key":`
        s = s.replaceAll("([{,]\\s*)([a-zA-Z_][a-zA-Z0-9_]*)\\s*:", "$1\"$2\":");
        return s;
    }

    /** {@code '...'} の内部に {@code "} が含まれてれば true (= 変換危険)。 */
    private static boolean containsAmbiguousString(String s) {
        boolean inSingle = false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\'') inSingle = !inSingle;
            else if (c == '"' && inSingle) return true;
        }
        return false;
    }
}
