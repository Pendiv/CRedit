package DIV.credit.client.draft;

import mezz.jei.api.constants.RecipeTypes;
import mezz.jei.api.recipe.RecipeType;
import mezz.jei.api.recipe.category.IRecipeCategory;
import net.minecraft.nbt.CompoundTag;
import net.minecraftforge.fml.ModList;
import org.jetbrains.annotations.Nullable;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * カテゴリごとに Draft を保持。サポート対象なら自動生成、未対応なら null。
 * minecraft:crafting だけは shaped / shapeless を別エントリで保持して切替で消えないようにする。
 */
public class DraftStore {

    public enum CraftingVariant { SHAPED, SHAPELESS }

    private final Map<String, RecipeDraft> drafts = new HashMap<>();
    /** MAX persistence で復元待ちの未マテリアライズ draft 状態。getOrCreate で取り出し apply する。 */
    private final Map<String, CompoundTag> pendingState = new HashMap<>();
    private CraftingVariant craftingVariant = CraftingVariant.SHAPED;

    public CraftingVariant getCraftingVariant()             { return craftingVariant; }
    public void            setCraftingVariant(CraftingVariant v) { this.craftingVariant = v; }

    @Nullable
    public RecipeDraft getOrCreate(IRecipeCategory<?> cat) {
        if (cat == null) return null;
        String key = keyFor(cat);
        RecipeDraft existing = drafts.get(key);
        if (existing != null) {
            if (existing instanceof CraftingDraft cd) {
                cd.setMode(craftingVariant == CraftingVariant.SHAPED
                    ? CraftingDraft.Mode.SHAPED : CraftingDraft.Mode.SHAPELESS);
            }
            return existing;
        }
        RecipeDraft fresh = create(cat);
        if (fresh != null) {
            drafts.put(key, fresh);
            CompoundTag pending = pendingState.remove(key);
            if (pending != null) {
                DIV.credit.client.draft.DraftPersistence.applyTo(fresh, pending);
            }
        }
        return fresh;
    }

    /** すべての draft と未マテリアライズ状態を破棄。MIN モード screen close 時に使う。 */
    public void clear() {
        drafts.clear();
        pendingState.clear();
    }

    /** MAX persistence の保存対象スナップショット。 */
    public Map<String, RecipeDraft> snapshotDrafts() {
        return Collections.unmodifiableMap(drafts);
    }

    /** まだマテリアライズしていない pending state も保存対象に含めるためのスナップショット。 */
    public Map<String, CompoundTag> snapshotPending() {
        return Collections.unmodifiableMap(pendingState);
    }

    /** 復元時に DraftPersistence から読み込んだ生 NBT をセット。 */
    public void setPendingState(Map<String, CompoundTag> state) {
        pendingState.clear();
        pendingState.putAll(state);
    }

    private String keyFor(IRecipeCategory<?> cat) {
        String base = cat.getRecipeType().getUid().toString();
        if (RecipeTypes.CRAFTING.equals(cat.getRecipeType())) {
            // 共有モード時は variant suffix を付けず単一 entry にする
            if (DIV.credit.CreditConfig.CRAFTING_SHARE_SLOTS.get()) return base;
            return base + "|" + craftingVariant.name();
        }
        return base;
    }

    @Nullable
    private RecipeDraft create(IRecipeCategory<?> cat) {
        RecipeType<?> rt = cat.getRecipeType();
        // 明示的に対応外（情報ページや診断系。レシピではないので編集不可）
        if (EXPLICIT_UNSUPPORTED.contains(rt.getUid().toString())) return null;
        if (RecipeTypes.CRAFTING.equals(rt)) {
            CraftingDraft cd = new CraftingDraft();
            cd.setMode(craftingVariant == CraftingVariant.SHAPED
                ? CraftingDraft.Mode.SHAPED : CraftingDraft.Mode.SHAPELESS);
            return cd;
        }
        if (RecipeTypes.SMELTING.equals(rt))         return new CookingDraft(CookingDraft.Type.SMELTING);
        if (RecipeTypes.BLASTING.equals(rt))         return new CookingDraft(CookingDraft.Type.BLASTING);
        if (RecipeTypes.SMOKING.equals(rt))          return new CookingDraft(CookingDraft.Type.SMOKING);
        if (RecipeTypes.CAMPFIRE_COOKING.equals(rt)) return new CookingDraft(CookingDraft.Type.CAMPFIRE);
        if (RecipeTypes.STONECUTTING.equals(rt))     return new StonecuttingDraft();

        // GT システム判定（modId 不問。recipe class が GTRecipe 派生）
        boolean isGt  = DIV.credit.client.draft.gt.GTSupport.isGtCategory(cat);
        // Mek システム判定（modId 不問。category が BaseRecipeCategory 派生）
        boolean isMek = DIV.credit.client.draft.mek.MekanismSupport.isMekCategory(cat);

        // hand-written drafts (GT compressor/assembler, Mek pressurized_reaction) を優先
        if (isGt && ModList.get().isLoaded("gtceu")) {
            RecipeDraft d = DIV.credit.client.draft.gt.GTSupport.tryCreate(cat);
            if (d != null) return d;
        }
        if (isMek && ModList.get().isLoaded("mekanism")) {
            RecipeDraft d = DIV.credit.client.draft.mek.MekanismSupport.tryCreate(cat);
            if (d != null) return d;
        }
        // GenericDraft 受け入れ条件:
        //   - vanilla minecraft
        //   - GT システム (gtceu 派生 mod 含む: StarT-Core 等)
        //   - Mek システム (Mek extension 含む: EvolvedMekanism 等)
        String ns = rt.getUid().getNamespace();
        if (!"minecraft".equals(ns) && !isGt && !isMek) return null;
        var rt2 = DIV.credit.jei.CraftPatternJeiPlugin.runtime;
        if (rt2 != null) {
            return GenericDraft.tryCreate(cat, rt2.getRecipeManager());
        }
        return null;
    }

    /** 明示的に対応外（情報・診断ページや KubeJS schema 不在で編集しても dump できないもの）。 */
    private static final java.util.Set<String> EXPLICIT_UNSUPPORTED = java.util.Set.of(
        "gtceu:multiblock_info",                       // マルチブロック情報
        "gtceu:bedrock_fluid_diagram",                 // 岩盤液体図
        "gtceu:bedrock_ore_diagram",                   // 岩盤鉱石図
        "gtceu:ore_vein_diagram",                      // 鉱脈図
        "gtceu:ore_processing_diagram",                // 鉱石処理工程図
        // Mek 系で KubeJS schema が存在しないため編集しても dump 不能
        "mekanism:sps_casing",                         // SPS (Supercritical Phase Shifter)
        "mekanism:boiler_casing",                      // ボイラー
        "mekanism:nutritional_liquifier",              // 栄養液化機
        "mekanismgenerators:fission",                  // Mek Generators 核分裂炉
        "evolvedmekanism:thermalizer",                 // EvolvedMek サーマライザー (MELTER)
        "evolvedmekanism:solidification_chamber",      // EvolvedMek 固化チャンバー
        "gtceu:programmed_circuit",       // プログラム回路ピッカー
        "ldlib:test_category"             // LDLib テスト用
    );

    public boolean isCraftingCategory(IRecipeCategory<?> cat) {
        return cat != null && RecipeTypes.CRAFTING.equals(cat.getRecipeType());
    }
}