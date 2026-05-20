package DIV.credit.client.importer;

import DIV.credit.Credit;
import DIV.credit.client.io.ScriptWriter.OperationKind;
import DIV.credit.client.screen.ImportConflictScreen;
import DIV.credit.client.staging.StagedChange;
import DIV.credit.client.staging.StagingArea;
import DIV.credit.client.staging.StagingPersistence;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

/**
 * v2.1.2: /credit import の本体。
 * <ul>
 *   <li>{@code <minecraft>/credit/import/} を探す</li>
 *   <li>無ければ作って案内 chat を返す</li>
 *   <li>あれば再帰スキャンして .js ファイル一覧を ImportParser に渡す</li>
 *   <li>パース結果を ConflictDetector に通し、conflict があれば ImportConflictScreen を開く</li>
 *   <li>conflict 無しなら全部 StagingArea に積んで chat 報告</li>
 * </ul>
 */
public final class ImportRunner {

    /** import 用ルートディレクトリ (kubejs/ と同階層)。 */
    public static final String IMPORT_REL_PATH = "credit/import";

    private ImportRunner() {}

    /** main thread から呼ぶ。folder check + scan + 後段振り分け。 */
    public static void run() {
        Path root = Minecraft.getInstance().gameDirectory.toPath().resolve(IMPORT_REL_PATH);

        // フォルダが存在しない → 作って案内
        if (!Files.exists(root)) {
            try {
                Files.createDirectories(root);
                chat(Component.translatable("gui.credit.import.folder_created", root.toString())
                    .withStyle(ChatFormatting.AQUA));
                chat(Component.translatable("gui.credit.import.place_files_hint")
                    .withStyle(ChatFormatting.GRAY));
            } catch (IOException e) {
                Credit.LOGGER.error("[CraftPattern] import folder create failed", e);
                chat(Component.translatable("gui.credit.import.folder_create_failed", e.getMessage())
                    .withStyle(ChatFormatting.RED));
            }
            return;
        }

        if (!Files.isDirectory(root)) {
            chat(Component.translatable("gui.credit.import.path_not_dir", root.toString())
                .withStyle(ChatFormatting.RED));
            return;
        }

        // 再帰スキャンで .js ファイルを集める
        List<Path> jsFiles = scanJsFiles(root);
        if (jsFiles.isEmpty()) {
            chat(Component.translatable("gui.credit.import.no_files", root.toString())
                .withStyle(ChatFormatting.YELLOW));
            return;
        }

        chat(Component.translatable("gui.credit.import.found_files", jsFiles.size())
            .withStyle(ChatFormatting.AQUA));

        // parse all (importRoot を渡して中間フォルダ skip した modid 推定)
        List<ImportedRecipe> all = new ArrayList<>();
        for (Path f : jsFiles) {
            List<ImportedRecipe> rs = ImportParser.parseFile(f, root);
            all.addAll(rs);
            String rel = root.relativize(f).toString();
            chat(Component.literal("  - " + rel + "  (" + rs.size() + " recipes)")
                .withStyle(ChatFormatting.GRAY));
        }
        if (all.isEmpty()) {
            chat(Component.translatable("gui.credit.import.no_recipes")
                .withStyle(ChatFormatting.YELLOW));
            return;
        }

        // v2.1.2-fix-A: import 元同士の同 recipeId を de-duplicate。
        //   全 entry が同 fingerprint → 1 件だけ採用、残りは silent skip + chat warn
        //   内容が異なる → UI で確認 (Conflict UI に「in-source dup」として表示)
        java.util.LinkedHashMap<String, List<ImportedRecipe>> grouped = new java.util.LinkedHashMap<>();
        for (ImportedRecipe r : all) {
            grouped.computeIfAbsent(r.recipeId(), k -> new ArrayList<>()).add(r);
        }
        List<ImportedRecipe> dedup = new ArrayList<>();
        List<ImportConflictScreen.Conflict> inSourceConflicts = new ArrayList<>();
        int silentSkipped = 0;
        for (List<ImportedRecipe> grp : grouped.values()) {
            if (grp.size() == 1) { dedup.add(grp.get(0)); continue; }
            java.util.LinkedHashMap<String, ImportedRecipe> byFp = new java.util.LinkedHashMap<>();
            for (ImportedRecipe r : grp) byFp.putIfAbsent(ConflictDetector.fingerprint(r), r);
            if (byFp.size() == 1) {
                dedup.add(byFp.values().iterator().next());
                silentSkipped += grp.size() - 1;
            } else {
                ImportedRecipe first = grp.get(0);
                dedup.add(first);
                inSourceConflicts.add(new ImportConflictScreen.Conflict(
                    first, new ArrayList<>(grp.subList(1, grp.size()))));
            }
        }
        if (silentSkipped > 0) {
            chat(Component.translatable("gui.credit.import.source_dup_skipped", silentSkipped)
                .withStyle(ChatFormatting.YELLOW));
        }

        // conflict detection (existing kubejs/server_scripts/generated/ との照合)
        Map<ImportedRecipe, List<ImportedRecipe>> rawConflicts = ConflictDetector.detect(dedup);
        List<ImportConflictScreen.Conflict> conflicts = new ArrayList<>(inSourceConflicts);
        for (Map.Entry<ImportedRecipe, List<ImportedRecipe>> e : rawConflicts.entrySet()) {
            conflicts.add(new ImportConflictScreen.Conflict(e.getKey(), e.getValue()));
        }
        List<ImportedRecipe> nonConflicts = new ArrayList<>();
        Set<ImportedRecipe> conflictedImports = new HashSet<>();
        for (ImportConflictScreen.Conflict c : conflicts) conflictedImports.add(c.imported());
        for (ImportedRecipe r : dedup) if (!conflictedImports.contains(r)) nonConflicts.add(r);

        Credit.LOGGER.info("[CraftPattern] import: {} parsed, {} de-dup'd, {} in-source conflicts, {} existing conflicts, {} clean",
            all.size(), silentSkipped, inSourceConflicts.size(), rawConflicts.size(), nonConflicts.size());

        // id 衝突セット (既存と同じ recipeId が import 側にある場合)
        Set<String> idCollisions = new HashSet<>();
        for (Map.Entry<ImportedRecipe, List<ImportedRecipe>> e : rawConflicts.entrySet()) {
            for (ImportedRecipe ex : e.getValue()) {
                if (e.getKey().recipeId().equals(ex.recipeId())) {
                    idCollisions.add(e.getKey().recipeId());
                }
            }
        }

        if (conflicts.isEmpty()) {
            // 全部 clean → 即 staging
            Stats stats = stageAccepted(nonConflicts, idCollisions);
            reportStats(stats);
            int renamed = markFilesProcessed(jsFiles);
            if (renamed > 0) {
                chat(Component.translatable("gui.credit.import.renamed", renamed)
                    .withStyle(ChatFormatting.GRAY));
            }
            return;
        }

        // conflict あり → Screen を開く
        Minecraft mc = Minecraft.getInstance();
        ImportConflictScreen screen = new ImportConflictScreen(
            mc.screen, conflicts, nonConflicts.size(),
            decisions -> {
                List<ImportedRecipe> accepted = new ArrayList<>(nonConflicts);
                accepted.addAll(ImportConflictScreen.acceptedFromDecisions(conflicts, decisions));
                Stats stats = stageAccepted(accepted, idCollisions);
                reportStats(stats);
                int renamed = markFilesProcessed(jsFiles);
                if (renamed > 0) {
                    chat(Component.translatable("gui.credit.import.renamed", renamed)
                        .withStyle(ChatFormatting.GRAY));
                }
            });
        mc.setScreen(screen);
    }

