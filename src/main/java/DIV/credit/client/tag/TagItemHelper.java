package DIV.credit.client.tag;

import DIV.credit.client.draft.IngredientSpec;
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
import net.minecraftforge.fluids.FluidStack;
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

    /** Block tag の存在判定 (forge:mineable/wrench、minecraft:mineable/pickaxe 等)。 */
    public static boolean isKnownBlockTag(ResourceLocation tagId) {
        if (tagId == null) return false;
        net.minecraft.tags.TagKey<net.minecraft.world.level.block.Block> tagKey =
            net.minecraft.tags.TagKey.create(Registries.BLOCK, tagId);
        var manager = net.minecraftforge.registries.ForgeRegistries.BLOCKS.tags();
        return manager != null && !manager.getTag(tagKey).isEmpty();
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

    /** タグ表示の cycle 周期 (tick)。1秒 = 20 ticks。 */
    private static final long CYCLE_TICKS = 20L;

    /**
     * Tag に含まれる ItemStack を tickCount に応じて 1秒ごとに cycle 取得。
     * Item tag → Block tag (block の item form) の順にフォールバック。
     * Forge tag manager 経由で vanilla + forge 両 tag 系を取得。
     */
    @Nullable
    public static ItemStack cycledItemFromTag(ResourceLocation tagId, long tickCount) {
        if (tagId == null) return null;
        // 1. Item tag
        TagKey<Item> itemTagKey = TagKey.create(Registries.ITEM, tagId);
        var itemManager = net.minecraftforge.registries.ForgeRegistries.ITEMS.tags();
        if (itemManager != null) {
            var tag = itemManager.getTag(itemTagKey);
            if (!tag.isEmpty()) {
                var list = tag.stream().toList();
                if (!list.isEmpty()) {
                    int idx = (int) Math.floorMod(tickCount / CYCLE_TICKS, list.size());
                    return new ItemStack(list.get(idx));
                }
            }
        }
        // 2. Block tag → block を item form に変換
        net.minecraft.tags.TagKey<net.minecraft.world.level.block.Block> blockTagKey =
            net.minecraft.tags.TagKey.create(Registries.BLOCK, tagId);
        var blockManager = net.minecraftforge.registries.ForgeRegistries.BLOCKS.tags();
        if (blockManager != null) {
            var tag = blockManager.getTag(blockTagKey);
            if (!tag.isEmpty()) {
                var list = tag.stream()
                    .map(net.minecraft.world.level.block.Block::asItem)
                    .filter(it -> it != Items.AIR)
                    .toList();
                if (!list.isEmpty()) {
                    int idx = (int) Math.floorMod(tickCount / CYCLE_TICKS, list.size());
                    return new ItemStack(list.get(idx));
                }
            }
        }
        return null;
    }

    /**
     * Fluid tag に含まれる FluidStack を cycle 取得。Forge tag manager 経由。
     */
    @Nullable
    public static FluidStack cycledFluidFromTag(ResourceLocation tagId, int amount, long tickCount) {
        if (tagId == null) return null;
        TagKey<Fluid> tagKey = TagKey.create(Registries.FLUID, tagId);
        var manager = net.minecraftforge.registries.ForgeRegistries.FLUIDS.tags();
        if (manager == null) return null;
        var tag = manager.getTag(tagKey);
        if (tag.isEmpty()) return null;
        var list = tag.stream().toList();
        if (list.isEmpty()) return null;
        int idx = (int) Math.floorMod(tickCount / CYCLE_TICKS, list.size());
        return new FluidStack(list.get(idx), amount);
    }

    /**
     * name_tag ItemStack から IngredientSpec.Tag / FluidTag を復元。
     * Item tag NBT が優先。fluid tag NBT があれば FluidTag。どちらも無ければ EMPTY。
     */
    public static IngredientSpec specFromNameTag(ItemStack stack) {
        ResourceLocation itemTagId = extractTagId(stack);
        if (itemTagId != null) return IngredientSpec.ofTag(itemTagId);
        ResourceLocation fluidTagId = extractFluidTagId(stack);
        if (fluidTagId != null) {
            int amount = extractFluidTagAmount(stack, 1000);
            return IngredientSpec.ofFluidTag(fluidTagId, amount);
        }
        return IngredientSpec.EMPTY;
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