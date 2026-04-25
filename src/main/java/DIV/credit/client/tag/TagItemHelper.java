package DIV.credit.client.tag;

import net.minecraft.ChatFormatting;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.material.Fluid;
import org.jetbrains.annotations.Nullable;

/**
 * 「タグ代理」用の name_tag アイテム生成・判定。
 * NBT に CreditTagId 文字列を入れて、ghost cursor 経由で運ぶ。
 */
public final class TagItemHelper {

    public static final String NBT_KEY              = "CreditTagId";
    public static final String NBT_FLUID_TAG_KEY    = "CreditFluidTagId";
    public static final String NBT_FLUID_TAG_AMOUNT = "CreditFluidTagAmount";

    private TagItemHelper() {}

    public static ItemStack createTagNameTag(ResourceLocation tagId) {
        ItemStack stack = new ItemStack(Items.NAME_TAG);
        stack.setHoverName(Component.literal("#" + tagId).withStyle(ChatFormatting.AQUA));
        stack.getOrCreateTag().putString(NBT_KEY, tagId.toString());
        return stack;
    }

    @Nullable
    public static ResourceLocation extractTagId(ItemStack stack) {
        if (stack == null || stack.isEmpty() || !stack.is(Items.NAME_TAG)) return null;
        if (!stack.hasTag()) return null;
        String s = stack.getTag().getString(NBT_KEY);
        return s.isEmpty() ? null : ResourceLocation.tryParse(s);
    }

    /** 入力文字列が登録済みのアイテムタグか。先頭の `#` は無視。 */
    public static boolean isKnownItemTag(String input) {
        ResourceLocation rl = parseTagId(input);
        return rl != null && isKnownItemTag(rl);
    }

    public static boolean isKnownItemTag(ResourceLocation tagId) {
        if (tagId == null) return false;
        TagKey<Item> tagKey = TagKey.create(Registries.ITEM, tagId);
        return BuiltInRegistries.ITEM.getTag(tagKey).isPresent();
    }

    public static boolean isKnownFluidTag(ResourceLocation tagId) {
        if (tagId == null) return false;
        TagKey<Fluid> tagKey = TagKey.create(Registries.FLUID, tagId);
        return BuiltInRegistries.FLUID.getTag(tagKey).isPresent();
    }

    public static ItemStack createFluidTagNameTag(ResourceLocation tagId, int amount) {
        ItemStack stack = new ItemStack(Items.NAME_TAG);
        stack.setHoverName(Component.literal("#" + tagId + " (" + amount + " mB, fluid)")
            .withStyle(net.minecraft.ChatFormatting.GREEN));
        var nbt = stack.getOrCreateTag();
        nbt.putString(NBT_FLUID_TAG_KEY, tagId.toString());
        nbt.putInt(NBT_FLUID_TAG_AMOUNT, amount);
        return stack;
    }

    @Nullable
    public static ResourceLocation extractFluidTagId(ItemStack stack) {
        if (stack == null || stack.isEmpty() || !stack.is(Items.NAME_TAG)) return null;
        if (!stack.hasTag()) return null;
        String s = stack.getTag().getString(NBT_FLUID_TAG_KEY);
        return s.isEmpty() ? null : ResourceLocation.tryParse(s);
    }

    public static int extractFluidTagAmount(ItemStack stack, int defaultAmount) {
        if (stack == null || stack.isEmpty() || !stack.hasTag()) return defaultAmount;
        return stack.getTag().contains(NBT_FLUID_TAG_AMOUNT)
            ? stack.getTag().getInt(NBT_FLUID_TAG_AMOUNT)
            : defaultAmount;
    }

    @Nullable
    public static ResourceLocation parseTagId(String input) {
        if (input == null) return null;
        String s = input.trim();
        if (s.isEmpty()) return null;
        if (s.startsWith("#")) s = s.substring(1);
        return ResourceLocation.tryParse(s);
    }
}