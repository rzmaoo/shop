package com.rzmao.shop;

import com.rzmao.shop.config.EconomyConfig;
import com.rzmao.shop.menu.AtmMenu;
import com.rzmao.shop.menu.EconomyMenu;
import com.rzmao.shop.menu.ShopMenu;
import com.rzmao.shop.mixin.PlayerListAccessor;
import com.rzmao.shop.money.Money;
import com.rzmao.shop.storage.AuditContext;
import com.rzmao.shop.storage.EconomyDatabase;
import com.rzmao.shop.storage.MenuKind;
import com.rzmao.shop.storage.MenuState;
import com.rzmao.shop.storage.StackCodec;
import com.rzmao.shop.text.ShopText;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.registries.ForgeRegistries;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.OptionalLong;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

public final class EconomyService implements AutoCloseable {
    public static final String INVENTORY_INTENT_MARKER = "shopInventoryIntent";
    public static final String DELIVERY_MARKER = "shopDelivery";
    private static final Logger LOGGER = LoggerFactory.getLogger(EconomyService.class);
    private static final DateTimeFormatter BACKUP_FILE_TIMESTAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd HH-mm-ss");

    private final MinecraftServer server;
    private final EconomyConfig config;
    private final EconomyDatabase database;
    private final ScheduledExecutorService backupExecutor;
    private ScheduledFuture<?> backupTask;

    private EconomyService(MinecraftServer server, EconomyConfig config, EconomyDatabase database) {
        this.server = server;
        this.config = config;
        this.database = database;
        this.backupExecutor = Executors.newSingleThreadScheduledExecutor(task -> {
            Thread thread = new Thread(task, "Shop player data backup");
            thread.setDaemon(true);
            return thread;
        });
    }

    public static EconomyService start(MinecraftServer server) throws IOException, SQLException {
        EconomyConfig config = new EconomyConfig();
        config.loadInitial();
        EconomyDatabase database = EconomyDatabase.open(server);
        EconomyService service = new EconomyService(server, config, database);
        service.rescheduleBackups(config.get());
        return service;
    }

    public EconomyConfig.Snapshot config() {
        return config.get();
    }

    public EconomyDatabase database() {
        return database;
    }

    public void openShop(ServerPlayer player) throws SQLException, IOException {
        preparePlayer(player);
        MenuState state = database.loadMenu(player.getUUID(), MenuKind.SHOP);
        database.audit(player.getUUID(), "SHOP_OPEN", "SUCCESS", null, "打开出售界面", AuditContext.player(player));
        player.openMenu(new SimpleMenuProvider((id, inventory, ignored) -> new ShopMenu(id, inventory, this, state),
                ShopText.text("shop.title.sell")));
    }

    public void openAtm(ServerPlayer player) throws SQLException, IOException {
        preparePlayer(player);
        MenuState state = database.loadMenu(player.getUUID(), MenuKind.ATM);
        database.audit(player.getUUID(), "ATM_OPEN", "SUCCESS", null, "打开 ATM 界面", AuditContext.player(player));
        player.openMenu(new SimpleMenuProvider((id, inventory, ignored) -> new AtmMenu(id, inventory, this, state),
                ShopText.text("shop.title.atm")));
    }

    public void preparePlayer(ServerPlayer player) throws SQLException, IOException {
        database.registerPlayer(player);
        String inventoryMarker = player.getPersistentData().getString(INVENTORY_INTENT_MARKER);
        EconomyDatabase.Recovery recovery = database.recoverInventoryIntent(player.getUUID(), inventoryMarker);
        if (recovery != EconomyDatabase.Recovery.NONE || !inventoryMarker.isEmpty()) {
            player.getPersistentData().remove(INVENTORY_INTENT_MARKER);
            forceSave(player);
            LOGGER.warn("Recovered inventory intent for {}: {}", player.getGameProfile().getName(), recovery);
        }
        recoverDeliveries(player);
    }

