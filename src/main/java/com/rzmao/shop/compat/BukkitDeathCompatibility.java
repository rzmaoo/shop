package com.rzmao.shop.compat;

import com.rzmao.shop.api.DeathPenaltyKiller;

import java.lang.reflect.Method;
import java.util.Optional;
import java.util.UUID;

/** Optional reflection bridge for hybrid Forge/Bukkit servers. */
final class BukkitDeathCompatibility {
    private BukkitDeathCompatibility() {
    }

    static Optional<DeathPenaltyKiller> findKiller(Object minecraftPlayer) {
        try {
            Object bukkitVictim = invokeNoArgs(minecraftPlayer, "getBukkitEntity");
            if (bukkitVictim == null) return Optional.empty();
            Object bukkitKiller = invokeNoArgs(bukkitVictim, "getKiller");
            if (bukkitKiller == null) return Optional.empty();

            Object uuid = invokeNoArgs(bukkitKiller, "getUniqueId");
            Object name = invokeNoArgs(bukkitKiller, "getName");
            if (uuid instanceof UUID playerUuid && name instanceof String playerName && !playerName.isBlank()) {
                return Optional.of(new DeathPenaltyKiller(playerUuid, playerName));
            }
        } catch (ReflectiveOperationException | SecurityException | LinkageError | ClassCastException ignored) {
            // A normal Forge server has no Bukkit methods. Compatibility is intentionally optional.
        }
        return Optional.empty();
    }

    private static Object invokeNoArgs(Object target, String methodName) throws ReflectiveOperationException {
        Method method = target.getClass().getMethod(methodName);
        return method.invoke(target);
    }
}
