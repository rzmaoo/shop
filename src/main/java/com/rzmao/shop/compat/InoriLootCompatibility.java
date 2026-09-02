package com.rzmao.shop.compat;

import com.rzmao.shop.menu.EconomyMenu;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.fml.ModList;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.function.IntFunction;

/** 仅在安装 InoriLoot 时加载其网格接口，不把插件或模组打包进 shop。 */
public final class InoriLootCompatibility {
    private static final Logger LOGGER = LoggerFactory.getLogger(InoriLootCompatibility.class);
    private static volatile Api api;

    private InoriLootCompatibility() {}

    public static synchronized void initialize() {
        if (api != null || !ModList.get().isLoaded("inoriloot")) return;
        try {
            api = new Api();
            LOGGER.info("Shop InoriLoot compatibility enabled (grid protocol 2)");
        } catch (ReflectiveOperationException | RuntimeException | LinkageError ex) {
            throw new IllegalStateException("InoriLoot 网格接口不兼容，无法安全打开商店", ex);
        }
    }

    public static boolean enabled() {
        return api != null;
    }

    public static List<Object> contexts(ServerPlayer player, EconomyMenu menu) {
        Api bridge = requireApi();
        List<Object> contexts = new ArrayList<>(2);
        // 直接提供当前菜单的区域，避免与插件自己的战利品会话绑定、解绑相互覆盖。
        contexts.add(invoke(bridge.playerContext, player, menu));
        if (menu.gridRows() > 0) contexts.add(invoke(bridge.lootContext, player, menu, menu.gridRows()));
        return contexts;
    }

    public static boolean isCurrentAction(Object packet, int menuId) {
        return ((Number) read(requireApi().menuId, packet)).intValue() == menuId;
    }

    public static boolean permits(Object packet) {
        Api bridge = requireApi();
        String action = ((Enum<?>) read(bridge.action, packet)).name();
        String scope = ((Enum<?>) read(bridge.scope, packet)).name();
        int cell = ((Number) read(bridge.cell, packet)).intValue();
        int button = ((Number) read(bridge.button, packet)).intValue();
        if ((!scope.equals("PLAYER") && !scope.equals("LOOT")) || cell < 0) return false;
        return switch (action) {
            case "PICKUP", "QUICK_MOVE", "QUICK_CRAFT", "PICKUP_ALL" -> button == 0 || button == 1;
            case "ROTATE_CARRIED", "ROTATE_SLOT" -> true;
            case "SWAP_HOTBAR" -> {
                int hotbar = ((Number) read(bridge.hotbar, packet)).intValue();
                yield hotbar >= 0 && hotbar < 9;
            }
            // 这些操作会在托管事务以外掉落、生成物品，不能交给 InoriLoot 执行。
            default -> false;
        };
    }

    public static void handle(ServerPlayer player, Object packet) {
        invoke(requireApi().handle, player, packet);
    }

    public static void sync(ServerPlayer player) {
        if (enabled()) invoke(requireApi().tick, player);
    }

    public static boolean valid(List<ItemStack> stacks) {
        if (!enabled()) return true;
        return !(Boolean) invokeOn(requireApi().invalidPlacement, layout(stacks));
    }

    public static boolean valid(Inventory inventory) {
        return valid(gridStacks(inventory));
    }

    /** 旧托管区可能按单格存放；只在全部物品都能放下时提交重新排列。 */
    public static List<ItemStack> arrange(List<ItemStack> stacks) {
        if (valid(stacks)) return stacks;
        Api bridge = requireApi();
        List<ItemStack> arranged = new ArrayList<>(stacks.size());
        for (int i = 0; i < stacks.size(); i++) arranged.add(ItemStack.EMPTY);
        Object grid = layout(arranged);
        for (ItemStack stack : stacks) {
            if (stack.isEmpty()) continue;
            Object size = invoke(bridge.itemSize, stack);
            boolean placed = false;
            for (int cell = 0; cell < arranged.size(); cell++) {
                if ((Boolean) invokeOn(bridge.canPlace, grid, cell, size, -1)) {
                    arranged.set(cell, stack.copy());
                    invokeOn(bridge.add, grid, cell, size);
                    placed = true;
                    break;
                }
            }
            if (!placed) return null;
        }
        return arranged;
    }

