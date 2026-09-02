package com.rzmao.shop.menu;

import com.rzmao.shop.EconomyService;
import com.rzmao.shop.compat.InoriLootCompatibility;
import com.rzmao.shop.compat.InoriLootItems;
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
    private boolean handlingGridAction;
    private boolean gridRecovery;
    private long lastControlNanos;
    private long lastMutationNanos;

    protected EconomyMenu(int id, Inventory inventory, EconomyService service, MenuKind kind,
                          MenuState initial, Predicate<ItemStack> validator) {
        super(MenuType.GENERIC_9x6, id);
        this.service = service;
        this.player = (ServerPlayer) inventory.player;
        this.kind = kind;
        this.gridRecovery = InoriLootCompatibility.enabled() && !InoriLootCompatibility.valid(initial.slots());
        this.inputs = new SimpleContainer(kind.inputSlots());
        this.controls = new SimpleContainer(GUI_SLOTS - kind.inputSlots());
        for (int i = 0; i < kind.inputSlots(); i++) inputs.setItem(i, initial.slots().get(i).copy());

        for (int slot = 0; slot < GUI_SLOTS; slot++) {
            int x = 8 + (slot % 9) * 18;
            int y = 18 + (slot / 9) * 18;
            if (slot < kind.inputSlots()) {
                addSlot(new InputSlot(inputs, slot, x, y,
                        stack -> !gridRecovery && !InoriLootItems.isPlaceholder(stack) && validator.test(stack)));
            } else {
                addSlot(new LockedSlot(controls, slot - kind.inputSlots(), x, y));
            }
        }
        addPlayerInventory(inventory);
        setCarried(initial.carried());
        refreshControls();
    }

    public final void initializeGrid() {
        if (!InoriLootCompatibility.enabled()) return;
        List<ItemStack> arrangedInputs = InoriLootCompatibility.arrange(snapshot().slots());
        List<ItemStack> arrangedInventory = InoriLootCompatibility.arrange(
                InoriLootCompatibility.gridStacks(player.getInventory()));
        if (arrangedInputs == null || arrangedInventory == null) {
            player.sendSystemMessage(ShopText.text("shop.compat.inoriloot.recovery"));
        }
        if (arrangedInventory != null) {
            durableMutation(() -> {
                if (arrangedInputs != null) {
                    for (int i = 0; i < arrangedInputs.size(); i++) inputs.setItem(i, arrangedInputs.get(i).copy());
                }
                for (int cell = 0; cell < arrangedInventory.size(); cell++) {
                    player.getInventory().setItem(cell < 27 ? cell + 9 : cell - 27, arrangedInventory.get(cell).copy());
                }
            });
        }
        InoriLootCompatibility.sync(player);
    }

    public final int gridRows() {
        return gridRecovery ? 0 : kind.inputSlots() / 9;
    }

    public final boolean isHandlingGridAction() {
        return handlingGridAction;
    }

    public final void handleGridAction(Object packet) {
        if (busy || fatal || player.containerMenu != this) return;
        if (!InoriLootCompatibility.isCurrentAction(packet, containerId)) return;
        if (!InoriLootCompatibility.permits(packet)) {
            player.sendSystemMessage(ShopText.text("shop.common.no_drop"));
            service.auditRejected(player, kind.name() + "_MOVE", "禁止的多格操作", "InoriLoot");
            broadcastFullState();
            InoriLootCompatibility.sync(player);
            return;
        }
        long now = System.nanoTime();
        if (now - lastMutationNanos < 100_000_000L) {
            broadcastFullState();
            InoriLootCompatibility.sync(player);
            return;
        }
        lastMutationNanos = now;
        handlingGridAction = true;
        try {
            durableMutation(() -> InoriLootCompatibility.handle(player, packet));
        } finally {
            handlingGridAction = false;
        }
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
            validateGridMutation();
            MenuState after = snapshot();
            if (!sameState(before, after) || !sameInventory(inventoryBefore)) {
                service.persistMenuMutation(player, kind, before, after);
            }
        } catch (EconomyService.RecoverableMutationException ex) {
            restore(before, inventoryBefore);
            player.sendSystemMessage(ShopText.text("shop.common.move_reverted"));
            service.auditRejected(player, kind.name() + "_MOVE", "数据库写入失败", ex.getMessage());
            return;
        } catch (EconomyService.FatalMutationException ex) {
            fatal = true;
            return;
        } catch (RuntimeException ex) {
            restore(before, inventoryBefore);
            throw ex;
        }
        refreshState(false);
    }

    protected final void durableMutation(Runnable mutation) {
        if (busy || fatal) return;
        busy = true;
        MenuState before = snapshot();
        List<ItemStack> inventoryBefore = snapshotInventory();
        try {
            try {
                mutation.run();
                validateGridMutation();
                MenuState after = snapshot();
                if (!sameState(before, after) || !sameInventory(inventoryBefore)) {
                    service.persistMenuMutation(player, kind, before, after);
                }
            } catch (EconomyService.RecoverableMutationException ex) {
                restore(before, inventoryBefore);
                player.sendSystemMessage(ShopText.text("shop.common.move_reverted"));
                service.auditRejected(player, kind.name() + "_MOVE", "数据库写入失败", ex.getMessage());
                return;
            } catch (EconomyService.FatalMutationException ex) {
                fatal = true;
                return;
            } catch (RuntimeException ex) {
                restore(before, inventoryBefore);
                player.sendSystemMessage(ShopText.text("shop.common.move_reverted"));
                service.auditRejected(player, kind.name() + "_MOVE", ex.getMessage(), "物品移动已回滚");
                return;
            }
            // 数据库提交后只刷新界面；同步失败不能把已提交的物品恢复到旧位置。
            refreshState(false);
        } finally {
            busy = false;
        }
    }

    protected final void takeBackAll() {
        durableMutation(() -> {
            if (!getCarried().isEmpty() && EconomyService.canFit(player.getInventory(), getCarried())) {
                ItemStack carried = getCarried().copy();
                EconomyService.addToInventory(player.getInventory(), carried);
                setCarried(carried);
            }
            for (int i = 0; i < inputs.getContainerSize(); i++) {
                ItemStack stack = inputs.getItem(i);
                if (stack.isEmpty() || !EconomyService.canFit(player.getInventory(), stack)) continue;
                ItemStack moving = stack.copy();
                EconomyService.addToInventory(player.getInventory(), moving);
                // 即便另一个模组只接收了部分物品，也必须扣除已经返还的数量。
                inputs.setItem(i, moving);
            }
        });
    }

    protected final void applyCommittedState(MenuState state) {
        for (int i = 0; i < inputs.getContainerSize(); i++) inputs.setItem(i, state.slots().get(i).copy());
        setCarried(state.carried());
        refreshState(false);
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
            if (gridRecovery || !isAccepted(source) || !moveItemStackTo(source, 0, kind.inputSlots(), false)) return ItemStack.EMPTY;
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
        refreshState(true);
    }

    private void validateGridMutation() {
        if (!InoriLootCompatibility.enabled()) return;
        if ((!gridRecovery && !InoriLootCompatibility.valid(snapshot().slots()))
                || !InoriLootCompatibility.valid(player.getInventory())) {
            throw new IllegalStateException("多格物品放置越界或重叠");
        }
    }

    private void refreshState(boolean full) {
        if (InoriLootCompatibility.enabled()) gridRecovery = !InoriLootCompatibility.valid(snapshot().slots());
        refreshControls();
        if (full) broadcastFullState(); else broadcastChanges();
        InoriLootCompatibility.sync(player);
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
