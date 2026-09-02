package com.rzmao.shop.mixin;

import com.rzmao.shop.compat.InoriLootCompatibility;
import com.rzmao.shop.menu.EconomyMenu;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.inventory.AbstractContainerMenu;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.List;

@Pseudo
@Mixin(targets = "inori.inoriloot.forge.grid.GridServerLayouts", remap = false)
public abstract class InoriLootMenuMixin {
    @Inject(method = "buildContexts", at = @At("HEAD"), cancellable = true)
    private static void shop$menuContexts(ServerPlayer player, AbstractContainerMenu menu, @Coerce Object binding,
                                          CallbackInfoReturnable<List<?>> callback) {
        if (menu instanceof EconomyMenu economy) {
            callback.setReturnValue(InoriLootCompatibility.contexts(player, economy));
        }
    }

    @Inject(method = "handle", at = @At("HEAD"), cancellable = true)
    private static void shop$durableGridAction(ServerPlayer player, @Coerce Object packet, CallbackInfo callback) {
        if (player.containerMenu instanceof EconomyMenu menu && !menu.isHandlingGridAction()) {
            callback.cancel();
            menu.handleGridAction(packet);
        }
    }

    @Inject(method = "recover", at = @At("HEAD"), cancellable = true)
    private static void shop$keepEscrowItems(ServerPlayer player, @Coerce Object context,
                                             CallbackInfoReturnable<Boolean> callback) {
        // 原恢复逻辑会把装不下的物品丢到地上；托管物品必须由菜单事务重新排列或回滚。
        if (player.containerMenu instanceof EconomyMenu) callback.setReturnValue(false);
    }
}
