package DIV.credit.client.draft;

import DIV.credit.Credit;
import com.google.gson.JsonElement;
import mezz.jei.api.constants.VanillaTypes;
import mezz.jei.api.forge.ForgeTypes;
import mezz.jei.api.gui.IRecipeLayoutDrawable;
import mezz.jei.api.gui.ingredient.IRecipeSlotView;
import mezz.jei.api.ingredients.ITypedIngredient;
import mezz.jei.api.recipe.IFocusGroup;
import mezz.jei.api.recipe.IRecipeManager;
import mezz.jei.api.recipe.RecipeIngredientRole;
import mezz.jei.api.recipe.RecipeType;
import mezz.jei.api.recipe.category.IRecipeCategory;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraftforge.fluids.FluidStack;
import net.minecraftforge.fml.ModList;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * 任意カテゴリで使える汎用 Draft。
 * 既存レシピ 1 個を probe して slot views から SlotKind を自動推定。
 * toRecipeInstance は null 固定 → 常に FALLBACK 表示 + ユーザー編集は overlay で視覚反映。
 * emit はベストエフォート（コメント形式 + 検出済み中身列挙）。
 */
public final class GenericDraft implements RecipeDraft {

    private final RecipeType<?> jeiType;
    private final SlotKind[] kinds;
    private final IngredientSpec[] slots;

    // GT 系か（gtceu + StarT-Core 等の addon。recipe class が GTRecipe 派生）
    private final boolean isGt;
    private final boolean isThermal;
    private final boolean isIf;
    private final boolean isBotania;
    // Mek 系か（mekanism + EvolvedMekanism 等の extension。category が BaseRecipeCategory 派生）
    private final boolean isMek;
    // IE 系か（immersiveengineering 本体 + 派生。category が IERecipeCategory 派生）
    private final boolean isIe;
    // Create 系か（category が CreateRecipeCategory 派生）
    private final boolean isCreate;
    // Create heat 設定 (mixing/compacting/packing のみ意味あり)
    private DIV.credit.client.draft.create.HeatLevel heatLevel = DIV.credit.client.draft.create.HeatLevel.NONE;
    // Create keepHeldItem 設定 (item_application/deploying のみ意味あり)
    private boolean keepHeldItem = false;
    /** v2.1.3: NullableLong に統一。get()/set()/isPresent()/clear() で扱う。emit ガード必須。 */
    private final RecipeDraft.NullableLong duration = new RecipeDraft.NullableLong();
    private final RecipeDraft.NullableLong eut = new RecipeDraft.NullableLong();
    /** v3.2.x: Botania mana 系 recipe (= mana_pool / runic_altar / terra_plate) の mana 要求量。 */
    private final RecipeDraft.NullableLong mana = new RecipeDraft.NullableLong();
    private final java.util.LinkedHashMap<String, Long> intDataValues = new java.util.LinkedHashMap<>();
    /** v2.0.8 GT cleanroom requirement (e.g. "cleanroom" / "sterile_cleanroom" / null = none). */
    @Nullable private String cleanroomType = null;
    /** v2.0.10: 当該 GT recipe type が電気を使うか (multi-sample probe 結果)。非電気なら EUt 編集不可。 */
    private boolean gtIsElectric = false;
    /**
     * v2.0.12: per-slot 最大 count。multi-sample probe で全サンプルが count=1 なら 1 (lock)、
     * いずれかで count>1 が観測されたら Integer.MAX_VALUE (無制限)。
     * 例: vanilla crafting output は count > 1 観測 → 無制限、入力は通常 1 → lock。
     */
    private int[] slotMaxCounts = null;  // null = 無制限 (probe 失敗時 fallback)
    /** v3.3.x auto pipeline: probe 時に Codec で抽出した sample recipe JSON (= mirror emit template)。 */
    @Nullable private JsonElement mirrorTemplate = null;
    /** v3.3.x auto pipeline: 各 slot の sample 時 displayed ingredient id (= "minecraft:iron_ingot" 等)。 mirror 用 leaf 探索キー。 */
    private String[] sampleIds = null;

    private GenericDraft(RecipeType<?> jeiType, SlotKind[] kinds,
                         boolean isGt, boolean isMek, boolean isIe, boolean isCreate,
                         boolean isThermal, boolean isIf, boolean isBotania,
                         long durationInit, long eutInit, java.util.LinkedHashMap<String, Long> intData) {
        this.jeiType = jeiType;
        this.kinds   = kinds;
        this.slots   = new IngredientSpec[kinds.length];
        for (int i = 0; i < slots.length; i++) slots[i] = IngredientSpec.EMPTY;
        this.isGt = isGt;
        this.isMek = isMek;
        this.isIe = isIe;
        this.isCreate = isCreate;
        this.isThermal = isThermal;
        this.isIf = isIf;
        this.isBotania = isBotania;
        // v2.1.3: probe 由来の初期値は set() で代入 (present=true)。
        this.duration.set(durationInit);
        this.eut.set(eutInit);
        // v3.2.x: Botania mana 系 default 値 (= category path 別)
        if (isBotania) {
            long defaultMana = switch (jeiType.getUid().getPath()) {
                case "terra_plate" -> 500_000L;
                case "runic_altar" -> 5_000L;
                case "mana_pool"   -> 1_000L;
                default            -> 0L;
            };
            if (defaultMana > 0) this.mana.set(defaultMana);
        }
        if (intData != null) this.intDataValues.putAll(intData);
    }

