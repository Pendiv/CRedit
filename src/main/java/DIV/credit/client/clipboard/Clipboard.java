package DIV.credit.client.clipboard;

import DIV.credit.CreditConfig;
import DIV.credit.client.draft.IngredientSpec;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * v3.0.1: Ctrl+C / Ctrl+V 用クリップボード履歴。
 * <p>最大 100 件のリングバッファ + cursor (= 現在 paste 対象 = 矢印キーで動く)。
 * cursor=0 が top (= 最新)、 size-1 が末尾 (= 最古)。
 * <p>top と同じ内容を push しようとすると skip (= Ctrl+C 連打 / 長押し対策)。
 * <p>{@link CreditConfig#CLIPBOARD_MULTI} OFF の時は単一スロット動作 (= push で常に [0] 上書き、 cursor 移動無効)。
 */
public final class Clipboard {

    public static final Clipboard INSTANCE = new Clipboard();
    public static final int MAX_ENTRIES = 100;

    private final Deque<IngredientSpec> entries = new ArrayDeque<>();
    private int cursor = 0;

    private Clipboard() {}

    public synchronized void push(IngredientSpec spec) {
        if (spec == null || spec.isEmpty()) return;
        boolean multi = safeMulti();
        IngredientSpec top = entries.peekFirst();
        if (top != null && sameContent(top, spec)) {
            cursor = 0;
            return;
        }
        if (multi) {
            entries.addFirst(spec);
            while (entries.size() > MAX_ENTRIES) entries.pollLast();
        } else {
            entries.clear();
            entries.addFirst(spec);
        }
        cursor = 0;
    }

    /** cursor 位置の ingredient。 空なら null。 */
    @Nullable
    public synchronized IngredientSpec current() {
        if (entries.isEmpty()) return null;
        int idx = Math.max(0, Math.min(cursor, entries.size() - 1));
        int i = 0;
        for (IngredientSpec s : entries) {
            if (i == idx) return s;
            i++;
        }
        return null;
    }

    /**
     * cursor を delta ぶん移動。 範囲外は clamp。
     * 矢印 ↑ = 新しい方向 (= cursor 減らす)、 ↓ = 古い方向 (= cursor 増やす)。
     */
    public synchronized void moveCursor(int delta) {
        if (!safeMulti()) return;
        if (entries.isEmpty()) return;
        cursor = Math.max(0, Math.min(cursor + delta, entries.size() - 1));
    }

    public synchronized int size() {
        return entries.size();
    }

    public synchronized int cursor() {
        return entries.isEmpty() ? -1 : Math.min(cursor, entries.size() - 1);
    }

    /** TRANSIENT モードで BuilderScreen 閉じる時等に呼ぶ。 */
    public synchronized void clear() {
        entries.clear();
        cursor = 0;
    }

    /** 永続化用 snapshot (top → bottom 順)。 */
    public synchronized java.util.List<IngredientSpec> snapshot() {
        return new java.util.ArrayList<>(entries);
    }

    /** 永続化からの復元。 entries は top → bottom 順で渡す。 */
    public synchronized void restore(java.util.List<IngredientSpec> list) {
        entries.clear();
        for (IngredientSpec s : list) {
            if (s != null && !s.isEmpty()) entries.addLast(s);
            if (entries.size() >= MAX_ENTRIES) break;
        }
        cursor = 0;
    }

    /** 現 cursor の ingredient を新しい spec で差し替え (= 右クリック count/amount 増減用)。 */
    public synchronized void replaceCurrent(IngredientSpec newSpec) {
        if (newSpec == null) return;
        int size = entries.size();
        if (size == 0) {
            if (!newSpec.isEmpty()) {
                entries.addFirst(newSpec);
                cursor = 0;
            }
            return;
        }
        int idx = Math.max(0, Math.min(cursor, size - 1));
        java.util.List<IngredientSpec> all = new java.util.ArrayList<>(entries);
        all.set(idx, newSpec);
        entries.clear();
        entries.addAll(all);
    }

    /** Item NBT + Tag/Fluid id + count まで同一なら true (= push skip 判定)。 */
    private static boolean sameContent(IngredientSpec a, IngredientSpec b) {
        if (a == null || b == null) return a == b;
        IngredientSpec au = a.unwrap();
        IngredientSpec bu = b.unwrap();
        if (au.getClass() != bu.getClass()) return false;
        if (au instanceof IngredientSpec.Item ai && bu instanceof IngredientSpec.Item bi) {
            return net.minecraft.world.item.ItemStack.matches(ai.stack(), bi.stack());
        }
        if (au instanceof IngredientSpec.Tag at && bu instanceof IngredientSpec.Tag bt) {
            return java.util.Objects.equals(at.tagId(), bt.tagId()) && at.count() == bt.count();
        }
        if (au instanceof IngredientSpec.Fluid af && bu instanceof IngredientSpec.Fluid bf) {
            return af.stack().isFluidEqual(bf.stack()) && af.stack().getAmount() == bf.stack().getAmount();
        }
        if (au instanceof IngredientSpec.FluidTag aft && bu instanceof IngredientSpec.FluidTag bft) {
            return java.util.Objects.equals(aft.tagId(), bft.tagId()) && aft.amount() == bft.amount();
        }
        if (au instanceof IngredientSpec.Gas ag && bu instanceof IngredientSpec.Gas bg) {
            return java.util.Objects.equals(ag.gasId(), bg.gasId())
                && ag.amount() == bg.amount()
                && ag.chemicalType() == bg.chemicalType();
        }
        return false;
    }

    private static boolean safeMulti() {
        try { return CreditConfig.CLIPBOARD_MULTI.get(); } catch (Exception e) { return true; }
    }
}
