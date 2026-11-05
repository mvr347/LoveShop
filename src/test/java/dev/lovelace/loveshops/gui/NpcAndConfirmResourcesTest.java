package dev.lovelace.loveshops.gui;

import static org.junit.jupiter.api.Assertions.*;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

class NpcAndConfirmResourcesTest {

    private static YamlConfiguration resource(String name) {
        var stream = NpcAndConfirmResourcesTest.class.getClassLoader().getResourceAsStream(name);
        assertNotNull(stream, name);
        return YamlConfiguration.loadConfiguration(new InputStreamReader(stream, StandardCharsets.UTF_8));
    }

    @Test
    void everyNpcHeadHasANameAndAShortDescription() {
        var lang = resource("lang.yml");
        for (String npc : new String[] {"war-merchant", "wanderer-deal", "wanderer-waiting"}) {
            assertNotNull(lang.getString("gui.npc." + npc + ".name"), npc);
            assertTrue(lang.getStringList("gui.npc." + npc + ".lore").size() >= 3, npc);
        }
    }

    @Test
    void theNpcAndConfirmTexturesAreBundled() {
        var heads = resource("heads.yml");
        assertTrue(heads.getString("npc-header", "").contains("eyJ0"));
        assertNotEquals(heads.getString("confirm-yes"), heads.getString("confirm-no"));
    }

    @Test
    void confirmWindowsKeepTheirButtonsOneSlotApartInTheMiddle() {
        // Owner decision 2026-11-05: [С][С][С][✓][С][✗][С][С][С]
        assertEquals(3, dev.lovelace.loveshops.gui.WandererHeadSale.CONFIRM_YES);
        assertEquals(5, dev.lovelace.loveshops.gui.WandererHeadSale.CONFIRM_NO);
        assertEquals(3, CommissionConfirmGui.SLOT_CONFIRM);
        assertEquals(5, CommissionConfirmGui.SLOT_CANCEL);
        assertEquals(0, BankerGui.SLOT_INFO);
    }
}
