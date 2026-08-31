package com.rzmao.shop.storage;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.level.ServerPlayer;

import java.util.UUID;

public record AuditContext(UUID actorUuid, String actorName, String dimension,
                           Double x, Double y, Double z) {
    public static AuditContext player(ServerPlayer player) {
        return new AuditContext(player.getUUID(), player.getGameProfile().getName(),
                player.serverLevel().dimension().location().toString(),
                player.getX(), player.getY(), player.getZ());
    }

    public static AuditContext death(ServerPlayer victim, UUID killerUuid, String killerName) {
        return new AuditContext(killerUuid, killerUuid == null ? "游戏环境" : killerName,
                victim.serverLevel().dimension().location().toString(),
                victim.getX(), victim.getY(), victim.getZ());
    }

    public static AuditContext source(CommandSourceStack source) {
        if (source.getEntity() instanceof ServerPlayer player) {
            return player(player);
        }
        return new AuditContext(null, source.getTextName(), null, null, null, null);
    }
}
