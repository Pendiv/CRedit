package DIV.credit.client.draft.industrialforegoing;

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
 * Industrial Foregoing 4 schema を emit。
 * <p>IF は KubeJS 標準 schema を持たないので event.custom 経由。
 * 流体は IF 独自の NBT 文字列形式 ({@code "{Amount:N,FluidName:\"id\"}"}) を使う。
 */
public final class IFKubeJSEmitter {

    private IFKubeJSEmitter() {}

    @Nullable
    public static String emit(String recipeId, ResourceLocation jeiUid, IngredientSpec[] slots, SlotKind[] kinds) {
        if (!"industrialforegoing".equals(jeiUid.getNamespace())) return null;
        return switch (jeiUid.getPath()) {
            case "crusher"             -> emitCrusher(recipeId, slots, kinds);
            case "dissolution_chamber" -> emitDissolution(recipeId, slots, kinds);
            case "fluid_extractor"     -> emitFluidExtractor(recipeId, slots, kinds);
            case "stonework_generate"  -> emitStonework(recipeId, slots, kinds);
            default -> null;
        };
    }

    /** crusher: input + output (1+1)。 */
    @Nullable
    private static String emitCrusher(String recipeId, IngredientSpec[] slots, SlotKind[] kinds) {
        String in  = findFirst(slots, kinds, SlotKind.ITEM_INPUT, IFKubeJSEmitter::itemIngredient);
        String out = findFirst(slots, kinds, SlotKind.ITEM_OUTPUT, IFKubeJSEmitter::itemOutput);
        if (in == null || out == null) return null;
        LinkedHashMap<String, String> f = new LinkedHashMap<>();
        f.put("input", in);
        f.put("output", out);
        return buildEventCustom(recipeId, "industrialforegoing:crusher", f);
    }

    /** dissolution_chamber: input array (max 8) + inputFluid (NBT) + output + processingTime。 */
    @Nullable
    private static String emitDissolution(String recipeId, IngredientSpec[] slots, SlotKind[] kinds) {
        List<String> ins = collectAll(slots, kinds, SlotKind.ITEM_INPUT, IFKubeJSEmitter::itemIngredient);
        String inFluid = findFirst(slots, kinds, SlotKind.FLUID_INPUT, IFKubeJSEmitter::fluidNbtString);
        String out = findFirst(slots, kinds, SlotKind.ITEM_OUTPUT, IFKubeJSEmitter::itemOutput);
        if (ins.isEmpty() || inFluid == null || out == null) return null;
        LinkedHashMap<String, String> f = new LinkedHashMap<>();
        f.put("input", "[" + String.join(", ", ins) + "]");
        f.put("inputFluid", inFluid);
        f.put("output", out);
        f.put("processingTime", "300");
        return buildEventCustom(recipeId, "industrialforegoing:dissolution_chamber", f);
    }

    /** fluid_extractor: input (item) + output (fluid NBT) + result (item, optional) + defaultRecipe + breakChance。 */
    @Nullable
    private static String emitFluidExtractor(String recipeId, IngredientSpec[] slots, SlotKind[] kinds) {
        String in  = findFirst(slots, kinds, SlotKind.ITEM_INPUT, IFKubeJSEmitter::itemSimpleId);
        String outFluid = findFirst(slots, kinds, SlotKind.FLUID_OUTPUT, IFKubeJSEmitter::fluidNbtString);
        String result = findFirst(slots, kinds, SlotKind.ITEM_OUTPUT, IFKubeJSEmitter::itemSimpleId);
        if (in == null || outFluid == null) return null;
        LinkedHashMap<String, String> f = new LinkedHashMap<>();
        f.put("breakChance", "0.01");
        f.put("defaultRecipe", "false");
        f.put("input", "{ item: " + in + " }");
        f.put("output", outFluid);
        if (result != null) f.put("result", result);
        return buildEventCustom(recipeId, "industrialforegoing:fluid_extractor", f);
    }

    /** stonework_generate: lavaConsume/lavaNeed/waterConsume/waterNeed + output。 default 300 mB each。 */
    @Nullable
    private static String emitStonework(String recipeId, IngredientSpec[] slots, SlotKind[] kinds) {
        String out = findFirst(slots, kinds, SlotKind.ITEM_OUTPUT, IFKubeJSEmitter::itemOutput);
        if (out == null) return null;
        LinkedHashMap<String, String> f = new LinkedHashMap<>();
        f.put("lavaConsume", "300");
        f.put("lavaNeed",    "300");
        f.put("waterConsume","300");
        f.put("waterNeed",   "300");
        f.put("output", out);
        return buildEventCustom(recipeId, "industrialforegoing:stonework_generate", f);
    }

    // ─── helpers ───

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
        return sb.toString();
    }

    @Nullable
    private static String itemIngredient(IngredientSpec s) {
        if (s == null || s.isEmpty()) return null;
        IngredientSpec base = s.unwrap();
        if (base instanceof IngredientSpec.Item it && !it.stack().isEmpty()) {
            return "{ item: '" + BuiltInRegistries.ITEM.getKey(it.stack().getItem()) + "' }";
        }
        if (base instanceof IngredientSpec.Tag tg && tg.tagId() != null) {
            return "{ tag: '" + tg.tagId() + "' }";
        }
        return null;
    }

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

    /** item id string 'ns:path' のみ (= fluid_extractor の input/result が item plain id を要求)。 */
    @Nullable
    private static String itemSimpleId(IngredientSpec s) {
        if (s == null || s.isEmpty()) return null;
        IngredientSpec base = s.unwrap();
        if (base instanceof IngredientSpec.Item it && !it.stack().isEmpty()) {
            return "'" + BuiltInRegistries.ITEM.getKey(it.stack().getItem()) + "'";
        }
        return null;
    }

    /** IF 独自 fluid NBT 文字列: {@code '{Amount:N,FluidName:"id"}'}。 */
    @Nullable
    private static String fluidNbtString(IngredientSpec s) {
        if (s == null || s.isEmpty()) return null;
        IngredientSpec base = s.unwrap();
        if (base instanceof IngredientSpec.Fluid fl && !fl.stack().isEmpty()) {
            FluidStack fs = fl.stack();
            ResourceLocation rl = BuiltInRegistries.FLUID.getKey(fs.getFluid());
            return "'{Amount:" + fs.getAmount() + ",FluidName:\"" + rl + "\"}'";
        }
        return null;
    }

    @Nullable
    private static String findFirst(IngredientSpec[] slots, SlotKind[] kinds, SlotKind want,
                                    java.util.function.Function<IngredientSpec, String> formatter) {
        for (int i = 0; i < slots.length; i++) {
            if (kinds[i] == want && !slots[i].isEmpty()) {
                String s = formatter.apply(slots[i]);
                if (s != null) return s;
            }
        }
        return null;
    }

    private static List<String> collectAll(IngredientSpec[] slots, SlotKind[] kinds, SlotKind want,
                                            java.util.function.Function<IngredientSpec, String> formatter) {
        List<String> result = new ArrayList<>();
        for (int i = 0; i < slots.length; i++) {
            if (kinds[i] == want && !slots[i].isEmpty()) {
                String s = formatter.apply(slots[i]);
                if (s != null) result.add(s);
            }
        }
        return result;
    }
}
