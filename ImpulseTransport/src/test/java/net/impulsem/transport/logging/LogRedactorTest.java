package net.impulsem.transport.logging;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;


public class LogRedactorTest {

    @Test
    public void messageTextAfterAContentKeyIsRedactedToTheEndOfTheLine() {
        String sanitized = LogRedactor.sanitizeText("send message=hello there, see you at 5");

        assertEquals("send message=" + LogRedactor.Redacted, sanitized);
    }


    @Test
    public void jsonMembersNamedLikePersonalDataAreRedactedAndErrorCodesSurvive() {
        String push = "{\"loc_key\":\"MESSAGE_TEXT\",\"loc_args\":[\"Alice\",\"secret plan\"],"
            + "\"message\":\"hi\",\"first_name\":\"Alice\",\"error\":\"FLOOD_WAIT_5\",\"text\":\"PEER_ID_INVALID\"}";

        String sanitized = LogRedactor.sanitizeText(push);

        assertFalse(sanitized, sanitized.contains("Alice"));
        assertFalse(sanitized, sanitized.contains("secret plan"));
        assertFalse(sanitized, sanitized.contains("\"hi\""));
        assertTrue(sanitized, sanitized.contains("\"loc_key\":\"MESSAGE_TEXT\""));
        assertTrue(sanitized, sanitized.contains("FLOOD_WAIT_5"));
        assertTrue("an error code under a content key is not personal data: " + sanitized, sanitized.contains("PEER_ID_INVALID"));
    }


    @Test
    public void tokensAuthKeysAndPushPayloadsAreRedacted() {
        String fcmToken = "dXJx3kqQ:APA91bHk2l9Zq0vTqY8rW6pLm4nB7cD1eF3gH5iJ7kL9mN0oP2qR4sT6uV8wX0yZ";
        String hexKey = "a3f19c0b77d24e5f8a6b1c2d3e4f5a6b7c8d9e0f1a2b3c4d5e6f7a8b9c0d1e2f";

        String token = LogRedactor.sanitizeText("Refreshed FCM token: " + fcmToken);
        String saved = LogRedactor.sanitizeText("saveLogInToken " + fcmToken);
        String key = LogRedactor.sanitizeText("FCM DECRYPT ERROR 3, key = " + hexKey);
        String bearer = LogRedactor.sanitizeText("Authorization: Bearer abc.def.ghi");
        String jwt = LogRedactor.sanitizeText("refresh eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxIn0.c2lnbmF0dXJl done");

        assertFalse(token, token.contains("APA91b"));
        assertFalse(saved, saved.contains("APA91b"));
        assertFalse(key, key.contains(hexKey));
        assertFalse(bearer, bearer.contains("abc.def.ghi"));
        assertFalse(jwt, jwt.contains("eyJ"));
        assertTrue(jwt, jwt.endsWith(" done"));
    }


    @Test
    public void phoneNumbersEmailsAndFilePathsAreRedacted() {
        String sanitized = LogRedactor.sanitizeText(
            "login +7 999 123-45-67 mail me@example.com file /storage/emulated/0/DCIM/holiday.jpg"
        );

        assertFalse(sanitized, sanitized.contains("999"));
        assertFalse(sanitized, sanitized.contains("example.com"));
        assertFalse(sanitized, sanitized.contains("holiday"));
    }


    @Test
    public void ordinaryDiagnosticsAreLeftReadable() {
        String line = "processUpdates TL_updates seq=12 pts=40 count=1 class org.telegram.messenger.MessagesController";

        assertEquals(line, LogRedactor.sanitizeText(line));
    }


    @Test
    public void structuredNumbersAndBooleansSurviveButSensitiveNamesDoNot() {
        assertEquals(5000000001L, LogRedactor.sanitizeField("callId", 5000000001L));
        assertEquals(Boolean.TRUE, LogRedactor.sanitizeField("hasSessionToken", Boolean.TRUE));
        assertEquals(LogRedactor.Redacted, LogRedactor.sanitizeField("phone", 79991234567L));
        assertEquals(LogRedactor.Redacted, LogRedactor.sanitizeField("first_name", "Alice"));
        assertEquals(LogRedactor.Redacted, LogRedactor.sanitizeField("accessToken", "abc"));
        assertEquals("PHONE_CALL_REQUEST", LogRedactor.sanitizeField("locKey", "PHONE_CALL_REQUEST"));
    }
}
