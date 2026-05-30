package DIV.credit.client.draft.auto;

import DIV.credit.Credit;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.neoforged.fml.loading.FMLPaths;
import org.jetbrains.annotations.Nullable;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/**
 * auto pipeline で「この jeiUid にはこの戦略が通った」 を persist。
 * <p>{@code config/credit-learned.json} 1 file:
 * <pre>
 * { "thermal:pulverizer": "mirror",
 *   "somemod:custom":     "pattern:ingredients+results" }
 * </pre>
 * <p>emit 時:
 * <ol>
 *   <li>cache に entry あれば該当戦略を最優先で試行 (= 1 発成功)</li>
 *   <li>失敗 / 未 cache なら full pipeline (= mirror → pattern 全探索)</li>
 *   <li>新たに通った戦略を put + save</li>
 * </ol>
 * <p>schema 変更や mod update で cache 戦略が無効化される可能性あり →
 * cache 戦略 fail 時は evict + full pipeline 再試行で自己治癒。
 */
public final class LearnedSchemaCache {

    private LearnedSchemaCache() {}

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
    private static final Map<String, String> CACHE = new HashMap<>();
    private static boolean loaded = false;

    public static synchronized void loadIfNeeded() {
        if (loaded) return;
        loaded = true;
        Path p = path();
        if (!Files.exists(p)) return;
        try {
            String txt = Files.readString(p);
            JsonObject obj = JsonParser.parseString(txt).getAsJsonObject();
            for (var e : obj.entrySet()) {
                if (e.getValue().isJsonPrimitive()) {
                    CACHE.put(e.getKey(), e.getValue().getAsString());
                }
            }
            Credit.LOGGER.info("[CraftPattern] LearnedSchemaCache load {} entries", CACHE.size());
        } catch (Exception e) {
            Credit.LOGGER.warn("[C5004] LearnedSchemaCache load failed: {}", e.toString());
        }
    }

    private static synchronized void save() {
        try {
            JsonObject obj = new JsonObject();
            for (var e : CACHE.entrySet()) obj.addProperty(e.getKey(), e.getValue());
            Files.writeString(path(), GSON.toJson(obj));
        } catch (Exception e) {
            Credit.LOGGER.warn("[C5005] LearnedSchemaCache save failed: {}", e.toString());
        }
    }

    private static Path path() {
        return FMLPaths.CONFIGDIR.get().resolve("credit-learned.json");
    }

    /** 既知戦略を返す (= "mirror" / "pattern:<id>")。 未学習なら null。 */
    @Nullable
    public static String get(String jeiUid) {
        loadIfNeeded();
        return CACHE.get(jeiUid);
    }

    /** 成功戦略を記憶。 既値と同じなら save skip。 */
    public static synchronized void put(String jeiUid, String strategy) {
        loadIfNeeded();
        if (strategy == null || strategy.isEmpty()) return;
        String prev = CACHE.put(jeiUid, strategy);
        if (!strategy.equals(prev)) save();
    }

    /** schema 変更等で失効時に evict。 */
    public static synchronized void evict(String jeiUid) {
        loadIfNeeded();
        if (CACHE.remove(jeiUid) != null) save();
    }

    /** strategy id 規約 helper。 */
    public static final String MIRROR_STRATEGY = "mirror";
    public static String patternStrategy(String patternId) { return "pattern:" + patternId; }
    public static boolean isMirror(String strategy)         { return MIRROR_STRATEGY.equals(strategy); }
    public static boolean isPattern(String strategy)        { return strategy != null && strategy.startsWith("pattern:"); }
    public static String patternIdOf(String strategy)       { return strategy.substring("pattern:".length()); }
}
