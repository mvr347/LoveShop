package dev.lovelace.loveshops.models;

import java.util.Locale;

/**
 * The four categories a player can pay extra to request specifically from the Wanderer
 * (see {@code wanderer.deal.personal-request} in config.yml and
 * {@code WandererManager#startDeal(Player, WandererRequestCategory)}). Every
 * {@link WandererItemConfig} entry in {@code wanderer.items-pool} declares which one it
 * belongs to via its {@code category} field.
 */
public enum WandererRequestCategory {
    TOOLS("Инструменты"),
    ARMOR("Броня"),
    ENCHANTMENTS("Зачарования"),
    RARE("Редкие предметы");

    private final String displayName;

    WandererRequestCategory(String displayName) {
        this.displayName = displayName;
    }

    public String displayName() {
        return displayName;
    }

    /** Unrecognized/missing config value falls back to RARE — matches the pool's pre-existing flavor (unique weapons/relics). */
    public static WandererRequestCategory fromConfigValue(String value) {
        if (value == null || value.isBlank()) return RARE;
        try {
            return WandererRequestCategory.valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return RARE;
        }
    }
}
