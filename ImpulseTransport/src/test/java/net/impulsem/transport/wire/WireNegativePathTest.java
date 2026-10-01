package net.impulsem.transport.wire;

import org.junit.Test;


public class WireNegativePathTest {

    @Test(expected = RuntimeException.class)
    public void tlReadBytesTruncatedPayload() {
        byte[] data = {0x05, 1, 2};
        new TlReader(data, 0, data.length).readBytes();
    }


    @Test(expected = RuntimeException.class)
    public void tlReadBytesTruncatedLongHeader() {
        byte[] data = {(byte) 0xFE, 0x00};
        new TlReader(data, 0, data.length).readBytes();
    }


    @Test(expected = RuntimeException.class)
    public void tlReadBytesTruncatedLongPayload() {
        byte[] data = {(byte) 0xFE, (byte) 0xFF, 0x00, 0x00, 1, 2, 3, 4};
        new TlReader(data, 0, data.length).readBytes();
    }


    @Test(expected = RuntimeException.class)
    public void tlReadInt64WithSevenBytes() {
        byte[] data = new byte[7];
        new TlReader(data, 0, data.length).readInt64();
    }


    @Test(expected = RuntimeException.class)
    public void tlReadInt32WithThreeBytes() {
        byte[] data = new byte[3];
        new TlReader(data, 0, data.length).readInt32();
    }


    @Test(expected = IllegalArgumentException.class)
    public void tlPatchOutOfRange() {
        TlWriter writer = new TlWriter();
        writer.writeInt32(0);
        writer.patchInt32(2, 1);
    }


    @Test(expected = RuntimeException.class)
    public void protoVarintWithElevenContinuationBytes() {
        byte[] data = new byte[12];
        data[0] = 0x08;
        for (int i = 1; i < 12; i++) {
            data[i] = (byte) 0x80;
        }
        ProtoReader reader = new ProtoReader(data, 0, data.length);
        reader.next();
        reader.readVarint();
    }


    @Test(expected = RuntimeException.class)
    public void protoLengthLargerThanRemaining() {
        byte[] data = {0x12, 0x05, 1, 2};
        ProtoReader reader = new ProtoReader(data, 0, data.length);
        reader.next();
        reader.readBytes();
    }


    @Test(expected = RuntimeException.class)
    public void protoSkipWireType3() {
        byte[] data = {(byte) ((1 << 3) | 3)};
        ProtoReader reader = new ProtoReader(data, 0, data.length);
        reader.next();
        reader.skip();
    }


    @Test(expected = RuntimeException.class)
    public void protoSkipWireType4() {
        byte[] data = {(byte) ((1 << 3) | 4)};
        ProtoReader reader = new ProtoReader(data, 0, data.length);
        reader.next();
        reader.skip();
    }


    @Test(expected = RuntimeException.class)
    public void protoTruncatedFixed64() {
        byte[] data = {(byte) ((1 << 3) | 1), 1, 2, 3};
        ProtoReader reader = new ProtoReader(data, 0, data.length);
        reader.next();
        reader.readFixed64();
    }
}
