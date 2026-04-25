package DIV.credit.client.screen;

import DIV.credit.Credit;
import DIV.credit.client.draft.CookingDraft;
import DIV.credit.client.draft.DraftStore;
import DIV.credit.client.draft.RecipeDraft;
import DIV.credit.client.io.ScriptWriter;
import DIV.credit.client.menu.CreditBuilderMenu;
import DIV.credit.client.recipe.RecipeArea;
import DIV.credit.client.tab.CategoryTab;
import DIV.credit.client.tab.CategoryTabBar;
import DIV.credit.client.tab.PageNav;
import DIV.credit.client.tag.TagBar;
import DIV.credit.jei.CraftPatternJeiPlugin;
import mezz.jei.api.helpers.IGuiHelper;
import mezz.jei.api.recipe.IRecipeManager;
import mezz.jei.api.recipe.category.IRecipeCategory;
import mezz.jei.api.runtime.IJeiRuntime;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@OnlyIn(Dist.CLIENT)
public class BuilderScreen extends AbstractContainerScreen<CreditBuilderMenu> {

    public static final int TOP_MARGIN      = 5;
    public static final int BOTTOM_MARGIN   = 5;
    public static final int TAB_AREA_HEIGHT = PageNav.H + CategoryTab.H;

    public static final int DUMP_W = 21;
    public static final int DUMP_H = 10;
    private static final ResourceLocation DUMP_TEX =
            new ResourceLocation(Credit.MODID, "ui/dump.png");

    /** Static so drafts and last-selected category persist across screen open/close (until game exit). */
    private static final DraftStore DRAFT_STORE = new DraftStore();
    private static IRecipeCategory<?> lastCategory;

    private CategoryTabBar tabBar;
    private final RecipeArea recipeArea = new RecipeArea();
    private final TagBar tagBar = new TagBar();
    private ItemStack ghostCursor = ItemStack.EMPTY;
    private IRecipeCategory<?> currentCategory;

    private int recipeAreaTop;
    private int recipeAreaBottom;
    private int tagBarY;

    private int toggleX = -1, toggleY, toggleW, toggleH;
    private int dumpX   = -1, dumpY;

    // Dynamic numeric fields (derived from current draft.numericFields())
    private final List<EditBox> numericBoxes = new ArrayList<>();
    private final List<RecipeDraft.NumericField> currentFields = new ArrayList<>();
    private boolean updatingFieldsFromDraft = false;

    public BuilderScreen(CreditBuilderMenu menu, Inventory playerInv) {
        super(menu, playerInv, Component.literal("CraftPattern Builder"));
        this.imageWidth      = 176;
        this.imageHeight     = CreditBuilderMenu.IMAGE_HEIGHT;
        this.titleLabelX     = -10000;
        this.inventoryLabelX = -10000;
    }

    public static BuilderScreen open() {
        var player = Minecraft.getInstance().player;
        return new BuilderScreen(new CreditBuilderMenu(player.getInventory()), player.getInventory());
    }

    @Override
    protected void init() {
        super.init();
        int minInventoryTop = TOP_MARGIN + TAB_AREA_HEIGHT + TagBar.H + 10;
        this.topPos = Math.max(minInventoryTop, this.height - this.imageHeight - BOTTOM_MARGIN);

        IJeiRuntime rt = CraftPatternJeiPlugin.runtime;
        if (rt != null) {
            IRecipeManager rm = rt.getRecipeManager();
            IGuiHelper     gh = rt.getJeiHelpers().getGuiHelper();
            List<IRecipeCategory<?>> cats = rm.createRecipeCategoryLookup().get().toList();
            this.tabBar = new CategoryTabBar(cats, rm, gh, this::onCategorySelected);
            this.tabBar.setBounds(leftPos, TOP_MARGIN, imageWidth);
            IRecipeCategory<?> restored = findCategory(cats, lastCategory);
            if (restored != null) tabBar.select(restored);
        }

        this.recipeAreaTop    = TOP_MARGIN + TAB_AREA_HEIGHT;
        this.tagBarY          = topPos - TagBar.H;
        this.recipeAreaBottom = tagBarY;

        recipeArea.setBounds(leftPos, recipeAreaTop, imageWidth, recipeAreaBottom - recipeAreaTop);

        tagBar.init(this, font, leftPos, tagBarY, imageWidth, this::addRenderableWidget);

        if (tabBar != null && tabBar.getSelected() != null) {
            applyCategory(tabBar.getSelected());
        }
    }

