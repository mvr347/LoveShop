package dev.lovelace.loveshops.config;

import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

import java.io.InputStream;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

/**
 * Bukkit only logs "duplicate keys found" and silently lets the LAST duplicate win, so a stale copy lower in the
 * file overrides the intended text without anyone noticing. Fail the build instead.
 */
class LangDuplicateKeysTest {

    @Test
    void shippedYamlFilesHaveNoDuplicateKeys() {
        for (String resource : new String[]{"/lang.yml", "/config.yml"}) {
            LoaderOptions options = new LoaderOptions();
            options.setAllowDuplicateKeys(false);
            try (InputStream in = getClass().getResourceAsStream(resource)) {
                if (in == null) continue;
                assertDoesNotThrow(() -> new Yaml(new SafeConstructor(options)).load(in), resource + " has duplicate keys");
            } catch (java.io.IOException e) {
                throw new AssertionError(e);
            }
        }
    }
}
