package com.rzmao.shop.api;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DeathPenaltyKillerTest {
    @Test
    void validatesExternalPlayerIdentity() {
        UUID uuid = UUID.fromString("00000000-0000-0000-0000-000000000020");
        assertThat(new DeathPenaltyKiller(uuid, "PluginKiller"))
                .isEqualTo(new DeathPenaltyKiller(uuid, "PluginKiller"));
        assertThatThrownBy(() -> new DeathPenaltyKiller(uuid, " "))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new DeathPenaltyKiller(null, "PluginKiller"))
                .isInstanceOf(NullPointerException.class);
    }
}
