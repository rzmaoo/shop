package com.rzmao.shop.test;

import com.mojang.authlib.GameProfile;
import com.rzmao.shop.EconomyService;
import com.rzmao.shop.Shop;
import com.rzmao.shop.compat.InoriLootCompatibility;
import com.rzmao.shop.compat.InoriLootItems;
import com.rzmao.shop.menu.EconomyMenu;
import com.rzmao.shop.storage.MenuKind;
import com.rzmao.shop.storage.MenuState;
import com.rzmao.shop.storage.StackCodec;
import io.netty.buffer.Unpooled;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.Connection;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.PacketSendListener;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.fml.common.Mod;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** 在独立测试世界中执行，验证生产 JAR、Mixin、玩家存档和 SQLite 的共同行为。 */
@Mod("shop_compat_tests")
public final class InoriLootIntegration {
    private final List<String> passed = new ArrayList<>();
    private MinecraftServer server;
    private EconomyService service;
    private long sequence;

    public InoriLootIntegration() {
        MinecraftForge.EVENT_BUS.addListener(EventPriority.LOWEST, this::started);
    }

    private void started(ServerStartedEvent event) {
        server = event.getServer();
        server.execute(() -> {
            String failure = null;
            try {
                service = Shop.startService(server);
                itemData();
                if (InoriLootCompatibility.enabled()) {
                    pluginIdentity();
                    gridTransactions();
                    dragAndHotbar();
                    spaceChecks();
                    atmTransactions();
                    databaseRollback();
                    legacyEscrow();
                } else {
                    vanillaTransactions();
                }
            } catch (Throwable ex) {
                failure = ex.toString();
                ex.printStackTrace();
            } finally {
                try {
                    String report = "InoriLoot loaded: " + InoriLootCompatibility.enabled() + "\n"
                            + String.join("\n", passed) + "\n"
                            + (failure == null ? "ALL PASSED" : "FAILED: " + failure) + "\n";
                    Files.writeString(Path.of("compat-test-results.txt"), report);
                    System.out.println(report);
                } catch (Exception ex) {
                    ex.printStackTrace();
                }
                server.halt(false);
            }
        });
    }

    private void itemData() throws Exception {
        ItemStack relic = relic(3, 2, 3);
        relic.getOrCreateTag().putInt("CustomModelData", 1234);
        relic.setHoverName(Component.literal("多格战利品"));
        equal("inoriloot:relic", InoriLootItems.id(relic).toString(), "plugin identity");
        equal(1000L, service.config().price(relic).orElseThrow(), "plugin-specific price");
        values(relic).putInt("inoriloot:grid-rotated", 1);
        equal(1000L, service.config().price(relic).orElseThrow(), "price after rotation");
        check(ItemStack.matches(relic, StackCodec.decodeStack(StackCodec.encodeStack(relic))), "full item NBT round trip");
        ItemStack plain = new ItemStack(Items.PAPER);
        equal(100L, service.config().price(plain).orElseThrow(), "material fallback price");
        values(relic).putInt("inoriloot:grid-locked", 1);
        check(service.config().price(relic).isEmpty(), "locked placeholder cannot be sold");
        ItemStack currency = new ItemStack(Items.GOLD_INGOT);
        values(currency).putInt("inoriloot:grid-searching", 1);
        check(!service.config().isAtmItem(currency), "search placeholder cannot be deposited");
        pass("NBT, plugin ID pricing, rotation and placeholder rejection");
    }

    private void vanillaTransactions() throws Exception {
        ServerPlayer player = player();
        player.getInventory().setItem(9, new ItemStack(Items.DIAMOND, 2));
        service.openShop(player);
        EconomyMenu menu = menu(player);
        menu.clicked(54, 0, ClickType.QUICK_MOVE, player);
        equal(2, menu.snapshot().slots().get(0).getCount(), "vanilla shift-click");
        saved(player, MenuKind.SHOP);
        control(player, 49);
        equal(5000L, service.balance(player.getUUID()), "vanilla sale");
        control(player, 49);
        equal(5000L, service.balance(player.getUUID()), "vanilla repeated sale");
        pass("Optional integration: normal shop works without InoriLoot");
    }

