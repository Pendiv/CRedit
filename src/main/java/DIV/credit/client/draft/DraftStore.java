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
        if (EXPLICIT_UNSUPPORTED.containsKey(rt.getUid().toString())) return null;
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
        // IE システム判定（modId 不問。category が IERecipeCategory 派生）
        boolean isIe  = DIV.credit.client.draft.ie.IESupport.isIeCategory(cat);
        // Create システム判定（modId 不問。category が CreateRecipeCategory 派生）
        boolean isCreate = DIV.credit.client.draft.create.CreateSupport.isCreateCategory(cat);

        // hand-written drafts (GT compressor/assembler, Mek pressurized_reaction) を優先
        if (isGt && ModList.get().isLoaded("gtceu")) {
            RecipeDraft d = DIV.credit.client.draft.gt.GTSupport.tryCreate(cat);
            if (d != null) return d;
        }
        if (isMek && ModList.get().isLoaded("mekanism")) {
            RecipeDraft d = DIV.credit.client.draft.mek.MekanismSupport.tryCreate(cat);
            if (d != null) return d;
        }
        if (isIe && ModList.get().isLoaded("immersiveengineering")) {
            RecipeDraft d = DIV.credit.client.draft.ie.IESupport.tryCreate(cat);
            if (d != null) return d;
        }
        if (isCreate && ModList.get().isLoaded("create")) {
            RecipeDraft d = DIV.credit.client.draft.create.CreateSupport.tryCreate(cat);
            if (d != null) return d;
        }
        // GenericDraft 受け入れ条件:
        //   - vanilla minecraft
        //   - GT システム (gtceu 派生 mod 含む: StarT-Core 等)
        //   - Mek システム (Mek extension 含む: EvolvedMekanism 等)
        //   - IE システム (現状は IE 本体のみ、addon あれば自動対応)
        //   - Create システム
        String ns = rt.getUid().getNamespace();
        if (!"minecraft".equals(ns) && !isGt && !isMek && !isIe && !isCreate) return null;
        var rt2 = DIV.credit.jei.CraftPatternJeiPlugin.runtime;
        if (rt2 != null) {
            return GenericDraft.tryCreate(cat, rt2.getRecipeManager());
        }
        return null;
    }

    /** 明示的に対応外（情報・診断ページや KubeJS schema 不在で編集しても dump できないもの）。
     *  値は ? icon ホバー tooltip 用の理由分類。 */
    private static final java.util.Map<String, UnsupportedReason> EXPLICIT_UNSUPPORTED;
    static {
        java.util.Map<String, UnsupportedReason> m = new java.util.HashMap<>();
        // GT 情報・診断ページ
        m.put("gtceu:multiblock_info",                  UnsupportedReason.VIEWER);
        m.put("gtceu:bedrock_fluid_diagram",            UnsupportedReason.VIEWER);
        m.put("gtceu:bedrock_ore_diagram",              UnsupportedReason.VIEWER);
        m.put("gtceu:ore_vein_diagram",                 UnsupportedReason.VIEWER);
        m.put("gtceu:ore_processing_diagram",           UnsupportedReason.VIEWER);
        m.put("gtceu:programmed_circuit",               UnsupportedReason.VIEWER);
        m.put("ldlib:test_category",                    UnsupportedReason.INTERNAL);
        // Mek 系で KubeJS schema が存在しないため編集しても dump 不能
        m.put("mekanism:sps_casing",                    UnsupportedReason.NO_KUBEJS);
        m.put("mekanism:boiler_casing",                 UnsupportedReason.NO_KUBEJS);
        m.put("mekanism:nutritional_liquifier",         UnsupportedReason.NO_KUBEJS);
        m.put("mekanismgenerators:fission",             UnsupportedReason.NO_KUBEJS);
        m.put("evolvedmekanism:thermalizer",            UnsupportedReason.NO_KUBEJS);
        m.put("evolvedmekanism:solidification_chamber", UnsupportedReason.NO_KUBEJS);
        // IE 系で編集対象外
        m.put("immersiveengineering:blast_furnace_fuel", UnsupportedReason.VIEWER);
        m.put("immersiveengineering:fertilizer",         UnsupportedReason.VIEWER);
        m.put("immersiveengineering:blueprint",          UnsupportedReason.COMPLEX);
        m.put("immersiveengineering:arc_recycling",      UnsupportedReason.DYNAMIC);
        m.put("immersiveengineering:cloche",             UnsupportedReason.COMPLEX);
        // Create 系で vanilla recipe を Create 機械で表示してるだけ
        m.put("create:fan_smoking",                     UnsupportedReason.VANILLA_ROUTED);
        m.put("create:fan_blasting",                    UnsupportedReason.VANILLA_ROUTED);
        m.put("create:block_cutting",                   UnsupportedReason.VANILLA_ROUTED);
        m.put("create:automatic_shaped",                UnsupportedReason.VANILLA_ROUTED);
        m.put("create:automatic_shapeless",             UnsupportedReason.VANILLA_ROUTED);
        m.put("create:automatic_packing",               UnsupportedReason.VANILLA_ROUTED);
        m.put("create:automatic_brewing",               UnsupportedReason.VANILLA_ROUTED);
        m.put("create:mystery_conversion",              UnsupportedReason.DYNAMIC);
        m.put("create:basin",                           UnsupportedReason.INTERNAL);
        // Create defer (今後対応予定だが Tier-A では未対応)
        m.put("create:mechanical_crafting",             UnsupportedReason.DEFERRED);
        m.put("create:sequenced_assembly",              UnsupportedReason.DEFERRED);
        EXPLICIT_UNSUPPORTED = java.util.Collections.unmodifiableMap(m);
    }

    /** UID から ? icon hover 用の reason を引く。EXPLICIT_UNSUPPORTED に無ければ null。 */
    @Nullable
    public static UnsupportedReason getUnsupportedReason(String uid) {
        return EXPLICIT_UNSUPPORTED.get(uid);
    }

    public boolean isCraftingCategory(IRecipeCategory<?> cat) {
        return cat != null && RecipeTypes.CRAFTING.equals(cat.getRecipeType());
    }
}