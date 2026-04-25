package DIV.credit.client.fluid;

import net.minecraft.ChatFormatting;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.Fluids;
import net.minecraftforge.fluids.FluidStack;
import org.jetbrains.annotations.Nullable;

/**
 * Fluid 用 ghost cursor の NBT 包装。バケツ item を視覚にして NBT で fluid id + amount を運ぶ。
 * tag の name_tag 包装と同じパターン。
 */
public final class FluidItemHelper {

    public static final String NBT_FLUID_KEY  = "CreditFluidId";
    public static final String NBT_AMOUNT_KEY = "CreditFluidAmount";

    private FluidItemHelper() {}

    public static ItemStack createFluidProxy(FluidStack fs) {
        Item bucketItem = fs.getFluid().getBucket();
        if (bucketItem == null || bucketItem == Items.AIR) bucketItem = Items.BUCKET;
        ItemStack stack = new ItemStack(bucketItem);
        Component fluidName = fs.getDisplayName();
        stack.setHoverName(Component.literal(fluidName.getString() + " (" + fs.getAmount() + " mB)")
            .withStyle(ChatFormatting.AQUA));
        CompoundTag nbt = stack.getOrCreateTag();
        ResourceLocation rl = BuiltInRegistries.FLUID.getKey(fs.getFluid());
        if (rl != null) nbt.putString(NBT_FLUID_KEY, rl.toString());
        nbt.putInt(NBT_AMOUNT_KEY, fs.getAmount());
        return stack;
    }

    @Nullable
    public static FluidStack extractFluid(ItemStack stack) {
        if (stack == null || stack.isEmpty() || !stack.hasTag()) return null;
        CompoundTag tag = stack.getTag();
        if (!tag.contains(NBT_FLUID_KEY)) return null;
        ResourceLocation rl = ResourceLocation.tryParse(tag.getString(NBT_FLUID_KEY));
        if (rl == null) return null;
        Fluid fluid = BuiltInRegistries.FLUID.get(rl);
        if (fluid == Fluids.EMPTY) return null;
        int amount = tag.contains(NBT_AMOUNT_KEY) ? tag.getInt(NBT_AMOUNT_KEY) : 1000;
        return new FluidStack(fluid, amount);
    }
}