    /**
     * sample recipe を 1 個取って slot kinds を推定。
     * 0 supported slot や例外で null → caller がアン対応扱い。
     */
    @Nullable
    public static <T> GenericDraft tryCreate(IRecipeCategory<T> cat, IRecipeManager rm) {
        try {
            RecipeType<T> rt = cat.getRecipeType();
            var sample = rm.createRecipeLookup(rt).includeHidden().get().findFirst();
            if (sample.isEmpty()) {
                Credit.LOGGER.info("[CraftPattern] GenericDraft: no sample recipe for {}", rt.getUid());
                return null;
            }
            IFocusGroup empty = mezz.jei.api.runtime.IJeiRuntime.class.cast(
                DIV.credit.jei.CraftPatternJeiPlugin.runtime).getJeiHelpers().getFocusFactory().getEmptyFocusGroup();
            IRecipeLayoutDrawable<?> drawable = rm.createRecipeLayoutDrawable(cat, sample.get(), empty).orElse(null);
            if (drawable == null) {
                Credit.LOGGER.info("[CraftPattern] GenericDraft: cannot build sample drawable for {}", rt.getUid());
                return null;
            }

            List<IRecipeSlotView> views = drawable.getRecipeSlotsView().getSlotViews();
            int slotCount = views.size();
            SlotKind[] kinds = new SlotKind[slotCount];
            for (int i = 0; i < slotCount; i++) {
                kinds[i] = inferKind(views.get(i));
            }
            // Multi-sample probe: first sample で型が決まらないスロットは複数サンプル舐めて補完
            // （alloy_blast_smelter 等、最初のレシピで fluid slot の一部が使われない場合への対処）
            int detected = countNonNull(kinds);
            if (detected < slotCount) {
                List<T> moreSamples = rm.createRecipeLookup(rt).includeHidden().get()
                    .limit(30).toList();
                for (T extra : moreSamples) {
                    if (countNonNull(kinds) >= slotCount) break;
                    try {
                        IRecipeLayoutDrawable<?> d2 = rm.createRecipeLayoutDrawable(cat, extra, empty).orElse(null);
                        if (d2 == null) continue;
                        List<IRecipeSlotView> v2 = d2.getRecipeSlotsView().getSlotViews();
                        for (int i = 0; i < Math.min(v2.size(), slotCount); i++) {
                            if (kinds[i] != null) continue;
                            SlotKind k = inferKind(v2.get(i));
                            if (k != null) kinds[i] = k;
                        }
                    } catch (Exception ignored) {}
                }
            }
            int supported = countNonNull(kinds);
            if (supported == 0) {
                Credit.LOGGER.info("[CraftPattern] GenericDraft: no item/fluid/gas slots in {} (views={})",
                    rt.getUid(), slotCount);
                return null;
            }
            // 残った null は role でフォールバック（OUTPUT → ITEM_OUTPUT、その他 → ITEM_INPUT）
            for (int i = 0; i < kinds.length; i++) {
                if (kinds[i] == null) {
                    var role = views.get(i).getRole();
                    kinds[i] = (role == mezz.jei.api.recipe.RecipeIngredientRole.OUTPUT)
                        ? SlotKind.ITEM_OUTPUT : SlotKind.ITEM_INPUT;
                }
            }
            // システム判定（modId 不問）
            boolean isGt  = DIV.credit.client.draft.gt.GTSupport.isGtCategory(cat)
                && net.minecraftforge.fml.ModList.get().isLoaded("gtceu");
            boolean isMek = DIV.credit.client.draft.mek.MekanismSupport.isMekCategory(cat)
                && net.minecraftforge.fml.ModList.get().isLoaded("mekanism");
            boolean isIe  = DIV.credit.client.draft.ie.IESupport.isIeCategory(cat)
                && net.minecraftforge.fml.ModList.get().isLoaded("immersiveengineering");
            boolean isCreate = DIV.credit.client.draft.create.CreateSupport.isCreateCategory(cat)
                && net.minecraftforge.fml.ModList.get().isLoaded("create");
            boolean isThermal = DIV.credit.client.draft.thermal.ThermalSupport.isThermalCategory(cat)
                && net.minecraftforge.fml.ModList.get().isLoaded("thermal");
            boolean isIf = DIV.credit.client.draft.industrialforegoing.IFSupport.isIFCategory(cat)
                && net.minecraftforge.fml.ModList.get().isLoaded("industrialforegoing");
            boolean isBotania = DIV.credit.client.draft.botania.BotaniaSupport.isBotaniaCategory(cat)
                && net.minecraftforge.fml.ModList.get().isLoaded("botania");
            long durationInit = 0, eutInit = 0;
            java.util.LinkedHashMap<String, Long> intDataInit = new java.util.LinkedHashMap<>();
            String cleanroomInit = null;
            boolean isElectric = false;
            if (isGt) {
                var meta = DIV.credit.client.draft.gt.GTSupport.probeMetadata(sample.get(), rt.getUid());
                durationInit = meta.duration;
                eutInit = meta.eut;
                intDataInit.putAll(meta.intData);
                cleanroomInit = DIV.credit.client.draft.gt.GTSupport.probeCleanroom(sample.get());
                // v2.0.10: multi-sample probe で電気種別判定 (型特性として max EUt > 0 か)
                if (eutInit > 0) {
                    isElectric = true;
                } else {
                    var moreSamples = rm.createRecipeLookup(rt).includeHidden().get().limit(20).toList();
                    for (T s : moreSamples) {
                        var m = DIV.credit.client.draft.gt.GTSupport.probeMetadata(s, rt.getUid());
                        if (m.eut > 0) { isElectric = true; break; }
                    }
                }
                Credit.LOGGER.info("[CraftPattern] GenericDraft GT metadata for {}: duration={} EUt={} intData={} cleanroom={} electric={}",
                    rt.getUid(), durationInit, eutInit, intDataInit, cleanroomInit, isElectric);
            }
            // v3.2.x Option A: Botania は sample probe の slot 数では足りない category がある
            //   (= elven_trade で 2 input 描けない等)。 target slot count に従って padding。
            //   INPUT 系を先頭に詰めて、 OUTPUT 系を後ろに。 順序は JEI category の
            //   setRecipe での addSlot 順 (= INPUT 列挙 → OUTPUT 列挙) と揃える。
            if (isBotania) {
                int[] target = DIV.credit.client.draft.botania.BotaniaSupport.getTargetSlotCounts(rt.getUid().getPath());
                if (target != null) {
                    SlotKind[] padded = padBotaniaSlots(kinds, target[0], target[1]);
                    if (padded.length != kinds.length) {
                        kinds = padded;
                        slotCount = kinds.length;
                    }
                }
            }
            Credit.LOGGER.info("[CraftPattern] GenericDraft created for {}: {} slots ({} supported) isGt={} isMek={} isIe={} isCreate={} isThermal={} isIf={} isBotania={}",
                rt.getUid(), slotCount, supported, isGt, isMek, isIe, isCreate, isThermal, isIf, isBotania);
            // v2.1.4: GenericDraft 経路 (GT/Mek/IE/Create/AE2/Avaritia) は
            // 全 system が ingredient JSON に count を書けるので、slot count を MAX_VALUE 固定。
            // v2.0.12 の probe ロジック (sample 全部 count=1 → 1 lock) は GT で右クリック
            // count up が block されるバグの原因だったため撤去。
            // vanilla の crafting/cooking 等は専用 draft (CraftingDraft/CookingDraft 等) で
            // 直接 override しているため GenericDraft fallback には来ない。
            int[] slotMaxCounts = new int[slotCount];
            for (int i = 0; i < slotCount; i++) slotMaxCounts[i] = Integer.MAX_VALUE;

            GenericDraft d = new GenericDraft(rt, kinds, isGt, isMek, isIe, isCreate, isThermal, isIf, isBotania,
                durationInit, eutInit, intDataInit);
            d.cleanroomType = cleanroomInit;
            d.gtIsElectric = isElectric;
            d.slotMaxCounts = slotMaxCounts;
            // v3.3.x auto pipeline: hand-written 適用外の category 向けに mirror 用 sample data 確保。
            // GT/Mek/IE/Create/Thermal/IF/Botania 専用 emitter 側が優先されるので、 GT 等で取り出しても無害。
            try {
                Object sampleObj = sample.get();
                if (sampleObj instanceof Recipe<?> rec) {
                    d.mirrorTemplate = DIV.credit.client.draft.auto.CodecExtractor.tryExtract(rec);
                }
                d.sampleIds = new String[slotCount];
                for (int i = 0; i < slotCount && i < views.size(); i++) {
                    d.sampleIds[i] = extractDisplayedItemId(views.get(i));
                }
            } catch (Exception e) {
                Credit.LOGGER.debug("[CraftPattern] GenericDraft auto-capture failed for {}: {}",
                    rt.getUid(), e.toString());
            }
            return d;
        } catch (Exception e) {
            Credit.LOGGER.warn("[C4014] GenericDraft probe failed for {}: {}",
                cat.getRecipeType().getUid(), e.toString());
            return null;
        }
    }

