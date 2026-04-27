package DIV.credit.client.screen;

import DIV.credit.Credit;
import DIV.credit.CreditConfig;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * 設定画面（gear ボタンから開く）。現状は dump 先のみ。
 * 値の永続化は Forge ConfigSpec 経由で <game>/config/credit-client.toml に。
 */
public class SettingsScreen extends Screen {

    private final Screen parent;
    private EditBox dumpRootBox;
    private EditBox fluidDefaultBox;
    private EditBox gasDefaultBox;

    public SettingsScreen(Screen parent) {
        super(Component.literal("Credit Settings"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        super.init();
        int cx = this.width / 2;
        int boxW = 240;
        int leftX = cx - boxW / 2;
        int smallBoxW = 80;

        // Dump root field
        int y = 50;
        this.dumpRootBox = new EditBox(this.font, leftX, y, boxW, 16, Component.literal("dumpRoot"));
        this.dumpRootBox.setMaxLength(200);
        this.dumpRootBox.setValue(CreditConfig.DUMP_ROOT.get());
        addRenderableWidget(this.dumpRootBox);

        // Fluid default amount
        y = 100;
        this.fluidDefaultBox = new EditBox(this.font, leftX, y, smallBoxW, 16, Component.literal("fluidDefault"));
        this.fluidDefaultBox.setMaxLength(10);
        this.fluidDefaultBox.setValue(String.valueOf(CreditConfig.FLUID_DEFAULT_AMOUNT.get()));
        addRenderableWidget(this.fluidDefaultBox);

        // Gas default amount
        y = 140;
        this.gasDefaultBox = new EditBox(this.font, leftX, y, smallBoxW, 16, Component.literal("gasDefault"));
        this.gasDefaultBox.setMaxLength(10);
        this.gasDefaultBox.setValue(String.valueOf(CreditConfig.GAS_DEFAULT_AMOUNT.get()));
        addRenderableWidget(this.gasDefaultBox);

        // Save + Cancel buttons
        int btnY = 180;
        addRenderableWidget(Button.builder(Component.literal("Save"), b -> save())
            .bounds(cx - 80, btnY, 70, 20).build());
        addRenderableWidget(Button.builder(Component.literal("Cancel"), b -> onClose())
            .bounds(cx + 10, btnY, 70, 20).build());
    }

    private void save() {
        String d = dumpRootBox.getValue().trim();
        if (d.isBlank()) d = "kubejs/server_scripts";
        CreditConfig.DUMP_ROOT.set(d);
        CreditConfig.DUMP_ROOT.save();

        try {
            int f = Integer.parseInt(fluidDefaultBox.getValue().trim());
            if (f < 1) f = 1;
            CreditConfig.FLUID_DEFAULT_AMOUNT.set(f);
            CreditConfig.FLUID_DEFAULT_AMOUNT.save();
        } catch (NumberFormatException ignored) {}
        try {
            int g = Integer.parseInt(gasDefaultBox.getValue().trim());
            if (g < 1) g = 1;
            CreditConfig.GAS_DEFAULT_AMOUNT.set(g);
            CreditConfig.GAS_DEFAULT_AMOUNT.save();
        } catch (NumberFormatException ignored) {}

        Credit.LOGGER.info("[CraftPattern] Saved settings: dumpRoot={} fluid={} gas={}",
            CreditConfig.DUMP_ROOT.get(), CreditConfig.FLUID_DEFAULT_AMOUNT.get(), CreditConfig.GAS_DEFAULT_AMOUNT.get());
        onClose();
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        renderBackground(g);
        super.render(g, mouseX, mouseY, partialTick);
        int cx = this.width / 2;
        int leftX = cx - 120;
        // Title
        g.drawCenteredString(this.font, this.title, cx, 20, 0xFFFFFFFF);
        // Labels above each box
        g.drawString(this.font, Component.literal("Dump destination (relative to game dir):")
            .withStyle(ChatFormatting.GRAY), leftX, 38, 0xFFAAAAAA, false);
        g.drawString(this.font, Component.literal("Default fluid amount on JEI drag (mB):")
            .withStyle(ChatFormatting.GRAY), leftX, 88, 0xFFAAAAAA, false);
        g.drawString(this.font, Component.literal("Default gas amount on JEI drag (mB):")
            .withStyle(ChatFormatting.GRAY), leftX, 128, 0xFFAAAAAA, false);
        // Hint
        g.drawString(this.font, Component.literal("Files: <root>/generated/<namespace>/<path>.js")
            .withStyle(ChatFormatting.DARK_GRAY), leftX, 70, 0xFF777777, false);
    }

    @Override
    public void onClose() {
        Minecraft.getInstance().setScreen(parent);
    }
}
