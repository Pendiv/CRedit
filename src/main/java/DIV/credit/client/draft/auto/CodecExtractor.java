package DIV.credit.client.draft.auto;

import DIV.credit.Credit;
import com.google.gson.JsonElement;
import com.mojang.serialization.Codec;
import com.mojang.serialization.JsonOps;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeSerializer;
import org.jetbrains.annotations.Nullable;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * Recipe<?> から JSON を抽出。 抽出経路は (優先順):
 * <ol>
 *   <li>{@link RecipeSerializer} に {@code codec()} method があれば invoke (= 1.20.5+ pattern)</li>
 *   <li>{@link RecipeSerializer} に public/private な {@code CODEC} 静的 field があれば取り出して encodeStart</li>
 *   <li>{@link RecipeSerializer} のスーパークラス chain を辿って同名 field 探索</li>
 * </ol>
 * <p>失敗時は null。 mirror モード不可なら呼出側で {@link AutoPatternEmitter} fallback。
 */
public final class CodecExtractor {

    private CodecExtractor() {}

    @Nullable
    @SuppressWarnings({"unchecked", "rawtypes"})
    public static JsonElement tryExtract(Recipe<?> recipe) {
        if (recipe == null) return null;
        RecipeSerializer<?> ser = recipe.getSerializer();
        if (ser == null) return null;

        Codec<Recipe<?>> codec = locateCodec(ser);
        if (codec == null) {
            Credit.LOGGER.debug("[CraftPattern] CodecExtractor: no codec on {}", ser.getClass().getName());
            return null;
        }
        try {
            var result = ((Codec) codec).encodeStart(JsonOps.INSTANCE, recipe);
            var dr = result.result();
            if (dr.isEmpty()) {
                Credit.LOGGER.debug("[CraftPattern] CodecExtractor: encode failed on {}",
                    ser.getClass().getName());
                return null;
            }
            return (JsonElement) dr.get();
        } catch (Exception e) {
            Credit.LOGGER.debug("[CraftPattern] CodecExtractor: encode exception {} {}",
                ser.getClass().getName(), e.toString());
            return null;
        }
    }

    @Nullable
    @SuppressWarnings("unchecked")
    private static Codec<Recipe<?>> locateCodec(RecipeSerializer<?> ser) {
        // 1) method codec()
        try {
            Method m = ser.getClass().getMethod("codec");
            Object o = m.invoke(ser);
            if (o instanceof Codec<?> c) return (Codec<Recipe<?>>) c;
        } catch (NoSuchMethodException ignored) {
        } catch (Exception e) {
            Credit.LOGGER.debug("[CraftPattern] codec() invoke fail: {}", e.toString());
        }
        // 2) static field CODEC (public→declared, クラス chain 辿り)
        Class<?> cls = ser.getClass();
        while (cls != null && cls != Object.class) {
            try {
                Field f = cls.getDeclaredField("CODEC");
                f.setAccessible(true);
                Object o = f.get(null);
                if (o instanceof Codec<?> c) return (Codec<Recipe<?>>) c;
            } catch (NoSuchFieldException ignored) {
            } catch (Exception e) {
                Credit.LOGGER.debug("[CraftPattern] CODEC field fail on {}: {}", cls.getName(), e.toString());
            }
            cls = cls.getSuperclass();
        }
        return null;
    }
}