    /** 使用 InoriLoot 自己的插入算法做副本预检，空白的从属格不能算空位。 */
    public static boolean canFit(Inventory inventory, ItemStack requested) {
        Api bridge = requireApi();
        Inventory scratch = new Inventory(inventory.player);
        for (int i = 0; i < inventory.items.size(); i++) scratch.items.set(i, inventory.items.get(i).copy());
        if (!valid(scratch)) return false;
        ItemStack remaining = requested.copy();
        while (!remaining.isEmpty()) {
            ItemStack part = remaining.split(Math.min(remaining.getCount(), remaining.getMaxStackSize()));
            if (!((ItemStack) invoke(bridge.insert, scratch, part)).isEmpty()) return false;
        }
        return true;
    }

    public static List<ItemStack> gridStacks(Inventory inventory) {
        List<ItemStack> stacks = new ArrayList<>(36);
        for (int cell = 0; cell < 36; cell++) stacks.add(inventory.getItem(cell < 27 ? cell + 9 : cell - 27));
        return stacks;
    }

    public static void insert(Inventory inventory, ItemStack stack) {
        ItemStack remaining = (ItemStack) invoke(requireApi().insert, inventory, stack);
        stack.setCount(remaining.getCount());
    }

    private static Object layout(List<ItemStack> stacks) {
        return invoke(requireApi().fromStacks, 9, stacks.size() / 9, (IntFunction<ItemStack>) stacks::get);
    }

    private static Api requireApi() {
        Api bridge = api;
        if (bridge == null) throw new IllegalStateException("InoriLoot compatibility has not been initialized");
        return bridge;
    }

    private static Object read(Field field, Object instance) {
        try {
            return field.get(instance);
        } catch (IllegalAccessException ex) {
            throw new IllegalStateException("Unable to read InoriLoot action", ex);
        }
    }

    private static Object invoke(Method method, Object... arguments) {
        return invokeOn(method, null, arguments);
    }

    private static Object invokeOn(Method method, Object instance, Object... arguments) {
        try {
            return method.invoke(instance, arguments);
        } catch (InvocationTargetException ex) {
            if (ex.getCause() instanceof Error error) throw error;
            throw new IllegalStateException("InoriLoot operation failed: " + method.getName(), ex.getCause());
        } catch (ReflectiveOperationException ex) {
            throw new IllegalStateException("Unable to call InoriLoot: " + method.getName(), ex);
        }
    }

    private static final class Api {
        final Method playerContext, lootContext, handle, tick, fromStacks, invalidPlacement, itemSize, canPlace, add, insert;
        final Field menuId, action, scope, cell, button, hotbar;

        Api() throws ReflectiveOperationException {
            ClassLoader loader = InoriLootCompatibility.class.getClassLoader();
            String prefix = "inori.inoriloot.forge.grid.";
            Class<?> layouts = Class.forName(prefix + "GridServerLayouts", true, loader);
            Class<?> context = Class.forName(prefix + "GridServerLayouts$GridContext", false, loader);
            Class<?> packet = Class.forName(prefix + "GridActionC2SPacket", false, loader);
            Class<?> grid = Class.forName(prefix + "GridLayout", false, loader);
            Class<?> size = Class.forName(prefix + "GridSize", false, loader);
            Class<?> item = Class.forName(prefix + "GridItemData", false, loader);
            Class<?> inventory = Class.forName(prefix + "GridPlayerInventory", false, loader);
            playerContext = context.getDeclaredMethod("player", ServerPlayer.class, AbstractContainerMenu.class);
            lootContext = context.getDeclaredMethod("loot", ServerPlayer.class, AbstractContainerMenu.class, int.class);
            playerContext.setAccessible(true);
            lootContext.setAccessible(true);
            handle = layouts.getMethod("handle", ServerPlayer.class, packet);
            tick = layouts.getMethod("tick", ServerPlayer.class);
            fromStacks = grid.getMethod("fromStacks", int.class, int.class, IntFunction.class);
            invalidPlacement = grid.getMethod("hasInvalidPlacement");
            canPlace = grid.getMethod("canPlace", int.class, size, int.class);
            add = grid.getMethod("add", int.class, size);
            itemSize = item.getMethod("size", ItemStack.class);
            insert = inventory.getMethod("insert", Inventory.class, ItemStack.class);
            menuId = packet.getField("menuId");
            action = packet.getField("action");
            scope = packet.getField("scope");
            cell = packet.getField("cell");
            button = packet.getField("button");
            hotbar = packet.getField("hotbarIndex");
        }
    }
}
