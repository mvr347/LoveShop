package dev.lovelace.loveshops.utils;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ConfigBackfillTest {
    private static YamlConfiguration yaml(String text) {
        YamlConfiguration y = new YamlConfiguration();
        try {
            y.loadFromString(text);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        return y;
    }

    @Test
    void addsOnlyMissingPathsAndKeepsOwnerValues() {
        var server = yaml("caravan:\n  lost:\n    bid-duration-seconds: 90\n");
        var bundled = yaml("caravan:\n  lost:\n    bid-duration-seconds: 60\n    bids-shown: 4\n    quality:\n      default:\n        bad:\n          rolls: 1\n");
        int added = ConfigBackfill.backfill(server, bundled, java.util.Set.of(), "caravan.lost");
        assertEquals(2, added);
        assertEquals(90, server.getInt("caravan.lost.bid-duration-seconds"));
        assertEquals(4, server.getInt("caravan.lost.bids-shown"));
        assertEquals(1, server.getInt("caravan.lost.quality.default.bad.rolls"));
        assertEquals(0, ConfigBackfill.backfill(server, bundled, java.util.Set.of(), "caravan.lost"));
    }

    @Test
    void ownerEditedLootTableIsNotRefilled() {
        var server = yaml("caravan:\n  lost:\n    crates:\n      default:\n        iron:\n          material: IRON_INGOT\n");
        var bundled = yaml("caravan:\n  lost:\n    crates:\n      default:\n        iron:\n          material: IRON_INGOT\n        gold:\n          material: GOLD_INGOT\n      secret:\n        totem:\n          material: TOTEM_OF_UNDYING\n");
        ConfigBackfill.backfill(server, bundled, java.util.Set.of("caravan.lost.crates.default", "caravan.lost.crates.secret"), "caravan.lost");
        assertFalse(server.contains("caravan.lost.crates.default.gold", true));
        // a table the server lacks entirely is copied
        assertTrue(server.contains("caravan.lost.crates.secret.totem.material", true));
    }
}
