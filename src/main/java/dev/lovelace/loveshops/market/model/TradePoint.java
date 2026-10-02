package dev.lovelace.loveshops.market.model;

import java.util.UUID;

/**
 * Trading side of a LoveClaims trade point. Keyed by the claim id; a free point has no owner.
 * Mutated on the main thread only (every market action is a click, an event or a main-thread task).
 */
public final class TradePoint {

    private final UUID claimId;
    private UUID ownerUuid;
    private String ownerName;
    private Integer npcCitizensId;
    private Integer guardCitizensId;
    private int level = 1;
    private int sellSlots = 5;
    private int buySlots = 5;
    private boolean open;
    private CloseReason closeReason;
    private long tillCoins;
    private long revenueTotal;
    private long salesTotal;
    private GuardState guardState = GuardState.NONE;
    private long guardPaidUntil;
    private long rentedAt;
    private long version;
    private TradingMode tradingMode = TradingMode.BOTH;
    private org.bukkit.Location closedSignLocation;
    /** Where a player is taken by {@code /tp <id>}; null = next to the trader. */
    private org.bukkit.Location teleportLocation;
    /** The sign that shows the point's id; null = none. */
    private org.bukkit.Location idSignLocation;
    /**
     * The owner's tax rate as last seen while the owner was ONLINE. LoveBehavior only knows online
     * players (an offline one reads as neutral), and most sales happen while the owner is away, so
     * the last known rate is what those sales are taxed with. In-memory only.
     */
    private Double lastTaxRate;

    public TradePoint(UUID claimId) {
        this.claimId = claimId;
    }

    public UUID claimId() { return claimId; }

    public UUID ownerUuid() { return ownerUuid; }
    public void ownerUuid(UUID ownerUuid) { this.ownerUuid = ownerUuid; }

    public String ownerName() { return ownerName; }
    public void ownerName(String ownerName) { this.ownerName = ownerName; }

    public Integer npcCitizensId() { return npcCitizensId; }
    public void npcCitizensId(Integer id) { this.npcCitizensId = id; }

    public Integer guardCitizensId() { return guardCitizensId; }
    public void guardCitizensId(Integer id) { this.guardCitizensId = id; }

    public int level() { return level; }
    public void level(int level) { this.level = Math.max(1, level); }

    public int sellSlots() { return sellSlots; }
    public void sellSlots(int sellSlots) { this.sellSlots = Math.max(0, sellSlots); }

    public int buySlots() { return buySlots; }
    public void buySlots(int buySlots) { this.buySlots = Math.max(0, buySlots); }

    public boolean open() { return open; }
    public void open(boolean open) { this.open = open; }

    public CloseReason closeReason() { return closeReason; }
    public void closeReason(CloseReason closeReason) { this.closeReason = closeReason; }

    public long tillCoins() { return tillCoins; }
    public void tillCoins(long tillCoins) { this.tillCoins = Math.max(0L, tillCoins); }

    public long revenueTotal() { return revenueTotal; }
    public void revenueTotal(long revenueTotal) { this.revenueTotal = Math.max(0L, revenueTotal); }

    public long salesTotal() { return salesTotal; }
    public void salesTotal(long salesTotal) { this.salesTotal = Math.max(0L, salesTotal); }

    public GuardState guardState() { return guardState; }
    public void guardState(GuardState guardState) { this.guardState = guardState == null ? GuardState.NONE : guardState; }

    public long guardPaidUntil() { return guardPaidUntil; }
    public void guardPaidUntil(long guardPaidUntil) { this.guardPaidUntil = guardPaidUntil; }

    public long rentedAt() { return rentedAt; }
    public void rentedAt(long rentedAt) { this.rentedAt = rentedAt; }

    public long version() { return version; }
    public void version(long version) { this.version = version; }

    public Double lastTaxRate() { return lastTaxRate; }
    public void lastTaxRate(Double rate) { this.lastTaxRate = rate; }

    public TradingMode tradingMode() { return tradingMode; }
    public void tradingMode(TradingMode mode) { this.tradingMode = mode == null ? TradingMode.BOTH : mode; }

    public org.bukkit.Location closedSignLocation() { return closedSignLocation; }
    public void closedSignLocation(org.bukkit.Location loc) { this.closedSignLocation = loc; }

    public org.bukkit.Location teleportLocation() { return teleportLocation; }
    public void teleportLocation(org.bukkit.Location loc) { this.teleportLocation = loc; }

    public org.bukkit.Location idSignLocation() { return idSignLocation; }
    public void idSignLocation(org.bukkit.Location loc) { this.idSignLocation = loc; }

    public boolean hasOwner() { return ownerUuid != null; }
    public boolean isOwner(UUID player) { return ownerUuid != null && ownerUuid.equals(player); }

    /** A point trades only when it has a tenant, is switched on and nothing else keeps it shut. */
    public boolean isTrading() { return hasOwner() && open; }
}
