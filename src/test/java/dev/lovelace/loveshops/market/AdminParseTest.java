package dev.lovelace.loveshops.market;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class AdminParseTest {

    @Test
    void amountRejectsZeroNegativeAndText() {
        assertNull(AdminParse.amount("0", 100));
        assertNull(AdminParse.amount("-5", 100));
        assertNull(AdminParse.amount("abc", 100));
        assertNull(AdminParse.amount("", 100));
        assertNull(AdminParse.amount(null, 100));
        assertNull(AdminParse.amount("1.5", 100));
    }

    @Test
    void amountHonoursTheCap() {
        assertEquals(100L, AdminParse.amount("100", 100));
        assertNull(AdminParse.amount("101", 100));
        assertEquals(1L, AdminParse.amount("1", 100));
    }

    @Test
    void amountSurvivesIntAndLongOverflow() {
        // Beyond int: still a valid long when the cap allows it, so int callers must cap at Integer.MAX_VALUE.
        assertNull(AdminParse.amount("2147483648", Integer.MAX_VALUE));
        assertEquals(2147483647L, AdminParse.amount("2147483647", Integer.MAX_VALUE));
        assertNull(AdminParse.amount("99999999999999999999", Long.MAX_VALUE));
    }

    @Test
    void amountOrZeroAllowsZeroOnly() {
        assertEquals(0L, AdminParse.amountOrZero("0", 100));
        assertNull(AdminParse.amountOrZero("-1", 100));
        assertNull(AdminParse.amountOrZero("101", 100));
    }

    @Test
    void percentAcceptsSignsAndPercentMark() {
        assertEquals(10.0, AdminParse.percent("+10"));
        assertEquals(-15.0, AdminParse.percent("-15"));
        assertEquals(12.5, AdminParse.percent("12.5%"));
        assertEquals(0.0, AdminParse.percent("0"));
    }

    @Test
    void percentRejectsGarbageAndNonFinite() {
        assertNull(AdminParse.percent("abc"));
        assertNull(AdminParse.percent(""));
        assertNull(AdminParse.percent("%"));
        assertNull(AdminParse.percent("NaN"));
        assertNull(AdminParse.percent("Infinity"));
        assertNull(AdminParse.percent(null));
    }

    @Test
    void multiplierRangeIsMinus90ToPlus500() {
        assertTrue(AdminParse.multiplierInRange(-90));
        assertTrue(AdminParse.multiplierInRange(500));
        assertTrue(AdminParse.multiplierInRange(0));
        assertFalse(AdminParse.multiplierInRange(-90.1));
        assertFalse(AdminParse.multiplierInRange(500.1));
        assertFalse(AdminParse.multiplierInRange(-100)); // would zero every price
    }

    @Test
    void stepDistinguishesPercentageFromFixed() {
        assertEquals(new AdminParse.Step("percentage", 5), AdminParse.step("5%", 1000));
        assertEquals(new AdminParse.Step("fixed", 50), AdminParse.step("50", 1000));
        assertNull(AdminParse.step("0%", 1000));
        assertNull(AdminParse.step("101%", 1000));
        assertNull(AdminParse.step("1001", 1000));
        assertNull(AdminParse.step("x", 1000));
        assertNull(AdminParse.step("", 1000));
    }

    @Test
    void russianAliasesMapToTheEnglishWords() {
        assertEquals("price", AdminParse.canonical("цена"));
        assertEquals("auction", AdminParse.canonical("Аукцион"));
        assertEquals("flea", AdminParse.canonical("барахолка"));
        assertEquals("seize", AdminParse.canonical("изъять"));
        assertEquals("restore", AdminParse.canonical("восстановить"));
        assertEquals("reload", AdminParse.canonical("RELOAD"));
        assertEquals("", AdminParse.canonical(null));
    }

    @Test
    void durationShowsTheTwoLargestUnits() {
        assertEquals("0с", AdminParse.duration(0));
        assertEquals("0с", AdminParse.duration(-10));
        assertEquals("40с", AdminParse.duration(40));
        assertEquals("8м", AdminParse.duration(8 * 60 + 20));
        assertEquals("5ч 12м", AdminParse.duration(5 * 3600 + 12 * 60));
        assertEquals("5ч", AdminParse.duration(5 * 3600));
        assertEquals("3д 4ч", AdminParse.duration(3 * 86400 + 4 * 3600 + 30));
        assertEquals("2д", AdminParse.duration(2 * 86400));
    }
}
