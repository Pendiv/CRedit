package DIV.credit.client.history;

import DIV.credit.Credit;
import DIV.credit.client.io.ScriptWriter.OperationKind;
import DIV.credit.client.staging.StagedChange;
import net.minecraft.client.Minecraft;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.Tag;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/**
 * v3.10: 1 push = 1 NBT ファイル の payload 永続化。
 *  HistoryStore (= metadata 集約) と分離。 preview 過去世代表示用 lazy load。
 *
 * <h3>ファイル構成</h3>
 * <pre>
 *   &lt;gameDir&gt;/credit/history/push_&lt;ts&gt;.nbt
 *     root:
 *       ts          (long)         — push timestamp (= filename と同値、 sanity check 用)
 *       items       (list of compound)
 *         kind      (string)       — OperationKind name
 *         modid     (string)
 *         recipeId  (string)
 *         codeBody  (string)       — ScriptWriter に渡された JS 本体 (= ImportParser で再解釈可)
 *         imported  (byte)         — 1 = StagedChange.imported=true 由来
 *         jeiCat    (string)       — JEI category UID (= optional)
 * </pre>
 *
 * <h3>cleanup</h3>
 * push 後に save() の末尾で trim() を呼び、 世代数 / サイズ 上限超過分を削除。
 */
public final class PushPayloadStore {

    private PushPayloadStore() {}

    /** 1 件 entry。 復元時に ImportParser.parseSource(codeBody, ...) で ImportedRecipe 列を生成。
     *  v3.13-C: EDIT 用に origRecipe の snapshot を同梱 (= push 後の before 表示用)。
     *  v3.16-C: Draft snapshot を同梱 (= preview で DraftPersistence.applyTo + toRecipeInstance で真 Recipe<?>)。 */
    public record Entry(OperationKind kind, String modid, String recipeId,
                        String codeBody, boolean imported,
                        @Nullable String jeiCategoryUid,
                        @Nullable OrigRecipeSnapshot origSnap,
                        @Nullable CompoundTag draftSnapshot,
                        /** Phase-orig-snap-draft: push 時の origRecipe を loadFromRecipe → DraftPersistence.serializeDraft で snapshot 化したもの。
                         *  Collector で復元 → toRecipeInstance で真の origRecipe<?>。 */
                        @Nullable CompoundTag origDraftSnap) {}

    public static Path rootDir() {
        return Minecraft.getInstance().gameDirectory.toPath()
            .resolve("credit").resolve("history");
    }

    public static Path fileFor(long ts) {
        return rootDir().resolve("push_" + ts + ".nbt");
    }

    /**
     * 1 push 分の StagedChange 列を NBT ファイルに書き出す。 push 完了後 doPush から呼ぶ。
     * @param ts        push timestamp (= HistoryEntry.timestamp と同一)
     * @param committed 書き込み対象の StagedChange 列 (= 直前 push に流された committed のみ)
     */
    public static void save(long ts, List<StagedChange> committed) {
        try {
            CompoundTag root = new CompoundTag();
            root.putLong("ts", ts);
            ListTag list = new ListTag();
            for (StagedChange c : committed) {
                CompoundTag t = new CompoundTag();
                t.putString("kind", c.kind.name());
                t.putString("modid", c.modid != null ? c.modid : "");
                t.putString("recipeId", c.recipeId != null ? c.recipeId : "");
                t.putString("codeBody", c.codeBody != null ? c.codeBody : "");
                t.putByte("imported", (byte) (c.imported ? 1 : 0));
                if (c.jeiCategoryUid != null) t.putString("jeiCat", c.jeiCategoryUid);
                // v3.16-C: after Draft snapshot を同梱 (= preview after Recipe<?> 復元用)
                if (c.draftSnapshot != null) t.put("draftSnap", c.draftSnapshot);
                // v3.13-C / Phase-orig-snap-draft: EDIT で origRecipeId が解決できれば
                //   (a) 簡易 snapshot (= OrigRecipeSnapshot vanilla 限定)
                //   (b) Draft snapshot (= 真 Recipe<?> 復元、 全 mod 対応) を同梱
                if (c.origRecipeId != null) {
                    var mc = net.minecraft.client.Minecraft.getInstance();
                    if (mc != null && mc.level != null) {
                        try {
                            net.minecraft.resources.ResourceLocation rl =
                                new net.minecraft.resources.ResourceLocation(c.origRecipeId);
                            var origRecipe = mc.level.getRecipeManager().byKey(rl).orElse(null);
                            if (origRecipe != null) {
                                var snap = OrigRecipeSnapshot.fromRecipe(origRecipe);
                                if (snap != null) t.put("origSnap", snap.toNbt());
                                // Phase-orig-snap-draft: 既存 Builder の loadFromRecipe + DraftPersistence で
                                //  origRecipe を Draft snapshot 化、 generic に保存
                                CompoundTag origDraftSnap = tryBuildOrigDraftSnap(origRecipe);
                                if (origDraftSnap != null) t.put("origDraftSnap", origDraftSnap);
                            }
                        } catch (Exception e) {
                            DIV.credit.Credit.LOGGER.warn("[C5011] origDraftSnap build failed for {}: {}",
                                c.origRecipeId, e.getMessage());
                        }
                    }
                }
                list.add(t);
            }
            root.put("items", list);

            Path p = fileFor(ts);
            Files.createDirectories(p.getParent());
            File f = p.toFile();
            NbtIo.writeCompressed(root, f);
            long sizeKb = f.length() / 1024 + 1;

            // size gate: 1 push がしきい値超なら削除 → 「preview 不可、 metadata のみ」 フォールバック
            int maxKb = configMaxSizeKb();
            if (maxKb > 0 && sizeKb > maxKb) {
                f.delete();
                Credit.LOGGER.warn("[C501] PushPayload {} ({} KB) exceeds limit {} KB, dropped",
                    f.getName(), sizeKb, maxKb);
                return;
            }

            Credit.LOGGER.info("[CraftPattern] PushPayload saved: {} ({} items, {} KB)",
                f.getName(), committed.size(), sizeKb);

            trim();
        } catch (Exception e) {
            Credit.LOGGER.error("[C5012] PushPayload save failed for ts={}", ts, e);
        }
    }

