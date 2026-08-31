package com.rzmao.shop.api;

import net.minecraft.server.level.ServerPlayer;

import java.util.Objects;
import java.util.UUID;

/** A player account that should receive the reward created by a death penalty. */
public record DeathPenaltyKiller(UUID uuid, String name) {
    public DeathPenaltyKiller {
        Objects.requireNonNull(uuid, "uuid");
        Objects.requireNonNull(name, "name");
        if (name.isBlank()) {
            throw new IllegalArgumentException("击杀者名称不能为空");
        }
    }

    public static DeathPenaltyKiller player(ServerPlayer player) {
        Objects.requireNonNull(player, "player");
        return new DeathPenaltyKiller(player.getUUID(), player.getGameProfile().getName());
    }
}
