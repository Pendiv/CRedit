package DIV.credit.client.draft.gt;

import java.util.List;

/**
 * GT recipe の input 衝突 pre-check (push 直前の chat WARN 用)。
 *
 * <p><b>【1.21 移植・一時 stub — 2026-05-31】</b> GregTech 非対応中は衝突判定を行わず常に空を返す。
 * {@link DIV.credit.client.screen.BuilderScreen} が呼ぶため class とメソッドは残す。
 * gtceu 1.21 復元時に 1.20.1 実装 (GTRecipe input fingerprint 比較) へ戻す。
 */
public final class GTConflictChecker {

    private GTConflictChecker() {}

    /** stub: 衝突なし扱い (空 list)。 引数 newRecipe は GT 復元まで未使用。 */
    public static List<?> findConflicts(Object newRecipe, int limit) {
        return List.of();
    }
}
