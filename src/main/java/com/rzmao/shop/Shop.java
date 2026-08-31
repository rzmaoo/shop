package com.rzmao.shop;

import com.rzmao.shop.api.DeathPenaltyKiller;
import com.rzmao.shop.api.event.DeathPenaltyEvent;
import com.rzmao.shop.command.ShopCommands;
import com.rzmao.shop.compat.DeathPenaltyKillerResolver;
import com.rzmao.shop.money.Money;
import com.rzmao.shop.permission.ShopPermissions;
import com.rzmao.shop.storage.EconomyDatabase;
import com.rzmao.shop.text.ShopText;
import net.minecraft.ChatFormatting;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.entity.living.LivingDeathEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.server.permission.events.PermissionGatherEvent;
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
        MinecraftForge.EVENT_BUS.addListener(EventPriority.MONITOR, false,
                LivingDeathEvent.class, this::onPlayerDeath);
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

    private void onPlayerDeath(LivingDeathEvent event) {
        if (event.isCanceled() || !(event.getEntity() instanceof ServerPlayer victim)) return;
        EconomyService running = service;
        if (running == null) return;
        if (!running.config().deathPenalty().enabled()) return;

        DeathPenaltyKiller detectedKiller = DeathPenaltyKillerResolver.resolve(victim, event.getSource())
                .orElse(null);
        DeathPenaltyEvent penaltyEvent = new DeathPenaltyEvent(victim, event.getSource(), detectedKiller);
        try {
            if (MinecraftForge.EVENT_BUS.post(penaltyEvent)) return;
        } catch (RuntimeException | LinkageError ex) {
            LOGGER.error("External death penalty compatibility listener failed for {}",
                    victim.getGameProfile().getName(), ex);
        }

        DeathPenaltyKiller killer = penaltyEvent.getKiller()
                .filter(candidate -> !candidate.uuid().equals(victim.getUUID()))
                .orElse(null);
        ServerPlayer onlineKiller = killer == null ? null
                : victim.server.getPlayerList().getPlayer(killer.uuid());
        final Optional<EconomyDatabase.DeathPenaltyResult> optional;
        try {
            optional = running.applyDeathPenalty(victim, killer, event.getSource().getMsgId());
        } catch (Exception ex) {
            LOGGER.error("Failed to apply death penalty for {}", victim.getGameProfile().getName(), ex);
            victim.sendSystemMessage(ShopText.text("shop.death.penalty_failed").withStyle(ChatFormatting.RED));
            return;
        }
        if (optional.isEmpty() || optional.get().deducted() == 0) return;

        var result = optional.get();
        try {
            if (killer == null) {
                victim.sendSystemMessage(ShopText.text("shop.death.penalty.environment",
                        Money.format(result.deducted()), Money.format(result.victimAfter()))
                        .withStyle(ChatFormatting.RED));
            } else {
                victim.sendSystemMessage(ShopText.text("shop.death.penalty.killer",
                        killer.name(), Money.format(result.deducted()),
                        Money.format(result.rewarded()), Money.format(result.victimAfter()))
                        .withStyle(ChatFormatting.RED));
                if (result.rewarded() > 0 && onlineKiller != null) {
                    onlineKiller.sendSystemMessage(ShopText.text("shop.death.reward",
                            victim.getGameProfile().getName(), Money.format(result.rewarded()),
                            Money.format(result.killerAfter())).withStyle(ChatFormatting.GREEN));
                }
            }
        } catch (RuntimeException ex) {
            LOGGER.error("Death penalty was applied but player notification failed for {}",
                    victim.getGameProfile().getName(), ex);
        }
    }
}
