package dev.lovelace.loveshops.market;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class RobberyLootTest {

    @Test
    void parsesTheLoggedFormat() {
        var list = RobberyLoot.parse("[{\"item\":\"AAA=\",\"amount\":3},{\"item\":\"BBB=\",\"amount\":64}]");
        assertEquals(2, list.size());
        assertEquals(new RobberyLoot.Entry("AAA=", 3), list.get(0));
        assertEquals(new RobberyLoot.Entry("BBB=", 64), list.get(1));
    }

    @Test
    void emptyAndMissingAreEmpty() {
        assertTrue(RobberyLoot.parse("[]").isEmpty());
        assertTrue(RobberyLoot.parse(null).isEmpty());
        assertTrue(RobberyLoot.parse("  ").isEmpty());
    }

    @Test
    void malformedJsonYieldsNothingInsteadOfThrowing() {
        assertTrue(RobberyLoot.parse("{not json").isEmpty());
        assertTrue(RobberyLoot.parse("{\"item\":\"A\"}").isEmpty());
    }

    @Test
    void badEntriesAreSkippedButGoodOnesSurvive() {
        var list = RobberyLoot.parse("[{\"item\":\"AAA=\",\"amount\":2},{\"item\":\"\",\"amount\":5},"
                + "{\"item\":\"C\",\"amount\":0},{\"item\":\"D\",\"amount\":\"x\"},{\"amount\":1},7,{\"item\":\"E=\",\"amount\":1}]");
        assertEquals(2, list.size());
        assertEquals("AAA=", list.get(0).itemData());
        assertEquals("E=", list.get(1).itemData());
    }

    @Test
    void negativeAmountIsDropped() {
        assertTrue(RobberyLoot.parse("[{\"item\":\"A\",\"amount\":-4}]").isEmpty());
    }
}
