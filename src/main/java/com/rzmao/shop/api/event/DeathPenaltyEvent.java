package com.rzmao.shop.api.event;

import com.rzmao.shop.api.DeathPenaltyKiller;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraftforge.eventbus.api.Cancelable;
import net.minecraftforge.eventbus.api.Event;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Fired on the Forge event bus immediately before Shop applies a configured death penalty.
 * Integrations may replace or clear the credited killer. Canceling the event skips the penalty.
 */
@Cancelable
public final class DeathPenaltyEvent extends Event {
    private final ServerPlayer victim;
    private final DamageSource source;
    private DeathPenaltyKiller killer;

    public DeathPenaltyEvent(ServerPlayer victim, DamageSource source, DeathPenaltyKiller killer) {
        this.victim = Objects.requireNonNull(victim, "victim");
        this.source = Objects.requireNonNull(source, "source");
        this.killer = killer;
    }

    public ServerPlayer getVictim() {
        return victim;
    }

    public DamageSource getSource() {
        return source;
    }

    public Optional<DeathPenaltyKiller> getKiller() {
        return Optional.ofNullable(killer);
    }

    public void setKiller(DeathPenaltyKiller killer) {
        this.killer = Objects.requireNonNull(killer, "killer");
    }

    public void setKiller(ServerPlayer killer) {
        setKiller(DeathPenaltyKiller.player(killer));
    }

    public void setKiller(UUID uuid, String name) {
        setKiller(new DeathPenaltyKiller(uuid, name));
    }

    public void clearKiller() {
        killer = null;
    }
}
