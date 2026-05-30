package DIV.credit.client.history;

import DIV.credit.Credit;
import net.minecraft.core.NonNullList;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.AbstractCookingRecipe;
import net.minecraft.world.item.crafting.BlastingRecipe;
import net.minecraft.world.item.crafting.CampfireCookingRecipe;
import net.minecraft.world.item.crafting.CookingBookCategory;
import net.minecraft.world.item.crafting.CraftingBookCategory;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeSerializer;
import net.minecraft.world.item.crafting.ShapedRecipe;
import net.minecraft.world.item.crafting.ShapedRecipePattern;
import net.minecraft.world.item.crafting.ShapelessRecipe;
import net.minecraft.world.item.crafting.SmeltingRecipe;
import net.minecraft.world.item.crafting.SmokingRecipe;
import net.minecraft.world.item.crafting.StonecutterRecipe;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * v3.13-C: push 時点の origRecipe を NBT で snapshot 保存。
 *  push 後 (= /reload で event.remove が消した) でも before 表示できるよう、 payload に同梱。
 *
 *  対応 kind: "smelting" / "blasting" / "smoking" / "campfire" / "stonecutting" / "shaped" / "shapeless" / "other"
 * <p>v3.2.1: 全 mod recipe 対応のため {@link #serializerId} + {@link #networkBytes} を追加 (= 任意)。
 *  RecipeSerializer.streamCodec で arbitrary Recipe&lt;?&gt; を bytes 化、 復元時 streamCodec.decode で再構築。
 *  "other" kind でも bytes あれば真の Recipe&lt;?&gt; 復元可能 (= Thermal/IE/Mek/Create/IF 等)。
 *
 * <p>1.21: Recipe.getId() 廃止 (id は RecipeHolder 側) のため、 id は呼出側から渡す。
 *  toNetwork/fromNetwork も廃止 → streamCodec().encode/decode(RegistryFriendlyByteBuf)。
 */
public record OrigRecipeSnapshot(
    String kind,
    String id,
    List<ItemStack> inputs,
    ItemStack output,
    int width,
    int height,
    int cookingTime,
    float xp,
    @Nullable String serializerId,
    @Nullable byte[] networkBytes
) {

    /** client 側 RegistryAccess (= ItemStack/streamCodec の (de)serialize 用)。 */
    private static RegistryAccess registryAccess() {
        var mc = net.minecraft.client.Minecraft.getInstance();
        if (mc != null) {
            if (mc.level != null) return mc.level.registryAccess();
            if (mc.getConnection() != null) return mc.getConnection().registryAccess();
        }
        return RegistryAccess.EMPTY;
    }

    /** push 時、 現行 RecipeManager から lookup した Recipe<?> を snapshot に。 id は RecipeHolder のキー。 */
    @Nullable
    public static OrigRecipeSnapshot fromRecipe(@Nullable Recipe<?> r, @Nullable String idStr) {
        if (r == null) return null;
        String id = idStr != null ? idStr : "";
        ItemStack out;
        try { out = r.getResultItem(registryAccess()).copy(); }
        catch (Exception e) { out = ItemStack.EMPTY; }

        List<ItemStack> ins = new ArrayList<>();
        try {
            for (Ingredient ing : r.getIngredients()) {
                ItemStack[] arr = ing.getItems();
                ins.add(arr.length > 0 ? arr[0].copy() : ItemStack.EMPTY);
            }
        } catch (Exception ignored) {}

        // v3.2.1: 全 recipe について serializer 経由で network bytes を捕捉 (= mod recipe 復元の主経路)。
        String serializerId = null;
        byte[] networkBytes = null;
        try {
            var ser = r.getSerializer();
            if (ser != null) {
                ResourceLocation sid = BuiltInRegistries.RECIPE_SERIALIZER.getKey(ser);
                if (sid != null) {
                    serializerId = sid.toString();
                    @SuppressWarnings({"unchecked", "rawtypes"})
                    var rawSer = (RecipeSerializer) ser;
                    var buf = new RegistryFriendlyByteBuf(io.netty.buffer.Unpooled.buffer(), registryAccess());
                    rawSer.streamCodec().encode(buf, r);
                    networkBytes = new byte[buf.writerIndex()];
                    buf.getBytes(0, networkBytes);
                }
            }
        } catch (Exception e) {
            Credit.LOGGER.debug("[CraftPattern] OrigRecipeSnapshot.fromRecipe: streamCodec encode failed for {}: {}", id, e.toString());
        }

        if (r instanceof ShapedRecipe sr) {
            return new OrigRecipeSnapshot("shaped", id, ins, out,
                sr.getWidth(), sr.getHeight(), 0, 0f, serializerId, networkBytes);
        }
        if (r instanceof ShapelessRecipe) {
            return new OrigRecipeSnapshot("shapeless", id, ins, out, 0, 0, 0, 0f, serializerId, networkBytes);
        }
        if (r instanceof AbstractCookingRecipe acr) {
            String k = (acr instanceof SmeltingRecipe) ? "smelting"
                : (acr instanceof BlastingRecipe) ? "blasting"
                : (acr instanceof SmokingRecipe) ? "smoking"
                : (acr instanceof CampfireCookingRecipe) ? "campfire"
                : "smelting";
            return new OrigRecipeSnapshot(k, id, ins, out, 0, 0,
                acr.getCookingTime(), acr.getExperience(), serializerId, networkBytes);
        }
        if (r instanceof StonecutterRecipe) {
            return new OrigRecipeSnapshot("stonecutting", id, ins, out, 0, 0, 0, 0f, serializerId, networkBytes);
        }
        // mod recipe (= GTRecipe 等): bytes があれば復元時に streamCodec.decode で真 Recipe<?> 復元可能
        return new OrigRecipeSnapshot("other", id, ins, out, 0, 0, 0, 0f, serializerId, networkBytes);
    }

    /** snapshot から JEI 描画用 Recipe<?> を再構築。 vanilla kind は専用構築、 "other" は network bytes から streamCodec.decode。 */
    @Nullable
    public Recipe<?> toRecipe() {
        // id validity guard (= 不正なら復元しない)
        try { ResourceLocation.parse(id); }
        catch (Exception e) { return null; }
        // v3.2.1: bytes + serializerId があれば最優先で streamCodec.decode 復元 (= 全 mod 対応)
        if (serializerId != null && networkBytes != null) {
            try {
                var sid = ResourceLocation.parse(serializerId);
                var ser = BuiltInRegistries.RECIPE_SERIALIZER.get(sid);
                if (ser != null) {
                    var buf = new RegistryFriendlyByteBuf(
                        io.netty.buffer.Unpooled.wrappedBuffer(networkBytes), registryAccess());
                    Recipe<?> rec = ser.streamCodec().decode(buf);
                    if (rec != null) return rec;
                }
            } catch (Exception e) {
                Credit.LOGGER.debug("[CraftPattern] OrigRecipeSnapshot.toRecipe: streamCodec decode failed for {}: {}", id, e.toString());
            }
        }
        try {
            switch (kind) {
                case "smelting" -> {
                    return new SmeltingRecipe("", CookingBookCategory.MISC,
                        firstIngredient(), output, xp, cookingTime);
                }
                case "blasting" -> {
                    return new BlastingRecipe("", CookingBookCategory.MISC,
                        firstIngredient(), output, xp, cookingTime);
                }
                case "smoking" -> {
                    return new SmokingRecipe("", CookingBookCategory.FOOD,
                        firstIngredient(), output, xp, cookingTime);
                }
                case "campfire" -> {
                    return new CampfireCookingRecipe("", CookingBookCategory.FOOD,
                        firstIngredient(), output, xp, cookingTime);
                }
                case "stonecutting" -> {
                    return new StonecutterRecipe("", firstIngredient(), output);
                }
                case "shaped" -> {
                    if (width <= 0 || height <= 0) return null;
                    NonNullList<Ingredient> ings = NonNullList.withSize(width * height, Ingredient.EMPTY);
                    for (int i = 0; i < Math.min(inputs.size(), ings.size()); i++) {
                        ItemStack s = inputs.get(i);
                        ings.set(i, s.isEmpty() ? Ingredient.EMPTY : Ingredient.of(s));
                    }
                    return new ShapedRecipe("", CraftingBookCategory.MISC,
                        new ShapedRecipePattern(width, height, ings, Optional.empty()), output);
                }
                case "shapeless" -> {
                    NonNullList<Ingredient> ings = NonNullList.create();
                    for (ItemStack s : inputs) ings.add(s.isEmpty() ? Ingredient.EMPTY : Ingredient.of(s));
                    return new ShapelessRecipe("", CraftingBookCategory.MISC, output, ings);
                }
                default -> { return null; }
            }
        } catch (Exception e) {
            Credit.LOGGER.warn("[C5015] OrigRecipeSnapshot.toRecipe failed kind={} id={}: {}",
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
        // 1.21: ItemStack.save(CompoundTag) 廃止 → save(HolderLookup.Provider) が Tag を返す
        if (!output.isEmpty()) t.put("out", output.save(registryAccess()));
        ListTag list = new ListTag();
        for (ItemStack s : inputs) {
            if (!s.isEmpty()) list.add(s.save(registryAccess()));
        }
        t.put("ins", list);
        // v3.2.1: bytes + serializerId (optional)
        if (serializerId != null) t.putString("sid", serializerId);
        if (networkBytes != null && networkBytes.length > 0) t.putByteArray("nbb", networkBytes);
        return t;
    }

    @Nullable
    public static OrigRecipeSnapshot fromNbt(CompoundTag t) {
        try {
            // 1.21: ItemStack.of(CompoundTag) 廃止 → ItemStack.parse(Provider, Tag) -> Optional
            ItemStack out = t.contains("out")
                ? ItemStack.parse(registryAccess(), t.getCompound("out")).orElse(ItemStack.EMPTY)
                : ItemStack.EMPTY;
            List<ItemStack> ins = new ArrayList<>();
            if (t.contains("ins", Tag.TAG_LIST)) {
                ListTag list = t.getList("ins", Tag.TAG_COMPOUND);
                for (int i = 0; i < list.size(); i++) {
                    ins.add(ItemStack.parse(registryAccess(), list.getCompound(i)).orElse(ItemStack.EMPTY));
                }
            }
            String sid = t.contains("sid") ? t.getString("sid") : null;
            byte[] nbb = t.contains("nbb") ? t.getByteArray("nbb") : null;
            return new OrigRecipeSnapshot(
                t.getString("kind"),
                t.getString("id"),
                ins, out,
                t.getInt("w"), t.getInt("h"),
                t.getInt("ct"), t.getFloat("xp"),
                sid, nbb);
        } catch (Exception e) {
            return null;
        }
    }
}
