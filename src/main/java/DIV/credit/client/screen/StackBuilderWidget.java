package DIV.credit.client.screen;

import DIV.credit.Credit;
import DIV.credit.client.draft.IngredientSpec;
import DIV.credit.client.jei.mek.MekanismIngredientAdapter;
import DIV.credit.jei.CraftPatternJeiPlugin;
import mezz.jei.api.forge.ForgeTypes;
import mezz.jei.api.runtime.IJeiRuntime;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.fml.ModList;

import java.util.function.Consumer;

/**
 * Duration/EUt 編集行の上に置く「中身 + 量」入力ヘルパー。
 * - スロット 1 つ：item/fluid/gas を JEI ドラッグ or ghost cursor からドロップで受け入れ
 * - 右の EditBox：量を数値入力
 * - 右クリ：base × counter (counter は drop 直後 1、右クリ毎に +1)
 *   - drop直後: base
 *   - 1 click: base × 2
 *   - 2 click: base × 3
 *   - ...
 *   - "3 つ目の値 = base × 3" になるよう counter+1 で更新
 * - 左クリ：ghost cursor として持ち上げる（量込み）
 * - Shift+右クリ：クリア
 * - EditBox 直接編集：新しい base 値、counter=1 にリセット
 */
public class StackBuilderWidget {

    public static final int W      = 60;
    public static final int H      = 18;
    public static final int SLOT_W = 16;

    private IngredientSpec content = IngredientSpec.EMPTY;
    private long baseAmount = 1000;
    private int multiplier = 1;

    private EditBox amountBox;
    private int x, y;
    private int slotX, slotY;
    private boolean visible = true;
    private boolean syncingFromSlot = false;

    private Consumer<IngredientSpec> onLeftClickPickup;

    public void init(Screen screen, Font font, int x, int y, Consumer<EditBox> register, Consumer<IngredientSpec> onPickup) {
        this.x = x;
        this.y = y;
        this.slotX = x + 1;
        this.slotY = y + 1;
        int boxX = slotX + SLOT_W + 4;
        int boxW = W - SLOT_W - 6;
        this.amountBox = new EditBox(font, boxX, y + 3, boxW, 12, Component.literal("amount"));
        this.amountBox.setMaxLength(10);
        this.amountBox.setValue("");
        this.amountBox.setResponder(this::onAmountTyped);
        register.accept(this.amountBox);
        this.onLeftClickPickup = onPickup;
    }

    public void setVisible(boolean v) {
        this.visible = v;
        if (this.amountBox != null) {
            this.amountBox.visible = v;
            this.amountBox.active  = v;
        }
        if (!v) {
            this.content = IngredientSpec.EMPTY;
            this.amountBox.setValue("");
        }
    }

    public boolean isVisible() {
        return visible;
    }

    public boolean isEditBoxFocused() {
        return amountBox != null && amountBox.isFocused();
    }

    public boolean isOverSlot(double mx, double my) {
        return visible
            && mx >= slotX && mx < slotX + SLOT_W
            && my >= slotY && my < slotY + SLOT_W;
    }

    public boolean isOverArea(double mx, double my) {
        return visible && mx >= x && mx < x + W && my >= y && my < y + H;
    }

    /** JEI ghost handler 用：slot 部分の screen rect。 */
    public net.minecraft.client.renderer.Rect2i getSlotRect() {
        return new net.minecraft.client.renderer.Rect2i(slotX, slotY, SLOT_W, SLOT_W);
    }

    /** JEI ghost handler / 既存タグバー pickup から呼ばれる。spec をスロットに配置 + 量初期化。 */
    public void setContent(IngredientSpec spec) {
        if (spec == null || spec.isEmpty()) {
            this.content = IngredientSpec.EMPTY;
            this.baseAmount = 1000;
            this.multiplier = 1;
            syncingFromSlot = true;
            try { this.amountBox.setValue(""); } finally { syncingFromSlot = false; }
            return;
        }
        this.baseAmount = defaultAmountFor(spec);
        this.multiplier = 1;
        this.content = withAmount(spec, baseAmount);
        syncingFromSlot = true;
        try { this.amountBox.setValue(String.valueOf(baseAmount)); } finally { syncingFromSlot = false; }
    }

