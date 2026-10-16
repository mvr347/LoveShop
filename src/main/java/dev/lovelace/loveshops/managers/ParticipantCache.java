package dev.lovelace.loveshops.managers;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.IntFunction;

/**
 * Per-session set of registered Lost Caravan participants. The registration menu asks "is this
 * viewer registered?" every second per viewer; answering from memory (loaded once per session)
 * keeps those polls off the database.
 */
public final class ParticipantCache {

    private final Map<Integer, Set<UUID>> sessions = new ConcurrentHashMap<>();

    /** Membership check; the loader runs once per session id, on the first miss. */
    public boolean contains(int sessionId, UUID uuid, IntFunction<Set<UUID>> loader) {
        return sessions.computeIfAbsent(sessionId, id -> {
            Set<UUID> set = ConcurrentHashMap.newKeySet();
            set.addAll(loader.apply(id));
            return set;
        }).contains(uuid);
    }

    /** Records a registration; creates the set only if already loaded to avoid a partial view. */
    public void add(int sessionId, UUID uuid) {
        Set<UUID> set = sessions.get(sessionId);
        if (set != null) set.add(uuid);
    }

    /** Drops every session except the given one (old sessions are never queried again). */
    public void retainOnly(int sessionId) {
        sessions.keySet().removeIf(id -> id != sessionId);
    }

    public void clear() {
        sessions.clear();
    }
}
