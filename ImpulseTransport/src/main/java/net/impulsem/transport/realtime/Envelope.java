package net.impulsem.transport.realtime;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import okio.ByteString;


/** The update envelope carried in the {@code data} of a publication. */
public final class Envelope {

    public final int type;
    public final long pts;
    public final int ptsCount;
    public final int date;
    /** The decoded {@code v1.Updates} proto; null when the envelope carries no body. */
    public final byte[] updatesProto;
    public final boolean oversize;
    private final boolean undecodable;


    private Envelope(
        int type,
        long pts,
        int ptsCount,
        int date,
        byte[] updatesProto,
        boolean oversize,
        boolean undecodable
    ) {
        this.type = type;
        this.pts = pts;
        this.ptsCount = ptsCount;
        this.date = date;
        this.updatesProto = updatesProto;
        this.oversize = oversize;
        this.undecodable = undecodable;
    }


    /**
     * Never throws. Anything that cannot be read (not an object, non-numeric fields, invalid base64) yields an
     * envelope with {@link #undecodable()} set, so the application can still react, for example with getDifference.
     *
     * @param data the {@code data} object of a publication.
     */
    public static Envelope parse(JsonElement data) {
        try {
            return parseStrict(data);
        } catch (RuntimeException e) {
            return new Envelope(0, 0L, 0, 0, null, false, true);
        }
    }


    private static Envelope parseStrict(JsonElement data) {
        if (data == null || !data.isJsonObject()) {
            throw new IllegalArgumentException("envelope is not a JSON object");
        }
        JsonObject object = data.getAsJsonObject();
        byte[] body = null;
        JsonElement bodyElement = object.get("data");
        if (bodyElement != null && bodyElement.isJsonPrimitive() && bodyElement.getAsJsonPrimitive().isString()) {
            ByteString decoded = ByteString.decodeBase64(bodyElement.getAsString());
            if (decoded == null) {
                throw new IllegalArgumentException("envelope data is not valid base64");
            }
            body = decoded.toByteArray();
        }
        JsonElement oversize = object.get("oversize");
        return new Envelope(
            intOf(object, "type"),
            longOf(object, "pts"),
            intOf(object, "ptsCount"),
            intOf(object, "date"),
            body,
            oversize != null && oversize.isJsonPrimitive() && oversize.getAsBoolean(),
            false
        );
    }


    /** True when the publication could not be read; nothing else in the envelope is meaningful then. */
    public boolean undecodable() {
        return undecodable;
    }


    /** A body-less, non-oversize frame: the channel moved on and the client must resume from {@link #pts}. */
    public boolean isChannelTooLongSignal() {
        return !undecodable && updatesProto == null && !oversize;
    }


    private static int intOf(
        JsonObject object,
        String name
    ) {
        return (int) longOf(object, name);
    }


    private static long longOf(
        JsonObject object,
        String name
    ) {
        JsonElement element = object.get(name);
        if (element == null || !element.isJsonPrimitive()) {
            return 0L;
        }
        return element.getAsLong();
    }
}
