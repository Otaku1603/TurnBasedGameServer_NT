package com.game.nt.economy.internal;

/** 背包条目视图（只读 DTO）。 */
public record InventoryItemView(int itemId, String name, String type, int count, boolean equipped) {
}
