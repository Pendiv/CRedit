package DIV.credit.client.draft;

import DIV.credit.Credit;
import net.minecraft.client.Minecraft;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.fluids.FluidStack;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/**
 * MAX persistence モード時、DraftStore の draft 状態を
 * `<gameDir>/config/credit-drafts.dat` (NBT/gzip) にシリアライズする。
 *
 * 保存対象は draft の slot 内容と numericFields の値のみ。
 * 復元は draft が getOrCreate されたタイミングで pending state から apply する遅延方式。
 */
public final class DraftPersistence {

    public static final String FILE_NAME = "credit-drafts.dat";

    private DraftPersistence() {}

    public static Path file() {
        return Minecraft.getInstance().gameDirectory.toPath()
            .resolve("config").resolve(FILE_NAME);
    }

    // ───── save ─────

    public static void save(DraftStore store) {
        try {
            CompoundTag root = new CompoundTag();
            for (var e : store.snapshotDrafts().entrySet()) {
                CompoundTag t = serializeDraft(e.getValue());
                if (t != null) root.put(e.getKey(), t);
            }
            // pending state（まだ開かれていないカテゴリ）も保持する
            for (var e : store.snapshotPending().entrySet()) {
                if (!root.contains(e.getKey())) root.put(e.getKey(), e.getValue());
            }
            Path p = file();
            Files.createDirectories(p.getParent());
            File f = p.toFile();
            if (root.isEmpty()) {
                if (f.exists()) f.delete();
                return;
            }
            NbtIo.writeCompressed(root, f);
        } catch (Exception ex) {
            Credit.LOGGER.error("[CraftPattern] persistence save error", ex);
        }
    }

    public static void load(DraftStore store) {
        Path p = file();
        if (!Files.exists(p)) return;
        try {
            CompoundTag root = NbtIo.readCompressed(p.toFile());
            Map<String, CompoundTag> map = new HashMap<>();
            for (String key : root.getAllKeys()) {
                Tag t = root.get(key);
                if (t instanceof CompoundTag c) map.put(key, c);
            }
            store.setPendingState(map);
        } catch (Exception ex) {
            Credit.LOGGER.error("[CraftPattern] persistence load error", ex);
        }
    }

    // ───── serialize / parse ─────

    public static CompoundTag serializeDraft(RecipeDraft d) {
        CompoundTag t = new CompoundTag();
        ListTag slots = new ListTag();
        for (int i = 0; i < d.slotCount(); i++) {
            slots.add(serializeSpec(d.getSlot(i)));
        }
        t.put("slots", slots);
        ListTag fields = new ListTag();
        for (var f : d.numericFields()) {
            CompoundTag fc = new CompoundTag();
            fc.putString("label", f.label());
            fc.putDouble("v", f.getter().getAsDouble());
            fields.add(fc);
        }
        t.put("fields", fields);
        return t;
    }

    public static void applyTo(RecipeDraft d, CompoundTag t) {
        try {
            ListTag slots = t.getList("slots", Tag.TAG_COMPOUND);
            int n = Math.min(slots.size(), d.slotCount());
            for (int i = 0; i < n; i++) {
                IngredientSpec spec = parseSpec(slots.getCompound(i));
                if (spec != null && d.acceptsAt(i, spec)) {
                    d.setSlot(i, spec);
                }
            }
            ListTag fields = t.getList("fields", Tag.TAG_COMPOUND);
            Map<String, RecipeDraft.NumericField> byLabel = new HashMap<>();
            for (var f : d.numericFields()) byLabel.put(f.label(), f);
            for (int i = 0; i < fields.size(); i++) {
                CompoundTag fc = fields.getCompound(i);
                var f = byLabel.get(fc.getString("label"));
                if (f != null) {
                    double v = fc.getDouble("v");
                    v = Math.max(f.min(), Math.min(f.max(), v));
                    f.setter().accept(v);
                }
            }
        } catch (Exception ex) {
            Credit.LOGGER.error("[CraftPattern] persistence apply error", ex);
        }
    }

    public static CompoundTag serializeSpec(IngredientSpec s) {
        CompoundTag t = new CompoundTag();
        // Configured は base を serialize して option を別 key で保存
        if (s instanceof IngredientSpec.Configured c) {
            t = serializeSpec(c.base());
            if (c.opt() != IngredientSpec.ItemOption.NONE) {
                t.putString("opt", c.opt().name());
                if (c.opt() == IngredientSpec.ItemOption.GT_CHANCE) {
                    t.putInt("chance", c.chanceMille());
                    t.putInt("boost",  c.tierBoost());
                }
            }
            return t;
        }
        if (s instanceof IngredientSpec.Item it && !it.stack().isEmpty()) {
            t.putString("type", "item");
            t.put("stack", it.stack().save(new CompoundTag()));
        } else if (s instanceof IngredientSpec.Tag tg && tg.tagId() != null) {
            t.putString("type", "tag");
            t.putString("id", tg.tagId().toString());
            t.putInt("count", tg.count());
        } else if (s instanceof IngredientSpec.Fluid fl && !fl.stack().isEmpty()) {
            t.putString("type", "fluid");
            t.put("stack", fl.stack().writeToNBT(new CompoundTag()));
        } else if (s instanceof IngredientSpec.FluidTag ft && ft.tagId() != null) {
            t.putString("type", "fluidtag");
            t.putString("id", ft.tagId().toString());
            t.putInt("amt", ft.amount());
        } else if (s instanceof IngredientSpec.Gas g && g.gasId() != null) {
            t.putString("type", "gas");
            t.putString("id", g.gasId().toString());
            t.putInt("amt", g.amount());
        } else {
            t.putString("type", "empty");
        }
        return t;
    }

    public static IngredientSpec parseSpec(CompoundTag t) {
        String type = t.getString("type");
        try {
            IngredientSpec base = switch (type) {
                case "item" -> IngredientSpec.ofItem(ItemStack.of(t.getCompound("stack")));
                case "tag"  -> IngredientSpec.ofTag(new ResourceLocation(t.getString("id")));
                case "fluid" -> {
                    FluidStack fs = FluidStack.loadFluidStackFromNBT(t.getCompound("stack"));
                    yield IngredientSpec.ofFluid(fs);
                }
                case "fluidtag" -> IngredientSpec.ofFluidTag(
                    new ResourceLocation(t.getString("id")), t.getInt("amt"));
                case "gas" -> IngredientSpec.ofGas(
                    new ResourceLocation(t.getString("id")), t.getInt("amt"));
                default -> IngredientSpec.EMPTY;
            };
            if (t.contains("opt")) {
                try {
                    IngredientSpec.ItemOption opt = IngredientSpec.ItemOption.valueOf(t.getString("opt"));
                    IngredientSpec wrapped = base.withOption(opt);
                    if (opt == IngredientSpec.ItemOption.GT_CHANCE
                        && wrapped instanceof IngredientSpec.Configured c) {
                        int chance = t.contains("chance") ? t.getInt("chance") : 1000;
                        int boost  = t.contains("boost")  ? t.getInt("boost")  : 0;
                        return c.withChance(chance, boost);
                    }
                    return wrapped;
                } catch (IllegalArgumentException ignored) {}
            }
            return base;
        } catch (Exception ex) {
            return IngredientSpec.EMPTY;
        }
    }
}