    /** Draft の numericFields() に基づいて EditBox を動的生成。 */
    private void rebuildNumericFields(@Nullable RecipeDraft draft) {
        for (EditBox box : numericBoxes) removeWidget(box);
        numericBoxes.clear();
        currentFields.clear();
        if (draft == null) return;
        List<RecipeDraft.NumericField> fields = draft.numericFields();
        if (fields.isEmpty()) return;

        int boxW = 38;
        int boxH = 12;
        int y    = recipeAreaBottom - boxH - 2;
        int x    = leftPos + 4;
        int spacing = 6;

        updatingFieldsFromDraft = true;
        try {
            for (RecipeDraft.NumericField field : fields) {
                int labelW = font.width(field.label());
                x += labelW + 3;
                EditBox box = new EditBox(font, x, y, boxW, boxH, Component.literal(field.label()));
                box.setMaxLength(8);
                box.setValue(formatField(field));
                box.setResponder(s -> onFieldChanged(field, s));
                numericBoxes.add(box);
                currentFields.add(field);
                addRenderableWidget(box);
                x += boxW + spacing;
            }
        } finally {
            updatingFieldsFromDraft = false;
        }
    }

    private static String formatField(RecipeDraft.NumericField field) {
        double v = field.getter().getAsDouble();
        if (field.kind() == RecipeDraft.NumericField.Kind.INT) return String.valueOf((long) v);
        if (v == (long) v) return String.valueOf((long) v);
        return String.valueOf((float) v);
    }

    private void onFieldChanged(RecipeDraft.NumericField field, String s) {
        if (updatingFieldsFromDraft) return;
        if (s.isBlank()) return;
        try {
            double v = field.kind() == RecipeDraft.NumericField.Kind.INT
                ? (double) Integer.parseInt(s)
                : (double) Float.parseFloat(s);
            v = Math.max(field.min(), Math.min(field.max(), v));
            field.setter().accept(v);
            recipeArea.rebuild();
        } catch (NumberFormatException ignored) {}
    }

    private static IRecipeCategory<?> findCategory(List<IRecipeCategory<?>> cats, IRecipeCategory<?> target) {
        if (target == null) return null;
        for (IRecipeCategory<?> c : cats) {
            if (c.getRecipeType().equals(target.getRecipeType())) return c;
        }
        return null;
    }

    public RecipeArea getRecipeArea() { return recipeArea; }

    private void onCategorySelected(IRecipeCategory<?> cat) {
        Credit.LOGGER.info("[CraftPattern] Selected category: {}", cat.getRecipeType().getUid());
        applyCategory(cat);
    }

    private void applyCategory(IRecipeCategory<?> cat) {
        this.currentCategory = cat;
        lastCategory = cat;
        recipeArea.setCategory(cat, DRAFT_STORE.getOrCreate(cat));
        rebuildNumericFields(recipeArea.getDraft());
        tagBar.setVisible(recipeArea.getDraft() != null);
    }

    private void toggleCraftingMode() {
        DRAFT_STORE.setCraftingVariant(
            DRAFT_STORE.getCraftingVariant() == DraftStore.CraftingVariant.SHAPED
                ? DraftStore.CraftingVariant.SHAPELESS
                : DraftStore.CraftingVariant.SHAPED
        );
        applyCategory(currentCategory);
        playClick();
    }

    private void doDump() {
        RecipeDraft draft = recipeArea.getDraft();
        if (draft == null) {
            chat(Component.literal("[CraftPattern] No draft for this category.").withStyle(ChatFormatting.RED));
            return;
        }
        String recipeId = autoRecipeId(draft);
        ScriptWriter.DumpResult result = ScriptWriter.dump(draft, recipeId);
        Component msg;
        if (result instanceof ScriptWriter.DumpResult.Success s) {
            msg = Component.literal("[CraftPattern] " + s.message()).withStyle(ChatFormatting.GREEN);
        } else if (result instanceof ScriptWriter.DumpResult.Fallback f) {
            msg = Component.literal("[CraftPattern] " + f.message()).withStyle(ChatFormatting.YELLOW);
        } else {
            msg = Component.literal("[CraftPattern] " + result.message()).withStyle(ChatFormatting.RED);
        }
        chat(msg);
        if (!(result instanceof ScriptWriter.DumpResult.Failure)) {
            chat(Component.literal("[CraftPattern] Run /reload (or /kubejs reload server_scripts) to apply.")
                .withStyle(ChatFormatting.GRAY));
            playClick();
        }
    }

