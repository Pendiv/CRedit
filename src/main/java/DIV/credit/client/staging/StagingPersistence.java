package DIV.credit.client.staging;

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
 * StagingArea を <gameDir>/config/credit-staging.dat に NBT (gzip) シリアライズ。
 * <p>呼び出し: stage() 直後、commit/reject 直後、push 直後、screen close 時 — 高頻度すぎないので毎回 save。
 * <p>復元: ゲーム起動の最初の BuilderScreen open 時 (DraftPersistence と同パターン)。
 */
public final class StagingPersistence {

    public static final String FILE_NAME = "credit-staging.dat";

    private StagingPersistence() {}

    public static Path file() {
        return Minecraft.getInstance().gameDirectory.toPath()
            .resolve("config").resolve(FILE_NAME);
    }

    public static void save() {
        try {
            CompoundTag root = new CompoundTag();
            ListTag list = new ListTag();
            for (StagedChange c : StagingArea.INSTANCE.all()) {
                list.add(c.toNbt());
            }
            root.put("changes", list);
            Path p = file();
            Files.createDirectories(p.getParent());
            File f = p.toFile();
            if (list.isEmpty()) {
                if (f.exists()) f.delete();
                return;
            }
            NbtIo.writeCompressed(root, f);
        } catch (Exception e) {
            Credit.LOGGER.error("[CraftPattern] staging save error", e);
        }
    }

    public static void load() {
        try {
            File f = file().toFile();
            if (!f.exists()) return;
            CompoundTag root = NbtIo.readCompressed(f);
            ListTag list = root.getList("changes", Tag.TAG_COMPOUND);
            List<StagedChange> restored = new ArrayList<>();
            for (int i = 0; i < list.size(); i++) {
                StagedChange c = StagedChange.fromNbt(list.getCompound(i));
                if (c != null) restored.add(c);
            }
            StagingArea.INSTANCE.replaceAll(restored);
            Credit.LOGGER.info("[CraftPattern] Loaded {} staged change(s) from {}", restored.size(), f);
        } catch (Exception e) {
            Credit.LOGGER.error("[CraftPattern] staging load error", e);
        }
    }
}
