package DIV.credit.client.draft.botania;

import mezz.jei.api.recipe.category.IRecipeCategory;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.Nullable;

import java.util.Map;
import java.util.Set;

/**
 * Botania detection。
 * <p>JEI category UID と recipe TYPE id がズレる: brew→brewery / mana_infusion→mana_pool /
 * petal_apothecary→petals。 emit 時は recipe TYPE id を使うため map 経由で逆引き。
 * <p>編集可 5、 IRREGULAR 5 (block state / weight / brew id 等で slot 編集に乗らない)。
 */
public final class BotaniaSupport {

    /** JEI category UID → KubeJS event.custom で使う recipe type id 変換。 */
    public static final Map<String, String> JEI_UID_TO_TYPE = Map.of(
        "botania:elven_trade",  "botania:elven_trade",
        "botania:mana_pool",    "botania:mana_infusion",
        "botania:petals",       "botania:petal_apothecary",
        "botania:runic_altar",  "botania:runic_altar",
        "botania:terra_plate",  "botania:terra_plate"
    );

    private static final Set<String> EDITABLE_UIDS = JEI_UID_TO_TYPE.keySet();

    /**
     * v3.2.x Option A: per-category の max INPUT / OUTPUT slot 数。
     * sample probe で得た slot 数を超えてユーザーが使えるよう padding する基準。
     * key = JEI category path (= jeiUid.getPath())、 value = {maxInputs, maxOutputs}。
     */
    private static final Map<String, int[]> TARGET_SLOT_COUNTS = Map.of(
        "elven_trade",  new int[]{4, 4},   // varargs ingredient + ItemStack[] output 仕様
        "petals",       new int[]{8, 1},   // 円形 layout 最大 8 petals
        "runic_altar",  new int[]{8, 1},   // 8 ingredient まで一般的
        "terra_plate",  new int[]{4, 1}    // 通常 3 ingredient (manasteel + pearl + diamond)
        // mana_pool は 1 input + 1 output 固定 (= spec)、 padding 不要
    );

    private BotaniaSupport() {}

    public static boolean isBotaniaCategory(@Nullable IRecipeCategory<?> cat) {
        if (cat == null) return false;
        ResourceLocation uid = cat.getRecipeType().getUid();
        return "botania".equals(uid.getNamespace()) && EDITABLE_UIDS.contains(uid.toString());
    }

    /** 指定 category の {maxInputs, maxOutputs}、 padding 不要なら null。 */
    @Nullable
    public static int[] getTargetSlotCounts(String jeiPath) {
        return TARGET_SLOT_COUNTS.get(jeiPath);
    }
}
