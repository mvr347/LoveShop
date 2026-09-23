package dev.lovelace.loveshops.models;

public record WandererDealItem(
    String id,
    String name,
    int price,
    int amount,
    boolean bought,
    String itemDataBase64
) {
    public WandererDealItem withBought(boolean newBought) {
        return new WandererDealItem(id, name, price, amount, newBought, itemDataBase64);
    }
}
