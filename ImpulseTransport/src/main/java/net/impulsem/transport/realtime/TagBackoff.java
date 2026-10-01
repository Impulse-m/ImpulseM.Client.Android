package net.impulsem.transport.realtime;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;


/**
 * Negative cache for channels whose actor tag the backend did not return. Each miss pushes the next attempt back:
 * 1 minute, doubling, capped at 30 minutes. A channel that leaves the desired set is forgotten, so a later return
 * starts again from the first step.
 */
public final class TagBackoff {

    public static final long FirstDelayMillis = 60L * 1000L;
    public static final long MaxDelayMillis = 30L * 60L * 1000L;


    private static final class Entry {

        long delayMillis;
        long dueAtMillis;
    }


    private final Map<Long, Entry> entries = new HashMap<Long, Entry>();


    /** True when the channel was never missed or its wait is over. */
    public synchronized boolean isDue(
        long channelId,
        long nowMillis
    ) {
        Entry entry = entries.get(channelId);
        return entry == null || nowMillis >= entry.dueAtMillis;
    }


    public synchronized void failed(
        long channelId,
        long nowMillis
    ) {
        Entry entry = entries.get(channelId);
        if (entry == null) {
            entry = new Entry();
            entry.delayMillis = FirstDelayMillis;
            entries.put(channelId, entry);
        } else {
            entry.delayMillis = Math.min(entry.delayMillis * 2L, MaxDelayMillis);
        }
        entry.dueAtMillis = nowMillis + entry.delayMillis;
    }


    public synchronized void succeeded(long channelId) {
        entries.remove(channelId);
    }


    /** Forgets every channel that is no longer desired. */
    public synchronized void retainOnly(Set<Long> desired) {
        Iterator<Long> iterator = entries.keySet().iterator();
        while (iterator.hasNext()) {
            if (!desired.contains(iterator.next())) {
                iterator.remove();
            }
        }
    }


    public synchronized void clear() {
        entries.clear();
    }


    /** The shortest wait until one of the given channels is due again; -1 when none is waiting. */
    public synchronized long nextDueInMillis(
        Set<Long> channelIds,
        long nowMillis
    ) {
        long best = -1L;
        for (Long id : channelIds) {
            Entry entry = entries.get(id);
            if (entry == null || nowMillis >= entry.dueAtMillis) {
                continue;
            }
            long wait = entry.dueAtMillis - nowMillis;
            if (best < 0L || wait < best) {
                best = wait;
            }
        }
        return best;
    }
}
