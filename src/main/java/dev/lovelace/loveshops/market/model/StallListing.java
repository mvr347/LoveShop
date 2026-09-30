package dev.lovelace.loveshops.market.model;

import org.bukkit.inventory.ItemStack;

import java.util.UUID;

/**
 * One shelf slot of a stall. {@link #template()} is the item with amount 1; how many there are is
 * {@link #stock()}. For a BUY order {@link #maxAmount()} caps how many the stall will hold in total.
 */
public final class StallListing {

    private final long id;
    private final UUID pointId;
    private final ListingType type;
    private final int slotIndex;
    private final ItemStack template;
    private final String itemHash;
    private long unitPrice;
    private int stock;
    private final int maxAmount;
    private boolean active;

    public StallListing(long id, UUID pointId, ListingType type, int slotIndex, ItemStack template,
                        String itemHash, long unitPrice, int stock, int maxAmount, boolean active) {
        this.id = id;
        this.pointId = pointId;
        this.type = type;
        this.slotIndex = slotIndex;
        this.template = template;
        this.itemHash = itemHash;
        this.unitPrice = unitPrice;
        this.stock = stock;
        this.maxAmount = maxAmount;
        this.active = active;
    }

    public long id() { return id; }
    public UUID pointId() { return pointId; }
    public ListingType type() { return type; }
    public int slotIndex() { return slotIndex; }
    /** A defensive copy: callers must not be able to change the stored template. */
    public ItemStack template() { return template.clone(); }
    public String itemHash() { return itemHash; }

    public long unitPrice() { return unitPrice; }
    public void unitPrice(long unitPrice) { this.unitPrice = unitPrice; }

    public int stock() { return stock; }
    public void stock(int stock) { this.stock = Math.max(0, stock); }

    /** 0 = no cap (SELL listings). */
    public int maxAmount() { return maxAmount; }

    public boolean active() { return active; }
    public void active(boolean active) { this.active = active; }

    /** How many more a BUY order can take before it is full. */
    public int freeCapacity() {
        if (type != ListingType.BUY) return 0;
        return Math.max(0, maxAmount - stock);
    }

    public boolean matches(ItemStack other) {
        return other != null && template.isSimilar(other);
    }
}
