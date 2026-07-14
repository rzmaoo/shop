package com.rzmao.shop.menu;

import com.rzmao.shop.EconomyService;
import com.rzmao.shop.storage.MenuKind;
import com.rzmao.shop.storage.MenuState;
import com.rzmao.shop.text.ShopText;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

public abstract class EconomyMenu extends AbstractContainerMenu {
    protected static final int GUI_SLOTS = 54;
    protected final EconomyService service;
    protected final ServerPlayer player;
    protected final MenuKind kind;
    protected final SimpleContainer inputs;
    protected final SimpleContainer controls;
    private boolean fatal;
    private boolean busy;
    private long lastControlNanos;
    private long lastMutationNanos;

    protected EconomyMenu(int id, Inventory inventory, EconomyService service, MenuKind kind,
                          MenuState initial, Predicate<ItemStack> validator) {
        super(MenuType.GENERIC_9x6, id);
        this.service = service;
        this.player = (ServerPlayer) inventory.player;
        this.kind = kind;
        this.inputs = new SimpleContainer(kind.inputSlots());
        this.controls = new SimpleContainer(GUI_SLOTS - kind.inputSlots());
        for (int i = 0; i < kind.inputSlots(); i++) inputs.setItem(i, initial.slots().get(i).copy());

        for (int slot = 0; slot < GUI_SLOTS; slot++) {
            int x = 8 + (slot % 9) * 18;
            int y = 18 + (slot / 9) * 18;
            if (slot < kind.inputSlots()) {
                addSlot(new InputSlot(inputs, slot, x, y, validator));
            } else {
                addSlot(new LockedSlot(controls, slot - kind.inputSlots(), x, y));
            }
        }
        addPlayerInventory(inventory);
        setCarried(initial.carried());
        refreshControls();
    }

