package net.impulsem.transport.realtime;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import java.nio.charset.StandardCharsets;
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
        assertFalse(envelope.undecodable());
    }


    @Test
    public void ptsLessFrameReadsPtsAsZero() {
        Envelope envelope = Envelope.parse(json("{\"type\":7,\"date\":5,\"data\":\"aGVsbG8=\"}"));
        assertFalse(envelope.undecodable());
        assertEquals(0L, envelope.pts);
        assertEquals(0, envelope.ptsCount);
    }


    @Test
    public void framesBreakingTheContractAreUndecodable() {
        String[] frames = new String[] {
            "{\"type\":215,\"pts\":900,\"date\":5}",
            "{\"type\":1,\"pts\":7,\"ptsCount\":1,\"date\":5,\"oversize\":true}",
            "{\"type\":1,\"date\":5,\"data\":\"\"}",
            "{\"pts\":1,\"date\":5,\"data\":\"aGVsbG8=\"}",
            "{\"type\":1,\"pts\":1,\"data\":\"aGVsbG8=\"}",
            "{\"type\":1,\"date\":0,\"data\":\"aGVsbG8=\"}"
        };
        for (String frame : frames) {
            Envelope envelope = Envelope.parse(json(frame));
            assertTrue(frame, envelope.undecodable());
            assertNull(frame, envelope.updatesProto);
        }
    }


    @Test
    public void invalidBase64IsUndecodable() {
        Envelope envelope = Envelope.parse(json("{\"type\":1,\"pts\":1,\"date\":5,\"data\":\"!!!not base64!!!\"}"));
        assertTrue(envelope.undecodable());
    }


    @Test
    public void nonObjectAndNonNumericAreUndecodable() {
        assertTrue(Envelope.parse(json("\"text\"")).undecodable());
        assertTrue(Envelope.parse(null).undecodable());
        assertTrue(Envelope.parse(json("{\"type\":1,\"pts\":\"zzz\",\"date\":5,\"data\":\"aGVsbG8=\"}")).undecodable());
        assertFalse(Envelope.parse(json("{\"type\":1,\"pts\":1,\"date\":5,\"data\":\"aGVsbG8=\"}")).undecodable());
    }


    @Test
    public void bytesEntryPointDecodesTheJsonBody() {
        byte[] data = "{\"type\":1,\"pts\":42,\"ptsCount\":2,\"date\":1700000000,\"data\":\"aGVsbG8=\"}".getBytes(StandardCharsets.UTF_8);
        Envelope envelope = Envelope.parseBytes(data);
        assertFalse(envelope.undecodable());
        assertEquals(42L, envelope.pts);
        assertArrayEquals("hello".getBytes(), envelope.updatesProto);
    }


    @Test
    public void bytesThatAreNotAJsonObjectAreUndecodableAndNeverThrow() {
        byte[][] garbage = new byte[][] {
            "not json".getBytes(StandardCharsets.UTF_8),
            "[1,2]".getBytes(StandardCharsets.UTF_8),
            "{\"pts\":".getBytes(StandardCharsets.UTF_8),
            new byte[0],
            new byte[] {(byte) 0xff, (byte) 0xfe, 0},
            null
        };
        for (byte[] bytes : garbage) {
            assertTrue(Envelope.parseBytes(bytes).undecodable());
        }
    }
}
