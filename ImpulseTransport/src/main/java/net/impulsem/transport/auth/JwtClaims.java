package net.impulsem.transport.auth;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.ByteArrayOutputStream;


/**
 * The claims this client reads from a JWT. The signature is not checked: the server verifies the token, the client only
 * schedules around its times.
 */
public final class JwtClaims {

    private static final long Absent = -1L;

    /** The exp claim in unix seconds; -1 when absent or not a positive number. */
    public final long exp;
    /** The iat claim in unix seconds; -1 when absent or not a positive number. */
    public final long iat;
    /** The orig_iat claim in unix seconds; -1 when absent or not a positive number. */
    public final long origIat;
    /** The sub claim; null when absent. */
    public final String sub;


    private JwtClaims(
        long exp,
        long iat,
        long origIat,
        String sub
    ) {
        this.exp = exp;
        this.iat = iat;
        this.origIat = origIat;
        this.sub = sub;
    }


    /** Reads the payload of a JWT; null when the token is not a readable JWT whose payload is a JSON object. */
    public static JwtClaims parse(String jwt) {
        try {
            int first = jwt.indexOf('.');
            int second = first < 0 ? -1 : jwt.indexOf('.', first + 1);
            if (first < 0 || second < 0) {
                return null;
            }
            byte[] payload = decodeBase64Url(jwt.substring(first + 1, second));
            JsonElement root = JsonParser.parseString(new String(payload, "UTF-8"));
            if (!root.isJsonObject()) {
                return null;
            }
            JsonObject object = root.getAsJsonObject();
            return new JwtClaims(
                positive(object, "exp"),
                positive(object, "iat"),
                positive(object, "orig_iat"),
                string(object, "sub")
            );
        } catch (Exception e) {
            return null;
        }
    }


    private static long positive(
        JsonObject object,
        String name
    ) {
        JsonElement element = object.get(name);
        if (element == null || !element.isJsonPrimitive()) {
            return Absent;
        }
        try {
            long value = element.getAsLong();
            return value > 0 ? value : Absent;
        } catch (RuntimeException e) {
            return Absent;
        }
    }


    private static String string(
        JsonObject object,
        String name
    ) {
        JsonElement element = object.get(name);
        if (element == null || !element.isJsonPrimitive()) {
            return null;
        }
        return element.getAsString();
    }


    /** Hand-rolled so the module does not need java.util.Base64 (absent below Android API 26). */
    private static byte[] decodeBase64Url(String text) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int buffer = 0;
        int bits = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            int value;
            if (c >= 'A' && c <= 'Z') {
                value = c - 'A';
            } else if (c >= 'a' && c <= 'z') {
                value = c - 'a' + 26;
            } else if (c >= '0' && c <= '9') {
                value = c - '0' + 52;
            } else if (c == '-' || c == '+') {
                value = 62;
            } else if (c == '_' || c == '/') {
                value = 63;
            } else if (c == '=') {
                break;
            } else {
                throw new IllegalArgumentException("bad base64url character");
            }
            buffer = (buffer << 6) | value;
            bits += 6;
            if (bits >= 8) {
                bits -= 8;
                out.write((buffer >> bits) & 0xFF);
            }
        }
        return out.toByteArray();
    }
}
