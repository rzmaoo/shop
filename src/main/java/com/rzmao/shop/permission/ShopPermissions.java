package com.rzmao.shop.permission;

import com.rzmao.shop.Shop;
import com.rzmao.shop.text.ShopText;
import net.minecraft.network.chat.Component;
import net.minecraftforge.server.permission.events.PermissionGatherEvent;
import net.minecraftforge.server.permission.nodes.PermissionNode;
import net.minecraftforge.server.permission.nodes.PermissionTypes;

public final class ShopPermissions {
    public static final PermissionNode<Boolean> USE_SHOP = node("command.shop", false);
    public static final PermissionNode<Boolean> USE_ATM = node("command.atm", false);
    public static final PermissionNode<Boolean> RELOAD = node("admin.reload", true);
    public static final PermissionNode<Boolean> BALANCE = node("admin.balance", true);
    public static final PermissionNode<Boolean> LOGS = node("admin.logs", true);

    private ShopPermissions() {}

    public static void register(PermissionGatherEvent.Nodes event) {
        event.addNodes(USE_SHOP, USE_ATM, RELOAD, BALANCE, LOGS);
    }

    private static PermissionNode<Boolean> node(String name, boolean admin) {
        PermissionNode<Boolean> node = new PermissionNode<>(Shop.MOD_ID, name, PermissionTypes.BOOLEAN,
                (player, uuid, contexts) -> !admin || (player != null && player.hasPermissions(3)));
        node.setInformation(Component.literal("shop." + name), ShopText.text("shop.permission." + name));
        return node;
    }
}
