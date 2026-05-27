package DIV.credit.client.runtime;

import DIV.credit.client.draft.IngredientSpec;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Optional;

/**
 * recipe viewer (JEI / EMI) backend の統一インターフェース。
 * <p>credit の中核 (= draft / preview / overlay 系) はこのインターフェース経由で viewer と対話する。
 * viewer 固有 API (= mezz.jei.* / dev.emi.*) は backend 実装内部に閉じ込めて、 他箇所に漏らさない。
 *
 * <h3>実装方針</h3>
 * <ul>
 *   <li>{@link #id()} で "jei" / "emi" 等の識別子を返す</li>
 *   <li>{@link #isAvailable()} で「該当 mod が読み込まれてて runtime も準備できてる」 を返す</li>
 *   <li>各 query method は viewer 不在時 (= isAvailable=false) で safe な fallback (= 空 list / null) を返す</li>
 *   <li>backend 固有 cast が必要な場面 (= mod 別 hand-written draft の category 判定等) は
 *       {@link CreditCategory#nativeRef()} を呼出側で instanceof check して取り出す</li>
 * </ul>
 *
 * <h3>Phase 1 仕様</h3>
 * stub backend を 2 個用意。 method 実装は Phase 2/3 で。 Phase 1 では呼ばれない (= 既存 code から
 * 参照されない) ので、 stub は noop でも OK。
 */
public interface CreditRuntimeBackend {

    /** backend 識別子 (= "jei" / "emi")。 log / 設定 / 切替時に使う。 */
    String id();

    /** runtime が利用可能か。 mod 未 load / runtime init 前は false。 */
    boolean isAvailable();

    // ──────────────── Category / Recipe 列挙 ────────────────

    /** 全 recipe category。 viewer 経由で見える category のみ (= EXPLICIT_UNSUPPORTED 等は本層では除外しない)。 */
    List<CreditCategory> getCategories();

    /** category 直下の全 recipe。 順序は viewer の natural order に従う。 */
    List<CreditRecipe> getRecipes(CreditCategory category);

    /** uid で category を探す。 */
    Optional<CreditCategory> findCategory(ResourceLocation uid);

    // ──────────────── Recipe inspection ────────────────

    /**
     * recipe の sample slot を probe。 順序は viewer の addSlot 順 (= category.setRecipe ループ準拠)。
     * cycle ingredient は先頭採用。 backend 固有抽象が違うが結果型は共通。
     */
    List<CreditSlot> probeSlots(CreditRecipe recipe);

    // ──────────────── Search / Hover ────────────────

    /** viewer の検索 box が focus されてるか。 credit の keyboard handler の延長 check 用。 */
    boolean isSearchFocused();

    /** viewer の検索 text 取得 (= 検索 box 非対応 backend 時は ""). */
    String getSearchText();

    /** viewer の検索 text 設定。 検索 box 非対応 backend では noop。 */
    void setSearchText(String text);

    /** screen 座標下の ingredient を返す (= viewer panel 上の ingredient のみ)。 無ければ null。 */
    @Nullable IngredientSpec hoveredIngredient(int mouseX, int mouseY);

    /**
     * viewer が画面下端で占有する領域の上端 Y を返す (= credit UI はこの Y より上に配置すべき)。
     * 例: EMI は検索 bar の上端 Y、 JEI は下端占有なしなので {@code screenHeight} そのまま。
     * <p>BuilderScreen の inventory 位置決めに利用。 default は screenHeight (= 反映なし)。
     */
    default int getBottomReservedTopY(int screenHeight) { return screenHeight; }

    // ──────────────── Navigation ────────────────

    /** spec を生み出す recipe 群を viewer で表示 (= JEI showRecipes / EMI displayRecipes)。 */
    void showRecipesFor(IngredientSpec spec);

    /** spec を消費する recipe 群を viewer で表示 (= JEI showUses / EMI displayUses)。 */
    void showUsesOf(IngredientSpec spec);

    /**
     * category UID 指定で viewer を開く。 成功時 true。 viewer 不在 / category 未登録時 false。
     * BuilderScreen の category tab から「現 category を JEI で開く」 等の遷移で使う。
     */
    default boolean openCategoryByUid(ResourceLocation uid) { return false; }

    /**
     * recipe ID 指定で viewer を開く (= 特定 recipe を viewer で focus 表示)。
     * @param recipeId      target recipe ID
     * @param categoryHint  GT 等で category prefix 推測に使う category UID。 null 可
     * @return 成功時 true
     */
    default boolean openRecipeId(ResourceLocation recipeId, @Nullable String categoryHint) { return false; }

    /**
     * Mek chemical (= Gas/Infusion/Pigment/Slurry) を screen 上の (x, y) 位置に描画。
     * <p>JeiBackend は Mek の JEI ingredient renderer に delegate、 EmiBackend は EMI 経路 (= Phase 4+ で実装)。
     * Mek 未 load / chemical 未認識時は noop。
     */
    default void renderChemical(net.minecraft.client.gui.GuiGraphics g,
                                DIV.credit.client.draft.IngredientSpec.Gas chemical,
                                int x, int y) {}

    // ──────────────── 拡張 (Phase 2 以降で実装が増える可能性) ────────────────

    /**
     * BuilderScreen 等を viewer overlay の exclusion area として登録。
     * viewer の右側 panel が credit UI に重ならないようにする。
     */
    void registerExclusionArea(Class<? extends Screen> screenClass, ExclusionAreaProvider provider);

    /** screen 上の領域提供者。 backend 経由で viewer に渡される。 */
    @FunctionalInterface
    interface ExclusionAreaProvider {
        /** 与えられた screen のうち、 viewer が描画しないべき矩形群。 */
        List<net.minecraft.client.renderer.Rect2i> getAreas(Screen screen);
    }
}
