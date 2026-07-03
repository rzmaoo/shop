package com.rzmao.shop.menu;

import net.minecraft.ChatFormatting;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import java.util.List;

final class MenuIcons {
    private MenuIcons() {}

    static ItemStack icon(Item item, String name, ChatFormatting color, String... loreLines) {
        ItemStack stack = new ItemStack(item);
        stack.setHoverName(Component.literal(name).withStyle(color));
        if (loreLines.length > 0) {
            CompoundTag display = stack.getOrCreateTagElement("display");
            ListTag lore = new ListTag();
            for (String line : loreLines) {
                lore.add(StringTag.valueOf(Component.Serializer.toJson(Component.literal(line).withStyle(ChatFormatting.GRAY))));
            }
            display.put("Lore", lore);
        }
        return stack;
    }

    static ItemStack filler() {
        return icon(net.minecraft.world.item.Items.GRAY_STAINED_GLASS_PANE, " ", ChatFormatting.GRAY);
    }
}
