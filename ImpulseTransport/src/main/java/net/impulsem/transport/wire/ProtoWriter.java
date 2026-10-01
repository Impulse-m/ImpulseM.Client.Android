package net.impulsem.transport.wire;

import java.io.ByteArrayOutputStream;


public final class ProtoWriter {

    private final ByteArrayOutputStream out = new ByteArrayOutputStream();


    public void writeVarintField(int field, long value) {
        writeTag(field, 0);
        writeVarint(value);
    }


    public void writeFixed64Field(int field, long bits) {
        writeTag(field, 1);
        writeFixed64(bits);
    }


    public void writeBytesField(int field, byte[] value) {
        writeTag(field, 2);
        writeVarint(value.length);
        out.write(value, 0, value.length);
    }


    public void writePackedVarints(int field, long[] values) {
        ProtoWriter inner = new ProtoWriter();
        for (long value : values) {
            inner.writeVarint(value);
        }
        writeBytesField(field, inner.toByteArray());
    }


    public void writePackedFixed64(int field, long[] values) {
        ProtoWriter inner = new ProtoWriter();
        for (long value : values) {
            inner.writeFixed64(value);
        }
        writeBytesField(field, inner.toByteArray());
    }


    public byte[] toByteArray() {
        return out.toByteArray();
    }


    private void writeTag(int field, int wireType) {
        writeVarint(((long) field << 3) | wireType);
    }


    private void writeVarint(long value) {
        long v = value;
        while ((v & ~0x7FL) != 0) {
            out.write((int) ((v & 0x7F) | 0x80));
            v >>>= 7;
        }
        out.write((int) v);
    }


    private void writeFixed64(long bits) {
        for (int i = 0; i < 8; i++) {
            out.write((int) (bits >>> (8 * i)) & 0xFF);
        }
    }
}
