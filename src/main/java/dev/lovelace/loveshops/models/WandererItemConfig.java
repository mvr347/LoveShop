package dev.lovelace.loveshops.models;

import dev.lovelace.loveshops.utils.MessageUtils;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.List;
import java.util.Map;

public record WandererItemConfig(
    String id,
    String material,
    String name,
    List<String> lore,
    Map<String, Integer> enchants,
    int price,
    int amount,
    int weight,
    Integer customModelData,
    String itemsAdderId
) {
    public ItemStack buildItemStack() {
        Material mat = Material.matchMaterial(material != null ? material : "STONE");
        if (mat == null) mat = Material.STONE;

        ItemStack stack = new ItemStack(mat, Math.max(1, amount));
        ItemMeta meta = stack.getItemMeta();
        if (meta != null) {
            if (name != null && !name.isEmpty()) {
                meta.displayName(MessageUtils.parse(name));
            }
            if (lore != null && !lore.isEmpty()) {
                meta.lore(lore.stream().map(MessageUtils::parse).toList());
            }
            if (customModelData != null && customModelData > 0) {
                meta.setCustomModelData(customModelData);
            }
            if (enchants != null) {
                for (Map.Entry<String, Integer> entry : enchants.entrySet()) {
                    try {
                        Enchantment ench = Enchantment.getByKey(NamespacedKey.minecraft(entry.getKey().toLowerCase()));
                        if (ench != null) {
                            meta.addEnchant(ench, entry.getValue(), true);
                        }
                    } catch (Exception ignored) {}
                }
            }
            stack.setItemMeta(meta);
        }
        return stack;
    }
}
