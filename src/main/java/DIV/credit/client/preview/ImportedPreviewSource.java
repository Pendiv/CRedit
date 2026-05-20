package DIV.credit.client.preview;

import DIV.credit.client.importer.ImportedRecipe;
import DIV.credit.client.importer.ImportedRecipeRecovery;
import DIV.credit.client.io.ScriptWriter.OperationKind;
import mezz.jei.api.recipe.category.IRecipeCategory;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.crafting.Recipe;
import org.jetbrains.annotations.Nullable;

/**
 * v3.0.0 (S13): ImportedRecipe を preview 表示する PreviewSource。
 *
 * <p>構築方法: {@link #create(ImportedRecipe)} factory 経由。
 * Recipe&lt;?&gt; 復元 + category lookup を constructor で前計算して保持する。
 *
 * <p>失敗ケース (create が null 返し):
 * <ul>
 *   <li>DELETE kind (= event.remove は preview 不可)</li>
 *   <li>ImportedRecipeRecovery が null 返し (= JSON 抽出失敗 / Serializer 未登録 等)</li>
 *   <li>JeiRenderBridge.findCategoryForRecipe が null 返し (= category 解決不能)</li>
 * </ul>
 */
public final class ImportedPreviewSource implements PreviewSource {

    private final ImportedRecipe imported;
    private final IRecipeCategory<?> category;
    private final Recipe<?> recipe;

    /** factory。 復元 / 検索失敗時は null。 */
    @Nullable
    public static ImportedPreviewSource create(ImportedRecipe imported) {
        if (imported == null) return null;
        if (imported.kind() == OperationKind.DELETE) return null;
        Recipe<?> recipe = ImportedRecipeRecovery.recoverRecipe(imported);
        if (recipe == null) return null;
        IRecipeCategory<?> category = JeiRenderBridge.findCategoryForRecipe(recipe);
        if (category == null) return null;
        return new ImportedPreviewSource(imported, category, recipe);
    }

    private ImportedPreviewSource(ImportedRecipe imported,
                                   IRecipeCategory<?> category,
                                   Recipe<?> recipe) {
        this.imported = imported;
        this.category = category;
        this.recipe = recipe;
    }

    @Override
    public IRecipeCategory<?> getCategory() {
        return category;
    }

    @Override
    public Object getRecipeObject() {
        return recipe;
    }

    @Override
    public Component getLabel() {
        String src = imported.sourceFile() != null
            ? imported.sourceFile().getFileName().toString()
            : "(unknown)";
        return Component.translatable("gui.credit.preview.imported.label",
            src, imported.recipeId());
    }
}
