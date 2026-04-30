package DIV.credit.client.history;

import DIV.credit.Credit;
import net.minecraft.client.Minecraft;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.Tag;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * HistoryStore を <gameDir>/config/credit-history.dat に NBT (gzip) シリアライズ。
 * 保存タイミング: push 完了直後 (record() 呼出後) に save。
 * 復元: ゲーム起動の最初の BuilderScreen open 時。
 */
public final class HistoryPersistence {

    public static final String FILE_NAME = "credit-history.dat";

    private HistoryPersistence() {}

    public static Path file() {
        return Minecraft.getInstance().gameDirectory.toPath()
            .resolve("config").resolve(FILE_NAME);
    }

    public static void save() {
        try {
            CompoundTag root = new CompoundTag();
            ListTag list = new ListTag();
            for (HistoryEntry e : HistoryStore.INSTANCE.all()) {
                list.add(e.toNbt());
            }
            root.put("entries", list);
            Path p = file();
            Files.createDirectories(p.getParent());
            File f = p.toFile();
            if (list.isEmpty()) {
                if (f.exists()) f.delete();
                return;
            }
            NbtIo.writeCompressed(root, f);
        } catch (Exception e) {
            Credit.LOGGER.error("[CraftPattern] history save error", e);
        }
    }

    public static void load() {
        try {
            File f = file().toFile();
            if (!f.exists()) return;
            CompoundTag root = NbtIo.readCompressed(f);
            ListTag list = root.getList("entries", Tag.TAG_COMPOUND);
            List<HistoryEntry> restored = new ArrayList<>();
            for (int i = 0; i < list.size(); i++) {
                HistoryEntry e = HistoryEntry.fromNbt(list.getCompound(i));
                if (e != null) restored.add(e);
            }
            HistoryStore.INSTANCE.replaceAll(restored);
            Credit.LOGGER.info("[CraftPattern] Loaded {} history entries from {}", restored.size(), f);
        } catch (Exception e) {
            Credit.LOGGER.error("[CraftPattern] history load error", e);
        }
    }
}
