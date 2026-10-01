package net.impulsem.transport.wire;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;

import org.junit.Test;


public class TlWriterReaderTest {

    @Test
    public void bytesRoundTripAtBoundaries() {
        int[] sizes = {0, 1, 253, 254, 300};
        for (int size : sizes) {
            byte[] payload = new byte[size];
            for (int i = 0; i < size; i++) {
                payload[i] = (byte) (i * 7 + 1);
            }
            TlWriter writer = new TlWriter();
            writer.writeBytes(payload);
            byte[] encoded = writer.toByteArray();
            assertEquals("padding for size " + size, 0, encoded.length % 4);
            int header = size < 254 ? 1 : 4;
            assertEquals(((header + size + 3) / 4) * 4, encoded.length);
            TlReader reader = new TlReader(encoded, 0, encoded.length);
            assertArrayEquals(payload, reader.readBytes());
            assertEquals(0, reader.remaining());
        }
    }

    @Test
    public void longFormUsesMarkerByte() {
        TlWriter writer = new TlWriter();
        writer.writeBytes(new byte[254]);
        byte[] encoded = writer.toByteArray();
        assertEquals((byte) 0xFE, encoded[0]);
        assertEquals((byte) 254, encoded[1]);
        assertEquals(0, encoded[2]);
        assertEquals(0, encoded[3]);
    }

    @Test
    public void numbersAreLittleEndian() {
        TlWriter writer = new TlWriter();
        writer.writeInt32(0x01020304);
        writer.writeInt64(0x0102030405060708L);
        writer.writeDouble(1.5);
        byte[] encoded = writer.toByteArray();
        assertEquals(4, encoded[0]);
        assertEquals(1, encoded[3]);
        assertEquals(8, encoded[4]);
        assertEquals(1, encoded[11]);
        TlReader reader = new TlReader(encoded, 0, encoded.length);
        assertEquals(0x01020304, reader.readInt32());
        assertEquals(0x0102030405060708L, reader.readInt64());
        assertEquals(1.5, reader.readDouble(), 0.0);
        assertEquals(20, reader.position());
    }

    @Test
    public void stringAndRawRoundTrip() {
        TlWriter writer = new TlWriter();
        writer.writeString("héllo 世界");
        writer.writeRaw(new byte[] {1, 2, 3, 4});
        byte[] encoded = writer.toByteArray();
        TlReader reader = new TlReader(encoded, 0, encoded.length);
        assertEquals("héllo 世界", reader.readString());
        assertArrayEquals(new byte[] {1, 2, 3, 4}, reader.readRaw(4));
    }

    @Test
    public void patchInt32() {
        TlWriter writer = new TlWriter();
        writer.writeInt32(0);
        int position = writer.position();
        writer.writeInt32(0);
        writer.writeInt32(7);
        writer.patchInt32(position, 0x55);
        byte[] encoded = writer.toByteArray();
        TlReader reader = new TlReader(encoded, 0, encoded.length);
        assertEquals(0, reader.readInt32());
        assertEquals(0x55, reader.readInt32());
        assertEquals(7, reader.readInt32());
    }

    @Test
    public void readerHonorsOffset() {
        TlWriter writer = new TlWriter();
        writer.writeInt32(9);
        writer.writeInt32(10);
        byte[] encoded = writer.toByteArray();
        TlReader reader = new TlReader(encoded, 4, 4);
        assertEquals(4, reader.remaining());
        assertEquals(10, reader.readInt32());
    }
}
