package dev.lovelace.loveshops.utils;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static dev.lovelace.loveshops.utils.LoreBlocks.Click;
import static dev.lovelace.loveshops.utils.LoreBlocks.Hint;
import static org.junit.jupiter.api.Assertions.*;

class LoreBlocksTest {

    private static final LoreBlocks.Style DEFAULTS = LoreBlocks.Style.DEFAULTS;

    @Test
    void defaultsEqualLangYml() {
        YamlConfiguration lang = YamlConfiguration.loadConfiguration(Path.of("src/main/resources/lang.yml").toFile());
        LoreBlocks.defaults().forEach((path, text) -> assertEquals(text, lang.getString(path), path));
    }

    @Test
    void everyClickIsItsOwnLineWithItsOwnColour() {
        assertEquals("<green>ЛКМ <dark_gray>— <gray>открыть", LoreBlocks.click(DEFAULTS, Click.LEFT, "открыть"));
        assertEquals("<gold>ПКМ <dark_gray>— <gray>снять", LoreBlocks.click(DEFAULTS, Click.RIGHT, "снять"));
        assertEquals("<yellow>Shift+ЛКМ <dark_gray>— <gray>стек", LoreBlocks.click(DEFAULTS, Click.SHIFT_LEFT, "стек"));
        assertEquals("<yellow>Shift+ПКМ <dark_gray>— <gray>всё", LoreBlocks.click(DEFAULTS, Click.SHIFT_RIGHT, "всё"));
    }

    @Test
    void clicksStartWithTheSeparatorAndHaveOneLinePerHint() {
        List<String> lines = LoreBlocks.clicks(DEFAULTS, new Hint(Click.LEFT, "купить"), new Hint(Click.RIGHT, "снять"));
        assertEquals(3, lines.size());
        assertEquals(LoreBlocks.SEPARATOR, lines.get(0));
        assertTrue(lines.stream().noneMatch(l -> l.contains("·")));
    }

    @Test
    void noHintsGiveNoSeparator() {
        assertTrue(LoreBlocks.clicks(DEFAULTS, List.of()).isEmpty());
    }

    @Test
    void blockedBlockKeepsTheStandardOrder() {
        List<String> lines = LoreBlocks.blocked(DEFAULTS, "unavailable", "<gray>Нет денег", new Hint(Click.LEFT, "улучшить"));
        assertEquals(List.of(
                LoreBlocks.SEPARATOR,
                "<red>✕ Действие недоступно",
                "<red>Причина: <gray><gray>Нет денег",
                "<dark_gray>ЛКМ — улучшить (заблокировано)"), lines);
    }

    @Test
    void disabledNameHasTheStateSuffix() {
        assertEquals("<gray>Продажа <dark_gray>| <red>Недоступно", LoreBlocks.disabledName(DEFAULTS, "Продажа"));
    }

    @Test
    void styleTextWinsOverTheDefault() {
        LoreBlocks.Style custom = path -> path.equals("style.click.left") ? "L {action}" : null;
        assertEquals("L x", LoreBlocks.click(custom, Click.LEFT, "x"));
        assertEquals("<gold>ПКМ <dark_gray>— <gray>y", LoreBlocks.click(custom, Click.RIGHT, "y"));
    }

    @Test
    void barIsSixteenCellsWithPercent() {
        String bar = LoreBlocks.bar(5, 10);
        assertEquals(16, bar.chars().filter(c -> c == '█').count());
        assertTrue(bar.contains("50%"));
    }

    @Test
    void italicsIsStrippedUnlessTheTextSetsIt() {
        Component plain = MiniMessage.miniMessage().deserialize("<gray>text");
        assertEquals(TextDecoration.State.FALSE, GuiUtils.noItalic(plain).decoration(TextDecoration.ITALIC));
        Component explicit = MiniMessage.miniMessage().deserialize("<italic>text");
        assertEquals(TextDecoration.State.TRUE, GuiUtils.noItalic(explicit).decoration(TextDecoration.ITALIC));
    }
}
