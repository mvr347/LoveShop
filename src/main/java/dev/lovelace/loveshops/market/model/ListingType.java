package dev.lovelace.loveshops.market.model;

public enum ListingType {
    /** The stall sells this item; {@code stock} is what is on the shelf. */
    SELL,
    /** The stall buys this item; {@code stock} is what it has bought and holds for the owner. */
    BUY
}
