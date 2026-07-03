package com.rzmao.shop;

import com.rzmao.shop.command.ShopCommands;
import com.rzmao.shop.permission.ShopPermissions;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.server.permission.events.PermissionGatherEvent;
import com.rzmao.shop.text.ShopText;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Optional;

@Mod(Shop.MOD_ID)
public final class Shop {
    public static final String MOD_ID = "shop";
    private static final Logger LOGGER = LoggerFactory.getLogger(Shop.class);
    private static volatile EconomyService service;

    public Shop() {
        MinecraftForge.EVENT_BUS.addListener(this::onServerStarted);
        MinecraftForge.EVENT_BUS.addListener(this::onServerStopping);
        MinecraftForge.EVENT_BUS.addListener(this::onCommands);
        MinecraftForge.EVENT_BUS.addListener(this::onPermissionNodes);
        MinecraftForge.EVENT_BUS.addListener(this::onPlayerLogin);
    }

    public static Optional<EconomyService> service() {
        return Optional.ofNullable(service);
    }

    public static synchronized EconomyService startService(net.minecraft.server.MinecraftServer server) throws Exception {
        if (service == null) {
            service = EconomyService.start(server);
        }
        return service;
    }

    private void onServerStarted(ServerStartedEvent event) {
        try {
            service = startService(event.getServer());
            LOGGER.info("Shop economy enabled with database {}", service.database().path());
        } catch (Exception ex) {
            service = null;
            LOGGER.error("Shop economy failed closed and will remain disabled", ex);
        }
    }

    private void onServerStopping(ServerStoppingEvent event) {
        EconomyService running = service;
        service = null;
        if (running != null) {
            try {
                running.close();
            } catch (Exception ex) {
                LOGGER.error("Failed to close shop database cleanly", ex);
            }
        }
    }

    private void onCommands(RegisterCommandsEvent event) {
        ShopCommands.register(event.getDispatcher());
    }

    private void onPermissionNodes(PermissionGatherEvent.Nodes event) {
        ShopPermissions.register(event);
    }

    private void onPlayerLogin(PlayerEvent.PlayerLoggedInEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        EconomyService running = service;
        if (running == null) return;
        try {
            running.preparePlayer(player);
        } catch (Exception ex) {
            LOGGER.error("Player recovery failed for {}", player.getGameProfile().getName(), ex);
            player.connection.disconnect(ShopText.text("shop.common.recovery_disconnect"));
        }
    }
}