    /** 現在の中身（量込み）。 */
    public IngredientSpec getContent() {
        return content;
    }

    // --- Undo snapshot 用 ---
    public long getBaseAmount() { return baseAmount; }
    public int  getMultiplier() { return multiplier; }

    /** Undo restore 用：3 値を直接書き戻し。default の baseAmount 計算は走らない。 */
    public void restoreState(IngredientSpec spec, long baseAmt, int mult) {
        this.content = spec == null ? IngredientSpec.EMPTY : spec;
        this.baseAmount = Math.max(1, baseAmt);
        this.multiplier = Math.max(1, mult);
        if (amountBox != null) {
            syncingFromSlot = true;
            try {
                amountBox.setValue(content.isEmpty() ? "" : String.valueOf(content.count()));
            } finally { syncingFromSlot = false; }
        }
    }

    /**
     * クリック処理。BuilderScreen から呼ばれる。
     * 左クリ：drag 開始用に content を返す（false 返してデフォルト処理に流す）
     * 右クリ：multiplier 操作（true で消費）
     */
    public boolean mouseClicked(double mx, double my, int button) {
        if (!isOverSlot(mx, my)) return false;
        if (content.isEmpty()) return true;  // empty スロットは無視（ただし drag pickup 不可なので true 返して飲む）
        if (button == 1) {
            if (Screen.hasShiftDown()) {
                setContent(IngredientSpec.EMPTY);
            } else {
                multiplier++;
                long newAmount = Math.min(baseAmount * multiplier, Integer.MAX_VALUE);
                content = withAmount(content, newAmount);
                syncingFromSlot = true;
                try { amountBox.setValue(String.valueOf(newAmount)); } finally { syncingFromSlot = false; }
            }
            return true;
        }
        // 左クリ：BuilderScreen 側で drag 開始させるため false 返す
        return false;
    }

    public void render(GuiGraphics g, int mouseX, int mouseY) {
        if (!visible) return;
        // Slot bg (MC 標準風)
        g.fill(slotX - 1, slotY - 1, slotX + SLOT_W + 1, slotY + SLOT_W + 1, 0xFF373737);
        g.fill(slotX,     slotY,     slotX + SLOT_W,     slotY + SLOT_W,     0xFFC6C6C6);
        if (isOverSlot(mouseX, mouseY)) {
            g.fill(slotX, slotY, slotX + SLOT_W, slotY + SLOT_W, 0x55FFFFFF);
        }
        // Content render
        if (!content.isEmpty()) {
            renderContent(g);
        }
    }

