package DIV.credit.client.draft;

import DIV.credit.Credit;
import mezz.jei.api.constants.RecipeTypes;
import mezz.jei.api.gui.IRecipeLayoutDrawable;
import mezz.jei.api.recipe.RecipeType;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.AbstractCookingRecipe;
import net.minecraft.world.item.crafting.BlastingRecipe;
import net.minecraft.world.item.crafting.CampfireCookingRecipe;
import net.minecraft.world.item.crafting.CookingBookCategory;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.SmeltingRecipe;
import net.minecraft.world.item.crafting.SmokingRecipe;

import java.util.List;

/**
 * AbstractCookingCategory を共通レイアウトとする 4 種：smelting / blasting / smoking / campfireCooking。
 * JEI スロット並び：index 0 = INPUT、index 1 = RENDER_ONLY (flame)、index 2 = OUTPUT。
 */
public class CookingDraft implements RecipeDraft {

    public enum Type {
        // viewSlotCount: JEI が返す slot view 数。 smelting/blasting/smoking は FLAME slot 込みで 3、
        // campfire は FLAME slot 描画なしで 2 (= JEI vanilla CampfireRecipeCategory より)。
        SMELTING(RecipeTypes.SMELTING, "smelting", CookingBookCategory.MISC, 200, 0.7f, 3),
        BLASTING(RecipeTypes.BLASTING, "blasting", CookingBookCategory.MISC, 100, 0.7f, 3),
        SMOKING(RecipeTypes.SMOKING, "smoking", CookingBookCategory.FOOD, 100, 0.35f, 3),
        CAMPFIRE(RecipeTypes.CAMPFIRE_COOKING, "campfireCooking", CookingBookCategory.FOOD, 600, 0.35f, 2);

        public final RecipeType<?>          jeiType;
        public final String                 kjsMethod;
        public final CookingBookCategory    cookCategory;
        public final int                    defaultTime;
        public final float                  defaultXp;
        public final int                    viewSlotCount;

        Type(RecipeType<?> jt, String km, CookingBookCategory cc, int dt, float dx, int vsc) {
            this.jeiType = jt; this.kjsMethod = km; this.cookCategory = cc;
            this.defaultTime = dt; this.defaultXp = dx; this.viewSlotCount = vsc;
        }
    }

    /** v3.0.1: INPUT は全 type 共通で view[0]。 OUTPUT は type.viewSlotCount - 1 (= campfire は 1、 他は 2)。
     *  IDX_FLAME は 3-slot type で 1、 campfire (2-slot) では -1 (= 存在しない) で setSlot 弾きが naturally skip。 */
    public static final int IDX_INPUT  = 0;
    public final int IDX_OUTPUT;
    private final int IDX_FLAME;

    private final Type type;
    private final IngredientSpec[] slots;
    // v2.1.3: xp (float) は個別 boolean 管理、cookingTime (int) は NullableLong。
    private float xp;
    private boolean xpPresent = true;
    private final RecipeDraft.NullableLong cookingTime = new RecipeDraft.NullableLong();

    public CookingDraft(Type type) {
        this.type = type;
        this.IDX_OUTPUT = type.viewSlotCount - 1;
        this.IDX_FLAME  = (type.viewSlotCount == 3) ? 1 : -1;
        this.slots      = new IngredientSpec[type.viewSlotCount];
        this.xp         = type.defaultXp;
        this.cookingTime.set(type.defaultTime);
        for (int i = 0; i < slots.length; i++) slots[i] = IngredientSpec.EMPTY;
    }

    public Type  getType()         { return type; }

    @Override
    public List<NumericField> numericFields() {
        return List.of(
            new NumericField("XP", NumericField.Kind.FLOAT,
                () -> xp,
                v -> { xp = (float) v; xpPresent = true; },
                0, 100_000,
                true,
                () -> xpPresent,
                () -> { xpPresent = false; xp = 0; }),
            cookingTime.toField("Time", NumericField.Kind.INT, 1, 100_000)
        );
    }

    @Override public int slotCount() { return slots.length; }
    @Override public IngredientSpec getSlot(int i) { return slots[i]; }
    @Override public boolean isOutputSlot(int i) { return i == IDX_OUTPUT; }
    @Override public IngredientSpec getOutput() { return slots[IDX_OUTPUT]; }
    @Override public RecipeType<?> recipeType() { return type.jeiType; }

    /**
     * v2.0.12: vanilla 製錬系 input は単一 ingredient (count 概念無し、JSON 非対応)。
     * output は count > 1 valid (KubeJS で extend 可)。
     * IDX_FLAME は RENDER_ONLY なのでどうでもいいが念のため lock。
     */
    @Override
    public int slotMaxCount(int slotIndex) {
        if (slotIndex == IDX_OUTPUT) return Integer.MAX_VALUE;
        return 1;  // input + flame は 1 lock
    }

