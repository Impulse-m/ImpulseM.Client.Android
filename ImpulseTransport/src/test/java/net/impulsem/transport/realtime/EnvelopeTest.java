package net.impulsem.transport.realtime;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import org.junit.Test;


public class EnvelopeTest {

    private static JsonElement json(String text) {
        return JsonParser.parseString(text);
    }


    @Test
    public void bodyEnvelopeDecodesBase64() {
        // base64("hello") == aGVsbG8=
        Envelope envelope = Envelope.parse(json("{\"type\":1,\"pts\":42,\"ptsCount\":2,\"date\":1700000000,\"data\":\"aGVsbG8=\"}"));
        assertEquals(1, envelope.type);
        assertEquals(42L, envelope.pts);
        assertEquals(2, envelope.ptsCount);
        assertEquals(1700000000, envelope.date);
        assertArrayEquals("hello".getBytes(), envelope.updatesProto);
        assertFalse(envelope.oversize);
        assertFalse(envelope.isChannelTooLongSignal());
    }


    @Test
    public void oversizeSentinelHasNoBody() {
        Envelope envelope = Envelope.parse(json("{\"type\":1,\"pts\":7,\"ptsCount\":1,\"date\":5,\"oversize\":true}"));
        assertNull(envelope.updatesProto);
        assertTrue(envelope.oversize);
        assertFalse(envelope.isChannelTooLongSignal());
    }


    @Test
    public void bodylessChannelFrameIsTooLongSignal() {
        Envelope envelope = Envelope.parse(json("{\"type\":215,\"pts\":900,\"date\":5}"));
        assertNull(envelope.updatesProto);
        assertFalse(envelope.oversize);
        assertTrue(envelope.isChannelTooLongSignal());
        assertEquals(215, envelope.type);
        assertEquals(900L, envelope.pts);
        assertEquals(0, envelope.ptsCount);
    }


    @Test
    public void invalidBase64IsUndecodable() {
        Envelope envelope = Envelope.parse(json("{\"type\":1,\"pts\":1,\"data\":\"!!!not base64!!!\"}"));
        assertTrue(envelope.undecodable());
        assertFalse(envelope.isChannelTooLongSignal());
    }


    @Test
    public void nonObjectAndNonNumericAreUndecodable() {
        assertTrue(Envelope.parse(json("\"text\"")).undecodable());
        assertTrue(Envelope.parse(null).undecodable());
        assertTrue(Envelope.parse(json("{\"type\":1,\"pts\":\"zzz\"}")).undecodable());
        assertFalse(Envelope.parse(json("{\"type\":1,\"pts\":1}")).undecodable());
    }
}
