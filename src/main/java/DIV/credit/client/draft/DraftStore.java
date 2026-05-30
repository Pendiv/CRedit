package DIV.credit.client.draft;

import mezz.jei.api.constants.RecipeTypes;
import mezz.jei.api.recipe.RecipeType;
import mezz.jei.api.recipe.category.IRecipeCategory;
import net.minecraft.nbt.CompoundTag;
import net.neoforged.fml.ModList;
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
        return create(cat, this.craftingVariant);
    }

    /** v3.16-A: instance 経由じゃなく static 経由でも Draft 構築可能に。 preview snapshot 復元等で使う。 */
    @Nullable
    public static RecipeDraft create(IRecipeCategory<?> cat, CraftingVariant variant) {
        if (cat == null) return null;
        RecipeType<?> rt = cat.getRecipeType();
        // 明示的に対応外（情報ページや診断系。レシピではないので編集不可）
        if (EXPLICIT_UNSUPPORTED.containsKey(rt.getUid().toString())) return null;
        if (RecipeTypes.CRAFTING.equals(rt)) {
            CraftingDraft cd = new CraftingDraft();
            cd.setMode(variant == CraftingVariant.SHAPED
                ? CraftingDraft.Mode.SHAPED : CraftingDraft.Mode.SHAPELESS);
            return cd;
        }
        if (RecipeTypes.SMELTING.equals(rt))         return new CookingDraft(CookingDraft.Type.SMELTING);
        if (RecipeTypes.BLASTING.equals(rt))         return new CookingDraft(CookingDraft.Type.BLASTING);
        if (RecipeTypes.SMOKING.equals(rt))          return new CookingDraft(CookingDraft.Type.SMOKING);
        if (RecipeTypes.CAMPFIRE_COOKING.equals(rt)) return new CookingDraft(CookingDraft.Type.CAMPFIRE);
        if (RecipeTypes.STONECUTTING.equals(rt))     return new StonecuttingDraft();
        // v3.0.1: FuelDraft 削除。 `event.fuel(...)` は KubeJS 標準 API に存在せず emit が bogus だった。
        //   minecraft:fuel カテゴリは EXPLICIT_UNSUPPORTED で IRREGULAR 扱い。

        // GT システム判定（modId 不問。recipe class が GTRecipe 派生）
        boolean isGt  = DIV.credit.client.draft.gt.GTSupport.isGtCategory(cat);
        // Mek システム判定（modId 不問。category が BaseRecipeCategory 派生）
        boolean isMek = DIV.credit.client.draft.mek.MekanismSupport.isMekCategory(cat);
        // IE システム判定（modId 不問。category が IERecipeCategory 派生）
        boolean isIe  = DIV.credit.client.draft.ie.IESupport.isIeCategory(cat);
        // Create システム判定（modId 不問。category が CreateRecipeCategory 派生）
        boolean isCreate = DIV.credit.client.draft.create.CreateSupport.isCreateCategory(cat);
        // DE 判定（category UID = draconicevolution:fusion_crafting）
        boolean isDe = DIV.credit.client.draft.de.DESupport.isDeCategory(cat);
        // AE2 判定（category UID = ae2:inscriber/charger/entropy/item_transformation）
        boolean isAe2 = DIV.credit.client.draft.ae2.AE2Support.isAe2Category(cat);
        // Re-Avaritia 判定 (category UID = avaritia:compressor / sculk_craft / nether_craft / end_craft / extreme_smithing)
        boolean isAvaritia = DIV.credit.client.draft.avaritia.AvaritiaSupport.isAvaritiaCategory(cat);

        // DE は最優先（fusion_crafting 専用 draft）
        if (isDe && ModList.get().isLoaded("draconicevolution")) {
            RecipeDraft d = DIV.credit.client.draft.de.DESupport.tryCreate(cat);
            if (d != null) return d;
        }
        if (isAe2 && ModList.get().isLoaded("ae2")) {
            RecipeDraft d = DIV.credit.client.draft.ae2.AE2Support.tryCreate(cat);
            if (d != null) return d;
        }
        if (isAvaritia && ModList.get().isLoaded("avaritia")) {
            RecipeDraft d = DIV.credit.client.draft.avaritia.AvaritiaSupport.tryCreate(cat);
            if (d != null) return d;
        }
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
        // v3.3.x auto pipeline: 未知 mod namespace でも GenericDraft 受け入れ可能 (= mirror/pattern emit 試行)。
        //   従来の「namespace whitelist」 を撤廃し、 GenericDraft.tryCreate の slot probe 失敗時に null 返却で篩う。
        //   EXPLICIT_UNSUPPORTED が真に非対応な category を依然として gate する。
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
        // v3.0.1: JEI category UID は `minecraft:` namespace (`jei:` ではない)。 ログで確認済。
        //   旧 `jei:` key は dead だったため map に hit せず supported 扱いになっていた。
        // 情報表示専用 (recipe ではない)
        m.put("minecraft:information",                  UnsupportedReason.VIEWER);  // JEI native info page
        m.put("minecraft:anvil",                        UnsupportedReason.VIEWER);  // 金床 (動的、編集不能)
        m.put("minecraft:tag_recipes/block",            UnsupportedReason.VIEWER);
        m.put("minecraft:tag_recipes/item",             UnsupportedReason.VIEWER);
        m.put("minecraft:tag_recipes/fluid",            UnsupportedReason.VIEWER);
        // バニラの KubeJS 非対応カテゴリ
        m.put("minecraft:loom",                         UnsupportedReason.VIEWER);
        m.put("minecraft:beacon",                       UnsupportedReason.VIEWER);
        m.put("minecraft:beacon_payment",               UnsupportedReason.VIEWER);
        m.put("minecraft:repair",                       UnsupportedReason.VIEWER);
        // v3.0.1: 例外的レシピ構造 (potion brewing / compostable / fuel / smithing)
        //   vanilla KubeJS 標準 API では安定して扱えない (= IRREGULAR)。
        //   - brewing: vanilla brewing stand は hardcoded、 standard event 不在 (addon 必要)
        //   - compostable: 専用 API (= setCompostable) は存在するが通常 recipe event ではない、 実装コスト高で未対応
        //   - fuel: `event.fuel(...)` は存在せず、 burnTime 改変は ItemEvents.modification (startup) 経由 → scope 外
        //   - smithing: 標準 recipe event が限定的 (trim 系は別 schema)
        m.put("minecraft:brewing",                      UnsupportedReason.IRREGULAR);
        m.put("minecraft:compostable",                  UnsupportedReason.IRREGULAR);
        m.put("minecraft:fuel",                         UnsupportedReason.IRREGULAR);
        m.put("minecraft:smithing",                     UnsupportedReason.IRREGULAR);
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
        // AE2: KubeJS 操作不可 / UI ガイドのみ
        m.put("ae2:condenser",                          UnsupportedReason.NO_KUBEJS);
        m.put("ae2:attunement",                         UnsupportedReason.VIEWER);
        m.put("ae2:certus_growth",                      UnsupportedReason.VIEWER);
        // v3.0.1: entropy は状態 transition (water→ice 等) で通常 ingredient 形式と異なる
        m.put("ae2:entropy",                            UnsupportedReason.IRREGULAR);
        // v3.1.1: Thermal Series 情報ページ系 (= catalyst boost / fuel multiplier / device mapping)
        m.put("thermal:pulverizer_catalyst",            UnsupportedReason.IRREGULAR);
        m.put("thermal:smelter_catalyst",               UnsupportedReason.IRREGULAR);
        m.put("thermal:insolator_catalyst",             UnsupportedReason.IRREGULAR);
        m.put("thermal:stirling_fuel",                  UnsupportedReason.VIEWER);
        m.put("thermal:compression_fuel",               UnsupportedReason.VIEWER);
        m.put("thermal:magmatic_fuel",                  UnsupportedReason.VIEWER);
        m.put("thermal:numismatic_fuel",                UnsupportedReason.VIEWER);
        m.put("thermal:lapidary_fuel",                  UnsupportedReason.VIEWER);
        m.put("thermal:disenchantment_fuel",            UnsupportedReason.VIEWER);
        m.put("thermal:gourmand_fuel",                  UnsupportedReason.VIEWER);
        m.put("thermal:hive_extractor",                 UnsupportedReason.VIEWER);
        m.put("thermal:tree_extractor",                 UnsupportedReason.VIEWER);
        m.put("thermal:tree_extractor_boost",           UnsupportedReason.VIEWER);
        m.put("thermal:fisher_boost",                   UnsupportedReason.VIEWER);
        m.put("thermal:rock_gen",                       UnsupportedReason.VIEWER);
        m.put("thermal:potion_diffuser_boost",          UnsupportedReason.VIEWER);
        // v3.1.1: Industrial Foregoing 動的 recipe (= entity/biome whitelist + rarity weight)
        m.put("industrialforegoing:laser_drill_fluid",  UnsupportedReason.IRREGULAR);
        m.put("industrialforegoing:laser_drill_ore",    UnsupportedReason.IRREGULAR);
        // v3.2.x: Botania IRREGULAR — brew id (= potion type)、 block state (StateIngredient)、 weight 系
        m.put("botania:brewery",          UnsupportedReason.IRREGULAR);  // brew id 文字列必須
        m.put("botania:pure_daisy",       UnsupportedReason.IRREGULAR);  // input/output が block state
        m.put("botania:orechid",          UnsupportedReason.IRREGULAR);  // block + weight 確率テーブル
        m.put("botania:orechid_ignem",    UnsupportedReason.IRREGULAR);  // 同
        m.put("botania:marimorphosis",    UnsupportedReason.IRREGULAR);  // block + weight + biome bonus
        // Re-Avaritia: 9x9 Extreme Crafting Table (tier 4) は v2.1 で対応外
        m.put("avaritia:extreme_craft",                 UnsupportedReason.DEFERRED);
        EXPLICIT_UNSUPPORTED = java.util.Collections.unmodifiableMap(m);
    }

    /**
     * 副作用なしで「このカテゴリを draft 化できるか」判定。JEI overlay の edit/delete ボタン表示条件に使う。
     * create() のロジックを写すが draft 自体は作らない。
     */
    public static boolean isSupportedCategory(@Nullable IRecipeCategory<?> cat) {
        if (cat == null) return false;
        RecipeType<?> rt = cat.getRecipeType();
        String uid = rt.getUid().toString();
        if (EXPLICIT_UNSUPPORTED.containsKey(uid)) return false;
        // v3.3.x auto pipeline: namespace whitelist 撤廃。 EXPLICIT_UNSUPPORTED に列挙されてなければ
        // すべて supported 扱いで UI button 表示。 draft 構築失敗時は BuilderScreen 側で fallback 通知。
        return true;
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