package net.impulsem.transport.realtime;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import net.impulsem.transport.wire.ProtoWriter;
import org.junit.Test;


public class ActorTagsProtoTest {

    @Test
    public void requestIsPackedVarints() {
        // field 1, wire type 2, length 4, values 1, 2, 300 (0xAC 0x02)
        byte[] expected = {0x0A, 0x04, 0x01, 0x02, (byte) 0xAC, 0x02};
        assertArrayEquals(expected, ActorTagsProto.encodeRequest(Arrays.asList(1L, 2L, 300L)));
    }


    @Test
    public void responseMapDecodes() {
        ProtoWriter entry1 = new ProtoWriter();
        entry1.writeVarintField(1, 5L);
        entry1.writeBytesField(2, "tagA".getBytes());
        ProtoWriter entry2 = new ProtoWriter();
        entry2.writeVarintField(1, 1234567890123L);
        entry2.writeBytesField(2, "tagB".getBytes());
        ProtoWriter response = new ProtoWriter();
        response.writeBytesField(1, entry1.toByteArray());
        response.writeBytesField(1, entry2.toByteArray());
        Map<Long, String> tags = ActorTagsProto.decodeResponse(response.toByteArray());
        assertEquals(2, tags.size());
        assertEquals("tagA", tags.get(5L));
        assertEquals("tagB", tags.get(1234567890123L));
    }


    @Test
    public void emptyResponseIsEmptyMap() {
        assertTrue(ActorTagsProto.decodeResponse(new byte[0]).isEmpty());
    }


    @Test
    public void tokenResponseDecodes() {
        ProtoWriter writer = new ProtoWriter();
        writer.writeBytesField(1, "jwt.value".getBytes());
        writer.writeVarintField(2, 300L);
        assertEquals("jwt.value", ActorTagsProto.decodeToken(writer.toByteArray()));
    }


    @Test
    public void batchesOfAtMost200() {
        List<Long> ids = new ArrayList<Long>();
        for (long a = 1; a <= 450; a++) {
            ids.add(a);
        }
        List<List<Long>> batches = ActorTagsProto.batches(ids, 200);
        assertEquals(3, batches.size());
        assertEquals(200, batches.get(0).size());
        assertEquals(200, batches.get(1).size());
        assertEquals(50, batches.get(2).size());
        assertEquals(Long.valueOf(450L), batches.get(2).get(49));
    }
}
