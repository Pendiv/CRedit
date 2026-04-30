package DIV.credit.command;

import DIV.credit.Credit;
import DIV.credit.client.screen.BuilderScreen;
import DIV.credit.jei.CraftPatternJeiPlugin;
import DIV.credit.poc.RecordingLayoutBuilder;
import DIV.credit.poc.RecordingSlotBuilder;
import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import mezz.jei.api.helpers.IJeiHelpers;
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

import java.util.Optional;

@Mod.EventBusSubscriber(modid = Credit.MODID, value = Dist.CLIENT)
public class CraftPatternCommand {

    private static final SuggestionProvider<CommandSourceStack> SUGGEST_CATEGORIES = (ctx, builder) -> {
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
        LiteralArgumentBuilder<CommandSourceStack> poc = Commands.literal("craftpattern_poc")
            .then(Commands.argument("category", StringArgumentType.greedyString())
                .suggests(SUGGEST_CATEGORIES)
                .executes(CraftPatternCommand::execute));
        event.getDispatcher().register(poc);

        LiteralArgumentBuilder<CommandSourceStack> open = Commands.literal("craftpattern")
            .executes(CraftPatternCommand::openBuilder);
        event.getDispatcher().register(open);

        // v2.2.2 short alias /crp
        event.getDispatcher().register(
            Commands.literal("crp").executes(CraftPatternCommand::openBuilder));
    }

    private static int openBuilder(CommandContext<CommandSourceStack> ctx) {
        if (CraftPatternJeiPlugin.runtime == null) {
            chat(Component.literal("[CraftPattern] JEI is not ready yet. Enter a world first.")
                .withStyle(ChatFormatting.RED));
            return 0;
        }
        Minecraft.getInstance().tell(() -> Minecraft.getInstance().setScreen(BuilderScreen.open()));
        return Command.SINGLE_SUCCESS;
    }

    private static int execute(CommandContext<CommandSourceStack> ctx) {
        String categoryArg = StringArgumentType.getString(ctx, "category").trim();
        IJeiRuntime rt = CraftPatternJeiPlugin.runtime;
        if (rt == null) {
            chat(Component.literal("[CraftPattern] JEI is not ready yet. Enter a world first.")
                .withStyle(ChatFormatting.RED));
            return 0;
        }

        ResourceLocation uid;
        try {
            uid = new ResourceLocation(categoryArg);
        } catch (Exception e) {
            chat(Component.literal("[CraftPattern] Invalid category id: " + categoryArg)
                .withStyle(ChatFormatting.RED));
            return 0;
        }

        IJeiHelpers helpers = rt.getJeiHelpers();
        IRecipeManager manager = rt.getRecipeManager();

        @SuppressWarnings("removal")
        Optional<RecipeType<?>> typeOpt = helpers.getRecipeType(uid);
        if (typeOpt.isEmpty()) {
            chat(Component.literal("[CraftPattern] Unknown recipe type: " + uid)
                .withStyle(ChatFormatting.RED));
            return 0;
        }

        IFocusGroup empty = helpers.getFocusFactory().getEmptyFocusGroup();
        RecordingLayoutBuilder builder = new RecordingLayoutBuilder();
        try {
            captureLayout(typeOpt.get(), manager, builder, empty);
        } catch (Exception e) {
            Credit.LOGGER.error("[CraftPattern] setRecipe threw", e);
            chat(Component.literal("[CraftPattern] setRecipe threw: " + e.getClass().getSimpleName() + " — " + e.getMessage())
                .withStyle(ChatFormatting.RED));
            return 0;
        }

        chat(Component.literal("[CraftPattern] Captured " + builder.slots.size() + " slot(s) for " + uid)
            .withStyle(ChatFormatting.GREEN));
        if (builder.shapeless) {
            chat(Component.literal("  shapeless=true"
                + (builder.shapelessX != null ? " @(" + builder.shapelessX + "," + builder.shapelessY + ")" : "")));
        }
        if (builder.transferButtonX != null) {
            chat(Component.literal("  transferButton @(" + builder.transferButtonX + "," + builder.transferButtonY + ")"));
        }
        if (builder.focusLinkCount > 0) {
            chat(Component.literal("  focusLinks=" + builder.focusLinkCount));
        }
        for (RecordingSlotBuilder slot : builder.slots) {
            chat(Component.literal("  " + slot.summary()));
        }
        return Command.SINGLE_SUCCESS;
    }

    private static <T> void captureLayout(RecipeType<T> type, IRecipeManager manager,
                                          RecordingLayoutBuilder builder, IFocusGroup focuses) {
        IRecipeCategory<T> category = manager.getRecipeCategory(type);
        Optional<T> recipe = manager.createRecipeLookup(type).includeHidden().get().findFirst();
        if (recipe.isEmpty()) {
            chat(Component.literal("[CraftPattern] No recipes found for category " + type.getUid())
                .withStyle(ChatFormatting.YELLOW));
            return;
        }
        category.setRecipe(builder, recipe.get(), focuses);
    }

    private static void chat(Component msg) {
        var player = Minecraft.getInstance().player;
        if (player != null) player.displayClientMessage(msg, false);
    }
}