    private static int countNonNull(SlotKind[] arr) {
        int n = 0;
        for (SlotKind k : arr) if (k != null) n++;
        return n;
    }

    /** slot view の role + ingredient type → SlotKind。判定不可なら null。 */
    @Nullable
    /**
     * v3.2.x Botania padding: probe で得た kinds[] を、 target {maxInputs, maxOutputs} に応じて
     * 拡張。 結果は [INPUT × maxInputs..., OUTPUT × maxOutputs] の順 (= JEI category の
     * setRecipe addSlot 順と合致、 loadFromRecipe の positional mapping が壊れない)。
     * すでに max を超えてれば変更なし。
     */
    private static SlotKind[] padBotaniaSlots(SlotKind[] kinds, int maxInputs, int maxOutputs) {
        int curIn = 0, curOut = 0;
        for (SlotKind k : kinds) {
            if (k == SlotKind.ITEM_INPUT)  curIn++;
            if (k == SlotKind.ITEM_OUTPUT) curOut++;
        }
        int newIn  = Math.max(curIn,  maxInputs);
        int newOut = Math.max(curOut, maxOutputs);
        if (newIn == curIn && newOut == curOut) return kinds;
        SlotKind[] out = new SlotKind[newIn + newOut];
        for (int i = 0; i < newIn; i++)  out[i]          = SlotKind.ITEM_INPUT;
        for (int i = 0; i < newOut; i++) out[newIn + i]  = SlotKind.ITEM_OUTPUT;
        return out;
    }