    @Override
    public void setSlot(int i, IngredientSpec s) {
        if (i == IDX_FLAME) return; // RENDER_ONLY (campfire は IDX_FLAME=-1 で naturally skip)
        if (i < 0 || i >= slots.length) return;
        if (s == null) s = IngredientSpec.EMPTY;
        if (isOutputSlot(i) && s instanceof IngredientSpec.Tag) return;
        slots[i] = s;
    }

    /** AbstractCookingRecipe (smelting/blasting/smoking/campfire 共通) を slots に反映。 */
    @Override
    public boolean loadFromRecipe(IRecipeLayoutDrawable<?> layout) {
        Object recipe = layout.getRecipe();
        if (!(recipe instanceof AbstractCookingRecipe acr)) {
            Credit.LOGGER.info("[CraftPattern] CookingDraft.loadFromRecipe: not AbstractCookingRecipe ({})",
                recipe == null ? "null" : recipe.getClass().getName());
            return false;
        }
        for (int i = 0; i < slots.length; i++) slots[i] = IngredientSpec.EMPTY;
        var ings = acr.getIngredients();
        if (!ings.isEmpty()) {
            ItemStack[] matches = ings.get(0).getItems();
            if (matches.length > 0 && !matches[0].isEmpty()) {
                slots[IDX_INPUT] = new IngredientSpec.Item(matches[0].copy());
            }
        }
        ItemStack out = acr.getResultItem(net.minecraft.core.RegistryAccess.EMPTY);
        if (out != null && !out.isEmpty()) {
            slots[IDX_OUTPUT] = new IngredientSpec.Item(out.copy());
        }
        this.xp = acr.getExperience();
        this.xpPresent = true;
        this.cookingTime.set(acr.getCookingTime());
        return true;
    }

    @Override
    public Recipe<?> toRecipeInstance() {
        Ingredient ing = RecipeDraft.toIngredient(slots[IDX_INPUT]);
        ResourceLocation id = new ResourceLocation(Credit.MODID, "draft/" + type.name().toLowerCase());
        // recipe instance 構築には数値必須。null 化されてたら default 値で代用 (UI 描画用)。
        float xpV  = xpPresent ? xp : 0f;
        int   ctV  = (int) (cookingTime.isPresent() ? cookingTime.get() : type.defaultTime);
        return switch (type) {
            case SMELTING -> new SmeltingRecipe(id, "", type.cookCategory, ing, RecipeDraft.toOutputStack(slots[IDX_OUTPUT]), xpV, ctV);
            case BLASTING -> new BlastingRecipe(id, "", type.cookCategory, ing, RecipeDraft.toOutputStack(slots[IDX_OUTPUT]), xpV, ctV);
            case SMOKING  -> new SmokingRecipe(id,  "", type.cookCategory, ing, RecipeDraft.toOutputStack(slots[IDX_OUTPUT]), xpV, ctV);
            case CAMPFIRE -> new CampfireCookingRecipe(id, "", type.cookCategory, ing, RecipeDraft.toOutputStack(slots[IDX_OUTPUT]), xpV, ctV);
        };
    }

    @Override
    public String relativeOutputPath() {
        return "generated/" + type.name().toLowerCase() + ".js";
    }

    /**
     * v3.0.1: 同 output で smelting / blasting / smoking / campfire が並立し得るので
     * {@code <type>/<input>_<output>(+_<count> if !=1)} 形式で一意化。
     * <p>例: smelting cobblestone -> iron_ingot → {@code smelting/cobblestone_iron_ingot}
     *     blasting netherrack -> 11x netherite_block → {@code blasting/netherrack_netherite_block_11}
     * <p>input または output が解決不能なら default ({@link #outputItemPath()}) に fallback。
     */
    @Override
    public String autoIdPath() {
        String inPath  = RecipeDraft.ingredientIdPath(slots[IDX_INPUT]);
        String outPath = outputItemPath();
        if (inPath == null || outPath == null) return outPath;
        StringBuilder sb = new StringBuilder();
        sb.append(type.name().toLowerCase()).append('/')
          .append(inPath).append('_').append(outPath);
        int outCount = slots[IDX_OUTPUT] == null ? 0 : slots[IDX_OUTPUT].count();
        if (outCount > 1) sb.append('_').append(outCount);
        return sb.toString();
    }

    @Override
    public String emit(String recipeId) {
        String inJs  = RecipeDraft.formatIngredientString(slots[IDX_INPUT]);
        String outJs = RecipeDraft.formatIngredientWithCount(slots[IDX_OUTPUT]);
        if (inJs == null || outJs == null) return null;
        StringBuilder mods = new StringBuilder();
        if (xpPresent)              mods.append(".xp(").append(xp).append(")");
        if (cookingTime.isPresent()) mods.append(".cookingTime(").append(cookingTime.get()).append(")");
        return "    event." + type.kjsMethod + "(\n"
            + "        " + outJs + ",\n"
            + "        " + inJs + "\n"
            + "    )" + mods + "\n"
            + "    .id('" + recipeId + "');\n";
    }
}