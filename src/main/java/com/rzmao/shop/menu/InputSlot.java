package com.rzmao.shop.menu;

import net.minecraft.world.Container;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

import java.util.function.Predicate;

final class InputSlot extends Slot {
    private final Predicate<ItemStack> validator;

    InputSlot(Container container, int slot, int x, int y, Predicate<ItemStack> validator) {
        super(container, slot, x, y);
        this.validator = validator;
    }

    @Override
    public boolean mayPlace(ItemStack stack) {
        return validator.test(stack);
    }
}
