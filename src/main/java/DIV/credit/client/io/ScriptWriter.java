package DIV.credit.client.io;

import DIV.credit.Credit;
import DIV.credit.client.draft.RecipeDraft;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * KubeJS .js ファイルへの書き込み（v2.0.0 構造）。
 * <pre>
 * dump_root/generated/&lt;modid&gt;/{add,edit,delete}.js
 * </pre>
 * 1 MOD あたり 3 ファイル（add / edit / delete）に分かれる。挿入は marker → `});` の二段フォールバック。
 */
public final class ScriptWriter {

    public static final String MARKER = "// @credit:insert-marker";
    public static final String MARKER_FULL = MARKER + " (do not remove)";

    /** 操作種別。ファイル名 + ヘッダ表示に使う。 */
    public enum OperationKind {
        ADD("add"),
        EDIT("edit"),
        DELETE("delete");

        public final String fileName;
        OperationKind(String n) { this.fileName = n; }
    }

    private ScriptWriter() {}

    public sealed interface DumpResult {
        Path path();
        String message();

        record Success(Path path) implements DumpResult {
            @Override public String message() { return "Dumped to: " + path; }
        }
        record Fallback(Path path, String reason) implements DumpResult {
            @Override public String message() { return "Fallback (" + reason + "); created: " + path; }
        }
        record Failure(String message) implements DumpResult {
            @Override public Path path() { return null; }
        }
    }

    // ─── code body 生成 (writing なし) ───
    // staging-aware path で使う。staging 経由なら build...() で code 取って StagingArea.stage に投げる。

    /** ADD: 新規レシピのコード body だけ生成。null/blank なら不正 draft。 */
    @Nullable
    public static String buildAddCode(RecipeDraft draft, String recipeId) {
        String code = draft.emit(recipeId);
        if (code == null || code.isBlank()) return null;
        return code;
    }

    /** EDIT: remove + 新 add のコード body 生成。 */
    @Nullable
    public static String buildEditCode(RecipeDraft draft, String origRecipeId, String newRecipeId) {
        String addCode = draft.emit(newRecipeId);
        if (addCode == null || addCode.isBlank()) return null;
        return "    // EDIT of original: " + origRecipeId + "\n"
             + "    event.remove({ id: '" + origRecipeId + "' })\n"
             + addCode;
    }

    /** DELETE: event.remove のコード 1 行生成。 */
    public static String buildDeleteCode(String recipeId) {
        return "    event.remove({ id: '" + recipeId + "' })\n";
    }

    /** EDIT 用 modid 抽出 (orig ID 優先、fallback で draft の category namespace)。 */
    public static String editModid(RecipeDraft draft, String origRecipeId) {
        ResourceLocation orig = parseSafe(origRecipeId);
        return orig != null ? orig.getNamespace() : draft.recipeType().getUid().getNamespace();
    }

    // ─── 旧 dump API: staging を経由しない直接書き込み (互換 + 緊急 escape hatch) ───

    public static DumpResult dumpAdd(RecipeDraft draft, String recipeId) {
        String code = buildAddCode(draft, recipeId);
        if (code == null) return new DumpResult.Failure("Draft is empty (need at least output + relevant inputs)");
        return writeOp(OperationKind.ADD, draft.recipeType().getUid().getNamespace(), code);
    }

    public static DumpResult dumpEdit(RecipeDraft draft, String origRecipeId, String newRecipeId) {
        String code = buildEditCode(draft, origRecipeId, newRecipeId);
        if (code == null) return new DumpResult.Failure("Draft is empty (need at least output + relevant inputs)");
        return writeOp(OperationKind.EDIT, editModid(draft, origRecipeId), code);
    }

    public static DumpResult dumpDelete(String recipeId) {
        ResourceLocation rl = parseSafe(recipeId);
        if (rl == null) return new DumpResult.Failure("Invalid recipe id: " + recipeId);
        return writeOp(OperationKind.DELETE, rl.getNamespace(), buildDeleteCode(recipeId));
    }

