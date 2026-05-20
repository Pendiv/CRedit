package DIV.credit.command;

import DIV.credit.Credit;
import DIV.credit.client.io.ScriptWriter;
import DIV.credit.client.staging.StagedChange;
import DIV.credit.client.staging.StagingArea;
import DIV.credit.client.staging.StagingPersistence;
import com.mojang.brigadier.Command;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RegisterClientCommandsEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.List;

/**
 * /credit umbrella コマンド。v2.1.0 の git ライク push 機能を提供。
 * <ul>
 *   <li>/credit push     — committed な StagedChange を実ファイル書き込み</li>
 *   <li>/credit commit   — Phase B で CommitScreen を開く</li>
 *   <li>/credit history  — Phase C で HistoryScreen を開く</li>
 *   <li>/credit status   — staging の現状チャット表示 (debug)</li>
 * </ul>
 */
@Mod.EventBusSubscriber(modid = Credit.MODID, value = Dist.CLIENT)
public final class CreditCommand {

    private CreditCommand() {}

    @SubscribeEvent
    public static void onRegister(RegisterClientCommandsEvent event) {
        LiteralArgumentBuilder<CommandSourceStack> root = Commands.literal("credit")
            .then(Commands.literal("push").executes(CreditCommand::doPush))
            .then(Commands.literal("commit").executes(CreditCommand::doCommit))
            .then(Commands.literal("history").executes(CreditCommand::doHistory))
            .then(Commands.literal("setting").executes(CreditCommand::doSetting))
            .then(Commands.literal("import").executes(CreditCommand::doImport))
            .then(Commands.literal("reconstruction").executes(CreditCommand::doReconstruction))
            .then(Commands.literal("preview").executes(CreditCommand::doPreview))
            .then(Commands.literal("help")
                .then(Commands.literal("error").executes(CreditCommand::doHelpError))
                .then(Commands.literal("null").executes(CreditCommand::doHelpNull)))
            .then(Commands.literal("status").executes(CreditCommand::doStatus));
        event.getDispatcher().register(root);

        // v2.2.0 alias: /craftpattern_setting (既存 /craftpattern* シリーズと同じ命名)
        event.getDispatcher().register(
            Commands.literal("craftpattern_setting").executes(CreditCommand::doSetting));

        // v2.2.2: OMIT_MODID_PREFIX が true なら top-level エイリアスも登録
        // 注意: ConfigSpec が読み込まれてないと get() で例外。try でガード。
        boolean omitPrefix = false;
        try { omitPrefix = DIV.credit.CreditConfig.OMIT_MODID_PREFIX.get(); } catch (Exception ignored) {}
        if (omitPrefix) {
            event.getDispatcher().register(Commands.literal("push")          .executes(CreditCommand::doPush));
            event.getDispatcher().register(Commands.literal("commit")        .executes(CreditCommand::doCommit));
            event.getDispatcher().register(Commands.literal("history")       .executes(CreditCommand::doHistory));
            event.getDispatcher().register(Commands.literal("setting")       .executes(CreditCommand::doSetting));
            event.getDispatcher().register(Commands.literal("import")        .executes(CreditCommand::doImport));
            event.getDispatcher().register(Commands.literal("reconstruction").executes(CreditCommand::doReconstruction));
            event.getDispatcher().register(Commands.literal("preview").executes(CreditCommand::doPreview));
            event.getDispatcher().register(Commands.literal("status")        .executes(CreditCommand::doStatus));
            DIV.credit.Credit.LOGGER.info("[CraftPattern] Registered top-level command aliases (/commit /push /history /setting /import /reconstruction /preview /status)");
        }
    }

    /**
     * v2.1.2: /credit import → <minecraft>/credit/import/ をスキャン。
     * フォルダが無ければ作って案内 chat、あれば ImportRunner に処理を委譲。
     */
    private static int doImport(CommandContext<CommandSourceStack> ctx) {
        Minecraft mc = Minecraft.getInstance();
        mc.tell(DIV.credit.client.importer.ImportRunner::run);
        return Command.SINGLE_SUCCESS;
    }