    private void addPlayerInventory(Inventory inventory) {
        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 9; col++) {
                addSlot(new Slot(inventory, col + row * 9 + 9, 8 + col * 18, 140 + row * 18));
            }
        }
        for (int col = 0; col < 9; col++) {
            addSlot(new Slot(inventory, col, 8 + col * 18, 198));
        }
    }

    @Override
    public final void clicked(int slotId, int button, ClickType clickType, Player ignored) {
        if (busy || fatal || ignored != player) return;
        if (clickType == ClickType.CLONE || clickType == ClickType.THROW
                || (slotId == SLOT_CLICKED_OUTSIDE && !getCarried().isEmpty())) {
            player.sendSystemMessage(ShopText.text("shop.common.no_drop"));
            service.auditRejected(player, kind.name() + "_MOVE", "禁止的点击类型", clickType.name());
            return;
        }
        long clickNanos = System.nanoTime();
        if (clickType != ClickType.QUICK_CRAFT && (slotId < kind.inputSlots() || slotId >= GUI_SLOTS)
                && clickNanos - lastMutationNanos < 100_000_000L) {
            broadcastFullState();
            return;
        }
        lastMutationNanos = clickNanos;
        if (slotId >= kind.inputSlots() && slotId < GUI_SLOTS) {
            long now = System.nanoTime();
            if (now - lastControlNanos < 100_000_000L) return;
            lastControlNanos = now;
            handleControl(slotId, button, clickType);
            return;
        }
        MenuState before = snapshot();
        List<ItemStack> inventoryBefore = snapshotInventory();
        try {
            super.clicked(slotId, button, clickType, ignored);
            MenuState after = snapshot();
            if (!sameState(before, after) || !sameInventory(inventoryBefore)) {
                service.persistMenuMutation(player, kind, before, after);
            }
            refreshControls();
            broadcastChanges();
        } catch (EconomyService.RecoverableMutationException ex) {
            restore(before, inventoryBefore);
            player.sendSystemMessage(ShopText.text("shop.common.move_reverted"));
            service.auditRejected(player, kind.name() + "_MOVE", "数据库写入失败", ex.getMessage());
        } catch (EconomyService.FatalMutationException ex) {
            fatal = true;
        } catch (RuntimeException ex) {
            restore(before, inventoryBefore);
            throw ex;
        }
    }

    protected final void durableMutation(Runnable mutation) {
        if (busy || fatal) return;
        busy = true;
        MenuState before = snapshot();
        List<ItemStack> inventoryBefore = snapshotInventory();
        try {
            mutation.run();
            MenuState after = snapshot();
            if (!sameState(before, after) || !sameInventory(inventoryBefore)) {
                service.persistMenuMutation(player, kind, before, after);
            }
            refreshControls();
            broadcastChanges();
        } catch (EconomyService.RecoverableMutationException ex) {
            restore(before, inventoryBefore);
            player.sendSystemMessage(ShopText.text("shop.common.move_reverted"));
        } catch (EconomyService.FatalMutationException ex) {
            fatal = true;
        } finally {
            busy = false;
        }
    }

    protected final void takeBackAll() {
        durableMutation(() -> {
            if (!getCarried().isEmpty() && EconomyService.canFit(player.getInventory(), getCarried())) {
                ItemStack carried = getCarried().copy();
                if (player.getInventory().add(carried) && carried.isEmpty()) setCarried(ItemStack.EMPTY);
            }
            for (int i = 0; i < inputs.getContainerSize(); i++) {
                ItemStack stack = inputs.getItem(i);
                if (stack.isEmpty() || !EconomyService.canFit(player.getInventory(), stack)) continue;
                ItemStack moving = stack.copy();
                if (player.getInventory().add(moving) && moving.isEmpty()) inputs.setItem(i, ItemStack.EMPTY);
            }
        });
    }

    protected final void applyCommittedState(MenuState state) {
        for (int i = 0; i < inputs.getContainerSize(); i++) inputs.setItem(i, state.slots().get(i).copy());
        setCarried(state.carried());
        refreshControls();
        broadcastChanges();
    }

    public final MenuState snapshot() {
        List<ItemStack> stacks = new ArrayList<>(inputs.getContainerSize());
        for (int i = 0; i < inputs.getContainerSize(); i++) stacks.add(inputs.getItem(i).copy());
        return new MenuState(stacks, getCarried());
    }

    @Override
    public final ItemStack quickMoveStack(Player ignored, int index) {
        if (index < 0 || index >= slots.size()) return ItemStack.EMPTY;
        Slot slot = slots.get(index);
        if (!slot.hasItem()) return ItemStack.EMPTY;
        ItemStack source = slot.getItem();
        ItemStack original = source.copy();
        if (index < kind.inputSlots()) {
            if (!moveItemStackTo(source, GUI_SLOTS, slots.size(), true)) return ItemStack.EMPTY;
        } else if (index >= GUI_SLOTS) {
            if (!isAccepted(source) || !moveItemStackTo(source, 0, kind.inputSlots(), false)) return ItemStack.EMPTY;
        } else {
            return ItemStack.EMPTY;
        }
        if (source.isEmpty()) slot.set(ItemStack.EMPTY); else slot.setChanged();
        slot.onTake(ignored, source);
        return original;
    }

    @Override public boolean canDragTo(Slot slot) { return slot.index < kind.inputSlots() || slot.index >= GUI_SLOTS; }
    @Override public boolean canTakeItemForPickAll(ItemStack stack, Slot slot) { return slot.index < kind.inputSlots() || slot.index >= GUI_SLOTS; }
    @Override public boolean stillValid(Player ignored) { return !fatal && ignored == player && !player.hasDisconnected(); }

    @Override
    public void removed(Player ignored) {
        if (ignored == player) {
            service.auditRetainedItems(player, kind, snapshot(), "关闭界面时仍有物品保留在托管区");
            // 鼠标携带物品属于已持久化的菜单状态，不让原版逻辑再次掉落或返还。
            setCarried(ItemStack.EMPTY);
        }
        super.removed(ignored);
    }

    protected final void setControl(int guiSlot, ItemStack stack) {
        controls.setItem(guiSlot - kind.inputSlots(), stack);
    }

    protected final void playConfiguredSound(SoundEvent sound) {
        var sounds = service.config().sounds();
        player.playNotifySound(sound, SoundSource.PLAYERS, sounds.volume(), sounds.pitch());
    }

    protected abstract boolean isAccepted(ItemStack stack);
    protected abstract void handleControl(int slotId, int button, ClickType clickType);
    protected abstract void refreshControls();

    private List<ItemStack> snapshotInventory() {
        List<ItemStack> snapshot = new ArrayList<>(player.getInventory().getContainerSize());
        for (int i = 0; i < player.getInventory().getContainerSize(); i++) snapshot.add(player.getInventory().getItem(i).copy());
        return snapshot;
    }

    private void restore(MenuState state, List<ItemStack> inventory) {
        for (int i = 0; i < inputs.getContainerSize(); i++) inputs.setItem(i, state.slots().get(i).copy());
        setCarried(state.carried());
        for (int i = 0; i < inventory.size(); i++) player.getInventory().setItem(i, inventory.get(i).copy());
        refreshControls();
        broadcastFullState();
    }

    private boolean sameInventory(List<ItemStack> before) {
        for (int i = 0; i < before.size(); i++) {
            if (!ItemStack.matches(before.get(i), player.getInventory().getItem(i))) return false;
        }
        return true;
    }

    private static boolean sameState(MenuState a, MenuState b) {
        if (!ItemStack.matches(a.carried(), b.carried()) || a.slots().size() != b.slots().size()) return false;
        for (int i = 0; i < a.slots().size(); i++) if (!ItemStack.matches(a.slots().get(i), b.slots().get(i))) return false;
        return true;
    }
}
