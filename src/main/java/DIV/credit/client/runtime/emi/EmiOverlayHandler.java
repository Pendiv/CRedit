package DIV.credit.client.runtime.emi;

import DIV.credit.Credit;
import DIV.credit.client.draft.DraftStore;
import DIV.credit.client.jei.DeleteHandler;
import DIV.credit.client.jei.EditDeleteTracker;
import DIV.credit.client.jei.EditHandler;
import DIV.credit.jei.CraftPatternJeiPlugin;
import dev.emi.emi.api.recipe.EmiRecipe;
import dev.emi.emi.api.widget.RecipeFillButtonWidget;
import dev.emi.emi.api.widget.Widget;
import dev.emi.emi.jemi.JemiRecipe;
import dev.emi.emi.screen.RecipeScreen;
import dev.emi.emi.screen.WidgetGroup;
import mezz.jei.api.gui.IRecipeLayoutDrawable;
import mezz.jei.api.recipe.category.IRecipeCategory;
import mezz.jei.api.runtime.IJeiRuntime;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.neoforge.client.event.ScreenEvent;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import org.jetbrains.annotations.Nullable;

import java.lang.reflect.Field;
import java.util.Collections;
import java.util.List;

/**
 * EMI {@link RecipeScreen} 上に credit の overlay を適用:
 * <ul>
 *   <li><b>EDIT</b>: EMI default の「+」 (= {@link RecipeFillButtonWidget}) が表示されてる recipe では click 乗っ取り、
 *       表示されてない recipe では自前「+」 を描画 (= 全 credit-editable recipe で edit 可能化)</li>
 *   <li><b>DELETE</b>: 自前 button (= ゴミ箱 icon) を「+」 の直下に配置</li>
 * </ul>
 *
 * <h3>EMI「+」 未表示への対応</h3>
 * EMI は {@link dev.emi.emi.registry.EmiRecipeFiller#isSupported} true な recipe (= JEMI bridge 経由
 * の JEI transfer handler 対応分、 主に vanilla crafting) にしか「+」 を表示しない。
 * 残りの credit-editable recipe (= 多くの mod 機械系) には自前「+」 を描画して同等の click 経路を提供。
 *
 * <h3>plugin 不採用</h3>
 * EmiPlugin で EmiRecipeHandler を register すれば EMI 自身が「+」 を出すようになるが、
 * @EmiEntrypoint annotation 追加が Phase 2c で JEI plugin 衝突を引き起こした実績あり (= 原因究明未完)。
 * 安全側で自前描画を選択。
 */
@SuppressWarnings({"removal", "deprecation"})
@EventBusSubscriber(modid = Credit.MODID, value = Dist.CLIENT)
public final class EmiOverlayHandler {

    private static final ResourceLocation DELETE_TEX  = ResourceLocation.fromNamespaceAndPath(Credit.MODID, "ui/delete.png");
    private static final ResourceLocation DELETED_TEX = ResourceLocation.fromNamespaceAndPath(Credit.MODID, "ui/deleted.png");
    private static final ResourceLocation EDITED_TEX  = ResourceLocation.fromNamespaceAndPath(Credit.MODID, "ui/edited.png");
    private static final int BTN_W = 16, BTN_H = 16;
    /** recipe 右端からこの距離だけ右に button column。 */
    private static final int RIGHT_OFFSET = 4;
    /** EMI default の縦 step (= RecipeDisplay.addButtons の yStep)。 */
    private static final int STEP = 14;

    private EmiOverlayHandler() {}

    // ─────────────── reflection cache ───────────────
    private static Field CURRENT_PAGE_FIELD;
    private static boolean reflectInitFailed = false;

    private static boolean ensureReflect() {
        if (reflectInitFailed) return false;
        if (CURRENT_PAGE_FIELD != null) return true;
        try {
            CURRENT_PAGE_FIELD = RecipeScreen.class.getDeclaredField("currentPage");
            CURRENT_PAGE_FIELD.setAccessible(true);
            return true;
        } catch (Throwable t) {
            Credit.LOGGER.warn("[C1005] EmiOverlayHandler: RecipeScreen.currentPage reflection failed: {}", t.toString());
            reflectInitFailed = true;
            return false;
        }
    }

