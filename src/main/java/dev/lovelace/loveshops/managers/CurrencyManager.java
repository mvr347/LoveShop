package dev.lovelace.loveshops.managers;

import dev.lovelace.loveshops.LoveShops;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.*;

public class CurrencyManager {

    private final LoveShops plugin;

    public CurrencyManager(LoveShops plugin) {
        this.plugin = plugin;
    }

    public String getCurrencyName() {
        return plugin.getConfig().getString("currency.currency-display-name", "Монеты");
    }

    public Map<String, Integer> getCoinTiers() {
        Map<String, Integer> tiers = new LinkedHashMap<>();
        ConfigurationSection section = plugin.getConfig().getConfigurationSection("currency.itemsadder-coins");
        if (section != null) {
            for (String key : section.getKeys(false)) {
                tiers.put(key.toLowerCase(), section.getInt(key));
            }
        }
        if (tiers.isEmpty()) {
            tiers.put("netherite_coin", 10000);
            tiers.put("diamond_coin", 1000);
            tiers.put("gold_coin", 100);
            tiers.put("iron_coin", 10);
            tiers.put("copper_coin", 1);
        }

        // Sort descending by value
        List<Map.Entry<String, Integer>> sorted = new ArrayList<>(tiers.entrySet());
        sorted.sort((a, b) -> Integer.compare(b.getValue(), a.getValue()));

        Map<String, Integer> sortedMap = new LinkedHashMap<>();
        for (Map.Entry<String, Integer> entry : sorted) {
            sortedMap.put(entry.getKey(), entry.getValue());
        }
        return sortedMap;
    }

    public int getStackCoinValue(ItemStack item) {
        if (item == null || item.getType() == Material.AIR) return 0;

        String type = plugin.getConfig().getString("currency.type", "itemsadder").toLowerCase();
        String fallbackId = plugin.getConfig().getString("currency.currency-item", "copper_coin").toLowerCase();

        Map<String, Integer> tiers = getCoinTiers();

        if (type.equals("itemsadder")) {
            try {
                Class<?> customItemClass = Class.forName("dev.lone.itemsadder.api.CustomStack");
                var byItemStack = customItemClass.getMethod("byItemStack", ItemStack.class);
                Object customStack = byItemStack.invoke(null, item);
                if (customStack != null) {
                    var getId = customItemClass.getMethod("getId");
                    String fullId = ((String) getId.invoke(customStack)).toLowerCase();
                    String shortId = fullId.contains(":") ? fullId.substring(fullId.indexOf(":") + 1) : fullId;

                    if (tiers.containsKey(shortId)) {
                        return tiers.get(shortId);
                    }
                    if (tiers.containsKey(fullId)) {
                        return tiers.get(fullId);
                    }
                    if (shortId.equalsIgnoreCase(fallbackId) || fullId.equalsIgnoreCase(fallbackId)) {
                        return 1;
                    }
                }
            } catch (Exception ignored) {}
        } else if (type.equals("executableitems")) {
            try {
                Class<?> apiClass = Class.forName("com.ssamm.executableitems.ExecutableItemsAPI");
                var method = apiClass.getMethod("getExecutableItemsManager");
                Object mgr = method.invoke(null);
                var getExecutableItemMethod = mgr.getClass().getMethod("getExecutableItem", ItemStack.class);
                Object execItem = getExecutableItemMethod.invoke(mgr, item);
                if (execItem != null) {
                    var getIdMethod = execItem.getClass().getMethod("getId");
                    String id = ((String) getIdMethod.invoke(execItem)).toLowerCase();
                    if (tiers.containsKey(id)) {
                        return tiers.get(id);
                    }
                    if (id.equalsIgnoreCase(fallbackId)) {
                        return 1;
                    }
                }
            } catch (Exception ignored) {}
        }

        // Vanilla fallback: GOLD_NUGGET
        if (item.getType() == Material.GOLD_NUGGET) {
            return 1;
        }

        return 0;
    }

    public boolean isCurrencyItem(ItemStack item) {
        return getStackCoinValue(item) > 0;
    }

    public int getPlayerBalance(Player player) {
        int count = 0;
        for (ItemStack item : player.getInventory().getStorageContents()) {
            int unitVal = getStackCoinValue(item);
            if (unitVal > 0) {
                count += item.getAmount() * unitVal;
            }
        }
        return count;
    }

