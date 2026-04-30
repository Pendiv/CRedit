package DIV.credit.client.staging;

import DIV.credit.Credit;
import DIV.credit.client.io.ScriptWriter.OperationKind;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * /credit push までキューに積まれた変更の集合。プロセスグローバル singleton。
 * 編集 = 自動 stage、/credit commit で committed=true マーク、/credit push で実ファイル書込 + キュー削除。
 */
public final class StagingArea {

    public static final StagingArea INSTANCE = new StagingArea();

    /** スレッドセーフな順序保持リスト (UI からの toggle と push が並ぶことはまずないが念のため)。 */
    private final List<StagedChange> changes = new CopyOnWriteArrayList<>();

    private StagingArea() {}

    /** 新しい変更を auto-stage (committed=false 状態)。 */
    public StagedChange stage(OperationKind kind, String modid, String recipeId,
                              @Nullable String origRecipeId, String codeBody,
                              @Nullable String jeiCategoryUid) {
        StagedChange c = StagedChange.create(kind, modid, recipeId, origRecipeId, codeBody, jeiCategoryUid);
        changes.add(c);
        Credit.LOGGER.info("[CraftPattern] STAGE {} {} (cat={}, id={})", kind, recipeId, jeiCategoryUid, c.id);
        return c;
    }

    /** 旧 signature 互換用 (jeiCategoryUid なし)。 */
    public StagedChange stage(OperationKind kind, String modid, String recipeId,
                              @Nullable String origRecipeId, String codeBody) {
        return stage(kind, modid, recipeId, origRecipeId, codeBody, null);
    }

    public List<StagedChange> all() {
        return new ArrayList<>(changes);
    }

    public List<StagedChange> committedOnly() {
        List<StagedChange> result = new ArrayList<>();
        for (StagedChange c : changes) if (c.committed) result.add(c);
        return result;
    }

    public List<StagedChange> uncommittedOnly() {
        List<StagedChange> result = new ArrayList<>();
        for (StagedChange c : changes) if (!c.committed) result.add(c);
        return result;
    }

    public int size() { return changes.size(); }

    public int committedCount() {
        int n = 0;
        for (StagedChange c : changes) if (c.committed) n++;
        return n;
    }

    /** UI から approve / reject。reject は削除、approve は committed=true。 */
    public void setCommitted(String id, boolean committed) {
        for (StagedChange c : changes) {
            if (c.id.equals(id)) { c.committed = committed; return; }
        }
    }

    public void remove(String id) {
        changes.removeIf(c -> c.id.equals(id));
    }

    /** push 完了後の cleanup: committed のみ消す。reject されてないが未承認の uncommitted は残す。 */
    public void removeAllCommitted() {
        changes.removeIf(c -> c.committed);
    }

    public void clear() {
        changes.clear();
    }

    /** Persistence からの復元用：内部リスト直接置換。 */
    public void replaceAll(List<StagedChange> restored) {
        changes.clear();
        changes.addAll(restored);
    }
}
