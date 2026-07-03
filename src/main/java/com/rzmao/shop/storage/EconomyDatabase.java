package com.rzmao.shop.storage;

import com.rzmao.shop.money.Money;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraftforge.registries.ForgeRegistries;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

public final class EconomyDatabase implements AutoCloseable {
    private final Connection connection;
    private final Path path;

    private EconomyDatabase(Connection connection, Path path) {
        this.connection = connection;
        this.path = path;
    }

    public static EconomyDatabase open(MinecraftServer server) throws SQLException, IOException {
        Path directory = server.getWorldPath(LevelResource.ROOT).resolve("data").resolve("shop");
        return open(directory.resolve("shop.sqlite3"));
    }

    public static EconomyDatabase open(Path requestedPath) throws SQLException, IOException {
        Path path = requestedPath.toAbsolutePath();
        if (path.getParent() == null) throw new IOException("数据库路径缺少父目录");
        Files.createDirectories(path.getParent());
        try {
            Class.forName("org.sqlite.JDBC");
        } catch (ClassNotFoundException ex) {
            throw new SQLException("SQLite JDBC 驱动未被打包", ex);
        }
        Connection connection = DriverManager.getConnection("jdbc:sqlite:" + path);
        EconomyDatabase database = new EconomyDatabase(connection, path);
        try {
            database.configure();
            database.verifyIntegrity();
            database.migrate();
            return database;
        } catch (SQLException ex) {
            connection.close();
            throw ex;
        }
    }

