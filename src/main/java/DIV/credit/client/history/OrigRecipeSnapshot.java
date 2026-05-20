package DIV.credit.client.history;

import DIV.credit.Credit;
import net.minecraft.core.NonNullList;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.AbstractCookingRecipe;
import net.minecraft.world.item.crafting.BlastingRecipe;
import net.minecraft.world.item.crafting.CampfireCookingRecipe;
import net.minecraft.world.item.crafting.CookingBookCategory;
import net.minecraft.world.item.crafting.CraftingBookCategory;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.ShapedRecipe;
import net.minecraft.world.item.crafting.ShapelessRecipe;
import net.minecraft.world.item.crafting.SmeltingRecipe;
import net.minecraft.world.item.crafting.SmokingRecipe;
import net.minecraft.world.item.crafting.StonecutterRecipe;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * v3.13-C: push 時点の origRecipe を簡易 NBT で snapshot 保存。
 *  push 後 (= /reload で event.remove が消した) でも before 表示できるよう、 payload に同梱。
 *
 *  対応 kind: "smelting" / "blasting" / "smoking" / "campfire" / "stonecutting" / "shaped" / "shapeless" / "other"
 *  "other" (= mod recipe 等) は最低限 id だけ保存、 復元は null (= Screen 側 placeholder)。
 */
public record OrigRecipeSnapshot(
    String kind,
    String id,
    List<ItemStack> inputs,
    ItemStack output,
    int width,
    int height,
    int cookingTime,
    float xp
) {

    /** push 時、 現行 RecipeManager から lookup した Recipe<?> を snapshot に。 */
    @Nullable
    public static OrigRecipeSnapshot fromRecipe(@Nullable Recipe<?> r) {
        if (r == null) return null;
        String id = r.getId() != null ? r.getId().toString() : "";
        ItemStack out;
        try { out = r.getResultItem(net.minecraft.core.RegistryAccess.EMPTY).copy(); }
        catch (Exception e) { out = ItemStack.EMPTY; }

        List<ItemStack> ins = new ArrayList<>();
        try {
            for (Ingredient ing : r.getIngredients()) {
                ItemStack[] arr = ing.getItems();
                ins.add(arr.length > 0 ? arr[0].copy() : ItemStack.EMPTY);
            }
        } catch (Exception ignored) {}

        if (r instanceof ShapedRecipe sr) {
            return new OrigRecipeSnapshot("shaped", id, ins, out,
                sr.getWidth(), sr.getHeight(), 0, 0f);
        }
        if (r instanceof ShapelessRecipe) {
            return new OrigRecipeSnapshot("shapeless", id, ins, out, 0, 0, 0, 0f);
        }
        if (r instanceof AbstractCookingRecipe acr) {
            String k = (acr instanceof SmeltingRecipe) ? "smelting"
                : (acr instanceof BlastingRecipe) ? "blasting"
                : (acr instanceof SmokingRecipe) ? "smoking"
                : (acr instanceof CampfireCookingRecipe) ? "campfire"
                : "smelting";
            return new OrigRecipeSnapshot(k, id, ins, out, 0, 0,
                acr.getCookingTime(), acr.getExperience());
        }
        if (r instanceof StonecutterRecipe) {
            return new OrigRecipeSnapshot("stonecutting", id, ins, out, 0, 0, 0, 0f);
        }
        // mod recipe (= GTRecipe 等): 最小情報のみ
        return new OrigRecipeSnapshot("other", id, ins, out, 0, 0, 0, 0f);
    }

    /** snapshot から JEI 描画用 Recipe<?> を再構築。 "other" は null。 */
    @Nullable
    public Recipe<?> toRecipe() {
        ResourceLocation rl;
        try { rl = new ResourceLocation(id); }
        catch (Exception e) { return null; }
        try {
            switch (kind) {
                case "smelting" -> {
                    return new SmeltingRecipe(rl, "", CookingBookCategory.MISC,
                        firstIngredient(), output, xp, cookingTime);
                }
                case "blasting" -> {
                    return new BlastingRecipe(rl, "", CookingBookCategory.MISC,
                        firstIngredient(), output, xp, cookingTime);
                }
                case "smoking" -> {
                    return new SmokingRecipe(rl, "", CookingBookCategory.FOOD,
                        firstIngredient(), output, xp, cookingTime);
                }
                case "campfire" -> {
                    return new CampfireCookingRecipe(rl, "", CookingBookCategory.FOOD,
                        firstIngredient(), output, xp, cookingTime);
                }
                case "stonecutting" -> {
                    return new StonecutterRecipe(rl, "", firstIngredient(), output);
                }
                case "shaped" -> {
                    if (width <= 0 || height <= 0) return null;
                    NonNullList<Ingredient> ings = NonNullList.withSize(width * height, Ingredient.EMPTY);
                    for (int i = 0; i < Math.min(inputs.size(), ings.size()); i++) {
                        ItemStack s = inputs.get(i);
                        ings.set(i, s.isEmpty() ? Ingredient.EMPTY : Ingredient.of(s));
                    }
                    return new ShapedRecipe(rl, "", CraftingBookCategory.MISC, width, height, ings, output);
                }
                case "shapeless" -> {
                    NonNullList<Ingredient> ings = NonNullList.create();
                    for (ItemStack s : inputs) ings.add(s.isEmpty() ? Ingredient.EMPTY : Ingredient.of(s));
                    return new ShapelessRecipe(rl, "", CraftingBookCategory.MISC, output, ings);
                }
                default -> { return null; }
            }
        } catch (Exception e) {
            Credit.LOGGER.warn("[CraftPattern] OrigRecipeSnapshot.toRecipe failed kind={} id={}: {}",
                kind, id, e.getMessage());
            return null;
        }
    }

    private Ingredient firstIngredient() {
        if (inputs.isEmpty() || inputs.get(0).isEmpty()) return Ingredient.EMPTY;
        return Ingredient.of(inputs.get(0));
    }

    public CompoundTag toNbt() {
        CompoundTag t = new CompoundTag();
        t.putString("kind", kind);
        t.putString("id", id);
        t.putInt("w", width);
        t.putInt("h", height);
        t.putInt("ct", cookingTime);
        t.putFloat("xp", xp);
        if (!output.isEmpty()) t.put("out", output.save(new CompoundTag()));
        ListTag list = new ListTag();
        for (ItemStack s : inputs) list.add(s.save(new CompoundTag()));
        t.put("ins", list);
        return t;
    }

    @Nullable
    public static OrigRecipeSnapshot fromNbt(CompoundTag t) {
        try {
            ItemStack out = t.contains("out")
                ? ItemStack.of(t.getCompound("out")) : ItemStack.EMPTY;
            List<ItemStack> ins = new ArrayList<>();
            if (t.contains("ins", Tag.TAG_LIST)) {
                ListTag list = t.getList("ins", Tag.TAG_COMPOUND);
                for (int i = 0; i < list.size(); i++) ins.add(ItemStack.of(list.getCompound(i)));
            }
            return new OrigRecipeSnapshot(
                t.getString("kind"),
                t.getString("id"),
                ins, out,
                t.getInt("w"), t.getInt("h"),
                t.getInt("ct"), t.getFloat("xp"));
        } catch (Exception e) {
            return null;
        }
    }
}
