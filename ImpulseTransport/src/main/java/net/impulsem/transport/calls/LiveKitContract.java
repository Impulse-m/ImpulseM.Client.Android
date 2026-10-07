package net.impulsem.transport.calls;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.net.URI;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;


/** Wire contract shared with the ImpulseM server and web client. */
public final class LiveKitContract {
    public static final String Protocol = "impulsem-livekit-1";

    public static final class JoinParameters {
        public final String url;
        public final String token;
        public final String room;

        private JoinParameters(String url, String token, String room) {
            this.url = url;
            this.token = token;
            this.room = room;
        }
    }

    public static final class JoinPayload {
        public final int source;
        public final String json;

        private JoinPayload(int source, String json) {
            this.source = source;
            this.json = json;
        }
    }

    private static final Pattern GroupIdentity = Pattern.compile("[uch][1-9][0-9]*\\.([1-9][0-9]*)(\\.p)?");


    public static JoinParameters parseJoin(String json, boolean group, long callId) {
        try {
            JsonObject livekit = JsonParser.parseString(json).getAsJsonObject().getAsJsonObject("livekit");
            String url = requiredString(livekit, "url");
            String token = requiredString(livekit, "token");
            String room = requiredString(livekit, "room");
            URI uri = URI.create(url);
            if ((!"wss".equals(uri.getScheme()) && !"ws".equals(uri.getScheme()))
                || uri.getHost() == null || uri.getUserInfo() != null || uri.getFragment() != null
                || callId <= 0 || !room.equals((group ? "g." : "p.") + callId)) {
                throw new IllegalArgumentException();
            }
            return new JoinParameters(url, token, room);
        } catch (RuntimeException exception) {
            // Do not attach the parser exception: it can contain the bearer token.
            throw new IllegalArgumentException("Invalid LiveKit join parameters for this call");
        }
    }


    public static byte[] mediaKey(byte[] authKey) {
        if (authKey == null || authKey.length != 256) {
            throw new IllegalArgumentException("Call DH key must contain exactly 256 bytes");
        }
        return Arrays.copyOf(authKey, authKey.length);
    }


    public static int groupSource(String identity) {
        Matcher match = GroupIdentity.matcher(identity);
        if (!match.matches()) {
            throw new IllegalArgumentException("Invalid LiveKit group identity");
        }
        long source = Long.parseLong(match.group(1));
        if (source > 0xffffffffL) {
            throw new IllegalArgumentException("Invalid LiveKit group source");
        }
        return (int) source;
    }


    public static JoinPayload createJoinPayload(boolean presentation, SecureRandom random) {
        Set<Integer> used = new HashSet<>();
        int source = uniqueSource(random, used);
        JsonObject json = new JsonObject();
        json.addProperty("ssrc", Integer.toUnsignedLong(source));
        JsonArray groups = new JsonArray();
        int count = presentation ? 1 : 3;
        int[] layers = new int[count];
        for (int i = 0; i < count; i++) {
            layers[i] = uniqueSource(random, used);
        }
        if (!presentation) {
            groups.add(sourceGroup("SIM", layers));
        }
        for (int layer : layers) {
            groups.add(sourceGroup("FID", layer, uniqueSource(random, used)));
        }
        json.add("ssrc-groups", groups);
        return new JoinPayload(source, json.toString());
    }


    private LiveKitContract() {
    }


    private static String requiredString(JsonObject object, String name) {
        JsonElement value = object.get(name);
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()
            || value.getAsString().trim().isEmpty()) {
            throw new IllegalArgumentException();
        }
        return value.getAsString();
    }


    private static int uniqueSource(SecureRandom random, Set<Integer> used) {
        int source;
        do {
            source = random.nextInt();
        } while (source == 0 || !used.add(source));
        return source;
    }


    private static JsonObject sourceGroup(String semantics, int... sources) {
        JsonObject group = new JsonObject();
        group.addProperty("semantics", semantics);
        JsonArray values = new JsonArray();
        for (int source : sources) {
            values.add(Integer.toUnsignedLong(source));
        }
        group.add("sources", values);
        return group;
    }
}
