package net.impulsem.transport.realtime;


public final class Publication {

    public final String channel;
    public final Envelope envelope;
    public final long offset;


    public Publication(
        String channel,
        Envelope envelope,
        long offset
    ) {
        this.channel = channel;
        this.envelope = envelope;
        this.offset = offset;
    }
}
