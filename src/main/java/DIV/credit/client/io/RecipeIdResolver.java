package DIV.credit.client.io;

import DIV.credit.Credit;
import DIV.credit.client.importer.ConflictDetector;
import DIV.credit.client.staging.StagedChange;
import DIV.credit.client.staging.StagingArea;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * v2.1.2: recipe id の衝突回避ユーティリティ。
 * <p>BuilderScreen の autoRecipeId / ImportRunner の staging 時に呼ばれ、
 * 既存 (StagingArea + kubejs/server_scripts/generated/) と衝突したら _2 / _3 ... を付加して
 * unique な id を返す。
 *
 * <h3>衝突 source</h3>
 * <ol>
 *   <li>{@link StagingArea} 内の未 push StagedChange.recipeId</li>
 *   <li>resolveGeneratedRoot 配下の全 .js 内の {@code .id('credit:...')} と {@code event.remove({id:'credit:...'})}</li>
 * </ol>
 */
public final class RecipeIdResolver {

    /** .id('credit:generated/...') の credit: namespace id 全部。 */
    private static final Pattern CREDIT_ID_PATTERN =
        Pattern.compile("['\"]?id['\"]?\\s*:\\s*['\"]?\\s*['\"]([a-z0-9_.-]+:[a-z0-9_./-]+)['\"]"
                      + "|\\.id\\(\\s*['\"]([a-z0-9_.-]+:[a-z0-9_./-]+)['\"]");

    private RecipeIdResolver() {}

    /** baseId が unique でなければ _2/_3... を付加。{@code excludeSelf} は EDIT 等で自分自身の origId を除外する用。 */
    public static String resolveUnique(String baseId, @Nullable String excludeSelf) {
        Set<String> used = collectUsedIds();
        if (excludeSelf != null) used.remove(excludeSelf);
        if (!used.contains(baseId)) return baseId;
        for (int i = 2; i < 100000; i++) {
            String candidate = baseId + "_" + i;
            if (!used.contains(candidate)) return candidate;
        }
        // 最終 fallback (実用上到達しない)
        return baseId + "_" + System.nanoTime();
    }

    /** excludeSelf 不要な簡易 entry point。 */
    public static String resolveUnique(String baseId) {
        return resolveUnique(baseId, null);
    }

    /** StagingArea + generated/ 全 .js から id を収集。 */
    private static Set<String> collectUsedIds() {
        Set<String> ids = new HashSet<>();
        for (StagedChange c : StagingArea.INSTANCE.all()) {
            if (c.recipeId != null) ids.add(c.recipeId);
            if (c.origRecipeId != null) ids.add(c.origRecipeId);
        }
        Path root = ConflictDetector.resolveGeneratedRoot();
        if (root != null && Files.isDirectory(root)) {
            try (Stream<Path> s = Files.walk(root)) {
                s.filter(Files::isRegularFile)
                 .filter(p -> p.getFileName().toString().toLowerCase().endsWith(".js"))
                 .forEach(p -> collectFromFile(p, ids));
            } catch (IOException e) {
                Credit.LOGGER.warn("[C404] id collect walk failed: {}", e.getMessage());
            }
        }
        return ids;
    }

    private static void collectFromFile(Path p, Set<String> out) {
        try {
            String content = Files.readString(p);
            Matcher m = CREDIT_ID_PATTERN.matcher(content);
            while (m.find()) {
                String g1 = m.group(1);
                String g2 = m.group(2);
                if (g1 != null) out.add(g1);
                if (g2 != null) out.add(g2);
            }
        } catch (IOException ignored) {
            // 単一ファイル読込失敗は skip
        }
    }
}
