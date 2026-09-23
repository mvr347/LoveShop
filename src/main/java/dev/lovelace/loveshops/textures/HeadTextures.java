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

    /**
     * Голова свитка для заключения сделки со Странником.
     */
    public static final String WANDERER_CONTRACT =
            HeadsConfig.get("wanderer-contract", "eyJ0ZXh0dXJlcyI6eyJTS0lOIjp7InVybCI6Imh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUvZTI0ZGJmN2E0MGNiNDcyNmNjYWRhYTNmNmYzMWRkMWQxNzRhMWE5NTg5YTY0YTc5YmYzNDIxYTZjNzc5NzUifX19");

    /**
     * Песочные часы для ожидания доставки Странника.
     */
    public static final String WANDERER_WAITING =
            HeadsConfig.get("wanderer-waiting", "eyJ0ZXh0dXJlcyI6eyJTS0lOIjp7InVybCI6Imh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUvZmM4MDM5MmJhMGE0MjkxMWU1NTgwNDM3OTExZGFkNTVjODE2NDExNmExMDg5Y2YxMWRlYjY5Y2FlM2QxYmEzIn19fQ==");

    /**
     * Информационная иконка заказа Странника.
     */
    public static final String WANDERER_INFO =
            HeadsConfig.get("wanderer-info", "eyJ0ZXh0dXJlcyI6eyJTS0lOIjp7InVybCI6Imh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUvMzkxZDZkNmFmNWNmMmRjZDE5MDA1NmY2YmMyNmFlZTNjMmRhNWNmYzM2OTUxNWE0MmE5NWU0NmYzNmQzN2I0In19fQ==");

    /**
     * Кнопка завершения/сброса заказа Странника.
     */
    public static final String WANDERER_RESET =
            HeadsConfig.get("wanderer-reset", "eyJ0ZXh0dXJlcyI6eyJTS0lOIjp7InVybCI6Imh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUvNmZhNzY0MTk3N2EzNmE3MTFkZGMxNWE4NTVlZThkZGYyYjQ4ZDY2MWQ4MzczM2FlY2FiZjQ3OTQyZDU3MzkxIn19fQ==");
}
