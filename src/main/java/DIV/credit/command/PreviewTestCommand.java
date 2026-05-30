package DIV.credit.command;

import DIV.credit.Credit;
import DIV.credit.client.draft.DraftStore;
import DIV.credit.client.draft.RecipeDraft;
import DIV.credit.client.preview.JeiRenderBridge;
import DIV.credit.jei.CraftPatternJeiPlugin;
import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import mezz.jei.api.gui.IRecipeLayoutDrawable;
import mezz.jei.api.recipe.IRecipeManager;
import mezz.jei.api.recipe.RecipeType;
import mezz.jei.api.recipe.category.IRecipeCategory;
import mezz.jei.api.runtime.IJeiRuntime;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.fml.ModList;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * v4.1.x: {@code /credit dev previewtest [filter]} — 各 JEI category に対して
 * 「draft 作成 → recipe load → preview 構築 (JEI + EMI 両 path)」 を試行して結果集計。
 *
 * <p>filter:
 * <ul>
 *   <li>{@code all} or 省略: 全 category</li>
 *   <li>{@code &lt;modid&gt;}: 当該 mod の category のみ (例: {@code mekanism})</li>
 *   <li>{@code &lt;modid:type&gt;}: 個別 category (例: {@code gtceu:assembler})</li>
 * </ul>
 *
 * <p>出力先: {@code run/credit_previewtest/preview_&lt;timestamp&gt;.txt}
 *
 * <h3>結果ステータス:</h3>
 * <ul>
 *   <li>{@code OK}      : JEI 経路で preview 構築成功 (= EMI 経路も成功想定)</li>
 *   <li>{@code OK_EMI_ONLY} : JEI 失敗、 EMI 経路だけ成功 (= 通常起きない、 もし発生したら要調査)</li>
 *   <li>{@code FAIL_SYNTH}  : draft.synthesizePreviewRecipe() が null (= toRecipeInstance 未実装)</li>
 *   <li>{@code FAIL_NO_DRAFT}: credit が当該 category 用 draft factory 持ってない</li>
 *   <li>{@code FAIL_NO_RECIPE}: category に sample recipe が無い</li>
 *   <li>{@code FAIL_LOAD}: loadFromRecipe 失敗</li>
 *   <li>{@code FAIL_BOTH}: synth ok だが JEI/EMI 両 path build 失敗</li>
 *   <li>{@code SKIP_UNSUPPORTED}: EXPLICIT_UNSUPPORTED に登録済</li>
 *   <li>{@code ERROR}: 想定外例外</li>
 * </ul>
 */
public final class PreviewTestCommand {

    private PreviewTestCommand() {}

    public static int execute(CommandContext<CommandSourceStack> ctx) {
        String filter = "all";
        try { filter = StringArgumentType.getString(ctx, "filter").trim(); }
        catch (IllegalArgumentException ignored) {}
        return runTest(filter);
    }

    public static int executeNoArg(CommandContext<CommandSourceStack> ctx) {
        return runTest("all");
    }