    private void pluginIdentity() throws Exception {
        Class<?> bukkit = Class.forName("org.bukkit.Bukkit");
        Object manager = bukkit.getMethod("getPluginManager").invoke(null);
        Object plugin = manager.getClass().getMethod("getPlugin", String.class).invoke(manager, "InoriLoot");
        check(plugin != null && (Boolean) plugin.getClass().getMethod("isEnabled").invoke(plugin), "supplied Bukkit plugin enabled");
        Object craftServer = bukkit.getMethod("getServer").invoke(null);
        Class<?> craftStack = Class.forName(craftServer.getClass().getPackageName() + ".inventory.CraftItemStack");
        Object bukkitStack = craftStack.getMethod("asBukkitCopy", ItemStack.class).invoke(null, relic(1, 2, 3));
        Class<?> identity = Class.forName("inori.inoriloot.game.loot.LootIdentityService", true, plugin.getClass().getClassLoader());
        Method getId = identity.getMethod("getLootId", Class.forName("org.bukkit.inventory.ItemStack"));
        equal("relic", getId.invoke(identity.getField("INSTANCE").get(null), bukkitStack), "actual plugin reads identical identity");
        pass("Supplied Bukkit plugin startup and identity API interoperability");
    }

    private void gridTransactions() throws Exception {
        ServerPlayer player = player();
        player.getInventory().setItem(9, relic(3, 2, 3));
        service.openShop(player);
        assertContexts(player, 5);
        grid(player, "QUICK_MOVE", "PLAYER", 0, 0);
        equal(3, menu(player).snapshot().slots().get(0).getCount(), "grid shift-click into shop");
        check(player.getInventory().getItem(9).isEmpty(), "source removed once");
        saved(player, MenuKind.SHOP);
        player.closeContainer();
        service.openShop(player);
        equal(3, menu(player).snapshot().slots().get(0).getCount(), "escrow survives reopen");
        grid(player, "PICKUP", "LOOT", 19, 0);
        equal(3, menu(player).getCarried().getCount(), "follower click resolves anchor");
        saved(player, MenuKind.SHOP);
        grid(player, "ROTATE_CARRIED", "LOOT", 0, 0);
        equal(1, values(menu(player).getCarried()).getInt("inoriloot:grid-rotated"), "rotation retained");
        grid(player, "PICKUP", "LOOT", 7, 0);
        equal(3, menu(player).getCarried().getCount(), "right edge rejects oversized footprint");
        grid(player, "PICKUP", "LOOT", 0, 0);
        grid(player, "PICKUP", "LOOT", 10, 1);
        equal(2, menu(player).getCarried().getCount(), "right click splits stack");
        grid(player, "PICKUP", "LOOT", 3, 1);
        equal(1, menu(player).getCarried().getCount(), "right click places one");
        grid(player, "PICKUP_ALL", "LOOT", 0, 0);
        equal(3, menu(player).getCarried().getCount(), "double click collects only real stacks");
        player.closeContainer();
        service.openShop(player);
        equal(3, menu(player).getCarried().getCount(), "carried item survives reopen");
        grid(player, "PICKUP", "LOOT", 0, 0);
        for (String action : List.of("THROW", "CLONE", "CREATIVE_SET")) {
            grid(player, action, "LOOT", 0, 0);
            equal(3, menu(player).snapshot().slots().get(0).getCount(), "blocked action " + action);
        }
        grid(player, "THROW", "PLAYER", -1, 0);
        saved(player, MenuKind.SHOP);
        control(player, 49);
        equal(3000L, service.balance(player.getUUID()), "sale counts items, not occupied cells");
        control(player, 49);
        equal(3000L, service.balance(player.getUUID()), "no repeated sale");
        saved(player, MenuKind.SHOP);
        pass("Shift-click, followers, rotation, boundaries, split, collect, reopen, sale and prohibited actions");
    }

    private void dragAndHotbar() throws Exception {
        ServerPlayer player = player();
        player.getInventory().setItem(9, relic(4, 2, 2));
        service.openShop(player);
        grid(player, "PICKUP", "PLAYER", 0, 0);
        grid(player, "QUICK_CRAFT", "LOOT", 0, 0, -1, new int[]{0, 2});
        equal(2, menu(player).snapshot().slots().get(0).getCount(), "drag first anchor");
        equal(2, menu(player).snapshot().slots().get(2).getCount(), "drag second anchor");
        check(menu(player).getCarried().isEmpty(), "drag conserves count");
        grid(player, "ROTATE_SLOT", "LOOT", 1, 0);
        saved(player, MenuKind.SHOP);
        grid(player, "QUICK_MOVE", "LOOT", 0, 0);
        equal(2, player.getInventory().getItem(9).getCount(), "grid shift-click back");
        player.getInventory().setItem(0, new ItemStack(Items.DIAMOND));
        grid(player, "SWAP_HOTBAR", "LOOT", 2, 0, 0, new int[0]);
        check(player.getInventory().getItem(0).is(Items.DIAMOND), "two-row item cannot be swapped into last row");
        equal(2, menu(player).snapshot().slots().get(2).getCount(), "invalid hotbar swap preserves escrow");
        control(player, 45);
        check(menu(player).snapshot().slots().stream().allMatch(ItemStack::isEmpty), "take back clears only delivered anchors");
        saved(player, MenuKind.SHOP);
        pass("Drag, slot rotation, return, hotbar footprint checks and take-all");
    }

