package dev.lovelace.loveshops.models.caravan;

/**
 * Состояние активного ящика Караванщика в базе данных текущего визита.
 */
public class DailyCrateState {

    private final int id;
    private final int visitId;
    private final String crateKey;
    private final String displayName;
    private int currentAmount;
    private final int maxAmount;
    private final int pricePerUnit;
    private final boolean urgent;
    private final long urgentExpiresAt;
    private boolean closed;

    public DailyCrateState(int id, int visitId, String crateKey, String displayName,
                           int currentAmount, int maxAmount, int pricePerUnit,
                           boolean urgent, long urgentExpiresAt, boolean closed) {
        this.id = id;
        this.visitId = visitId;
        this.crateKey = crateKey;
        this.displayName = displayName;
        this.currentAmount = currentAmount;
        this.maxAmount = maxAmount;
        this.pricePerUnit = pricePerUnit;
        this.urgent = urgent;
        this.urgentExpiresAt = urgentExpiresAt;
        this.closed = closed;
    }

    public int id() { return id; }
    public int visitId() { return visitId; }
    public String crateKey() { return crateKey; }
    public String displayName() { return displayName; }
    public int currentAmount() { return currentAmount; }
    public int maxAmount() { return maxAmount; }
    public int pricePerUnit() { return pricePerUnit; }
    public boolean isUrgent() { return urgent; }
    public long urgentExpiresAt() { return urgentExpiresAt; }
    public boolean isClosed() { return closed; }

    public void setCurrentAmount(int currentAmount) { this.currentAmount = currentAmount; }
    public void setClosed(boolean closed) { this.closed = closed; }

    public int remainingAmount() {
        return Math.max(0, maxAmount - currentAmount);
    }
}
