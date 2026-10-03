package dev.lovelace.loveshops.utils;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ProgressBarUtilTest {

    @Test
    void testEmptyAndFullBars() {
        String empty = ProgressBarUtil.formatProgressBar(0, 100, 20, "шт");
        assertTrue(empty.contains("&8■■■■■■■■■■■■■■■■■■■■"));
        assertTrue(empty.contains("(0%)"));

        String full = ProgressBarUtil.formatProgressBar(100, 100, 20, "шт");
        assertTrue(full.contains("&f■■■■■■■■■■■■■■■■■■■■"));
        assertTrue(full.contains("(100%)"));
    }

    @Test
    void testStackProgressBar() {
        // 640 units = 10 stacks (64 units per stack), max 1280 = 20 stacks
        String bar = ProgressBarUtil.formatStackProgressBar(640, 1280, 64, 20);
        assertTrue(bar.contains("10 &f / 20 стаков"));
        assertTrue(bar.contains("(50%)"));
    }
}
