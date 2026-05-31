package DIV.credit.client.runtime;

import DIV.credit.Credit;
import net.neoforged.fml.ModList;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * active な {@link CreditRuntimeBackend} を保持する singleton。
 * <p>FMLClientSetupEvent から {@link #init()} 呼出で初期化。
 * 検出順序 (= EMI 優先、 default 設定 AUTO 時):
 * <ol>
 *   <li>EMI loaded → EmiBackend</li>
 *   <li>JEI loaded → JeiBackend</li>
 *   <li>どちらも無し → noop backend (= 全 method 空返却)</li>
 * </ol>
 * 将来 CreditConfig の `activeBackend` 設定 (= AUTO/JEI/EMI) で override 可能化予定。
 */
public final class BackendRegistry {

    private BackendRegistry() {}

    @Nullable private static CreditRuntimeBackend active;
    private static final List<CreditRuntimeBackend> all = new ArrayList<>();

    /** 初期化済か (= init 二重呼出防止)。 */
    private static boolean initialized = false;

    /**
     * client setup phase から 1 回だけ呼出。 mod load 状況で backend を選定。
     * 呼出位置: {@code Credit.java} or {@code RuntimeViewerCheck.onClientSetup} 経由。
     */
    public static synchronized void init() {
        if (initialized) return;
        initialized = true;

        boolean hasJei = ModList.get().isLoaded("jei");
        boolean hasEmi = ModList.get().isLoaded("emi");

        // backend 候補登録 (= 利用可能なら instance 生成)
        if (hasEmi) {
            all.add(new DIV.credit.client.runtime.emi.EmiBackend());
        }

        if (hasJei) {
            all.add(new DIV.credit.client.runtime.jei.JeiBackend());
        }

        // active 選定 (= EMI 優先、 でなければ JEI、 でなければ noop)
        for (CreditRuntimeBackend b : all) {
            if ("emi".equals(b.id()) && b.isAvailable()) { active = b; break; }
        }
        if (active == null) {
            for (CreditRuntimeBackend b : all) {
                if ("jei".equals(b.id()) && b.isAvailable()) { active = b; break; }
            }
        }
        if (active == null) {
            active = NoopBackend.INSTANCE;
        }
        Credit.LOGGER.info("[CraftPattern] BackendRegistry: active = {} (JEI={}, EMI={})",
            active.id(), hasJei, hasEmi);
    }

    /** active backend 取得。 init 前 呼出時は noop backend を返す (= NPE 回避)。 */
    public static CreditRuntimeBackend active() {
        if (active == null) return NoopBackend.INSTANCE;
        return active;
    }

    /** 登録済全 backend (= switcher UI 用)。 */
    public static List<CreditRuntimeBackend> all() {
        return Collections.unmodifiableList(all);
    }

    /** ID 指定で backend 切替 (= 設定 UI 経由)。 該当無しなら現行維持。 */
    public static synchronized void setActive(String id) {
        for (CreditRuntimeBackend b : all) {
            if (b.id().equals(id) && b.isAvailable()) {
                active = b;
                Credit.LOGGER.info("[CraftPattern] BackendRegistry: switched active to {}", id);
                return;
            }
        }
        Credit.LOGGER.warn("[C902] BackendRegistry: setActive({}) — not available", id);
    }
}