    /** v2.1.2: /credit help error → KubeJS error の自己復旧手順を chat に出す。 */
    private static int doHelpError(CommandContext<CommandSourceStack> ctx) {
        chat(Component.translatable("gui.credit.help.error.l1").withStyle(ChatFormatting.GOLD));
        chat(Component.translatable("gui.credit.help.error.l2").withStyle(ChatFormatting.GRAY));
        chat(Component.translatable("gui.credit.help.error.l3").withStyle(ChatFormatting.GRAY));
        chat(Component.translatable("gui.credit.help.error.l4").withStyle(ChatFormatting.GRAY));
        chat(Component.translatable("gui.credit.help.error.l5").withStyle(ChatFormatting.YELLOW));
        return Command.SINGLE_SUCCESS;
    }

    /**
     * v2.1.4: /credit reconstruction → 既存 generated/ 配下の .js を再解釈して
     * 現行コード生成規則 + UNIFIED_EDIT_FILES 設定で書き直す。詳細は ReconstructionRunner。
     */
    private static int doReconstruction(CommandContext<CommandSourceStack> ctx) {
        Minecraft mc = Minecraft.getInstance();
        mc.tell(DIV.credit.client.importer.ReconstructionRunner::run);
        return Command.SINGLE_SUCCESS;
    }

    /** v3.9: /credit preview (一本化) → default ADD で開き、 画面内 dropdown で切替。 */
    private static int doPreview(CommandContext<CommandSourceStack> ctx) {
        DIV.credit.client.changed.PreviewLauncher.open(
            Minecraft.getInstance().screen,
            DIV.credit.client.changed.ChangedRecipeCollector.ViewMode.USER_ADD);
        return Command.SINGLE_SUCCESS;
    }

    /** v2.1.3: /credit help null → 数値フィールド null 化の使い方 + 注意。 */
    private static int doHelpNull(CommandContext<CommandSourceStack> ctx) {
        chat(Component.translatable("gui.credit.help.null.l1").withStyle(ChatFormatting.GOLD));
        chat(Component.translatable("gui.credit.help.null.l2").withStyle(ChatFormatting.GRAY));
        chat(Component.translatable("gui.credit.help.null.l3").withStyle(ChatFormatting.GRAY));
        chat(Component.translatable("gui.credit.help.null.l4").withStyle(ChatFormatting.GRAY));
        chat(Component.translatable("gui.credit.help.null.l5").withStyle(ChatFormatting.YELLOW));
        chat(Component.translatable("gui.credit.help.null.l6").withStyle(ChatFormatting.YELLOW));
        return Command.SINGLE_SUCCESS;
    }

    /** /credit setting または /craftpattern_setting → SettingsScreen を開く。 */
    private static int doSetting(CommandContext<CommandSourceStack> ctx) {
        Minecraft mc = Minecraft.getInstance();
        mc.tell(() -> mc.setScreen(new DIV.credit.client.screen.SettingsScreen(mc.screen)));
        return Command.SINGLE_SUCCESS;
    }

    /** /credit history → HistoryScreen を開く。 */
    private static int doHistory(CommandContext<CommandSourceStack> ctx) {
        Minecraft mc = Minecraft.getInstance();
        mc.tell(() -> mc.setScreen(new DIV.credit.client.screen.HistoryScreen(mc.screen)));
        return Command.SINGLE_SUCCESS;
    }

    /** /credit commit → CommitScreen を開く。 */
    private static int doCommit(CommandContext<CommandSourceStack> ctx) {
        Minecraft mc = Minecraft.getInstance();
        mc.tell(() -> mc.setScreen(new DIV.credit.client.screen.CommitScreen(mc.screen)));
        return Command.SINGLE_SUCCESS;
    }

