package DIV.credit.client.jei;

import DIV.credit.client.preview.PreviewWindowManager;
import DIV.credit.client.screen.BuilderScreen;
import mezz.jei.api.gui.handlers.IGuiContainerHandler;
import net.minecraft.client.renderer.Rect2i;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * v3.4.x: BuilderScreen 周辺の「JEI/EMI sidebar に描画させない領域」 を申告する handler。
 *
 * <p>JEI に直接登録 (= {@link DIV.credit.jei.CraftPatternJeiPlugin#registerGuiHandlers}) すれば、
 * JEMI bridge が EMI 側の exclusion area にも自動 forward する → 1 登録で両 viewer 対応。
 *
 * <h3>除外対象:</h3>
 * <ul>
 *   <li>BuilderScreen 本体の GUI 矩形 (= 通常 JEI 既定で除外されるが、 確実性のため明示登録)</li>
 *   <li>preview windows (= 右隣にカスケード描画されるため sidebar と重なる)</li>
 * </ul>
 *
 * <p>preview window は動的生成のため {@link PreviewWindowManager} から実際描画中の window 群を
 * 取得 (= future TODO)、 暫定では BuilderScreen GUI を超えて右側余白を一定幅まで予約。
 */
public class BuilderGuiContainerHandler implements IGuiContainerHandler<BuilderScreen> {

    /** preview window の最大想定幅 (= 通常 200-300 px)。 これを超えると sidebar に被る可能性。 */
    private static final int PREVIEW_RESERVE_WIDTH = 320;

    @Override
    public List<Rect2i> getGuiExtraAreas(BuilderScreen screen) {
        if (screen == null) return Collections.emptyList();
        List<Rect2i> areas = new ArrayList<>(2);

        // 1. BuilderScreen 本体 GUI 矩形 (= 念のため明示)
        try {
            int x = screen.getGuiLeft();
            int y = screen.getGuiTop();
            int w = screen.getXSize();
            int h = screen.getYSize();
            if (w > 0 && h > 0) areas.add(new Rect2i(x, y, w, h));
        } catch (Throwable ignored) {}

        // 2. preview windows が描画中なら右余白を予約
        try {
            if (PreviewWindowManager.INSTANCE.hasWindows()) {
                int x = screen.getGuiLeft() + screen.getXSize();
                int y = 0;
                int w = Math.min(PREVIEW_RESERVE_WIDTH, screen.width - x);
                int h = screen.height;
                if (w > 0) areas.add(new Rect2i(x, y, w, h));
            }
        } catch (Throwable ignored) {}

        return areas;
    }
}