    private void configure() throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA foreign_keys=ON");
            statement.execute("PRAGMA journal_mode=WAL");
            statement.execute("PRAGMA synchronous=FULL");
            statement.execute("PRAGMA busy_timeout=5000");
            statement.execute("PRAGMA wal_autocheckpoint=1000");
        }
    }

    private void verifyIntegrity() throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery("PRAGMA integrity_check")) {
            if (!result.next() || !"ok".equalsIgnoreCase(result.getString(1))) {
                throw new SQLException("SQLite 完整性检查失败，拒绝启用经济系统");
            }
        }
    }

    private void migrate() throws SQLException {
        inTransaction(() -> {
            try (Statement statement = connection.createStatement()) {
                statement.executeUpdate("CREATE TABLE IF NOT EXISTS schema_meta (version INTEGER NOT NULL)");
                try (ResultSet result = statement.executeQuery("SELECT COUNT(*) FROM schema_meta")) {
                    if (result.next() && result.getInt(1) == 0) {
                        statement.executeUpdate("INSERT INTO schema_meta(version) VALUES (1)");
                    }
                }
                try (ResultSet result = statement.executeQuery("SELECT version FROM schema_meta")) {
                    if (!result.next() || result.getInt(1) != 1 || result.next()) {
                        throw new SQLException("不支持或损坏的数据库 schema 版本");
                    }
                }
                statement.executeUpdate("""
                        CREATE TABLE IF NOT EXISTS players (
                          uuid TEXT PRIMARY KEY,
                          last_name TEXT NOT NULL,
                          balance_minor INTEGER NOT NULL DEFAULT 0 CHECK(balance_minor >= 0),
                          updated_at INTEGER NOT NULL
                        )
                        """);
                statement.executeUpdate("""
                        CREATE TABLE IF NOT EXISTS menu_sessions (
                          player_uuid TEXT NOT NULL,
                          kind TEXT NOT NULL CHECK(kind IN ('SHOP','ATM')),
                          state_blob BLOB NOT NULL,
                          updated_at INTEGER NOT NULL,
                          PRIMARY KEY(player_uuid, kind),
                          FOREIGN KEY(player_uuid) REFERENCES players(uuid)
                        )
                        """);
                statement.executeUpdate("""
                        CREATE TABLE IF NOT EXISTS inventory_intents (
                          id TEXT PRIMARY KEY,
                          player_uuid TEXT NOT NULL,
                          kind TEXT NOT NULL,
                          before_blob BLOB NOT NULL,
                          after_blob BLOB NOT NULL,
                          status TEXT NOT NULL CHECK(status IN ('PREPARED','COMMITTED','ROLLED_BACK')),
                          created_at INTEGER NOT NULL,
                          finished_at INTEGER,
                          FOREIGN KEY(player_uuid) REFERENCES players(uuid)
                        )
                        """);
                statement.executeUpdate("CREATE UNIQUE INDEX IF NOT EXISTS one_prepared_intent ON inventory_intents(player_uuid) WHERE status='PREPARED'");
                statement.executeUpdate("""
                        CREATE TABLE IF NOT EXISTS transactions (
                          id TEXT PRIMARY KEY,
                          player_uuid TEXT NOT NULL,
                          type TEXT NOT NULL,
                          status TEXT NOT NULL CHECK(status IN ('COMMITTED','PENDING_DELIVERY','DELIVERED')),
                          amount_minor INTEGER NOT NULL,
                          item_blob BLOB,
                          item_count INTEGER NOT NULL DEFAULT 0,
                          created_at INTEGER NOT NULL,
                          finished_at INTEGER,
                          FOREIGN KEY(player_uuid) REFERENCES players(uuid)
                        )
                        """);
                statement.executeUpdate("CREATE INDEX IF NOT EXISTS pending_delivery_idx ON transactions(player_uuid,status)");
                statement.executeUpdate("""
                        CREATE TABLE IF NOT EXISTS audit_logs (
                          id INTEGER PRIMARY KEY AUTOINCREMENT,
                          operation_id TEXT NOT NULL,
                          occurred_at INTEGER NOT NULL,
                          actor_uuid TEXT,
                          actor_name TEXT NOT NULL,
                          target_uuid TEXT,
                          action TEXT NOT NULL,
                          outcome TEXT NOT NULL,
                          reason TEXT,
                          balance_before INTEGER,
                          balance_after INTEGER,
                          delta_minor INTEGER,
                          item_summary TEXT,
                          item_blob BLOB,
                          item_hash TEXT,
                          details TEXT,
                          dimension TEXT,
                          x REAL,
                          y REAL,
                          z REAL
                        )
                        """);
                statement.executeUpdate("CREATE INDEX IF NOT EXISTS audit_target_time_idx ON audit_logs(target_uuid,occurred_at DESC,id DESC)");
                statement.executeUpdate("CREATE INDEX IF NOT EXISTS audit_actor_time_idx ON audit_logs(actor_uuid,occurred_at DESC,id DESC)");
            }
            return null;
        });
    }

    public synchronized void registerPlayer(ServerPlayer player) throws SQLException {
        upsertPlayer(player.getUUID(), player.getGameProfile().getName());
    }

    public synchronized PlayerIdentity ensurePlayer(UUID uuid, String name) throws SQLException {
        upsertPlayer(uuid, name);
        return new PlayerIdentity(uuid, name, balance(uuid));
    }

    public synchronized long balance(UUID uuid) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("SELECT balance_minor FROM players WHERE uuid=?")) {
            statement.setString(1, uuid.toString());
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? result.getLong(1) : 0L;
            }
        }
    }

    public synchronized MenuState loadMenu(UUID player, MenuKind kind) throws SQLException, IOException {
        byte[] state = loadMenuBytes(player, kind);
        return StackCodec.decode(state, kind.inputSlots());
    }

    public synchronized String prepareInventoryIntent(ServerPlayer player, MenuKind kind,
                                                      MenuState before, MenuState after) throws SQLException, IOException {
        byte[] beforeBytes = StackCodec.encode(before);
        byte[] afterBytes = StackCodec.encode(after);
        String id = UUID.randomUUID().toString();
        return inTransaction(() -> {
            upsertPlayerTx(player.getUUID(), player.getGameProfile().getName());
            byte[] current = loadMenuBytesTx(player.getUUID(), kind);
            if (!Arrays.equals(current, beforeBytes)) {
                throw new SQLException("托管库存状态已变化，拒绝覆盖");
            }
            try (PreparedStatement statement = connection.prepareStatement("INSERT INTO inventory_intents(id,player_uuid,kind,before_blob,after_blob,status,created_at) VALUES(?,?,?,?,?,'PREPARED',?)")) {
                statement.setString(1, id);
                statement.setString(2, player.getUUID().toString());
                statement.setString(3, kind.name());
                statement.setBytes(4, beforeBytes);
                statement.setBytes(5, afterBytes);
                statement.setLong(6, System.currentTimeMillis());
                statement.executeUpdate();
            }
            saveMenuBytesTx(player.getUUID(), kind, afterBytes);
            return id;
        });
    }

    public synchronized void completeInventoryIntent(String id) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("UPDATE inventory_intents SET status='COMMITTED',finished_at=? WHERE id=? AND status='PREPARED'")) {
            statement.setLong(1, System.currentTimeMillis());
            statement.setString(2, id);
            if (statement.executeUpdate() != 1) {
                throw new SQLException("库存意图不存在或已经完成: " + id);
            }
        }
    }

    public synchronized Recovery recoverInventoryIntent(UUID player, String persistedMarker) throws SQLException {
        return inTransaction(() -> {
            try (PreparedStatement statement = connection.prepareStatement("SELECT id,kind,before_blob,after_blob FROM inventory_intents WHERE player_uuid=? AND status='PREPARED' ORDER BY created_at LIMIT 1")) {
                statement.setString(1, player.toString());
                try (ResultSet result = statement.executeQuery()) {
                    if (!result.next()) {
                        return Recovery.NONE;
                    }
                    String id = result.getString("id");
                    MenuKind kind = MenuKind.valueOf(result.getString("kind"));
                    boolean playerSaveCommitted = id.equals(persistedMarker);
                    if (!playerSaveCommitted) {
                        saveMenuBytesTx(player, kind, result.getBytes("before_blob"));
                    }
                    try (PreparedStatement update = connection.prepareStatement("UPDATE inventory_intents SET status=?,finished_at=? WHERE id=?")) {
                        update.setString(1, playerSaveCommitted ? "COMMITTED" : "ROLLED_BACK");
                        update.setLong(2, System.currentTimeMillis());
                        update.setString(3, id);
                        update.executeUpdate();
                    }
                    return playerSaveCommitted ? Recovery.KEPT_AFTER_STATE : Recovery.RESTORED_BEFORE_STATE;
                }
            }
        });
    }

    public synchronized BalanceChange commitEscrowExchange(ServerPlayer player, MenuKind kind,
                                                            MenuState expected, MenuState after,
                                                            long delta, long maxBalance,
                                                            String type, String itemSummary,
                                                            byte[] itemBlob, String details,
                                                            AuditContext context) throws SQLException, IOException {
        if (delta <= 0) {
            throw new IllegalArgumentException("入账金额必须大于 0");
        }
        byte[] expectedBytes = StackCodec.encode(expected);
        byte[] afterBytes = StackCodec.encode(after);
        String operationId = UUID.randomUUID().toString();
        return inTransaction(() -> {
            upsertPlayerTx(player.getUUID(), player.getGameProfile().getName());
            if (!Arrays.equals(loadMenuBytesTx(player.getUUID(), kind), expectedBytes)) {
                throw new SQLException("托管库存与数据库不一致");
            }
            long before = balanceTx(player.getUUID());
            long next;
            try {
                next = Math.addExact(before, delta);
            } catch (ArithmeticException ex) {
                throw new SQLException("余额溢出", ex);
            }
            if (next > maxBalance) {
                throw new SQLException("余额将超过最大值 " + Money.format(maxBalance));
            }
            updateBalanceTx(player.getUUID(), next);
            saveMenuBytesTx(player.getUUID(), kind, afterBytes);
            insertTransactionTx(operationId, player.getUUID(), type, "COMMITTED", delta, itemBlob, 0);
            insertAuditTx(operationId, player.getUUID(), type, "SUCCESS", null, before, next, delta,
                    itemSummary, itemBlob, details, context);
            return new BalanceChange(operationId, before, next, delta);
        });
    }

    public synchronized PendingDelivery beginWithdrawal(ServerPlayer player, ItemStack item, int count,
                                                        long cost, AuditContext context) throws SQLException, IOException {
        if (count <= 0 || cost <= 0) {
            throw new IllegalArgumentException("取款数量和金额必须大于 0");
        }
        ItemStack payload = new ItemStack(item.getItem(), count);
        if (item.hasTag()) {
            payload.setTag(item.getTag().copy());
        }
        byte[] itemBlob = StackCodec.encodeStack(payload);
        String id = UUID.randomUUID().toString();
        return inTransaction(() -> {
            upsertPlayerTx(player.getUUID(), player.getGameProfile().getName());
            long before = balanceTx(player.getUUID());
            if (before < cost) {
                throw new SQLException("余额不足，需要 " + Money.format(cost));
            }
            long after = before - cost;
            updateBalanceTx(player.getUUID(), after);
            insertTransactionTx(id, player.getUUID(), "ATM_WITHDRAW", "PENDING_DELIVERY", -cost, itemBlob, count);
            String summary = ForgeRegistries.ITEMS.getKey(item.getItem()) + " x" + count;
            insertAuditTx(id, player.getUUID(), "ATM_WITHDRAW", "PENDING", null, before, after, -cost,
                    summary, itemBlob, "实体货币待安全发放", context);
            return new PendingDelivery(id, payload, count, cost);
        });
    }

    public synchronized List<PendingDelivery> pendingDeliveries(UUID player) throws SQLException, IOException {
        List<PendingDelivery> pending = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement("SELECT id,item_blob,item_count,amount_minor FROM transactions WHERE player_uuid=? AND status='PENDING_DELIVERY' ORDER BY created_at")) {
            statement.setString(1, player.toString());
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    pending.add(new PendingDelivery(result.getString("id"), StackCodec.decodeStack(result.getBytes("item_blob")),
                            result.getInt("item_count"), Math.abs(result.getLong("amount_minor"))));
                }
            }
        }
        return pending;
    }

    public synchronized void markDeliveryComplete(String id, UUID player) throws SQLException {
        inTransaction(() -> {
            try (PreparedStatement statement = connection.prepareStatement("UPDATE transactions SET status='DELIVERED',finished_at=? WHERE id=? AND player_uuid=? AND status='PENDING_DELIVERY'")) {
                statement.setLong(1, System.currentTimeMillis());
                statement.setString(2, id);
                statement.setString(3, player.toString());
                if (statement.executeUpdate() != 1) {
                    throw new SQLException("待发放事务不存在或已完成: " + id);
                }
            }
            try (PreparedStatement statement = connection.prepareStatement("UPDATE audit_logs SET outcome='SUCCESS',details='实体货币已安全发放' WHERE operation_id=?")) {
                statement.setString(1, id);
                statement.executeUpdate();
            }
            return null;
        });
    }

    public synchronized void audit(UUID target, String action, String outcome, String reason,
                                   String details, AuditContext context) throws SQLException {
        String operation = UUID.randomUUID().toString();
        insertAudit(operation, target, action, outcome, reason, null, null, null,
                null, null, details, context);
    }

    public synchronized void auditItems(UUID target, String action, String outcome, String reason,
                                        String itemSummary, byte[] itemBlob, String details,
                                        AuditContext context) throws SQLException {
        String operation = UUID.randomUUID().toString();
        insertAudit(operation, target, action, outcome, reason, null, null, null,
                itemSummary, itemBlob, details, context);
    }

    public synchronized List<AuditRow> queryLogs(UUID target, Instant fromInclusive,
                                                 Instant toExclusive, int page, int pageSize) throws SQLException {
        long from = fromInclusive == null ? Long.MIN_VALUE : fromInclusive.toEpochMilli();
        long to = toExclusive == null ? Long.MAX_VALUE : toExclusive.toEpochMilli();
        int offset = Math.multiplyExact(page - 1, pageSize);
        List<AuditRow> rows = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT id,operation_id,occurred_at,actor_name,action,outcome,reason,balance_before,balance_after,
                       delta_minor,item_summary,item_hash,details,dimension,x,y,z
                FROM audit_logs WHERE target_uuid=? AND occurred_at>=? AND occurred_at<?
                ORDER BY occurred_at DESC,id DESC LIMIT ? OFFSET ?
                """)) {
            statement.setString(1, target.toString());
            statement.setLong(2, from);
            statement.setLong(3, to);
            statement.setInt(4, pageSize + 1);
            statement.setInt(5, offset);
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    rows.add(new AuditRow(result.getLong("id"), result.getString("operation_id"),
                            Instant.ofEpochMilli(result.getLong("occurred_at")), result.getString("actor_name"),
                            result.getString("action"), result.getString("outcome"), result.getString("reason"),
                            nullableLong(result, "balance_before"), nullableLong(result, "balance_after"),
                            nullableLong(result, "delta_minor"), result.getString("item_summary"),
                            result.getString("item_hash"), result.getString("details"), result.getString("dimension"),
                            nullableDouble(result, "x"), nullableDouble(result, "y"), nullableDouble(result, "z")));
                }
            }
        }
        return rows;
    }

    public synchronized List<PlayerIdentity> findPlayers(String nameOrUuid) throws SQLException {
        List<PlayerIdentity> found = new ArrayList<>();
        UUID uuid = null;
        try {
            uuid = UUID.fromString(nameOrUuid);
        } catch (IllegalArgumentException ignored) {
        }
        String sql = uuid == null
                ? "SELECT uuid,last_name,balance_minor FROM players WHERE lower(last_name)=lower(?)"
                : "SELECT uuid,last_name,balance_minor FROM players WHERE uuid=?";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, uuid == null ? nameOrUuid : uuid.toString());
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    found.add(new PlayerIdentity(UUID.fromString(result.getString("uuid")), result.getString("last_name"), result.getLong("balance_minor")));
                }
            }
        }
        return found;
    }

    public synchronized int exportPlayersCsv(Path target) throws SQLException, IOException {
        Objects.requireNonNull(target, "target");
        Path absoluteTarget = target.toAbsolutePath();
        Path parent = absoluteTarget.getParent();
        if (parent == null) throw new IOException("备份文件路径缺少父目录");
        Files.createDirectories(parent);
        Path temp = Files.createTempFile(parent, ".players-", ".csv.tmp");
        boolean moved = false;
        try {
            int rows = 0;
            try (BufferedWriter writer = Files.newBufferedWriter(temp, StandardCharsets.UTF_8);
                 PreparedStatement statement = connection.prepareStatement("""
                         SELECT uuid,last_name,balance_minor
                         FROM players
                         ORDER BY lower(last_name),uuid
                         """);
                 ResultSet result = statement.executeQuery()) {
                writer.write("uuid,last_name,balance");
                writer.newLine();
                while (result.next()) {
                    long balance = result.getLong("balance_minor");
                    writer.write(csv(result.getString("uuid")));
                    writer.write(',');
                    writer.write(csv(result.getString("last_name")));
                    writer.write(',');
                    writer.write(csv(Money.format(balance)));
                    writer.newLine();
                    rows++;
                }
            }
            try {
                Files.move(temp, absoluteTarget, StandardCopyOption.ATOMIC_MOVE);
            } catch (java.nio.file.AtomicMoveNotSupportedException ex) {
                Files.move(temp, absoluteTarget);
            }
            moved = true;
            return rows;
        } finally {
            if (!moved) {
                Files.deleteIfExists(temp);
            }
        }
    }

    public synchronized int escrowItemCount(UUID player) throws SQLException, IOException {
        int count = 0;
        for (MenuKind kind : MenuKind.values()) {
            MenuState state = loadMenu(player, kind);
            for (ItemStack stack : state.slots()) {
                count = Math.addExact(count, stack.getCount());
            }
            count = Math.addExact(count, state.carried().getCount());
        }
        return count;
    }

    private void insertAudit(String operationId, UUID target, String action, String outcome, String reason,
                             Long before, Long after, Long delta, String itemSummary, byte[] itemBlob,
                             String details, AuditContext context) throws SQLException {
        insertAuditTx(operationId, target, action, outcome, reason, before, after, delta,
                itemSummary, itemBlob, details, context);
    }

    private void insertAuditTx(String operationId, UUID target, String action, String outcome, String reason,
                               Long before, Long after, Long delta, String itemSummary, byte[] itemBlob,
                               String details, AuditContext context) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO audit_logs(operation_id,occurred_at,actor_uuid,actor_name,target_uuid,action,outcome,reason,
                  balance_before,balance_after,delta_minor,item_summary,item_blob,item_hash,details,dimension,x,y,z)
                VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                """)) {
            statement.setString(1, operationId);
            statement.setLong(2, System.currentTimeMillis());
            statement.setString(3, context.actorUuid() == null ? null : context.actorUuid().toString());
            statement.setString(4, context.actorName());
            statement.setString(5, target == null ? null : target.toString());
            statement.setString(6, action);
            statement.setString(7, outcome);
            statement.setString(8, reason);
            setNullableLong(statement, 9, before);
            setNullableLong(statement, 10, after);
            setNullableLong(statement, 11, delta);
            statement.setString(12, itemSummary);
            statement.setBytes(13, itemBlob);
            statement.setString(14, itemBlob == null ? null : sha256(itemBlob));
            statement.setString(15, truncate(details, 8192));
            statement.setString(16, context.dimension());
            setNullableDouble(statement, 17, context.x());
            setNullableDouble(statement, 18, context.y());
            setNullableDouble(statement, 19, context.z());
            statement.executeUpdate();
        }
    }

    private void insertTransactionTx(String id, UUID player, String type, String status, long amount,
                                     byte[] itemBlob, int itemCount) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("INSERT INTO transactions(id,player_uuid,type,status,amount_minor,item_blob,item_count,created_at,finished_at) VALUES(?,?,?,?,?,?,?,?,?)")) {
            statement.setString(1, id);
            statement.setString(2, player.toString());
            statement.setString(3, type);
            statement.setString(4, status);
            statement.setLong(5, amount);
            statement.setBytes(6, itemBlob);
            statement.setInt(7, itemCount);
            statement.setLong(8, System.currentTimeMillis());
            if ("COMMITTED".equals(status)) {
                statement.setLong(9, System.currentTimeMillis());
            } else {
                statement.setObject(9, null);
            }
            statement.executeUpdate();
        }
    }

    private void upsertPlayer(UUID uuid, String name) throws SQLException {
        inTransaction(() -> {
            upsertPlayerTx(uuid, name);
            return null;
        });
    }

    private void upsertPlayerTx(UUID uuid, String name) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO players(uuid,last_name,balance_minor,updated_at) VALUES(?,?,0,?)
                ON CONFLICT(uuid) DO UPDATE SET last_name=excluded.last_name,updated_at=excluded.updated_at
                """)) {
            statement.setString(1, uuid.toString());
            statement.setString(2, name);
            statement.setLong(3, System.currentTimeMillis());
            statement.executeUpdate();
        }
    }

    private long balanceTx(UUID uuid) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("SELECT balance_minor FROM players WHERE uuid=?")) {
            statement.setString(1, uuid.toString());
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) {
                    throw new SQLException("玩家经济账户不存在");
                }
                return result.getLong(1);
            }
        }
    }

    private void updateBalanceTx(UUID uuid, long balance) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("UPDATE players SET balance_minor=?,updated_at=? WHERE uuid=? AND balance_minor>=0")) {
            statement.setLong(1, balance);
            statement.setLong(2, System.currentTimeMillis());
            statement.setString(3, uuid.toString());
            if (statement.executeUpdate() != 1) {
                throw new SQLException("余额并发更新失败");
            }
        }
    }

    private byte[] loadMenuBytes(UUID player, MenuKind kind) throws SQLException {
        byte[] bytes = loadMenuBytesTx(player, kind);
        return bytes;
    }

    private byte[] loadMenuBytesTx(UUID player, MenuKind kind) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("SELECT state_blob FROM menu_sessions WHERE player_uuid=? AND kind=?")) {
            statement.setString(1, player.toString());
            statement.setString(2, kind.name());
            try (ResultSet result = statement.executeQuery()) {
                if (result.next()) {
                    return result.getBytes(1);
                }
                try {
                    return StackCodec.encode(MenuState.empty(kind));
                } catch (IOException ex) {
                    throw new SQLException("无法创建空托管库存", ex);
                }
            }
        }
    }

    private void saveMenuBytesTx(UUID player, MenuKind kind, byte[] state) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO menu_sessions(player_uuid,kind,state_blob,updated_at) VALUES(?,?,?,?)
                ON CONFLICT(player_uuid,kind) DO UPDATE SET state_blob=excluded.state_blob,updated_at=excluded.updated_at
                """)) {
            statement.setString(1, player.toString());
            statement.setString(2, kind.name());
            statement.setBytes(3, state);
            statement.setLong(4, System.currentTimeMillis());
            statement.executeUpdate();
        }
    }

    private <T> T inTransaction(SqlSupplier<T> supplier) throws SQLException {
        boolean old = connection.getAutoCommit();
        connection.setAutoCommit(false);
        try {
            T result = supplier.get();
            connection.commit();
            return result;
        } catch (SQLException | RuntimeException ex) {
            connection.rollback();
            throw ex;
        } finally {
            connection.setAutoCommit(old);
        }
    }

    private static Long nullableLong(ResultSet result, String column) throws SQLException {
        long value = result.getLong(column);
        return result.wasNull() ? null : value;
    }

    private static Double nullableDouble(ResultSet result, String column) throws SQLException {
        double value = result.getDouble(column);
        return result.wasNull() ? null : value;
    }

    private static void setNullableLong(PreparedStatement statement, int index, Long value) throws SQLException {
        if (value == null) statement.setObject(index, null); else statement.setLong(index, value);
    }

    private static void setNullableDouble(PreparedStatement statement, int index, Double value) throws SQLException {
        if (value == null) statement.setObject(index, null); else statement.setDouble(index, value);
    }

    private static String sha256(byte[] data) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException(ex);
        }
    }

    private static String truncate(String value, int max) {
        if (value == null || value.length() <= max) return value;
        return value.substring(0, max) + "…";
    }

    private static String csv(String value) {
        if (value == null) return "";
        boolean quoted = value.indexOf(',') >= 0
                || value.indexOf('"') >= 0
                || value.indexOf('\n') >= 0
                || value.indexOf('\r') >= 0;
        String escaped = value.replace("\"", "\"\"");
        return quoted ? '"' + escaped + '"' : escaped;
    }

    public Path path() {
        return path;
    }

    @Override
    public synchronized void close() throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA wal_checkpoint(TRUNCATE)");
        } finally {
            connection.close();
        }
    }

    @FunctionalInterface
    private interface SqlSupplier<T> {
        T get() throws SQLException;
    }

    public enum Recovery { NONE, KEPT_AFTER_STATE, RESTORED_BEFORE_STATE }

    public record BalanceChange(String operationId, long before, long after, long delta) {}

    public record PendingDelivery(String id, ItemStack item, int count, long cost) {}

    public record PlayerIdentity(UUID uuid, String name, long balance) {}

    public record AuditRow(long id, String operationId, Instant time, String actorName, String action,
                           String outcome, String reason, Long balanceBefore, Long balanceAfter, Long delta,
                           String itemSummary, String itemHash, String details, String dimension,
                           Double x, Double y, Double z) {}
}
