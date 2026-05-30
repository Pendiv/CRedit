package DIV.credit.client.importer;

import DIV.credit.Credit;
import DIV.credit.client.io.ScriptWriter.OperationKind;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * v2.1.2: credit 自身が出力した .js ファイルを parse して {@link ImportedRecipe} 列を返す。
 * <p>regex ではなく文字レベルスキャナを使う (event.custom の入れ子オブジェクト / 文字列内の特殊文字
 * を正しく扱うため)。
 *
 * <h3>認識する形式</h3>
 * <ul>
 *   <li>{@code event.shaped('out', [..], {..}).id('credit:generated/X');}</li>
 *   <li>{@code event.shapeless('out', [..]).id('credit:generated/X');}</li>
 *   <li>{@code event.custom({type:..,..}).id('credit:generated/X');}</li>
 *   <li>{@code event.remove({ id: '<orig>' });}</li>
 *   <li>{@code // EDIT of original: <orig>\n event.remove(...)\n event.<add>(...).id('<new>');} → 1 件の EDIT</li>
 * </ul>
 * <p>ServerEvents.recipes(event => {{ ... }}) のラッパは無視 (中身をフラットに scan する)。
 */
public final class ImportParser {

    /** "// EDIT of original: <recipe_id>" コメント。 */
    private static final Pattern EDIT_COMMENT_PATTERN =
        Pattern.compile("//\\s*EDIT of original:\\s*([^\\s]+)");

    /** event.custom の "type: 'ns:path'" / "type:\"ns:path\"" 抽出。 */
    private static final Pattern TYPE_FIELD_PATTERN =
        Pattern.compile("['\"]?type['\"]?\\s*:\\s*['\"]([^'\"]+)['\"]");

    /** event.shaped/shapeless の最初の引数 (output 文字列) を簡易抽出。 */
    private static final Pattern FIRST_STRING_ARG_PATTERN =
        Pattern.compile("^\\s*['\"]([^'\"]+)['\"]");

    /** event.remove の id 抽出: { id: 'X' } 形式。 */
    private static final Pattern REMOVE_ID_PATTERN =
        Pattern.compile("id\\s*:\\s*['\"]([^'\"]+)['\"]");

    /** event.custom 内 result.item / output 推定 (簡易): "result"|"output" : { ... item: 'X' ... }。 */
    private static final Pattern RESULT_ITEM_PATTERN =
        Pattern.compile("['\"]?(?:result|output)['\"]?\\s*:\\s*\\{[^}]*?['\"]?(?:item|id)['\"]?\\s*:\\s*['\"]([^'\"]+)['\"]");

    /** event.custom 内 result が文字列 ('ns:path') の場合: "result"|"output" : 'X'。 */
    private static final Pattern RESULT_STRING_PATTERN =
        Pattern.compile("['\"]?(?:result|output)['\"]?\\s*:\\s*['\"]([^'\"]+)['\"]");

    private ImportParser() {}

    /** 1 ファイルを parse。失敗時は空リスト + ログ。importRoot を渡すと相対パスから modid 推定。 */
    public static List<ImportedRecipe> parseFile(Path file, @Nullable Path importRoot) {
        String src;
        try {
            src = Files.readString(file);
        } catch (IOException e) {
            Credit.LOGGER.error("[C6011] import parse: read failed {}", file, e);
            return List.of();
        }
        String defaultModid = deriveDefaultModid(file, importRoot);
        List<ImportedRecipe> out = new ArrayList<>();
        new ScanState(src, file, defaultModid, out).run();
        return out;
    }

    /** 旧 signature: ConflictDetector が既存 generated scan に使う (importRoot 不要)。 */
    public static List<ImportedRecipe> parseFile(Path file) {
        return parseFile(file, null);
    }

    /**
     * v3.1: source 文字列をそのまま parse。StagingArea の codeBody 直接処理用。
     * 1 statement (= event.shaped/.custom etc.) でも複数 statement の塊でも対応。
     * @param src     KubeJS source body (= event.* statements)
     * @param sourceFileHint ログ表示用パス (= 任意、 null OK)
     * @param modidHint     ImportedRecipe.modid に使う既定値 (= caller が分かってる場合)
     */
    public static List<ImportedRecipe> parseSource(String src, @Nullable Path sourceFileHint,
                                                    @Nullable String modidHint) {
        if (src == null || src.isBlank()) return List.of();
        List<ImportedRecipe> out = new ArrayList<>();
        new ScanState(src, sourceFileHint, modidHint, out).run();
        return out;
    }

    /** importRoot から見た相対パスの中間フォルダから modid を推定。 */
    private static final java.util.Set<String> SKIP_PATH_PARTS =
        java.util.Set.of("generated", "import", "kubejs", "server_scripts", "imported", "credit");

    @Nullable
    private static String deriveDefaultModid(Path file, @Nullable Path importRoot) {
        // importRoot 指定があれば relative path の中間フォルダを traverse
        if (importRoot != null) {
            try {
                Path rel = importRoot.relativize(file);
                // 末尾はファイル名なので除外
                for (int i = 0; i < rel.getNameCount() - 1; i++) {
                    String n = rel.getName(i).toString();
                    if (n.isBlank() || SKIP_PATH_PARTS.contains(n.toLowerCase())) continue;
                    if (n.matches("[a-z0-9_.-]+")) return n;
                }
                return null;
            } catch (IllegalArgumentException e) {
                // file が importRoot 配下に無い → fallback
            }
        }
        // fallback: 親フォルダ名 1 つだけ見る。"generated" 等の skip 対象なら 1 つ上を見る。
        Path p = file.getParent();
        int safety = 5;
        while (p != null && safety-- > 0) {
            String name = p.getFileName() != null ? p.getFileName().toString() : "";
            if (name.isBlank() || SKIP_PATH_PARTS.contains(name.toLowerCase())) {
                p = p.getParent();
                continue;
            }
            if (name.matches("[a-z0-9_.-]+")) return name;
            break;
        }
        return null;
    }

    /** recipe id から namespace を抽出 (ResourceLocation parse 失敗時は null)。 */
    @Nullable
    private static String extractNamespace(@Nullable String recipeId) {
        if (recipeId == null) return null;
        try { return ResourceLocation.parse(recipeId).getNamespace(); } catch (Exception e) { return null; }
    }

    // ─── scanner ───

    private static final class ScanState {
        final String src;
        final Path file;
        final @Nullable String defaultModid;
        final List<ImportedRecipe> out;
        int i = 0;

        /** 直前に見た "// EDIT of original: X" コメント (まだペアになる remove/add を見ていない)。 */
        @Nullable String pendingEditOrig;
        /** EDIT 進行中: edit コメント開始位置 (codeBody の開始)。 */
        int editChunkStart = -1;
        /** EDIT の remove を既に読んだか。 */
        boolean editRemoveSeen;

        ScanState(String src, Path file, @Nullable String defaultModid, List<ImportedRecipe> out) {
            this.src = src;
            this.file = file;
            this.defaultModid = defaultModid;
            this.out = out;
        }

        void run() {
            while (i < src.length()) {
                if (!skipWhitespace()) break;
                if (skipLineComment()) continue;
                if (skipBlockComment()) continue;
                if (tryEvent()) continue;
                // それ以外の文字は読み飛ばす (ServerEvents.recipes(event => { ... 等)
                i++;
            }
        }

        boolean skipWhitespace() {
            while (i < src.length() && Character.isWhitespace(src.charAt(i))) i++;
            return i < src.length();
        }

        /** "//" コメントなら処理して true。EDIT コメントなら pendingEditOrig を立てる。 */
        boolean skipLineComment() {
            if (!startsWith("//")) return false;
            int eol = src.indexOf('\n', i);
            if (eol < 0) eol = src.length();
            String line = src.substring(i, eol);
            Matcher m = EDIT_COMMENT_PATTERN.matcher(line);
            if (m.find()) {
                pendingEditOrig = m.group(1).trim();
                editChunkStart = i;
                editRemoveSeen = false;
            }
            i = eol;
            return true;
        }

        boolean skipBlockComment() {
            if (!startsWith("/*")) return false;
            int end = src.indexOf("*/", i + 2);
            if (end < 0) i = src.length();
            else i = end + 2;
            return true;
        }

        /** "event." なら 1 statement 分処理して true。
         *  v3.12: 識別子 chain ベース。 event.shaped(...) も event.recipes.gtceu.electric_furnace(...) も拾う。 */
        boolean tryEvent() {
            if (!startsWith("event.")) return false;
            int stmtStart = i;
            int pos = i + "event.".length();

            // 識別子 chain を読む: shaped → 1 個、 recipes.gtceu.electric_furnace → 3 個
            List<String> chain = new ArrayList<>();
            while (pos < src.length()) {
                int idStart = pos;
                while (pos < src.length() && (Character.isLetterOrDigit(src.charAt(pos))
                    || src.charAt(pos) == '_')) pos++;
                if (pos == idStart) break;
                chain.add(src.substring(idStart, pos));
                if (pos < src.length() && src.charAt(pos) == '.') { pos++; continue; }
                break;
            }
            if (chain.isEmpty()) { i++; return true; }

            // chain 末尾 識別子直後の空白 skip → '(' を待つ
            int j = pos;
            while (j < src.length() && Character.isWhitespace(src.charAt(j))) j++;
            if (j >= src.length() || src.charAt(j) != '(') { i = pos; return true; }
            int argsClose = findMatching(src, j, '(', ')');
            if (argsClose < 0) { i = src.length(); return true; }
            String argsRaw = src.substring(j + 1, argsClose);
            int afterArgs = argsClose + 1;

            // v3.13: method chain (= .xp(0.7).cookingTime(200).id('X') 等) を全部消費する。
            //  以前は `.id(` だけ専用扱いだったため、 直前に `.xp(...)` 等があると idValue を取り損ね、
            //  stmtSrc にも chain が含まれず Recovery が default 値で構築する bug があった。
            String idValue = null;
            int k = afterArgs;
            while (k < src.length()) {
                int ws = k;
                while (ws < src.length() && Character.isWhitespace(src.charAt(ws))) ws++;
                if (ws >= src.length() || src.charAt(ws) != '.') break;
                int idStart = ws + 1;
                int idEnd = idStart;
                while (idEnd < src.length() && (Character.isLetterOrDigit(src.charAt(idEnd))
                    || src.charAt(idEnd) == '_')) idEnd++;
                if (idEnd == idStart) break;
                int parenPos = idEnd;
                if (parenPos >= src.length() || src.charAt(parenPos) != '(') break;
                int parenClose = findMatching(src, parenPos, '(', ')');
                if (parenClose < 0) break;
                String chainMethod = src.substring(idStart, idEnd);
                if ("id".equals(chainMethod)) {
                    String idArg = src.substring(parenPos + 1, parenClose).trim();
                    idValue = extractString(idArg);
                }
                k = parenClose + 1;
                afterArgs = k;
            }

            // optional ;
            int s = afterArgs;
            while (s < src.length() && Character.isWhitespace(src.charAt(s))) s++;
            if (s < src.length() && src.charAt(s) == ';') afterArgs = s + 1;

            String stmtSrc = src.substring(stmtStart, afterArgs);

            // chain dispatch
            if (chain.size() == 1) {
                handleStatement(chain.get(0), argsRaw, idValue, stmtSrc, stmtStart, afterArgs);
            } else if (chain.size() >= 3 && "recipes".equals(chain.get(0))) {
                // event.recipes.<modid>.<type>(...)
                String modid = chain.get(1);
                String type = String.join(".", chain.subList(2, chain.size()));
                handleModRecipe(modid, type, argsRaw, idValue, stmtSrc, stmtStart, afterArgs);
            } else {
                Credit.LOGGER.debug("[CraftPattern] import: unhandled event chain '{}' skipped",
                    String.join(".", chain));
            }
            i = afterArgs;
            return true;
        }

        void handleStatement(String method, String argsRaw, @Nullable String idValue,
                             String stmtSrc, int stmtStart, int stmtEnd) {
            switch (method) {
                case "remove" -> {
                    String removeId = firstMatch(REMOVE_ID_PATTERN, argsRaw);
                    if (pendingEditOrig != null && !editRemoveSeen
                        && removeId != null && removeId.equals(pendingEditOrig)) {
                        // EDIT の途中 (comment + remove を読んだ)。次の event.add を待つ。
                        editRemoveSeen = true;
                        return;
                    }
                    // 単独 DELETE
                    if (removeId == null) {
                        Credit.LOGGER.warn("[C601] import: event.remove without id: {}", stmtSrc);
                        return;
                    }
                    String modid = resolveModid(removeId);
                    out.add(new ImportedRecipe(
                        OperationKind.DELETE, modid, removeId, null, null,
                        List.of(), file, indentedCodeBody(stmtSrc), "remove", null));
                    resetEditState();
                }
                case "shaped", "shapeless", "custom" -> {
                    String outputId = inferOutputId(method, argsRaw);
                    String typeId = "custom".equals(method) ? firstMatch(TYPE_FIELD_PATTERN, argsRaw) : null;
                    if (pendingEditOrig != null && editRemoveSeen) {
                        // EDIT 完成。codeBody は edit comment 行から add statement 終端まで。
                        String chunk = src.substring(editChunkStart, stmtEnd);
                        String modid = resolveModid(pendingEditOrig);
                        String newId = idValue != null ? idValue : ("credit:generated/" + safeIdPath(outputId));
                        out.add(new ImportedRecipe(
                            OperationKind.EDIT, modid, newId, pendingEditOrig,
                            outputId, List.of(), file, indentedCodeBody(chunk), method, typeId));
                        resetEditState();
                        return;
                    }
                    // ADD
                    String modid = resolveModid(outputId);
                    String newId = idValue != null ? idValue : ("credit:generated/" + safeIdPath(outputId));
                    out.add(new ImportedRecipe(
                        OperationKind.ADD, modid, newId, null,
                        outputId, List.of(), file, indentedCodeBody(stmtSrc), method, typeId));
                    resetEditState();
                }
                case "smelting", "blasting", "smoking", "campfireCooking",
                     "stonecutting", "fuel" -> {
                    // v3.4-3: vanilla 系 cooking + stonecutting + fuel を ImportedRecipe 化
                    String outputId = firstMatch(FIRST_STRING_ARG_PATTERN, argsRaw);
                    if (pendingEditOrig != null && editRemoveSeen) {
                        String chunk = src.substring(editChunkStart, stmtEnd);
                        String modid = resolveModid(pendingEditOrig);
                        String newId = idValue != null ? idValue : ("credit:generated/" + safeIdPath(outputId));
                        out.add(new ImportedRecipe(
                            OperationKind.EDIT, modid, newId, pendingEditOrig,
                            outputId, List.of(), file, indentedCodeBody(chunk), method, null));
                        resetEditState();
                        return;
                    }
                    String modid = resolveModid(outputId);
                    String newId = idValue != null ? idValue : ("credit:generated/" + safeIdPath(outputId));
                    out.add(new ImportedRecipe(
                        OperationKind.ADD, modid, newId, null,
                        outputId, List.of(), file, indentedCodeBody(stmtSrc), method, null));
                    resetEditState();
                }
                default -> {
                    Credit.LOGGER.debug("[CraftPattern] import: unknown event method '{}' skipped", method);
                    resetEditState();
                }
            }
        }

        /** v3.12-A: event.recipes.<modid>.<type>(...) を ImportedRecipe 化。
         *  recipeType = "modid.type" 形式で記録、 Recovery 側で識別。 */
        void handleModRecipe(String modid, String type, String argsRaw, @Nullable String idValue,
                              String stmtSrc, int stmtStart, int stmtEnd) {
            // ADD/EDIT 判定は既存 method と同様 (= pendingEditOrig + editRemoveSeen で判定)
            String recipeTypeKey = modid + "." + type;
            String outputId = inferOutputId("custom", argsRaw);  // mod recipe は result/output field 想定
            String newId = idValue != null ? idValue
                : ("credit:generated/" + safeIdPath(outputId != null ? outputId : recipeTypeKey));
            String resolvedModid = (defaultModid != null) ? defaultModid : modid;

            if (pendingEditOrig != null && editRemoveSeen) {
                String chunk = src.substring(editChunkStart, stmtEnd);
                out.add(new ImportedRecipe(
                    OperationKind.EDIT, resolvedModid, newId, pendingEditOrig,
                    outputId, List.of(), file, indentedCodeBody(chunk), recipeTypeKey, type));
                resetEditState();
                return;
            }
            out.add(new ImportedRecipe(
                OperationKind.ADD, resolvedModid, newId, null,
                outputId, List.of(), file, indentedCodeBody(stmtSrc), recipeTypeKey, type));
            resetEditState();
        }

        /** modid 解決: 親フォルダ → recipe id namespace → fallback "minecraft"。 */
        String resolveModid(@Nullable String idHint) {
            if (defaultModid != null) return defaultModid;
            String ns = extractNamespace(idHint);
            return ns != null ? ns : "minecraft";
        }

        void resetEditState() {
            pendingEditOrig = null;
            editChunkStart = -1;
            editRemoveSeen = false;
        }

        boolean startsWith(String prefix) { return startsWithAt(i, prefix); }
        boolean startsWithAt(int pos, String prefix) {
            return pos >= 0 && pos + prefix.length() <= src.length()
                && src.regionMatches(pos, prefix, 0, prefix.length());
        }
    }

    /** event.shaped/.shapeless: 第 1 引数の文字列。event.custom: result/output フィールドから推定。 */
    @Nullable
    private static String inferOutputId(String method, String argsRaw) {
        if (method.equals("shaped") || method.equals("shapeless")) {
            return firstMatch(FIRST_STRING_ARG_PATTERN, argsRaw);
        }
        // custom
        String fromObj = firstMatch(RESULT_ITEM_PATTERN, argsRaw);
        if (fromObj != null) return fromObj;
        return firstMatch(RESULT_STRING_PATTERN, argsRaw);
    }

    @Nullable
    private static String firstMatch(Pattern p, String s) {
        Matcher m = p.matcher(s);
        return m.find() ? m.group(1) : null;
    }

    /** "'foo'" / "\"foo\"" を foo に戻す。失敗なら null。 */
    @Nullable
    private static String extractString(String s) {
        if (s.length() < 2) return null;
        char q = s.charAt(0);
        if ((q != '\'' && q != '"') || s.charAt(s.length() - 1) != q) return null;
        return s.substring(1, s.length() - 1);
    }

    /** 文字列・コメントを跨いだ括弧 matching。失敗 = -1。 */
    private static int findMatching(String s, int openPos, char open, char close) {
        int depth = 0;
        int p = openPos;
        while (p < s.length()) {
            char c = s.charAt(p);
            if (c == '\'' || c == '"') {
                p = skipString(s, p);
                continue;
            }
            if (p + 1 < s.length() && c == '/' && s.charAt(p + 1) == '/') {
                int eol = s.indexOf('\n', p);
                p = eol < 0 ? s.length() : eol;
                continue;
            }
            if (p + 1 < s.length() && c == '/' && s.charAt(p + 1) == '*') {
                int end = s.indexOf("*/", p + 2);
                p = end < 0 ? s.length() : end + 2;
                continue;
            }
            if (c == open) depth++;
            else if (c == close) {
                depth--;
                if (depth == 0) return p;
            }
            p++;
        }
        return -1;
    }

    /** 開始位置の char (' or ") を quote として読み飛ばす。\エスケープ対応。 */
    private static int skipString(String s, int start) {
        char q = s.charAt(start);
        int p = start + 1;
        while (p < s.length()) {
            char c = s.charAt(p);
            if (c == '\\') { p += 2; continue; }
            if (c == q) return p + 1;
            p++;
        }
        return s.length();
    }

    /** "credit:generated/" の path 部に使える文字に変換。 */
    private static String safeIdPath(@Nullable String outputId) {
        if (outputId == null || outputId.isBlank()) return "unknown";
        int colon = outputId.indexOf(':');
        String p = colon >= 0 ? outputId.substring(colon + 1) : outputId;
        return p.replaceAll("[^a-z0-9_/.-]", "_");
    }

    /** 抽出元の indent (4 spaces) が消えていれば足す。末尾に改行 1 つ。 */
    private static String indentedCodeBody(String chunk) {
        StringBuilder sb = new StringBuilder(chunk.length() + 16);
        for (String line : chunk.split("\n", -1)) {
            if (line.isEmpty()) { sb.append('\n'); continue; }
            if (line.startsWith("    ")) sb.append(line);
            else sb.append("    ").append(line);
            sb.append('\n');
        }
        // 末尾の余分な改行を 1 つに正規化
        while (sb.length() > 1 && sb.charAt(sb.length() - 1) == '\n' && sb.charAt(sb.length() - 2) == '\n') {
            sb.deleteCharAt(sb.length() - 1);
        }
        return sb.toString();
    }
}
