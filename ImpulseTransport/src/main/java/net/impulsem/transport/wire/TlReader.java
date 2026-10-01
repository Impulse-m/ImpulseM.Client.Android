package net.impulsem.transport.wire;

import java.nio.charset.Charset;
import java.util.Arrays;


public final class TlReader {

    private static final Charset Utf8 = Charset.forName("UTF-8");

    private final byte[] data;
    private final int start;
    private final int end;
    private int pos;


    public TlReader(byte[] data, int offset, int length) {
        if (offset < 0 || length < 0 || offset + length > data.length) {
            throw new IllegalArgumentException("bad bounds");
        }
        this.data = data;
        this.start = offset;
        this.end = offset + length;
        this.pos = offset;
    }


    public int readInt32() {
        require(4);
        int v = (data[pos] & 0xFF)
            | ((data[pos + 1] & 0xFF) << 8)
            | ((data[pos + 2] & 0xFF) << 16)
            | ((data[pos + 3] & 0xFF) << 24);
        pos += 4;
        return v;
    }


    public long readInt64() {
        require(8);
        long v = 0;
        for (int i = 7; i >= 0; i--) {
            v = (v << 8) | (data[pos + i] & 0xFFL);
        }
        pos += 8;
        return v;
    }


    public double readDouble() {
        return Double.longBitsToDouble(readInt64());
    }


    public byte[] readBytes() {
        require(1);
        int length = data[pos] & 0xFF;
        int header = 1;
        if (length >= 254) {
            require(4);
            length = (data[pos + 1] & 0xFF) | ((data[pos + 2] & 0xFF) << 8) | ((data[pos + 3] & 0xFF) << 16);
            header = 4;
        }
        int padded = ((header + length + 3) / 4) * 4;
        require(padded);
        byte[] result = Arrays.copyOfRange(data, pos + header, pos + header + length);
        pos += padded;
        return result;
    }


    public String readString() {
        return new String(readBytes(), Utf8);
    }


    public byte[] readRaw(int count) {
        require(count);
        byte[] result = Arrays.copyOfRange(data, pos, pos + count);
        pos += count;
        return result;
    }


    public int position() {
        return pos - start;
    }


    public int remaining() {
        return end - pos;
    }


    private void require(int count) {
        if (count < 0 || pos + count > end) {
            throw new IllegalStateException("TL read past end: need " + count + ", have " + (end - pos));
        }
    }
}
