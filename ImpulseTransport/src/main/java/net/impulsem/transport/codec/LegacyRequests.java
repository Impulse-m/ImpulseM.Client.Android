package net.impulsem.transport.codec;

import java.util.Arrays;


/**
 * Rewrites requests that the app still serializes with an older constructor into the layer 229
 * form the mapping knows.
 */
public final class LegacyRequests {

    private static final int SignUpLegacy = 0x80eee427;
    private static final int SignUpCurrent = 0xaac7b717;


    private LegacyRequests() {
    }


    /** auth.signUp#80eee427 gains the leading flags word of auth.signUp#aac7b717 (no flags set). */
    public static byte[] upgrade(byte[] tl) {
        if (tl.length < 4 || readInt32(tl) != SignUpLegacy) {
            return tl;
        }
        byte[] result = new byte[tl.length + 4];
        writeInt32(result, 0, SignUpCurrent);
        writeInt32(result, 4, 0);
        System.arraycopy(tl, 4, result, 8, tl.length - 4);
        return result;
    }


    public static int readInt32(byte[] data) {
        return (data[0] & 0xFF) | ((data[1] & 0xFF) << 8) | ((data[2] & 0xFF) << 16) | ((data[3] & 0xFF) << 24);
    }


    private static void writeInt32(
        byte[] data,
        int offset,
        int value
    ) {
        data[offset] = (byte) value;
        data[offset + 1] = (byte) (value >>> 8);
        data[offset + 2] = (byte) (value >>> 16);
        data[offset + 3] = (byte) (value >>> 24);
    }
}
