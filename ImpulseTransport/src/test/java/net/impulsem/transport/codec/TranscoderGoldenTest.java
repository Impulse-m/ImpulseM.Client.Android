package net.impulsem.transport.codec;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import net.impulsem.transport.schema.TlProtoSchema;
import net.impulsem.transport.wire.ProtoWriter;
import net.impulsem.transport.wire.TlWriter;

import org.junit.Test;

import java.io.UnsupportedEncodingException;
import java.util.ArrayList;
import java.util.List;


public class TranscoderGoldenTest {

    private static final int BoolTrue = 0x997275b5;
    private static final int BoolFalse = 0xbc799737;
    private static final int VectorId = 0x1cb5c415;
    private static final int PeerUserId = 0x59511722;
    private static final int UserEmptyId = 0xd3bc4b7a;
    private static final int MessageId = 1979759059;
    private static final int InvokeWithLayerId = -627372787;
    private static final int InitConnectionId = -1043505495;
    private static final int InvokeWithTakeoutId = -1398145746;
    private static final int GetConfigId = -990308245;
    private static final int GetUsersId = 227648840;
    private static final int ResetAuthorizationsId = -1616179942;
    private static final int GetContactSignUpNotificationId = -1626880216;
    private static final int AuthAuthorizationId = 782418132;
    private static final int InputClientProxyId = 0x75588b3f;
    private static final int JsonNullId = 0x3f6d7b68;

    private final Transcoder transcoder = new Transcoder(TlProtoSchema.load());


    @Test
    public void peerUserGoldenBytes() {
        byte[] tl = new byte[] {0x22, 0x17, 0x51, 0x59, 5, 0, 0, 0, 0, 0, 0, 0};
        byte[] proto = new byte[] {0x0A, 0x02, 0x08, 0x05};
        assertArrayEquals(proto, transcoder.tlObjectToProto("Peer", tl));
        assertArrayEquals(tl, transcoder.protoToTlObject("Peer", proto, null));
    }


    @Test
    public void messageFlagsAndFieldNumbers() {
        TlWriter tl = new TlWriter();
        tl.writeInt32(MessageId);
        tl.writeInt32((1 << 1) | (1 << 24));
        tl.writeInt32(1 << 1);
        tl.writeInt32(42);
        tl.writeInt32(PeerUserId);
        tl.writeInt64(5L);
        tl.writeInt32(1700000000);
        tl.writeString("hi");
        byte[] proto = transcoder.tlObjectToProto("Message", tl.toByteArray());

        ProtoWriter body = new ProtoWriter();
        body.writeVarintField(1, 1);
        body.writeVarintField(9, 1);
        body.writeVarintField(12, 1);
        body.writeVarintField(16, 42);
        body.writeBytesField(20, new byte[] {0x0A, 0x02, 0x08, 0x05});
        body.writeVarintField(26, 1700000000L);
        body.writeBytesField(27, utf8("hi"));
        ProtoWriter expected = new ProtoWriter();
        expected.writeBytesField(2, body.toByteArray());
        assertArrayEquals(expected.toByteArray(), proto);
        assertArrayEquals(tl.toByteArray(), transcoder.protoToTlObject("Message", proto, null));
    }


    @Test
    public void unknownProtoFieldIsIgnored() {
        ProtoWriter body = new ProtoWriter();
        body.writeVarintField(1, 1);
        body.writeVarintField(999, 77);
        body.writeBytesField(1000, new byte[] {1, 2});
        body.writeVarintField(16, 42);
        body.writeBytesField(20, new byte[] {0x0A, 0x02, 0x08, 0x05});
        body.writeVarintField(26, 9);
        body.writeBytesField(27, utf8("x"));
        ProtoWriter message = new ProtoWriter();
        message.writeBytesField(2, body.toByteArray());

        TlWriter expected = new TlWriter();
        expected.writeInt32(MessageId);
        expected.writeInt32(1 << 1);
        expected.writeInt32(0);
        expected.writeInt32(42);
        expected.writeInt32(PeerUserId);
        expected.writeInt64(5L);
        expected.writeInt32(9);
        expected.writeString("x");
        assertArrayEquals(expected.toByteArray(), transcoder.protoToTlObject("Message", message.toByteArray(), null));
    }


    @Test
    public void unsetOneofThrows() {
        try {
            transcoder.protoToTlObject("Peer", new byte[0], null);
            fail("expected TranscodeException");
        } catch (TranscodeException e) {
            assertEquals("unset oneof for Peer", e.getMessage());
        }
    }


    @Test
    public void getUsersListResult() {
        ProtoWriter userEmpty = new ProtoWriter();
        userEmpty.writeVarintField(1, 11);
        ProtoWriter user = new ProtoWriter();
        user.writeBytesField(1, userEmpty.toByteArray());
        ProtoWriter proto = new ProtoWriter();
        proto.writeBytesField(1, user.toByteArray());
        proto.writeBytesField(1, user.toByteArray());

        TlWriter expected = new TlWriter();
        expected.writeInt32(VectorId);
        expected.writeInt32(2);
        for (int i = 0; i < 2; i++) {
            expected.writeInt32(UserEmptyId);
            expected.writeInt64(11L);
        }
        assertArrayEquals(expected.toByteArray(), transcoder.decodeResult(GetUsersId, proto.toByteArray(), null));
    }


    @Test
    public void emptyListResultIsEmptyVector() {
        TlWriter expected = new TlWriter();
        expected.writeInt32(VectorId);
        expected.writeInt32(0);
        assertArrayEquals(expected.toByteArray(), transcoder.decodeResult(GetUsersId, new byte[0], null));
    }