    /** v3.3.x auto pipeline: slot view から displayed ingredient の id 抽出 (= mirror leaf 探索キー)。 */
    @Nullable
    private static String extractDisplayedItemId(IRecipeSlotView view) {
        var displayed = view.getDisplayedIngredient();
        ITypedIngredient<?> ti = displayed.orElse(null);
        if (ti == null) {
            ti = view.getAllIngredients()
                .filter(ITypedIngredient.class::isInstance)
                .map(o -> (ITypedIngredient<?>) o)
                .findFirst().orElse(null);
        }
        if (ti == null) return null;
        Object obj = ti.getIngredient();
        if (obj instanceof ItemStack stack && !stack.isEmpty()) {
            ResourceLocation rl = BuiltInRegistries.ITEM.getKey(stack.getItem());
            return rl == null ? null : rl.toString();
        }
        if (obj instanceof FluidStack fs && !fs.isEmpty()) {
            ResourceLocation rl = BuiltInRegistries.FLUID.getKey(fs.getFluid());
            return rl == null ? null : rl.toString();
        }
        return null;
    }

    private static SlotKind inferKind(IRecipeSlotView view) {
        RecipeIngredientRole role = view.getRole();
        // v3.2.x: CATALYST role (= Botania petals の altar 表示等、 fixed decoration) は user 編集不可
        // null kind 返却 → tryCreate の slot probe で skip される
        if (role == RecipeIngredientRole.CATALYST) return null;
        boolean output = role == RecipeIngredientRole.OUTPUT;
        // ingredient type は最初に見つけたもので決める
        for (Object obj : view.getAllIngredients().toList()) {
            if (!(obj instanceof ITypedIngredient<?> ti)) continue;
            String typeUid = ti.getType().getUid();
            if (VanillaTypes.ITEM_STACK.getUid().equals(typeUid)) {
                return output ? SlotKind.ITEM_OUTPUT : SlotKind.ITEM_INPUT;
            }
            if (ForgeTypes.FLUID_STACK.getUid().equals(typeUid)) {
                return output ? SlotKind.FLUID_OUTPUT : SlotKind.FLUID_INPUT;
            }
            if (ModList.get().isLoaded("mekanism")) {
                // v3.3.x: Mek chemical 4 種は全て GAS_INPUT/OUTPUT slot kind で扱う (= IngredientSpec.Gas.chemicalType で区別)
                if ("mekanism.api.chemical.gas.GasStack".equals(typeUid)
                    || "mekanism.api.chemical.infuse.InfusionStack".equals(typeUid)
                    || "mekanism.api.chemical.pigment.PigmentStack".equals(typeUid)
                    || "mekanism.api.chemical.slurry.SlurryStack".equals(typeUid)) {
                    return output ? SlotKind.GAS_OUTPUT : SlotKind.GAS_INPUT;
                }
            }
        }
        return null;
    }

    @Override public int slotCount() { return slots.length; }
    @Override public IngredientSpec getSlot(int i) { return slots[i]; }
    @Override public RecipeType<?> recipeType() { return jeiType; }
    /** v2.0.10: 電気種別の判定。eutValue ではなく gtIsElectric (recipe type 由来) を見る。 */
    @Override public boolean usesGtElectricity() { return isGt && gtIsElectric; }

    @Override
    public boolean canRequireHeat() {
        if (!isCreate) return false;
        String path = jeiType.getUid().getPath();
        // JEI category UID ベース。packing は実は compacting レシピで heat 可。
        return "mixing".equals(path) || "compacting".equals(path) || "packing".equals(path);
    }

    @Override public DIV.credit.client.draft.create.HeatLevel getHeatLevel() { return heatLevel; }

    @Override public void setHeatLevel(DIV.credit.client.draft.create.HeatLevel level) {
        if (level != null) this.heatLevel = level;
    }

    // ─── v2.0.8 cleanroom (GT のみ) ───
    @Nullable public String getCleanroomType()                  { return cleanroomType; }
    public void            setCleanroomType(@Nullable String s) { this.cleanroomType = (s == null || s.isEmpty()) ? null : s; }
    public boolean         supportsCleanroom()                  { return isGt; }

    @Override
    public boolean canKeepHeldItem() {
        if (!isCreate) return false;
        String path = jeiType.getUid().getPath();
        return "item_application".equals(path) || "deploying".equals(path);
    }

    @Override public boolean isKeepHeldItem() { return keepHeldItem; }

    @Override public void setKeepHeldItem(boolean value) { this.keepHeldItem = value; }

    @Override
    public SlotKind slotKind(int i) {
        if (i < 0 || i >= kinds.length) return SlotKind.ITEM_INPUT;
        return kinds[i];
    }

    @Override
    public int slotMaxCount(int slotIndex) {
        if (slotMaxCounts == null || slotIndex < 0 || slotIndex >= slotMaxCounts.length) {
            return Integer.MAX_VALUE;
        }
        return slotMaxCounts[slotIndex];
    }

