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

        // GT カテゴリは GTSupport に委譲（GT がロードされている時のみクラスロード）
        if ("gtceu".equals(rt.getUid().getNamespace()) && ModList.get().isLoaded("gtceu")) {
            return DIV.credit.client.draft.gt.GTSupport.tryCreate(cat);
        }
        // Mekanism カテゴリは MekanismSupport に委譲
        if ("mekanism".equals(rt.getUid().getNamespace()) && ModList.get().isLoaded("mekanism")) {
            return DIV.credit.client.draft.mek.MekanismSupport.tryCreate(cat);
        }
        return null;
    }

    public boolean isCraftingCategory(IRecipeCategory<?> cat) {
        return cat != null && RecipeTypes.CRAFTING.equals(cat.getRecipeType());
    }
}