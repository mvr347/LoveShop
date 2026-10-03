package dev.lovelace.loveshops.utils;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ProgressBarUtilTest {

    @Test
    void testEmptyAndFullBars() {
        String empty = ProgressBarUtil.formatProgressBar(0, 100, 10, "шт");
        assertTrue(empty.contains("<gray>▱</gray>"));
        assertTrue(empty.contains("(0/100 шт)"));

        String full = ProgressBarUtil.formatProgressBar(100, 100, 10, "шт");
        assertTrue(full.contains("<green>▰</green>"));
        assertTrue(full.contains("(100/100 шт)"));
    }

    @Test
    void testStackProgressBar() {
        // 640 units = 10 stacks (64 units per stack), max 1280 = 20 stacks
        String bar = ProgressBarUtil.formatStackProgressBar(640, 1280, 64, 10);
        assertTrue(bar.contains("(10/20 стаков)"));
    }
}
