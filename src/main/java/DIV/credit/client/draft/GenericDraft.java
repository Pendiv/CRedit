package DIV.credit.client.draft;

import DIV.credit.Credit;
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

    // GT 専用 numeric 値（namespace=gtceu かつ sample が GTRecipe の場合のみ意味あり）
    private final boolean isGt;
    private long durationValue;
    private long eutValue;
    private final java.util.LinkedHashMap<String, Long> intDataValues = new java.util.LinkedHashMap<>();

    private GenericDraft(RecipeType<?> jeiType, SlotKind[] kinds, boolean isGt,
                         long duration, long eut, java.util.LinkedHashMap<String, Long> intData) {
        this.jeiType = jeiType;
        this.kinds   = kinds;
        this.slots   = new IngredientSpec[kinds.length];
        for (int i = 0; i < slots.length; i++) slots[i] = IngredientSpec.EMPTY;
        this.isGt = isGt;
        this.durationValue = duration;
        this.eutValue = eut;
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
            // GT カテゴリなら sample から numeric metadata 抽出
            boolean isGt = "gtceu".equals(rt.getUid().getNamespace())
                && net.minecraftforge.fml.ModList.get().isLoaded("gtceu");
            long durationInit = 0, eutInit = 0;
            java.util.LinkedHashMap<String, Long> intDataInit = new java.util.LinkedHashMap<>();
            if (isGt) {
                var meta = DIV.credit.client.draft.gt.GTSupport.probeMetadata(sample.get(), rt.getUid());
                durationInit = meta.duration;
                eutInit = meta.eut;
                intDataInit.putAll(meta.intData);
                Credit.LOGGER.info("[CraftPattern] GenericDraft GT metadata for {}: duration={} EUt={} intData={}",
                    rt.getUid(), durationInit, eutInit, intDataInit);
            }
            Credit.LOGGER.info("[CraftPattern] GenericDraft created for {}: {} slots ({} supported)",
                rt.getUid(), views.size(), supported);
            return new GenericDraft(rt, kinds, isGt, durationInit, eutInit, intDataInit);
        } catch (Exception e) {
            Credit.LOGGER.warn("[CraftPattern] GenericDraft probe failed for {}: {}",
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
    private static SlotKind inferKind(IRecipeSlotView view) {
        RecipeIngredientRole role = view.getRole();
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
            if (ModList.get().isLoaded("mekanism")
                && "mekanism.api.chemical.gas.GasStack".equals(typeUid)) {
                return output ? SlotKind.GAS_OUTPUT : SlotKind.GAS_INPUT;
            }
        }
        return null;
    }

    @Override public int slotCount() { return slots.length; }
    @Override public IngredientSpec getSlot(int i) { return slots[i]; }
    @Override public RecipeType<?> recipeType() { return jeiType; }
    @Override public boolean usesGtElectricity() { return isGt && eutValue > 0; }

    @Override
    public SlotKind slotKind(int i) {
        if (i < 0 || i >= kinds.length) return SlotKind.ITEM_INPUT;
        return kinds[i];
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
     * GT カテゴリは空 recipe を構築（duration/EUt/data も適用）→ LdLib widgets が numeric を反映描画。
     */
    @Override
    public net.minecraft.world.item.crafting.Recipe<?> toRecipeInstance() {
        if (isGt) {
            return DIV.credit.client.draft.gt.GTSupport.tryBuildEmptyRecipe(
                jeiType.getUid(), durationValue, eutValue, intDataValues);
        }
        return null;
    }

    @Override
    public java.util.List<NumericField> numericFields() {
        if (!isGt) return java.util.List.of();
        java.util.List<NumericField> fields = new java.util.ArrayList<>();
        fields.add(new NumericField("Duration", NumericField.Kind.INT,
            () -> durationValue, v -> durationValue = (long) v, 1, Integer.MAX_VALUE));
        fields.add(new NumericField("EUt", NumericField.Kind.INT,
            () -> eutValue, v -> eutValue = (long) v, 0, Integer.MAX_VALUE));
        for (String key : intDataValues.keySet()) {
            fields.add(new NumericField(prettyDataLabel(key), NumericField.Kind.INT,
                () -> intDataValues.getOrDefault(key, 0L),
                v -> intDataValues.put(key, (long) v),
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
        if ("mekanism".equals(jeiType.getUid().getNamespace())
            && net.minecraftforge.fml.ModList.get().isLoaded("mekanism")) {
            String s = DIV.credit.client.draft.mek.MekanismKubeJSEmitter.emit(
                recipeId, jeiType.getUid(), slots, kinds);
            if (s != null) return s;
        }
        return emitCommentSkeleton(recipeId);
    }

    /** GT KubeJS: event.recipes.gtceu.<path>(id).itemInputs(...).inputFluids(...).itemOutputs(...).outputFluids(...).duration(...).EUt(...) */
    private String emitGt(String recipeId) {
        java.util.List<String> itemIn = new java.util.ArrayList<>();
        java.util.List<String> fluidIn = new java.util.ArrayList<>();
        java.util.List<String> itemOut = new java.util.ArrayList<>();
        java.util.List<String> fluidOut = new java.util.ArrayList<>();
        for (int i = 0; i < slots.length; i++) {
            IngredientSpec s = slots[i];
            if (s.isEmpty()) continue;
            switch (kinds[i]) {
                case ITEM_INPUT -> { String f = formatGtItem(s); if (f != null) itemIn.add(f); }
                case ITEM_OUTPUT -> { String f = formatGtItem(s); if (f != null) itemOut.add(f); }
                case FLUID_INPUT -> { String f = formatGtFluid(s); if (f != null) fluidIn.add(f); }
                case FLUID_OUTPUT -> { String f = formatGtFluid(s); if (f != null) fluidOut.add(f); }
                default -> {}  // gas は GT recipe には基本ないので無視
            }
        }

        // GT サブカテゴリは親 type 名を使う必要あり（chem_dyes → chemical_bath 等）
        String path = DIV.credit.client.draft.gt.GTSupport.resolveKubeJsRecipeName(jeiType.getUid());
        StringBuilder sb = new StringBuilder();
        sb.append("    event.recipes.gtceu.").append(path)
          .append("('").append(recipeId).append("')\n");
        if (!itemIn.isEmpty())   sb.append("        .itemInputs(").append(String.join(", ", itemIn)).append(")\n");
        if (!fluidIn.isEmpty())  sb.append("        .inputFluids(").append(String.join(", ", fluidIn)).append(")\n");
        if (!itemOut.isEmpty())  sb.append("        .itemOutputs(").append(String.join(", ", itemOut)).append(")\n");
        if (!fluidOut.isEmpty()) sb.append("        .outputFluids(").append(String.join(", ", fluidOut)).append(")\n");
        sb.append("        .duration(").append(durationValue).append(")\n");
        sb.append("        .EUt(").append(eutValue).append(")");
        // 既知の data パラメータを named method として追加
        if (intDataValues.containsKey("ebf_temp")) {
            sb.append("\n        .blastFurnaceTemp(").append(intDataValues.get("ebf_temp")).append(")");
        }
        // それ以外の data は addData(key, val) で
        for (var e : intDataValues.entrySet()) {
            if ("ebf_temp".equals(e.getKey())) continue;
            sb.append("\n        .addData('").append(e.getKey()).append("', ").append(e.getValue()).append(")");
        }
        sb.append(";\n");
        return sb.toString();
    }

    /** GT 用 item 表記: "Nx ns:path" or "ns:path"。Tag は "Nx #ns:path"。 */
    private static String formatGtItem(IngredientSpec s) {
        int c = Math.max(1, s.count());
        if (s instanceof IngredientSpec.Item it && !it.stack().isEmpty()) {
            ResourceLocation rl = BuiltInRegistries.ITEM.getKey(it.stack().getItem());
            return c <= 1 ? "'" + rl + "'" : "'" + c + "x " + rl + "'";
        }
        if (s instanceof IngredientSpec.Tag tg && tg.tagId() != null) {
            return c <= 1 ? "'#" + tg.tagId() + "'" : "'" + c + "x #" + tg.tagId() + "'";
        }
        return null;
    }

    /** GT 用 fluid 表記: Fluid.of('ns:path', amount) or Fluid.of('#ns:tag', amount)。 */
    private static String formatGtFluid(IngredientSpec s) {
        if (s instanceof IngredientSpec.Fluid fl && !fl.stack().isEmpty()) {
            FluidStack fs = fl.stack();
            ResourceLocation rl = BuiltInRegistries.FLUID.getKey(fs.getFluid());
            return "Fluid.of('" + rl + "', " + fs.getAmount() + ")";
        }
        if (s instanceof IngredientSpec.FluidTag ft && ft.tagId() != null) {
            return "Fluid.of('#" + ft.tagId() + "', " + ft.amount() + ")";
        }
        return null;
    }

    /** Mod 別 KubeJS 形式が分からないとき用のコメントスケルトン。 */
    private String emitCommentSkeleton(String recipeId) {
        StringBuilder sb = new StringBuilder();
        sb.append("    // Auto-generated draft for category ").append(jeiType.getUid()).append("\n");
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
