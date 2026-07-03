package com.rzmao.shop.text;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ShopTextTest {
    @Test
    void resolvesChineseOnServerForClientsWithoutTheMod() {
        assertThat(ShopText.get("shop.title.sell")).isEqualTo("出售物品");
        assertThat(ShopText.get("shop.message.sell_success", "10.00", "20.00"))
                .isEqualTo("出售成功！获得 10.00，当前余额 20.00");
    }

    @Test
    void translatesAuditCodesAndDimensions() {
        assertThat(ShopText.auditAction("SHOP_SELL")).isEqualTo("出售物品");
        assertThat(ShopText.auditOutcome("SUCCESS")).isEqualTo("成功");
        assertThat(ShopText.dimension("minecraft:overworld")).isEqualTo("主世界");
    }
}