    private void renderContent(GuiGraphics g) {
        try {
            if (content instanceof IngredientSpec.Item it && !it.stack().isEmpty()) {
                g.renderItem(it.stack(), slotX, slotY);
                g.renderItemDecorations(Minecraft.getInstance().font, it.stack(), slotX, slotY);
                return;
            }
            long ticks = Minecraft.getInstance().level != null
                ? Minecraft.getInstance().level.getGameTime()
                : 0;
            if (content instanceof IngredientSpec.Tag tg && tg.tagId() != null) {
                ItemStack cycled = DIV.credit.client.tag.TagItemHelper
                    .cycledItemFromTag(tg.tagId(), ticks);
                if (cycled != null && !cycled.isEmpty()) {
                    g.renderItem(cycled, slotX, slotY);
                }
                return;
            }
            IJeiRuntime rt = CraftPatternJeiPlugin.runtime;
            if (rt == null) return;
            if (content instanceof IngredientSpec.Fluid fl && !fl.stack().isEmpty()) {
                rt.getIngredientManager().getIngredientRenderer(ForgeTypes.FLUID_STACK)
                    .render(g, fl.stack(), slotX, slotY);
                return;
            }
            if (content instanceof IngredientSpec.FluidTag ft && ft.tagId() != null) {
                net.minecraftforge.fluids.FluidStack cycled = DIV.credit.client.tag.TagItemHelper
                    .cycledFluidFromTag(ft.tagId(), ft.amount(), ticks);
                if (cycled != null && !cycled.isEmpty()) {
                    rt.getIngredientManager().getIngredientRenderer(ForgeTypes.FLUID_STACK)
                        .render(g, cycled, slotX, slotY);
                }
                return;
            }
            if (content instanceof IngredientSpec.Gas gas && gas.gasId() != null) {
                if (!ModList.get().isLoaded("mekanism")) return;
                renderGas(g, rt, gas, slotX, slotY);
            }
        } catch (Exception ignored) {}
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static void renderGas(GuiGraphics g, IJeiRuntime rt, IngredientSpec.Gas gas, int x, int y) {
        var gasStack = MekanismIngredientAdapter.toGasStack(gas);
        if (gasStack.isEmpty()) return;
        var type = mekanism.client.jei.MekanismJEI.TYPE_GAS;
        var renderer = (mezz.jei.api.ingredients.IIngredientRenderer) rt.getIngredientManager().getIngredientRenderer(type);
        renderer.render(g, gasStack, x, y);
    }

    public void renderTooltip(GuiGraphics g, Font font, int mouseX, int mouseY) {
        if (!visible) return;
        if (isOverSlot(mouseX, mouseY) && !content.isEmpty()) {
            g.renderTooltip(font, Component.literal(describe(content) + " ×" + multiplier), mouseX, mouseY);
        }
    }

    private static String describe(IngredientSpec s) {
        if (s instanceof IngredientSpec.Item it) return it.stack().getDisplayName().getString() + " (" + it.stack().getCount() + ")";
        if (s instanceof IngredientSpec.Tag tg) return "#" + tg.tagId() + " ×" + tg.count();
        if (s instanceof IngredientSpec.Fluid fl) return fl.stack().getDisplayName().getString() + " (" + fl.stack().getAmount() + "mB)";
        if (s instanceof IngredientSpec.FluidTag ft) return "#" + ft.tagId() + " (" + ft.amount() + "mB, fluid)";
        if (s instanceof IngredientSpec.Gas gas) return gas.gasId() + " (" + gas.amount() + "mB)";
        return s.toString();
    }

    private void onAmountTyped(String s) {
        if (syncingFromSlot) return;
        if (content.isEmpty() || s.isBlank()) return;
        try {
            long v = Long.parseLong(s);
            if (v < 1) v = 1;
            this.baseAmount = v;
            this.multiplier = 1;
            this.content = withAmount(content, v);
        } catch (NumberFormatException ignored) {}
    }

    private static long defaultAmountFor(IngredientSpec spec) {
        if (spec instanceof IngredientSpec.Item) return 1;
        if (spec instanceof IngredientSpec.Tag) return 1;
        if (spec instanceof IngredientSpec.Fluid || spec instanceof IngredientSpec.FluidTag) {
            return DIV.credit.CreditConfig.FLUID_DEFAULT_AMOUNT.get();
        }
        if (spec instanceof IngredientSpec.Gas) {
            return DIV.credit.CreditConfig.GAS_DEFAULT_AMOUNT.get();
        }
        return 1000;
    }

    private static IngredientSpec withAmount(IngredientSpec spec, long amount) {
        int n = (int) Math.min(Integer.MAX_VALUE, Math.max(1, amount));
        if (spec instanceof IngredientSpec.Item it) {
            ItemStack copy = it.stack().copy();
            copy.setCount(Math.min(n, 64));
            return new IngredientSpec.Item(copy);
        }
        return IngredientSpec.withCount(spec, n);
    }
}