    public void persistMenuMutation(ServerPlayer player, MenuKind kind, MenuState before, MenuState after)
            throws RecoverableMutationException, FatalMutationException {
        final String id;
        try {
            id = database.prepareInventoryIntent(player, kind, before, after);
        } catch (SQLException | IOException ex) {
            throw new RecoverableMutationException(ex.getMessage(), ex);
        }
        try {
            player.getPersistentData().putString(INVENTORY_INTENT_MARKER, id);
            forceSave(player);
            database.completeInventoryIntent(id);
            player.getPersistentData().remove(INVENTORY_INTENT_MARKER);
            forceSave(player);
        } catch (Exception ex) {
            failClosed(player, ShopText.get("shop.common.save_disconnect"), ex);
            throw new FatalMutationException(ex.getMessage(), ex);
        }
    }

    public EconomyDatabase.BalanceChange sell(ServerPlayer player, MenuState state) throws SQLException, IOException {
        long total = shopValue(state, true);
        if (total <= 0) throw new SQLException(ShopText.get("shop.message.sell_empty"));
        MenuState after = clearSlotsKeepCarried(state);
        byte[] blob = StackCodec.encode(new MenuState(state.slots(), ItemStack.EMPTY));
        String summary = summarize(state.slots());
        String details = "按配置价格出售；总额=" + Money.format(total);
        return database.commitEscrowExchange(player, MenuKind.SHOP, state, after, total,
                config().maxBalance(), "SHOP_SELL", summary, blob, details, AuditContext.player(player));
    }

    public EconomyDatabase.BalanceChange depositAtm(ServerPlayer player, MenuState state) throws SQLException, IOException {
        int count = 0;
        for (ItemStack stack : state.slots()) {
            if (!stack.isEmpty() && !config().isAtmItem(stack)) {
                throw new SQLException("ATM 托管区存在非货币物品");
            }
            count = Math.addExact(count, stack.getCount());
        }
        if (count == 0) throw new SQLException(ShopText.get("shop.atm.message.no_items"));
        long total = Money.multiply(config().atmValuePerItem(), count);
        MenuState after = clearSlotsKeepCarried(state);
        byte[] blob = StackCodec.encode(new MenuState(state.slots(), ItemStack.EMPTY));
        String summary = config().atmItemId() + " x" + count;
        return database.commitEscrowExchange(player, MenuKind.ATM, state, after, total,
                config().maxBalance(), "ATM_DEPOSIT", summary, blob,
                "实体货币存入；汇率=" + Money.format(config().atmValuePerItem()), AuditContext.player(player));
    }

    public EconomyDatabase.PendingDelivery withdraw(ServerPlayer player, int count) throws SQLException, IOException {
        long cost = Money.multiply(config().atmValuePerItem(), count);
        ItemStack prototype = new ItemStack(config().atmItem(), count);
        if (!canFit(player.getInventory(), prototype)) {
            auditRejected(player, "ATM_WITHDRAW", "背包空间不足", "数量=" + count);
            throw new SQLException(ShopText.get("shop.atm.message.no_space"));
        }
        EconomyDatabase.PendingDelivery delivery = database.beginWithdrawal(player, prototype, count, cost, AuditContext.player(player));
        deliver(player, delivery);
        return delivery;
    }

    public long shopValue(MenuState state, boolean strict) throws SQLException {
        long total = 0;
        for (ItemStack stack : state.slots()) {
            if (stack.isEmpty()) continue;
            OptionalLong price = config().price(stack);
            if (price.isEmpty()) {
                if (strict) throw new SQLException(ShopText.get("shop.message.item_not_sellable"));
                continue;
            }
            try {
                total = Math.addExact(total, Money.multiply(price.getAsLong(), stack.getCount()));
            } catch (ArithmeticException ex) {
                throw new SQLException(ShopText.get("shop.message.sell_too_large"), ex);
            }
        }
        return total;
    }

    public long balance(UUID player) throws SQLException {
        return database.balance(player);
    }

