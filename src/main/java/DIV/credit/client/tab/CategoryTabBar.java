package DIV.credit.client.tab;

import mezz.jei.api.helpers.IGuiHelper;
import mezz.jei.api.recipe.IRecipeManager;
import mezz.jei.api.recipe.category.IRecipeCategory;
import net.minecraft.client.gui.GuiGraphics;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * 上段タブバー：上に PageNav (← 1/N →)、下に 24x24 タブを横並び。
 * JEI の RecipeGuiTabs を参考に簡略化した自前実装。
 */
public class CategoryTabBar {

    private final List<CategoryTab> allTabs = new ArrayList<>();
    private final Consumer<IRecipeCategory<?>> onSelect;
    private final PageNav nav;

    private int x, tabsY, navY, width;
    private int tabsPerPage = 1;
    private int pageNumber  = 0;
    private int pageCount   = 1;
    private IRecipeCategory<?> selected;

    public CategoryTabBar(List<? extends IRecipeCategory<?>> categories,
                          IRecipeManager rm, IGuiHelper gh,
                          Consumer<IRecipeCategory<?>> onSelect) {
        for (IRecipeCategory<?> c : categories) allTabs.add(new CategoryTab(c, rm, gh));
        this.onSelect = onSelect;
        if (!categories.isEmpty()) this.selected = categories.get(0);
        this.nav = new PageNav(
            () -> pageNumber,
            () -> pageCount,
            () -> pageNumber > 0,
            () -> pageNumber + 1 < pageCount,
            this::previousPage,
            this::nextPage
        );
    }

    /** y は PageNav の上端。タブ列はその直下。 */
    public void setBounds(int x, int y, int width) {
        this.x = x;
        this.navY = y;
        this.tabsY = y + PageNav.H;
        this.width = width;
        this.tabsPerPage = Math.max(1, width / CategoryTab.W);
        this.pageCount = Math.max(1, (allTabs.size() + tabsPerPage - 1) / tabsPerPage);
        if (selected != null) {
            int idx = indexOf(selected);
            if (idx >= 0) pageNumber = idx / tabsPerPage;
        }
        nav.setBounds(x, navY, width);
        relayout();
    }

    public int totalHeight() {
        return PageNav.H + CategoryTab.H;
    }

    private int indexOf(IRecipeCategory<?> cat) {
        for (int i = 0; i < allTabs.size(); i++) {
            if (allTabs.get(i).category.getRecipeType().equals(cat.getRecipeType())) return i;
        }
        return -1;
    }

    private void relayout() {
        int startIdx = pageNumber * tabsPerPage;
        // center tabs in the strip when last page is short
        int visible = Math.min(tabsPerPage, allTabs.size() - startIdx);
        int stripUsedWidth = visible * CategoryTab.W;
        int tx = x + (width - stripUsedWidth) / 2;
        for (int i = 0; i < visible; i++) {
            allTabs.get(startIdx + i).setPos(tx, tabsY);
            tx += CategoryTab.W;
        }
    }

    public IRecipeCategory<?> getSelected() {
        return selected;
    }

    public void select(IRecipeCategory<?> cat) {
        this.selected = cat;
        if (cat != null) {
            int idx = indexOf(cat);
            if (idx >= 0) {
                this.pageNumber = idx / tabsPerPage;
                relayout();
            }
        }
    }

    public void nextPage() {
        if (pageNumber + 1 < pageCount) {
            pageNumber++;
            relayout();
        }
    }

    public void previousPage() {
        if (pageNumber > 0) {
            pageNumber--;
            relayout();
        }
    }

    public void draw(GuiGraphics g) {
        nav.draw(g);
        int startIdx = pageNumber * tabsPerPage;
        int visible = Math.min(tabsPerPage, allTabs.size() - startIdx);
        for (int i = 0; i < visible; i++) {
            CategoryTab tab = allTabs.get(startIdx + i);
            boolean isSel = selected != null
                && tab.category.getRecipeType().equals(selected.getRecipeType());
            tab.draw(g, isSel);
        }
    }

    public CategoryTab getHovered(double mx, double my) {
        int startIdx = pageNumber * tabsPerPage;
        int visible = Math.min(tabsPerPage, allTabs.size() - startIdx);
        for (int i = 0; i < visible; i++) {
            CategoryTab tab = allTabs.get(startIdx + i);
            if (tab.isMouseOver(mx, my)) return tab;
        }
        return null;
    }

    public boolean mouseClicked(double mx, double my, int button) {
        if (nav.mouseClicked(mx, my, button)) return true;
        if (button != 0) return false;
        CategoryTab tab = getHovered(mx, my);
        if (tab != null) {
            this.selected = tab.category;
            onSelect.accept(selected);
            return true;
        }
        return false;
    }

    public boolean mouseScrolled(double mx, double my, double delta) {
        if (mx < x || mx >= x + width) return false;
        if (my < navY || my >= tabsY + CategoryTab.H) return false;
        if (delta > 0) { previousPage(); return true; }
        if (delta < 0) { nextPage();     return true; }
        return false;
    }
}