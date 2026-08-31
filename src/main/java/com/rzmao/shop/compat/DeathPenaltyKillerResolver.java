package com.rzmao.shop.compat;

import com.rzmao.shop.api.DeathPenaltyKiller;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.OwnableEntity;
import net.minecraft.world.entity.TraceableEntity;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;

public final class DeathPenaltyKillerResolver {
    private DeathPenaltyKillerResolver() {
    }

    public static Optional<DeathPenaltyKiller> resolve(ServerPlayer victim, DamageSource source) {
        Optional<DeathPenaltyKiller> pluginKiller = BukkitDeathCompatibility.findKiller(victim)
                .filter(killer -> !killer.uuid().equals(victim.getUUID()));
        if (pluginKiller.isPresent()) return pluginKiller;

        Set<Entity> visited = Collections.newSetFromMap(new IdentityHashMap<>());
        DeathPenaltyKiller killer = resolveCandidate(source::getEntity, victim, visited);
        if (killer == null) killer = resolveCandidate(source::getDirectEntity, victim, visited);
        if (killer == null) killer = resolveCandidate(victim::getKillCredit, victim, visited);
        return Optional.ofNullable(killer);
    }

    private static DeathPenaltyKiller resolveCandidate(Supplier<? extends Entity> supplier,
                                                        ServerPlayer victim, Set<Entity> visited) {
        try {
            return resolveEntity(supplier.get(), victim, visited);
        } catch (RuntimeException | LinkageError ignored) {
            return null;
        }
    }

    private static DeathPenaltyKiller resolveEntity(Entity entity, ServerPlayer victim, Set<Entity> visited) {
        if (entity == null || !visited.add(entity)) return null;
        if (entity instanceof ServerPlayer player) {
            return player.getUUID().equals(victim.getUUID()) ? null : DeathPenaltyKiller.player(player);
        }

        try {
            if (entity instanceof TraceableEntity traceable) {
                DeathPenaltyKiller owner = resolveEntity(traceable.getOwner(), victim, visited);
                if (owner != null) return owner;
            }
        } catch (RuntimeException | LinkageError ignored) {
            // Broken third-party ownership implementations must not interrupt the death flow.
        }

        try {
            if (entity instanceof OwnableEntity ownable) {
                return resolveEntity(ownable.getOwner(), victim, visited);
            }
        } catch (RuntimeException | LinkageError ignored) {
            // Broken third-party ownership implementations must not interrupt the death flow.
        }
        return null;
    }
}
