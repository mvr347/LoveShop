package dev.lovelace.loveshops.market;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Every {@code gui-*} key used by the market menus must exist in lang.yml: a missing key shows up
 * in game as a red "[key]". Keys built at run time (prefix + variable part) are listed in
 * {@link #DYNAMIC}.
 */
class LangKeysTest {

    private static final Pattern KEY = Pattern.compile("\"((?:gui|cmd|tpa|wiz|feudal|tp)-[a-z0-9_-]+)\"");

    /** Keys assembled from a prefix in code; every variant must exist. */
    private static final List<String> DYNAMIC = List.of(
            "gui-mode-sell_only", "gui-mode-buy_only", "gui-mode-both",
            "gui-customer-tab-goods", "gui-customer-tab-goods-on", "gui-customer-tab-goods-lore",
            "gui-customer-tab-orders", "gui-customer-tab-orders-on", "gui-customer-tab-orders-lore",
            "wiz-step-corner_1", "wiz-step-corner_2", "wiz-step-npc", "wiz-step-closed_sign", "wiz-step-id_sign", "wiz-step-teleport");

    @Test
    void everyGuiKeyIsInLangYml() throws IOException {
        YamlConfiguration lang = YamlConfiguration.loadConfiguration(
                Path.of("src/main/resources/lang.yml").toFile());
        List<String> missing = new ArrayList<>();
        List<String> used = new ArrayList<>(DYNAMIC);
        try (Stream<Path> files = Files.walk(Path.of("src/main/java"))) {
            for (Path file : files.filter(f -> f.toString().endsWith(".java")).toList()) {
                Matcher m = KEY.matcher(Files.readString(file, StandardCharsets.UTF_8));
                while (m.find()) {
                    String key = m.group(1);
                    // a prefix such as "gui-mode-", "gui-customer-tab-" or "wiz-step-" is built at run time
                    if (!key.endsWith("-")) used.add(key);
                }
            }
        }
        for (String key : used) {
            if (!lang.contains("market." + key)) missing.add(key);
        }
        assertTrue(missing.isEmpty(), "keys missing from lang.yml (market.*): " + missing);
    }
}
