package dev.lovelace.loveshops.utils;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ProgressBarUtilTest {

    @Test
    void emptyBarIsBracketedAndGray() {
        String bar = ProgressBarUtil.bar(0, 100, 20);
        assertEquals("<dark_gray>[</dark_gray><dark_gray>" + "■".repeat(20) + "</dark_gray><dark_gray>]</dark_gray>", bar);
        assertFalse(bar.contains("<green>"));
    }

    @Test
    void fullBarIsGreen() {
        String bar = ProgressBarUtil.bar(100, 100, 20);
        assertEquals("<dark_gray>[</dark_gray><green>" + "■".repeat(20) + "</green><dark_gray>]</dark_gray>", bar);
    }

    @Test
    void halfBarSplitsGreenAndGray() {
        String bar = ProgressBarUtil.bar(10, 20, 20);
        assertTrue(bar.contains("<green>" + "■".repeat(10) + "</green>"));
        assertTrue(bar.contains("<dark_gray>" + "■".repeat(10) + "</dark_gray>"));
    }

    @Test
    void captionGoesToTheNextLine() {
        // 640 units = 10 stacks (64 units per stack), max 1280 = 20 stacks
        List<String> lines = ProgressBarUtil.formatStackProgressBar(640, 1280, 64, 20);
        assertEquals(2, lines.size());
        assertFalse(lines.get(0).contains("Заполненность"));
        assertTrue(lines.get(1).contains("10"));
        assertTrue(lines.get(1).contains("/ 20 стаков"));
        assertTrue(lines.get(1).contains("(50%)"));
    }

    @Test
    void overflowAndZeroMaxAreClamped() {
        assertTrue(ProgressBarUtil.bar(500, 100, 20).contains("<green>" + "■".repeat(20)));
        assertTrue(ProgressBarUtil.caption(5, 0, "шт").contains("(100%)"));
    }
}