    public EconomyConfig.Snapshot reload() throws IOException {
        EconomyConfig.Snapshot snapshot = config.reload();
        rescheduleBackups(snapshot);
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            if (player.containerMenu instanceof EconomyMenu) {
                player.closeContainer();
                player.sendSystemMessage(ShopText.text("shop.common.reload_closed"));
            }
        }
        return snapshot;
    }

    public void auditRejected(ServerPlayer player, String action, String reason, String details) {
        try {
            database.audit(player.getUUID(), action, "REJECTED", reason, details, AuditContext.player(player));
        } catch (SQLException ex) {
            LOGGER.error("Unable to persist rejected operation audit", ex);
        }
    }

    public void auditRetainedItems(ServerPlayer player, MenuKind kind, MenuState state, String reason) {
        int count = retainedItemCount(state);
        if (count == 0) return;
        try {
            database.auditItems(player.getUUID(), kind.name() + "_RETAIN", "SUCCESS", null,
                    summarize(state), StackCodec.encode(state),
                    reason + "；保留物品数量=" + count, AuditContext.player(player));
        } catch (SQLException | IOException ex) {
            LOGGER.error("Unable to persist retained item audit for {}", player.getGameProfile().getName(), ex);
        }
    }

    private void recoverDeliveries(ServerPlayer player) throws SQLException, IOException {
        String marker = player.getPersistentData().getString(DELIVERY_MARKER);
        List<EconomyDatabase.PendingDelivery> pending = database.pendingDeliveries(player.getUUID());
        if (!marker.isEmpty()) {
            boolean matched = false;
            for (EconomyDatabase.PendingDelivery delivery : pending) {
                if (delivery.id().equals(marker)) {
                    database.markDeliveryComplete(delivery.id(), player.getUUID());
                    matched = true;
                    break;
                }
            }
            player.getPersistentData().remove(DELIVERY_MARKER);
            forceSave(player);
            if (!matched) LOGGER.warn("Cleared stale delivery marker {} for {}", marker, player.getGameProfile().getName());
            pending = database.pendingDeliveries(player.getUUID());
        }
        for (EconomyDatabase.PendingDelivery delivery : pending) {
            if (!canFit(player.getInventory(), delivery.item())) {
                player.sendSystemMessage(ShopText.text("shop.atm.message.pending"));
                return;
            }
            deliver(player, delivery);
        }
    }

    private void deliver(ServerPlayer player, EconomyDatabase.PendingDelivery delivery) throws SQLException {
        ItemStack remaining = delivery.item().copy();
        if (!canFit(player.getInventory(), remaining)) throw new SQLException("背包空间不足");
        while (!remaining.isEmpty()) {
            int amount = Math.min(remaining.getCount(), remaining.getMaxStackSize());
            ItemStack part = remaining.split(amount);
            if (!player.getInventory().add(part) || !part.isEmpty()) {
                throw new SQLException("背包预检与实际发放不一致");
            }
        }
        try {
            player.getPersistentData().putString(DELIVERY_MARKER, delivery.id());
            forceSave(player);
            database.markDeliveryComplete(delivery.id(), player.getUUID());
            player.getPersistentData().remove(DELIVERY_MARKER);
            forceSave(player);
        } catch (Exception ex) {
            failClosed(player, ShopText.get("shop.common.save_disconnect"), ex);
            if (ex instanceof SQLException sql) throw sql;
            throw new SQLException(ex);
        }
    }

    public static boolean canFit(Inventory inventory, ItemStack requested) {
        int remaining = requested.getCount();
        for (ItemStack existing : inventory.items) {
            if (!existing.isEmpty() && ItemStack.isSameItemSameTags(existing, requested)) {
                remaining -= Math.max(0, existing.getMaxStackSize() - existing.getCount());
                if (remaining <= 0) return true;
            }
        }
        for (ItemStack existing : inventory.items) {
            if (existing.isEmpty()) {
                remaining -= requested.getMaxStackSize();
                if (remaining <= 0) return true;
            }
        }
        return remaining <= 0;
    }

    private static MenuState clearSlotsKeepCarried(MenuState state) {
        List<ItemStack> empty = new ArrayList<>(state.slots().size());
        for (int i = 0; i < state.slots().size(); i++) empty.add(ItemStack.EMPTY);
        return new MenuState(empty, state.carried());
    }

    private static String summarize(List<ItemStack> stacks) {
        StringBuilder result = new StringBuilder();
        for (ItemStack stack : stacks) {
            appendSummary(result, stack, null);
            if (result.length() > 1000) return result.substring(0, 1000) + "…";
        }
        return result.toString();
    }

    private static String summarize(MenuState state) {
        StringBuilder result = new StringBuilder();
        for (ItemStack stack : state.slots()) {
            appendSummary(result, stack, null);
            if (result.length() > 1000) return result.substring(0, 1000) + "…";
        }
        appendSummary(result, state.carried(), "鼠标携带");
        return result.length() > 1000 ? result.substring(0, 1000) + "…" : result.toString();
    }

    private static void appendSummary(StringBuilder result, ItemStack stack, String prefix) {
        if (stack.isEmpty()) return;
        if (!result.isEmpty()) result.append(", ");
        if (prefix != null) result.append(prefix).append(": ");
        result.append(ForgeRegistries.ITEMS.getKey(stack.getItem())).append(" x").append(stack.getCount());
    }

    private static int retainedItemCount(MenuState state) {
        int count = state.carried().getCount();
        for (ItemStack stack : state.slots()) {
            count = Math.addExact(count, stack.getCount());
        }
        return count;
    }

    private void forceSave(ServerPlayer player) {
        ((PlayerListAccessor) server.getPlayerList()).shop$getPlayerIo().save(player);
    }

    private static void failClosed(ServerPlayer player, String message, Exception ex) {
        LOGGER.error("Economy operation failed closed for {}", player.getGameProfile().getName(), ex);
        player.connection.disconnect(Component.literal(message));
    }

    private synchronized void rescheduleBackups(EconomyConfig.Snapshot snapshot) {
        if (backupTask != null) {
            backupTask.cancel(false);
            backupTask = null;
        }
        long interval = snapshot.backupIntervalSeconds();
        if (interval == 0) {
            LOGGER.info("Shop player data backup disabled");
            return;
        }
        Path directory = backupDirectory(snapshot);
        backupTask = backupExecutor.scheduleWithFixedDelay(this::runBackupSafely, interval, interval, TimeUnit.SECONDS);
        LOGGER.info("Shop player data backup scheduled every {} seconds to {}", interval, directory);
    }

    private void runBackupSafely() {
        try {
            EconomyConfig.Snapshot snapshot = config.get();
            BackupResult result = backupPlayers(snapshot);
            LOGGER.info("Backed up {} shop player accounts to {}", result.rows(), result.path());
        } catch (Exception ex) {
            LOGGER.error("Scheduled shop player data backup failed", ex);
        }
    }

    private BackupResult backupPlayers(EconomyConfig.Snapshot snapshot) throws IOException, SQLException {
        Path directory = backupDirectory(snapshot);
        Files.createDirectories(directory);
        Path target = nextBackupFile(directory, snapshot.logTimeZone());
        int rows = database.exportPlayersCsv(target);
        return new BackupResult(target, rows);
    }

    private Path backupDirectory(EconomyConfig.Snapshot snapshot) {
        Path configured = Path.of(snapshot.backupDirectory());
        if (!configured.isAbsolute()) {
            configured = database.path().getParent().resolve(configured);
        }
        return configured.normalize().toAbsolutePath();
    }

    private static Path nextBackupFile(Path directory, ZoneId timeZone) {
        String base = BACKUP_FILE_TIMESTAMP.withZone(timeZone).format(Instant.now());
        Path target = directory.resolve(base + ".csv");
        int suffix = 2;
        while (Files.exists(target)) {
            target = directory.resolve(base + "-" + suffix + ".csv");
            suffix++;
        }
        return target;
    }

    private void shutdownBackups() {
        synchronized (this) {
            if (backupTask != null) {
                backupTask.cancel(false);
                backupTask = null;
            }
        }
        backupExecutor.shutdown();
        try {
            if (!backupExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                backupExecutor.shutdownNow();
            }
        } catch (InterruptedException ex) {
            backupExecutor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    @Override
    public void close() throws SQLException {
        shutdownBackups();
        database.close();
    }

    private record BackupResult(Path path, int rows) {}

    public static final class RecoverableMutationException extends Exception {
        public RecoverableMutationException(String message, Throwable cause) { super(message, cause); }
    }

    public static final class FatalMutationException extends Exception {
        public FatalMutationException(String message, Throwable cause) { super(message, cause); }
    }
}
