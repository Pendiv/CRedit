package DIV.credit.client.draft.gt;

import DIV.credit.client.draft.IngredientSpec;
import DIV.credit.client.draft.RecipeDraft;
import mezz.jei.api.recipe.category.IRecipeCategory;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.crafting.Recipe;
import org.jetbrains.annotations.Nullable;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * GT (GregTechCEu Modern) サポートのゲートウェイ。
 *
 * <p><b>【1.21 移植・一時 stub — 2026-05-31】</b> GregTech は MC 1.21 安定版が未確定のため、
 * 本プロジェクトでは GT 対応を一旦無効化している (ユーザー決定)。 直接 gtceu API を使う実装
 * (recipe probe / 構築 / cleanroom 等) は安全な既定値を返す stub に置換してある。
 * 安定版 gtceu 1.21 が出たら、 1.20.1 の {@code credit/src/.../gt/GTSupport.java} を参照して
 * 直接 API 実装へ復元する (HANDOFF.md の GT 処理手順を参照)。
 *
 * <p>stub の効果: {@link #isGtCategory} が常に false を返すため GT カテゴリは GenericDraft の
 * 汎用 probe → auto-emit → skeleton 経路にフォールバックする。 GT 専用の高品質 emit
 * ({@code event.recipes.gtceu...}) と GT 専用 draft (compressor/assembler) は復元まで出ない。
 */
public final class GTSupport {

    private GTSupport() {}

    /** stub: gtceu 非対応中は常に false (= GT 専用経路に入らない)。 */
    public static boolean isGtCategory(@Nullable IRecipeCategory<?> cat) {
        return false;
    }

    /** stub: GT 専用 draft (GTCompressorDraft/GTAssemblerDraft) は作らない。 */
    @Nullable
    public static RecipeDraft tryCreate(IRecipeCategory<?> cat) {
        return null;
    }

    /** stub: slots 込み GT recipe 構築 (preview 用) は不可 → null。 */
    @Nullable
    public static Recipe<?> tryBuildRecipeWithSlots(ResourceLocation jeiUid, long duration, long eut,
                                                     Map<String, Long> intData, @Nullable String cleanroomName,
                                                     @Nullable IngredientSpec[] slots,
                                                     @Nullable RecipeDraft.SlotKind[] kinds) {
        return null;
    }

    /** stub: GT metadata (duration/EUt/intData) probe は空。 */
    public static GtMetadata probeMetadata(Object sampleRecipe, @Nullable ResourceLocation jeiUid) {
        return GtMetadata.EMPTY;
    }

    /** 旧 API 互換 stub。 */
    public static GtMetadata probeMetadata(Object sampleRecipe) {
        return GtMetadata.EMPTY;
    }

    /** stub: cleanroom 要件 probe は無し。 */
    @Nullable
    public static String probeCleanroom(Object sampleRecipe) {
        return null;
    }

    /** stub: cleanroom 種別一覧は空 (TagBar の dropdown 非表示)。 */
    public static List<String> getAllCleanroomNames() {
        return List.of();
    }

    /** stub: JEI uid → KubeJS recipe 名は path そのまま (GT 復元時に親 type 解決へ戻す)。 */
    public static String resolveKubeJsRecipeName(ResourceLocation jeiUid) {
        return jeiUid.getPath();
    }

    /** GenericDraft が duration/eut/intData を読むための軽量コンテナ (stub でも型は維持)。 */
    public static final class GtMetadata {
        public static final GtMetadata EMPTY = new GtMetadata(0, 0, new LinkedHashMap<>());
        public final long duration;
        public final long eut;
        public final LinkedHashMap<String, Long> intData;
        public GtMetadata(long duration, long eut, LinkedHashMap<String, Long> intData) {
            this.duration = duration;
            this.eut = eut;
            this.intData = intData;
        }
    }
}
