package dev.lovelace.loveshops.textures;

/**
 * Централизованное хранилище base64 текстур голов (skull textures), используемых в GUI LoveShops.
 * <p>
 * Все base64-литералы текстур голов должны объявляться здесь, а не хардкодиться по месту
 * использования — так плагин следует единой точке правды для GUI-текстур, вместо дублирования
 * одних и тех же строк в разных классах.
 */
public final class HeadTextures {

    private HeadTextures() {
        // Утилитарный класс-константа, инстанцирование не предполагается
    }

    /**
     * Кнопка «закрыть» в меню Buyer/Seller/Auction GUI.
     */
    public static final String BUTTON_CLOSE =
            HeadsConfig.get("button-close", "eyJ0ZXh0dXJlcyI6eyJTS0lOIjp7InVybCI6Imh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUvM2VkMWFiYTczZjYzOWY0YmM0MmJkNDgxOTZjNzE1MTk3YmUyNzEyYzNiOTYyYzk3ZWJmOWU5ZWQ4ZWZhMDI1In19fQ==");

    /**
     * Кнопка «назад» для навигации по GUI.
     */
    public static final String BUTTON_BACK =
            HeadsConfig.get("button-back", "eyJ0ZXh0dXJlcyI6eyJTS0lOIjp7InVybCI6Imh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUvYmQ2OWUwNmU1ZGFkZmQ4NGU1ZjNkMWMyMTA2M2YyNTUzYjJmYTk0NWVlMWQ0ZDcxNTJmZGM1NDI1YmMxMmE5In19fQ==");

    /**
     * Иконка вкладки Buyer (скупщик) для переключения между меню GUI.
     */
    public static final String TAB_BUYER =
            HeadsConfig.get("tab-buyer", "eyJ0ZXh0dXJlcyI6eyJTS0lOIjp7InVybCI6Imh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUvZWZiNmEzZDdkYmE5N2JiNmU3Zjc5YTE1NjI3YWVjNjM2OTc5MTIzM2Y4MzNmYTc0OWVmMjFiZWQ3OWU5OCJ9fX0=");

    /**
     * Иконка вкладки Seller (барахолка) для переключения между меню GUI.
     */
    public static final String TAB_SELLER =
            HeadsConfig.get("tab-seller", "eyJ0ZXh0dXJlcyI6eyJTS0lOIjp7InVybCI6Imh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUvZmM2MjExMGQ4MTg4NDQxZDIxNzk0NDM0ZjY3ZDEyYTAyMWI3NDAyYzhkYWE0MmQ0ZmVhMzIzZTdlMTllMGJiNyJ9fX0=");

    /**
     * Иконка вкладки Auction (аукцион) для переключения между меню GUI.
     */
    public static final String TAB_AUCTION =
            HeadsConfig.get("tab-auction", "eyJ0ZXh0dXJlcyI6eyJTS0lOIjp7InVybCI6Imh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUvMWQ5NDA5M2MyNzM4NzUyZjI0NTY5NmI1NDc4YjY2ODIzMjE4OTkwYzlkZDUzNjFiNjQ2ZGFjYWIxNzY3NGE0MSJ9fX0=");

    /**
     * Красный череп для индикации проданного лота в Seller GUI.
     */
    public static final String SOLD_ITEM_SKULL =
            HeadsConfig.get("sold-item-skull", "eyJ0ZXh0dXJlcyI6eyJTS0lOIjp7InVybCI6Imh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUvM2VkMWFiYTczZjYzOWY0YmM0MmJkNDgxOTZjNzE1MTk3YmUyNzEyYzNiOTYyYzk3ZWJmOWU5ZWQ4ZWZhMDI1In19fQ==");
}
