package net.impulsem.transport.auth;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;


public class ImpulseRpcPathsTest {

    @Test
    public void emptyIdentityTokenIsOmittedAndApiIdIsAVarint() {
        assertArrayEquals(new byte[] {0x10, 0x06}, ImpulseRpcPaths.encodeConnectionIdentity("", 6));
    }


    @Test
    public void tokenAndApiIdAreEncodedInFieldOrder() {
        byte[] expected = {0x0A, 0x03, 'a', 'b', 'c', 0x10, 0x06};
        assertArrayEquals(expected, ImpulseRpcPaths.encodeConnectionIdentity("abc", 6));
    }


    @Test
    public void zeroApiIdIsOmitted() {
        byte[] expected = {0x0A, 0x03, 'a', 'b', 'c'};
        assertArrayEquals(expected, ImpulseRpcPaths.encodeConnectionIdentity("abc", 0));
    }


    @Test
    public void decodesTheToken() {
        byte[] reply = {0x0A, 0x03, 'a', 'b', 'c'};
        assertEquals("abc", ImpulseRpcPaths.decodeConnectionIdentity(reply));
    }


    @Test
    public void absentOrEmptyTokenDecodesToNull() {
        assertNull(ImpulseRpcPaths.decodeConnectionIdentity(new byte[0]));
        assertNull(ImpulseRpcPaths.decodeConnectionIdentity(new byte[] {0x0A, 0x00}));
    }
}
