package net.impulsem.transport.calls;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.Test;

import java.security.SecureRandom;
import java.util.HashSet;
import java.util.Set;

import static org.junit.Assert.*;


public class LiveKitContractTest {
    @Test
    public void readsCanonicalParametersAndRejectsCrossCallCredentials() {
        String json = "{\"livekit\":{\"url\":\"wss://media.example.test\","
            + "\"token\":\"secret-token\",\"room\":\"p.42\"}}";
        LiveKitContract.JoinParameters parameters = LiveKitContract.parseJoin(json, false, 42);
        assertEquals("p.42", parameters.room);
        assertEquals("secret-token", parameters.token);
        assertFalse(parameters.toString().contains("secret-token"));
        assertThrows(IllegalArgumentException.class, () -> LiveKitContract.parseJoin(json, true, 42));
        assertThrows(IllegalArgumentException.class, () -> LiveKitContract.parseJoin(json, false, 43));
        assertThrows(IllegalArgumentException.class, () -> LiveKitContract.parseJoin(
            "{\"livekit_url\":\"wss://media.example.test\",\"livekit_token\":\"secret-token\"}", false, 42
        ));
        assertThrows(IllegalArgumentException.class, () -> LiveKitContract.parseJoin(
            json.replace("wss://", "https://"), false, 42
        ));
        assertThrows(IllegalArgumentException.class, () -> LiveKitContract.parseJoin(
            json.replace("secret-token", ""), false, 42
        ));
    }


    @Test
    public void usesRawPaddedDhKeyAndDefensiveCopies() {
        byte[] key = new byte[256];
        key[255] = (byte) 255;
        byte[] material = LiveKitContract.mediaKey(key);
        assertArrayEquals(key, material);
        key[0] = 1;
        assertEquals(0, material[0]);
        assertThrows(IllegalArgumentException.class, () -> LiveKitContract.mediaKey(new byte[255]));
        assertThrows(IllegalArgumentException.class, () -> LiveKitContract.mediaKey(new byte[257]));
    }


    @Test
    public void routesSignedSourcesAndPresentationIdentities() {
        assertEquals(-1, LiveKitContract.groupSource("u42.4294967295"));
        assertEquals(Integer.MIN_VALUE, LiveKitContract.groupSource("h123.2147483648.p"));
        assertEquals(5, LiveKitContract.groupSource("c7.5"));
        for (String invalid : new String[] {"u42", "u42.0", "u42.4294967296", "u42.-1", "u042.5", "x42.5"}) {
            assertThrows(invalid, IllegalArgumentException.class, () -> LiveKitContract.groupSource(invalid));
        }
    }


    @Test
    public void declaresCameraLayersEvenBeforeVideoIsEnabled() {
        LiveKitContract.JoinPayload payload = LiveKitContract.createJoinPayload(false, new SecureRandom());
        JsonObject json = JsonParser.parseString(payload.json).getAsJsonObject();
        assertEquals(Integer.toUnsignedLong(payload.source), json.get("ssrc").getAsLong());
        assertNotEquals(0, payload.source);
        JsonArray groups = json.getAsJsonArray("ssrc-groups");
        assertEquals(4, groups.size());
        assertEquals("SIM", groups.get(0).getAsJsonObject().get("semantics").getAsString());
        Set<Long> unique = new HashSet<>();
        unique.add(Integer.toUnsignedLong(payload.source));
        JsonArray layers = groups.get(0).getAsJsonObject().getAsJsonArray("sources");
        for (int i = 0; i < 3; i++) {
            JsonArray fid = groups.get(i + 1).getAsJsonObject().getAsJsonArray("sources");
            assertEquals(layers.get(i), fid.get(0));
            assertTrue(unique.add(fid.get(0).getAsLong()));
            assertTrue(unique.add(fid.get(1).getAsLong()));
        }
        JsonArray screen = JsonParser.parseString(
            LiveKitContract.createJoinPayload(true, new SecureRandom()).json
        ).getAsJsonObject().getAsJsonArray("ssrc-groups");
        assertEquals(1, screen.size());
        assertEquals("FID", screen.get(0).getAsJsonObject().get("semantics").getAsString());
    }
}