    /**
     * IRecipeLayoutDrawable の slot views を walk して、各 slot の displayed ingredient を draft に流し込む。
     * Item / Fluid / Gas (Mek 系) を識別。複数候補は最初を採用。
     * GT metadata (duration/EUt/intData) は recipe instance を probe して反映。
     */
    @Override
    public boolean loadFromRecipe(IRecipeLayoutDrawable<?> layout) {
        try {
            List<IRecipeSlotView> views = layout.getRecipeSlotsView().getSlotViews();
            int n = Math.min(views.size(), slots.length);
            int loaded = 0;
            for (int i = 0; i < n; i++) {
                IngredientSpec spec = readSpecFromView(views.get(i));
                if (!spec.isEmpty()) {
                    slots[i] = spec;
                    loaded++;
                }
            }
            // GT metadata 反映
            if (isGt) {
                Object recipe = layout.getRecipe();
                if (recipe instanceof net.minecraft.world.item.crafting.Recipe<?> mcr) {
                    var meta = DIV.credit.client.draft.gt.GTSupport.probeMetadata(mcr, jeiType.getUid());
                    this.duration.set(meta.duration);
                    this.eut.set(meta.eut);
                    this.intDataValues.clear();
                    this.intDataValues.putAll(meta.intData);
                    // v2.0.8: cleanroom 要件抽出
                    this.cleanroomType = DIV.credit.client.draft.gt.GTSupport.probeCleanroom(mcr);
                }
            }
            Credit.LOGGER.info("[CraftPattern] GenericDraft.loadFromRecipe {} → {}/{} slots",
                jeiType.getUid(), loaded, n);
            return loaded > 0;
        } catch (Exception e) {
            Credit.LOGGER.warn("[C4015] GenericDraft.loadFromRecipe failed for {}: {}",
                jeiType.getUid(), e.toString());
            return false;
        }
    }

    /**
     * Slot view の displayed ingredient を IngredientSpec に変換。複数候補 ITypedIngredient のうち最初を採用。
     * v3.0.1: hand-written draft (GTAssembler / GTCompressor / PressurizedReaction 等) からも呼ばれるため public 化。
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    public static IngredientSpec readSpecFromView(IRecipeSlotView view) {
        var displayed = view.getDisplayedIngredient();
        ITypedIngredient<?> ti;
        if (displayed.isPresent()) {
            ti = displayed.get();
        } else {
            // displayed なしなら all ingredients から最初の有効なものを探す
            ti = view.getAllIngredients()
                .filter(ITypedIngredient.class::isInstance)
                .map(o -> (ITypedIngredient<?>) o)
                .findFirst().orElse(null);
        }
        if (ti == null) return IngredientSpec.EMPTY;
        Object obj = ti.getIngredient();
        if (obj instanceof ItemStack stack && !stack.isEmpty()) {
            return new IngredientSpec.Item(stack.copy());
        }
        if (obj instanceof FluidStack fs && !fs.isEmpty()) {
            return new IngredientSpec.Fluid(fs.copy());
        }
        // v3.3.x: Mek chemical 4 種 (gas/infusion/pigment/slurry) を統一抽出。
        //   MekanismIngredientAdapter.tryChemical が IIngredientType ベースで spec 返す。
        if (ModList.get().isLoaded("mekanism")) {
            try {
                IngredientSpec chem = DIV.credit.client.jei.mek.MekanismIngredientAdapter
                    .tryChemical((mezz.jei.api.ingredients.IIngredientType<Object>) ti.getType(), obj);
                if (chem != null && !chem.isEmpty()) return chem;
            } catch (Exception ignored) {}
        }
        return IngredientSpec.EMPTY;
    }

    @Override
    public void setSlot(int i, IngredientSpec s) {
        if (i < 0 || i >= slots.length) return;
        if (s == null) s = IngredientSpec.EMPTY;
        if (!acceptsAt(i, s)) return;
        slots[i] = s;
    }

    /**
     * 通常は null → FALLBACK 表示。
     * GT カテゴリは slots 込み recipe を構築 (= preview 真 drawable 用)。 Builder Screen 編集中は
     * slot overlay で別描画してたが、 preview では JEI drawable に渡す Recipe<?> に slots が無いと描かれない。
     */
    @Override
    public net.minecraft.world.item.crafting.Recipe<?> toRecipeInstance() {
        if (isGt) {
            long dur = duration.isPresent() ? duration.get() : 0;
            long eu  = eut.isPresent()      ? eut.get()      : 0;
            return DIV.credit.client.draft.gt.GTSupport.tryBuildRecipeWithSlots(
                jeiType.getUid(), dur, eu, intDataValues, cleanroomType, slots, kinds);
        }
        if (isBotania) {
            // v3.2.x: reflection で本物 Recipe<?> class 構築 → JEI が Botania 純正 layout で render
            long m = mana.isPresent() ? mana.get() : 0L;
            Credit.LOGGER.info("[CraftPattern] Botania toRecipeInstance {} mana={}", jeiType.getUid(), m);
            return DIV.credit.client.draft.botania.BotaniaRecipeFactory.tryBuild(
                jeiType.getUid(), slots, kinds, m);
        }
        return null;
    }

