package com.rzmao.shop.compat;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;

import java.util.Locale;

/** InoriLoot 插件通过 Bukkit PDC 写入的物品信息，纯 Forge 环境也可以读取。 */
public final class InoriLootItems {
    private InoriLootItems() {}

    public static ResourceLocation id(ItemStack stack) {
        CompoundTag values = values(stack);
        if (values == null || !values.contains("inoriloot:loot-item-id", Tag.TAG_STRING)) return null;
        String id = values.getString("inoriloot:loot-item-id").trim().toLowerCase(Locale.ROOT);
        return id.isEmpty() ? null : ResourceLocation.tryParse("inoriloot:" + id);
    }

    public static boolean isPlaceholder(ItemStack stack) {
        CompoundTag values = values(stack);
        return values != null && (values.getInt("inoriloot:grid-locked") != 0
                || values.getInt("inoriloot:grid-searching") != 0);
    }

    private static CompoundTag values(ItemStack stack) {
        CompoundTag tag = stack.getTag();
        return tag != null && tag.contains("PublicBukkitValues", Tag.TAG_COMPOUND)
                ? tag.getCompound("PublicBukkitValues") : null;
    }
}
