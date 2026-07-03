package com.rzmao.shop.menu;

import net.minecraft.world.Container;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

final class LockedSlot extends Slot {
    LockedSlot(Container container, int slot, int x, int y) {
        super(container, slot, x, y);
    }

    @Override public boolean mayPlace(ItemStack stack) { return false; }
    @Override public boolean mayPickup(Player player) { return false; }
    @Override public boolean allowModification(Player player) { return false; }
}
