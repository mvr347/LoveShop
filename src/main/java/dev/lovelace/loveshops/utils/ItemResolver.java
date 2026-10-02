package dev.lovelace.loveshops.utils;

import dev.lovelace.lovecore.api.economy.Denomination;
import dev.lovelace.lovecore.api.economy.LoveEconomy;
import dev.lovelace.loveshops.LoveShops;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.Nullable;

import java.lang.reflect.Method;
import java.util.Locale;

/**
 * Универсальный резолвер предметов: поддерживает стандартные материалы Bukkit,
 * кастомные предметы ItemsAdder (через рефлексию) и монеты LoveEconomy.
 */
public final class ItemResolver {

    private static boolean iaChecked = false;
    private static Method iaGetInstance = null;
    private static Method iaGetItemStack = null;
    private static Method iaByItemStack = null;
    private static Method iaGetNamespacedId = null;

    private ItemResolver() {}

    private static synchronized void initItemsAdder() {
        if (iaChecked) return;
        iaChecked = true;
        if (!Bukkit.getPluginManager().isPluginEnabled("ItemsAdder")) return;

        String[] candidates = {
                "dev.beer.itemsadder.api.CustomStack",
                "beer.devs.itemsadder.api.CustomStack",
                "dev.lone.itemsadder.api.CustomStack"
        };
        for (String candidate : candidates) {
            try {
                Class<?> clazz = Class.forName(candidate);
                iaGetInstance = clazz.getMethod("getInstance", String.class);
                iaGetItemStack = clazz.getMethod("getItemStack");
                iaByItemStack = clazz.getMethod("byItemStack", ItemStack.class);
                iaGetNamespacedId = clazz.getMethod("getNamespacedID");
                if (iaGetInstance != null && iaGetItemStack != null) {
                    break;
                }
            } catch (Throwable ignored) {
                iaGetInstance = null;
                iaGetItemStack = null;
                iaByItemStack = null;
                iaGetNamespacedId = null;
            }
        }
    }

    /**
     * Создаёт ItemStack по строковому идентификатору:
     * - "OAK_LOG" или "minecraft:oak_log" -> ванильный предмет
     * - "itemsadder:custom_item" или "namespace:item" -> ItemsAdder CustomStack
     * - "lovecore:iron_coin" -> монета из LoveEconomy
     */
    public static ItemStack resolveItemStack(@Nullable String id, int amount) {
        int count = Math.max(1, amount);
        if (id == null || id.isBlank()) {
            return new ItemStack(Material.CHEST, count);
        }

        String raw = id.trim();

        // 1. Попытка LoveCore Coin
        if (raw.toLowerCase(Locale.ROOT).startsWith("lovecore:")) {
            LoveEconomy eco = LoveShops.getInstance() != null ? LoveShops.getInstance().getEconomy().orElse(null) : null;
            if (eco != null) {
                for (Denomination den : eco.denominations()) {
                    if (den.itemId() != null && den.itemId().equalsIgnoreCase(raw)) {
                        ItemStack is = resolveItemsAdder(den.itemId());
                        if (is != null) {
                            is.setAmount(count);
                            return is;
                        }
                    }
                }
            }
        }

        // 2. Попытка ItemsAdder CustomStack
        if (raw.contains(":") && !raw.toLowerCase(Locale.ROOT).startsWith("minecraft:")) {
            ItemStack is = resolveItemsAdder(raw);
            if (is != null) {
                is.setAmount(count);
                return is;
            }
        }

        // 3. Ванильный Material
        String cleanMat = raw;
        if (cleanMat.toLowerCase(Locale.ROOT).startsWith("minecraft:")) {
            cleanMat = cleanMat.substring(10);
        }
        Material mat = Material.matchMaterial(cleanMat.toUpperCase(Locale.ROOT));
        if (mat != null && !mat.isAir()) {
            return new ItemStack(mat, count);
        }

        // Фолбэк на ItemsAdder (если id без двоеточия, но кастомный)
        ItemStack fallbackIa = resolveItemsAdder(raw);
        if (fallbackIa != null) {
            fallbackIa.setAmount(count);
            return fallbackIa;
        }

        return new ItemStack(Material.CHEST, count);
    }

    @Nullable
    private static ItemStack resolveItemsAdder(String id) {
        initItemsAdder();
        if (iaGetInstance == null || iaGetItemStack == null) return null;
        try {
            Object customStack = iaGetInstance.invoke(null, id);
            if (customStack != null) {
                ItemStack is = (ItemStack) iaGetItemStack.invoke(customStack);
                if (is != null) return is.clone();
            }
        } catch (Throwable ignored) {}
        return null;
    }

    /**
     * Проверяет, совпадает ли предмет в руке / инвентаре с требуемым ID.
     */
    public static boolean matches(@Nullable ItemStack item, @Nullable String targetId) {
        if (item == null || item.getType().isAir() || targetId == null || targetId.isBlank()) return false;

        String raw = targetId.trim();

        // Проверка через ItemsAdder CustomStack
        initItemsAdder();
        if (iaByItemStack != null && iaGetNamespacedId != null) {
            try {
                Object customStack = iaByItemStack.invoke(null, item);
                if (customStack != null) {
                    String namespacedId = (String) iaGetNamespacedId.invoke(customStack);
                    if (namespacedId != null && (namespacedId.equalsIgnoreCase(raw) || raw.equalsIgnoreCase("itemsadder:" + namespacedId))) {
                        return true;
                    }
                }
            } catch (Throwable ignored) {}
        }

        // Проверка по ванильному материалу
        String cleanMat = raw;
        if (cleanMat.toLowerCase(Locale.ROOT).startsWith("minecraft:")) {
            cleanMat = cleanMat.substring(10);
        }
        Material mat = Material.matchMaterial(cleanMat.toUpperCase(Locale.ROOT));
        return mat != null && item.getType() == mat;
    }
}