    private static void reportStats(Stats s) {
        chat(Component.translatable("gui.credit.import.staged_all", s.staged())
            .withStyle(ChatFormatting.GREEN));
        if (s.reEmitted() > 0 || s.skipped() > 0 || s.copyThru() > 0) {
            chat(Component.translatable("gui.credit.import.stage_breakdown",
                s.reEmitted(), s.copyThru(), s.skipped())
                .withStyle(ChatFormatting.GRAY));
        }
        if (s.skipped() > 0) {
            chat(Component.translatable("gui.credit.import.skip_hint")
                .withStyle(ChatFormatting.YELLOW));
        }
        chat(Component.translatable("gui.credit.staging.commit_hint")
            .withStyle(ChatFormatting.GRAY));
    }

    /** stageAccepted の返り値統計。 */
    public record Stats(int staged, int reEmitted, int copyThru, int skipped) {}

    /**
     * v2.1.2: 処理済 .js を {@code <name>.imported} (衝突時 .imported.2 / .3 ...) にリネーム。
     * 失敗は warn ログのみで続行 (他人の wkdir 破壊しないこと優先)。
     * @return リネーム成功数
     */
    private static int markFilesProcessed(List<Path> files) {
        int n = 0;
        for (Path src : files) {
            try {
                Path target = chooseRenameTarget(src);
                Files.move(src, target);
                Credit.LOGGER.info("[CraftPattern] import: marked processed: {} → {}",
                    src.getFileName(), target.getFileName());
                n++;
            } catch (IOException e) {
                Credit.LOGGER.warn("[CraftPattern] import: rename failed for {}: {}", src, e.getMessage());
            }
        }
        return n;
    }

