package DIV.credit.command;

import DIV.credit.Credit;
import com.mojang.brigadier.Command;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RegisterClientCommandsEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * /craftpattern_help — クリック可能 + ホバー説明付きの CraftPattern コマンド一覧。
 * 説明は lang key 経由（en_us / ja_jp）。
 */
@Mod.EventBusSubscriber(modid = Credit.MODID, value = Dist.CLIENT)
public class HelpCommand {

    /** name, hasArg (true なら / の後 space まで挿入し、false なら full command 挿入) */
    private record CmdEntry(String name, boolean hasArg) {}

    private static final CmdEntry[] COMMANDS = {
        new CmdEntry("craftpattern",       false),
        new CmdEntry("craftpattern_probe", true),
        new CmdEntry("craftpattern_poc",   true),
        new CmdEntry("craftpattern_help",  false),
    };

    @SubscribeEvent
    public static void onRegister(RegisterClientCommandsEvent event) {
        event.getDispatcher().register(
            Commands.literal("craftpattern_help").executes(HelpCommand::execute)
        );
    }

    private static int execute(CommandContext<CommandSourceStack> ctx) {
        chat(Component.translatable("command.credit.help.header").withStyle(ChatFormatting.GOLD));
        Component hint = Component.translatable("command.credit.help.hover_click").withStyle(ChatFormatting.GRAY, ChatFormatting.ITALIC);
        chat(hint);
        for (CmdEntry e : COMMANDS) {
            chat(formatLine(e));
        }
        return Command.SINGLE_SUCCESS;
    }

    private static MutableComponent formatLine(CmdEntry e) {
        String cmdText = "/" + e.name + (e.hasArg ? " " : "");
        Component desc = Component.translatable("command.credit." + e.name + ".desc");
        MutableComponent label = Component.literal("  /" + e.name)
            .withStyle(s -> s
                .withColor(ChatFormatting.AQUA)
                .withClickEvent(new ClickEvent(ClickEvent.Action.SUGGEST_COMMAND, cmdText))
                .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, desc)));
        return label.append(Component.literal(" — ").withStyle(ChatFormatting.DARK_GRAY))
                    .append(desc.copy().withStyle(ChatFormatting.GRAY));
    }

    private static void chat(Component msg) {
        var p = Minecraft.getInstance().player;
        if (p != null) p.displayClientMessage(msg, false);
    }
}