    @Override
    public java.util.List<NumericField> numericFields() {
        // v3.2.x: Botania mana 系は mana NumericField 1 個
        if (isBotania) {
            String path = jeiType.getUid().getPath();
            if ("mana_pool".equals(path) || "runic_altar".equals(path) || "terra_plate".equals(path)) {
                return java.util.List.of(
                    mana.toField("Mana", NumericField.Kind.INT, 0, Integer.MAX_VALUE));
            }
            return java.util.List.of();
        }
        if (!isGt) return java.util.List.of();
        java.util.List<NumericField> fields = new java.util.ArrayList<>();
        // v2.1.3: 全 GT 数値 field を一斉 nullable=true 化 (helper.toField 経由)。
        // 「本来編集不要なのに probe で混ざってしまった field」を null 入力で省略可能に。
        fields.add(duration.toField("Duration", NumericField.Kind.INT, 1, Integer.MAX_VALUE));
        if (gtIsElectric) {
            fields.add(eut.toField("EUt", NumericField.Kind.INT, 0, Integer.MAX_VALUE));
        }
        // v2.1.3: intData (ebf_temp 等) も nullable=true で intDataField helper 経由。
        // ConcurrentModification 回避のため key list を snapshot してから iterate。
        for (String key : new java.util.ArrayList<>(intDataValues.keySet())) {
            fields.add(RecipeDraft.intDataField(key, prettyDataLabel(key), intDataValues,
                0, Integer.MAX_VALUE));
        }
        return fields;
    }

    /** "ebf_temp" → "EBF Temp"、"solderMultiplier" → "Solder Multiplier" 等の軽い整形。 */
    private static String prettyDataLabel(String key) {
        if (key == null || key.isEmpty()) return key;
        // snake_case → Title Case
        if (key.contains("_")) {
            StringBuilder sb = new StringBuilder();
            for (String part : key.split("_")) {
                if (sb.length() > 0) sb.append(' ');
                if (!part.isEmpty()) sb.append(Character.toUpperCase(part.charAt(0))).append(part.substring(1));
            }
            return sb.toString();
        }
        // camelCase → Title Case
        StringBuilder sb = new StringBuilder();
        sb.append(Character.toUpperCase(key.charAt(0)));
        for (int i = 1; i < key.length(); i++) {
            char c = key.charAt(i);
            if (Character.isUpperCase(c) && i > 0 && !Character.isUpperCase(key.charAt(i - 1))) sb.append(' ');
            sb.append(c);
        }
        return sb.toString();
    }

    @Override
    public String relativeOutputPath() {
        ResourceLocation rl = jeiType.getUid();
        return "generated/" + rl.getNamespace() + "/" + rl.getPath() + ".js";
    }

    /**
     * GT 系は KubeJS GT 共通形式、Mek 系は per-schema 形式。それ以外はコメント形式。
     */
    @Override
    public String emit(String recipeId) {
        boolean anyEdit = false;
        for (IngredientSpec s : slots) if (!s.isEmpty()) { anyEdit = true; break; }
        if (!anyEdit) return null;

        if (isGt) return emitGt(recipeId);
        if (isMek) {
            // 既知 Mek schema にマッチすれば schema 形式、それ以外（addon の独自カテゴリ等）は
            // 下のコメント skeleton にフォールバック
            String s = DIV.credit.client.draft.mek.MekanismKubeJSEmitter.emit(
                recipeId, jeiType.getUid(), slots, kinds);
            if (s != null) return s;
        }
        if (isIe) {
            String s = DIV.credit.client.draft.ie.IEKubeJSEmitter.emit(
                recipeId, jeiType.getUid(), slots, kinds);
            if (s != null) return s;
        }
        if (isCreate) {
            String s = DIV.credit.client.draft.create.CreateKubeJSEmitter.emit(
                recipeId, jeiType.getUid(), slots, kinds, heatLevel, keepHeldItem);
            if (s != null) return s;
        }
        if (isThermal) {
            String s = DIV.credit.client.draft.thermal.ThermalKubeJSEmitter.emit(
                recipeId, jeiType.getUid(), slots, kinds);
            if (s != null) return s;
        }
        if (isIf) {
            String s = DIV.credit.client.draft.industrialforegoing.IFKubeJSEmitter.emit(
                recipeId, jeiType.getUid(), slots, kinds);
            if (s != null) return s;
        }
        if (isBotania) {
            long m = mana.isPresent() ? mana.get() : 0L;
            String s = DIV.credit.client.draft.botania.BotaniaKubeJSEmitter.emit(
                recipeId, jeiType.getUid(), slots, kinds, m);
            if (s != null) return s;
        }
        // v3.3.x auto pipeline: hand-written 全 fail なら mirror → pattern auto 試行。
        // 通れば event.custom emit、 全 fail なら skeleton fallback。
        String auto = tryAutoEmit(recipeId);
        if (auto != null) return auto;
        return emitCommentSkeleton(recipeId);
    }