    private static int runTest(String filter) {
        IJeiRuntime rt = CraftPatternJeiPlugin.runtime;
        if (rt == null) {
            chat(Component.literal("[PreviewTest] JEI not ready").withStyle(ChatFormatting.RED));
            return 0;
        }
        IRecipeManager mgr = rt.getRecipeManager();
        List<IRecipeCategory<?>> cats;
        try { cats = mgr.createRecipeCategoryLookup().get().toList(); }
        catch (Throwable t) {
            chat(Component.literal("[PreviewTest] category lookup failed: " + t).withStyle(ChatFormatting.RED));
            return 0;
        }

        boolean emiAvail = ModList.get().isLoaded("emi");

        // filter 適用
        List<IRecipeCategory<?>> selected = new ArrayList<>();
        for (var cat : cats) {
            if (matchesFilter(cat, filter)) selected.add(cat);
        }
        if (selected.isEmpty()) {
            chat(Component.literal("[PreviewTest] no categories match filter: " + filter).withStyle(ChatFormatting.YELLOW));
            return 0;
        }

        chat(Component.literal("[PreviewTest] running " + selected.size() + " categories (filter=" + filter + ", emi=" + emiAvail + ")")
            .withStyle(ChatFormatting.AQUA));

        List<TestResult> results = new ArrayList<>(selected.size());
        Map<String, Integer> statusCounts = new HashMap<>();
        long t0 = System.currentTimeMillis();
        for (var cat : selected) {
            TestResult r = testCategory(cat, mgr, emiAvail);
            results.add(r);
            statusCounts.merge(r.status, 1, Integer::sum);
        }
        long elapsed = System.currentTimeMillis() - t0;

        Path file = writeOutput(filter, results, statusCounts, elapsed, emiAvail);
        if (file != null) {
            chat(Component.literal("[PreviewTest] wrote " + file).withStyle(ChatFormatting.GREEN));
        }
        // chat summary
        for (var e : statusCounts.entrySet().stream()
                .sorted((a, b) -> Integer.compare(b.getValue(), a.getValue())).toList()) {
            chat(Component.literal("  " + e.getKey() + ": " + e.getValue())
                .withStyle(statusColor(e.getKey())));
        }
        chat(Component.literal("[PreviewTest] done in " + elapsed + "ms").withStyle(ChatFormatting.GRAY));
        return Command.SINGLE_SUCCESS;
    }

    private static boolean matchesFilter(IRecipeCategory<?> cat, String filter) {
        if (filter == null || filter.isEmpty() || "all".equalsIgnoreCase(filter)) return true;
        ResourceLocation uid = cat.getRecipeType().getUid();
        if (filter.equals(uid.toString())) return true;
        if (filter.equals(uid.getNamespace())) return true;
        return false;
    }

    private static <T> TestResult testCategory(IRecipeCategory<T> cat, IRecipeManager mgr, boolean emiAvail) {
        ResourceLocation uid = cat.getRecipeType().getUid();
        TestResult r = new TestResult(uid);
        try {
            // 1. EXPLICIT_UNSUPPORTED check (= ? icon reason 引けたら unsupported)
            if (DIV.credit.client.draft.DraftStore.getUnsupportedReason(uid.toString()) != null) {
                r.status = "SKIP_UNSUPPORTED";
                return r;
            }
            // 2. draft 作成
            RecipeDraft draft = DraftStore.create(cat, DraftStore.CraftingVariant.SHAPED);
            if (draft == null) { r.status = "FAIL_NO_DRAFT"; return r; }
            r.draftClass = draft.getClass().getSimpleName();
            // 3. sample recipe 取得
            Optional<T> sampleOpt = mgr.createRecipeLookup(cat.getRecipeType()).includeHidden().get().findFirst();
            if (sampleOpt.isEmpty()) { r.status = "FAIL_NO_RECIPE"; return r; }
            T sample = sampleOpt.get();
            // 4. loadFromRecipe
            var empty = CraftPatternJeiPlugin.runtime.getJeiHelpers().getFocusFactory().getEmptyFocusGroup();
            IRecipeLayoutDrawable<?> sampleDrawable = mgr.createRecipeLayoutDrawable(cat, sample, empty).orElse(null);
            if (sampleDrawable == null) { r.status = "FAIL_LOAD"; r.note = "sample drawable null"; return r; }
            boolean loaded = draft.loadFromRecipe(sampleDrawable);
            if (!loaded) { r.status = "FAIL_LOAD"; r.note = "loadFromRecipe returned false"; return r; }
            // 5. synth
            net.minecraft.world.item.crafting.Recipe<?> synth = draft.synthesizePreviewRecipe();
            if (synth == null) { r.status = "FAIL_SYNTH"; r.note = "synthesizePreviewRecipe = null"; return r; }
            r.synthClass = synth.getClass().getSimpleName();
            // 6. JEI build
            IRecipeLayoutDrawable<?> jeiDrawable = JeiRenderBridge.build(cat, synth);
            r.jeiOk = (jeiDrawable != null);
            // 7. EMI build (= optional)
            if (emiAvail) {
                try {
                    Object emiRenderable = null; // 1.21: EMI backend (runtime.emi) 未移植 → 常に null
                    r.emiOk = (emiRenderable != null);
                } catch (Throwable t) {
                    r.emiOk = false;
                    r.note = (r.note == null ? "" : r.note + "; ") + "emi-threw=" + t.getClass().getSimpleName();
                }
            }
            // 8. status 判定
            if (r.jeiOk && (!emiAvail || r.emiOk)) r.status = "OK";
            else if (r.emiOk && !r.jeiOk) r.status = "OK_EMI_ONLY";
            else r.status = "FAIL_BOTH";
            return r;
        } catch (Throwable t) {
            r.status = "ERROR";
            r.note = t.getClass().getSimpleName() + ": " + t.getMessage();
            return r;
        }
    }