    private void spaceChecks() throws Exception {
        ServerPlayer player = player();
        Inventory inventory = player.getInventory();
        inventory.setItem(9, relic(1, 9, 4));
        check(!EconomyService.canFit(inventory, new ItemStack(Items.GOLD_INGOT)), "followers are not empty capacity");
        inventory.clearContent();
        for (int cell = 0; cell < 36; cell++) {
            if ((cell / 9 + cell % 9) % 2 == 0) inventory.setItem(physical(cell), new ItemStack(Items.DIAMOND, 64));
        }
        check(!EconomyService.canFit(inventory, relic(1, 2, 2)), "fragmented space has no 2x2 rectangle");
        inventory.clearContent();
        inventory.setItem(9, relic(63, 2, 2));
        for (int cell = 0; cell < 36; cell++) {
            if (cell != 0 && cell != 1 && cell != 9 && cell != 10) inventory.setItem(physical(cell), new ItemStack(Items.DIAMOND, 64));
        }
        check(!EconomyService.canFit(inventory, relic(2, 2, 2)), "partial merge is not full capacity");
        equal(63, inventory.getItem(9).getCount(), "preflight does not mutate inventory");
        ItemStack partial = relic(2, 2, 2);
        EconomyService.addToInventory(inventory, partial);
        equal(64, inventory.getItem(9).getCount(), "partial insert adds one");
        equal(1, partial.getCount(), "partial insert returns remainder");

        ServerPlayer escrowPlayer = player();
        escrowPlayer.getInventory().setItem(9, relic(2, 2, 2));
        service.openShop(escrowPlayer);
        grid(escrowPlayer, "QUICK_MOVE", "PLAYER", 0, 0);
        escrowPlayer.getInventory().setItem(9, relic(1, 9, 4));
        control(escrowPlayer, 45);
        equal(2, menu(escrowPlayer).snapshot().slots().get(0).getCount(), "full inventory leaves escrow untouched");
        equal(1, escrowPlayer.getInventory().getItem(9).getCount(), "take-all does not duplicate inventory");
        saved(escrowPlayer, MenuKind.SHOP);
        pass("Full grids, fragmented grids, partial merges and full-inventory escrow retention");
    }

    private void atmTransactions() throws Exception {
        ServerPlayer player = player();
        ItemStack currency = new ItemStack(Items.GOLD_INGOT, 5);
        values(currency).putInt("inoriloot:grid-width", 2);
        values(currency).putInt("inoriloot:grid-height", 2);
        player.getInventory().setItem(9, currency);
        service.openAtm(player);
        assertContexts(player, 4);
        grid(player, "QUICK_MOVE", "PLAYER", 0, 0);
        equal(5, menu(player).snapshot().slots().get(0).getCount(), "grid ATM insertion");
        saved(player, MenuKind.ATM);
        control(player, 47);
        equal(500L, service.balance(player.getUUID()), "grid ATM deposit");
        player.getInventory().setItem(9, relic(1, 9, 4));
        try {
            service.withdraw(player, 1);
            throw new AssertionError("ATM withdrawal accepted a full grid");
        } catch (SQLException expected) {
            equal(500L, service.balance(player.getUUID()), "failed withdrawal does not charge");
            check(service.database().pendingDeliveries(player.getUUID()).isEmpty(), "no false pending delivery");
        }
        player.getInventory().clearContent();
        service.withdraw(player, 1);
        equal(400L, service.balance(player.getUUID()), "successful withdrawal charges once");
        equal(1, player.getInventory().getItem(9).getCount(), "withdrawal reaches grid anchor");
        check(service.database().pendingDeliveries(player.getUUID()).isEmpty(), "withdrawal completed");
        pass("ATM grid layout, deposit, no-space rejection and successful delivery");
    }