    /**
     * auto pipeline entry。 cached strategy → mirror → pattern の順試行。
     * 成功 strategy は {@link DIV.credit.client.draft.auto.LearnedSchemaCache} に記憶。
     */
    @Nullable
    private String tryAutoEmit(String recipeId) {
        String uid = jeiType.getUid().toString();
        DIV.credit.client.draft.auto.LearnedSchemaCache.loadIfNeeded();
        String cached = DIV.credit.client.draft.auto.LearnedSchemaCache.get(uid);
        if (cached != null) {
            String r = runAutoStrategy(cached, recipeId);
            if (r != null) return r;
            DIV.credit.client.draft.auto.LearnedSchemaCache.evict(uid);
        }
        // mirror (= 案 D) 優先
        if (mirrorTemplate != null && sampleIds != null) {
            var mr = DIV.credit.client.draft.auto.MirrorEmitter.tryEmit(
                recipeId, jeiType.getUid(), slots, kinds, mirrorTemplate, sampleIds);
            if (mr != null) {
                DIV.credit.client.draft.auto.LearnedSchemaCache.put(uid,
                    DIV.credit.client.draft.auto.LearnedSchemaCache.MIRROR_STRATEGY);
                return mr.jsCode();
            }
        }
        // pattern (= 案 E) fallback
        var ar = DIV.credit.client.draft.auto.AutoPatternEmitter.tryEmit(
            recipeId, jeiType.getUid(), slots, kinds);
        if (ar != null) {
            DIV.credit.client.draft.auto.LearnedSchemaCache.put(uid,
                DIV.credit.client.draft.auto.LearnedSchemaCache.patternStrategy(ar.patternId()));
            return ar.jsCode();
        }
        return null;
    }

    @Nullable
    private String runAutoStrategy(String strategy, String recipeId) {
        if (DIV.credit.client.draft.auto.LearnedSchemaCache.isMirror(strategy)) {
            if (mirrorTemplate == null || sampleIds == null) return null;
            var r = DIV.credit.client.draft.auto.MirrorEmitter.tryEmit(
                recipeId, jeiType.getUid(), slots, kinds, mirrorTemplate, sampleIds);
            return r == null ? null : r.jsCode();
        }
        if (DIV.credit.client.draft.auto.LearnedSchemaCache.isPattern(strategy)) {
            // 簡略: 戦略 id 限定再実行ではなく全 pattern 再走 (= 戦略順並びで早期命中する)
            var r = DIV.credit.client.draft.auto.AutoPatternEmitter.tryEmit(
                recipeId, jeiType.getUid(), slots, kinds);
            return r == null ? null : r.jsCode();
        }
        return null;
    }

    /**
     * GT KubeJS: event.recipes.gtceu.<path>(id)
     *   .itemInputs(...) .inputFluids(...) .itemOutputs(...) .outputFluids(...)
     *   [.notConsumable(catalyst)]   <- GT_CATALYST 入力
     *   [.chancedOutput(stack, c, b)]<- GT_CHANCE 出力 (1000分率→×10)
     *   .duration(...) .EUt(...) [.addData(k,v) ...]
     */
    private String emitGt(String recipeId) {
        java.util.List<String> itemIn         = new java.util.ArrayList<>();
        java.util.List<String> fluidIn        = new java.util.ArrayList<>();
        java.util.List<String> itemOut        = new java.util.ArrayList<>();
        java.util.List<String> fluidOut       = new java.util.ArrayList<>();
        java.util.List<String> notConsumable  = new java.util.ArrayList<>();
        java.util.List<String> chancedItemOut = new java.util.ArrayList<>();
        java.util.List<String> chancedFlOut   = new java.util.ArrayList<>();
        // v4.1.x: programmed circuit は itemInput でなく GT 専用構文 .circuit(n) で emit する。
        // null = circuit 入力なし。 検出時は configuration NBT を保持して後段で出力。
        Integer circuit = null;
        for (int i = 0; i < slots.length; i++) {
            IngredientSpec s = slots[i];
            if (s.isEmpty()) continue;
            boolean catalyst = DIV.credit.client.draft.gt.GTEmitFormat.isCatalyst(s);
            boolean chance   = DIV.credit.client.draft.gt.GTEmitFormat.isChance(s);
            switch (kinds[i]) {
                case ITEM_INPUT -> {
                    // circuit は itemInputs から外し .circuit(n) で別途 emit (= reload で番号が消える bug 修正)
                    if (DIV.credit.client.draft.gt.GTEmitFormat.isProgrammedCircuit(s)) {
                        circuit = DIV.credit.client.draft.gt.GTEmitFormat.circuitConfig(s);
                        break;
                    }
                    String f = DIV.credit.client.draft.gt.GTEmitFormat.formatItem(s);
                    if (f == null) break;
                    if (catalyst) notConsumable.add(f);
                    else          itemIn.add(f);
                }
                case ITEM_OUTPUT -> {
                    String f = DIV.credit.client.draft.gt.GTEmitFormat.formatItem(s);
                    if (f == null) break;
                    if (chance) chancedItemOut.add(f + ", "
                        + DIV.credit.client.draft.gt.GTEmitFormat.chanceArgs(s));
                    else        itemOut.add(f);
                }
                case FLUID_INPUT -> {
                    String f = DIV.credit.client.draft.gt.GTEmitFormat.formatFluid(s);
                    if (f != null) fluidIn.add(f);
                }
                case FLUID_OUTPUT -> {
                    String f = DIV.credit.client.draft.gt.GTEmitFormat.formatFluid(s);
                    if (f == null) break;
                    if (chance) chancedFlOut.add(f + ", "
                        + DIV.credit.client.draft.gt.GTEmitFormat.chanceArgs(s));
                    else        fluidOut.add(f);
                }
                default -> {}  // gas は GT recipe には基本ないので無視
            }
        }

        // GT サブカテゴリは親 type 名を使う必要あり（chem_dyes → chemical_bath 等）
        String path = DIV.credit.client.draft.gt.GTSupport.resolveKubeJsRecipeName(jeiType.getUid());
        // KubeJS 経路は registered RecipeType の namespace を使う。
        // gtceu native も addon (StarT-Core 等) も addon の namespace で emit される。
        String ns = jeiType.getUid().getNamespace();
        StringBuilder sb = new StringBuilder();
        sb.append("    event.recipes.").append(ns).append('.').append(path)
          .append("('").append(recipeId).append("')\n");
        if (!itemIn.isEmpty())   sb.append("        .itemInputs(").append(String.join(", ", itemIn)).append(")\n");
        if (circuit != null)     sb.append("        .circuit(").append(circuit).append(")\n");
        if (!fluidIn.isEmpty())  sb.append("        .inputFluids(").append(String.join(", ", fluidIn)).append(")\n");
        if (!itemOut.isEmpty())  sb.append("        .itemOutputs(").append(String.join(", ", itemOut)).append(")\n");
        if (!fluidOut.isEmpty()) sb.append("        .outputFluids(").append(String.join(", ", fluidOut)).append(")\n");
        for (String nc : notConsumable)   sb.append("        .notConsumable(").append(nc).append(")\n");
        for (String c  : chancedItemOut)  sb.append("        .chancedOutput(").append(c).append(")\n");
        for (String c  : chancedFlOut)    sb.append("        .chancedFluidOutput(").append(c).append(")\n");
        // v2.1.3: duration nullable=true。未設定なら .duration(...) ごと省略 (= デフォルト時間)。
        if (duration.isPresent()) {
            sb.append("        .duration(").append(duration.get()).append(")");
        }
        // .EUt(0) は非電気機械で KubeJS エラーになるため skip。電気機械なら EUt 最低 1 (ULV) なので
        // 0 = 「非電気」と判定して安全。null 化 (eut.isPresent=false) でも skip。
        if (eut.isPresent() && eut.get() > 0) {
            // duration が isPresent=false で sb 末尾に改行が無い時もあるが、出力フォーマットは
            // KubeJS が許容するので neglect (見た目軽微)。
            sb.append("\n        .EUt(").append(eut.get()).append(")");
        }
        // 既知の data パラメータを named method として追加
        if (intDataValues.containsKey("ebf_temp")) {
            sb.append("\n        .blastFurnaceTemp(").append(intDataValues.get("ebf_temp")).append(")");
        }
        // それ以外の data は addData(key, val) で
        for (var e : intDataValues.entrySet()) {
            if ("ebf_temp".equals(e.getKey())) continue;
            sb.append("\n        .addData('").append(e.getKey()).append("', ").append(e.getValue()).append(")");
        }
        // v2.0.8: cleanroom requirement
        // KubeJS は CleanroomType を文字列で受けない (string overload 無し) → 定数参照を使う:
        //   .cleanroom(CleanroomType.CLEANROOM) / .cleanroom(CleanroomType.STERILE_CLEANROOM)
        // 規約: 登録名 (snake_case) を UPPER_SNAKE_CASE に変換すれば static field 名と一致する想定。
        if (cleanroomType != null && !cleanroomType.isEmpty()) {
            String upper = cleanroomType.toUpperCase(java.util.Locale.ROOT);
            sb.append("\n        .cleanroom(CleanroomType.").append(upper).append(")");
        }
        sb.append(";\n");
        return sb.toString();
    }

