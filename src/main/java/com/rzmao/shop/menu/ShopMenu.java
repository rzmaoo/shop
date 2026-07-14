package com.rzmao.shop.menu;

import com.rzmao.shop.EconomyService;
import com.rzmao.shop.money.Money;
import com.rzmao.shop.storage.EconomyDatabase;
import com.rzmao.shop.storage.MenuKind;
import com.rzmao.shop.storage.MenuState;
import com.rzmao.shop.text.ShopText;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.io.IOException;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

public final class ShopMenu extends EconomyMenu {
    public ShopMenu(int id, Inventory inventory, EconomyService service, MenuState initial) {
        super(id, inventory, service, MenuKind.SHOP, initial, stack -> service.config().price(stack).isPresent());
    }

    @Override
    protected boolean isAccepted(ItemStack stack) {
        return service.config().price(stack).isPresent();
    }

    @Override
    protected void handleControl(int slotId, int button, ClickType clickType) {
        if (clickType != ClickType.PICKUP) return;
        if (slotId == 45) {
            takeBackAll();
        } else if (slotId == 49) {
            confirmSale();
        }
    }

    private void confirmSale() {
        MenuState before = snapshot();
        try {
            EconomyDatabase.BalanceChange change = service.sell(player, before);
            applyCommittedState(emptySlots(before));
            player.sendSystemMessage(ShopText.text("shop.message.sell_success", Money.format(change.delta()),
                    Money.format(change.after())).withStyle(ChatFormatting.GREEN));
            playConfiguredSound(service.config().sounds().sellSuccess());
        } catch (SQLException | IOException ex) {
            service.auditRejected(player, "SHOP_SELL", ex.getMessage(), "确认出售失败");
            player.sendSystemMessage(ShopText.text("shop.message.sell_failed", ex.getMessage()).withStyle(ChatFormatting.RED));
            playConfiguredSound(service.config().sounds().sellFailed());
            try {
                applyCommittedState(service.database().loadMenu(player.getUUID(), MenuKind.SHOP));
            } catch (Exception reloadFailure) {
                player.connection.disconnect(ShopText.text("shop.common.contact_admin"));
            }
        } catch (RuntimeException ex) {
            try {
                applyCommittedState(service.database().loadMenu(player.getUUID(), MenuKind.SHOP));
            } catch (Exception reloadFailure) {
                player.connection.disconnect(ShopText.text("shop.common.save_disconnect"));
            }
        }
    }

    @Override
    protected void refreshControls() {
        for (int slot = 45; slot < 54; slot++) setControl(slot, MenuIcons.filler());
        setControl(45, MenuIcons.icon(Items.BARRIER, ShopText.get("shop.button.take_all"), ChatFormatting.YELLOW,
                ShopText.get("shop.button.take_all.line1"), ShopText.get("shop.button.take_all.line2")));
        long estimate = 0;
        String error = null;
        try {
            estimate = service.shopValue(snapshot(), false);
        } catch (SQLException ex) {
            error = ex.getMessage();
        }
        setControl(49, MenuIcons.icon(Items.LIME_STAINED_GLASS_PANE, ShopText.get("shop.button.sell"), ChatFormatting.GREEN,
                ShopText.get("shop.button.sell.line1"), ShopText.get("shop.button.sell.value", Money.format(estimate))));
        try {
            long balance = service.balance(player.getUUID());
            String balanceLine = ShopText.get("shop.info.balance.line", Money.format(balance));
            setControl(53, error == null
                    ? MenuIcons.icon(Items.GOLD_NUGGET, ShopText.get("shop.info.balance"), ChatFormatting.GOLD, balanceLine)
                    : MenuIcons.icon(Items.GOLD_NUGGET, ShopText.get("shop.info.balance"), ChatFormatting.GOLD,
                    balanceLine, ShopText.get("shop.info.value_error")));
        } catch (SQLException ex) {
            setControl(53, MenuIcons.icon(Items.REDSTONE, ShopText.get("shop.common.unavailable"), ChatFormatting.RED,
                    ShopText.get("shop.common.contact_admin")));
        }
    }

    private static MenuState emptySlots(MenuState state) {
        List<ItemStack> slots = new ArrayList<>(state.slots().size());
        for (int i = 0; i < state.slots().size(); i++) slots.add(ItemStack.EMPTY);
        return new MenuState(slots, state.carried());
    }
}
