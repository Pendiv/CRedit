package DIV.credit.client.draft.botania;

import DIV.credit.Credit;
import DIV.credit.client.draft.IngredientSpec;
import DIV.credit.client.draft.RecipeDraft.SlotKind;
import net.minecraft.core.NonNullList;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;
import org.jetbrains.annotations.Nullable;

import java.lang.reflect.Constructor;
import java.util.ArrayList;
import java.util.List;

/**
 * Botania 5 schema を **本物の Recipe&lt;?&gt; class で構築** (= reflection 経由)。
 * <p>JEI category が Botania 独自の layout (= 花壺の円配置、 マナプール 等) で
 * render するのに、 generic wrapper では足りず本物クラスが必要。
 * <p>Botania class 名 / constructor 変更で reflection 壊れた時は warn log + null 返却で
 * GenericDraft 側が FALLBACK 表示に流す。
 */
public final class BotaniaRecipeFactory {

    private static long COUNTER = 0;

    // ─── class + constructor lookups (= 起動時 1 回、 失敗時 null) ───
    private static final Class<?>       PETALS_CLASS       = tryLoad("vazkii.botania.common.crafting.PetalsRecipe");
    private static final Class<?>       TERRA_PLATE_CLASS  = tryLoad("vazkii.botania.common.crafting.RecipeTerraPlate");
    private static final Class<?>       MANA_INF_CLASS     = tryLoad("vazkii.botania.common.crafting.ManaInfusionRecipe");
    private static final Class<?>       RUNIC_CLASS        = tryLoad("vazkii.botania.common.crafting.RunicAltarRecipe");
    private static final Class<?>       ELVEN_CLASS        = tryLoad("vazkii.botania.common.crafting.ElvenTradeRecipe");

    private static final Constructor<?> PETALS_CTOR        = tryGetCtor(PETALS_CLASS,
        ResourceLocation.class, ItemStack.class, Ingredient.class, Ingredient[].class);
    private static final Constructor<?> TERRA_PLATE_CTOR   = tryGetCtor(TERRA_PLATE_CLASS,
        ResourceLocation.class, int.class, NonNullList.class, ItemStack.class);
    private static final Constructor<?> MANA_INF_CTOR      = tryGetCtor(MANA_INF_CLASS,
        ResourceLocation.class, ItemStack.class, Ingredient.class, int.class, String.class,
        tryLoad("vazkii.botania.api.recipe.StateIngredient"));
    private static final Constructor<?> RUNIC_CTOR         = tryGetCtor(RUNIC_CLASS,
        ResourceLocation.class, ItemStack.class, int.class, Ingredient[].class);
    private static final Constructor<?> ELVEN_CTOR         = tryGetCtor(ELVEN_CLASS,
        ResourceLocation.class, ItemStack[].class, Ingredient[].class);

    private BotaniaRecipeFactory() {}

    @Nullable
    public static Recipe<?> tryBuild(ResourceLocation jeiUid, IngredientSpec[] slots, SlotKind[] kinds, long mana) {
        if (!"botania".equals(jeiUid.getNamespace())) return null;
        try {
            return switch (jeiUid.getPath()) {
                case "petals"       -> buildPetals(slots, kinds);
                case "terra_plate"  -> buildTerraPlate(slots, kinds, mana);
                case "mana_pool"    -> buildManaInfusion(slots, kinds, mana);
                case "runic_altar"  -> buildRunicAltar(slots, kinds, mana);
                case "elven_trade"  -> buildElvenTrade(slots, kinds);
                default -> null;
            };
        } catch (Throwable t) {
            Credit.LOGGER.warn("[CraftPattern] BotaniaRecipeFactory build failed for {}: {}", jeiUid, t.toString());
            return null;
        }
    }

