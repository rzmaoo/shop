package com.rzmao.shop.storage;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EconomyDatabaseTest {
    @TempDir Path tempDir;

    @Test
    void createsDurableSchemaAndBalanceConstraint() throws Exception {
        Path file = tempDir.resolve("shop.sqlite3");
        try (EconomyDatabase ignored = EconomyDatabase.open(file)) {
            assertThat(file).exists();
        }

        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + file);
             Statement statement = connection.createStatement()) {
            try (var result = statement.executeQuery("SELECT version FROM schema_meta")) {
                assertThat(result.next()).isTrue();
                assertThat(result.getInt(1)).isEqualTo(1);
            }
            statement.executeUpdate("INSERT INTO players(uuid,last_name,balance_minor,updated_at) VALUES('u','n',0,0)");
            assertThatThrownBy(() -> statement.executeUpdate("UPDATE players SET balance_minor=-1 WHERE uuid='u'"))
                    .isInstanceOf(SQLException.class);
        }
    }

    @Test
    void enablesWalMode() throws Exception {
        Path file = tempDir.resolve("wal.sqlite3");
        try (EconomyDatabase ignored = EconomyDatabase.open(file);
             var connection = DriverManager.getConnection("jdbc:sqlite:" + file);
             var result = connection.createStatement().executeQuery("PRAGMA journal_mode")) {
            assertThat(result.next()).isTrue();
            assertThat(result.getString(1)).isEqualToIgnoringCase("wal");
        }
    }

    @Test
    void exportsPlayerBalancesAsSingleCsv() throws Exception {
        Path file = tempDir.resolve("backup.sqlite3");
        Path csv = tempDir.resolve("backup").resolve("players.csv");
        UUID uuid = UUID.fromString("00000000-0000-0000-0000-000000000001");
        try (EconomyDatabase database = EconomyDatabase.open(file)) {
            database.ensurePlayer(uuid, "Alice, \"A\"");
            try (var connection = DriverManager.getConnection("jdbc:sqlite:" + file);
                 var statement = connection.prepareStatement("UPDATE players SET balance_minor=?,updated_at=? WHERE uuid=?")) {
                statement.setLong(1, 1234);
                statement.setLong(2, 0);
                statement.setString(3, uuid.toString());
                statement.executeUpdate();
            }

            assertThat(database.exportPlayersCsv(csv)).isEqualTo(1);
        }

        assertThat(Files.readAllLines(csv)).containsExactly(
                "uuid,last_name,balance",
                "00000000-0000-0000-0000-000000000001,\"Alice, \"\"A\"\"\",12.34");
    }

    @Test
    void auditItemsStoresItemSummaryAndHash() throws Exception {
        Path file = tempDir.resolve("audit-items.sqlite3");
        UUID uuid = UUID.fromString("00000000-0000-0000-0000-000000000002");
        try (EconomyDatabase database = EconomyDatabase.open(file)) {
            database.auditItems(uuid, "SHOP_RETAIN", "SUCCESS", null,
                    "minecraft:diamond x3", new byte[] {1, 2, 3},
                    "关闭界面时仍有物品保留在托管区；保留物品数量=3",
                    new AuditContext(null, "test", null, null, null, null));

            var rows = database.queryLogs(uuid, null, null, 1, 10);
            assertThat(rows).hasSize(1);
            assertThat(rows.get(0).action()).isEqualTo("SHOP_RETAIN");
            assertThat(rows.get(0).itemSummary()).isEqualTo("minecraft:diamond x3");
            assertThat(rows.get(0).itemHash()).isNotBlank();
            assertThat(rows.get(0).details()).contains("保留物品数量=3");
        }
    }
}
