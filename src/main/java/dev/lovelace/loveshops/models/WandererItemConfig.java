package dev.lovelace.loveshops.models;

import dev.lovelace.loveshops.utils.MessageUtils;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.Damageable;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Random;

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
    String itemsAdderId,
    /**
     * One of {@link dev.lovelace.loveshops.models.WandererRequestCategory}'s names, or
     * {@code null}/unrecognized to fall back to {@code RARE} — which of the paid personal
     * request categories (Инструменты/Броня/Зачарования/Редкие предметы) this pool entry
     * belongs to. Defaults to RARE so existing items-pool entries written before this field
     * existed keep working without a config edit.
     */
    String category
) {
    public WandererRequestCategory resolvedCategory() {
        return WandererRequestCategory.fromConfigValue(category);
    }

    public ItemStack buildItemStack() {
        return buildItemStack(WandererItemQuality.DISABLED, new Random());
    }

    /**
     * @param quality durability-wear / enchant-thinning rolls (see {@link WandererItemQuality});
     *                pass {@link WandererItemQuality#DISABLED} to always build the pristine,
     *                fully-enchanted item exactly as configured.
     * @param random  shared per-roll RNG (not a fresh one per item) so a whole deal doesn't
     *                spend identical seeds if called in a tight loop.
     */
    public ItemStack buildItemStack(WandererItemQuality quality, Random random) {
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

            applyEnchants(meta, quality, random);

            // Реалистичный износ: предмет, который Странник "нашёл" или "добыл", не должен
            // всегда приходить в идеальном состоянии — это выдаёт его как сгенерированный
            // только что, а не как честную добычу. Roll — независимо от зачарований.
            if (quality.durabilityWearEnabled() && meta instanceof Damageable damageable
                    && mat.getMaxDurability() > 0 && random.nextDouble() >= quality.pristineChance()) {
                double wearPercent = quality.minWearPercent()
                        + random.nextDouble() * Math.max(0, quality.maxWearPercent() - quality.minWearPercent());
                int damage = (int) Math.round(mat.getMaxDurability() * (wearPercent / 100.0));
                damageable.setDamage(Math.max(0, Math.min(mat.getMaxDurability() - 1, damage)));
            }

            stack.setItemMeta(meta);
        }
        return stack;
    }

    private void applyEnchants(ItemMeta meta, WandererItemQuality quality, Random random) {
        if (enchants == null || enchants.isEmpty()) return;

        Map<String, Integer> toApply = enchants;
        if (quality.enchantQualityEnabled()) {
            double roll = random.nextDouble();
            if (roll >= quality.fullEnchantChance() + quality.partialEnchantChance()) {
                // Ни одного зачарования на этот конкретный ролл товара.
                return;
            } else if (roll >= quality.fullEnchantChance()) {
                // Частичный набор — оставляем только сильнейшую половину (минимум одно),
                // чтобы даже "неполный" ролл товара Странника был лучше рядового дропа.
                List<Map.Entry<String, Integer>> sorted = new ArrayList<>(enchants.entrySet());
                sorted.sort(Comparator.<Map.Entry<String, Integer>>comparingInt(Map.Entry::getValue).reversed());
                int keep = Math.max(1, (int) Math.ceil(sorted.size() / 2.0));
                toApply = new java.util.LinkedHashMap<>();
                for (int i = 0; i < keep; i++) {
                    toApply.put(sorted.get(i).getKey(), sorted.get(i).getValue());
                }
            }
        }

        for (Map.Entry<String, Integer> entry : toApply.entrySet()) {
            try {
                Enchantment ench = Enchantment.getByKey(NamespacedKey.minecraft(entry.getKey().toLowerCase()));
                if (ench != null) {
                    meta.addEnchant(ench, entry.getValue(), true);
                }
            } catch (Exception ignored) {}
        }
    }
}