    /** push 経路から呼ばれる: staged code body を直接書き込み。 */
    public static DumpResult writeStagedCode(OperationKind op, String modid, String code) {
        return writeOp(op, modid, code);
    }

    @Nullable
    private static ResourceLocation parseSafe(String s) {
        try { return new ResourceLocation(s); } catch (Exception e) { return null; }
    }

    private static DumpResult writeOp(OperationKind op, String modid, String code) {
        String root = DIV.credit.CreditConfig.DUMP_ROOT.get();
        if (root == null || root.isBlank()) root = "kubejs/server_scripts";
        if (root.endsWith("/") || root.endsWith("\\")) root = root.substring(0, root.length() - 1);
        Path target = Minecraft.getInstance().gameDirectory.toPath()
            .resolve(root + "/generated/" + modid + "/" + op.fileName + ".js");
        try {
            Files.createDirectories(target.getParent());
            if (!Files.exists(target)) {
                writeNewFile(target, op, modid, code);
                return new DumpResult.Success(target);
            }
            return appendIntoExisting(target, op, modid, code);
        } catch (IOException e) {
            Credit.LOGGER.error("[CraftPattern] {} write IO error", op, e);
            return new DumpResult.Failure("IO error: " + e.getMessage());
        }
    }

    private static void writeNewFile(Path target, OperationKind op, String modid, String code) throws IOException {
        String now = LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME);
        String header =
            "// =====================================================\n" +
            "// Generated by /craftpattern (credit MOD)\n" +
            "// FORMAT: KubeJS (https://kubejs.com/)\n" +
            "// Operation: " + op.name() + "\n" +
            "// MOD: " + modid + "\n" +
            "// First created: " + now + "\n" +
            "// Place this file under kubejs/server_scripts/ to take effect.\n" +
            "// Run /reload (or /kubejs reload server_scripts) to apply.\n" +
            "// =====================================================\n\n" +
            "ServerEvents.recipes(event => {\n\n";
        String footer =
            "    " + MARKER_FULL + "\n" +
            "});\n";
        Files.writeString(target, header + code + footer);
    }

    private static DumpResult appendIntoExisting(Path target, OperationKind op, String modid, String code) throws IOException {
        String content = Files.readString(target);

        Integer insertAt = findMarkerInsertionPoint(content);
        String reason = null;
        if (insertAt == null) {
            insertAt = findClosingBraceInsertionPoint(content);
            reason = "marker missing";
        }
        if (insertAt != null) {
            String newContent = content.substring(0, insertAt) + code + content.substring(insertAt);
            Files.writeString(target, newContent);
            return reason == null
                ? new DumpResult.Success(target)
                : new DumpResult.Fallback(target, reason);
        }

        Path numbered = nextNumberedFile(target);
        writeNewFile(numbered, op, modid, code);
        return new DumpResult.Fallback(numbered, "no marker and no `});` found");
    }

    @Nullable
    private static Integer findMarkerInsertionPoint(String content) {
        int idx = content.indexOf(MARKER);
        if (idx < 0) return null;
        return content.lastIndexOf('\n', idx) + 1;
    }

    @Nullable
    private static Integer findClosingBraceInsertionPoint(String content) {
        int idx = content.lastIndexOf("});");
        if (idx < 0) return null;
        return content.lastIndexOf('\n', idx) + 1;
    }

    private static Path nextNumberedFile(Path original) {
        String fileName = original.getFileName().toString();
        int dot = fileName.lastIndexOf('.');
        String base = dot >= 0 ? fileName.substring(0, dot) : fileName;
        String ext  = dot >= 0 ? fileName.substring(dot)    : "";
        Path dir = original.getParent();
        for (int i = 2; i < 10000; i++) {
            Path candidate = dir.resolve(base + "_" + i + ext);
            if (!Files.exists(candidate)) return candidate;
        }
        return dir.resolve(base + "_overflow" + ext);
    }
}