    @Nullable
    private static Recipe<?> buildPetals(IngredientSpec[] slots, SlotKind[] kinds) throws Exception {
        if (PETALS_CTOR == null) return null;
        Ingredient[] inputs = collectItemInputsPadded(slots, kinds);
        ItemStack output = firstItemOutput(slots, kinds);
        boolean anyIn = false; for (Ingredient i : inputs) if (i != Ingredient.EMPTY) { anyIn = true; break; }
        if (output.isEmpty() || !anyIn) return null;
        Ingredient reagent = Ingredient.of(TagKey.create(Registries.ITEM,
            new ResourceLocation("botania", "seed_apothecary_reagent")));
        return (Recipe<?>) PETALS_CTOR.newInstance(draftId("petal"), output, reagent, inputs);
    }

    @Nullable
    private static Recipe<?> buildTerraPlate(IngredientSpec[] slots, SlotKind[] kinds, long mana) throws Exception {
        if (TERRA_PLATE_CTOR == null) return null;
        NonNullList<Ingredient> inputs = NonNullList.create();
        for (Ingredient i : collectItemInputsPadded(slots, kinds)) inputs.add(i);
        ItemStack output = firstItemOutput(slots, kinds);
        boolean anyIn = false; for (Ingredient i : inputs) if (i != Ingredient.EMPTY) { anyIn = true; break; }
        if (output.isEmpty() || !anyIn) return null;
        int m = (int) Math.max(1, mana > 0 ? mana : 500_000);
        return (Recipe<?>) TERRA_PLATE_CTOR.newInstance(draftId("terra_plate"), m, inputs, output);
    }

    @Nullable
    private static Recipe<?> buildManaInfusion(IngredientSpec[] slots, SlotKind[] kinds, long mana) throws Exception {
        if (MANA_INF_CTOR == null) return null;
        Ingredient input = firstItemInputIngredient(slots, kinds);
        ItemStack output = firstItemOutput(slots, kinds);
        if (input == null || output.isEmpty()) return null;
        int m = (int) Math.max(1, mana > 0 ? mana : 1000);
        // group=null, catalyst=null (= 基本 infusion、 block state は credit の slot 概念外)
        return (Recipe<?>) MANA_INF_CTOR.newInstance(draftId("mana_infusion"), output, input, m, null, null);
    }

    @Nullable
    private static Recipe<?> buildRunicAltar(IngredientSpec[] slots, SlotKind[] kinds, long mana) throws Exception {
        if (RUNIC_CTOR == null) return null;
        Ingredient[] inputs = collectItemInputsPadded(slots, kinds);
        ItemStack output = firstItemOutput(slots, kinds);
        boolean anyIn = false; for (Ingredient i : inputs) if (i != Ingredient.EMPTY) { anyIn = true; break; }
        if (output.isEmpty() || !anyIn) return null;
        int m = (int) Math.max(1, mana > 0 ? mana : 5000);
        return (Recipe<?>) RUNIC_CTOR.newInstance(draftId("runic_altar"), output, m, inputs);
    }

    @Nullable
    private static Recipe<?> buildElvenTrade(IngredientSpec[] slots, SlotKind[] kinds) throws Exception {
        if (ELVEN_CTOR == null) return null;
        // v3.2.x Option A: padded slot 全部を Ingredient[] / ItemStack[] に展開 (= 空も含めて JEI に
        // 描画させる)。 空 slot は Ingredient.EMPTY / ItemStack.EMPTY で渡し、 user が見て埋められる。
        Ingredient[] inputs = collectItemInputsPadded(slots, kinds);
        ItemStack[] outs   = collectItemOutputsPadded(slots, kinds);
        boolean anyIn  = false; for (Ingredient i : inputs) if (i != Ingredient.EMPTY) { anyIn = true; break; }
        boolean anyOut = false; for (ItemStack s : outs) if (!s.isEmpty()) { anyOut = true; break; }
        if (!anyIn || !anyOut) return null;
        return (Recipe<?>) ELVEN_CTOR.newInstance(draftId("elven_trade"), outs, inputs);
    }

    // ─── slot collection helpers ───

    private static Ingredient[] collectItemInputs(IngredientSpec[] slots, SlotKind[] kinds) {
        List<Ingredient> out = new ArrayList<>();
        for (int i = 0; i < slots.length; i++) {
            if (kinds[i] != SlotKind.ITEM_INPUT) continue;
            Ingredient ing = toIngredient(slots[i]);
            if (ing != Ingredient.EMPTY) out.add(ing);
        }
        return out.toArray(new Ingredient[0]);
    }