    @SuppressWarnings("unchecked")
    private static List<WidgetGroup> currentPageOf(RecipeScreen screen) {
        if (!ensureReflect()) return Collections.emptyList();
        try {
            Object v = CURRENT_PAGE_FIELD.get(screen);
            if (v instanceof List<?> list) return (List<WidgetGroup>) list;
        } catch (Throwable t) {
            Credit.LOGGER.debug("[CraftPattern] EmiOverlayHandler: currentPage read failed: {}", t.toString());
        }
        return Collections.emptyList();
    }

    // ─────────────── render ───────────────

    @SubscribeEvent
    public static void onScreenRender(ScreenEvent.Render.Post event) {
        if (!DIV.credit.CreditConfig.isEmiIntegrationEnabled()) return;
        Screen screen = event.getScreen();
        if (!(screen instanceof RecipeScreen rs)) return;
        GuiGraphics g = event.getGuiGraphics();
        int mx = event.getMouseX(), my = event.getMouseY();
        for (WidgetGroup group : currentPageOf(rs)) {
            renderForGroup(g, mx, my, group);
        }
    }

    /** 1 recipe 分の overlay 描画。 EDIT button (EMI 既存 or 自前) + DELETE button。 */
    private static void renderForGroup(GuiGraphics g, int mx, int my, WidgetGroup group) {
        if (group == null || group.recipe == null) return;
        Context ctx = extractContext(group.recipe);
        if (ctx == null) return;
        if (!DraftStore.isSupportedCategory(ctx.jeiCategory)) return;

        boolean isEdited  = EditDeleteTracker.INSTANCE.isEdited(ctx.recipeId);
        boolean isDeleted = EditDeleteTracker.INSTANCE.isDeleted(ctx.recipeId);
        Font font = Minecraft.getInstance().font;

        // EDIT button 位置 (= EMI「+」 ある場合はその bounds、 無い場合は自前位置)
        RecipeFillButtonWidget emiFill = findFillButton(group);
        int editX, editY;
        if (emiFill != null) {
            var b = emiFill.getBounds();
            editX = group.x + b.x();
            editY = group.y + b.y();
        } else {
            editX = group.x + group.width + RIGHT_OFFSET;
            editY = group.y;
            // 自前「+」 を緑系 box + 中央「+」 で描画 (= EMI「+」 と視覚的差別化)
            g.fill(editX,     editY,     editX + 16, editY + 16, 0xFF223322);  // dark border
            g.fill(editX + 1, editY + 1, editX + 15, editY + 15, 0xFF44AA44);  // green inner
            g.drawCenteredString(font, "+", editX + 8, editY + 4, 0xFFFFFFFF);
            if (inBox(mx, my, editX, editY, 16, 16) && !isEdited) {
                g.fill(editX - 1, editY - 1, editX + 17, editY + 17, 0x44FFFFFF);  // hover halo
            }
        }

        // EDITED overlay (= 「+」 の上に重ね描き、 EMI/自前 両方共通)
        if (isEdited) {
            g.blit(EDITED_TEX, editX, editY, 0, 0, 16, 16, 16, 16);
        }
        // v3.3.x: tooltip は EMI 側の RecipeFillButtonWidget.getTooltip mixin が出す (= 重複回避)
        // 自前「+」 の場合は EMI tooltip が無いので、 こちら側で表示
        if (emiFill == null && inBox(mx, my, editX, editY, 16, 16)) {
            String key = isEdited ? "gui.credit.edit.already" : "gui.credit.edit.button";
            g.renderTooltip(font,
                List.of(Component.translatable(key),
                    Component.literal(String.valueOf(ctx.recipeId)).withStyle(ChatFormatting.GRAY)),
                java.util.Optional.empty(), mx, my);
        }

        // DELETE button (= EDIT button の 1 step 下)
        int delX = editX;
        int delY = editY + STEP;
        boolean delHover = inBox(mx, my, delX, delY, BTN_W, BTN_H);
        if (delHover && !isDeleted) {
            g.fill(delX - 1, delY - 1, delX + BTN_W + 1, delY + BTN_H + 1, 0x66FFFFFF);
        }
        g.blit(isDeleted ? DELETED_TEX : DELETE_TEX, delX, delY, 0, 0, BTN_W, BTN_H, BTN_W, BTN_H);
        if (delHover) {
            String key = isDeleted ? "gui.credit.delete.already" : "gui.credit.delete.button";
            g.renderTooltip(font,
                List.of(Component.translatable(key),
                    Component.literal(String.valueOf(ctx.recipeId)).withStyle(ChatFormatting.GRAY)),
                java.util.Optional.empty(), mx, my);
        }
    }

