package DIV.credit.client.draft;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.fluids.FluidStack;

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
        GT_CHANCE,
        /** Create 系 output の確率指定 (chanceMille / 1000.0 = 0..1)。tierBoost は未使用。 */
        CREATE_CHANCE
    }

    /**
     * ItemOption を別 spec の上に被せる wrapper。各クエリは base に委譲。
     * GT_CHANCE 用に 1000分率の chance と tierBoost を保持（他 option では無視）。
     * デフォルトは chance=1000 (100%), boost=0。
     */
    record Configured(IngredientSpec base, ItemOption opt, int chanceMille, int tierBoost)
            implements IngredientSpec {
        public Configured(IngredientSpec base, ItemOption opt) {
            this(base, opt, defaultChanceMille(), defaultBoost());
        }
        /** v3.2.x: CreditConfig から読む default chance (= 1000 = 100%)。 config 未 init 環境では 1000。 */
        private static int defaultChanceMille() {
            try { return DIV.credit.CreditConfig.CHANCE_DEFAULT_MILLE.get(); }
            catch (Exception e) { return 1000; }
        }
        private static int defaultBoost() {
            try { return DIV.credit.CreditConfig.CHANCE_DEFAULT_BOOST.get(); }
            catch (Exception e) { return 0; }
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

    /** Mekanism chemical 種別 (= GAS/INFUSION/PIGMENT/SLURRY)。 Gas record の chemicalType field で識別。 */
    enum ChemicalType { GAS, INFUSION, PIGMENT, SLURRY }

    /**
     * Mekanism chemical (mB)。 Mek クラスを直接持たないよう registry id + amount + chemicalType のみ保持。
     * <p>chemicalType は 4 種 (= Gas / Infusion / Pigment / Slurry) を識別。 旧 2-arg ctor は GAS 既定で
     * back-compat。 record 名は歴史的経緯で Gas のままだが「chemical 全般を表す」 と読む。
     * <p>SlotKind は GAS_INPUT/OUTPUT を 4 種共用 (= slot 構造を膨らませない)、 各値の type で区別。
     */
    record Gas(ResourceLocation gasId, int amount, ChemicalType chemicalType) implements IngredientSpec {
        public Gas(ResourceLocation gasId, int amount) { this(gasId, amount, ChemicalType.GAS); }
        public Gas(ResourceLocation gasId)             { this(gasId, 1000,  ChemicalType.GAS); }
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
        return new Gas(gasId, Math.max(1, amount), ChemicalType.GAS);
    }

    static IngredientSpec ofInfusion(ResourceLocation id, int amount) {
        if (id == null) return EMPTY;
        return new Gas(id, Math.max(1, amount), ChemicalType.INFUSION);
    }

    static IngredientSpec ofPigment(ResourceLocation id, int amount) {
        if (id == null) return EMPTY;
        return new Gas(id, Math.max(1, amount), ChemicalType.PIGMENT);
    }

    static IngredientSpec ofSlurry(ResourceLocation id, int amount) {
        if (id == null) return EMPTY;
        return new Gas(id, Math.max(1, amount), ChemicalType.SLURRY);
    }

    static IngredientSpec withCount(IngredientSpec s, int newCount) {
        // Configured は base の量を変えて再 wrap（option + chance/tierBoost 維持）
        // 4-arg ctor を使わないと GT_CHANCE の chanceMille/tierBoost が default(1000/0) に
        // リセットされ、 count 変更だけでユーザー設定の確率が失われる。
        if (s instanceof Configured c) {
            return new Configured(withCount(c.base(), newCount), c.opt(), c.chanceMille(), c.tierBoost());
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
            // NeoForge FluidStack は component ベースで immutable 寄り。setAmount でなく copyWithAmount を使う。
            return new Fluid(fl.stack().copyWithAmount(n));
        }
        if (s instanceof FluidTag ft && ft.tagId() != null) {
            return new FluidTag(ft.tagId(), n);
        }
        if (s instanceof Gas g && g.gasId() != null) {
            return new Gas(g.gasId(), n, g.chemicalType());
        }
        return s;
    }
}