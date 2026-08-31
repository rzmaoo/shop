package com.rzmao.shop.text;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ShopTextTest {
    @Test
    void resolvesChineseOnServerForClientsWithoutTheMod() {
        assertThat(ShopText.get("shop.title.sell")).isEqualTo("出售物品");
        assertThat(ShopText.get("shop.message.sell_success", "10.00", "20.00"))
                .isEqualTo("出售成功！获得 10.00，当前余额 20.00");
        assertThat(ShopText.get("shop.death.penalty.environment", "5.00", "45.00"))
                .isEqualTo("你因死亡被扣除 5.00，当前余额：45.00");
        assertThat(ShopText.get("shop.death.penalty.killer", "Killer", "5.00", "5.00", "45.00"))
                .isEqualTo("你被 Killer 击杀，已扣除 5.00，其中 5.00 转给对方；当前余额：45.00");
        assertThat(ShopText.get("shop.death.reward", "Victim", "5.00", "15.00"))
                .isEqualTo("你击杀了 Victim，获得 5.00；当前余额：15.00");
    }

    @Test
    void translatesAuditCodesAndDimensions() {
        assertThat(ShopText.auditAction("SHOP_SELL")).isEqualTo("出售物品");
        assertThat(ShopText.auditAction("DEATH_PENALTY")).isEqualTo("死亡扣款");
        assertThat(ShopText.auditAction("DEATH_REWARD")).isEqualTo("击杀奖励");
        assertThat(ShopText.auditOutcome("SUCCESS")).isEqualTo("成功");
        assertThat(ShopText.dimension("minecraft:overworld")).isEqualTo("主世界");
    }
}
