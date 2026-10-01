package net.impulsem.transport.codec;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import net.impulsem.transport.schema.TlProtoSchema;
import org.junit.Test;


public class LegacyRequestsTest {

    private static byte[] legacySignUp() {
        // 80eee427, then four TL strings "a", "b", "c", "d" (1 length byte, 1 char, 2 padding).
        return new byte[] {
            0x27, (byte) 0xe4, (byte) 0xee, (byte) 0x80,
            1, 'a', 0, 0,
            1, 'b', 0, 0,
            1, 'c', 0, 0,
            1, 'd', 0, 0
        };
    }


    @Test
    public void legacySignUpGainsTheFlagsWord() {
        byte[] upgraded = LegacyRequests.upgrade(legacySignUp());
        assertEquals(0xaac7b717, LegacyRequests.readInt32(upgraded));
        assertEquals(0, LegacyRequests.readInt32(java.util.Arrays.copyOfRange(upgraded, 4, 8)));
        assertArrayEquals(
            java.util.Arrays.copyOfRange(legacySignUp(), 4, 20),
            java.util.Arrays.copyOfRange(upgraded, 8, 24)
        );
    }


    @Test
    public void otherRequestsAreUntouched() {
        byte[] other = {0x6b, 0x18, (byte) 0xf9, (byte) 0xc4};
        assertSame(other, LegacyRequests.upgrade(other));
    }


    @Test
    public void upgradedSignUpIsInTheMapping() {
        Transcoder transcoder = new Transcoder(TlProtoSchema.load());
        assertTrue(transcoder.hasMethod(LegacyRequests.readInt32(LegacyRequests.upgrade(legacySignUp()))));
        transcoder.encodeRequest(LegacyRequests.upgrade(legacySignUp()));
    }
}
