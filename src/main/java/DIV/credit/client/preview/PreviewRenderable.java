package DIV.credit.client.preview;

import net.minecraft.client.gui.GuiGraphics;

/**
 * v3.4.x: PreviewWindow 内部に描画される 「recipe 本体」 の抽象。
 *
 * <p>従来は {@code IRecipeLayoutDrawable} 直 (= JEI 専用) だった preview 内側を本 interface に
 * 抽象化し、 EMI native widget 経路にも対応可能にする。
 *
 * <h3>実装:</h3>
 * <ul>
 *   <li>{@link JeiPreviewRenderable}: JEI {@code IRecipeLayoutDrawable} ラッパ (= 既存挙動)</li>
 *   <li>{@link EmiPreviewRenderable}: EMI {@code List<Widget>} ラッパ (= 純 EMI 環境 fallback)</li>
 * </ul>
 *
 * <h3>呼出契約:</h3>
 * <ol>
 *   <li>{@link #getWidth()} / {@link #getHeight()} は constructor 後 immutable</li>
 *   <li>{@link #setPosition(int, int)} は render 前に毎フレーム呼ばれる可能性あり</li>
 *   <li>{@link #render} は title bar / 枠の内側、 padding 適用後座標で呼ぶ</li>
 *   <li>tick 系処理は impl 内 render で済ませる (= host 側 dispatch 不要)</li>
 * </ol>
 */
public interface PreviewRenderable {

    /** 内側 (= title bar 抜き) の幅。 */
    int getWidth();

    /** 内側の高さ。 */
    int getHeight();

    /** 描画位置 (= 内側左上 absolute 座標) をセット。 render 前に毎フレーム呼ばれる前提。 */
    void setPosition(int x, int y);

    /** GUI に描画。 setPosition で渡された位置に描画。 mouseX/Y は absolute 画面座標。 */
    void render(GuiGraphics g, int mouseX, int mouseY, float partialTick);
}
