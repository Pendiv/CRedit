package DIV.credit.client.draft.industrialforegoing;

import mezz.jei.api.recipe.category.IRecipeCategory;
import org.jetbrains.annotations.Nullable;

import java.util.Set;

/**
 * Industrial Foregoing detection。
 * <p>編集可 (= 4 type、 schema 別) のみ true。 laser_drill_* は EXPLICIT_UNSUPPORTED IRREGULAR 行き。
 */
public final class IFSupport {

    private static final Set<String> EDITABLE = Set.of(
        "crusher", "dissolution_chamber", "fluid_extractor", "stonework_generate"
    );

    private IFSupport() {}

    public static boolean isIFCategory(@Nullable IRecipeCategory<?> cat) {
        if (cat == null) return false;
        var uid = cat.getRecipeType().getUid();
        return "industrialforegoing".equals(uid.getNamespace()) && EDITABLE.contains(uid.getPath());
    }
}
