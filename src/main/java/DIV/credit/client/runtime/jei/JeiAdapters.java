package DIV.credit.client.runtime.jei;

import DIV.credit.Credit;
import DIV.credit.client.draft.GenericDraft;
import DIV.credit.client.draft.IngredientSpec;
import DIV.credit.client.runtime.CreditCategory;
import DIV.credit.client.runtime.CreditRecipe;
import DIV.credit.client.runtime.CreditSlot;
import mezz.jei.api.gui.IRecipeLayoutDrawable;
import mezz.jei.api.gui.ingredient.IRecipeSlotDrawable;
import mezz.jei.api.gui.ingredient.IRecipeSlotView;
import mezz.jei.api.recipe.IRecipeManager;
import mezz.jei.api.recipe.RecipeIngredientRole;
import mezz.jei.api.recipe.RecipeType;
import mezz.jei.api.recipe.category.IRecipeCategory;
import mezz.jei.api.runtime.IJeiRuntime;
import net.minecraft.client.renderer.Rect2i;
import net.minecraft.world.item.crafting.Recipe;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * JEI ↔ Credit 型変換 helper。 JeiBackend 内部専用 (= 他 package からは呼び出さない、 backend interface 経由)。
 */
final class JeiAdapters {

    private JeiAdapters() {}

    /** {@link IRecipeCategory} → {@link CreditCategory} 変換。 nativeRef に元 IRecipeCategory を保持。 */
    static CreditCategory toCreditCategory(IRecipeCategory<?> cat) {
        return new CreditCategory(cat.getRecipeType().getUid(), cat.getTitle(), cat);
    }

    /** recipe instance + category → {@link CreditRecipe}。 backingRecipe は instance が Recipe<?> なら自動 set。 */
    static <T> CreditRecipe toCreditRecipe(IRecipeCategory<T> cat, T recipeInstance) {
        @Nullable Recipe<?> backing = (recipeInstance instanceof Recipe<?> r) ? r : null;
        @Nullable net.minecraft.resources.ResourceLocation id =
            (backing != null) ? backing.getId() : null;
        return new CreditRecipe(id, toCreditCategory(cat), backing, recipeInstance);
    }

    /**
     * {@link IRecipeSlotView} 列 → {@link CreditSlot} 列に変換。
     * - x,y は {@link IRecipeSlotDrawable#getRect} から取得。 view が drawable でなければ (0,0)。
     * - role は JEI {@link RecipeIngredientRole} → CreditSlot.Role に map。
     * - sample ingredient は既存 {@link GenericDraft#readSpecFromView} 流用 (= 既存挙動完全同等)。
     */
    static List<CreditSlot> toCreditSlots(List<IRecipeSlotView> views) {
        List<CreditSlot> out = new ArrayList<>(views.size());
        for (int i = 0; i < views.size(); i++) {
            IRecipeSlotView v = views.get(i);
            int x = 0, y = 0;
            if (v instanceof IRecipeSlotDrawable sd) {
                Rect2i r = sd.getRect();
                x = r.getX(); y = r.getY();
            }
            CreditSlot.Role role = mapRole(v.getRole());
            IngredientSpec sample = GenericDraft.readSpecFromView(v);
            out.add(new CreditSlot(i, x, y, role, sample, v));
        }
        return out;
    }

    private static CreditSlot.Role mapRole(RecipeIngredientRole role) {
        return switch (role) {
            case OUTPUT   -> CreditSlot.Role.OUTPUT;
            case CATALYST -> CreditSlot.Role.CATALYST;
            default       -> CreditSlot.Role.INPUT;
        };
    }

    /**
     * sample drawable を build。 失敗時 null。 inferKind 等 既存 probe path で使う。
     * {@link IRecipeCategory#getRecipeType} で取れる型と recipe instance の型が一致してることを caller が保証。
     */
    @Nullable
    @SuppressWarnings({"unchecked", "rawtypes"})
    static IRecipeLayoutDrawable<?> buildDrawable(IJeiRuntime runtime, IRecipeCategory<?> cat, Object recipe) {
        try {
            IRecipeManager rm = runtime.getRecipeManager();
            var focusGroup = runtime.getJeiHelpers().getFocusFactory().getEmptyFocusGroup();
            // raw type 経由のため Optional も raw、 cast で型補完
            java.util.Optional opt = rm.createRecipeLayoutDrawable((IRecipeCategory) cat, recipe, focusGroup);
            Object d = opt.orElse(null);
            return (IRecipeLayoutDrawable<?>) d;
        } catch (Exception e) {
            Credit.LOGGER.debug("[CraftPattern] JeiAdapters.buildDrawable failed for {}: {}",
                cat.getRecipeType().getUid(), e.toString());
            return null;
        }
    }
}
