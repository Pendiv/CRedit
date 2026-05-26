package DIV.credit.client.runtime;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.crafting.Recipe;
import org.jetbrains.annotations.Nullable;

/**
 * 1 個の recipe を backend 非依存で表す。
 * <p>{@code backingRecipe} は vanilla {@code Recipe<?>} (= 全 backend で取得可能、 hand-written draft
 * の metadata probe や auto pipeline の Codec 抽出に使う)。 取れない recipe (= 動的生成や non-data-driven)
 * は null。
 * <p>{@code nativeRef} は backend 固有 instance:
 * <ul>
 *   <li>JeiBackend: 元の T (= IRecipeCategory&lt;T&gt; の T、 大体 Recipe&lt;?&gt; だが mod 独自 type も)</li>
 *   <li>EmiBackend: {@code EmiRecipe} instance</li>
 * </ul>
 * sample probe + layout build で backend 内部処理に使う。
 *
 * @param id           recipe id (= ResourceLocation、 EMI synthetic id 含む。 null の場合あり)
 * @param category     所属 category
 * @param backingRecipe vanilla Recipe instance、 取れなければ null
 * @param nativeRef    backend 固有 instance
 */
public record CreditRecipe(
    @Nullable ResourceLocation id,
    CreditCategory category,
    @Nullable Recipe<?> backingRecipe,
    Object nativeRef
) {
}
