package net.impulsem.transport.codec;

import net.impulsem.transport.calls.LiveKitContract;
import net.impulsem.transport.live.TlBuilder;
import net.impulsem.transport.schema.TlProtoSchema;
import net.impulsem.transport.wire.ProtoWriter;
import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.util.Collections;

import static org.junit.Assert.assertArrayEquals;


/** Golden field numbers from backend integration c0944ab81, independent of the generated map. */
public class LiveKitCarrierTest {
    private final Transcoder transcoder = new Transcoder(TlProtoSchema.load());


    @Test
    public void phoneCallKeepsCustomParametersWithEmptyLegacyConnections() {
        String json = parameters("p.42");
        TlBuilder protocol = TlBuilder.object("phoneCallProtocol")
            .put("udp_p2p", true).put("udp_reflector", true)
            .put("min_layer", 65).put("max_layer", 92)
            .put("library_versions", Collections.singletonList(LiveKitContract.Protocol));
        byte[] tl = TlBuilder.object("phoneCall")
            .put("id", 42L).put("access_hash", 43L).put("date", 1)
            .put("admin_id", 10L).put("participant_id", 20L)
            .put("g_a_or_b", new byte[] {1, 2}).put("key_fingerprint", 44L)
            .put("protocol", protocol).put("connections", Collections.emptyList()).put("start_date", 2)
            .put("custom_parameters", TlBuilder.object("dataJSON").put("data", json)).toBytes();
        ProtoWriter protoProtocol = new ProtoWriter();
        protoProtocol.writeVarintField(1, 1);
        protoProtocol.writeVarintField(2, 1);
        protoProtocol.writeVarintField(3, 65);
        protoProtocol.writeVarintField(4, 92);
        protoProtocol.writeBytesField(5, LiveKitContract.Protocol.getBytes(StandardCharsets.UTF_8));
        ProtoWriter body = new ProtoWriter();
        body.writeVarintField(4, 42);
        body.writeVarintField(5, 43);
        body.writeVarintField(6, 1);
        body.writeVarintField(7, 10);
        body.writeVarintField(8, 20);
        body.writeBytesField(9, new byte[] {1, 2});
        body.writeVarintField(10, 44);
        body.writeBytesField(11, protoProtocol.toByteArray());
        body.writeVarintField(13, 2);
        body.writeBytesField(14, dataJson(json));
        ProtoWriter envelope = new ProtoWriter();
        envelope.writeBytesField(5, body.toByteArray());
        assertArrayEquals(tl, transcoder.protoToTlObject("PhoneCall", envelope.toByteArray(), null));
        assertArrayEquals(envelope.toByteArray(), transcoder.tlObjectToProto("PhoneCall", tl));
    }


    @Test
    public void groupConnectionKeepsParametersForMainAndPresentation() {
        for (boolean presentation : new boolean[] {false, true}) {
            String json = parameters("g.42");
            TlBuilder update = TlBuilder.object("updateGroupCallConnection")
                .put("params", TlBuilder.object("dataJSON").put("data", json));
            ProtoWriter body = new ProtoWriter();
            if (presentation) {
                update.put("presentation", true);
                body.writeVarintField(1, 1);
            }
            body.writeBytesField(2, dataJson(json));
            ProtoWriter envelope = new ProtoWriter();
            envelope.writeBytesField(92, body.toByteArray());
            assertArrayEquals(update.toBytes(), transcoder.protoToTlObject("Update", envelope.toByteArray(), null));
            assertArrayEquals(envelope.toByteArray(), transcoder.tlObjectToProto("Update", update.toBytes()));
        }
    }


    private static String parameters(String room) {
        return "{\"livekit\":{\"url\":\"wss://media.example.test\",\"token\":\"test-token\",\"room\":\"" + room + "\"}}";
    }


    private static byte[] dataJson(String json) {
        ProtoWriter body = new ProtoWriter();
        body.writeBytesField(1, json.getBytes(StandardCharsets.UTF_8));
        return body.toByteArray();
    }
}