    /** v3.2.x: padded slot 全部を返す (= 空 slot = Ingredient.EMPTY)。 JEI が padded slot 数で描画するため。 */
    private static Ingredient[] collectItemInputsPadded(IngredientSpec[] slots, SlotKind[] kinds) {
        List<Ingredient> out = new ArrayList<>();
        for (int i = 0; i < slots.length; i++) {
            if (kinds[i] != SlotKind.ITEM_INPUT) continue;
            out.add(toIngredient(slots[i]));
        }
        return out.toArray(new Ingredient[0]);
    }

    private static ItemStack[] collectItemOutputsPadded(IngredientSpec[] slots, SlotKind[] kinds) {
        List<ItemStack> out = new ArrayList<>();
        for (int i = 0; i < slots.length; i++) {
            if (kinds[i] != SlotKind.ITEM_OUTPUT) continue;
            IngredientSpec s = slots[i].unwrap();
            if (s instanceof IngredientSpec.Item it && !it.stack().isEmpty()) out.add(it.stack().copy());
            else out.add(ItemStack.EMPTY);
        }
        return out.toArray(new ItemStack[0]);
    }

    @Nullable
    private static Ingredient firstItemInputIngredient(IngredientSpec[] slots, SlotKind[] kinds) {
        for (int i = 0; i < slots.length; i++) {
            if (kinds[i] != SlotKind.ITEM_INPUT) continue;
            Ingredient ing = toIngredient(slots[i]);
            if (ing != Ingredient.EMPTY) return ing;
        }
        return null;
    }

    private static ItemStack firstItemOutput(IngredientSpec[] slots, SlotKind[] kinds) {
        for (int i = 0; i < slots.length; i++) {
            if (kinds[i] != SlotKind.ITEM_OUTPUT) continue;
            IngredientSpec s = slots[i].unwrap();
            if (s instanceof IngredientSpec.Item it && !it.stack().isEmpty()) return it.stack().copy();
        }
        return ItemStack.EMPTY;
    }

    private static List<ItemStack> collectItemOutputs(IngredientSpec[] slots, SlotKind[] kinds) {
        List<ItemStack> out = new ArrayList<>();
        for (int i = 0; i < slots.length; i++) {
            if (kinds[i] != SlotKind.ITEM_OUTPUT) continue;
            IngredientSpec s = slots[i].unwrap();
            if (s instanceof IngredientSpec.Item it && !it.stack().isEmpty()) out.add(it.stack().copy());
        }
        return out;
    }

    private static Ingredient toIngredient(IngredientSpec s) {
        if (s == null) return Ingredient.EMPTY;
        s = s.unwrap();
        if (s instanceof IngredientSpec.Item it && !it.stack().isEmpty()) {
            return Ingredient.of(it.stack());
        }
        if (s instanceof IngredientSpec.Tag tg && tg.tagId() != null) {
            return Ingredient.of(TagKey.create(Registries.ITEM, tg.tagId()));
        }
        return Ingredient.EMPTY;
    }

    private static ResourceLocation draftId(String suffix) {
        return new ResourceLocation(Credit.MODID, "draft_botania_" + suffix + "_" + (++COUNTER));
    }

    // ─── reflection helpers ───

    @Nullable
    private static Class<?> tryLoad(String name) {
        try { return Class.forName(name); } catch (Throwable t) { return null; }
    }

    @Nullable
    private static Constructor<?> tryGetCtor(@Nullable Class<?> cls, Class<?>... params) {
        if (cls == null) return null;
        // null param (= StateIngredient class load 失敗時) があれば諦め
        for (Class<?> p : params) if (p == null) return null;
        try {
            Constructor<?> c = cls.getConstructor(params);
            c.setAccessible(true);
            return c;
        } catch (Throwable t) {
            Credit.LOGGER.warn("[CraftPattern] BotaniaRecipeFactory ctor lookup failed for {}: {}", cls.getName(), t.toString());
            return null;
        }
    }
}
