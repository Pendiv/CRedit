package DIV.credit.client.clipboard;

import DIV.credit.Credit;
import DIV.credit.client.draft.DraftPersistence;
import DIV.credit.client.draft.IngredientSpec;
import net.minecraft.client.Minecraft;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.Tag;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * v3.0.1: Clipboard を NBT で <gameDir>/config/credit-clipboard.dat に保存。
 * CLIPBOARD_PERSISTENCE = PERSISTENT の時のみ save / load される。
 */
public final class ClipboardPersistence {

    public static final String FILE_NAME = "credit-clipboard.dat";

    private ClipboardPersistence() {}

    public static Path file() {
        return Minecraft.getInstance().gameDirectory.toPath()
            .resolve("config").resolve(FILE_NAME);
    }

    public static void save() {
        try {
            List<IngredientSpec> snap = Clipboard.INSTANCE.snapshot();
            Path p = file();
            Files.createDirectories(p.getParent());
            File f = p.toFile();
            if (snap.isEmpty()) {
                if (f.exists()) f.delete();
                return;
            }
            CompoundTag root = new CompoundTag();
            ListTag list = new ListTag();
            for (IngredientSpec s : snap) {
                if (s == null || s.isEmpty()) continue;
                list.add(DraftPersistence.serializeSpec(s));
            }
            root.put("entries", list);
            NbtIo.writeCompressed(root, p);  // 1.21: NbtIo は Path
        } catch (Exception ex) {
            Credit.LOGGER.error("[C5007] clipboard save error", ex);
        }
    }

    public static void load() {
        Path p = file();
        if (!Files.exists(p)) return;
        try {
            CompoundTag root = NbtIo.readCompressed(p, NbtAccounter.unlimitedHeap());
            ListTag list = root.getList("entries", Tag.TAG_COMPOUND);
            List<IngredientSpec> restored = new ArrayList<>();
            for (int i = 0; i < list.size(); i++) {
                IngredientSpec s = DraftPersistence.parseSpec(list.getCompound(i));
                if (s != null && !s.isEmpty()) restored.add(s);
            }
            Clipboard.INSTANCE.restore(restored);
        } catch (Exception ex) {
            Credit.LOGGER.error("[C5008] clipboard load error", ex);
        }
    }
}
