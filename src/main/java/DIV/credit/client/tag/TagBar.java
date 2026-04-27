package DIV.credit.client.tag;

import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraftforge.fml.ModList;

import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * レシピエリア下部のタグ検索バー。
 * 構成（左→右）：[namespace toggle slot] [EditBox] [result slot]
 * - namespace toggle はクリックで minecraft (grass_block) / forge (anvil) を切替
 * - EditBox に namespace 抜きで打てば現在の namespace が補完される。`ns:path` 形式なら full 指定として尊重
 * - 有効なタグなら result slot に NBT 付き name_tag が現れる
 */
public class TagBar {

    public static final int H      = 18;
    public static final int SLOT_W = 16;

    private static final String NS_MINECRAFT = "minecraft";
    private static final String NS_FORGE     = "forge";

    /** 短期記憶：namespace は static で保持。 */
    private static String currentNamespace = NS_MINECRAFT;

    private EditBox box;
    private ItemStack resultStack = ItemStack.EMPTY;
    private int x, y, width;
    private int nsSlotX,     nsSlotY;
    private int resultSlotX, resultSlotY;
    private boolean visible = true;

    public void init(Screen screen, Font font, int x, int y, int width, Consumer<EditBox> register) {
        this.x = x;
        this.y = y;
        this.width = width;
        this.nsSlotX     = x + 1;
        this.nsSlotY     = y + 1;
        this.resultSlotX = x + width - SLOT_W - 1;
        this.resultSlotY = y + 1;
        int boxX = nsSlotX + SLOT_W + 4;
        int boxW = resultSlotX - boxX - 2;
        this.box = new EditBox(font, boxX, y + 3, boxW, 12, Component.literal("Tag"));
        this.box.setMaxLength(80);
        this.box.setResponder(this::onChange);
        register.accept(this.box);
    }

    public void setVisible(boolean v) {
        this.visible = v;
        if (this.box != null) {
            this.box.visible = v;
            this.box.active  = v;
        }
        if (!v) this.resultStack = ItemStack.EMPTY;
    }

    public boolean isVisible() {
        return visible;
    }

    public boolean isEditBoxFocused() {
        return box != null && box.isFocused();
    }

    public ItemStack getNsIcon() {
        return new ItemStack(currentNamespace.equals(NS_FORGE) ? Items.ANVIL : Items.GRASS_BLOCK);
    }

    public boolean isOverNsSlot(double mx, double my) {
        return visible
            && mx >= nsSlotX && mx < nsSlotX + SLOT_W
            && my >= nsSlotY && my < nsSlotY + SLOT_W;
    }

    public boolean isOverResultSlot(double mx, double my) {
        return visible
            && mx >= resultSlotX && mx < resultSlotX + SLOT_W
            && my >= resultSlotY && my < resultSlotY + SLOT_W;
    }

    public boolean isOverBar(double mx, double my) {
        return visible && mx >= x && mx < x + width && my >= y && my < y + H;
    }

    /** 入力と結果を消去。Ctrl+右クリで呼ばれる。 */
    public void clear() {
        if (box != null) box.setValue("");
        resultStack = ItemStack.EMPTY;
    }

    public ItemStack getResult() {
        return resultStack;
    }

    public void toggleNamespace() {
        currentNamespace = currentNamespace.equals(NS_MINECRAFT) ? NS_FORGE : NS_MINECRAFT;
        if (box != null) onChange(box.getValue());
    }

    public void render(GuiGraphics g, int mouseX, int mouseY) {
        if (!visible) return;
        // Namespace slot
        drawSlot(g, nsSlotX, nsSlotY, isOverNsSlot(mouseX, mouseY));
        g.renderItem(getNsIcon(), nsSlotX, nsSlotY);
        // Result slot
        drawSlot(g, resultSlotX, resultSlotY, isOverResultSlot(mouseX, mouseY));
        if (!resultStack.isEmpty()) {
            g.renderItem(resultStack, resultSlotX, resultSlotY);
        }
    }

    private static void drawSlot(GuiGraphics g, int sx, int sy, boolean hover) {
        g.fill(sx - 1, sy - 1, sx + SLOT_W + 1, sy + SLOT_W + 1, 0xFF8B8B8B);
        g.fill(sx,     sy,     sx + SLOT_W,     sy + SLOT_W,     0xFF373737);
        if (hover) g.fill(sx, sy, sx + SLOT_W, sy + SLOT_W, 0x55FFFFFF);
    }

    public void renderTooltip(GuiGraphics g, Font font, int mouseX, int mouseY) {
        if (!visible) return;
        if (isOverNsSlot(mouseX, mouseY)) {
            g.renderTooltip(font, Component.literal("Default namespace: ")
                .append(Component.literal(currentNamespace).withStyle(ChatFormatting.AQUA)),
                mouseX, mouseY);
        } else if (!resultStack.isEmpty() && isOverResultSlot(mouseX, mouseY)) {
            g.renderTooltip(font, resultStack, mouseX, mouseY);
        }
    }

    private void onChange(String s) {
        String input = s == null ? "" : s.trim();
        if (input.isEmpty()) {
            resultStack = ItemStack.EMPTY;
            return;
        }

        // 例外処理: GT programmed circuit。例: "gtceu:circuit(7)" or "circuit(7)"
        ItemStack circuit = tryProgrammedCircuit(input);
        if (!circuit.isEmpty()) {
            resultStack = circuit;
            return;
        }

        if (input.startsWith("#")) input = input.substring(1);

        ResourceLocation rl;
        if (input.contains(":")) {
            rl = ResourceLocation.tryParse(input);
        } else {
            rl = ResourceLocation.tryBuild(currentNamespace, input);
        }

        // 自動判定：item tag を優先、なければ fluid tag
        if (rl != null && TagItemHelper.isKnownItemTag(rl)) {
            resultStack = TagItemHelper.createTagNameTag(rl);
        } else if (rl != null && TagItemHelper.isKnownFluidTag(rl)) {
            resultStack = TagItemHelper.createFluidTagNameTag(rl, 1000);
        } else {
            resultStack = ItemStack.EMPTY;
        }
    }

    private static final Pattern CIRCUIT_PATTERN =
        Pattern.compile("^(?:gtceu:)?circuit\\((\\d{1,2})\\)$");
    private static final int CIRCUIT_MAX = 32;
    private static final ResourceLocation CIRCUIT_ITEM_ID =
        new ResourceLocation("gtceu", "programmed_circuit");

    /** "gtceu:circuit(N)" or "circuit(N)" → 該当 configuration の programmed circuit ItemStack。 */
    private static ItemStack tryProgrammedCircuit(String input) {
        if (!ModList.get().isLoaded("gtceu")) return ItemStack.EMPTY;
        Matcher m = CIRCUIT_PATTERN.matcher(input);
        if (!m.matches()) return ItemStack.EMPTY;
        int config;
        try { config = Integer.parseInt(m.group(1)); } catch (NumberFormatException e) { return ItemStack.EMPTY; }
        if (config < 0 || config > CIRCUIT_MAX) return ItemStack.EMPTY;
        Item item = BuiltInRegistries.ITEM.get(CIRCUIT_ITEM_ID);
        if (item == Items.AIR) return ItemStack.EMPTY;
        ItemStack stack = new ItemStack(item);
        stack.getOrCreateTag().putInt("Configuration", config);
        return stack;
    }
}