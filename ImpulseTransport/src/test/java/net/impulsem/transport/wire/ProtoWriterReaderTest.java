package net.impulsem.transport.wire;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;


public class ProtoWriterReaderTest {

    @Test
    public void negativeInt32IsTenByteVarint() {
        ProtoWriter writer = new ProtoWriter();
        writer.writeVarintField(1, -5);
        byte[] encoded = writer.toByteArray();
        assertEquals(11, encoded.length);
        ProtoReader reader = new ProtoReader(encoded, 0, encoded.length);
        assertTrue(reader.next());
        assertEquals(1, reader.field());
        assertEquals(0, reader.wireType());
        assertEquals(-5, (int) reader.readVarint());
        assertFalse(reader.next());
    }

    @Test
    public void packedAndUnpackedRepeatedBothDecode() {
        long[] values = {1, -2, 300, Long.MAX_VALUE};
        ProtoWriter packed = new ProtoWriter();
        packed.writePackedVarints(2, values);
        byte[] packedBytes = packed.toByteArray();
        ProtoReader reader = new ProtoReader(packedBytes, 0, packedBytes.length);
        assertTrue(reader.next());
        assertEquals(2, reader.wireType());
        int[] bounds = reader.readLengthDelimitedBounds();
        ProtoReader inner = new ProtoReader(packedBytes, bounds[0], bounds[1]);
        long[] decoded = new long[values.length];
        int index = 0;
        while (inner.hasMore()) {
            decoded[index++] = inner.readVarint();
        }
        assertArrayEquals(values, decoded);

        ProtoWriter unpacked = new ProtoWriter();
        for (long value : values) {
            unpacked.writeVarintField(2, value);
        }
        byte[] unpackedBytes = unpacked.toByteArray();
        ProtoReader reader2 = new ProtoReader(unpackedBytes, 0, unpackedBytes.length);
        index = 0;
        while (reader2.next()) {
            assertEquals(0, reader2.wireType());
            assertEquals(values[index++], reader2.readVarint());
        }
        assertEquals(values.length, index);
    }

    @Test
    public void packedFixed64() {
        ProtoWriter writer = new ProtoWriter();
        writer.writePackedFixed64(3, new long[] {1L, -1L});
        byte[] encoded = writer.toByteArray();
        ProtoReader reader = new ProtoReader(encoded, 0, encoded.length);
        assertTrue(reader.next());
        int[] bounds = reader.readLengthDelimitedBounds();
        assertEquals(16, bounds[1]);
        ProtoReader inner = new ProtoReader(encoded, bounds[0], bounds[1]);
        assertEquals(1L, inner.readFixed64());
        assertEquals(-1L, inner.readFixed64());
    }

    @Test
    public void unknownFieldsOfEveryWireTypeAreSkipped() {
        ProtoWriter writer = new ProtoWriter();
        writer.writeVarintField(10, 12345);
        writer.writeFixed64Field(11, 0x1122334455667788L);
        writer.writeBytesField(12, new byte[] {9, 9, 9});
        byte[] fixed32Field = {(byte) ((13 << 3) | 5), 1, 2, 3, 4};
        byte[] head = writer.toByteArray();
        byte[] all = new byte[head.length + fixed32Field.length + 2];
        System.arraycopy(head, 0, all, 0, head.length);
        System.arraycopy(fixed32Field, 0, all, head.length, fixed32Field.length);
        all[all.length - 2] = (byte) (14 << 3);
        all[all.length - 1] = 42;
        ProtoReader reader = new ProtoReader(all, 0, all.length);
        int seen = 0;
        int last = 0;
        while (reader.next()) {
            seen++;
            if (reader.field() == 14) {
                last = (int) reader.readVarint();
            } else {
                reader.skip();
            }
        }
        assertEquals(5, seen);
        assertEquals(42, last);
    }

    @Test
    public void lengthDelimitedBoundsAreCorrect() {
        ProtoWriter writer = new ProtoWriter();
        writer.writeVarintField(1, 1);
        writer.writeBytesField(2, new byte[] {5, 6, 7});
        byte[] encoded = writer.toByteArray();
        ProtoReader reader = new ProtoReader(encoded, 0, encoded.length);
        reader.next();
        reader.skip();
        assertTrue(reader.next());
        int[] bounds = reader.readLengthDelimitedBounds();
        assertEquals(3, bounds[1]);
        assertEquals(5, encoded[bounds[0]]);
        assertEquals(7, encoded[bounds[0] + 2]);
    }

    @Test
    public void readBytesCopiesPayload() {
        ProtoWriter writer = new ProtoWriter();
        writer.writeBytesField(4, new byte[] {1, 2});
        byte[] encoded = writer.toByteArray();
        ProtoReader reader = new ProtoReader(encoded, 0, encoded.length);
        reader.next();
        assertArrayEquals(new byte[] {1, 2}, reader.readBytes());
    }
}
