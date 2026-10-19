package dev.lovelace.loveshops.managers;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class LangMigratorTest {

    private static YamlConfiguration yaml(String text) {
        YamlConfiguration y = new YamlConfiguration();
        try {
            y.loadFromString(text);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        return y;
    }

    private static final String OLD_SERVER = """
            prefix: "mine"
            gui:
              item-bought: "edited"
              old-key: x
            market:
              stall-rented: "admin chat text"
              gui-back: "<yellow>Назад</yellow>"
              gui-gone: "removed in the jar"
            """;

    private static final String JAR = """
            lang-version: 2
            prefix: "jar"
            style:
              separator: "sep"
            gui:
              item-bought: "jar text"
              close: "<red>Закрыть"
            market:
              stall-rented: "jar chat text"
              gui-back: "<yellow>Назад"
              gui-new: "new"
            """;

    @Test
    void oldFileGetsGuiPartsAndKeepsEverythingElse() {
        var server = yaml(OLD_SERVER);
        assertTrue(LangMigrator.migrate(server, yaml(JAR)));
        assertEquals(2, server.getInt("lang-version"));
        // overwritten from the jar
        assertEquals("sep", server.getString("style.separator"));
        assertEquals("jar text", server.getString("gui.item-bought"));
        assertNull(server.get("gui.old-key"));
        assertEquals("<yellow>Назад", server.getString("market.gui-back"));
        assertEquals("new", server.getString("market.gui-new"));
        assertNull(server.get("market.gui-gone"));
        // chat texts and the prefix keep the admin's version
        assertEquals("admin chat text", server.getString("market.stall-rented"));
        assertEquals("mine", server.getString("prefix"));
    }

    @Test
    void secondRunIsNoop() {
        var server = yaml(OLD_SERVER);
        assertTrue(LangMigrator.migrate(server, yaml(JAR)));
        server.set("gui.close", "edited after migration");
        assertFalse(LangMigrator.migrate(server, yaml(JAR)));
        assertEquals("edited after migration", server.getString("gui.close"));
    }

    @Test
    void freshFileOfTheCurrentVersionIsUntouched() {
        var server = yaml("lang-version: 2\ngui:\n  close: custom\n");
        assertFalse(LangMigrator.migrate(server, yaml(JAR)));
        assertEquals("custom", server.getString("gui.close"));
    }

    @Test
    void shippedLangYmlCarriesTheLatestVersion() {
        var jar = YamlConfiguration.loadConfiguration(Path.of("src/main/resources/lang.yml").toFile());
        assertEquals(LangMigrator.latestVersion(), jar.getInt(LangMigrator.VERSION_KEY));
    }

    @Test
    void migratingTheRealJarFileLeavesAChatKeyAlone() {
        var jar = YamlConfiguration.loadConfiguration(Path.of("src/main/resources/lang.yml").toFile());
        var server = yaml("market:\n  stall-rented: custom\n  gui-back: old\n");
        assertTrue(LangMigrator.migrate(server, jar));
        assertEquals("custom", server.getString("market.stall-rented"));
        assertEquals(jar.getString("market.gui-back"), server.getString("market.gui-back"));
        assertEquals(jar.getStringList("market.gui-page-lore"), server.getStringList("market.gui-page-lore"));
        assertEquals(jar.getString("style.separator"), server.getString("style.separator"));
    }

    @Test
    void jarCommentsComeAlongWithOverwrittenKeys() {
        var jar = yaml("lang-version: 2\nstyle:\n  # 2026-10-19: why\n  separator: sep\n");
        var server = yaml("style:\n  separator: old\n");
        assertTrue(LangMigrator.migrate(server, jar));
        assertTrue(server.getComments("style.separator").stream().anyMatch(c -> c != null && c.contains("why")));
    }
}