    private String autoRecipeId(RecipeDraft draft) {
        String outPath = draft.outputItemPath();
        if (outPath != null && !outPath.isEmpty()) {
            return Credit.MODID + ":generated/" + outPath;
        }
        return Credit.MODID + ":generated/recipe_" + (System.currentTimeMillis() % 100000);
    }

    private static void chat(Component msg) {
        var p = Minecraft.getInstance().player;
        if (p != null) p.displayClientMessage(msg, false);
    }

    private static void playClick() {
        Minecraft.getInstance().getSoundManager().play(
            SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK, 1.0F));
    }

    @Override
    protected void renderBg(GuiGraphics g, float partialTick, int mouseX, int mouseY) {
        g.fill(leftPos, recipeAreaTop, leftPos + imageWidth, recipeAreaBottom,    0xFF1A1A2E);
        g.fill(leftPos, tagBarY,       leftPos + imageWidth, tagBarY + TagBar.H,  0xFF161628);
        g.fill(leftPos, topPos,        leftPos + imageWidth, topPos + imageHeight, 0xFF2A2A3E);
        for (Slot s : menu.slots) {
            int sx = leftPos + s.x;
            int sy = topPos  + s.y;
            g.fill(sx - 1, sy - 1, sx + 17, sy + 17, 0xFF8B8B8B);
            g.fill(sx,     sy,     sx + 16, sy + 16, 0xFF373737);
        }
        if (tabBar != null) tabBar.draw(g);
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        renderBackground(g);
        super.render(g, mouseX, mouseY, partialTick);
        recipeArea.render(g, mouseX, mouseY);

        renderModeToggle(g, mouseX, mouseY);
        renderUnsupportedNotice(g);
        renderNumberLabels(g);
        renderDumpButton(g, mouseX, mouseY);
        tagBar.render(g, mouseX, mouseY);

        if (tabBar != null) {
            CategoryTab hover = tabBar.getHovered(mouseX, mouseY);
            if (hover != null) g.renderTooltip(font, hover.tooltip(), Optional.empty(), mouseX, mouseY);
        }
        recipeArea.renderOverlays(g, mouseX, mouseY);
        tagBar.renderTooltip(g, font, mouseX, mouseY);
        renderTooltip(g, mouseX, mouseY);

        if (!ghostCursor.isEmpty()) {
            int gx = mouseX - 8;
            int gy = mouseY - 8;
            g.renderItem(ghostCursor, gx, gy);
            g.renderItemDecorations(font, ghostCursor, gx, gy);
        }
    }

    private void renderModeToggle(GuiGraphics g, int mouseX, int mouseY) {
        toggleX = -1;
        if (!DRAFT_STORE.isCraftingCategory(currentCategory)) return;
        String label = "[" + (DRAFT_STORE.getCraftingVariant() == DraftStore.CraftingVariant.SHAPED
            ? "Shaped" : "Shapeless") + "]";
        toggleW = font.width(label) + 4;
        toggleH = font.lineHeight + 2;
        toggleX = leftPos + imageWidth - toggleW - 2;
        toggleY = recipeAreaTop + 2;
        boolean hover = mouseX >= toggleX && mouseX < toggleX + toggleW
                     && mouseY >= toggleY && mouseY < toggleY + toggleH;
        int bg = hover ? 0xCC404060 : 0x88202040;
        g.fill(toggleX, toggleY, toggleX + toggleW, toggleY + toggleH, bg);
        g.drawString(font, label, toggleX + 2, toggleY + 2, hover ? 0xFFFFFF55 : 0xFFFFFFFF, false);
    }

    private void renderUnsupportedNotice(GuiGraphics g) {
        if (currentCategory == null || recipeArea.getDraft() != null) return;
        String msg = "Editing not supported for this category";
        int tw = font.width(msg);
        int tx = leftPos + (imageWidth - tw) / 2;
        int ty = recipeAreaTop + 2;
        g.fill(tx - 3, ty - 2, tx + tw + 3, ty + font.lineHeight + 1, 0x88000000);
        g.drawString(font, msg, tx, ty, 0xFFFF5555, false);
    }

    private void renderNumberLabels(GuiGraphics g) {
        for (int i = 0; i < numericBoxes.size(); i++) {
            EditBox box = numericBoxes.get(i);
            if (!box.visible) continue;
            String label = currentFields.get(i).label();
            g.drawString(font, label, box.getX() - font.width(label) - 3, box.getY() + 2, 0xFFE0E0E0, false);
        }
    }

    private void renderDumpButton(GuiGraphics g, int mouseX, int mouseY) {
        dumpX = -1;
        if (recipeArea.getDraft() == null) return;
        dumpX = leftPos + imageWidth - DUMP_W - 2;
        dumpY = recipeAreaBottom - DUMP_H - 2;
        boolean hover = mouseX >= dumpX && mouseX < dumpX + DUMP_W
                     && mouseY >= dumpY && mouseY < dumpY + DUMP_H;
        if (hover) g.fill(dumpX - 1, dumpY - 1, dumpX + DUMP_W + 1, dumpY + DUMP_H + 1, 0x66FFFFFF);
        g.blit(DUMP_TEX, dumpX, dumpY, 0, 0, DUMP_W, DUMP_H, DUMP_W, DUMP_H);
    }

    @Override
    protected void containerTick() {
        super.containerTick();
        recipeArea.tick();
    }

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        if (tabBar != null && tabBar.mouseClicked(mx, my, button)) return true;

        if (button == 0 && toggleX >= 0
            && mx >= toggleX && mx < toggleX + toggleW
            && my >= toggleY && my < toggleY + toggleH) {
            toggleCraftingMode();
            return true;
        }
        if (button == 0 && dumpX >= 0
            && mx >= dumpX && mx < dumpX + DUMP_W
            && my >= dumpY && my < dumpY + DUMP_H) {
            doDump();
            return true;
        }

        // Tag bar: Ctrl+right-click anywhere on the bar clears input + result
        if (button == 1 && Screen.hasControlDown() && tagBar.isOverBar(mx, my)) {
            tagBar.clear();
            playClick();
            return true;
        }
        // Tag bar: namespace toggle
        if (button == 0 && tagBar.isOverNsSlot(mx, my)) {
            tagBar.toggleNamespace();
            playClick();
            return true;
        }
        // Tag bar result slot pickup → ghost cursor
        if (button == 0 && tagBar.isOverResultSlot(mx, my)) {
            ItemStack r = tagBar.getResult();
            this.ghostCursor = r.isEmpty() ? ItemStack.EMPTY : r.copy();
            return true;
        }

        if (recipeArea.mouseClicked(mx, my, button, ghostCursor)) return true;

        boolean overInvSlot = isOverInventorySlot(mx, my);
        boolean handled = super.mouseClicked(mx, my, button);

        // Vanilla-like: dropping the cursor outside any interactive region clears it
        if (!overInvSlot && !ghostCursor.isEmpty()) {
            ghostCursor = ItemStack.EMPTY;
        }
        return handled;
    }

    private boolean isOverInventorySlot(double mx, double my) {
        for (Slot s : menu.slots) {
            int sx = leftPos + s.x;
            int sy = topPos  + s.y;
            if (mx >= sx && mx < sx + 16 && my >= sy && my < sy + 16) return true;
        }
        return false;
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double delta) {
        if (tabBar != null && tabBar.mouseScrolled(mx, my, delta)) return true;
        if (recipeArea.mouseScrolled(mx, my, delta)) return true;
        return super.mouseScrolled(mx, my, delta);
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        // While any text field is focused, swallow container shortcuts (E to close, Q to drop,
        // 1-9 hotbar swap, middle-click pick) so they don't trigger while typing.
        // ESC and editing keys (arrows, backspace, etc.) propagate normally.
        if (isAnyTextFieldFocused()) {
            var opts = this.minecraft.options;
            if (opts.keyInventory.matches(keyCode, scanCode)) return true;
            if (opts.keyDrop.matches(keyCode, scanCode))      return true;
            if (opts.keyPickItem.matches(keyCode, scanCode))  return true;
            for (var k : opts.keyHotbarSlots) {
                if (k.matches(keyCode, scanCode)) return true;
            }
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    private boolean isAnyTextFieldFocused() {
        for (EditBox box : numericBoxes) {
            if (box.isFocused()) return true;
        }
        return tagBar.isEditBoxFocused();
    }

    @Override
    protected void slotClicked(Slot slot, int slotId, int mouseButton, ClickType type) {
        if (type != ClickType.PICKUP || slot == null) return;
        if (mouseButton == 0) {
            if (slot.hasItem()) {
                ItemStack stack = slot.getItem().copy();
                stack.setCount(1);
                this.ghostCursor = stack;
            } else {
                this.ghostCursor = ItemStack.EMPTY;
            }
        }
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}