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

public final class AtmMenu extends EconomyMenu {
    public AtmMenu(int id, Inventory inventory, EconomyService service, MenuState initial) {
        super(id, inventory, service, MenuKind.ATM, initial, stack -> service.config().isAtmItem(stack));
    }

    @Override
    protected boolean isAccepted(ItemStack stack) {
        return service.config().isAtmItem(stack);
    }

    @Override
    protected void handleControl(int slotId, int button, ClickType clickType) {
        if (clickType != ClickType.PICKUP) return;
        switch (slotId) {
            case 45 -> takeBackAll();
            case 47 -> confirmDeposit();
            case 49 -> withdraw(1);
            case 50 -> withdraw(8);
            case 51 -> withdraw(64);
            default -> { }
        }
    }

    private void confirmDeposit() {
        MenuState before = snapshot();
        try {
            EconomyDatabase.BalanceChange change = service.depositAtm(player, before);
            applyCommittedState(emptySlots(before));
            player.sendSystemMessage(ShopText.text("shop.atm.message.deposit_success", Money.format(change.delta()),
                    Money.format(change.after())).withStyle(ChatFormatting.GREEN));
        } catch (SQLException | IOException ex) {
            service.auditRejected(player, "ATM_DEPOSIT", ex.getMessage(), "确认存入失败");
            player.sendSystemMessage(ShopText.text("shop.atm.message.deposit_failed", ex.getMessage()).withStyle(ChatFormatting.RED));
            try {
                applyCommittedState(service.database().loadMenu(player.getUUID(), MenuKind.ATM));
            } catch (Exception reloadFailure) {
                player.connection.disconnect(ShopText.text("shop.common.contact_admin"));
            }
        } catch (RuntimeException ex) {
            try {
                applyCommittedState(service.database().loadMenu(player.getUUID(), MenuKind.ATM));
            } catch (Exception reloadFailure) {
                player.connection.disconnect(ShopText.text("shop.common.save_disconnect"));
            }
        }
    }

    private void withdraw(int count) {
        try {
            EconomyDatabase.PendingDelivery delivery = service.withdraw(player, count);
            player.sendSystemMessage(ShopText.text("shop.atm.message.withdraw_success", count,
                    Money.format(delivery.cost())).withStyle(ChatFormatting.GREEN));
            refreshControls();
            broadcastChanges();
        } catch (SQLException | IOException ex) {
            service.auditRejected(player, "ATM_WITHDRAW", ex.getMessage(), "数量=" + count);
            player.sendSystemMessage(ShopText.text("shop.atm.message.withdraw_failed", ex.getMessage()).withStyle(ChatFormatting.RED));
        }
    }

    @Override
    protected void refreshControls() {
        for (int slot = 36; slot < 54; slot++) setControl(slot, MenuIcons.filler());
        setControl(45, MenuIcons.icon(Items.BARRIER, ShopText.get("shop.button.take_all"), ChatFormatting.YELLOW,
                ShopText.get("shop.button.take_all.line1"), ShopText.get("shop.button.take_all.line2")));
        int deposited = 0;
        for (ItemStack stack : snapshot().slots()) deposited += stack.getCount();
        setControl(47, MenuIcons.icon(Items.LIME_STAINED_GLASS_PANE, ShopText.get("shop.atm.button.deposit"), ChatFormatting.GREEN,
                ShopText.get("shop.atm.button.deposit.line1", deposited),
                ShopText.get("shop.atm.button.deposit.value", Money.format(Money.multiply(service.config().atmValuePerItem(), deposited)))));
        setControl(49, withdrawalIcon(1));
        setControl(50, withdrawalIcon(8));
        setControl(51, withdrawalIcon(64));
        try {
            setControl(53, MenuIcons.icon(Items.GOLD_NUGGET, ShopText.get("shop.atm.info"), ChatFormatting.GOLD,
                    ShopText.get("shop.info.balance.line", Money.format(service.balance(player.getUUID()))),
                    ShopText.get("shop.atm.info.rate", Money.format(service.config().atmValuePerItem()))));
        } catch (SQLException ex) {
            setControl(53, MenuIcons.icon(Items.REDSTONE, ShopText.get("shop.common.unavailable"), ChatFormatting.RED,
                    ShopText.get("shop.common.contact_admin")));
        }
    }

    private ItemStack withdrawalIcon(int count) {
        return MenuIcons.icon(service.config().atmItem(), ShopText.get("shop.atm.button.withdraw", count), ChatFormatting.AQUA,
                ShopText.get("shop.atm.button.withdraw.cost", Money.format(Money.multiply(service.config().atmValuePerItem(), count))),
                ShopText.get("shop.atm.button.withdraw.safe"));
    }

    private static MenuState emptySlots(MenuState state) {
        List<ItemStack> slots = new ArrayList<>(state.slots().size());
        for (int i = 0; i < state.slots().size(); i++) slots.add(ItemStack.EMPTY);
        return new MenuState(slots, state.carried());
    }
}
