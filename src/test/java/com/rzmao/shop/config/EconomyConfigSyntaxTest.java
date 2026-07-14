package com.rzmao.shop.config;

import com.electronwill.nightconfig.core.Config;
import com.electronwill.nightconfig.core.file.CommentedFileConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class EconomyConfigSyntaxTest {
    @TempDir Path tempDir;

    @Test
    void generatedConfigHasRootPriceListAndAtmSection() throws Exception {
        Path path = tempDir.resolve("economy.toml");
        Files.writeString(path, EconomyConfig.DEFAULT_CONFIG);
        try (CommentedFileConfig config = CommentedFileConfig.builder(path).sync().build()) {
            config.load();
            assertThat(((Number) config.get("schemaVersion")).intValue()).isEqualTo(1);
            assertThat((String) config.get("logTimeZone")).isEqualTo("Asia/Shanghai");
            assertThat((List<?>) config.get("prices")).isEmpty();
            assertThat((String) config.get("sounds.sellSuccess")).isEqualTo("minecraft:entity.player.levelup");
            assertThat((String) config.get("sounds.sellFailed")).isEqualTo("minecraft:entity.villager.no");
            assertThat((String) config.get("sounds.depositSuccess")).isEqualTo("minecraft:entity.experience_orb.pickup");
            assertThat((String) config.get("sounds.withdrawSuccess")).isEqualTo("minecraft:entity.item.pickup");
            assertThat(((Number) config.get("sounds.volume")).doubleValue()).isEqualTo(1.0);
            assertThat(((Number) config.get("sounds.pitch")).doubleValue()).isEqualTo(1.0);
            assertThat(((Number) config.get("backup.intervalSeconds")).intValue()).isEqualTo(300);
            assertThat((String) config.get("backup.directory")).isEqualTo("backup");
            assertThat((String) config.get("atm.item")).isEqualTo("minecraft:gold_ingot");
            assertThat((String) config.get("atm.valuePerItem")).isEqualTo("1.00");
        }
    }

    @Test
    void inlinePriceEntriesAreNightConfigTables() throws Exception {
        Path path = tempDir.resolve("prices.toml");
        Files.writeString(path, """
                schemaVersion = 1
                maxBalance = "100.00"
                prices = [ { item = "minecraft:diamond", price = "25.00" } ]
                [atm]
                item = "minecraft:gold_ingot"
                valuePerItem = "1.00"
                """);
        try (CommentedFileConfig config = CommentedFileConfig.builder(path).sync().build()) {
            config.load();
            List<?> prices = config.get("prices");
            assertThat(prices).hasSize(1);
            assertThat(prices.get(0)).isInstanceOf(Config.class);
            assertThat((String) ((Config) prices.get(0)).get("item")).isEqualTo("minecraft:diamond");
        }
    }
}