    /** skeleton fallback 検出用マーカー (= chat warn 判定で BuilderScreen が contains 検査)。 */
    public static final String SKELETON_MARKER = "// CraftPattern: auto-support failed, skeleton fallback for ";

    /** Mod 別 KubeJS 形式が分からないとき用のコメントスケルトン。 */
    private String emitCommentSkeleton(String recipeId) {
        StringBuilder sb = new StringBuilder();
        sb.append("    ").append(SKELETON_MARKER).append(jeiType.getUid()).append("\n");
        sb.append("    // recipeId: ").append(recipeId).append("\n");
        sb.append("    // TODO: convert to actual KubeJS recipe call (mod-specific)\n");
        sb.append("    /*\n");
        for (int i = 0; i < slots.length; i++) {
            sb.append("     *   slot[").append(i).append("] ").append(kinds[i].name()).append(": ");
            sb.append(describeSpec(slots[i]));
            sb.append("\n");
        }
        sb.append("     */\n");
        return sb.toString();
    }

    private static String describeSpec(IngredientSpec s) {
        if (s.isEmpty()) return "(empty)";
        if (s instanceof IngredientSpec.Item it) {
            ResourceLocation rl = BuiltInRegistries.ITEM.getKey(it.stack().getItem());
            return rl + " x" + it.stack().getCount();
        }
        if (s instanceof IngredientSpec.Tag tg) {
            return "#" + tg.tagId() + " x" + tg.count();
        }
        if (s instanceof IngredientSpec.Fluid fl) {
            FluidStack fs = fl.stack();
            ResourceLocation rl = BuiltInRegistries.FLUID.getKey(fs.getFluid());
            return rl + " " + fs.getAmount() + "mB";
        }
        if (s instanceof IngredientSpec.FluidTag ft) {
            return "#" + ft.tagId() + " " + ft.amount() + "mB";
        }
        if (s instanceof IngredientSpec.Gas g) {
            return g.gasId() + " " + g.amount() + "mB";
        }
        return s.toString();
    }
}
