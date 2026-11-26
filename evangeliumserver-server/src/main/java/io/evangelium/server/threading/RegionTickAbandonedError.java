package io.evangelium.server.threading;

/**
 * Evangelium - plugin compatibility: thrown on a tick thread whose region tick RegionGuard abandoned while the thread waited for a
 * plugin lock (its region lock entries were removed, so it holds nothing any more). An Error on purpose: the generic catch blocks of
 * event dispatch, entity and block entity ticking rethrow it (they would otherwise log it and go on, or discard the entity), so it ends
 * the whole region tick. Once set, OnDemandRegionAccess#isTickAbandoned stays true for this thread until the next region tick starts.
 */
public final class RegionTickAbandonedError extends Error {

    public RegionTickAbandonedError(String message) {
        super(message);
    }
}
