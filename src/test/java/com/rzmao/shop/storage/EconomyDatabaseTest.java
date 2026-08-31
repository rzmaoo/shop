package com.rzmao.shop.storage;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.math.BigDecimal;
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

    @Test
    void deathPenaltyAtomicallyTransfersToPlayerKillerAndAuditsBothAccounts() throws Exception {
        Path file = tempDir.resolve("death-transfer.sqlite3");
        UUID victim = UUID.fromString("00000000-0000-0000-0000-000000000010");
        UUID killer = UUID.fromString("00000000-0000-0000-0000-000000000011");
        try (EconomyDatabase database = EconomyDatabase.open(file)) {
            database.ensurePlayer(victim, "Victim");
            database.ensurePlayer(killer, "Killer");
            setBalance(file, victim, 10_000);
            setBalance(file, killer, 1_000);

            var result = database.applyDeathPenalty(victim, "Victim", killer, "Killer",
                    new BigDecimal("0.10"), 100_000, "player",
                    new AuditContext(killer, "Killer", "minecraft:overworld", 1.0, 2.0, 3.0));

            assertThat(result.deducted()).isEqualTo(1_000);
            assertThat(result.rewarded()).isEqualTo(1_000);
            assertThat(result.removed()).isZero();
            assertThat(database.balance(victim)).isEqualTo(9_000);
            assertThat(database.balance(killer)).isEqualTo(2_000);

            var victimLog = database.queryLogs(victim, null, null, 1, 10).get(0);
            var killerLog = database.queryLogs(killer, null, null, 1, 10).get(0);
            assertThat(victimLog.action()).isEqualTo("DEATH_PENALTY");
            assertThat(victimLog.delta()).isEqualTo(-1_000);
            assertThat(killerLog.action()).isEqualTo("DEATH_REWARD");
            assertThat(killerLog.delta()).isEqualTo(1_000);
            assertThat(killerLog.operationId()).isEqualTo(victimLog.operationId());
        }
    }

    @Test
    void deathPenaltyWithoutKillerRemovesConfiguredShare() throws Exception {
        Path file = tempDir.resolve("death-environment.sqlite3");
        UUID victim = UUID.fromString("00000000-0000-0000-0000-000000000012");
        try (EconomyDatabase database = EconomyDatabase.open(file)) {
            database.ensurePlayer(victim, "Victim");
            setBalance(file, victim, 12_345);

            var result = database.applyDeathPenalty(victim, "Victim", null, null,
                    new BigDecimal("0.25"), 100_000, "fall",
                    new AuditContext(null, "游戏环境", "minecraft:overworld", 1.0, 2.0, 3.0));

            assertThat(result.deducted()).isEqualTo(3_086);
            assertThat(result.rewarded()).isZero();
            assertThat(result.removed()).isEqualTo(3_086);
            assertThat(database.balance(victim)).isEqualTo(9_259);
        }
    }

    @Test
    void deathRewardRespectsMaximumBalanceAndRemovesOverflow() throws Exception {
        Path file = tempDir.resolve("death-cap.sqlite3");
        UUID victim = UUID.fromString("00000000-0000-0000-0000-000000000013");
        UUID killer = UUID.fromString("00000000-0000-0000-0000-000000000014");
        try (EconomyDatabase database = EconomyDatabase.open(file)) {
            database.ensurePlayer(victim, "Victim");
            database.ensurePlayer(killer, "Killer");
            setBalance(file, victim, 10_000);
            setBalance(file, killer, 9_950);

            var result = database.applyDeathPenalty(victim, "Victim", killer, "Killer",
                    new BigDecimal("0.10"), 10_000, "player",
                    new AuditContext(killer, "Killer", "minecraft:overworld", 1.0, 2.0, 3.0));

            assertThat(result.deducted()).isEqualTo(1_000);
            assertThat(result.rewarded()).isEqualTo(50);
            assertThat(result.removed()).isEqualTo(950);
            assertThat(database.balance(victim)).isEqualTo(9_000);
            assertThat(database.balance(killer)).isEqualTo(10_000);
        }
    }

    private static void setBalance(Path file, UUID uuid, long balance) throws SQLException {
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + file);
             var statement = connection.prepareStatement(
                     "UPDATE players SET balance_minor=?,updated_at=? WHERE uuid=?")) {
            statement.setLong(1, balance);
            statement.setLong(2, 0);
            statement.setString(3, uuid.toString());
            assertThat(statement.executeUpdate()).isEqualTo(1);
        }
    }
}
