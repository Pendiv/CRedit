package DIV.credit.client.draft.gt;

import DIV.credit.Credit;
import DIV.credit.client.draft.RecipeDraft;
import com.gregtechceu.gtceu.api.recipe.GTRecipe;
import com.gregtechceu.gtceu.api.recipe.GTRecipeType;
import com.gregtechceu.gtceu.api.recipe.RecipeHelper;
import com.gregtechceu.gtceu.api.registry.GTRegistries;
import com.gregtechceu.gtceu.data.recipe.builder.GTRecipeBuilder;
import mezz.jei.api.recipe.RecipeType;
import mezz.jei.api.recipe.category.IRecipeCategory;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.Nullable;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * GT 関連 Draft 生成のゲートウェイ。
 * このクラスは GT のクラスを直接参照するので、呼び出し側 (DraftStore) は GT がロードされている
 * ことを namespace チェック等で確認してから呼ぶこと。
 */
public final class GTSupport {

    private GTSupport() {}

    private static long EMPTY_RECIPE_COUNTER = 0;

    @Nullable
    public static RecipeDraft tryCreate(IRecipeCategory<?> cat) {
        RecipeType<?> rt = cat.getRecipeType();
        String path = rt.getUid().getPath();
        // First target: compressor only. Add more cases as we generalize.
        if ("compressor".equals(path)) {
            return new GTCompressorDraft(rt);
        }
        if ("assembler".equals(path)) {
            return new GTAssemblerDraft(rt);
        }
        return null;
    }

    /**
     * GenericDraft 経由の汎用ルート用：JEI uid から GT 空 recipe を構築。
     * 成功すれば LdLib widgets が「ingredient なし」として描画 → カテゴリ固有の空スロット枠が見える。
     * GTRecipeType が見つからない / 構築失敗時は null。
     */
    @Nullable
    public static GTRecipe tryBuildEmptyRecipe(ResourceLocation jeiUid) {
        return tryBuildEmptyRecipe(jeiUid, 0, 0, Map.of());
    }

    /**
     * GenericDraft が numeric fields を介してユーザー編集を反映する版。
     * duration / EUt / 任意の int data (ebf_temp 等) を builder に適用する。
     */
    @Nullable
    public static GTRecipe tryBuildEmptyRecipe(ResourceLocation jeiUid, long duration, long eut,
                                                 Map<String, Long> intData) {
        try {
            // First try as direct GTRecipeType
            GTRecipeType type = GTRegistries.RECIPE_TYPES.get(jeiUid);
            // Fallback: GT サブカテゴリ（chem_dyes, ingot_molding, *_recycling 等）の場合
            // GTRecipeCategory の親 GTRecipeType を引く
            if (type == null) {
                var cat = GTRegistries.RECIPE_CATEGORIES.get(jeiUid);
                if (cat != null) type = cat.getRecipeType();
            }
            if (type == null) return null;
            GTRecipeBuilder b = type.recipeBuilder(new ResourceLocation(Credit.MODID,
                    "draft_generic_" + (++EMPTY_RECIPE_COUNTER)));
            if (duration > 0) b.duration((int) duration);
            if (eut > 0) b.EUt(eut);
            for (Map.Entry<String, Long> e : intData.entrySet()) {
                if (e.getValue() != null && e.getValue() > 0) b.addData(e.getKey(), e.getValue().intValue());
            }
            return b.buildRawRecipe();
        } catch (Exception e) {
            Credit.LOGGER.warn("[CraftPattern] GT empty recipe build failed for {}: {}",
                jeiUid, e.toString());
            return null;
        }
    }

    /**
     * sample GTRecipe から duration / EUt / int data を抽出して GenericDraft の初期値に。
     */
    /** 既知の per-recipe-type 必須 numeric data。probe で見つからない時の fallback default。 */
    private static final java.util.Map<String, java.util.Map<String, Long>> REQUIRED_DATA_BY_TYPE = java.util.Map.of(
        "electric_blast_furnace", java.util.Map.of("ebf_temp", 1000L),
        "alloy_blast_smelter",    java.util.Map.of("ebf_temp", 1000L),
        "circuit_assembler",      java.util.Map.of("solder_multiplier", 1L),
        "fusion_reactor",         java.util.Map.of("eu_to_start", 0L)
    );

    public static GtMetadata probeMetadata(Object sampleRecipe, ResourceLocation jeiUid) {
        long dur = 100;
        long eut = 0;
        LinkedHashMap<String, Long> intData = new LinkedHashMap<>();
        if (sampleRecipe instanceof GTRecipe r) {
            dur = r.duration;
            try { eut = RecipeHelper.getInputEUt(r); } catch (Exception ignored) {}
            if (r.data != null) {
                // BYTE/SHORT/INT/LONG いずれも整数として扱う
                // (実例: ebf_temp は SHORT で保存されてた。codec が値範囲で型最適化するため)
                for (String key : r.data.getAllKeys()) {
                    Tag t = r.data.get(key);
                    if (t == null) continue;
                    int tagId = t.getId();
                    if (tagId == Tag.TAG_INT || tagId == Tag.TAG_SHORT || tagId == Tag.TAG_BYTE) {
                        intData.put(key, (long) r.data.getInt(key));
                    } else if (tagId == Tag.TAG_LONG) {
                        intData.put(key, r.data.getLong(key));
                    }
                }
            }
        }
        // 強制：このレシピ種が必要とする data field を追加（probe で見つからなくても）
        if (jeiUid != null) {
            java.util.Map<String, Long> required = REQUIRED_DATA_BY_TYPE.get(jeiUid.getPath());
            if (required != null) {
                for (java.util.Map.Entry<String, Long> e : required.entrySet()) {
                    intData.putIfAbsent(e.getKey(), e.getValue());
                }
            }
        }
        return new GtMetadata(dur, eut, intData);
    }

    /** 旧 API（probeMetadata(Object) 呼び出し互換）。 */
    public static GtMetadata probeMetadata(Object sampleRecipe) {
        return probeMetadata(sampleRecipe, null);
    }

    /**
     * JEI uid → KubeJS event.recipes.gtceu.<X> の X 部分。
     * GT サブカテゴリ (chem_dyes, ingot_molding, *_recycling 等) は親 GTRecipeType の path を返す。
     * KubeJS は recipe TYPE 単位で event を持つので、サブカテゴリ名では存在しない。
     */
    public static String resolveKubeJsRecipeName(ResourceLocation jeiUid) {
        try {
            // 直接 RECIPE_TYPES にあればそれが正解
            if (GTRegistries.RECIPE_TYPES.get(jeiUid) != null) return jeiUid.getPath();
            // サブカテゴリ → 親 type
            var cat = GTRegistries.RECIPE_CATEGORIES.get(jeiUid);
            if (cat != null) return cat.getRecipeType().registryName.getPath();
        } catch (Exception ignored) {}
        return jeiUid.getPath();
    }

    public static final class GtMetadata {
        public static final GtMetadata EMPTY = new GtMetadata(0, 0, new LinkedHashMap<>());
        public final long duration;
        public final long eut;
        public final LinkedHashMap<String, Long> intData;
        public GtMetadata(long duration, long eut, LinkedHashMap<String, Long> intData) {
            this.duration = duration;
            this.eut = eut;
            this.intData = intData;
        }
    }
}