    /** Phase-orig-snap-draft: origRecipe を JeiRenderBridge.build → loadFromRecipe → DraftPersistence.serializeDraft で Draft snapshot 化。 */
    @Nullable
    private static CompoundTag tryBuildOrigDraftSnap(net.minecraft.world.item.crafting.Recipe<?> origRecipe) {
        try {
            var cat = DIV.credit.client.preview.JeiRenderBridge.findCategoryForRecipe(origRecipe);
            if (cat == null) return null;
            var drawable = DIV.credit.client.preview.JeiRenderBridge.build(cat, origRecipe);
            if (drawable == null) return null;
            var draft = DIV.credit.client.draft.DraftStore.create(cat,
                DIV.credit.client.draft.DraftStore.CraftingVariant.SHAPED);
            if (draft == null) return null;
            boolean loaded = draft.loadFromRecipe(drawable);
            if (!loaded) return null;
            return DIV.credit.client.draft.DraftPersistence.serializeDraft(draft);
        } catch (Exception e) {
            return null;
        }
    }

    /** 過去 push の payload ファイル一覧 (= timestamp 降順、 = 新しい順)。 */
    public static List<Long> availableTimestamps() {
        Path root = rootDir();
        if (!Files.isDirectory(root)) return Collections.emptyList();
        List<Long> out = new ArrayList<>();
        try (Stream<Path> s = Files.list(root)) {
            s.filter(Files::isRegularFile)
             .forEach(p -> {
                 String name = p.getFileName().toString();
                 if (name.startsWith("push_") && name.endsWith(".nbt")) {
                     try {
                         long ts = Long.parseLong(name.substring(5, name.length() - 4));
                         out.add(ts);
                     } catch (NumberFormatException ignored) {}
                 }
             });
        } catch (IOException e) {
            Credit.LOGGER.warn("[C5013] PushPayload list failed: {}", e.getMessage());
        }
        out.sort(Comparator.reverseOrder());
        return out;
    }

    /**
     * 指定 timestamp の payload を読み込む。 不在 / 破損なら空リスト。
     */
    public static List<Entry> load(long ts) {
        File f = fileFor(ts).toFile();
        if (!f.exists()) return Collections.emptyList();
        try {
            CompoundTag root = NbtIo.readCompressed(f);
            ListTag list = root.getList("items", Tag.TAG_COMPOUND);
            List<Entry> out = new ArrayList<>();
            for (int i = 0; i < list.size(); i++) {
                CompoundTag t = list.getCompound(i);
                try {
                    OrigRecipeSnapshot snap = t.contains("origSnap")
                        ? OrigRecipeSnapshot.fromNbt(t.getCompound("origSnap"))
                        : null;
                    CompoundTag draftSnap = t.contains("draftSnap")
                        ? t.getCompound("draftSnap") : null;
                    CompoundTag origDraftSnap = t.contains("origDraftSnap")
                        ? t.getCompound("origDraftSnap") : null;
                    out.add(new Entry(
                        OperationKind.valueOf(t.getString("kind")),
                        t.getString("modid"),
                        t.getString("recipeId"),
                        t.getString("codeBody"),
                        t.getByte("imported") == 1,
                        t.contains("jeiCat") ? t.getString("jeiCat") : null,
                        snap,
                        draftSnap,
                        origDraftSnap
                    ));
                } catch (Exception ignored) {}
            }
            return out;
        } catch (Exception e) {
            Credit.LOGGER.warn("[C5014] PushPayload load failed for {}: {}", f.getName(), e.getMessage());
            return Collections.emptyList();
        }
    }

    /** 世代数上限を超えた古い payload を削除。 size gate と独立。 */
    private static void trim() {
        int max = configMaxGen();
        if (max <= 0) return;
        List<Long> stamps = availableTimestamps();  // 新しい順
        if (stamps.size() <= max) return;
        for (int i = max; i < stamps.size(); i++) {
            File f = fileFor(stamps.get(i)).toFile();
            if (f.exists() && f.delete()) {
                Credit.LOGGER.debug("[CraftPattern] PushPayload trim: removed {}", f.getName());
            }
        }
    }

    private static int configMaxGen() {
        try {
            return DIV.credit.CreditConfig.HISTORY_MAX_PUSH_PAYLOAD_GENERATIONS.get();
        } catch (Exception e) {
            return 20;  // default
        }
    }

    private static int configMaxSizeKb() {
        try {
            return DIV.credit.CreditConfig.HISTORY_MAX_PUSH_PAYLOAD_SIZE_KB.get();
        } catch (Exception e) {
            return 1024;  // default 1 MB
        }
    }
}
