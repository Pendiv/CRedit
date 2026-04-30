package DIV.credit.client.history;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * push 履歴のリングバッファ singleton。新しいものが先頭。
 * 上限 200 件で古いものから drop。EditPersistence MAX 時のみ history.dat に永続化される。
 */
public final class HistoryStore {

    public static final HistoryStore INSTANCE = new HistoryStore();
    public static final int MAX_ENTRIES = 200;

    private final LinkedList<HistoryEntry> entries = new LinkedList<>();
    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();

    private HistoryStore() {}

    public void record(HistoryEntry entry) {
        lock.writeLock().lock();
        try {
            entries.addFirst(entry);
            int max = effectiveMax();
            if (max > 0) {
                while (entries.size() > max) entries.removeLast();
            }
        } finally {
            lock.writeLock().unlock();
        }
    }

    /** ImmediateHistorySession 用: 同一 timestamp の既存 entry を新 entry で置換。 */
    public void replaceImmediateSession(long timestamp, HistoryEntry replacement) {
        lock.writeLock().lock();
        try {
            for (int i = 0; i < entries.size(); i++) {
                if (entries.get(i).timestamp == timestamp
                    && entries.get(i).kind == HistoryEntry.Kind.IMMEDIATE) {
                    entries.set(i, replacement);
                    return;
                }
            }
            // 見つからなければ新規追加
            entries.addFirst(replacement);
        } finally {
            lock.writeLock().unlock();
        }
    }

    /** CreditConfig.HISTORY_MAX 値。-1 (UNLIMITED) → 上限なし。 */
    private int effectiveMax() {
        try {
            int v = DIV.credit.CreditConfig.HISTORY_MAX.get().limit;
            return v;  // -1 で trim しない、1+ で trim
        } catch (Exception e) {
            return MAX_ENTRIES;  // 設定読めなければ default
        }
    }

    public List<HistoryEntry> all() {
        lock.readLock().lock();
        try { return new ArrayList<>(entries); } finally { lock.readLock().unlock(); }
    }

    public int size() {
        lock.readLock().lock();
        try { return entries.size(); } finally { lock.readLock().unlock(); }
    }

    public void clear() {
        lock.writeLock().lock();
        try { entries.clear(); } finally { lock.writeLock().unlock(); }
    }

    public void replaceAll(List<HistoryEntry> restored) {
        lock.writeLock().lock();
        try {
            entries.clear();
            for (HistoryEntry e : restored) entries.add(e);
            while (entries.size() > MAX_ENTRIES) entries.removeLast();
        } finally {
            lock.writeLock().unlock();
        }
    }
}