    /** committed な change を ScriptWriter で実ファイル書き込み + キューから除去。 */
    private static int doPush(CommandContext<CommandSourceStack> ctx) {
        List<StagedChange> committed = StagingArea.INSTANCE.committedOnly();
        if (committed.isEmpty()) {
            int total = StagingArea.INSTANCE.size();
            if (total == 0) {
                chat(Component.translatable("gui.credit.push.nothing").withStyle(ChatFormatting.YELLOW));
            } else {
                chat(Component.translatable("gui.credit.push.no_committed", total).withStyle(ChatFormatting.YELLOW));
            }
            return 0;
        }
        int ok = 0, fail = 0;
        java.util.List<DIV.credit.client.history.HistoryEntry.Item> historyItems = new java.util.ArrayList<>();
        for (StagedChange c : committed) {
            ScriptWriter.DumpResult r = c.imported
                ? ScriptWriter.writeImportedCode(c.kind, c.modid, c.codeBody, c.jeiCategoryUid)
                : ScriptWriter.writeStagedCode(c.kind, c.modid, c.codeBody, c.jeiCategoryUid);
            boolean success = !(r instanceof ScriptWriter.DumpResult.Failure);
            String filePath = (r.path() != null) ? r.path().toString() : null;
            historyItems.add(new DIV.credit.client.history.HistoryEntry.Item(
                c.kind, c.modid, c.recipeId, filePath, success, c.jeiCategoryUid));
            if (!success) {
                fail++;
                Credit.LOGGER.warn("[CraftPattern] push: {} {} → FAILED ({})", c.kind, c.recipeId, r.message());
            } else {
                ok++;
                Credit.LOGGER.info("[CraftPattern] push: {} {} → {}", c.kind, c.recipeId, r.message());
            }
        }
        // History record (1 push = 1 entry)
        if (!historyItems.isEmpty()) {
            long ts = System.currentTimeMillis();
            DIV.credit.client.history.HistoryStore.INSTANCE.record(
                new DIV.credit.client.history.HistoryEntry(ts,
                    DIV.credit.client.history.HistoryEntry.Kind.PUSHED, historyItems));
            DIV.credit.client.history.HistoryPersistence.save();
            // v3.10: payload (= codeBody 列) を別ファイルに保存。 preview 過去世代用 lazy load
            DIV.credit.client.history.PushPayloadStore.save(ts, committed);
        }
        StagingArea.INSTANCE.removeAllCommitted();
        StagingPersistence.save();
        chat(Component.translatable("gui.credit.push.done", ok, fail)
            .withStyle(fail == 0 ? ChatFormatting.GREEN : ChatFormatting.YELLOW));
        // v2.2.0 auto reload
        if (DIV.credit.CreditConfig.AUTO_RELOAD_AFTER_PUSH.get() && ok > 0) {
            triggerReload();
        } else {
            chat(Component.translatable("gui.credit.dump.reload_hint").withStyle(ChatFormatting.GRAY));
        }
        return Command.SINGLE_SUCCESS;
    }

    /** /reload を Minecraft player 経由で送信。SP/MP どちらでも動く。 */
    private static void triggerReload() {
        var conn = Minecraft.getInstance().getConnection();
        if (conn == null) {
            chat(Component.translatable("gui.credit.push.reload_failed_no_conn")
                .withStyle(ChatFormatting.YELLOW));
            return;
        }
        // /reload は server command。SP では権限満たす想定。
        conn.sendCommand("reload");
        chat(Component.translatable("gui.credit.push.auto_reloaded").withStyle(ChatFormatting.GREEN));
    }

    /** debug: staging の中身を chat にざっくり出す。 */
    private static int doStatus(CommandContext<CommandSourceStack> ctx) {
        int total = StagingArea.INSTANCE.size();
        int committed = StagingArea.INSTANCE.committedCount();
        int uncommitted = total - committed;
        chat(Component.translatable("gui.credit.status.summary", total, committed, uncommitted)
            .withStyle(ChatFormatting.AQUA));
        for (StagedChange c : StagingArea.INSTANCE.all()) {
            String marker = c.committed ? "✓" : " ";
            chat(Component.literal("  " + marker + " [" + c.kind + "] " + c.recipeId)
                .withStyle(c.committed ? ChatFormatting.GREEN : ChatFormatting.GRAY));
        }
        return Command.SINGLE_SUCCESS;
    }

    private static void chat(Component msg) {
        var p = Minecraft.getInstance().player;
        if (p != null) p.displayClientMessage(msg, false);
    }
}
