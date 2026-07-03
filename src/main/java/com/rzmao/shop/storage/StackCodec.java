package com.rzmao.shop.storage;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.Tag;
import net.minecraft.world.item.ItemStack;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

public final class StackCodec {
    private static final int MAX_COMPRESSED_STATE_BYTES = 2 * 1024 * 1024;
    private StackCodec() {
    }

    public static byte[] encode(MenuState state) throws IOException {
        CompoundTag root = new CompoundTag();
        ListTag slots = new ListTag();
        for (int i = 0; i < state.slots().size(); i++) {
            ItemStack stack = state.slots().get(i);
            if (!stack.isEmpty()) {
                CompoundTag entry = new CompoundTag();
                entry.putInt("Slot", i);
                entry.put("Stack", stack.save(new CompoundTag()));
                slots.add(entry);
            }
        }
        root.putInt("Size", state.slots().size());
        root.put("Slots", slots);
        if (!state.carried().isEmpty()) {
            root.put("Carried", state.carried().save(new CompoundTag()));
        }
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        NbtIo.writeCompressed(root, output);
        if (output.size() > MAX_COMPRESSED_STATE_BYTES) {
            throw new IOException("托管物品数据超过 2 MiB 安全上限");
        }
        return output.toByteArray();
    }

    public static MenuState decode(byte[] data, int expectedSize) throws IOException {
        if (data == null || data.length == 0) {
            return empty(expectedSize);
        }
        CompoundTag root = NbtIo.readCompressed(new ByteArrayInputStream(data));
        if (root.getInt("Size") != expectedSize) {
            throw new IOException("托管槽数量不匹配");
        }
        List<ItemStack> slots = new ArrayList<>(expectedSize);
        for (int i = 0; i < expectedSize; i++) {
            slots.add(ItemStack.EMPTY);
        }
        ListTag entries = root.getList("Slots", Tag.TAG_COMPOUND);
        for (Tag raw : entries) {
            CompoundTag entry = (CompoundTag) raw;
            int slot = entry.getInt("Slot");
            if (slot < 0 || slot >= expectedSize) {
                throw new IOException("托管槽索引越界");
            }
            slots.set(slot, ItemStack.of(entry.getCompound("Stack")));
        }
        ItemStack carried = root.contains("Carried", Tag.TAG_COMPOUND)
                ? ItemStack.of(root.getCompound("Carried")) : ItemStack.EMPTY;
        return new MenuState(slots, carried);
    }

    public static byte[] encodeStack(ItemStack stack) throws IOException {
        return encode(new MenuState(List.of(stack), ItemStack.EMPTY));
    }

    public static ItemStack decodeStack(byte[] data) throws IOException {
        return decode(data, 1).slots().get(0);
    }

    private static MenuState empty(int size) {
        List<ItemStack> stacks = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            stacks.add(ItemStack.EMPTY);
        }
        return new MenuState(stacks, ItemStack.EMPTY);
    }
}