    @Test
    public void boolResponseEmptyIsTrue() {
        TlWriter expected = new TlWriter();
        expected.writeInt32(BoolTrue);
        assertArrayEquals(expected.toByteArray(), transcoder.decodeResult(ResetAuthorizationsId, new byte[0], null));
    }


    @Test
    public void boolOverlay() {
        TlWriter yes = new TlWriter();
        yes.writeInt32(BoolTrue);
        TlWriter no = new TlWriter();
        no.writeInt32(BoolFalse);
        ProtoWriter falseValue = new ProtoWriter();
        falseValue.writeVarintField(1, 0);
        ProtoWriter trueValue = new ProtoWriter();
        trueValue.writeVarintField(1, 1);
        assertArrayEquals(yes.toByteArray(), transcoder.decodeResult(GetContactSignUpNotificationId, new byte[0], null));
        assertArrayEquals(no.toByteArray(), transcoder.decodeResult(GetContactSignUpNotificationId, falseValue.toByteArray(), null));
        assertArrayEquals(yes.toByteArray(), transcoder.decodeResult(GetContactSignUpNotificationId, trueValue.toByteArray(), null));
    }


    @Test
    public void authorizationCapturesTokens() {
        ProtoWriter userEmpty = new ProtoWriter();
        userEmpty.writeVarintField(1, 3);
        ProtoWriter user = new ProtoWriter();
        user.writeBytesField(1, userEmpty.toByteArray());
        ProtoWriter body = new ProtoWriter();
        body.writeBytesField(5, user.toByteArray());
        body.writeBytesField(6, utf8("A"));
        body.writeBytesField(7, utf8("B"));
        ProtoWriter proto = new ProtoWriter();
        proto.writeBytesField(1, body.toByteArray());

        final List<String> captured = new ArrayList<String>();
        byte[] tl = transcoder.protoToTlObject("auth.Authorization", proto.toByteArray(), new CaptureSink() {
            @Override
            public void onCapture(String protoMessage, String fieldName, String value) {
                captured.add(protoMessage + "." + fieldName + "=" + value);
            }
        });

        TlWriter expected = new TlWriter();
        expected.writeInt32(AuthAuthorizationId);
        expected.writeInt32(0);
        expected.writeInt32(UserEmptyId);
        expected.writeInt64(3L);
        assertArrayEquals(expected.toByteArray(), tl);
        assertEquals(2, captured.size());
        assertTrue(captured.contains("AuthAuthorizationValue.session_token=A"));
        assertTrue(captured.contains("AuthAuthorizationValue.refresh_token=B"));
    }


    @Test
    public void invokeWithLayerAndInitConnectionAreUnwrapped() {
        TlWriter tl = new TlWriter();
        tl.writeInt32(InvokeWithLayerId);
        tl.writeInt32(229);
        tl.writeInt32(InitConnectionId);
        tl.writeInt32(0);
        tl.writeInt32(3);
        for (int i = 0; i < 6; i++) {
            tl.writeString("v" + i);
        }
        tl.writeInt32(GetConfigId);
        EncodedRequest request = transcoder.encodeRequest(tl.toByteArray());
        assertEquals(GetConfigId, request.methodId);
        assertEquals("/v1.help.Help/GetConfig", request.path);
        assertEquals(0, request.proto.length);
        assertNull(request.takeoutId);
        assertTrue(transcoder.hasMethod(GetConfigId));
        assertFalse(transcoder.hasMethod(InvokeWithLayerId));
    }


    @Test
    public void initConnectionWithProxyAndParamsIsSkipped() {
        TlWriter tl = new TlWriter();
        tl.writeInt32(InitConnectionId);
        tl.writeInt32(3);
        tl.writeInt32(3);
        for (int i = 0; i < 6; i++) {
            tl.writeString("v" + i);
        }
        tl.writeInt32(InputClientProxyId);
        tl.writeString("1.2.3.4");
        tl.writeInt32(1080);
        tl.writeInt32(JsonNullId);
        tl.writeInt32(GetConfigId);
        EncodedRequest request = transcoder.encodeRequest(tl.toByteArray());
        assertEquals(GetConfigId, request.methodId);
    }


    @Test
    public void invokeWithTakeoutRecordsTakeoutId() {
        TlWriter tl = new TlWriter();
        tl.writeInt32(InvokeWithTakeoutId);
        tl.writeInt64(987654321L);
        tl.writeInt32(GetConfigId);
        EncodedRequest request = transcoder.encodeRequest(tl.toByteArray());
        assertEquals(Long.valueOf(987654321L), request.takeoutId);
        assertEquals("/v1.help.Help/GetConfig", request.path);
    }


    @Test
    public void unknownMethodThrows() {
        TlWriter tl = new TlWriter();
        tl.writeInt32(0x12345678);
        try {
            transcoder.encodeRequest(tl.toByteArray());
            fail("expected TranscodeException");
        } catch (TranscodeException e) {
            assertTrue(e.getMessage().contains("unknown method"));
        }
    }


    @Test
    public void unknownConstructorThrows() {
        TlWriter tl = new TlWriter();
        tl.writeInt32(0x12345678);
        try {
            transcoder.tlObjectToProto("Peer", tl.toByteArray());
            fail("expected TranscodeException");
        } catch (TranscodeException e) {
            assertTrue(e.getMessage().contains("unknown constructor"));
        }
    }


    @Test
    public void truncatedInputThrowsTranscodeException() {
        try {
            transcoder.tlObjectToProto("Peer", new byte[] {0x22, 0x17, 0x51, 0x59, 5});
            fail("expected TranscodeException");
        } catch (TranscodeException e) {
            assertTrue(e.getMessage().length() > 0);
        }
    }


    private static byte[] utf8(String value) {
        try {
            return value.getBytes("UTF-8");
        } catch (UnsupportedEncodingException e) {
            throw new IllegalStateException(e);
        }
    }
}