    public List<ItemStack> getCoinsBreakdown(int amount) {
        List<ItemStack> result = new ArrayList<>();
        if (amount <= 0) return result;

        String type = plugin.getConfig().getString("currency.type", "itemsadder").toLowerCase();
        Map<String, Integer> tiers = getCoinTiers();
        int remaining = amount;

        for (Map.Entry<String, Integer> entry : tiers.entrySet()) {
            String coinId = entry.getKey();
            int unitVal = entry.getValue();
            int count = remaining / unitVal;
            if (count > 0) {
                remaining %= unitVal;
                List<ItemStack> stacks = createCoinStacks(type, coinId, count);
                result.addAll(stacks);
            }
        }

        if (remaining > 0) {
            String fallbackId = plugin.getConfig().getString("currency.currency-item", "copper_coin");
            result.addAll(createCoinStacks(type, fallbackId, remaining));
        }

        return result;
    }

    private List<ItemStack> createCoinStacks(String type, String coinId, int amount) {
        List<ItemStack> result = new ArrayList<>();
        int remaining = amount;

        while (remaining > 0) {
            int stackSize = Math.min(remaining, 64);
            ItemStack stack = createSingleCoinStack(type, coinId, stackSize);
            if (stack != null) {
                result.add(stack);
            }
            remaining -= stackSize;
        }

        return result;
    }

    private ItemStack createSingleCoinStack(String type, String coinId, int amount) {
        if (type.equalsIgnoreCase("itemsadder")) {
            try {
                Class<?> customItemClass = Class.forName("dev.lone.itemsadder.api.CustomStack");
                var getInstanceMethod = customItemClass.getMethod("getInstance", String.class);
                Object customStack = getInstanceMethod.invoke(null, coinId);
                if (customStack != null) {
                    var getItemStackMethod = customItemClass.getMethod("getItemStack");
                    ItemStack stack = (ItemStack) getItemStackMethod.invoke(customStack);
                    stack.setAmount(amount);
                    return stack;
                }
            } catch (Exception ignored) {}
        }

        // Vanilla Gold Nugget fallback
        ItemStack coinItem = new ItemStack(Material.GOLD_NUGGET, amount);
        ItemMeta meta = coinItem.getItemMeta();
        if (meta != null) {
            meta.displayName(net.kyori.adventure.text.Component.text(getCurrencyName()));
            coinItem.setItemMeta(meta);
        }
        return coinItem;
    }

    public boolean giveCurrency(Player player, int amount) {
        if (amount <= 0) return true;
        List<ItemStack> coins = getCoinsBreakdown(amount);
        for (ItemStack coin : coins) {
            player.getInventory().addItem(coin);
        }
        return true;
    }

    public boolean removeCurrency(Player player, int amount) {
        if (amount <= 0) return true;
        if (getPlayerBalance(player) < amount) {
            return false;
        }

        int remaining = amount;
        ItemStack[] storage = player.getInventory().getStorageContents();

        // Collect all coin slots
        List<Integer> coinSlots = new ArrayList<>();
        for (int i = 0; i < storage.length; i++) {
            if (getStackCoinValue(storage[i]) > 0) {
                coinSlots.add(i);
            }
        }

        // Sort slots: smallest unit value first to minimize change, or largest first
        coinSlots.sort(Comparator.comparingInt(slot -> getStackCoinValue(storage[slot])));

        for (int slot : coinSlots) {
            ItemStack stack = storage[slot];
            if (stack == null || stack.getType() == Material.AIR) continue;

            int unitVal = getStackCoinValue(stack);
            if (unitVal <= 0) continue;

            int stackTotalVal = stack.getAmount() * unitVal;

            if (stackTotalVal <= remaining) {
                remaining -= stackTotalVal;
                player.getInventory().setItem(slot, null);
            } else {
                // Stack total value > remaining
                int itemsNeeded = (int) Math.ceil((double) remaining / unitVal);
                int itemsValue = itemsNeeded * unitVal;
                int change = itemsValue - remaining;

                int newAmount = stack.getAmount() - itemsNeeded;
                if (newAmount > 0) {
                    stack.setAmount(newAmount);
                } else {
                    player.getInventory().setItem(slot, null);
                }

                remaining = 0;
                if (change > 0) {
                    giveCurrency(player, change);
                }
                break;
            }

            if (remaining <= 0) break;
        }

        return remaining == 0;
    }

