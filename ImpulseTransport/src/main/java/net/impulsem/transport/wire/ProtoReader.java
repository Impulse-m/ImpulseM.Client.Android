package net.impulsem.transport.wire;

import java.util.Arrays;


public final class ProtoReader {

    private final byte[] data;
    private final int end;
    private int pos;
    private int field;
    private int wireType;
    private boolean valueConsumed = true;


    public ProtoReader(byte[] data, int offset, int length) {
        if (offset < 0 || length < 0 || offset + length > data.length) {
            throw new IllegalArgumentException("bad bounds");
        }
        this.data = data;
        this.pos = offset;
        this.end = offset + length;
    }


    public boolean hasMore() {
        return pos < end;
    }


    public boolean next() {
        if (!valueConsumed) {
            skip();
        }
        if (pos >= end) {
            return false;
        }
        long tag = rawVarint();
        field = (int) (tag >>> 3);
        wireType = (int) (tag & 7);
        valueConsumed = false;
        return true;
    }


    public int field() {
        return field;
    }


    public int wireType() {
        return wireType;
    }


    public long readVarint() {
        valueConsumed = true;
        return rawVarint();
    }


    public long readFixed64() {
        valueConsumed = true;
        require(8);
        long v = 0;
        for (int i = 7; i >= 0; i--) {
            v = (v << 8) | (data[pos + i] & 0xFFL);
        }
        pos += 8;
        return v;
    }


    public int readFixed32() {
        valueConsumed = true;
        require(4);
        int v = (data[pos] & 0xFF)
            | ((data[pos + 1] & 0xFF) << 8)
            | ((data[pos + 2] & 0xFF) << 16)
            | ((data[pos + 3] & 0xFF) << 24);
        pos += 4;
        return v;
    }


    public byte[] readBytes() {
        int[] bounds = readLengthDelimitedBounds();
        return Arrays.copyOfRange(data, bounds[0], bounds[0] + bounds[1]);
    }


    public int[] readLengthDelimitedBounds() {
        valueConsumed = true;
        long length = rawVarint();
        if (length < 0 || length > end - pos) {
            throw new IllegalStateException("protobuf length out of bounds");
        }
        int[] bounds = {pos, (int) length};
        pos += (int) length;
        return bounds;
    }


    public void skip() {
        switch (wireType) {
            case 0:
                readVarint();
                break;
            case 1:
                readFixed64();
                break;
            case 2:
                readLengthDelimitedBounds();
                break;
            case 5:
                readFixed32();
                break;
            default:
                throw new IllegalStateException("unsupported wire type " + wireType);
        }
        valueConsumed = true;
    }


    private long rawVarint() {
        long result = 0;
        int shift = 0;
        while (shift < 64) {
            require(1);
            byte b = data[pos++];
            result |= (long) (b & 0x7F) << shift;
            if ((b & 0x80) == 0) {
                return result;
            }
            shift += 7;
        }
        throw new IllegalStateException("malformed varint");
    }


    private void require(int count) {
        if (pos + count > end) {
            throw new IllegalStateException("protobuf read past end");
        }
    }
}
