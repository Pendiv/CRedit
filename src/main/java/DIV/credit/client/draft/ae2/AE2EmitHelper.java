package DIV.credit.client.draft.ae2;

import DIV.credit.client.draft.IngredientSpec;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;

/**
 * AE2 / Re-Avaritia 等 event.custom({...}) 形式 emit で使う JSON ingredient helper。
 * <p>NBT 持ち ItemStack は forge:nbt 形式 ({type:'forge:nbt', item:'...', nbt:'<NBT 文字列>'}) で表現。
 * <p>Re-Avaritia の Singularity ({Id:'avaritia:xxx'}) も同形式で動く。
 */
public final class AE2EmitHelper {

    private AE2EmitHelper() {}

    /** JSON ingredient (event.custom({...}) の値) を文字列で返す。空 spec は "{}" */
    public static String ingredientJson(IngredientSpec spec) {
        if (spec == null || spec.isEmpty()) return "{}";
        spec = spec.unwrap();
        if (spec instanceof IngredientSpec.Item it && !it.stack().isEmpty()) {
            return itemJson(it.stack());
        }
        if (spec instanceof IngredientSpec.Tag tg && tg.tagId() != null) {
            int c = tg.count();
            if (c > 1) return "{ tag: '" + tg.tagId() + "', count: " + c + " }";
            return "{ tag: '" + tg.tagId() + "' }";
        }
        return "{}";
    }

    /** ItemStack を JSON 表現。NBT があれば forge:nbt 形式。 */
    public static String itemJson(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return "{}";
        ResourceLocation rl = BuiltInRegistries.ITEM.getKey(stack.getItem());
        if (stack.hasTag()) {
            // forge:nbt 形式 — Re-Avaritia Singularity ({Id:'avaritia:xxx'}) もこれで動く
            String nbtStr = stack.getTag().toString();
            StringBuilder sb = new StringBuilder();
            sb.append("{ type: 'forge:nbt', item: '").append(rl).append("'");
            if (stack.getCount() > 1) sb.append(", count: ").append(stack.getCount());
            sb.append(", nbt: '").append(escapeForSingleQuotedJsString(nbtStr)).append("' }");
            return sb.toString();
        }
        if (stack.getCount() > 1) {
            return "{ item: '" + rl + "', count: " + stack.getCount() + " }";
        }
        return "{ item: '" + rl + "' }";
    }

    /** 結果 (output) スロット用: ItemStack を {"item":"...", "count":N} 形式で。 */
    public static String resultJson(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return "{}";
        ResourceLocation rl = BuiltInRegistries.ITEM.getKey(stack.getItem());
        if (stack.getCount() > 1) {
            return "{ item: '" + rl + "', count: " + stack.getCount() + " }";
        }
        return "{ item: '" + rl + "' }";
    }

    private static String escapeForSingleQuotedJsString(String s) {
        // JS string in single quotes — escape backslash first, then single quote
        return s.replace("\\", "\\\\").replace("'", "\\'");
    }
}
