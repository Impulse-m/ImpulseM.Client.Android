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


    private Envelope(
        int type,
        long pts,
        int ptsCount,
        int date,
        byte[] updatesProto,
        boolean oversize
    ) {
        this.type = type;
        this.pts = pts;
        this.ptsCount = ptsCount;
        this.date = date;
        this.updatesProto = updatesProto;
        this.oversize = oversize;
    }


    /**
     * @param data the {@code data} object of a publication.
     * @throws IllegalArgumentException when the value is not an object or the body is not valid base64.
     */
    public static Envelope parse(JsonElement data) {
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
        return new Envelope(
            intOf(object, "type"),
            longOf(object, "pts"),
            intOf(object, "ptsCount"),
            intOf(object, "date"),
            body,
            object.has("oversize") && object.get("oversize").isJsonPrimitive() && object.get("oversize").getAsBoolean()
        );
    }


    /** A body-less, non-oversize frame: the channel moved on and the client must resume from {@link #pts}. */
    public boolean isChannelTooLongSignal() {
        return updatesProto == null && !oversize;
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
