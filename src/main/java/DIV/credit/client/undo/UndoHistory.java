package DIV.credit.client.undo;

import DIV.credit.Credit;
import DIV.credit.client.draft.DraftStore;

import java.util.ArrayList;
import java.util.List;

/**
 * Ctrl+Z 用の編集履歴。プレイヤーがワールドを抜けるまで保持。
 *
 * 構造: 時系列の Snapshot リスト + 現在位置を示す pointer。
 * - push: pointer 以降の future 履歴を切り詰めてから新規追加 (= redo path 破棄)
 * - undo: pointer を 1 戻して該当 Snapshot を返す
 * - redo: pointer を 1 進めて該当 Snapshot を返す (Phase 2 用に予約)
 * - clear: 全消去 (world logout で呼ばれる)
 */
public final class UndoHistory {

    public static final int MAX_DEPTH = 50;
    public static final UndoHistory INSTANCE = new UndoHistory();

    private final List<Snapshot> states = new ArrayList<>();
    private int pointer = -1;

    private UndoHistory() {}

    public synchronized void push(Snapshot s) {
        if (s == null) return;
        // Truncate forward history (no redo path after a fresh edit)
        while (states.size() > pointer + 1) states.remove(states.size() - 1);
        states.add(s);
        pointer++;
        // Memory limit
        while (states.size() > MAX_DEPTH) {
            states.remove(0);
            pointer--;
        }
    }

    /** 1 つ前の状態を返す。返却後に pointer は 1 戻る。一番最初なら null。 */
    public synchronized Snapshot undo() {
        if (pointer <= 0) return null;
        pointer--;
        return states.get(pointer);
    }

    /** 1 つ後の状態を返す（Phase 2: Ctrl+Y）。返却後に pointer は 1 進む。 */
    public synchronized Snapshot redo() {
        if (pointer >= states.size() - 1) return null;
        pointer++;
        return states.get(pointer);
    }

    public synchronized void clear() {
        states.clear();
        pointer = -1;
        Credit.LOGGER.info("[CraftPattern] UndoHistory cleared (world logout or explicit reset)");
    }

    public synchronized int size() { return states.size(); }
    public synchronized int pointer() { return pointer; }

    /** 軽量 hash 計算。tick 毎に呼ぶ → 変化検知用。 */
    public static long computeHash(DraftStore store,
                                   DIV.credit.client.screen.BuilderScreen screen) {
        long h = store.getCraftingVariant().hashCode();
        // 選択カテゴリの遷移も undo 対象
        String uid = screen.getCurrentCategoryUid();
        h = h * 31 + (uid == null ? 0 : uid.hashCode());
        // Drafts
        for (var e : store.snapshotDrafts().entrySet()) {
            h = h * 31 + e.getKey().hashCode();
            h = h * 31 + Snapshot.draftHash(e.getValue());
        }
        // Widgets
        var tagBar = screen.getTagBar();
        h = h * 31 + (tagBar.getCfgContent()    == null ? 0 : tagBar.getCfgContent().hashCode());
        h = h * 31 + (tagBar.getFinderSource()  == null ? 0 : tagBar.getFinderSource().hashCode());
        h = h * 31 + tagBar.getBoxValue().hashCode();
        var sb = screen.getStackBuilder();
        h = h * 31 + (sb.getContent() == null ? 0 : sb.getContent().hashCode());
        h = h * 31 + Long.hashCode(sb.getBaseAmount());
        h = h * 31 + sb.getMultiplier();
        // EnergyHelper map (各 int[] の値を含めて hash)
        for (var e : screen.getEnergyHelperStateMap().entrySet()) {
            h = h * 31 + e.getKey().hashCode();
            int[] v = e.getValue();
            h = h * 31 + (v.length >= 1 ? v[0] : 0);
            h = h * 31 + (v.length >= 2 ? v[1] : 0);
        }
        return h;
    }
}
