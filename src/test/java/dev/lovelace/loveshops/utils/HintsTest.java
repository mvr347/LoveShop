package dev.lovelace.loveshops.utils;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.function.UnaryOperator;

import static org.junit.jupiter.api.Assertions.*;

class HintsTest {

    private static final LoreBlocks.Style STYLE = LoreBlocks.Style.DEFAULTS;
    private static final UnaryOperator<String> TEXT = key -> key;

    @Test
    void coinPickerIsOneActionPerLineWithGoldRightClick() {
        List<String> lines = Hints.coinPicker(STYLE, TEXT);
        assertEquals(List.of(
                LoreBlocks.SEPARATOR,
                "<yellow>Shift+ЛКМ <dark_gray>— <gray>gui-hint-coin-cycle",
                "<green>ЛКМ <dark_gray>— <gray>gui-hint-coin-add",
                "<gold>ПКМ <dark_gray>— <gray>gui-hint-coin-sub"), lines);
    }

    @Test
    void amountStepperShowsTheBigStepOnShift() {
        UnaryOperator<String> text = key -> key.endsWith("-big") ? "шаг {step}" : key;
        List<String> lines = Hints.amountStepper(STYLE, text, 8);
        assertEquals(5, lines.size());
        assertEquals("<yellow>Shift+ЛКМ <dark_gray>— <gray>шаг 8", lines.get(3));
        assertEquals("<yellow>Shift+ПКМ <dark_gray>— <gray>шаг 8", lines.get(4));
    }

    @Test
    void noHintJoinsActionsWithADot() {
        String all = String.join("\n", Hints.coinPicker(STYLE, TEXT)) + String.join("\n", Hints.amountStepper(STYLE, TEXT, 8));
        assertFalse(all.contains("·"));
        assertFalse(all.contains("<red>ПКМ"));
    }

    @Test
    void everyActionKeyExistsInLangYml() {
        YamlConfiguration lang = YamlConfiguration.loadConfiguration(Path.of("src/main/resources/lang.yml").toFile());
        UnaryOperator<String> real = key -> {
            String value = lang.getString("market." + key);
            assertNotNull(value, "market." + key);
            return value;
        };
        Hints.coinPicker(STYLE, real);
        Hints.amountStepper(STYLE, real, 8);
    }
}
