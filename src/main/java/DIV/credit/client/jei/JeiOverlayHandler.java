package DIV.credit.client.jei;

import DIV.credit.Credit;
import DIV.credit.client.draft.DraftStore;
import mezz.jei.api.gui.IRecipeLayoutDrawable;
import mezz.jei.api.recipe.category.IRecipeCategory;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.renderer.Rect2i;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ScreenEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.jetbrains.annotations.Nullable;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.List;

/**
 * JEI's RecipesGui に重ねる overlay handler。
 * <p>JEI 内部 (mezz.jei.gui.*) はビルド時 classpath に無いため、すべて reflection 経由。
 * <p>v2.0.0。
 */
@SuppressWarnings({"removal", "deprecation"}) // ResourceLocation(String,String) は 1.20.6+ で removal 予定。1.20.1 では正規 API。
@Mod.EventBusSubscriber(modid = Credit.MODID, value = Dist.CLIENT)
public final class JeiOverlayHandler {

    private static final ResourceLocation DELETE_TEX = new ResourceLocation(Credit.MODID, "ui/delete.png");
    private static final int BTN_W = 16, BTN_H = 16;
    private static final int BTN_SPACING = 2;
    /** "+" 転送ボタンと DELETE 間の縦オフセット。bookmark ボタン (1 段上) を避けるため 2 段上に置く。 */
    private static final int DELETE_Y_OFFSET = 2 * (BTN_H + BTN_SPACING);

    private JeiOverlayHandler() {}

    // ─────────────────────────── lifecycle ───────────────────────────

    @SubscribeEvent
    public static void onScreenInit(ScreenEvent.Init.Post event) {
        if (!OriginTracker.isActive()) return;
        if (!isRecipesGui(event.getScreen())) {
            Credit.LOGGER.info("[JeiOverlay] OriginTracker exit (screen now {})",
                event.getScreen() == null ? "null" : event.getScreen().getClass().getName());
            OriginTracker.exit();
            // ユーザー自発的に別 screen 開いた場合は parent 復元しない
            OriginTracker.clearParent();
            loggedRender = false;
        }
    }

    /**
     * v2.2.5: JEI を ESC で閉じて world に戻った場合 (screen=null) 、
     * 元の HistoryScreen 等に戻す。Init.Post は null screen で fire しないため tick で検出。
     */
    @SubscribeEvent
    public static void onClientTick(net.minecraftforge.event.TickEvent.ClientTickEvent event) {
        if (event.phase != net.minecraftforge.event.TickEvent.Phase.END) return;
        if (!OriginTracker.isActive()) return;
        Minecraft mc = Minecraft.getInstance();
        // JEI active のはずなのに screen が null か RecipesGui じゃない → JEI 閉じた
        if (mc.screen != null && isRecipesGui(mc.screen)) return;
        net.minecraft.client.gui.screens.Screen parent = OriginTracker.getParentScreen();
        OriginTracker.exit();
        OriginTracker.clearParent();
        loggedRender = false;
        if (parent != null && mc.screen == null) {
            // ESC で world に戻った状態 → parent 復元 (HistoryScreen 等)
            Credit.LOGGER.info("[JeiOverlay] Restoring parent screen: {}", parent.getClass().getName());
            mc.setScreen(parent);
        }
    }

    // ─────────────────────────── render ───────────────────────────

    private static boolean loggedRender = false;

