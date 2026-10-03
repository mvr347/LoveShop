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

    private static final java.util.Map<String, String> RUSSIAN_NAMES = new java.util.HashMap<>();
    static {
        // Древесина и доски
        RUSSIAN_NAMES.put("OAK_LOG", "Дубовое бревно");
        RUSSIAN_NAMES.put("SPRUCE_LOG", "Еловое бревно");
        RUSSIAN_NAMES.put("BIRCH_LOG", "Берёзовое бревно");
        RUSSIAN_NAMES.put("JUNGLE_LOG", "Бревно тропического дерева");
        RUSSIAN_NAMES.put("ACACIA_LOG", "Акациевое бревно");
        RUSSIAN_NAMES.put("DARK_OAK_LOG", "Бревно тёмного дуба");
        RUSSIAN_NAMES.put("MANGROVE_LOG", "Мангровое бревно");
        RUSSIAN_NAMES.put("CHERRY_LOG", "Вишнёвое бревно");
        RUSSIAN_NAMES.put("OAK_WOOD", "Дубовая древесина");
        RUSSIAN_NAMES.put("SPRUCE_WOOD", "Еловая древесина");
        RUSSIAN_NAMES.put("BIRCH_WOOD", "Берёзовая древесина");
        RUSSIAN_NAMES.put("OAK_PLANKS", "Дубовые доски");
        RUSSIAN_NAMES.put("SPRUCE_PLANKS", "Еловые доски");
        RUSSIAN_NAMES.put("BIRCH_PLANKS", "Берёзовые доски");
        RUSSIAN_NAMES.put("JUNGLE_PLANKS", "Доски тропического дерева");
        RUSSIAN_NAMES.put("ACACIA_PLANKS", "Акациевые доски");
        RUSSIAN_NAMES.put("DARK_OAK_PLANKS", "Доски тёмного дуба");
        RUSSIAN_NAMES.put("MANGROVE_PLANKS", "Мангровые доски");
        RUSSIAN_NAMES.put("CHERRY_PLANKS", "Вишнёвые доски");
        // Камень и строительные блоки
        RUSSIAN_NAMES.put("STONE", "Камень");
        RUSSIAN_NAMES.put("COBBLESTONE", "Булыжник");
        RUSSIAN_NAMES.put("DEEPSLATE", "Глубинный сланец");
        RUSSIAN_NAMES.put("COBBLED_DEEPSLATE", "Дроблёный сланец");
        RUSSIAN_NAMES.put("ANDESITE", "Андезит");
        RUSSIAN_NAMES.put("DIORITE", "Диорит");
        RUSSIAN_NAMES.put("GRANITE", "Гранит");
        RUSSIAN_NAMES.put("SANDSTONE", "Песчаник");
        RUSSIAN_NAMES.put("RED_SANDSTONE", "Красный песчаник");
        RUSSIAN_NAMES.put("NETHERRACK", "Незерак");
        RUSSIAN_NAMES.put("END_STONE", "Эндерняк");
        RUSSIAN_NAMES.put("OBSIDIAN", "Обсидиан");
        RUSSIAN_NAMES.put("CRYING_OBSIDIAN", "Плачущий обсидиан");
        RUSSIAN_NAMES.put("GLASS", "Стекло");
        RUSSIAN_NAMES.put("DIRT", "Земля");
        RUSSIAN_NAMES.put("SAND", "Песок");
        RUSSIAN_NAMES.put("GRAVEL", "Гравий");
        RUSSIAN_NAMES.put("CLAY", "Глина");
        // Минералы и руды
        RUSSIAN_NAMES.put("COAL", "Уголь");
        RUSSIAN_NAMES.put("CHARCOAL", "Древесный уголь");
        RUSSIAN_NAMES.put("RAW_IRON", "Руда железа");
        RUSSIAN_NAMES.put("IRON_INGOT", "Железный слиток");
        RUSSIAN_NAMES.put("IRON_BLOCK", "Железный блок");
        RUSSIAN_NAMES.put("RAW_GOLD", "Руда золота");
        RUSSIAN_NAMES.put("GOLD_INGOT", "Золотой слиток");
        RUSSIAN_NAMES.put("GOLD_BLOCK", "Золотой блок");
        RUSSIAN_NAMES.put("RAW_COPPER", "Руда меди");
        RUSSIAN_NAMES.put("COPPER_INGOT", "Медный слиток");
        RUSSIAN_NAMES.put("COPPER_BLOCK", "Медный блок");
        RUSSIAN_NAMES.put("DIAMOND", "Алмаз");
        RUSSIAN_NAMES.put("DIAMOND_BLOCK", "Алмазный блок");
        RUSSIAN_NAMES.put("EMERALD", "Изумруд");
        RUSSIAN_NAMES.put("EMERALD_BLOCK", "Изумрудный блок");
        RUSSIAN_NAMES.put("LAPIS_LAZULI", "Лазурит");
        RUSSIAN_NAMES.put("LAPIS_BLOCK", "Лазуритовый блок");
        RUSSIAN_NAMES.put("REDSTONE", "Редстоун");
        RUSSIAN_NAMES.put("REDSTONE_BLOCK", "Редстоун блок");
        RUSSIAN_NAMES.put("NETHERITE_INGOT", "Незеритовый слиток");
        RUSSIAN_NAMES.put("NETHERITE_SCRAP", "Незеритовый обломок");
        RUSSIAN_NAMES.put("NETHERITE_BLOCK", "Незеритовый блок");
        RUSSIAN_NAMES.put("QUARTZ", "Кварц");
        RUSSIAN_NAMES.put("AMETHYST_SHARD", "Осколок аметиста");
        // Фермерство и еда
        RUSSIAN_NAMES.put("WHEAT", "Пшеница");
        RUSSIAN_NAMES.put("CARROT", "Морковь");
        RUSSIAN_NAMES.put("POTATO", "Картофель");
        RUSSIAN_NAMES.put("BEETROOT", "Свёкла");
        RUSSIAN_NAMES.put("BREAD", "Хлеб");
        RUSSIAN_NAMES.put("APPLE", "Яблоко");
        RUSSIAN_NAMES.put("GOLDEN_APPLE", "Золотое яблоко");
        RUSSIAN_NAMES.put("ENCHANTED_GOLDEN_APPLE", "Зачарованное золотое яблоко");
        RUSSIAN_NAMES.put("PUMPKIN", "Тыква");
        RUSSIAN_NAMES.put("MELON_SLICE", "Ломтик арбуза");
        RUSSIAN_NAMES.put("SWEET_BERRIES", "Сладкие ягоды");
        RUSSIAN_NAMES.put("GLOW_BERRIES", "Светящиеся ягоды");
        RUSSIAN_NAMES.put("COOKED_BEEF", "Жареная говядина");
        RUSSIAN_NAMES.put("COOKED_PORKCHOP", "Жареная свинина");
        RUSSIAN_NAMES.put("COOKED_MUTTON", "Жареная баранина");
        RUSSIAN_NAMES.put("COOKED_CHICKEN", "Жареная курятина");
        RUSSIAN_NAMES.put("BEEF", "Сырая говядина");
        RUSSIAN_NAMES.put("PORKCHOP", "Сырая свинина");
        RUSSIAN_NAMES.put("MUTTON", "Сырая баранина");
        RUSSIAN_NAMES.put("CHICKEN", "Сырая курятина");
        RUSSIAN_NAMES.put("SUGAR_CANE", "Сахарный тростник");
        RUSSIAN_NAMES.put("LEATHER", "Кожа");
        RUSSIAN_NAMES.put("FEATHER", "Перо");
        RUSSIAN_NAMES.put("STRING", "Нить");
        RUSSIAN_NAMES.put("BONE", "Кость");
        RUSSIAN_NAMES.put("GUNPOWDER", "Порох");
        RUSSIAN_NAMES.put("BLAZE_ROD", "Стержень ифрита");
        RUSSIAN_NAMES.put("ENDER_PEARL", "Жемчуг Энда");
        RUSSIAN_NAMES.put("SLIME_BALL", "Сгусток слизи");
        RUSSIAN_NAMES.put("TOTEM_OF_UNDYING", "Тотем бессмертия");
        RUSSIAN_NAMES.put("EXPERIENCE_BOTTLE", "Пузырёк опыта");
        RUSSIAN_NAMES.put("ARROW", "Стрела");
        RUSSIAN_NAMES.put("BOW", "Лук");
        RUSSIAN_NAMES.put("CROSSBOW", "Арбалет");
        RUSSIAN_NAMES.put("BOOK", "Книга");
        RUSSIAN_NAMES.put("PAPER", "Бумага");
    }

    public static String getFriendlyRussianName(@Nullable String itemId) {
        if (itemId == null || itemId.isBlank()) return "Неизвестный предмет";
        String raw = itemId.trim();
        ItemStack is = resolveItemStack(raw, 1);
        if (is != null && is.hasItemMeta() && is.getItemMeta().hasDisplayName()) {
            return net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText().serialize(is.getItemMeta().displayName());
        }
        String clean = raw.toUpperCase(Locale.ROOT);
        if (clean.startsWith("MINECRAFT:")) clean = clean.substring(10);
        String name = RUSSIAN_NAMES.get(clean);
        if (name != null) return name;
        String[] parts = clean.split("_");
        StringBuilder sb = new StringBuilder();
        for (String p : parts) {
            if (p.isEmpty()) continue;
            if (sb.length() > 0) sb.append(" ");
            sb.append(Character.toUpperCase(p.charAt(0))).append(p.substring(1).toLowerCase(Locale.ROOT));
        }
        return sb.toString();
    }
}
