package DIV.credit.client.draft.thermal;

import mezz.jei.api.recipe.category.IRecipeCategory;
import org.jetbrains.annotations.Nullable;

import java.util.Set;

/**
 * Thermal Series detection。
 * <p>編集可能な 16 machine recipe type (= 全て {@code thermal:<machine>} 形式) を識別する。
 * <p>catalyst / fuel / device 系は EXPLICIT_UNSUPPORTED 行き (= 別経路で弾く)。
 */
public final class ThermalSupport {

    /** 編集可 = MachineRecipeSerializer 経由で同一 schema (input/result/energy/xp)。 */
    private static final Set<String> EDITABLE = Set.of(
        "furnace", "sawmill", "pulverizer", "pulverizer_recycle",
        "smelter", "smelter_recycle", "insolator", "centrifuge",
        "press", "crucible", "chiller", "refinery", "pyrolyzer",
        "bottler", "brewer", "crystallizer"
    );

    private ThermalSupport() {}

    public static boolean isThermalCategory(@Nullable IRecipeCategory<?> cat) {
        if (cat == null) return false;
        var uid = cat.getRecipeType().getUid();
        return "thermal".equals(uid.getNamespace()) && EDITABLE.contains(uid.getPath());
    }
}