    // ─────────────── click ───────────────

    @SubscribeEvent
    public static void onMousePressed(ScreenEvent.MouseButtonPressed.Pre event) {
        if (!DIV.credit.CreditConfig.isEmiIntegrationEnabled()) return;
        Screen screen = event.getScreen();
        if (!(screen instanceof RecipeScreen rs)) return;
        if (event.getButton() != 0) return;
        int mx = (int) event.getMouseX(), my = (int) event.getMouseY();
        for (WidgetGroup group : currentPageOf(rs)) {
            if (handleClick(group, mx, my)) {
                event.setCanceled(true);
                return;
            }
        }
    }

    private static boolean handleClick(WidgetGroup group, int mx, int my) {
        if (group == null || group.recipe == null) return false;
        Context ctx = extractContext(group.recipe);
        if (ctx == null) return false;
        if (!DraftStore.isSupportedCategory(ctx.jeiCategory)) return false;

        // EDIT button click (= EMI「+」 hijack or 自前「+」)
        RecipeFillButtonWidget emiFill = findFillButton(group);
        int editX, editY;
        if (emiFill != null) {
            var b = emiFill.getBounds();
            editX = group.x + b.x();
            editY = group.y + b.y();
        } else {
            editX = group.x + group.width + RIGHT_OFFSET;
            editY = group.y;
        }
        if (inBox(mx, my, editX, editY, 16, 16)) {
            if (EditDeleteTracker.INSTANCE.isEdited(ctx.recipeId)) return true;  // consume only
            dispatchEdit(ctx);
            return true;
        }

        // DELETE button click
        int delX = editX;
        int delY = editY + STEP;
        if (inBox(mx, my, delX, delY, BTN_W, BTN_H)) {
            if (EditDeleteTracker.INSTANCE.isDeleted(ctx.recipeId)) return true;
            DeleteHandler.handle(ctx.recipeId);
            return true;
        }
        return false;
    }

    private static boolean inBox(int mx, int my, int x, int y, int w, int h) {
        return mx >= x && my >= y && mx < x + w && my < y + h;
    }

    // ─────────────── helpers ───────────────

    /** WidgetGroup.widgets から RecipeFillButtonWidget を探す。 無ければ null。 */
    @Nullable
    private static RecipeFillButtonWidget findFillButton(WidgetGroup group) {
        if (group == null || group.widgets == null) return null;
        for (Widget w : group.widgets) {
            if (w instanceof RecipeFillButtonWidget fb) return fb;
        }
        return null;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static void dispatchEdit(Context ctx) {
        IJeiRuntime rt = CraftPatternJeiPlugin.runtime;
        if (rt == null) {
            Credit.LOGGER.warn("[C101] EmiOverlayHandler: JEI runtime null, cannot dispatch EDIT");
            return;
        }
        try {
            var focus = rt.getJeiHelpers().getFocusFactory().getEmptyFocusGroup();
            IRecipeCategory rawCat = ctx.jeiCategory;
            java.util.Optional opt = rt.getRecipeManager()
                .createRecipeLayoutDrawable(rawCat, ctx.jeiRecipe, focus);
            Object raw = opt.orElse(null);
            if (!(raw instanceof IRecipeLayoutDrawable<?> drawable)) {
                Credit.LOGGER.warn("[C102] EmiOverlayHandler: drawable build failed for {}", ctx.recipeId);
                return;
            }
            EditHandler.handle((IRecipeLayoutDrawable) drawable, rawCat, ctx.jeiRecipe, ctx.recipeId);
        } catch (Throwable t) {
            Credit.LOGGER.warn("[C1006] EmiOverlayHandler: dispatchEdit failed for {}: {}",
                ctx.recipeId, t.toString());
        }
    }

    @Nullable
    private static Context extractContext(EmiRecipe emiRecipe) {
        if (!(emiRecipe instanceof JemiRecipe<?> jemi)) return null;
        if (jemi.category == null || jemi.recipe == null || jemi.originalId == null) return null;
        return new Context(jemi.category, jemi.recipe, jemi.originalId);
    }

    private record Context(IRecipeCategory<?> jeiCategory, Object jeiRecipe, ResourceLocation recipeId) {}
}
