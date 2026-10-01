package net.impulsem.transport.wire;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;


public class WireKnownAnswerTest {

    private static byte[] b(int... values) {
        byte[] result = new byte[values.length];
        for (int i = 0; i < values.length; i++) {
            result[i] = (byte) values[i];
        }
        return result;
    }


    private static byte[] filled(int size, int value) {
        byte[] result = new byte[size];
        for (int i = 0; i < size; i++) {
            result[i] = (byte) value;
        }
        return result;
    }


    private static byte[] tlBytes(byte[] payload) {
        TlWriter writer = new TlWriter();
        writer.writeBytes(payload);
        return writer.toByteArray();
    }


    @Test
    public void tlBytesSmall() {
        byte[] encoded = tlBytes(b(1, 2, 3));
        assertArrayEquals(b(0x03, 0x01, 0x02, 0x03), encoded);
        assertArrayEquals(b(1, 2, 3), new TlReader(encoded, 0, encoded.length).readBytes());
    }


    @Test
    public void tlBytesEmpty() {
        byte[] encoded = tlBytes(new byte[0]);
        assertArrayEquals(b(0, 0, 0, 0), encoded);
        assertEquals(0, new TlReader(encoded, 0, encoded.length).readBytes().length);
    }


    @Test
    public void tlBytes253() {
        byte[] payload = filled(253, 0x11);
        byte[] encoded = tlBytes(payload);
        assertEquals(256, encoded.length);
        assertEquals((byte) 0xFD, encoded[0]);
        for (int i = 1; i <= 253; i++) {
            assertEquals(0x11, encoded[i]);
        }
        assertEquals(0, encoded[254]);
        assertEquals(0, encoded[255]);
        assertArrayEquals(payload, new TlReader(encoded, 0, encoded.length).readBytes());
    }


    @Test
    public void tlBytes254() {
        byte[] payload = filled(254, 0x22);
        byte[] encoded = tlBytes(payload);
        assertEquals(260, encoded.length);
        assertArrayEquals(b(0xFE, 0xFE, 0x00, 0x00), java.util.Arrays.copyOf(encoded, 4));
        for (int i = 4; i < 258; i++) {
            assertEquals(0x22, encoded[i]);
        }
        assertEquals(0, encoded[258]);
        assertEquals(0, encoded[259]);
        assertArrayEquals(payload, new TlReader(encoded, 0, encoded.length).readBytes());
    }


    @Test
    public void tlScalars() {
        TlWriter writer = new TlWriter();
        writer.writeInt32(0x01020304);
        assertArrayEquals(b(0x04, 0x03, 0x02, 0x01), writer.toByteArray());

        writer = new TlWriter();
        writer.writeInt64(1);
        assertArrayEquals(b(1, 0, 0, 0, 0, 0, 0, 0), writer.toByteArray());

        writer = new TlWriter();
        writer.writeDouble(1.5);
        byte[] encoded = writer.toByteArray();
        assertArrayEquals(b(0, 0, 0, 0, 0, 0, 0xF8, 0x3F), encoded);
        assertEquals(1.5, new TlReader(encoded, 0, 8).readDouble(), 0.0);
        assertEquals(1L, new TlReader(b(1, 0, 0, 0, 0, 0, 0, 0), 0, 8).readInt64());
        assertEquals(0x01020304, new TlReader(b(4, 3, 2, 1), 0, 4).readInt32());
    }


    @Test
    public void protoVarint300() {
        ProtoWriter writer = new ProtoWriter();
        writer.writeVarintField(1, 300);
        byte[] encoded = writer.toByteArray();
        assertArrayEquals(b(0x08, 0xAC, 0x02), encoded);
        ProtoReader reader = new ProtoReader(encoded, 0, encoded.length);
        assertTrue(reader.next());
        assertEquals(300, reader.readVarint());
    }


    @Test
    public void protoNegativeInt32() {
        ProtoWriter writer = new ProtoWriter();
        writer.writeVarintField(1, -5);
        byte[] encoded = writer.toByteArray();
        assertArrayEquals(b(0x08, 0xFB, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0x01), encoded);
        ProtoReader reader = new ProtoReader(encoded, 0, encoded.length);
        reader.next();
        assertEquals(-5, (int) reader.readVarint());
    }


    @Test
    public void protoBytesField() {
        ProtoWriter writer = new ProtoWriter();
        writer.writeBytesField(2, b(0x61));
        byte[] encoded = writer.toByteArray();
        assertArrayEquals(b(0x12, 0x01, 0x61), encoded);
        ProtoReader reader = new ProtoReader(encoded, 0, encoded.length);
        reader.next();
        assertEquals(2, reader.field());
        assertArrayEquals(b(0x61), reader.readBytes());
    }


    @Test
    public void protoFixed64Field() {
        ProtoWriter writer = new ProtoWriter();
        writer.writeFixed64Field(3, Double.doubleToLongBits(1.5));
        byte[] encoded = writer.toByteArray();
        assertArrayEquals(b(0x19, 0, 0, 0, 0, 0, 0, 0xF8, 0x3F), encoded);
        ProtoReader reader = new ProtoReader(encoded, 0, encoded.length);
        reader.next();
        assertEquals(1.5, Double.longBitsToDouble(reader.readFixed64()), 0.0);
    }


    @Test
    public void protoPackedVarints() {
        ProtoWriter writer = new ProtoWriter();
        writer.writePackedVarints(4, new long[] {1, 2, 300});
        byte[] encoded = writer.toByteArray();
        assertArrayEquals(b(0x22, 0x04, 0x01, 0x02, 0xAC, 0x02), encoded);
        ProtoReader reader = new ProtoReader(encoded, 0, encoded.length);
        reader.next();
        int[] bounds = reader.readLengthDelimitedBounds();
        ProtoReader inner = new ProtoReader(encoded, bounds[0], bounds[1]);
        assertEquals(1, inner.readVarint());
        assertEquals(2, inner.readVarint());
        assertEquals(300, inner.readVarint());
    }
}
