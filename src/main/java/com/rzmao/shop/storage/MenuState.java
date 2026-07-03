package com.rzmao.shop.storage;

import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;

public record MenuState(List<ItemStack> slots, ItemStack carried) {
    public MenuState {
        List<ItemStack> copies = new ArrayList<>(slots.size());
        for (ItemStack stack : slots) {
            copies.add(stack.copy());
        }
        slots = List.copyOf(copies);
        carried = carried.copy();
    }

    public static MenuState empty(MenuKind kind) {
        List<ItemStack> stacks = new ArrayList<>(kind.inputSlots());
        for (int i = 0; i < kind.inputSlots(); i++) {
            stacks.add(ItemStack.EMPTY);
        }
        return new MenuState(stacks, ItemStack.EMPTY);
    }
}
