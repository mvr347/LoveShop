package dev.lovelace.loveshops.managers;

import dev.lovelace.loveshops.LoveShops;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

public class CurrencyManager {

    private final LoveShops plugin;

    public CurrencyManager(LoveShops plugin) {
        this.plugin = plugin;
    }

    public String getCurrencyName() {
        return plugin.getConfig().getString("currency.currency-display-name", "Монеты");
    }

    public boolean giveCurrency(Player player, int amount) {
        if (amount <= 0) return true;

        String type = plugin.getConfig().getString("currency.type", "itemsadder").toLowerCase();
        String itemCustomId = plugin.getConfig().getString("currency.currency-item", "coins");

        switch (type) {
            case "itemsadder" -> {
                try {
                    Class<?> customItemClass = Class.forName("dev.lone.itemsadder.api.CustomStack");
                    var getInstanceMethod = customItemClass.getMethod("getInstance", String.class);
                    Object customStack = getInstanceMethod.invoke(null, itemCustomId);
                    if (customStack != null) {
                        var getItemStackMethod = customItemClass.getMethod("getItemStack");
                        ItemStack stack = (ItemStack) getItemStackMethod.invoke(customStack);
                        stack.setAmount(amount);
                        player.getInventory().addItem(stack);
                        return true;
                    }
                } catch (Exception ignored) {}
            }
            case "executableitems" -> {
                try {
                    Class<?> apiClass = Class.forName("com.ssamm.executableitems.ExecutableItemsAPI");
                    var method = apiClass.getMethod("getExecutableItemsManager");
                    Object mgr = method.invoke(null);
                    var giveMethod = mgr.getClass().getMethod("giveExecutableItem", Player.class, String.class, int.class);
                    giveMethod.invoke(mgr, player, itemCustomId, amount);
                    return true;
                } catch (Exception ignored) {}
            }
        }

        // Fallback: Default Gold NUGGET or SUNFLOWER coins
        ItemStack coinItem = new ItemStack(Material.GOLD_NUGGET, amount);
        ItemMeta meta = coinItem.getItemMeta();
        if (meta != null) {
            meta.displayName(net.kyori.adventure.text.Component.text(getCurrencyName()));
            coinItem.setItemMeta(meta);
        }
        player.getInventory().addItem(coinItem);
        return true;
    }

    public boolean removeCurrency(Player player, int amount) {
        if (amount <= 0) return true;
        if (getPlayerBalance(player) < amount) {
            return false;
        }

        int remaining = amount;
        for (ItemStack item : player.getInventory().getContents()) {
            if (item == null || item.getType() == Material.AIR) continue;
            if (isCurrencyItem(item)) {
                int itemAmount = item.getAmount();
                if (itemAmount <= remaining) {
                    remaining -= itemAmount;
                    item.setAmount(0);
                } else {
                    item.setAmount(itemAmount - remaining);
                    remaining = 0;
                    break;
                }
            }
        }
        return remaining == 0;
    }

    public int getPlayerBalance(Player player) {
        int count = 0;
        for (ItemStack item : player.getInventory().getContents()) {
            if (item != null && isCurrencyItem(item)) {
                count += item.getAmount();
            }
        }
        return count;
    }

    public boolean isCurrencyItem(ItemStack item) {
        if (item == null || item.getType() == Material.AIR) return false;
        String type = plugin.getConfig().getString("currency.type", "itemsadder").toLowerCase();

        if (type.equals("itemsadder")) {
            try {
                Class<?> customItemClass = Class.forName("dev.lone.itemsadder.api.CustomStack");
                var byItemStack = customItemClass.getMethod("byItemStack", ItemStack.class);
                Object customStack = byItemStack.invoke(null, item);
                if (customStack != null) {
                    var getId = customItemClass.getMethod("getId");
                    String id = (String) getId.invoke(customStack);
                    return id.equalsIgnoreCase(plugin.getConfig().getString("currency.currency-item", "coins"));
                }
            } catch (Exception ignored) {}
        }

        // Default item match (GOLD_NUGGET or custom item)
        return item.getType() == Material.GOLD_NUGGET;
    }
}
