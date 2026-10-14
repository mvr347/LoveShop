package dev.lovelace.loveshops.market;

import dev.lovelace.loveshops.market.NoticeRouting.Kind;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class NoticeRoutingTest {

    @Test
    void moneyAndLossNoticesAreImportant() {
        for (String key : new String[]{"trade-bought", "trade-sold", "till-collected", "upgrade-done",
                "shop-robbed", "rent-grace-closed", "guard-expired"}) {
            assertEquals(Kind.IMPORTANT, NoticeRouting.classify(key), key);
        }
    }

    @Test
    void clickStatusesAreEvents() {
        for (String key : new String[]{"listing-added", "trade-no-money", "storage-full", "blacklist-added"}) {
            assertEquals(Kind.EVENT, NoticeRouting.classify(key), key);
        }
    }

    @Test
    void promptsWizardAndNpcSpeechStayInChat() {
        for (String key : new String[]{"prompt-invalid", "prompt-timeout", "wiz-started", "stall-vacant",
                "robber-hostile", "aggressor-refused", "outcast-refused"}) {
            assertEquals(Kind.CHAT, NoticeRouting.classify(key), key);
        }
    }

    @Test
    void unknownOrNullKeyFallsBackToChat() {
        assertEquals(Kind.CHAT, NoticeRouting.classify("some-future-key"));
        assertEquals(Kind.CHAT, NoticeRouting.classify(null));
    }
}
