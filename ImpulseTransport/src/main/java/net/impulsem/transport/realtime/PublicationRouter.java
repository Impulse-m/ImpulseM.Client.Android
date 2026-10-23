package net.impulsem.transport.realtime;


/** Maps a publication to what the application must do with it. */
public final class PublicationRouter {

    public static final String UserPrefix = "user:";
    public static final String ChannelPrefix = "channel:";


    public enum Kind {
        /** Something cannot be read: run getDifference. */
        GET_DIFFERENCE,
        /** Transcode {@link Action#proto} into TL Updates and process them. */
        DECODE
    }


    public static final class Action {

        public final Kind kind;
        public final byte[] proto;


        private Action(
            Kind kind,
            byte[] proto
        ) {
            this.kind = kind;
            this.proto = proto;
        }
    }


    private PublicationRouter() {
    }


    public static Action route(Publication publication) {
        Envelope envelope = publication.envelope;
        if (envelope.undecodable()) {
            return new Action(Kind.GET_DIFFERENCE, null);
        }
        return new Action(Kind.DECODE, envelope.updatesProto);
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
