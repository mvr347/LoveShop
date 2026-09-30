package dev.lovelace.loveshops.market;

import dev.lovelace.lovecore.api.economy.LoveEconomy;
import dev.lovelace.loveshops.LoveShops;
import dev.lovelace.loveshops.market.MarketRepository.ReturnEntry;
import dev.lovelace.loveshops.utils.ItemStackConverter;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.sql.SQLException;
import java.util.List;

/**
 * Hands players what the market owes them (goods and till of a finished rent, overflow of a trade).
 * For every entry the database row is reduced or deleted FIRST and only then the items or coins are
 * given, so a failure can lose nothing and duplicate nothing; what does not fit stays for next time.
 */
public final class ReturnsService {

    /** What one {@link #claimAll} did. */
    public record Summary(int items, long coins, int itemsLeft, int coinEntriesLeft) {
        public boolean anyDelivered() { return items > 0 || coins > 0; }
        public boolean anyLeft() { return itemsLeft > 0 || coinEntriesLeft > 0; }
    }

    private final LoveShops plugin;
    private final MarketRepository repo;

    public ReturnsService(LoveShops plugin, MarketRepository repo) {
        this.plugin = plugin;
        this.repo = repo;
    }

    public Summary claimAll(Player player) {
        LoveEconomy eco = plugin.getEconomy().orElse(null);
        int items = 0;
        long coins = 0;
        int itemsLeft = 0;
        int coinEntriesLeft = 0;
        List<ReturnEntry> entries;
        try {
            entries = repo.returnsFor(player.getUniqueId());
        } catch (SQLException e) {
            plugin.getLogger().warning("Возвраты игрока " + player.getName() + " не прочитаны: " + e.getMessage());
            return new Summary(0, 0, 0, 0);
        }
        for (ReturnEntry entry : entries) {
            try {
                if (entry.isCoins()) {
                    if (eco == null || !eco.canFit(player, entry.amount())) {
                        coinEntriesLeft++;
                        continue;
                    }
                    repo.inTransaction(conn -> {
                        repo.deleteReturn(conn, entry.id());
                        return null;
                    });
                    eco.give(player, entry.amount());
                    coins += entry.amount();
                } else {
                    ItemStack template = ItemStackConverter.itemStackFromBase64(entry.itemData());
                    if (template == null) {
                        plugin.getLogger().warning("Возврат #" + entry.id() + " с повреждённым предметом пропущен.");
                        continue;
                    }
                    long amount = entry.amount();
                    int fit = (int) Math.min(amount, ItemTransfer.capacity(player, template));
                    if (fit <= 0) {
                        itemsLeft += (int) Math.min(Integer.MAX_VALUE, amount);
                        continue;
                    }
                    final long remaining = amount - fit;
                    repo.inTransaction(conn -> {
                        if (remaining <= 0) repo.deleteReturn(conn, entry.id());
                        else repo.updateReturnAmount(conn, entry.id(), remaining, null);
                        return null;
                    });
                    int notGiven = ItemTransfer.give(player, template, fit);
                    if (notGiven > 0) {
                        repo.inTransaction(conn -> {
                            repo.addReturnItem(conn, player.getUniqueId(), template, notGiven, "returns-overflow");
                            return null;
                        });
                    }
                    items += fit - notGiven;
                    itemsLeft += (int) Math.min(Integer.MAX_VALUE, remaining + notGiven);
                }
            } catch (SQLException | RuntimeException e) {
                plugin.getLogger().warning("Возврат #" + entry.id() + " игроку " + player.getName() + " не выдан: " + e.getMessage());
            }
        }
        return new Summary(items, coins, itemsLeft, coinEntriesLeft);
    }
}
