package net.impulsem.transport.auth;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import org.junit.Test;


public class JwtClaimsTest {

    private static String token(String payloadJson) {
        Base64.Encoder encoder = Base64.getUrlEncoder().withoutPadding();
        String header = encoder.encodeToString("{\"alg\":\"none\"}".getBytes(StandardCharsets.UTF_8));
        String payload = encoder.encodeToString(payloadJson.getBytes(StandardCharsets.UTF_8));
        return header + "." + payload + ".sig";
    }


    @Test
    public void readsExpIatOrigIatAndSub() {
        JwtClaims claims = JwtClaims.parse(token("{\"sub\":\"7\",\"exp\":2000,\"iat\":1500,\"orig_iat\":1000}"));
        assertNotNull(claims);
        assertEquals(2000L, claims.exp);
        assertEquals(1500L, claims.iat);
        assertEquals(1000L, claims.origIat);
        assertEquals("7", claims.sub);
    }


    @Test
    public void missingClaimsAreMinusOneOrNull() {
        JwtClaims claims = JwtClaims.parse(token("{}"));
        assertNotNull(claims);
        assertEquals(-1L, claims.exp);
        assertEquals(-1L, claims.iat);
        assertEquals(-1L, claims.origIat);
        assertNull(claims.sub);
    }


    @Test
    public void nonPositiveNumbersAreMinusOne() {
        JwtClaims claims = JwtClaims.parse(token("{\"exp\":0,\"iat\":-5,\"orig_iat\":\"soon\"}"));
        assertNotNull(claims);
        assertEquals(-1L, claims.exp);
        assertEquals(-1L, claims.iat);
        assertEquals(-1L, claims.origIat);
    }


    @Test
    public void garbageIsNull() {
        assertNull(JwtClaims.parse("not-a-jwt"));
        assertNull(JwtClaims.parse("a.b"));
        assertNull(JwtClaims.parse("a.!!!.c"));
        assertNull(JwtClaims.parse(token("[1,2,3]")));
        assertNull(JwtClaims.parse(token("{not json")));
        assertNull(JwtClaims.parse(null));
    }


    @Test
    public void base64UrlPayloadWithDashAndUnderscoreDecodes() {
        // The sub is chosen so the payload's base64url text contains '-' or '_'; the decoder must map both.
        String sub = "?>?>?>?>?>?>";
        String payloadJson = "{\"sub\":\"" + sub + "\",\"exp\":2000}";
        String encoded = Base64.getUrlEncoder().withoutPadding().encodeToString(payloadJson.getBytes(StandardCharsets.UTF_8));
        assertTrue("payload must exercise - or _", encoded.indexOf('-') >= 0 || encoded.indexOf('_') >= 0);
        JwtClaims claims = JwtClaims.parse(token(payloadJson));
        assertNotNull(claims);
        assertEquals(sub, claims.sub);
        assertEquals(2000L, claims.exp);
    }
}
