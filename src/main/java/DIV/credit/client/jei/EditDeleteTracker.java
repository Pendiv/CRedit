package DIV.credit.client.jei;

import DIV.credit.Credit;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.client.event.RecipesUpdatedEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/**
 * EDIT / DELETE が押されたレシピ ID をプロセスグローバルに記録。
 * 同レシピへの再 EDIT/DELETE を block するため、JEI overlay で表示も切り替える。
 *
 * <p>クリア条件:
 * <ul>
 *   <li>world logout (PlayerEvent.PlayerLoggedOutEvent)</li>
 *   <li>recipes 更新 (RecipesUpdatedEvent — /reload で fire)</li>
 * </ul>
 *
 * <p>edited / deleted は別 set。同じ ID が両方に入ることもある (edit して delete も)。
 */
public final class EditDeleteTracker {

    public static final EditDeleteTracker INSTANCE = new EditDeleteTracker();

    private final Set<ResourceLocation> editedIds  = Collections.synchronizedSet(new HashSet<>());
    private final Set<ResourceLocation> deletedIds = Collections.synchronizedSet(new HashSet<>());

    private EditDeleteTracker() {}

    public void markEdited(ResourceLocation id) {
        if (id != null) {
            editedIds.add(id);
            Credit.LOGGER.info("[CraftPattern] markEdited: {}", id);
        }
    }

    public void markDeleted(ResourceLocation id) {
        if (id != null) {
            deletedIds.add(id);
            Credit.LOGGER.info("[CraftPattern] markDeleted: {}", id);
        }
    }

    public boolean isEdited(ResourceLocation id)  { return id != null && editedIds.contains(id); }
    public boolean isDeleted(ResourceLocation id) { return id != null && deletedIds.contains(id); }

    public void clearAll() {
        int e = editedIds.size(), d = deletedIds.size();
        editedIds.clear();
        deletedIds.clear();
        Credit.LOGGER.info("[CraftPattern] EditDeleteTracker cleared (was edited={} deleted={})", e, d);
    }

    /** Forge event hook — Credit.java で MinecraftForge.EVENT_BUS.register。 */
    public static final class Hook {
        @SubscribeEvent
        public static void onRecipesUpdated(RecipesUpdatedEvent event) {
            // /reload or world join 時に fire。tracker を clear して再操作可能にする。
            INSTANCE.clearAll();
        }
        @SubscribeEvent
        public static void onLogout(PlayerEvent.PlayerLoggedOutEvent event) {
            INSTANCE.clearAll();
        }
    }
}
