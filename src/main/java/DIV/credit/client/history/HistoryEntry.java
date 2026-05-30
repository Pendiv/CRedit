package DIV.credit.client.history;

import DIV.credit.client.io.ScriptWriter.OperationKind;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * 1 回の /credit push or 1 ワールドセッションの即時適応の議事録。
 * <p>kind:
 * <ul>
 *   <li>PUSHED    : 1 回の /credit push に対応</li>
 *   <li>IMMEDIATE : 1 world session 内で即時適応された全ての変更を集約</li>
 * </ul>
 */
public final class HistoryEntry {

    /** 履歴の出所種別。STAGED は永続化されない (StagingArea から動的生成)。 */
    public enum Kind { PUSHED, IMMEDIATE, STAGED }

    public final long timestamp;
    public final Kind kind;
    public final List<Item> items;

    public HistoryEntry(long timestamp, Kind kind, List<Item> items) {
        this.timestamp = timestamp;
        this.kind      = kind;
        this.items     = items;
    }

    /** 1 件の push 内の 1 変更。ファイル相対 path + recipe ID + 操作種別 + JEI category UID。 */
    public static final class Item {
        public final OperationKind kind;
        public final String        modid;
        public final String        recipeId;
        @Nullable public final String filePath;  // 書き込み先 (Success/Fallback の場合 set)
        public final boolean       success;
        /** v2.0.7: 元 JEI カテゴリ UID。GT 等の ID prefix 解決用。null 可 (旧 history)。 */
        @Nullable public final String jeiCategoryUid;

        public Item(OperationKind kind, String modid, String recipeId, @Nullable String filePath,
                    boolean success, @Nullable String jeiCategoryUid) {
            this.kind = kind; this.modid = modid; this.recipeId = recipeId;
            this.filePath = filePath; this.success = success;
            this.jeiCategoryUid = jeiCategoryUid;
        }

        /** 旧 signature 互換 (jeiCategoryUid なし)。 */
        public Item(OperationKind kind, String modid, String recipeId, @Nullable String filePath, boolean success) {
            this(kind, modid, recipeId, filePath, success, null);
        }

        public CompoundTag toNbt() {
            CompoundTag t = new CompoundTag();
            t.putString("kind",     kind.name());
            t.putString("modid",    modid);
            t.putString("recipeId", recipeId);
            if (filePath != null) t.putString("filePath", filePath);
            t.putBoolean("success", success);
            if (jeiCategoryUid != null) t.putString("jeiCat", jeiCategoryUid);
            return t;
        }

        @Nullable
        public static Item fromNbt(CompoundTag t) {
            try {
                return new Item(
                    OperationKind.valueOf(t.getString("kind")),
                    t.getString("modid"),
                    t.getString("recipeId"),
                    t.contains("filePath") ? t.getString("filePath") : null,
                    t.getBoolean("success"),
                    t.contains("jeiCat") ? t.getString("jeiCat") : null
                );
            } catch (Exception e) { return null; }
        }
    }

    public CompoundTag toNbt() {
        CompoundTag t = new CompoundTag();
        t.putLong("ts", timestamp);
        t.putString("entryKind", kind.name());
        ListTag list = new ListTag();
        for (Item it : items) list.add(it.toNbt());
        t.put("items", list);
        return t;
    }

    @Nullable
    public static HistoryEntry fromNbt(CompoundTag t) {
        try {
            long ts = t.getLong("ts");
            Kind kind = t.contains("entryKind") ? Kind.valueOf(t.getString("entryKind")) : Kind.PUSHED;
            ListTag list = t.getList("items", Tag.TAG_COMPOUND);
            List<Item> items = new ArrayList<>();
            for (int i = 0; i < list.size(); i++) {
                Item it = Item.fromNbt(list.getCompound(i));
                if (it != null) items.add(it);
            }
            return new HistoryEntry(ts, kind, items);
        } catch (Exception e) { return null; }
    }

    /** 既存 entry に新しい item を追加した copy を返す。IMMEDIATE session の append 用。 */
    public HistoryEntry withAddedItem(Item it) {
        List<Item> next = new ArrayList<>(items.size() + 1);
        next.addAll(items);
        next.add(it);
        return new HistoryEntry(timestamp, kind, next);
    }
}
