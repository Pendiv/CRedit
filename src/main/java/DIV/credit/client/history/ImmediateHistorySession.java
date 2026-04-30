package DIV.credit.client.history;

import DIV.credit.Credit;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

/**
 * 即時適応 (immediate-apply) を「1 ワールドセッション = 1 HistoryEntry」に集約するための session 管理。
 * <ul>
 *   <li>世界 join で session 開始 (currentEntry=null)</li>
 *   <li>即時適応イベント発生時、currentEntry が null なら新規生成、あれば append</li>
 *   <li>世界 logout で flush — HistoryStore に確定 record</li>
 * </ul>
 * Credit.WorldEvents で SubscribeEvent 経由で起動 / 終了通知を受ける。
 */
public final class ImmediateHistorySession {

    public static final ImmediateHistorySession INSTANCE = new ImmediateHistorySession();

    /** 現在 session の集約中 entry。null = まだ即時適応イベント未発生 or session 未開始。 */
    private HistoryEntry currentEntry;
    /** Store に push 済 entry の id (timestamp で identify)。append 時の差し替え判定に使う。 */
    private long currentEntryTs = 0;

    private ImmediateHistorySession() {}

    /** 即時適応イベントを 1 件追加。current entry に append。HistoryStore も同時に更新。 */
    public synchronized void addItem(HistoryEntry.Item it) {
        if (currentEntry == null) {
            currentEntry = new HistoryEntry(System.currentTimeMillis(),
                HistoryEntry.Kind.IMMEDIATE, java.util.List.of(it));
            currentEntryTs = currentEntry.timestamp;
            HistoryStore.INSTANCE.record(currentEntry);
        } else {
            HistoryEntry updated = currentEntry.withAddedItem(it);
            HistoryStore.INSTANCE.replaceImmediateSession(currentEntryTs, updated);
            currentEntry = updated;
        }
        HistoryPersistence.save();
    }

    /** Session 終了時に呼ぶ。次の即時適応で新 entry を作るよう state リセット。 */
    public synchronized void flush() {
        currentEntry = null;
        currentEntryTs = 0;
    }

    // ─── World join/logout で auto-flush + persistence load ───
    public static final class Hook {
        /** ゲーム起動後 1 回目の world join でだけ load 走らせる (以後の世界入室では in-memory 維持)。 */
        private static boolean firstJoinLoaded = false;

        @SubscribeEvent
        public static void onLoggingIn(ClientPlayerNetworkEvent.LoggingIn event) {
            INSTANCE.flush();
            // v2.2.6: world join 時に staging + history を load
            // → /credit history を BuilderScreen 開かずに直接実行しても空にならない
            if (!firstJoinLoaded) {
                firstJoinLoaded = true;
                DIV.credit.client.staging.StagingPersistence.load();
                HistoryPersistence.load();
                Credit.LOGGER.info("[CraftPattern] Loaded staging + history on world join");
            }
        }
        @SubscribeEvent
        public static void onLoggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
            INSTANCE.flush();
            Credit.LOGGER.debug("[CraftPattern] ImmediateHistorySession reset (world logout)");
        }
    }
}
