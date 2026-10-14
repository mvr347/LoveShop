package dev.lovelace.loveshops.utils;

import dev.lovelace.lovecore.api.notify.LoveNotify.Channel;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

class NotifyChannelsTest {

    @Test
    void actionBarKindRequestsOnlyActionBar() {
        assertEquals(Set.of(Channel.ACTION_BAR), Notify.Bridge.channels(Notify.Bridge.ACTION_BAR));
    }

    @Test
    void titleKindFallsBackToActionBarNotChat() {
        // a player who disabled titles must still see the notice on the action bar before chat is considered
        assertEquals(Set.of(Channel.TITLE, Channel.ACTION_BAR), Notify.Bridge.channels(Notify.Bridge.TITLE));
    }

    @Test
    void eventKindIncludesChatForOptedInPlayers() {
        assertEquals(Set.of(Channel.ACTION_BAR, Channel.CHAT), Notify.Bridge.channels(Notify.Bridge.EVENT));
    }
}
