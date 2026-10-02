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
     * Кнопка «закрыть» в GUI.
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
     * Иконка товаров для торговых точек.
     */
    public static final String TAB_SELLER =
            HeadsConfig.get("tab-seller", "eyJ0ZXh0dXJlcyI6eyJTS0lOIjp7InVybCI6Imh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUvZmM2MjExMGQ4MTg4NDQxZDIxNzk0NDM0ZjY3ZDEyYTAyMWI3NDAyYzhkYWE0MmQ0ZmVhMzIzZTdlMTllMGJiNyJ9fX0=");



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

    /**
     * Слот «Ваша валюта» у Банкира: пусто (депозит = 0).
     */
    public static final String BANKER_DEPOSIT_EMPTY =
            HeadsConfig.get("banker-deposit-empty", "eyJ0ZXh0dXJlcyI6eyJTS0lOIjp7InVybCI6Imh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUvNjRjMTY0YmFjMjE4NGE3NmExZWU5NjkxMzI0MmUzMzVmMWQ0MTFjYWZmNTEyMDVlYTM5YjIwNWU2ZjhmMDU4YSJ9fX0=");

    /**
     * Слот «Ваша валюта» у Банкира: есть монеты (депозит > 0).
     */
    public static final String BANKER_DEPOSIT_FILLED =
            HeadsConfig.get("banker-deposit-filled", "eyJ0ZXh0dXJlcyI6eyJTS0lOIjp7InVybCI6Imh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUvOTVmZDY3ZDU2ZmZjNTNmYjM2MGExNzg3OWQ5YjUzMzhkNzMzMmQ4ZjEyOTQ5MWE1ZTE3ZThkNmU4YWVhNmMzYSJ9fX0=");

    /**
     * Кнопка «Как работает банкир» (информация).
     */
    public static final String BANKER_INFO =
            HeadsConfig.get("banker-info", "eyJ0ZXh0dXJlcyI6eyJTS0lOIjp7InVybCI6Imh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUvYmFkYzA0OGE3Y2U3OGY3ZGFkNzJhMDdkYTI3ZDg1YzA5MTY4ODFlNTUyMmVlZWQxZTNkYWYyMTdhMzhjMWEifX19");

    /** Зелёная галочка (стандарт gui_gen «Confirm»): магазин открыт. */
    public static final String MARKET_OPEN =
            HeadsConfig.get("market-open", "eyJ0ZXh0dXJlcyI6eyJTS0lOIjp7InVybCI6Imh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUvYmFkYzA0OGE3Y2U3OGY3ZGFkNzJhMDdkYTI3ZDg1YzA5MTY4ODFlNTUyMmVlZWQxZTNkYWYyMTdhMzhjMWEifX19");

    /** Красный крест (стандарт gui_gen «Cancel»): магазин закрыт. */
    public static final String MARKET_CLOSED =
            HeadsConfig.get("market-closed", "eyJ0ZXh0dXJlcyI6eyJTS0lOIjp7InVybCI6Imh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUvYzYxODczMWUwNjMzNzlhZWJmODJmMWQ2NGM0MTljOTBkN2YwYzE2NDhjNTQ4ZTliNjE1MWIxYmFiYTY2ZDcyMyJ9fX0=");

    /** Стрелка влево (стандарт gui_gen «Arrow Left»): предыдущая страница. */
    public static final String ARROW_LEFT =
            HeadsConfig.get("arrow-left", "eyJ0ZXh0dXJlcyI6eyJTS0lOIjp7InVybCI6Imh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUvODRkZjJjZWZhZDQ4YzEwMDYzZDczNTM5OWY5MDRmYWE0NjA4ZmQ0NjZkZWYxZGU5ZTU1YjFhMzY2NWUzODYwMyJ9fX0=");

    /** Стрелка вправо (стандарт gui_gen «Arrow Right»): следующая страница. */
    public static final String ARROW_RIGHT =
            HeadsConfig.get("arrow-right", "eyJ0ZXh0dXJlcyI6eyJTS0lOIjp7InVybCI6Imh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUvOWM4YzJhMDExYmU4ZTI2NDk4YjAzNmJjNDA3OTc3NDA4ODczYTYxYTc0MjYxMmM0OTdhMjI1MzU5YTMwYjRjZDMifX19");

    public static final String BUTTON_ARROW_LEFT = ARROW_LEFT;
    public static final String BUTTON_ARROW_RIGHT = ARROW_RIGHT;
    public static final String BUTTON_PLUS = HeadsConfig.get("button-plus", "eyJ0ZXh0dXJlcyI6eyJTS0lOIjp7InVybCI6Imh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUvM2VkMWFiYTczZjYzOWY0YmM0MmJkNDgxOTZjNzE1MTk3YmUyNzEyYzNiOTYyYzk3ZWJmOWU5ZWQ4ZWZhMDI1In19fQ==");
    public static final String BANKER_DEPOSIT = HeadsConfig.get("banker-deposit-filled", "eyJ0ZXh0dXJlcyI6eyJTS0lOIjp7InVybCI6Imh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUvOTVmZDY3ZDU2ZmZjNTNmYjM2MGExNzg3OWQ5YjUzMzhkNzMzMmQ4ZjEyOTQ5MWE1ZTE3ZThkNmU4YWVhNmMzYSJ9fX0=");
    public static final String BANKER_WITHDRAW = HeadsConfig.get("banker-deposit-empty", "eyJ0ZXh0dXJlcyI6eyJTS0lOIjp7InVybCI6Imh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUvNjRjMTY0YmFjMjE4NGE3NmExZWU5NjkxMzI0MmUzMzVmMWQ0MTFjYWZmNTEyMDVlYTM5YjIwNWU2ZjhmMDU4YSJ9fX0=");
    public static final String BANKER_ACCOUNT = HeadsConfig.get("banker-info", "eyJ0ZXh0dXJlcyI6eyJTS0lOIjp7InVybCI6Imh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUvYmFkYzA0OGE3Y2U3OGY3ZGFkNzJhMDdkYTI3ZDg1YzA5MTY4ODFlNTUyMmVlZWQxZTNkYWYyMTdhMzhjMWEifX19");
    public static final String HEAD_CONFIRM = MARKET_OPEN;
    public static final String HEAD_DELETE_NO = MARKET_CLOSED;
}

