package dev.lovelace.loveshops.npc;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class NpcTextTest {

    private static String plain(Component c) {
        return PlainTextComponentSerializer.plainText().serialize(c);
    }

    @Test
    void ampersandCodesBecomeColours() {
        Component c = NpcText.amp("&aOpen");
        assertEquals("Open", plain(c));
        assertEquals(NamedTextColor.GREEN, c.children().isEmpty() ? c.color() : c.children().get(0).color());
    }

    @Test
    void hexColoursSurviveBothNotations() {
        assertEquals("Hi", plain(NpcText.amp("&#ff8800Hi")));
        assertEquals("Hi", plain(NpcText.amp("&x&f&f&8&8&0&0Hi")));
        Component c = NpcText.amp("&#ff8800Hi");
        TextColor color = c.children().isEmpty() ? c.color() : c.children().get(0).color();
        assertEquals(TextColor.color(0xff8800), color);
    }

    @Test
    void sectionSignCodesAreHonoured() {
        assertEquals("Bank", plain(NpcText.section("§6Bank")));
        assertEquals("&6Bank", plain(NpcText.section("&6Bank")));
    }

    @Test
    void nullIsEmpty() {
        assertEquals("", plain(NpcText.amp(null)));
        assertEquals("", plain(NpcText.section(null)));
        assertTrue(NpcText.ampLines(null).isEmpty());
    }

    @Test
    void linesSkipBlankOnes() {
        List<Component> lines = NpcText.ampLines("&6Point {id}\n\n  \r\n&7Owner: x");
        assertEquals(2, lines.size());
        assertEquals("Point {id}", plain(lines.get(0)));
        assertEquals("Owner: x", plain(lines.get(1)));
    }

    @Test
    void plainNameDropsTheStyling() {
        assertEquals("Landlord", NpcText.plainAmp("&6&lLandlord"));
    }
}
