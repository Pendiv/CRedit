package DIV.credit.client.importer;

import DIV.credit.Credit;
import DIV.credit.CreditConfig;
import DIV.credit.client.io.ScriptWriter.OperationKind;
import net.minecraft.client.Minecraft;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * v2.1.2: import 候補 vs 既存 kubejs/server_scripts/generated/&lt;modid&gt;/*.js の重複検出。
 * <p>判定キー:
 * <ul>
 *   <li>(a) 同 recipeId (event の .id('...') が完全一致)</li>
 *   <li>(b) 同 fingerprint = kind + modid + recipeType + typeId + outputId + sorted(ingredients ids)</li>
 * </ul>
 * いずれか 1 つでもヒットすれば conflict として返す。
 */
public final class ConflictDetector {

    /** 引数 (..., ...) 内の 'ns:path' / "ns:path" 文字列。 */
    private static final Pattern QUOTED_ID_PATTERN =
        Pattern.compile("['\"]([a-z0-9_.-]+:[a-z0-9_./-]+)['\"]");

    /** Item.of('ns:path', count, ...) の count 引数。Tag 文字列 '#...' は無視。 */
    private static final Pattern ITEM_OF_COUNT_PATTERN =
        Pattern.compile("Item\\.of\\(\\s*['\"][a-z0-9_.-]+:[a-z0-9_./-]+['\"]\\s*,\\s*(\\d+)");

    private ConflictDetector() {}

    /** 1 件の import 候補と、衝突した既存 recipe 群。 */
    public record Conflict(ImportedRecipe imported, List<ImportedRecipe> existing) {}

    /**
     * imports を順に既存と照合し、conflict があるものだけ返す。
     * @return 順序保持マップ (LinkedHashMap)。conflict 無し import は含まれない。
     */
    public static Map<ImportedRecipe, List<ImportedRecipe>> detect(List<ImportedRecipe> imports) {
        List<ImportedRecipe> existing = loadExistingGenerated();
        Map<String, ImportedRecipe> byId = new HashMap<>();
        Map<String, List<ImportedRecipe>> byFp = new HashMap<>();
        for (ImportedRecipe r : existing) {
            byId.putIfAbsent(r.recipeId(), r);
            byFp.computeIfAbsent(fingerprint(r), k -> new ArrayList<>()).add(r);
        }

        Map<ImportedRecipe, List<ImportedRecipe>> conflicts = new LinkedHashMap<>();
        for (ImportedRecipe imp : imports) {
            List<ImportedRecipe> hits = new ArrayList<>();
            ImportedRecipe sameId = byId.get(imp.recipeId());
            if (sameId != null) hits.add(sameId);
            List<ImportedRecipe> sameFp = byFp.getOrDefault(fingerprint(imp), Collections.emptyList());
            for (ImportedRecipe e : sameFp) {
                if (sameId == null || !e.recipeId().equals(sameId.recipeId())) {
                    hits.add(e);
                }
            }
            if (!hits.isEmpty()) conflicts.put(imp, hits);
        }
        return conflicts;
    }

    /** 既存の generated 配下を ImportParser で読み直して List<ImportedRecipe> 化。 */
    public static List<ImportedRecipe> loadExistingGenerated() {
        Path root = resolveGeneratedRoot();
        if (root == null || !Files.isDirectory(root)) return List.of();
        List<ImportedRecipe> all = new ArrayList<>();
        try (Stream<Path> s = Files.walk(root)) {
            s.filter(Files::isRegularFile)
             .filter(p -> p.getFileName().toString().toLowerCase().endsWith(".js"))
             .forEach(p -> all.addAll(ImportParser.parseFile(p)));
        } catch (IOException e) {
            Credit.LOGGER.error("[C6001] existing scan failed", e);
        }
        return all;
    }

    /** ScriptWriter と同じロジックで dump_root/generated を求める。設定未読込なら null。 */
    @Nullable
    public static Path resolveGeneratedRoot() {
        try {
            String root = CreditConfig.DUMP_ROOT.get();
            if (root == null || root.isBlank()) root = "kubejs/server_scripts";
            if (root.endsWith("/") || root.endsWith("\\")) root = root.substring(0, root.length() - 1);
            return Minecraft.getInstance().gameDirectory.toPath().resolve(root + "/generated");
        } catch (Exception e) {
            return Minecraft.getInstance().gameDirectory.toPath().resolve("kubejs/server_scripts/generated");
        }
    }

    /**
     * fingerprint 文字列: kind|modid|recipeType|typeId|output|sortedIngredients|sortedCounts。
     * <p>ingredients は codeBody 内の 'ns:path' を全て拾い、output と recipeId を除外。
     */
    public static String fingerprint(ImportedRecipe r) {
        StringBuilder sb = new StringBuilder(128);
        sb.append(r.kind()).append('|');
        sb.append(r.modid()).append('|');
        sb.append(r.recipeType()).append('|');
        sb.append(r.recipeTypeId() != null ? r.recipeTypeId() : "").append('|');

        if (r.kind() == OperationKind.DELETE) {
            // DELETE は recipeId だけが意味を持つ
            sb.append(r.recipeId());
            return sb.toString();
        }

        sb.append(r.outputId() != null ? r.outputId() : "").append('|');

        // codeBody から ingredient id 群を抽出
        List<String> ids = extractIds(r.codeBody(), r.outputId(), r.recipeId(), r.origRecipeId());
        Collections.sort(ids);
        sb.append(String.join(",", ids)).append('|');

        // Item.of count 集合
        List<String> counts = extractItemOfCounts(r.codeBody());
        Collections.sort(counts);
        sb.append(String.join(",", counts));

        return sb.toString();
    }

    /** codeBody 内の 'ns:path' 文字列を全部拾い、除外候補と一致するものを取り除く。 */
    private static List<String> extractIds(String code, @Nullable String output,
                                           @Nullable String recipeId, @Nullable String origRecipeId) {
        List<String> out = new ArrayList<>();
        Matcher m = QUOTED_ID_PATTERN.matcher(code);
        boolean outputRemoved = false;
        boolean recipeIdRemoved = false;
        boolean origRemoved = false;
        while (m.find()) {
            String s = m.group(1);
            // output は 1 回だけ除外 (output が input にも使われる shaped 等を保持)
            if (!outputRemoved && output != null && s.equals(output)) {
                outputRemoved = true;
                continue;
            }
            if (!recipeIdRemoved && recipeId != null && s.equals(recipeId)) {
                recipeIdRemoved = true;
                continue;
            }
            if (!origRemoved && origRecipeId != null && s.equals(origRecipeId)) {
                origRemoved = true;
                continue;
            }
            // credit:generated/* は id 系なので除外
            if (s.startsWith("credit:generated/")) continue;
            out.add(s);
        }
        return out;
    }

    private static List<String> extractItemOfCounts(String code) {
        List<String> out = new ArrayList<>();
        Matcher m = ITEM_OF_COUNT_PATTERN.matcher(code);
        while (m.find()) out.add(m.group(1));
        return out;
    }
}
