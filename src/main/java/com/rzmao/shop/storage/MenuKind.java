package com.rzmao.shop.storage;

public enum MenuKind {
    SHOP(45),
    ATM(36);

    private final int inputSlots;

    MenuKind(int inputSlots) {
        this.inputSlots = inputSlots;
    }

    public int inputSlots() {
        return inputSlots;
    }
}
