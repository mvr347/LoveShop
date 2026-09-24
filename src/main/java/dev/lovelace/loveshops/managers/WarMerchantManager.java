package dev.lovelace.loveshops.managers;

import dev.lovelace.lovecore.api.LoveCore;
import dev.lovelace.lovecore.api.economy.LoveEconomy;
import dev.lovelace.lovecore.api.social.BehaviorLevels;
import dev.lovelace.loveshops.LoveShops;
import dev.lovelace.loveshops.utils.MessageUtils;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Военный торговец - НПС, товары которого доступны только игрокам с достаточно
 * агрессивным стилем игры (LoveCore.BehaviorLevels, ставит LoveBehavior). Как и остальная
 * агрессивная механика в экосистеме (см. LoveClans CombatListener/ClanManager), "агрессивный"
 * значит НИЗКУЮ ступень playstyleLevel (0 - максимально агрессивный, MAX_LEVEL - максимально
 * миролюбивый) - порог задаётся в конфиге, а не жёстко.
 *
 * <p>Без LoveBehavior (сервис недоступен) доступа нет ни у кого - это не обход гейта отключением
 * плагина поведения, а осознанно закрытое по умолчанию состояние.</p>
 */
public class WarMerchantManager {

    public record MerchantItem(ItemStack display, int price) {}

    private final LoveShops plugin;

    public WarMerchantManager(LoveShops plugin) {
        this.plugin = plugin;
    }

    public boolean isEnabled() {
        return plugin.getConfig().getBoolean("war-merchant.enabled", true);
    }

    public boolean isEligible(UUID playerId) {
        if (!isEnabled()) return false;
        int threshold = plugin.getConfig().getInt("war-merchant.playstyle-threshold", 0);
        return LoveCore.service(BehaviorLevels.class)
                .map(levels -> levels.playstyleLevel(playerId) <= threshold)
                .orElse(false);
    }

    public List<MerchantItem> getItems() {
        List<MerchantItem> items = new ArrayList<>();
        for (Map<?, ?> entry : plugin.getConfig().getMapList("war-merchant.items")) {
            try {
                Material material = Material.valueOf(String.valueOf(entry.get("material")).toUpperCase());
                int price = entry.get("price") instanceof Number number ? number.intValue() : 0;

                ItemStack item = new ItemStack(material);
                Object enchantsRaw = entry.get("enchantments");
                if (enchantsRaw instanceof Map<?, ?> enchantMap && !enchantMap.isEmpty()) {
                    ItemMeta meta = item.getItemMeta();
                    for (Map.Entry<?, ?> enchEntry : enchantMap.entrySet()) {
                        Enchantment enchantment = Registry.ENCHANTMENT.get(
                                NamespacedKey.minecraft(String.valueOf(enchEntry.getKey()).toLowerCase()));
                        int level = enchEntry.getValue() instanceof Number lvl ? lvl.intValue() : 1;
                        if (enchantment != null) {
                            meta.addEnchant(enchantment, level, true);
                        } else {
                            plugin.getLogger().warning("war-merchant.items: неизвестное зачарование '" + enchEntry.getKey() + "'");
                        }
                    }
                    item.setItemMeta(meta);
                }

                items.add(new MerchantItem(item, price));
            } catch (IllegalArgumentException e) {
                plugin.getLogger().warning("war-merchant.items: некорректная запись " + entry + " (" + e.getMessage() + ")");
            }
        }
        return items;
    }

    /**
     * Синхронная покупка (весь ассортимент статичен и живёт в config.yml, без БД/async
     * раунд-трипа как у SellerManager) - право доступа и цена всегда пересчитываются из
     * канонического списка по индексу, а не берутся из лора кликнутого предмета в инвентаре.
     */
    public boolean purchase(Player player, int index) {
        if (!isEligible(player.getUniqueId())) {
            MessageUtils.sendMessage(player, randomDenyMessage());
            return false;
        }

        List<MerchantItem> items = getItems();
        if (index < 0 || index >= items.size()) {
            return false;
        }
        MerchantItem merchantItem = items.get(index);

        // merchant-tax (config.yml): same flat economy-sink markup as Buyer/Seller — see
        // PriceCalculator#applyMerchantTaxToCost. Wanderer never goes through this.
        long taxedPrice = plugin.getPriceCalculator().applyMerchantTaxToCost(merchantItem.price());

        LoveEconomy economy = plugin.getEconomy().orElse(null);
        if (economy == null || !economy.has(player, taxedPrice)) {
            MessageUtils.sendMessage(player, MessageUtils.currencyIcon() + plugin.getConfig().getString("protection.insufficient-funds", "&cНедостаточно средств!"));
            return false;
        }
        if (!economy.charge(player, taxedPrice)) {
            MessageUtils.sendMessage(player, MessageUtils.currencyIcon() + plugin.getConfig().getString("protection.insufficient-funds", "&cНедостаточно средств!"));
            return false;
        }

        ItemStack toGive = merchantItem.display().clone();
        for (ItemStack extra : player.getInventory().addItem(toGive).values()) {
            player.getWorld().dropItemNaturally(player.getLocation(), extra);
        }
        MessageUtils.sendMessage(player, plugin.getLangManager().getRaw("gui.item-bought", "&aПокупка успешна!"));
        return true;
    }

    public String randomDenyMessage() {
        List<String> messages = plugin.getConfig().getStringList("war-merchant.messages.deny");
        if (messages.isEmpty()) {
            return "&cВоенный торговец: Тебе тут делать нечего, миролюбивый. Проваливай.";
        }
        return messages.get((int) (Math.random() * messages.size()));
    }
}