    /**
     * v2.1.2-unified: ImportedRecipe から recipetype UID を解決。
     * <ul>
     *   <li>event.custom → recipeTypeId (例 "ae2:charger")</li>
     *   <li>event.shaped → "minecraft:crafting_shaped"</li>
     *   <li>event.shapeless → "minecraft:crafting_shapeless"</li>
     *   <li>event.remove (DELETE) → null (writer 側で unified fallback)</li>
     * </ul>
     */
    @org.jetbrains.annotations.Nullable
    private static String resolveRecipeTypeUid(ImportedRecipe r) {
        return switch (r.recipeType()) {
            case "custom"    -> r.recipeTypeId();
            case "shaped"    -> "minecraft:crafting_shaped";
            case "shapeless" -> "minecraft:crafting_shapeless";
            default          -> null;
        };
    }

    private static Path chooseRenameTarget(Path src) {
        Path parent = src.getParent();
        String name = src.getFileName().toString();
        Path first = parent.resolve(name + ".imported");
        if (!Files.exists(first)) return first;
        for (int i = 2; i < 10000; i++) {
            Path c = parent.resolve(name + ".imported." + i);
            if (!Files.exists(c)) return c;
        }
        return parent.resolve(name + ".imported." + System.nanoTime());
    }

    /**
     * 採用された ImportedRecipe を StagingArea に積む。
     * <p>v2.1.2 Phase 1:
     * <ul>
     *   <li>DELETE (event.remove) → 元 codeBody をそのまま (copy-thru)</li>
     *   <li>ADD/EDIT で event.custom 経路 → RecipeReEmitter で再 emit、成功時のみ stage</li>
     *   <li>event.shaped/.shapeless (vanilla クラフト) → 現状未対応、skip</li>
     *   <li>id 衝突がある ADD は EDIT に transform (duplicate id 回避)</li>
     * </ul>
     */
    private static Stats stageAccepted(List<ImportedRecipe> accepted, Set<String> idCollisions) {
        int staged = 0, reEmitted = 0, copyThru = 0, skipped = 0;
        for (ImportedRecipe r : accepted) {
            String codeBody;
            if (r.kind() == OperationKind.DELETE) {
                codeBody = r.codeBody();
                copyThru++;
            } else {
                java.util.Optional<String> re = RecipeReEmitter.reEmit(r);
                if (re.isEmpty()) { skipped++; continue; }
                codeBody = re.get();
                reEmitted++;
            }

            String jeiCat = resolveRecipeTypeUid(r);
            StagedChange sc;
            if (r.kind() == OperationKind.ADD && idCollisions.contains(r.recipeId())) {
                String editCode =
                    "    // EDIT of original: " + r.recipeId() + " (imported, replaces existing)\n"
                  + "    event.remove({ id: '" + r.recipeId() + "' })\n"
                  + codeBody;
                sc = StagedChange.createImported(
                    OperationKind.EDIT, r.modid(), r.recipeId(), r.recipeId(), editCode, jeiCat);
            } else {
                sc = StagedChange.createImported(
                    r.kind(), r.modid(), r.recipeId(), r.origRecipeId(), codeBody, jeiCat);
            }
            StagingArea.INSTANCE.stage(sc);
            staged++;
        }
        if (staged > 0) StagingPersistence.save();
        Credit.LOGGER.info("[CraftPattern] import stage: re-emit={}, copy-thru={}, skipped={}, total staged={}",
            reEmitted, copyThru, skipped, staged);
        return new Stats(staged, reEmitted, copyThru, skipped);
    }

    /**
     * v3.0.0: 副作用なしの scan + parse。 /credit preview-import 等で
     * staging に積まず ImportedRecipe 列だけ欲しいケース用。
     * <p>import folder が無ければ空リスト (= 通常 run() のように作成しない)。
     */
    public static List<ImportedRecipe> scanAndParse() {
        Path root = Minecraft.getInstance().gameDirectory.toPath().resolve(IMPORT_REL_PATH);
        if (!Files.isDirectory(root)) return List.of();
        List<Path> files = scanJsFiles(root);
        List<ImportedRecipe> out = new ArrayList<>();
        for (Path f : files) {
            out.addAll(ImportParser.parseFile(f, root));
        }
        return out;
    }

    /** root 配下を再帰して .js ファイルを集める。失敗時は空リスト。 */
    private static List<Path> scanJsFiles(Path root) {
        List<Path> out = new ArrayList<>();
        try (Stream<Path> s = Files.walk(root)) {
            s.filter(Files::isRegularFile)
             .filter(p -> p.getFileName().toString().toLowerCase().endsWith(".js"))
             .forEach(out::add);
        } catch (IOException e) {
            Credit.LOGGER.error("[CraftPattern] import scan failed", e);
        }
        return out;
    }

    private static void chat(Component msg) {
        var p = Minecraft.getInstance().player;
        if (p != null) p.displayClientMessage(msg, false);
    }
}
