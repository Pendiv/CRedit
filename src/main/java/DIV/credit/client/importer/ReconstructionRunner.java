package DIV.credit.client.importer;

import DIV.credit.Credit;
import DIV.credit.client.io.ScriptWriter.OperationKind;
import DIV.credit.client.screen.ImportConflictScreen;
import DIV.credit.client.screen.ReconstructionConfirmScreen;
import DIV.credit.client.staging.StagedChange;
import DIV.credit.client.staging.StagingArea;
import DIV.credit.client.staging.StagingPersistence;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

/**
 * v2.1.4: /credit reconstruction の本体。
 * <p>{@code <dump_root>/generated/} 配下の credit 既存 .js を再解釈して
 * 現行コード生成規則 + UNIFIED_EDIT_FILES 設定で書き直す自己改修コマンド。
 *
 * <h3>動作</h3>
 * <ol>
 *   <li>{@code generated/} を再帰 scan (.reconstructed_backup_* は除外)</li>
 *   <li>ImportParser で全 parse → List&lt;ImportedRecipe&gt;</li>
 *   <li>in-source dup 検出 (= 同 recipeId が複数ファイルにある)</li>
 *   <li>ReconstructionConfirmScreen で件数表示 → user 確認</li>
 *   <li>OK → backup フォルダに全 .js move → stage</li>
 *   <li>stage: re-emit 試行、失敗時は copy-thru fallback (= 元 codeBody そのまま)</li>
 *   <li>imported flag は元 sourceFile が {@code imported_*} で始まるなら継承</li>
 * </ol>
 */
public final class ReconstructionRunner {

    /** backup フォルダ名 prefix。日時 ISO サフィックス付き。 */
    public static final String BACKUP_PREFIX = "reconstructed_backup_";
    /** backup 退避ルート (= <minecraft>/credit/reconstruction_backup/<ts>/)。
     *  KubeJS の scan 範囲 (= kubejs/server_scripts/) の外に置くため duplicate id を防ぐ。 */
    public static final String BACKUP_REL_PATH = "credit/reconstruction_backup";
    private static final DateTimeFormatter BACKUP_TS =
        DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss");

    private ReconstructionRunner() {}

