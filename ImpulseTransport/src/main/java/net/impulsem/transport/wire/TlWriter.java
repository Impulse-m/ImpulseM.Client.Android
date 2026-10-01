package net.impulsem.transport.wire;

import java.nio.charset.Charset;
import java.util.Arrays;


public final class TlWriter {

    private static final Charset Utf8 = Charset.forName("UTF-8");

    private byte[] buffer = new byte[256];
    private int size;


    public void writeInt32(int v) {
        ensure(4);
        buffer[size++] = (byte) v;
        buffer[size++] = (byte) (v >>> 8);
        buffer[size++] = (byte) (v >>> 16);
        buffer[size++] = (byte) (v >>> 24);
    }


    public void writeInt64(long v) {
        ensure(8);
        for (int i = 0; i < 8; i++) {
            buffer[size++] = (byte) (v >>> (8 * i));
        }
    }


    public void writeDouble(double v) {
        writeInt64(Double.doubleToRawLongBits(v));
    }


    public void writeBytes(byte[] v) {
        int length = v.length;
        int header;
        if (length < 254) {
            ensure(length + 4);
            buffer[size++] = (byte) length;
            header = 1;
        } else {
            ensure(length + 8);
            buffer[size++] = (byte) 0xFE;
            buffer[size++] = (byte) length;
            buffer[size++] = (byte) (length >>> 8);
            buffer[size++] = (byte) (length >>> 16);
            header = 4;
        }
        System.arraycopy(v, 0, buffer, size, length);
        size += length;
        int total = header + length;
        while (total % 4 != 0) {
            buffer[size++] = 0;
            total++;
        }
    }


    public void writeString(String v) {
        writeBytes(v.getBytes(Utf8));
    }


    public void writeRaw(byte[] v) {
        ensure(v.length);
        System.arraycopy(v, 0, buffer, size, v.length);
        size += v.length;
    }


    public int position() {
        return size;
    }


    public void patchInt32(int position, int value) {
        buffer[position] = (byte) value;
        buffer[position + 1] = (byte) (value >>> 8);
        buffer[position + 2] = (byte) (value >>> 16);
        buffer[position + 3] = (byte) (value >>> 24);
    }


    public byte[] toByteArray() {
        return Arrays.copyOf(buffer, size);
    }


    private void ensure(int extra) {
        int needed = size + extra;
        if (needed > buffer.length) {
            buffer = Arrays.copyOf(buffer, Math.max(needed, buffer.length * 2));
        }
    }
}
