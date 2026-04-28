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

    /** CFG_SLOT 設定。デフォルト NONE。Configured wrapper のみ非 NONE。 */
    default ItemOption option() { return ItemOption.NONE; }
    /** option を変えた copy。base 型は維持。 */
    default IngredientSpec withOption(ItemOption opt) {
        if (opt == null || opt == ItemOption.NONE) return unwrap();
        return new Configured(unwrap(), opt);
    }
    /** Configured 解除。それ以外は this。 */
    default IngredientSpec unwrap() { return this; }

    /**
     * CFG_SLOT で適用できる挙動オプション。
     * - VANILLA_DAMAGE / VANILLA_KEEP : minecraft namespace 用
     * - GT_CATALYST / GT_CHANCE        : gtceu namespace 用（CHANCE は output 限定）
     */
    enum ItemOption {
        NONE,
        VANILLA_DAMAGE,
        VANILLA_KEEP,
        GT_CATALYST,
        GT_CHANCE
    }

    /**
     * ItemOption を別 spec の上に被せる wrapper。各クエリは base に委譲。
     * GT_CHANCE 用に 1000分率の chance と tierBoost を保持（他 option では無視）。
     * デフォルトは chance=1000 (100%), boost=0。
     */
    record Configured(IngredientSpec base, ItemOption opt, int chanceMille, int tierBoost)
            implements IngredientSpec {
        public Configured(IngredientSpec base, ItemOption opt) {
            this(base, opt, 1000, 0);
        }
        @Override public boolean isEmpty()       { return base.isEmpty(); }
        @Override public int     count()         { return base.count(); }
        @Override public int     incrementStep() { return base.incrementStep(); }
        @Override public int     maxCount()      { return base.maxCount(); }
        @Override public ItemOption option()     { return opt; }
        @Override public IngredientSpec unwrap() { return base; }
        @Override public IngredientSpec withOption(ItemOption o) {
            if (o == null || o == ItemOption.NONE) return base;
            return new Configured(base, o, chanceMille, tierBoost); // 値を維持
        }
        public Configured withChance(int newChanceMille, int newTierBoost) {
            return new Configured(base, opt,
                Math.max(0, Math.min(1000, newChanceMille)),
                Math.max(0, newTierBoost));
        }
    }

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
        // Configured は base の量を変えて再 wrap（option 維持）
        if (s instanceof Configured c) {
            return new Configured(withCount(c.base(), newCount), c.opt());
        }
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