    public boolean canFitCurrency(Player player, int coinsToGive, ItemStack itemToBeRemoved) {
        if (coinsToGive <= 0) return true;

        ItemStack[] storage = player.getInventory().getStorageContents().clone();
        for (int i = 0; i < storage.length; i++) {
            if (storage[i] != null) {
                storage[i] = storage[i].clone();
            }
        }

        // Simulate removing itemToBeRemoved from inventory
        if (itemToBeRemoved != null) {
            int toRemove = itemToBeRemoved.getAmount();
            for (int i = 0; i < storage.length; i++) {
                if (storage[i] != null && storage[i].isSimilar(itemToBeRemoved)) {
                    int count = storage[i].getAmount();
                    if (count <= toRemove) {
                        toRemove -= count;
                        storage[i] = null;
                    } else {
                        storage[i].setAmount(count - toRemove);
                        toRemove = 0;
                        break;
                    }
                    if (toRemove <= 0) break;
                }
            }
        }

        List<ItemStack> coinStacks = getCoinsBreakdown(coinsToGive);
        return simulateAddItems(storage, coinStacks);
    }

    public boolean canFitItem(Player player, ItemStack itemToGive, int currencyToDeduct) {
        if (itemToGive == null) return true;

        ItemStack[] storage = player.getInventory().getStorageContents().clone();
        for (int i = 0; i < storage.length; i++) {
            if (storage[i] != null) {
                storage[i] = storage[i].clone();
            }
        }

        // Simulate deducting currency from storage
        simulateRemoveCurrency(storage, currencyToDeduct);

        List<ItemStack> toAdd = new ArrayList<>();
        toAdd.add(itemToGive.clone());
        return simulateAddItems(storage, toAdd);
    }

    private void simulateRemoveCurrency(ItemStack[] storage, int amount) {
        if (amount <= 0) return;
        int remaining = amount;

        List<Integer> coinSlots = new ArrayList<>();
        for (int i = 0; i < storage.length; i++) {
            if (getStackCoinValue(storage[i]) > 0) {
                coinSlots.add(i);
            }
        }
        coinSlots.sort(Comparator.comparingInt(slot -> getStackCoinValue(storage[slot])));

        for (int slot : coinSlots) {
            ItemStack stack = storage[slot];
            if (stack == null || stack.getType() == Material.AIR) continue;
            int unitVal = getStackCoinValue(stack);
            if (unitVal <= 0) continue;

            int stackTotalVal = stack.getAmount() * unitVal;
            if (stackTotalVal <= remaining) {
                remaining -= stackTotalVal;
                storage[slot] = null;
            } else {
                int itemsNeeded = (int) Math.ceil((double) remaining / unitVal);
                int itemsValue = itemsNeeded * unitVal;
                int change = itemsValue - remaining;
                int newAmount = stack.getAmount() - itemsNeeded;
                if (newAmount > 0) {
                    stack.setAmount(newAmount);
                } else {
                    storage[slot] = null;
                }
                remaining = 0;
                if (change > 0) {
                    List<ItemStack> changeStacks = getCoinsBreakdown(change);
                    simulateAddItems(storage, changeStacks);
                }
                break;
            }
            if (remaining <= 0) break;
        }
    }

    private boolean simulateAddItems(ItemStack[] storage, List<ItemStack> items) {
        for (ItemStack item : items) {
            if (item == null || item.getType() == Material.AIR) continue;
            int remainingAmount = item.getAmount();

            // 1. Try to stack with existing matching items
            for (int i = 0; i < storage.length; i++) {
                if (storage[i] != null && storage[i].isSimilar(item)) {
                    int maxStack = storage[i].getMaxStackSize();
                    int space = maxStack - storage[i].getAmount();
                    if (space > 0) {
                        int add = Math.min(space, remainingAmount);
                        storage[i].setAmount(storage[i].getAmount() + add);
                        remainingAmount -= add;
                        if (remainingAmount <= 0) break;
                    }
                }
            }

            // 2. Put remaining in first empty slot
            if (remainingAmount > 0) {
                for (int i = 0; i < storage.length; i++) {
                    if (storage[i] == null || storage[i].getType() == Material.AIR) {
                        int maxStack = item.getMaxStackSize();
                        int add = Math.min(maxStack, remainingAmount);
                        ItemStack newStack = item.clone();
                        newStack.setAmount(add);
                        storage[i] = newStack;
                        remainingAmount -= add;
                        if (remainingAmount <= 0) break;
                    }
                }
            }

            if (remainingAmount > 0) {
                return false; // Storage full!
            }
        }
        return true;
    }
}

