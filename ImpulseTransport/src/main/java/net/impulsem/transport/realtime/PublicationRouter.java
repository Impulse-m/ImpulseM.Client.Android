package net.impulsem.transport.realtime;


/** Maps a publication to what the application must do with it. */
public final class PublicationRouter {

    public static final String UserPrefix = "user:";
    public static final String ChannelPrefix = "channel:";


    public enum Kind {
        /** Something was missed or cannot be read: run getDifference. */
        GET_DIFFERENCE,
        /** Build an updateChannelTooLong for {@link Action#channelId} with {@link Action#pts}. */
        CHANNEL_TOO_LONG,
        /** Transcode {@link Action#proto} into TL Updates and process them. */
        DECODE
    }


    public static final class Action {

        public final Kind kind;
        public final byte[] proto;
        public final long channelId;
        public final int pts;
        public final int date;


        private Action(
            Kind kind,
            byte[] proto,
            long channelId,
            int pts,
            int date
        ) {
            this.kind = kind;
            this.proto = proto;
            this.channelId = channelId;
            this.pts = pts;
            this.date = date;
        }
    }


    private PublicationRouter() {
    }


    public static Action route(Publication publication) {
        Envelope envelope = publication.envelope;
        if (envelope.undecodable() || envelope.oversize) {
            return new Action(Kind.GET_DIFFERENCE, null, 0L, 0, 0);
        }
        if (envelope.isChannelTooLongSignal()) {
            long channelId = channelId(publication.channel);
            if (channelId < 0L) {
                return new Action(Kind.GET_DIFFERENCE, null, 0L, 0, 0);
            }
            return new Action(Kind.CHANNEL_TOO_LONG, null, channelId, (int) envelope.pts, envelope.date);
        }
        return new Action(Kind.DECODE, envelope.updatesProto, 0L, 0, 0);
    }


    /** The id in {@code channel:{id}}, or -1 when the name is not a channel lane. */
    public static long channelId(String channel) {
        if (channel == null || !channel.startsWith(ChannelPrefix) || channel.length() == ChannelPrefix.length()) {
            return -1L;
        }
        try {
            long id = Long.parseLong(channel.substring(ChannelPrefix.length()));
            return id > 0L ? id : -1L;
        } catch (NumberFormatException e) {
            return -1L;
        }
    }
}
