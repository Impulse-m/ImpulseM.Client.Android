package net.impulsem.transport.realtime;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.nio.charset.StandardCharsets;
import okio.ByteString;


/**
 * The update envelope carried in the {@code data} of a publication. Every frame carries a non-empty body and a
 * positive {@code type} and {@code date}; {@code pts} and {@code ptsCount} are present only on numbered frames,
 * and their absence reads as 0.
 */
public final class Envelope {

    public final int type;
    public final long pts;
    public final int ptsCount;
    public final int date;
    /** The decoded {@code v1.Updates} proto; null only when the envelope is undecodable. */
    public final byte[] updatesProto;
    private final boolean undecodable;


    private Envelope(
        int type,
        long pts,
        int ptsCount,
        int date,
        byte[] updatesProto,
        boolean undecodable
    ) {
        this.type = type;
        this.pts = pts;
        this.ptsCount = ptsCount;
        this.date = date;
        this.updatesProto = updatesProto;
        this.undecodable = undecodable;
    }


    /**
     * Never throws. Anything that breaks the envelope contract (not an object, non-numeric fields, a missing or
     * invalid body, a missing type or date) yields an envelope with {@link #undecodable()} set, so the
     * application can still react, for example with getDifference.
     *
     * @param data the {@code data} object of a publication.
     */
    public static Envelope parse(JsonElement data) {
        try {
            return parseStrict(data);
        } catch (RuntimeException e) {
            return new Envelope(0, 0L, 0, 0, null, true);
        }
    }


    /**
     * Reads the raw publication data, as the Protobuf SDK delivers it: UTF-8 JSON, then {@link #parse(JsonElement)}.
     * Never throws. Bytes that are not JSON, or not a JSON object, yield an envelope with {@link #undecodable()} set.
     *
     * @param data the {@code data} bytes of a publication; null is treated as undecodable.
     */
    public static Envelope parseBytes(byte[] data) {
        if (data == null) {
            return parse(null);
        }
        try {
            return parse(JsonParser.parseString(new String(data, StandardCharsets.UTF_8)));
        } catch (RuntimeException e) {
            return parse(null);
        }
    }


    private static Envelope parseStrict(JsonElement data) {
        if (data == null || !data.isJsonObject()) {
            throw new IllegalArgumentException("envelope is not a JSON object");
        }
        JsonObject object = data.getAsJsonObject();
        JsonElement bodyElement = object.get("data");
        if (bodyElement == null || !bodyElement.isJsonPrimitive() || !bodyElement.getAsJsonPrimitive().isString()) {
            throw new IllegalArgumentException("envelope has no data");
        }
        ByteString decoded = ByteString.decodeBase64(bodyElement.getAsString());
        if (decoded == null || decoded.size() == 0) {
            throw new IllegalArgumentException("envelope data is empty or not valid base64");
        }
        return new Envelope(
            requiredPositive(object, "type"),
            longOf(object, "pts"),
            intOf(object, "ptsCount"),
            requiredPositive(object, "date"),
            decoded.toByteArray(),
            false
        );
    }


    /** True when the publication could not be read; nothing else in the envelope is meaningful then. */
    public boolean undecodable() {
        return undecodable;
    }


    private static int requiredPositive(
        JsonObject object,
        String name
    ) {
        int value = intOf(object, name);
        if (value <= 0) {
            throw new IllegalArgumentException("envelope " + name + " is missing or not positive");
        }
        return value;
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