    /** main thread から呼ぶ。 */
    public static void run() {
        Path generatedRoot = ConflictDetector.resolveGeneratedRoot();
        if (generatedRoot == null || !Files.isDirectory(generatedRoot)) {
            chat(Component.translatable("gui.credit.recon.no_generated")
                .withStyle(ChatFormatting.YELLOW));
            return;
        }

        List<Path> jsFiles = scanGeneratedJs(generatedRoot);
        if (jsFiles.isEmpty()) {
            chat(Component.translatable("gui.credit.recon.no_files")
                .withStyle(ChatFormatting.YELLOW));
            return;
        }

        // parse all
        List<ImportedRecipe> all = new ArrayList<>();
        for (Path f : jsFiles) {
            all.addAll(ImportParser.parseFile(f, generatedRoot));
        }
        if (all.isEmpty()) {
            chat(Component.translatable("gui.credit.recon.no_recipes")
                .withStyle(ChatFormatting.YELLOW));
            return;
        }

        // in-source dup 検出 (同 recipeId の集合 → fingerprint 違いなら conflict UI)
        LinkedHashMap<String, List<ImportedRecipe>> grouped = new LinkedHashMap<>();
        for (ImportedRecipe r : all) {
            grouped.computeIfAbsent(r.recipeId(), k -> new ArrayList<>()).add(r);
        }
        List<ImportedRecipe> dedup = new ArrayList<>();
        List<ImportConflictScreen.Conflict> inSourceConflicts = new ArrayList<>();
        int silentSkipped = 0;
        for (List<ImportedRecipe> grp : grouped.values()) {
            if (grp.size() == 1) { dedup.add(grp.get(0)); continue; }
            LinkedHashMap<String, ImportedRecipe> byFp = new LinkedHashMap<>();
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

        Credit.LOGGER.info("[CraftPattern] reconstruction scan: files={}, parsed={}, dedup'd={}, in-source conflicts={}, total to process={}",
            jsFiles.size(), all.size(), silentSkipped, inSourceConflicts.size(), dedup.size());

        // Confirm screen 開く
        Minecraft mc = Minecraft.getInstance();
        ReconstructionConfirmScreen confirm = new ReconstructionConfirmScreen(
            mc.screen, jsFiles.size(), dedup.size(), inSourceConflicts.size(), silentSkipped,
            () -> execute(generatedRoot, jsFiles, dedup, inSourceConflicts));
        mc.setScreen(confirm);
    }

    /** Confirm OK 後の実処理: backup → stage。 */
    private static void execute(Path generatedRoot, List<Path> jsFiles,
                                List<ImportedRecipe> dedup,
                                List<ImportConflictScreen.Conflict> inSourceConflicts) {
        // 1. backup フォルダに全 .js を move
        // v2.1.4-fix: KubeJS scan 範囲 (= kubejs/server_scripts/) の外に退避する。
        // 旧実装では generated/.reconstructed_backup_<ts>/ に置いてしまい、 KubeJS が
        // hidden prefix '.' フォルダも scan するせいで duplicate id error が出ていた。
        Path backupRoot = Minecraft.getInstance().gameDirectory.toPath()
            .resolve(BACKUP_REL_PATH)
            .resolve(BACKUP_PREFIX + LocalDateTime.now().format(BACKUP_TS));
        int backedUp = moveToBackup(jsFiles, generatedRoot, backupRoot);
        chat(Component.translatable("gui.credit.recon.backup_done", backedUp, backupRoot.toString())
            .withStyle(ChatFormatting.AQUA));

        // 2. conflict あれば ImportConflictScreen (= 既存 UI 流用)
        if (!inSourceConflicts.isEmpty()) {
            Minecraft mc = Minecraft.getInstance();
            int nonConflictCount = dedup.size() - inSourceConflicts.size();
            ImportConflictScreen screen = new ImportConflictScreen(
                mc.screen, inSourceConflicts, nonConflictCount,
                decisions -> {
                    List<ImportedRecipe> nonConflicts = new ArrayList<>(dedup);
                    Set<ImportedRecipe> conflicted = new HashSet<>();
                    for (ImportConflictScreen.Conflict c : inSourceConflicts) conflicted.add(c.imported());
                    nonConflicts.removeAll(conflicted);
                    List<ImportedRecipe> accepted = new ArrayList<>(nonConflicts);
                    accepted.addAll(ImportConflictScreen.acceptedFromDecisions(inSourceConflicts, decisions));
                    Stats stats = stageAccepted(accepted);
                    reportStats(stats);
                });
            mc.setScreen(screen);
            return;
        }

        // 3. conflict 無し → 全 dedup を即 staging
        Stats stats = stageAccepted(dedup);
        reportStats(stats);
    }

    /** generated/ 配下を再帰 scan (.reconstructed_backup_* は除外)。 */
    private static List<Path> scanGeneratedJs(Path root) {
        List<Path> out = new ArrayList<>();
        try (Stream<Path> s = Files.walk(root)) {
            s.filter(Files::isRegularFile)
             .filter(p -> p.getFileName().toString().toLowerCase().endsWith(".js"))
             .filter(p -> !isInsideBackup(root, p))
             .forEach(out::add);
        } catch (IOException e) {
            Credit.LOGGER.error("[CraftPattern] reconstruction scan failed", e);
        }
        return out;
    }

    /** path が backup フォルダ配下なら true。新旧 prefix 両対応 (= 既存 backup 残存ケース)。 */
    private static boolean isInsideBackup(Path root, Path file) {
        Path rel;
        try { rel = root.relativize(file); } catch (Exception e) { return false; }
        for (int i = 0; i < rel.getNameCount(); i++) {
            String n = rel.getName(i).toString();
            if (n.startsWith(BACKUP_PREFIX) || n.startsWith("." + BACKUP_PREFIX)) return true;
        }
        return false;
    }

    /** 全 .js を相対 path 維持で backup フォルダに move。失敗は warn ログのみ続行。 */
    private static int moveToBackup(List<Path> files, Path generatedRoot, Path backupRoot) {
        int n = 0;
        for (Path src : files) {
            try {
                Path rel = generatedRoot.relativize(src);
                Path dst = backupRoot.resolve(rel);
                Files.createDirectories(dst.getParent());
                Files.move(src, dst);
                n++;
            } catch (IOException e) {
                Credit.LOGGER.warn("[CraftPattern] reconstruction backup move failed: {} → {}: {}",
                    src, backupRoot, e.getMessage());
            }
        }
        return n;
    }

    /** stageAccepted の返り値統計。 */
    public record Stats(int staged, int reEmitted, int copyThru) {}

    /**
     * 採用 ImportedRecipe を Staging に積む。
     * <p>RecipeReEmitter で再 emit → 成功時 新 codeBody / 失敗時 copy-thru fallback。
     * imported flag は元 sourceFile name の {@code imported_} prefix で継承判定。
     */
    private static Stats stageAccepted(List<ImportedRecipe> accepted) {
        int staged = 0, reEmitted = 0, copyThru = 0;
        for (ImportedRecipe r : accepted) {
            String codeBody;
            if (r.kind() == OperationKind.DELETE) {
                codeBody = r.codeBody();
                copyThru++;
            } else {
                var re = RecipeReEmitter.reEmit(r);
                if (re.isPresent()) {
                    codeBody = re.get();
                    reEmitted++;
                } else {
                    // v2.1.4: reconstruction は failure 時 copy-thru fallback
                    codeBody = r.codeBody();
                    copyThru++;
                }
            }

            String jeiCat = resolveRecipeTypeUid(r);
            boolean wasImported = isFromImportedFile(r);
            StagedChange sc = wasImported
                ? StagedChange.createImported(r.kind(), r.modid(), r.recipeId(), r.origRecipeId(), codeBody, jeiCat)
                : StagedChange.create(r.kind(), r.modid(), r.recipeId(), r.origRecipeId(), codeBody, jeiCat);
            StagingArea.INSTANCE.stage(sc);
            staged++;
        }
        if (staged > 0) StagingPersistence.save();
        Credit.LOGGER.info("[CraftPattern] reconstruction stage: re-emit={}, copy-thru={}, total staged={}",
            reEmitted, copyThru, staged);
        return new Stats(staged, reEmitted, copyThru);
    }

    /** sourceFile name が "imported_" で始まるなら元 imported 由来。 */
    private static boolean isFromImportedFile(ImportedRecipe r) {
        if (r.sourceFile() == null) return false;
        Path fn = r.sourceFile().getFileName();
        return fn != null && fn.toString().startsWith("imported_");
    }

    /** ImportRunner と同じロジック (event.shaped/.shapeless/.custom → JEI category UID)。 */
    @Nullable
    private static String resolveRecipeTypeUid(ImportedRecipe r) {
        return switch (r.recipeType()) {
            case "custom"    -> r.recipeTypeId();
            case "shaped"    -> "minecraft:crafting_shaped";
            case "shapeless" -> "minecraft:crafting_shapeless";
            default          -> null;
        };
    }

    private static void reportStats(Stats s) {
        chat(Component.translatable("gui.credit.recon.staged", s.staged())
            .withStyle(ChatFormatting.GREEN));
        chat(Component.translatable("gui.credit.recon.breakdown",
            s.reEmitted(), s.copyThru())
            .withStyle(ChatFormatting.GRAY));
        chat(Component.translatable("gui.credit.staging.commit_hint")
            .withStyle(ChatFormatting.GRAY));
    }

    private static void chat(Component msg) {
        var p = Minecraft.getInstance().player;
        if (p != null) p.displayClientMessage(msg, false);
    }
}
