package DIV.credit.command;

import DIV.credit.Credit;
import DIV.credit.jei.CraftPatternJeiPlugin;
import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import mezz.jei.api.gui.IRecipeLayoutDrawable;
import mezz.jei.api.gui.ingredient.IRecipeSlotView;
import mezz.jei.api.ingredients.ITypedIngredient;
import mezz.jei.api.recipe.IFocusGroup;
import mezz.jei.api.recipe.IRecipeManager;
import mezz.jei.api.recipe.RecipeType;
import mezz.jei.api.recipe.category.IRecipeCategory;
import mezz.jei.api.runtime.IJeiRuntime;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RegisterClientCommandsEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * /craftpattern_probe <type>
 * 指定 JEI category の sample recipe(s) を reflection + JEI slot views で全部ダンプ。
 * 出力先：run/credit_probes/<namespace>__<path>.txt
 *
 * GTRecipe や Mek recipe の data field 構造、condition、slot 定義を一気に把握するための診断ツール。
 * 「コイル温度の key 名は何？」「solder_multiplier は実際 data に入ってる？」みたいな調査に使う。
 */
@Mod.EventBusSubscriber(modid = Credit.MODID, value = Dist.CLIENT)
public class ProbeCommand {

    private static final int MAX_SAMPLES = 5;

    private static final SuggestionProvider<CommandSourceStack> SUGGEST_TYPES = (ctx, builder) -> {
        IJeiRuntime rt = CraftPatternJeiPlugin.runtime;
        if (rt != null) {
            rt.getJeiHelpers().getAllRecipeTypes()
                .map(t -> t.getUid().toString())
                .filter(s -> s.startsWith(builder.getRemaining()))
                .forEach(builder::suggest);
        }
        return builder.buildFuture();
    };

    @SubscribeEvent
    public static void onRegister(RegisterClientCommandsEvent event) {
        LiteralArgumentBuilder<CommandSourceStack> probe = Commands.literal("craftpattern_probe")
            .then(Commands.argument("type", StringArgumentType.greedyString())
                .suggests(SUGGEST_TYPES)
                .executes(ProbeCommand::execute));
        event.getDispatcher().register(probe);
    }

    private static int execute(CommandContext<CommandSourceStack> ctx) {
        String typeArg = StringArgumentType.getString(ctx, "type").trim();
        IJeiRuntime rt = CraftPatternJeiPlugin.runtime;
        if (rt == null) {
            chat(Component.literal("[Probe] JEI not ready").withStyle(ChatFormatting.RED));
            return 0;
        }

        ResourceLocation uid;
        try { uid = new ResourceLocation(typeArg); }
        catch (Exception e) {
            chat(Component.literal("[Probe] invalid id: " + typeArg).withStyle(ChatFormatting.RED));
            return 0;
        }

        Optional<RecipeType<?>> typeOpt = rt.getJeiHelpers().getRecipeType(uid);
        if (typeOpt.isEmpty()) {
            chat(Component.literal("[Probe] unknown recipe type: " + uid).withStyle(ChatFormatting.RED));
            return 0;
        }

        StringBuilder out = new StringBuilder();
        try {
            probeType(typeOpt.get(), rt, out);
        } catch (Exception e) {
            Credit.LOGGER.error("[Probe] failed", e);
            chat(Component.literal("[Probe] error: " + e.getClass().getSimpleName() + ": " + e.getMessage()).withStyle(ChatFormatting.RED));
            return 0;
        }

        Path file = writeOutput(uid, out.toString());
        if (file != null) {
            chat(Component.literal("[Probe] wrote " + file).withStyle(ChatFormatting.GREEN));
        }
        return Command.SINGLE_SUCCESS;
    }

