package dev.lovelace.loveshops.market;

/**
 * What the start-up sync may do with trade points that LoveClaims does not list (2026-11-06). A point is
 * only forgotten when LoveClaims answered with data and the absence stood up to a second look: an
 * unreachable LoveClaims, an empty answer (claims load per world) or a point missing for a moment must
 * never wipe a tenant's shop at a restart.
 */
final class ClaimSync {

    enum Verdict {
        /** LoveClaims could not be asked: nothing is touched, the sync is retried. */
        UNAVAILABLE,
        /** Nothing is missing. */
        NOTHING_MISSING,
        /** Missing on the first look: keep everything and look again later. */
        RECHECK_LATER,
        /** LoveClaims lists no points at all while the shop holds some: keep them, claims did not load. */
        KEEP_NO_DATA,
        /** Still missing on the second look: the claim is really gone. */
        FORGET
    }

    private ClaimSync() {}

    static Verdict orphans(boolean reachable, int listedByClaims, int missing, boolean confirmed) {
        if (!reachable) return Verdict.UNAVAILABLE;
        if (missing == 0) return Verdict.NOTHING_MISSING;
        if (listedByClaims == 0) return Verdict.KEEP_NO_DATA;
        return confirmed ? Verdict.FORGET : Verdict.RECHECK_LATER;
    }
}
