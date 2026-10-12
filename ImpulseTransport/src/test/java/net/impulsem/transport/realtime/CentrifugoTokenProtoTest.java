package net.impulsem.transport.realtime;

import static org.junit.Assert.assertEquals;

import net.impulsem.transport.wire.ProtoWriter;
import org.junit.Test;


public class CentrifugoTokenProtoTest {

    @Test
    public void tokenResponseDecodes() {
        ProtoWriter writer = new ProtoWriter();
        writer.writeBytesField(1, "jwt.value".getBytes());
        writer.writeVarintField(2, 300L);
        assertEquals("jwt.value", CentrifugoTokenProto.decodeToken(writer.toByteArray()));
    }
}
