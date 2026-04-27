package DIV.credit.client.draft;

import mezz.jei.api.constants.RecipeTypes;
import mezz.jei.api.recipe.RecipeType;
import mezz.jei.api.recipe.category.IRecipeCategory;
import net.minecraftforge.fml.ModList;
import org.jetbrains.annotations.Nullable;

import java.util.HashMap;
import java.util.Map;

/**
 * カテゴリごとに Draft を保持。サポート対象なら自動生成、未対応なら null。
 * minecraft:crafting だけは shaped / shapeless を別エントリで保持して切替で消えないようにする。
 */
public class DraftStore {

    public enum CraftingVariant { SHAPED, SHAPELESS }

    private final Map<String, RecipeDraft> drafts = new HashMap<>();
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
        if (fresh != null) drafts.put(key, fresh);
        return fresh;
    }

    private String keyFor(IRecipeCategory<?> cat) {
        String base = cat.getRecipeType().getUid().toString();
        if (RecipeTypes.CRAFTING.equals(cat.getRecipeType())) {
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

        // GT カテゴリは GTSupport に委譲（hand-written 優先）
        if ("gtceu".equals(rt.getUid().getNamespace()) && ModList.get().isLoaded("gtceu")) {
            RecipeDraft d = DIV.credit.client.draft.gt.GTSupport.tryCreate(cat);
            if (d != null) return d;
        }
        // Mekanism カテゴリは MekanismSupport に委譲（hand-written 優先）
        if ("mekanism".equals(rt.getUid().getNamespace()) && ModList.get().isLoaded("mekanism")) {
            RecipeDraft d = DIV.credit.client.draft.mek.MekanismSupport.tryCreate(cat);
            if (d != null) return d;
        }
        // 上記いずれも未対応 → GenericDraft で probe（対応 mod の namespace のみ）
        // 全 MOD 対応はやめ、minecraft / gtceu / mekanism のみに絞る
        String ns = rt.getUid().getNamespace();
        if (!ALLOWED_NAMESPACES.contains(ns)) return null;
        var rt2 = DIV.credit.jei.CraftPatternJeiPlugin.runtime;
        if (rt2 != null) {
            return GenericDraft.tryCreate(cat, rt2.getRecipeManager());
        }
        return null;
    }

    /** GenericDraft 経由の対応 namespace 限定リスト。 */
    private static final java.util.Set<String> ALLOWED_NAMESPACES = java.util.Set.of(
        "minecraft", "gtceu", "mekanism"
    );

    /** 明示的に対応外（情報・診断ページなど、レシピ編集対象外）。 */
    private static final java.util.Set<String> EXPLICIT_UNSUPPORTED = java.util.Set.of(
        "gtceu:multiblock_info",          // マルチブロック情報
        "gtceu:bedrock_fluid_diagram",    // 岩盤液体図
        "gtceu:bedrock_ore_diagram",      // 岩盤鉱石図
        "gtceu:ore_vein_diagram",         // 鉱脈図
        "gtceu:ore_processing_diagram",   // 鉱石処理工程図
        "gtceu:programmed_circuit",       // プログラム回路ピッカー
        "ldlib:test_category"             // LDLib テスト用
    );

    public boolean isCraftingCategory(IRecipeCategory<?> cat) {
        return cat != null && RecipeTypes.CRAFTING.equals(cat.getRecipeType());
    }
}