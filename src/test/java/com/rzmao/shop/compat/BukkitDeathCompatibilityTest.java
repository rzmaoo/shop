package com.rzmao.shop.compat;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class BukkitDeathCompatibilityTest {
    @Test
    void readsKillerFromHybridServerBukkitBridge() {
        UUID uuid = UUID.fromString("00000000-0000-0000-0000-000000000021");
        FakeBukkitPlayer killer = new FakeBukkitPlayer(uuid, "PluginKiller");

        var resolved = BukkitDeathCompatibility.findKiller(
                new FakeMinecraftPlayer(new FakeBukkitVictim(killer)));

        assertThat(resolved).hasValueSatisfying(identity -> {
            assertThat(identity.uuid()).isEqualTo(uuid);
            assertThat(identity.name()).isEqualTo("PluginKiller");
        });
    }

    @Test
    void remainsOptionalOnPlainForgeAndWhenPluginReportsNoKiller() {
        assertThat(BukkitDeathCompatibility.findKiller(new Object())).isEmpty();
        assertThat(BukkitDeathCompatibility.findKiller(
                new FakeMinecraftPlayer(new FakeBukkitVictim(null)))).isEmpty();
    }

    public static final class FakeMinecraftPlayer {
        private final FakeBukkitVictim bukkitEntity;

        FakeMinecraftPlayer(FakeBukkitVictim bukkitEntity) {
            this.bukkitEntity = bukkitEntity;
        }

        public FakeBukkitVictim getBukkitEntity() {
            return bukkitEntity;
        }
    }

    public static final class FakeBukkitVictim {
        private final FakeBukkitPlayer killer;

        FakeBukkitVictim(FakeBukkitPlayer killer) {
            this.killer = killer;
        }

        public FakeBukkitPlayer getKiller() {
            return killer;
        }
    }

    public static final class FakeBukkitPlayer {
        private final UUID uuid;
        private final String name;

        FakeBukkitPlayer(UUID uuid, String name) {
            this.uuid = uuid;
            this.name = name;
        }

        public UUID getUniqueId() {
            return uuid;
        }

        public String getName() {
            return name;
        }
    }
}
