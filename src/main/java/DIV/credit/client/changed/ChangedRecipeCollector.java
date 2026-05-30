package DIV.credit.client.changed;

import DIV.credit.Credit;
import DIV.credit.CreditConfig;
import DIV.credit.client.importer.ImportParser;
import DIV.credit.client.importer.ImportedRecipe;
import DIV.credit.client.importer.ImportedRecipeRecovery;
import DIV.credit.client.io.ScriptWriter.OperationKind;
import DIV.credit.client.preview.JeiRenderBridge;
import DIV.credit.client.staging.StagedChange;
import DIV.credit.client.staging.StagingArea;
import mezz.jei.api.recipe.category.IRecipeCategory;
import net.minecraft.client.Minecraft;
import net.minecraft.world.item.crafting.Recipe;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * v3.1 (S-changed): 「credit が変更を加えた ADD レシピ」 を 2 系統から集めて
 * {@link IRecipeCategory} 別にグループ化する utility。
 *
 * <h3>収集 source</h3>
 * <ul>
 *   <li>{@link StagingArea} の {@link OperationKind#ADD} (= 未 push の draft 由来 + import 由来)</li>
 *   <li>{@code kubejs/server_scripts/generated/&lt;modid&gt;/imported_*.js} 内の ADD</li>
 * </ul>
 *
 * <h3>パイプライン</h3>
 * <ol>
 *   <li>{@link ImportParser} で codeBody / .js を ImportedRecipe 列に</li>
 *   <li>{@link ImportedRecipeRecovery#recoverRecipe} で Recipe&lt;?&gt; 復元</li>
 *   <li>{@link JeiRenderBridge#findCategoryForRecipe} で JEI category 解決</li>
 *   <li>category 別 grouping (= {@link LinkedHashMap} 順序維持)</li>
 * </ol>
 *
 * 失敗 entry (= recover null / category 解決失敗) は silent skip + log debug。
 */
public final class ChangedRecipeCollector {

    private ChangedRecipeCollector() {}

    /** v3.5: source 区別タグ — user 編集 由来か import 由来か。 */
    public enum SourceTag { USER, IMPORT }

    /**
     * v3.11: Screen の view モード。 1 dropdown 4 値で「kind × source」 を網羅。
     *  USER_ADD    = ユーザー編集 由来の追加
     *  USER_EDIT   = ユーザー編集 由来の編集
     *  IMPORT_ADD  = import 由来の追加
     *  IMPORT_EDIT = import 由来の編集
     */
    public enum ViewMode {
        USER_ADD, USER_EDIT, IMPORT_ADD, IMPORT_EDIT;

        public DIV.credit.client.io.ScriptWriter.OperationKind kind() {
            return (this == USER_EDIT || this == IMPORT_EDIT)
                ? DIV.credit.client.io.ScriptWriter.OperationKind.EDIT
                : DIV.credit.client.io.ScriptWriter.OperationKind.ADD;
        }

        /** SourceTag を残すべきか判定。 */
        public boolean acceptsTag(SourceTag tag) {
            if (tag == null) return false;
            boolean wantImport = (this == IMPORT_ADD || this == IMPORT_EDIT);
            return wantImport == (tag == SourceTag.IMPORT);
        }
    }

    /**
     * v3.2/v3.5/v3.12: 1 件分の collect 結果。
     *  recipe / category は mod recipe (= ADD かつ Recovery 不可) で null になることあり → placeholder 描画。
     */
    public record Item(ImportedRecipe imported,
                       @org.jetbrains.annotations.Nullable Recipe<?> recipe,
                       @org.jetbrains.annotations.Nullable IRecipeCategory<?> category,
                       @org.jetbrains.annotations.Nullable Recipe<?> origRecipe,
                       SourceTag sourceTag) {}

    /** Phase 3 series: collect 中間 — ImportedRecipe + source tag + origSnap + draftSnap + origDraftSnap。
     *  staging 経路: origSnap/origDraftSnap=null、 draftSnap=stage 時の Draft snapshot。
     *  payload 経路: 全フィールド push 時保存。 */
    private record Tagged(ImportedRecipe recipe, SourceTag tag,
                          @org.jetbrains.annotations.Nullable
                          DIV.credit.client.history.OrigRecipeSnapshot origSnap,
                          @org.jetbrains.annotations.Nullable
                          net.minecraft.nbt.CompoundTag draftSnap,
                          @org.jetbrains.annotations.Nullable
                          String jeiCategoryUid,
                          @org.jetbrains.annotations.Nullable
                          net.minecraft.nbt.CompoundTag origDraftSnap) {}

    /**
     * v3.6: kind 指定で 全件 (= 未 push view) を収集。 = collect(kind, null)。
     */
    public static Map<IRecipeCategory<?>, List<Item>> collect(OperationKind kind) {
        return collect(kind, null);
    }

    /**
     * v3.10: 時間軸付きで収集。
     * @param kind    OperationKind.ADD / EDIT 等
     * @param pushTs  null なら 未 push view (= staging + generated/ 現状)、
     *                non-null なら 過去 push の payload から復元 (= PushPayloadStore)
     */
    public static Map<IRecipeCategory<?>, List<Item>> collect(OperationKind kind,
            @org.jetbrains.annotations.Nullable Long pushTs) {
        List<Tagged> all = new ArrayList<>();
        int stagingTotal = 0, stagingSize = 0, fromPayload = 0;
        if (pushTs == null) {
            // v3.11: 「未 push」 は staging のみ。 generated/ scan は廃止 (累積=過去push payload 経由)。
            stagingTotal = StagingArea.INSTANCE.all().size();
            List<Tagged> fromStaging = collectFromStaging();
            stagingSize = fromStaging.size();
            all.addAll(fromStaging);
        } else {
            List<Tagged> fp = collectFromPushPayload(pushTs);
            fromPayload = fp.size();
            all.addAll(fp);
        }

        int matchedKind = 0, recoverFail = 0, catFail = 0, ok = 0, placeholder = 0;
        Map<IRecipeCategory<?>, List<Item>> map = new LinkedHashMap<>();
        // v3.15-A: mod recipe も「Mod」 一括 bucket 廃止 → recipeType key から JEI category 直接 lookup。
        // category 解決できれば通常 bucket に入れる (recipe=null だが Screen 側で placeholder plate 描画)。
        // どうしても category 解決失敗 (= unknown type) のみ null bucket に fallback。
        List<Item> placeholders = new ArrayList<>();
        for (Tagged t : all) {
            ImportedRecipe r = t.recipe();
            if (r.kind() != kind) continue;
            matchedKind++;
            boolean isModRecipe = r.recipeType() != null && r.recipeType().contains(".");
            Recipe<?> origRecipe = null;
            if (kind == OperationKind.EDIT && r.origRecipeId() != null) {
                // Phase-orig-snap-draft: origDraftSnap (Draft snapshot) → 真の Recipe<?> を最優先
                if (t.origDraftSnap() != null && t.jeiCategoryUid() != null) {
                    try {
                        var origCat = JeiRenderBridge.findCategoryByUid(
                            net.minecraft.resources.ResourceLocation.parse(t.jeiCategoryUid()));
                        if (origCat != null) {
                            var origDraft = DIV.credit.client.draft.DraftStore.create(origCat,
                                DIV.credit.client.draft.DraftStore.CraftingVariant.SHAPED);
                            if (origDraft != null) {
                                DIV.credit.client.draft.DraftPersistence.applyTo(origDraft, t.origDraftSnap());
                                origRecipe = origDraft.toRecipeInstance();
                            }
                        }
                    } catch (Exception e) {
                        Credit.LOGGER.warn("[C6002] origDraftSnap → recipe failed for {}: {}",
                            r.origRecipeId(), e.getMessage());
                    }
                }
                // fallback: 旧 OrigRecipeSnapshot (vanilla 簡易) → 現行 RecipeManager lookup
                if (origRecipe == null && t.origSnap() != null) origRecipe = t.origSnap().toRecipe();
                if (origRecipe == null) origRecipe = lookupRecipeById(r.origRecipeId());
            }

            // v3.16-D: draftSnapshot があれば最優先で真 Recipe<?> 復元 (= CraftPattern 描画と完全同一)
            if (t.draftSnap() != null) {
                IRecipeCategory<?> cat = null;
                if (t.jeiCategoryUid() != null) {
                    try {
                        cat = JeiRenderBridge.findCategoryByUid(net.minecraft.resources.ResourceLocation.parse(t.jeiCategoryUid()));
                    } catch (Exception ignored) {}
                }
                if (cat == null && isModRecipe) cat = resolveModCategory(r.recipeType());
                if (cat != null) {
                    try {
                        DIV.credit.client.draft.RecipeDraft d =
                            DIV.credit.client.draft.DraftStore.create(cat,
                                DIV.credit.client.draft.DraftStore.CraftingVariant.SHAPED);
                        if (d != null) {
                            DIV.credit.client.draft.DraftPersistence.applyTo(d, t.draftSnap());
                            Recipe<?> recipe = d.toRecipeInstance();
                            if (recipe != null) {
                                map.computeIfAbsent(cat, k -> new ArrayList<>())
                                    .add(new Item(r, recipe, cat, origRecipe, t.tag()));
                                ok++;
                                continue;
                            }
                        }
                    } catch (Exception e) {
                        Credit.LOGGER.warn("[C6003] draftSnap → recipe failed for {} ({}): {}",
                            r.recipeId(), t.jeiCategoryUid(), e.getMessage());
                    }
                }
                // snapshot 経路失敗 → 既存 path に fallthrough
            }

            if (isModRecipe) {
                // mod recipe: recipeType key (= "modid.type") を ResourceLocation 経由で category lookup
                IRecipeCategory<?> cat = resolveModCategory(r.recipeType());
                if (cat == null) {
                    placeholders.add(new Item(r, null, null, origRecipe, t.tag()));
                    placeholder++;
                    continue;
                }
                // recipe=null だが category 解決済 → 通常 bucket に
                map.computeIfAbsent(cat, k -> new ArrayList<>())
                    .add(new Item(r, null, cat, origRecipe, t.tag()));
                placeholder++;  // count 上は placeholder (= drawable 未復元) 扱い
                continue;
            }

            // vanilla 経路 (= 旧 codeBody Recovery、 snapshot 無し or fallback)
            Recipe<?> recipe = ImportedRecipeRecovery.recoverRecipe(r);
            if (recipe == null) { recoverFail++; continue; }
            IRecipeCategory<?> cat = JeiRenderBridge.findCategoryForRecipe(recipe);
            if (cat == null) { catFail++; continue; }
            map.computeIfAbsent(cat, k -> new ArrayList<>())
                .add(new Item(r, recipe, cat, origRecipe, t.tag()));
            ok++;
        }
        // unknown type の mod recipe のみ null bucket (= 旧 「Mod」 一括) に集積
        if (!placeholders.isEmpty()) {
            map.put(null, placeholders);
        }
        Credit.LOGGER.info("[CraftPattern] ChangedRecipeCollector (kind={}, pushTs={}): "
                + "stagingTotal={}, stagingAfterFilter={}, fromPayload={}, "
                + "matchedKind={}, recoverFail={}, catFail={}, placeholder={}, ok={} → {} categories",
            kind, pushTs, stagingTotal, stagingSize, fromPayload,
            matchedKind, recoverFail, catFail, placeholder, ok, map.size());
        return map;
    }

    /** v3.15-A: mod recipe の recipeType key (= "modid.type") を ResourceLocation(modid, type) に変換 →
     *  JeiRenderBridge.findCategoryByUid で category 直接 lookup。 失敗時 null。 */
    @org.jetbrains.annotations.Nullable
    private static IRecipeCategory<?> resolveModCategory(String recipeTypeKey) {
        if (recipeTypeKey == null) return null;
        int dot = recipeTypeKey.indexOf('.');
        if (dot <= 0 || dot >= recipeTypeKey.length() - 1) return null;
        String modid = recipeTypeKey.substring(0, dot);
        String type  = recipeTypeKey.substring(dot + 1);
        try {
            net.minecraft.resources.ResourceLocation uid =
                net.minecraft.resources.ResourceLocation.fromNamespaceAndPath(modid, type);
            return JeiRenderBridge.findCategoryByUid(uid);
        } catch (Exception e) {
            return null;
        }
    }

    /** v3.10/v3.13-C: PushPayloadStore から復元。 各 entry の codeBody を ImportParser.parseSource で解釈、
     *  Entry の origSnap を Tagged に同梱して push 後でも before recipe を復元可能に。 */
    private static List<Tagged> collectFromPushPayload(long ts) {
        List<Tagged> out = new ArrayList<>();
        for (DIV.credit.client.history.PushPayloadStore.Entry e :
                DIV.credit.client.history.PushPayloadStore.load(ts)) {
            SourceTag tag = e.imported() ? SourceTag.IMPORT : SourceTag.USER;
            for (ImportedRecipe ir : ImportParser.parseSource(e.codeBody(), null, e.modid())) {
                out.add(new Tagged(ir, tag, e.origSnap(), e.draftSnapshot(), e.jeiCategoryUid(), e.origDraftSnap()));
            }
        }
        return out;
    }

    /**
     * v3.6: StagingArea 全件を tag 付きで返す (= filter は Screen 側)。
     */
    private static List<Tagged> collectFromStaging() {
        List<Tagged> out = new ArrayList<>();
        int parsedZero = 0;
        for (StagedChange c : StagingArea.INSTANCE.all()) {
            SourceTag tag = c.imported ? SourceTag.IMPORT : SourceTag.USER;
            List<ImportedRecipe> parsed = ImportParser.parseSource(c.codeBody, null, c.modid);
            if (parsed.isEmpty()) {
                parsedZero++;
                Credit.LOGGER.debug("[CraftPattern] Collector staging parsedZero for kind={}, recipeId={}, codeBody head={}",
                    c.kind, c.recipeId,
                    c.codeBody != null && c.codeBody.length() > 60 ? c.codeBody.substring(0, 60) : c.codeBody);
            }
            for (ImportedRecipe ir : parsed) out.add(new Tagged(ir, tag, null, c.draftSnapshot, c.jeiCategoryUid, null));
        }
        Credit.LOGGER.debug("[CraftPattern] Collector staging scan: parsedZero={}, parsedOk={}",
            parsedZero, out.size());
        return out;
    }

    /**
     * v3.6: generated/ 配下の .js 全件を tag 付きで返す (= backup 除外)。
     *  filename prefix が imported_ なら IMPORT、 それ以外は USER。
     */
    private static List<Tagged> collectFromFiles() {
        List<Tagged> out = new ArrayList<>();
        Path root = resolveGeneratedRoot();
        if (root == null || !Files.isDirectory(root)) return out;
        try (Stream<Path> s = Files.walk(root)) {
            s.filter(Files::isRegularFile)
             .filter(p -> {
                 String name = p.getFileName().toString().toLowerCase();
                 if (!name.endsWith(".js")) return false;
                 if (isInsideBackup(root, p)) return false;
                 return true;
             })
             .forEach(p -> {
                 // v3.5: filename prefix → SourceTag。 imported_*.js は IMPORT、 それ以外は USER。
                 boolean isImportedFile = p.getFileName().toString().toLowerCase().startsWith("imported_");
                 SourceTag tag = isImportedFile ? SourceTag.IMPORT : SourceTag.USER;
                 for (ImportedRecipe ir : ImportParser.parseFile(p, root)) {
                     out.add(new Tagged(ir, tag, null, null, null, null));
                 }
             });
        } catch (IOException e) {
            Credit.LOGGER.warn("[C6004] ChangedRecipeCollector: file scan failed: {}", e.getMessage());
        }
        return out;
    }

    /** reconstructed_backup 配下なら true (新旧 prefix 両対応)。 */
    private static boolean isInsideBackup(Path root, Path file) {
        Path rel;
        try { rel = root.relativize(file); } catch (Exception e) { return false; }
        for (int i = 0; i < rel.getNameCount(); i++) {
            String n = rel.getName(i).toString();
            if (n.startsWith("reconstructed_backup_") || n.startsWith(".reconstructed_backup_")) return true;
        }
        return false;
    }

    /** v3.2: recipe id 文字列から client RecipeManager 経由で Recipe<?> を取得。 失敗時 null。 */
    @org.jetbrains.annotations.Nullable
    private static Recipe<?> lookupRecipeById(String idStr) {
        try {
            var rl = net.minecraft.resources.ResourceLocation.parse(idStr);
            var mc = Minecraft.getInstance();
            if (mc.level == null) {
                Credit.LOGGER.debug("[CraftPattern] lookupRecipeById: mc.level=null for {}", idStr);
                return null;
            }
            var opt = mc.level.getRecipeManager().byKey(rl);
            if (opt.isEmpty()) {
                Credit.LOGGER.debug("[CraftPattern] lookupRecipeById: not found in RecipeManager: {}", idStr);
            } else {
                Credit.LOGGER.debug("[CraftPattern] lookupRecipeById: ok ({}) = {}",
                    idStr, opt.get().getClass().getSimpleName());
            }
            return opt.map(h -> h.value()).orElse(null);
        } catch (Exception e) {
            Credit.LOGGER.debug("[CraftPattern] lookupRecipeById: exception for {}: {}", idStr, e.getMessage());
            return null;
        }
    }

    /** ScriptWriter と同じロジックで dump_root/generated を求める。 */
    private static Path resolveGeneratedRoot() {
        try {
            String root = CreditConfig.DUMP_ROOT.get();
            if (root == null || root.isBlank()) root = "kubejs/server_scripts";
            if (root.endsWith("/") || root.endsWith("\\")) root = root.substring(0, root.length() - 1);
            return Minecraft.getInstance().gameDirectory.toPath().resolve(root + "/generated");
        } catch (Exception e) {
            return Minecraft.getInstance().gameDirectory.toPath().resolve("kubejs/server_scripts/generated");
        }
    }
}