    /**
     * JEI 描画前に「+」を強制可視化。Render.Post で render 後に visible=true にすると
     * 次フレームの JEI tick が visible=false に戻し、結果ちらつく。Pre で確定させる。
     */
    @SubscribeEvent
    public static void onScreenRenderPre(ScreenEvent.Render.Pre event) {
        if (!OriginTracker.isActive()) return;
        Screen screen = event.getScreen();
        if (!isRecipesGui(screen)) return;
        for (Object wrapped : readLayoutsList(screen)) {
            IRecipeLayoutDrawable<?> layout = readRecipeLayout(wrapped);
            if (layout == null) continue;
            IRecipeCategory<?> category = layout.getRecipeCategory();
            if (!DraftStore.isSupportedCategory(category)) continue;
            Object recipe = layout.getRecipe();
            ResourceLocation rid = safeRegistryNameUnchecked(category, recipe);
            Recipe<?> mcRecipe = (recipe instanceof Recipe<?> r) ? r : null;
            if (!DynamicRecipeDetector.isEditableOrDeletable(mcRecipe, rid)) continue;
            forceTransferButtonVisible(wrapped);
        }
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static ResourceLocation safeRegistryNameUnchecked(IRecipeCategory category, Object recipe) {
        if (recipe == null) return null;
        try { return category.getRegistryName(recipe); } catch (Exception e) { return null; }
    }

    @SubscribeEvent
    public static void onScreenRender(ScreenEvent.Render.Post event) {
        if (!OriginTracker.isActive()) return;
        Screen screen = event.getScreen();
        if (!isRecipesGui(screen)) {
            if (!loggedRender) {
                Credit.LOGGER.info("[JeiOverlay] active but screen={} (not RecipesGui)",
                    screen == null ? "null" : screen.getClass().getName());
                loggedRender = true;
            }
            return;
        }

        List<Object> layouts = readLayoutsList(screen);
        if (!loggedRender) {
            Credit.LOGGER.info("[JeiOverlay] render fired: RecipesGui detected, layouts={}", layouts.size());
            loggedRender = true;
        }
        if (layouts.isEmpty()) return;

        GuiGraphics g = event.getGuiGraphics();
        int mx = event.getMouseX(), my = event.getMouseY();
        for (Object wrapped : layouts) {
            renderForLayout(g, mx, my, wrapped);
        }
    }

    private static void renderForLayout(GuiGraphics g, int mx, int my, Object wrapped) {
        IRecipeLayoutDrawable<?> layout = readRecipeLayout(wrapped);
        if (layout == null) return;
        renderForLayoutTyped(g, mx, my, layout, wrapped);
    }

    private static <R> void renderForLayoutTyped(GuiGraphics g, int mx, int my,
                                                  IRecipeLayoutDrawable<R> layout, Object wrapped) {
        IRecipeCategory<R> category = layout.getRecipeCategory();
        if (!DraftStore.isSupportedCategory(category)) return;

        R recipe = layout.getRecipe();
        ResourceLocation recipeId = safeRegistryName(category, recipe);
        Recipe<?> mcRecipe = (recipe instanceof Recipe<?> r) ? r : null;
        if (!DynamicRecipeDetector.isEditableOrDeletable(mcRecipe, recipeId)) return;

        // "+" 強制可視は Render.Pre 側で行う（こちらは描画 / hover のみ）。
        Rect2i btn = computeDeleteBounds(layout);
        boolean hover = mx >= btn.getX() && mx < btn.getX() + btn.getWidth()
                     && my >= btn.getY() && my < btn.getY() + btn.getHeight();
        if (hover) g.fill(btn.getX() - 1, btn.getY() - 1,
                          btn.getX() + BTN_W + 1, btn.getY() + BTN_H + 1, 0x66FFFFFF);
        g.blit(DELETE_TEX, btn.getX(), btn.getY(), 0, 0, BTN_W, BTN_H, BTN_W, BTN_H);
        if (hover) {
            // ここに来る時点で recipeId は isEditableOrDeletable で非 null 保証
            g.renderTooltip(net.minecraft.client.Minecraft.getInstance().font,
                List.of(
                    Component.translatable("gui.credit.delete.button"),
                    Component.literal(String.valueOf(recipeId)).withStyle(net.minecraft.ChatFormatting.GRAY)
                ),
                java.util.Optional.empty(), mx, my);
        }
    }

    private static Rect2i computeDeleteBounds(IRecipeLayoutDrawable<?> layout) {
        Rect2i layoutArea = layout.getRect();
        Rect2i transferArea = layout.getRecipeTransferButtonArea();
        int x = transferArea.getX() + layoutArea.getX();
        int y = transferArea.getY() + layoutArea.getY() - DELETE_Y_OFFSET;
        return new Rect2i(x, y, BTN_W, BTN_H);
    }

    // ─────────────────────────── click handling ───────────────────────────

    @SubscribeEvent
    public static void onMousePressed(ScreenEvent.MouseButtonPressed.Pre event) {
        if (!OriginTracker.isActive()) return;
        Screen screen = event.getScreen();
        if (!isRecipesGui(screen)) return;
        if (event.getButton() != 0) return;

        double mx = event.getMouseX(), my = event.getMouseY();
        for (Object wrapped : readLayoutsList(screen)) {
            IRecipeLayoutDrawable<?> layout = readRecipeLayout(wrapped);
            if (layout == null) continue;
            if (handleClickTyped(layout, mx, my)) {
                event.setCanceled(true);
                return;
            }
        }
    }

    private static <R> boolean handleClickTyped(IRecipeLayoutDrawable<R> layout, double mx, double my) {
        IRecipeCategory<R> category = layout.getRecipeCategory();
        if (!DraftStore.isSupportedCategory(category)) return false;

        R recipe = layout.getRecipe();
        ResourceLocation recipeId = safeRegistryName(category, recipe);
        Recipe<?> mcRecipe = (recipe instanceof Recipe<?> r) ? r : null;
        if (!DynamicRecipeDetector.isEditableOrDeletable(mcRecipe, recipeId)) return false;

        Rect2i del = computeDeleteBounds(layout);
        if (mx >= del.getX() && mx < del.getX() + del.getWidth()
            && my >= del.getY() && my < del.getY() + del.getHeight()) {
            DeleteHandler.handle(recipeId);
            return true;
        }
        // "+" hijack → EDIT 遷移
        Rect2i layoutArea = layout.getRect();
        Rect2i transferArea = layout.getRecipeTransferButtonArea();
        int tx = transferArea.getX() + layoutArea.getX();
        int ty = transferArea.getY() + layoutArea.getY();
        int tw = transferArea.getWidth();
        int th = transferArea.getHeight();
        if (mx >= tx && mx < tx + tw && my >= ty && my < ty + th) {
            EditHandler.handle(layout, category, recipe, recipeId);
            return true;
        }
        return false;
    }

    // ─────────────────────────── reflection ───────────────────────────

    private static Class<?> RECIPES_GUI_CLASS;
    private static Class<?> RECIPE_LAYOUTS_CLASS;
    private static Field LAYOUTS_FIELD;        // RecipesGui.layouts
    private static Field LAYOUTS_LIST_FIELD;   // RecipeGuiLayouts.recipeLayoutsWithButtons
    private static Method RECIPE_LAYOUT_ACCESSOR; // RecipeLayoutWithButtons#recipeLayout()
    private static boolean reflectFailed = false;

    private static boolean isRecipesGui(Screen screen) {
        if (screen == null) return false;
        if (RECIPES_GUI_CLASS == null && !reflectFailed) {
            try {
                RECIPES_GUI_CLASS = Class.forName("mezz.jei.gui.recipes.RecipesGui");
            } catch (ClassNotFoundException e) {
                Credit.LOGGER.warn("[JeiOverlay] RecipesGui class not found — JEI internals layout changed?");
                reflectFailed = true;
                return false;
            }
        }
        return RECIPES_GUI_CLASS != null && RECIPES_GUI_CLASS.isInstance(screen);
    }

    private static List<Object> readLayoutsList(Screen recipesGui) {
        try {
            if (LAYOUTS_FIELD == null) {
                if (RECIPES_GUI_CLASS == null) return Collections.emptyList();
                LAYOUTS_FIELD = RECIPES_GUI_CLASS.getDeclaredField("layouts");
                LAYOUTS_FIELD.setAccessible(true);
            }
            Object rgl = LAYOUTS_FIELD.get(recipesGui);
            if (rgl == null) return Collections.emptyList();
            if (LAYOUTS_LIST_FIELD == null) {
                RECIPE_LAYOUTS_CLASS = rgl.getClass();
                LAYOUTS_LIST_FIELD = RECIPE_LAYOUTS_CLASS.getDeclaredField("recipeLayoutsWithButtons");
                LAYOUTS_LIST_FIELD.setAccessible(true);
            }
            Object listObj = LAYOUTS_LIST_FIELD.get(rgl);
            if (listObj instanceof List<?> raw) {
                @SuppressWarnings({"unchecked", "rawtypes"})
                List<Object> casted = (List) raw;
                return casted;
            }
            return Collections.emptyList();
        } catch (Exception e) {
            Credit.LOGGER.error("[JeiOverlay] readLayoutsList failed", e);
            return Collections.emptyList();
        }
    }

    @Nullable
    private static IRecipeLayoutDrawable<?> readRecipeLayout(Object recipeLayoutWithButtons) {
        try {
            if (RECIPE_LAYOUT_ACCESSOR == null) {
                RECIPE_LAYOUT_ACCESSOR = recipeLayoutWithButtons.getClass().getMethod("recipeLayout");
            }
            Object res = RECIPE_LAYOUT_ACCESSOR.invoke(recipeLayoutWithButtons);
            if (res instanceof IRecipeLayoutDrawable<?> ird) return ird;
            return null;
        } catch (Exception e) {
            Credit.LOGGER.debug("[JeiOverlay] readRecipeLayout failed", e);
            return null;
        }
    }

    @Nullable
    private static <R> ResourceLocation safeRegistryName(IRecipeCategory<R> category, R recipe) {
        if (recipe == null) return null;
        try { return category.getRegistryName(recipe); } catch (Exception e) { return null; }
    }

    private static java.lang.reflect.Method TRANSFER_BUTTON_ACCESSOR;
    private static Field TRANSFER_BUTTON_INNER_FIELD;

    /**
     * RecipeLayoutWithButtons → transferButton (RecipeTransferButton) → 内部 GuiIconButton (AbstractWidget) → visible=true。
     * EDIT 用に「+」を常時表示。
     */
    private static void forceTransferButtonVisible(Object wrapped) {
        try {
            if (TRANSFER_BUTTON_ACCESSOR == null) {
                TRANSFER_BUTTON_ACCESSOR = wrapped.getClass().getMethod("transferButton");
            }
            Object transferButton = TRANSFER_BUTTON_ACCESSOR.invoke(wrapped);
            if (transferButton == null) return;
            if (TRANSFER_BUTTON_INNER_FIELD == null) {
                // RecipeTransferButton extends GuiIconToggleButton; "button" field is in parent
                TRANSFER_BUTTON_INNER_FIELD = transferButton.getClass().getSuperclass().getDeclaredField("button");
                TRANSFER_BUTTON_INNER_FIELD.setAccessible(true);
            }
            Object btn = TRANSFER_BUTTON_INNER_FIELD.get(transferButton);
            if (btn instanceof AbstractWidget w) {
                w.visible = true;
                w.active = true;
            }
        } catch (Exception e) {
            Credit.LOGGER.debug("[JeiOverlay] forceTransferButtonVisible failed (non-fatal)", e);
        }
    }

    /** Helper invoked from new render path; same as forceTransferButtonVisible but takes IRecipeLayoutDrawable directly is not possible — we need the wrapped. */
    @SuppressWarnings("unused")
    private static void forceTransferButtonVisibleByLayout(IRecipeLayoutDrawable<?> layout) {
        // 互換 stub: 上の forceTransferButtonVisible(Object wrapped) を使う
    }
}