    private static <T> void probeType(RecipeType<T> type, IJeiRuntime rt, StringBuilder out) {
        IRecipeManager rm = rt.getRecipeManager();
        IRecipeCategory<T> cat = rm.getRecipeCategory(type);

        out.append("=== JEI Category Probe ===\n");
        out.append("uid: ").append(type.getUid()).append("\n");
        out.append("recipeClass: ").append(type.getRecipeClass().getName()).append("\n");
        out.append("category class: ").append(cat.getClass().getName()).append("\n");
        out.append("category title: ").append(cat.getTitle().getString()).append("\n\n");

        List<T> samples = rm.createRecipeLookup(type).includeHidden().get().limit(MAX_SAMPLES).toList();
        out.append("samples found: ").append(samples.size()).append(" (max ").append(MAX_SAMPLES).append(")\n\n");

        IFocusGroup empty = rt.getJeiHelpers().getFocusFactory().getEmptyFocusGroup();
        for (int i = 0; i < samples.size(); i++) {
            T r = samples.get(i);
            out.append("--- Sample #").append(i).append(" ---\n");
            out.append("class: ").append(r.getClass().getName()).append("\n");
            // Reflect on all instance fields (declared + inherited)
            dumpFields(r, out);
            // JEI slot views
            try {
                Optional<IRecipeLayoutDrawable<T>> drawable = rm.createRecipeLayoutDrawable(cat, r, empty);
                if (drawable.isPresent()) {
                    out.append("\n  -- JEI slots --\n");
                    List<IRecipeSlotView> views = drawable.get().getRecipeSlotsView().getSlotViews();
                    for (int j = 0; j < views.size(); j++) {
                        IRecipeSlotView v = views.get(j);
                        out.append("  slot[").append(j).append("] role=").append(v.getRole());
                        if (v.getSlotName().isPresent()) out.append(" name=").append(v.getSlotName().get());
                        out.append(" ingredients=[");
                        List<ITypedIngredient<?>> ings = v.getAllIngredients().limit(3).toList();
                        for (int k = 0; k < ings.size(); k++) {
                            if (k > 0) out.append(", ");
                            ITypedIngredient<?> ti = ings.get(k);
                            out.append(ti.getType().getUid()).append(":").append(safeToString(ti.getIngredient()));
                        }
                        if (ings.size() == 3 && v.getAllIngredients().count() > 3) out.append(", ...");
                        out.append("]\n");
                    }
                } else {
                    out.append("  (drawable build failed)\n");
                }
            } catch (Exception e) {
                out.append("  (drawable exception: ").append(e).append(")\n");
            }
            out.append("\n");
        }
    }

    /** インスタンスの全 field を reflection で出力。継承チェーンも辿る。Static / synthetic は除外。 */
    private static void dumpFields(Object obj, StringBuilder out) {
        Class<?> c = obj.getClass();
        List<Field> fields = new ArrayList<>();
        while (c != null && c != Object.class) {
            for (Field f : c.getDeclaredFields()) {
                if (Modifier.isStatic(f.getModifiers())) continue;
                if (f.isSynthetic()) continue;
                fields.add(f);
            }
            c = c.getSuperclass();
        }
        for (Field f : fields) {
            try {
                f.setAccessible(true);
                Object v = f.get(obj);
                out.append("  ").append(f.getDeclaringClass().getSimpleName()).append(".")
                   .append(f.getName()).append(" (").append(f.getType().getSimpleName()).append(") = ")
                   .append(safeToString(v)).append("\n");
            } catch (Exception e) {
                out.append("  ").append(f.getName()).append(" = <error: ").append(e.getMessage()).append(">\n");
            }
        }
    }

    private static String safeToString(Object o) {
        if (o == null) return "null";
        try {
            String s = o.toString();
            if (s.length() > 600) return s.substring(0, 600) + "... (truncated " + (s.length() - 600) + ")";
            return s;
        } catch (Exception e) {
            return "<toString error: " + e.getMessage() + ">";
        }
    }

    private static Path writeOutput(ResourceLocation uid, String content) {
        try {
            Path dir = Paths.get("credit_probes");
            Files.createDirectories(dir);
            String filename = uid.getNamespace() + "__" + uid.getPath().replace('/', '_') + ".txt";
            Path file = dir.resolve(filename);
            Files.writeString(file, content, StandardCharsets.UTF_8);
            return file.toAbsolutePath();
        } catch (IOException e) {
            Credit.LOGGER.error("[Probe] write failed", e);
            return null;
        }
    }

    private static void chat(Component msg) {
        var player = Minecraft.getInstance().player;
        if (player != null) player.displayClientMessage(msg, false);
    }
}