    private static Path writeOutput(String filter, List<TestResult> results, Map<String, Integer> counts,
                                     long elapsedMs, boolean emiAvail) {
        try {
            Path dir = Paths.get("credit_previewtest");
            Files.createDirectories(dir);
            String ts = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));
            Path file = dir.resolve("preview_" + ts + ".txt");

            StringBuilder out = new StringBuilder();
            out.append("=== credit Preview Construction Test ===\n");
            out.append("date: ").append(LocalDateTime.now()).append("\n");
            out.append("filter: ").append(filter).append("\n");
            out.append("emi available: ").append(emiAvail).append("\n");
            out.append("total categories: ").append(results.size()).append("\n");
            out.append("elapsed: ").append(elapsedMs).append("ms\n\n");

            out.append("== Summary ==\n");
            counts.entrySet().stream()
                .sorted((a, b) -> Integer.compare(b.getValue(), a.getValue()))
                .forEach(e -> out.append("  ").append(e.getKey()).append(": ").append(e.getValue()).append("\n"));
            out.append("\n");

            // group by status for readability
            out.append("== Per-category (grouped by status) ==\n");
            Map<String, List<TestResult>> byStatus = new HashMap<>();
            for (var r : results) byStatus.computeIfAbsent(r.status, k -> new ArrayList<>()).add(r);
            for (var status : byStatus.keySet().stream().sorted().toList()) {
                out.append("\n--- ").append(status).append(" (").append(byStatus.get(status).size()).append(") ---\n");
                for (var r : byStatus.get(status)) {
                    out.append("  ").append(String.format("%-40s", r.uid));
                    out.append(" jei=").append(r.jeiOk ? "ok" : "--");
                    if (emiAvail) out.append(" emi=").append(r.emiOk ? "ok" : "--");
                    if (r.draftClass != null) out.append(" draft=").append(r.draftClass);
                    if (r.synthClass != null) out.append(" synth=").append(r.synthClass);
                    if (r.note != null && !r.note.isEmpty()) out.append(" note=").append(r.note);
                    out.append("\n");
                }
            }

            Files.writeString(file, out.toString(), StandardCharsets.UTF_8);
            return file.toAbsolutePath();
        } catch (IOException e) {
            Credit.LOGGER.error("[C9002] PreviewTest write failed", e);
            return null;
        }
    }

    private static ChatFormatting statusColor(String status) {
        return switch (status) {
            case "OK" -> ChatFormatting.GREEN;
            case "OK_EMI_ONLY" -> ChatFormatting.AQUA;
            case "FAIL_SYNTH" -> ChatFormatting.YELLOW;
            case "FAIL_NO_DRAFT", "SKIP_UNSUPPORTED" -> ChatFormatting.GRAY;
            case "FAIL_NO_RECIPE", "FAIL_LOAD", "FAIL_BOTH" -> ChatFormatting.RED;
            case "ERROR" -> ChatFormatting.DARK_RED;
            default -> ChatFormatting.WHITE;
        };
    }

    private static void chat(Component msg) {
        var p = Minecraft.getInstance().player;
        if (p != null) p.displayClientMessage(msg, false);
    }

    /** 1 category 分の結果。 */
    private static final class TestResult {
        final ResourceLocation uid;
        String status = "?";
        String draftClass;
        String synthClass;
        boolean jeiOk;
        boolean emiOk;
        String note;
        TestResult(ResourceLocation uid) { this.uid = uid; }
    }
}
