package net.impulsem.transport.realtime;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;


/** Chooses which channels get a realtime lane: the opened chat first, then the most recently active channels. */
public final class LaneSelector {

    /** The most channel lanes one connection may hold. */
    public static final int MaxLanes = 32;


    public static final class Candidate {

        public final long channelId;
        public final int lastMessageDate;


        public Candidate(
            long channelId,
            int lastMessageDate
        ) {
            this.channelId = channelId;
            this.lastMessageDate = lastMessageDate;
        }
    }


    private LaneSelector() {
    }


    /**
     * @param candidates channel dialogs, in any order; ids are positive channel ids, others are ignored.
     * @param openedChannelId the channel of the open chat, or a non-positive value for none.
     * @param cap the most lanes to return.
     * @return channel ids in priority order: the opened chat, then newest first.
     */
    public static Set<Long> select(
        List<Candidate> candidates,
        long openedChannelId,
        int cap
    ) {
        Set<Long> result = new LinkedHashSet<Long>();
        if (cap <= 0) {
            return result;
        }
        if (openedChannelId > 0L) {
            result.add(openedChannelId);
        }
        List<Candidate> sorted = new ArrayList<Candidate>(candidates);
        Collections.sort(sorted, new Comparator<Candidate>() {
            @Override
            public int compare(
                Candidate a,
                Candidate b
            ) {
                return b.lastMessageDate < a.lastMessageDate ? -1 : (b.lastMessageDate == a.lastMessageDate ? 0 : 1);
            }
        });
        for (int a = 0; a < sorted.size() && result.size() < cap; a++) {
            long id = sorted.get(a).channelId;
            if (id > 0L) {
                result.add(id);
            }
        }
        return result;
    }
}
