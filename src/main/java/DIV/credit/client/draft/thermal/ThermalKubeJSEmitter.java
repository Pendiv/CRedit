package DIV.credit.client.draft.thermal;

import DIV.credit.client.draft.IngredientSpec;
import DIV.credit.client.draft.RecipeDraft.SlotKind;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.fluids.FluidStack;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;

/**
 * Thermal Series 全 16 machine recipe の emit。
 * <p>{@link cofh.thermal.lib.util.recipes.MachineRecipeSerializer} の fromJson より:
 * <ul>
 *   <li>{@code ingredient} (single) or {@code ingredients} (array): item/fluid 自動判別</li>
 *   <li>{@code result} (single) or {@code results} (array): item (chance 可) / fluid 自動判別</li>
 *   <li>{@code energy} (int, optional), {@code experience} (float, optional)</li>
 * </ul>
 * <p>credit は 1+ 入力 / 1+ 出力 を array で emit (= safer)。
 */
public final class ThermalKubeJSEmitter {

    private ThermalKubeJSEmitter() {}

    @Nullable
    public static String emit(String recipeId, ResourceLocation jeiUid, IngredientSpec[] slots, SlotKind[] kinds) {
        if (!"thermal".equals(jeiUid.getNamespace())) return null;
        // 入力: item + fluid をまとめて ingredients array
        List<String> ins = new ArrayList<>();
        List<String> outs = new ArrayList<>();
        for (int i = 0; i < slots.length; i++) {
            IngredientSpec s = slots[i]; SlotKind k = kinds[i];
            if (s == null || s.isEmpty()) continue;
            String json = switch (k) {
                case ITEM_INPUT  -> itemIngredient(s);
                case FLUID_INPUT -> fluidJson(s);
                default -> null;
            };
            if (json != null) ins.add(json);
        }
        for (int i = 0; i < slots.length; i++) {
            IngredientSpec s = slots[i]; SlotKind k = kinds[i];
            if (s == null || s.isEmpty()) continue;
            String json = switch (k) {
                case ITEM_OUTPUT  -> itemOutput(s);
                case FLUID_OUTPUT -> fluidJson(s);
                default -> null;
            };
            if (json != null) outs.add(json);
        }
        if (ins.isEmpty() || outs.isEmpty()) return null;

        LinkedHashMap<String, String> f = new LinkedHashMap<>();
        if (ins.size() == 1) f.put("ingredient", ins.get(0));
        else                 f.put("ingredients", "[" + String.join(", ", ins) + "]");
        if (outs.size() == 1) f.put("result", outs.get(0));
        else                  f.put("results", "[" + String.join(", ", outs) + "]");
        return buildEventCustom(recipeId, "thermal:" + jeiUid.getPath(), f);
    }

    private static String buildEventCustom(String recipeId, String type, LinkedHashMap<String, String> fields) {
        StringBuilder sb = new StringBuilder();
        sb.append("    event.custom({\n");
        sb.append("        type: '").append(type).append("',\n");
        int i = 0, n = fields.size();
        for (var e : fields.entrySet()) {
            sb.append("        ").append(e.getKey()).append(": ").append(e.getValue());
            if (++i < n) sb.append(",");
            sb.append("\n");
        }
        sb.append("    }).id('").append(recipeId).append("');\n");
        DIV.credit.client.io.EmitSelfTest.verifyFields(type, fields, recipeId);
        return sb.toString();
    }

    /** 入力 item: {item:..} / {tag:..}、 count>1 は forge ingredient 拡張で {item/tag, count}。 */
    @Nullable
    private static String itemIngredient(IngredientSpec s) {
        if (s == null || s.isEmpty()) return null;
        IngredientSpec base = s.unwrap();
        if (base instanceof IngredientSpec.Item it && !it.stack().isEmpty()) {
            ResourceLocation rl = BuiltInRegistries.ITEM.getKey(it.stack().getItem());
            int c = it.stack().getCount();
            return c <= 1 ? "{ item: '" + rl + "' }"
                          : "{ item: '" + rl + "', count: " + c + " }";
        }
        if (base instanceof IngredientSpec.Tag tg && tg.tagId() != null) {
            int c = Math.max(1, tg.count());
            return c <= 1 ? "{ tag: '" + tg.tagId() + "' }"
                          : "{ tag: '" + tg.tagId() + "', count: " + c + " }";
        }
        return null;
    }

    /** 出力 item: {item, count}。 */
    @Nullable
    private static String itemOutput(IngredientSpec s) {
        if (s == null || s.isEmpty()) return null;
        IngredientSpec base = s.unwrap();
        if (base instanceof IngredientSpec.Item it && !it.stack().isEmpty()) {
            ResourceLocation rl = BuiltInRegistries.ITEM.getKey(it.stack().getItem());
            int c = it.stack().getCount();
            return c <= 1 ? "{ item: '" + rl + "' }"
                          : "{ item: '" + rl + "', count: " + c + " }";
        }
        return null;
    }

    /** Fluid (input/output 共通): {amount, fluid} or {amount, tag}。 */
    @Nullable
    private static String fluidJson(IngredientSpec s) {
        if (s == null || s.isEmpty()) return null;
        IngredientSpec base = s.unwrap();
        if (base instanceof IngredientSpec.Fluid fl && !fl.stack().isEmpty()) {
            FluidStack fs = fl.stack();
            ResourceLocation rl = BuiltInRegistries.FLUID.getKey(fs.getFluid());
            return "{ amount: " + fs.getAmount() + ", fluid: '" + rl + "' }";
        }
        if (base instanceof IngredientSpec.FluidTag ft && ft.tagId() != null) {
            return "{ amount: " + ft.amount() + ", tag: '" + ft.tagId() + "' }";
        }
        return null;
    }
}
