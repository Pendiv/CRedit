package DIV.credit.client.menu;

import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.Nullable;

/**
 * クライアント専用メニュー：プレイヤーの主インベントリ＋ホットバーのみ。
 * サーバ同期しない。スロットクリックは Screen 側で完全に握って制御する。
 */
public class CreditBuilderMenu extends AbstractContainerMenu {

    public static final int INV_X    = 8;
    public static final int MAIN_Y   = 4;
    public static final int HOTBAR_Y = 58;

    public static final int IMAGE_HEIGHT = HOTBAR_Y + 18 + 4; // 80

    public CreditBuilderMenu(@Nullable Inventory playerInv) {
        super(null, 0);
        if (playerInv == null) return;
        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 9; col++) {
                addSlot(new Slot(playerInv, 9 + col + row * 9,
                    INV_X + col * 18, MAIN_Y + row * 18));
            }
        }
        for (int col = 0; col < 9; col++) {
            addSlot(new Slot(playerInv, col, INV_X + col * 18, HOTBAR_Y));
        }
    }

    @Override public ItemStack quickMoveStack(Player p, int i) { return ItemStack.EMPTY; }
    @Override public boolean stillValid(Player p) { return true; }
}