    private void databaseRollback() throws Exception {
        ServerPlayer player = player();
        player.getInventory().setItem(9, relic(3, 2, 2));
        service.openShop(player);
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + service.database().path());
             var statement = connection.createStatement()) {
            statement.executeUpdate("CREATE TRIGGER test_reject_intent BEFORE INSERT ON inventory_intents BEGIN SELECT RAISE(FAIL, 'test rollback'); END");
            try {
                grid(player, "QUICK_MOVE", "PLAYER", 0, 0);
                equal(3, player.getInventory().getItem(9).getCount(), "database failure restores original inventory");
                check(menu(player).snapshot().slots().stream().allMatch(ItemStack::isEmpty), "database failure clears tentative escrow");
                saved(player, MenuKind.SHOP);
            } finally {
                statement.executeUpdate("DROP TRIGGER test_reject_intent");
            }
        }
        grid(player, "QUICK_MOVE", "PLAYER", 0, 0);
        equal(3, menu(player).snapshot().slots().get(0).getCount(), "retry after database failure");
        saved(player, MenuKind.SHOP);
        pass("Injected SQLite write failure rolls back the complete grid action; retry succeeds");
    }

    private void legacyEscrow() throws Exception {
        ServerPlayer player = player();
        List<ItemStack> slots = emptySlots();
        slots.set(44, relic(1, 2, 2));
        seed(player, new MenuState(slots, ItemStack.EMPTY));
        service.openShop(player);
        check(InoriLootCompatibility.valid(menu(player).snapshot().slots()), "old out-of-bounds escrow repacked");
        equal(1, menu(player).snapshot().slots().get(0).getCount(), "old item retained");
        saved(player, MenuKind.SHOP);

        ServerPlayer overflow = player();
        slots = emptySlots();
        slots.set(0, relic(1, 9, 5));
        slots.set(44, relic(1, 9, 5));
        seed(overflow, new MenuState(slots, ItemStack.EMPTY));
        service.openShop(overflow);
        equal(0, menu(overflow).gridRows(), "unpackable legacy escrow stays available for recovery");
        equal(2L, menu(overflow).snapshot().slots().stream().filter(stack -> !stack.isEmpty()).count(), "overflow is not dropped");
        control(overflow, 49);
        equal(2000L, service.balance(overflow.getUUID()), "legacy escrow can still be sold once");
        equal(5, menu(overflow).gridRows(), "normal grid automatically resumes");
        saved(overflow, MenuKind.SHOP);
        pass("Legacy escrow migration and overflow retention without drops");
    }

    private ServerPlayer player() throws Exception {
        ServerPlayer player = new ServerPlayer(server, server.overworld(), new GameProfile(UUID.randomUUID(), "ShopTest"));
        new ServerGamePacketListenerImpl(server, new MemoryConnection(), player);
        service.preparePlayer(player);
        return player;
    }

    private static EconomyMenu menu(ServerPlayer player) {
        check(player.containerMenu instanceof EconomyMenu, "economy menu opened");
        return (EconomyMenu) player.containerMenu;
    }

    private static ItemStack relic(int count, int width, int height) {
        ItemStack stack = new ItemStack(Items.PAPER, count);
        CompoundTag values = values(stack);
        values.putString("inoriloot:loot-item-id", "relic");
        values.putInt("inoriloot:grid-width", width);
        values.putInt("inoriloot:grid-height", height);
        values.putInt("inoriloot:grid-rotated", 0);
        values.putInt("inoriloot:quality-index", 2);
        return stack;
    }

    private static CompoundTag values(ItemStack stack) {
        if (!stack.getOrCreateTag().contains("PublicBukkitValues")) stack.getOrCreateTag().put("PublicBukkitValues", new CompoundTag());
        return stack.getOrCreateTag().getCompound("PublicBukkitValues");
    }

    private void grid(ServerPlayer player, String action, String scope, int cell, int button) throws Exception {
        grid(player, action, scope, cell, button, -1, new int[0]);
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private void grid(ServerPlayer player, String action, String scope, int cell, int button, int hotbar, int[] cells) throws Exception {
        resetThrottle(menu(player));
        InoriLootCompatibility.sync(player);
        long revision = (Long) field(session(player), "revision");
        Class<?> layouts = Class.forName("inori.inoriloot.forge.grid.GridServerLayouts");
        Class<?> packetType = Class.forName("inori.inoriloot.forge.grid.GridActionC2SPacket");
        Class<? extends Enum> actionType = (Class<? extends Enum>) Class.forName(packetType.getName() + "$Action");
        Class<? extends Enum> scopeType = (Class<? extends Enum>) Class.forName("inori.inoriloot.forge.grid.GridScope");
        Object packet = packetType.getConstructors()[0].newInstance(menu(player).containerId, revision, ++sequence,
                Enum.valueOf(actionType, action), Enum.valueOf(scopeType, scope), cell, button, hotbar, cells, ItemStack.EMPTY);
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
        try {
            packetType.getMethod("encode", packetType, FriendlyByteBuf.class).invoke(null, packet, buffer);
            Object decoded = packetType.getMethod("decode", FriendlyByteBuf.class).invoke(null, buffer);
            layouts.getMethod("handle", ServerPlayer.class, packetType).invoke(null, player, decoded);
        } finally {
            buffer.release();
        }
    }

    private static Object session(ServerPlayer player) throws Exception {
        Field sessions = Class.forName("inori.inoriloot.forge.grid.GridServerLayouts").getDeclaredField("SESSIONS");
        sessions.setAccessible(true);
        Object session = ((Map<?, ?>) sessions.get(null)).get(player.getUUID());
        check(session != null, "grid session created");
        return session;
    }

    private static Object field(Object target, String name) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(target);
    }

    private static void assertContexts(ServerPlayer player, int rows) throws Exception {
        List<?> contexts = (List<?>) field(session(player), "contexts");
        equal(2, contexts.size(), "player and input contexts");
        Object loot = contexts.stream().filter(context -> {
            try { return field(context, "scope").toString().equals("LOOT"); }
            catch (Exception ex) { throw new IllegalStateException(ex); }
        }).findFirst().orElseThrow();
        equal(rows, field(loot, "rows"), "input grid rows");
        for (int slot : (int[]) field(loot, "menuSlots")) check(slot < rows * 9, "controls excluded from grid");
    }

    private static void resetThrottle(EconomyMenu menu) throws Exception {
        for (String name : List.of("lastMutationNanos", "lastControlNanos")) {
            Field field = EconomyMenu.class.getDeclaredField(name);
            field.setAccessible(true);
            field.setLong(menu, 0L);
        }
    }

    private static void control(ServerPlayer player, int slot) throws Exception {
        resetThrottle(menu(player));
        menu(player).clicked(slot, 0, ClickType.PICKUP, player);
    }

    private void saved(ServerPlayer player, MenuKind kind) throws Exception {
        MenuState current = menu(player).snapshot();
        MenuState saved = service.database().loadMenu(player.getUUID(), kind);
        check(ItemStack.matches(current.carried(), saved.carried()), "carried state persisted");
        for (int i = 0; i < current.slots().size(); i++) {
            check(ItemStack.matches(current.slots().get(i), saved.slots().get(i)), "slot " + i + " persisted");
        }
        check(player.getPersistentData().getString(EconomyService.INVENTORY_INTENT_MARKER).isEmpty(), "intent fully committed");
    }

    private void seed(ServerPlayer player, MenuState state) throws Exception {
        String id = service.database().prepareInventoryIntent(player, MenuKind.SHOP, MenuState.empty(MenuKind.SHOP), state);
        service.database().completeInventoryIntent(id);
    }

    private static List<ItemStack> emptySlots() {
        List<ItemStack> slots = new ArrayList<>();
        for (int i = 0; i < 45; i++) slots.add(ItemStack.EMPTY);
        return slots;
    }

    private static int physical(int cell) {
        return cell < 27 ? cell + 9 : cell - 27;
    }

    private void pass(String message) {
        passed.add("PASS: " + message);
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static void equal(Object expected, Object actual, String message) {
        if (!expected.equals(actual)) throw new AssertionError(message + ": expected=" + expected + ", actual=" + actual);
    }

    private static final class MemoryConnection extends Connection {
        private boolean connected = true;

        MemoryConnection() { super(PacketFlow.SERVERBOUND); }
        @Override public boolean isConnected() { return connected; }
        @Override public boolean isConnecting() { return false; }
        @Override public SocketAddress getRemoteAddress() { return new InetSocketAddress("127.0.0.1", 0); }
        @Override public void send(Packet<?> packet) {}
        @Override public void send(Packet<?> packet, PacketSendListener listener) { if (listener != null) listener.onSuccess(); }
        @Override public void disconnect(Component reason) { connected = false; }
    }
}
