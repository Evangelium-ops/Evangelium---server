package io.multipaper.shreddedpaper.network;

import java.util.LinkedHashMap;
import java.util.concurrent.TimeUnit;

/** One-use reply credits for pings actually forwarded to this client. Guarded by Connection.PACKET_LIMIT_LOCK. */
public final class PacketReplyTracker {
    private final LinkedHashMap<Integer, Pending> pending = new LinkedHashMap<>();
    private final int maximum;
    private final long lifetime;
    private int count;

    public PacketReplyTracker() { this(65_536, TimeUnit.SECONDS.toNanos(60)); }

    PacketReplyTracker(int maximum, long lifetime) {
        if (maximum < 1 || lifetime < 1) throw new IllegalArgumentException("Invalid reply tracking bounds");
        this.maximum = maximum;
        this.lifetime = lifetime;
    }

    public void sentPing(int id, long now) {
        expire(now);
        Pending entry = pending.remove(id);
        if (entry == null) entry = new Pending();
        entry.sent = now;
        entry.count++;
        pending.put(id, entry);
        count++;
        if (count > maximum) {
            var oldest = pending.entrySet().iterator();
            Pending first = oldest.next().getValue();
            if (--first.count == 0) oldest.remove();
            count--;
        }
    }

    public boolean acceptPong(int id, long now) {
        expire(now);
        Pending entry = pending.get(id);
        if (entry == null || now - entry.sent < 0) return false;
        if (--entry.count == 0) pending.remove(id);
        count--;
        return true;
    }

    private void expire(long now) {
        var entries = pending.entrySet().iterator();
        while (entries.hasNext()) {
            Pending entry = entries.next().getValue();
            if (now - entry.sent < lifetime) break;
            count -= entry.count;
            entries.remove();
        }
    }

    public void clear() { pending.clear(); count = 0; }

    private static final class Pending {
        long sent;
        int count;
    }
}
