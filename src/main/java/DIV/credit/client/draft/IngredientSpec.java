package DIV.credit.client.draft;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.fluids.FluidStack;

/**
 * レシピスロット 1 個の中身。
 * - Item: ItemStack（count = stack.getCount）
 * - Tag: タグ ID + count
 * - Fluid: FluidStack（count = mB amount）
 */
public interface IngredientSpec {

    int MAX_COUNT_DEFAULT = 64;

    boolean isEmpty();
    int     count();

    /** 右クリ/スクロール時の標準ステップ（item=1, fluid=100 等）。 */
    default int incrementStep() { return 1; }
    /** 上限（item=64, fluid=16000 等）。 */
    default int maxCount()      { return MAX_COUNT_DEFAULT; }

    record Item(ItemStack stack) implements IngredientSpec {
        @Override public boolean isEmpty() { return stack.isEmpty(); }
        @Override public int     count()   { return stack.getCount(); }
    }

    record Tag(ResourceLocation tagId, int count) implements IngredientSpec {
        public Tag(ResourceLocation tagId) { this(tagId, 1); }
        @Override public boolean isEmpty() { return tagId == null; }
    }

    record Fluid(FluidStack stack) implements IngredientSpec {
        @Override public boolean isEmpty()       { return stack.isEmpty(); }
        @Override public int     count()         { return stack.getAmount(); }
        @Override public int     incrementStep() { return 100; }
        @Override public int     maxCount()      { return 64_000; }
    }

    record FluidTag(ResourceLocation tagId, int amount) implements IngredientSpec {
        public FluidTag(ResourceLocation tagId) { this(tagId, 1000); }
        @Override public boolean isEmpty()       { return tagId == null; }
        @Override public int     count()         { return amount; }
        @Override public int     incrementStep() { return 100; }
        @Override public int     maxCount()      { return 64_000; }
    }

    /** Mekanism Gas (mB)。Mek クラスを直接持たないよう registry id + amount のみ保持。 */
    record Gas(ResourceLocation gasId, int amount) implements IngredientSpec {
        public Gas(ResourceLocation gasId) { this(gasId, 1000); }
        @Override public boolean isEmpty()       { return gasId == null; }
        @Override public int     count()         { return amount; }
        @Override public int     incrementStep() { return 100; }
        @Override public int     maxCount()      { return 1_000_000; }
    }

    IngredientSpec EMPTY = new Item(ItemStack.EMPTY);

    static IngredientSpec ofItem(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return EMPTY;
        return new Item(stack);
    }

    static IngredientSpec ofTag(ResourceLocation tagId) {
        if (tagId == null) return EMPTY;
        return new Tag(tagId);
    }

    static IngredientSpec ofFluid(FluidStack fs) {
        if (fs == null || fs.isEmpty()) return EMPTY;
        return new Fluid(fs.copy());
    }

    static IngredientSpec ofFluidTag(ResourceLocation tagId, int amount) {
        if (tagId == null) return EMPTY;
        return new FluidTag(tagId, Math.max(1, amount));
    }

    static IngredientSpec ofGas(ResourceLocation gasId, int amount) {
        if (gasId == null) return EMPTY;
        return new Gas(gasId, Math.max(1, amount));
    }

    static IngredientSpec withCount(IngredientSpec s, int newCount) {
        int n = Math.max(1, Math.min(s.maxCount(), newCount));
        if (s instanceof Item it && !it.stack().isEmpty()) {
            ItemStack copy = it.stack().copy();
            copy.setCount(Math.min(n, 64)); // ItemStack 自体は 64 上限
            return new Item(copy);
        }
        if (s instanceof Tag tg && tg.tagId() != null) {
            return new Tag(tg.tagId(), n);
        }
        if (s instanceof Fluid fl && !fl.stack().isEmpty()) {
            FluidStack copy = fl.stack().copy();
            copy.setAmount(n);
            return new Fluid(copy);
        }
        if (s instanceof FluidTag ft && ft.tagId() != null) {
            return new FluidTag(ft.tagId(), n);
        }
        if (s instanceof Gas g && g.gasId() != null) {
            return new Gas(g.gasId(), n);
        }
        return s;
    }
}