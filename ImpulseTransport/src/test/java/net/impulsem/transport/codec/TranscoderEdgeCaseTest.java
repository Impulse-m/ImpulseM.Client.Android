package net.impulsem.transport.codec;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import net.impulsem.transport.schema.ConstructorSpec;
import net.impulsem.transport.schema.ParamSpec;
import net.impulsem.transport.schema.ResultSpec;
import net.impulsem.transport.schema.TlProtoSchema;
import net.impulsem.transport.wire.ProtoWriter;
import net.impulsem.transport.wire.TlWriter;

import org.junit.Test;


/** proto3 edge cases that the non-zero synthetic round trip cannot reach. */
public class TranscoderEdgeCaseTest {

    private static final int BoolTrue = 0x997275b5;
    private static final int BoolFalse = 0xbc799737;
    private static final int VectorId = 0x1cb5c415;

    private static final int InputMediaPhotoExternalId = -440664550;
    private static final int CommunityForbiddenId = -46343496;
    private static final int ChatParticipantId = 954703838;
    private static final int AuthLoggedOutId = -1012759713;
    private static final int RequestPeerTypeUserId = 1597737472;
    private static final int UpdateDialogFilterOrderId = -1512627963;
    private static final int MessageActionChatAddUserId = 365886720;
    private static final int InputGeoPointId = 1210199983;
    private static final int TmpPasswordId = -614138572;
    private static final int PasswordRecoveryId = 326715557;
    private static final int PasskeyRegistrationOptionsId = -513057567;
    private static final int DataJsonId = 2104790276;
    private static final int InputNotifyPeerId = -1195615476;
    private static final int InputPeerEmptyId = 2134579434;
    private static final int ContactStatusId = 383348795;
    private static final int UserStatusEmptyId = 164646985;
    private static final int BotCallbackAnswerId = 911761060;
    private static final int GetContactIdsMethod = 2061264541;
    private static final int DeletePhotosMethod = -2016444625;

    private final TlProtoSchema schema = TlProtoSchema.load();
    private final Transcoder transcoder = new Transcoder(schema);


    // ---------------------------------------------------------------- (a) present with zero value


    @Test
    public void conditionalIntPresentWithZero() {
        TlWriter tl = new TlWriter();
        tl.writeInt32(InputMediaPhotoExternalId);
        tl.writeInt32(1);
        tl.writeString("u");
        tl.writeInt32(0);
        ProtoWriter body = new ProtoWriter();
        body.writeBytesField(2, utf8("u"));
        body.writeVarintField(3, 0);
        assertBothWays("InputMedia", tl.toByteArray(), wrap(9, body));
    }


    @Test
    public void conditionalLongPresentWithZero() {
        TlWriter tl = new TlWriter();
        tl.writeInt32(CommunityForbiddenId);
        tl.writeInt32(1 << 13);
        tl.writeInt64(5L);
        tl.writeInt64(0L);
        tl.writeString("t");
        ProtoWriter body = new ProtoWriter();
        body.writeVarintField(1, 5);
        body.writeVarintField(2, 0);
        body.writeBytesField(3, utf8("t"));
        assertBothWays("Chat", tl.toByteArray(), wrap(6, body));
    }


    @Test
    public void conditionalStringPresentEmpty() {
        TlWriter tl = new TlWriter();
        tl.writeInt32(ChatParticipantId);
        tl.writeInt32(1);
        tl.writeInt64(1L);
        tl.writeInt64(2L);
        tl.writeInt32(3);
        tl.writeString("");
        ProtoWriter body = new ProtoWriter();
        body.writeVarintField(1, 1);
        body.writeVarintField(2, 2);
        body.writeVarintField(3, 3);
        body.writeBytesField(4, new byte[0]);
        assertBothWays("ChatParticipant", tl.toByteArray(), wrap(1, body));
    }


    @Test
    public void conditionalBytesPresentEmpty() {
        TlWriter tl = new TlWriter();
        tl.writeInt32(AuthLoggedOutId);
        tl.writeInt32(1);
        tl.writeBytes(new byte[0]);
        ProtoWriter body = new ProtoWriter();
        body.writeBytesField(1, new byte[0]);
        assertBothWays("auth.LoggedOut", tl.toByteArray(), body.toByteArray());
    }


    @Test
    public void conditionalBoolPresentFalse() {
        TlWriter tl = new TlWriter();
        tl.writeInt32(RequestPeerTypeUserId);
        tl.writeInt32(3);
        tl.writeInt32(BoolFalse);
        tl.writeInt32(BoolFalse);
        ProtoWriter body = new ProtoWriter();
        body.writeVarintField(1, 0);
        body.writeVarintField(2, 0);
        assertBothWays("RequestPeerType", tl.toByteArray(), wrap(1, body));

        TlWriter absent = new TlWriter();
        absent.writeInt32(RequestPeerTypeUserId);
        absent.writeInt32(0);
        assertBothWays("RequestPeerType", absent.toByteArray(), wrap(1, new ProtoWriter()));
    }


    // ---------------------------------------------------------------- (b) omitted unconditional scalars


    @Test
    public void omittedUnconditionalScalarsDecodeToDefaults() {
        TlWriter media = new TlWriter();
        media.writeInt32(InputMediaPhotoExternalId);
        media.writeInt32(0);
        media.writeString("");
        assertArrayEquals(media.toByteArray(), transcoder.protoToTlObject("InputMedia", wrap(9, new ProtoWriter()), null));

        TlWriter participant = new TlWriter();
        participant.writeInt32(ChatParticipantId);
        participant.writeInt32(0);
        participant.writeInt64(0L);
        participant.writeInt64(0L);
        participant.writeInt32(0);
        assertArrayEquals(participant.toByteArray(), transcoder.protoToTlObject("ChatParticipant", wrap(1, new ProtoWriter()), null));

        TlWriter password = new TlWriter();
        password.writeInt32(TmpPasswordId);
        password.writeBytes(new byte[0]);
        password.writeInt32(0);
        assertArrayEquals(password.toByteArray(), transcoder.protoToTlObject("account.TmpPassword", new byte[0], null));

        TlWriter geo = new TlWriter();
        geo.writeInt32(InputGeoPointId);
        geo.writeInt32(0);
        geo.writeDouble(0.0);
        geo.writeDouble(0.0);
        assertArrayEquals(geo.toByteArray(), transcoder.protoToTlObject("InputGeoPoint", wrap(2, new ProtoWriter()), null));
    }


    // ---------------------------------------------------------------- (c) unpacked repeated scalars


    @Test
    public void unpackedAndPackedIntVectorsDecodeTheSame() {
        long[] values = {3, -1, 5};
        TlWriter expected = new TlWriter();
        expected.writeInt32(UpdateDialogFilterOrderId);
        expected.writeInt32(VectorId);
        expected.writeInt32(3);
        for (long value : values) {
            expected.writeInt32((int) value);
        }
        ProtoWriter unpacked = new ProtoWriter();
        for (long value : values) {
            unpacked.writeVarintField(1, value);
        }
        ProtoWriter packed = new ProtoWriter();
        packed.writePackedVarints(1, values);
        assertArrayEquals(expected.toByteArray(), transcoder.protoToTlObject("Update", wrap(75, unpacked), null));
        assertArrayEquals(expected.toByteArray(), transcoder.protoToTlObject("Update", wrap(75, packed), null));
        assertArrayEquals(wrap(75, packed), transcoder.tlObjectToProto("Update", expected.toByteArray()));
    }


    @Test
    public void unpackedAndPackedLongVectorsDecodeTheSame() {
        long[] values = {1234567890123L, 0, -7};
        TlWriter expected = new TlWriter();
        expected.writeInt32(MessageActionChatAddUserId);
        expected.writeInt32(VectorId);
        expected.writeInt32(3);
        for (long value : values) {
            expected.writeInt64(value);
        }
        ProtoWriter unpacked = new ProtoWriter();
        for (long value : values) {
            unpacked.writeVarintField(1, value);
        }
        ProtoWriter packed = new ProtoWriter();
        packed.writePackedVarints(1, values);
        assertArrayEquals(expected.toByteArray(), transcoder.protoToTlObject("MessageAction", wrap(6, unpacked), null));
        assertArrayEquals(expected.toByteArray(), transcoder.protoToTlObject("MessageAction", wrap(6, packed), null));
    }


    @Test
    public void unpackedAndPackedBoolVectorsDecodeTheSame() {
        TlWriter expected = new TlWriter();
        expected.writeInt32(VectorId);
        expected.writeInt32(3);
        expected.writeInt32(BoolTrue);
        expected.writeInt32(BoolFalse);
        expected.writeInt32(BoolTrue);
        ProtoWriter unpacked = new ProtoWriter();
        unpacked.writeVarintField(1, 1);
        unpacked.writeVarintField(1, 0);
        unpacked.writeVarintField(1, 1);
        ProtoWriter packed = new ProtoWriter();
        packed.writePackedVarints(1, new long[] {1, 0, 1});
        ResultSpec spec = listResult(ParamSpec.Kind.BOOL);
        assertArrayEquals(expected.toByteArray(), transcoder.decodeResultSpec(spec, unpacked.toByteArray(), null));
        assertArrayEquals(expected.toByteArray(), transcoder.decodeResultSpec(spec, packed.toByteArray(), null));
    }


    @Test
    public void unpackedAndPackedDoubleVectorsDecodeTheSame() {
        long[] bits = {Double.doubleToRawLongBits(1.5), Double.doubleToRawLongBits(-2.25)};
        TlWriter expected = new TlWriter();
        expected.writeInt32(VectorId);
        expected.writeInt32(2);
        expected.writeInt64(bits[0]);
        expected.writeInt64(bits[1]);
        ProtoWriter unpacked = new ProtoWriter();
        unpacked.writeFixed64Field(1, bits[0]);
        unpacked.writeFixed64Field(1, bits[1]);
        ProtoWriter packed = new ProtoWriter();
        packed.writePackedFixed64(1, bits);
        ResultSpec spec = listResult(ParamSpec.Kind.DOUBLE);
        assertArrayEquals(expected.toByteArray(), transcoder.decodeResultSpec(spec, unpacked.toByteArray(), null));
        assertArrayEquals(expected.toByteArray(), transcoder.decodeResultSpec(spec, packed.toByteArray(), null));
    }


    // ---------------------------------------------------------------- (d) zero-length submessages


    @Test
    public void zeroLengthSubmessageOfFlatTypeDecodesToDefaults() {
        ProtoWriter body = new ProtoWriter();
        body.writeBytesField(1, new byte[0]);
        assertArrayEquals(passkeyOptionsTl(), transcoder.protoToTlObject("account.PasskeyRegistrationOptions", body.toByteArray(), null));

        TlWriter recovery = new TlWriter();
        recovery.writeInt32(PasswordRecoveryId);
        recovery.writeString("");
        assertArrayEquals(recovery.toByteArray(), transcoder.protoToTlObject("auth.PasswordRecovery", new byte[0], null));
    }


    @Test
    public void zeroLengthSubmessageOfPolymorphicTypeThrows() {
        ProtoWriter notifyBody = new ProtoWriter();
        notifyBody.writeBytesField(1, new byte[0]);
        try {
            transcoder.protoToTlObject("InputNotifyPeer", wrap(1, notifyBody), null);
            fail("expected TranscodeException");
        } catch (TranscodeException e) {
            assertEquals("unset oneof for InputPeer", e.getMessage());
        }
    }


    // ---------------------------------------------------------------- (e) absent unconditional object


    @Test
    public void absentObjectOfFlatTypeDecodesFromEmptyBody() {
        assertArrayEquals(passkeyOptionsTl(), transcoder.protoToTlObject("account.PasskeyRegistrationOptions", new byte[0], null));
    }


    @Test
    public void absentObjectOfPolymorphicTypeUsesZeroParamConstructor() {
        TlWriter notify = new TlWriter();
        notify.writeInt32(InputNotifyPeerId);
        notify.writeInt32(InputPeerEmptyId);
        assertArrayEquals(notify.toByteArray(), transcoder.protoToTlObject("InputNotifyPeer", wrap(1, new ProtoWriter()), null));

        TlWriter status = new TlWriter();
        status.writeInt32(ContactStatusId);
        status.writeInt64(0L);
        status.writeInt32(UserStatusEmptyId);
        assertArrayEquals(status.toByteArray(), transcoder.protoToTlObject("ContactStatus", new byte[0], null));
    }


    @Test
    public void absentObjectOfPolymorphicTypeWithoutZeroParamConstructorThrows() {
        ConstructorSpec target = null;
        for (ConstructorSpec candidate : schema.constructors()) {
            if (candidate.params.size() == 1
                && candidate.params.get(0).kind == ParamSpec.Kind.OBJECT
                && candidate.params.get(0).flagRegister == null
                && schema.type(candidate.params.get(0).typeName).polymorphic
                && !hasZeroParamConstructor(candidate.params.get(0).typeName)
                && candidate.arm != null) {
                target = candidate;
                break;
            }
        }
        assertNotNull("no suitable constructor in the schema", target);
        try {
            transcoder.protoToTlObject(target.type, wrap(target.arm.intValue(), new ProtoWriter()), null);
            fail("expected TranscodeException for " + target.predicate);
        } catch (TranscodeException e) {
            assertTrue(e.getMessage(), e.getMessage().contains("missing required object field"));
        }
    }


    // ---------------------------------------------------------------- (f) result kinds


    @Test
    public void intAndLongResultKinds() {
        ResultSpec intSpec = new ResultSpec();
        intSpec.kind = "int";
        ResultSpec longSpec = new ResultSpec();
        longSpec.kind = "long";

        ProtoWriter negative = new ProtoWriter();
        negative.writeVarintField(1, -5L);
        TlWriter expectedInt = new TlWriter();
        expectedInt.writeInt32(-5);
        assertArrayEquals(expectedInt.toByteArray(), transcoder.decodeResultSpec(intSpec, negative.toByteArray(), null));

        TlWriter zeroInt = new TlWriter();
        zeroInt.writeInt32(0);
        assertArrayEquals(zeroInt.toByteArray(), transcoder.decodeResultSpec(intSpec, new byte[0], null));

        ProtoWriter big = new ProtoWriter();
        big.writeVarintField(1, 1234567890123L);
        TlWriter expectedLong = new TlWriter();
        expectedLong.writeInt64(1234567890123L);
        assertArrayEquals(expectedLong.toByteArray(), transcoder.decodeResultSpec(longSpec, big.toByteArray(), null));

        TlWriter zeroLong = new TlWriter();
        zeroLong.writeInt64(0L);
        assertArrayEquals(zeroLong.toByteArray(), transcoder.decodeResultSpec(longSpec, new byte[0], null));
    }


    @Test
    public void primitiveListResultsPackedAndUnpacked() {
        long[] longs = {10L, 1234567890123L};
        TlWriter expectedLongs = new TlWriter();
        expectedLongs.writeInt32(VectorId);
        expectedLongs.writeInt32(2);
        expectedLongs.writeInt64(longs[0]);
        expectedLongs.writeInt64(longs[1]);
        ProtoWriter packedLongs = new ProtoWriter();
        packedLongs.writePackedVarints(1, longs);
        ProtoWriter unpackedLongs = new ProtoWriter();
        unpackedLongs.writeVarintField(1, longs[0]);
        unpackedLongs.writeVarintField(1, longs[1]);
        assertArrayEquals(expectedLongs.toByteArray(), transcoder.decodeResult(DeletePhotosMethod, packedLongs.toByteArray(), null));
        assertArrayEquals(expectedLongs.toByteArray(), transcoder.decodeResult(DeletePhotosMethod, unpackedLongs.toByteArray(), null));

        long[] ints = {4, -2, 9};
        TlWriter expectedInts = new TlWriter();
        expectedInts.writeInt32(VectorId);
        expectedInts.writeInt32(3);
        for (long value : ints) {
            expectedInts.writeInt32((int) value);
        }
        ProtoWriter packedInts = new ProtoWriter();
        packedInts.writePackedVarints(1, ints);
        ProtoWriter unpackedInts = new ProtoWriter();
        for (long value : ints) {
            unpackedInts.writeVarintField(1, value);
        }
        assertArrayEquals(expectedInts.toByteArray(), transcoder.decodeResult(GetContactIdsMethod, packedInts.toByteArray(), null));
        assertArrayEquals(expectedInts.toByteArray(), transcoder.decodeResult(GetContactIdsMethod, unpackedInts.toByteArray(), null));
    }


    // ---------------------------------------------------------------- (g) derived_from


    @Test
    public void derivedHasUrlBitFollowsUrlPresence() {
        ProtoWriter withUrl = new ProtoWriter();
        withUrl.writeBytesField(4, utf8("u"));
        TlWriter expectedWith = new TlWriter();
        expectedWith.writeInt32(BotCallbackAnswerId);
        expectedWith.writeInt32((1 << 2) | (1 << 3));
        expectedWith.writeString("u");
        expectedWith.writeInt32(0);
        assertArrayEquals(expectedWith.toByteArray(), transcoder.protoToTlObject("messages.BotCallbackAnswer", withUrl.toByteArray(), null));

        TlWriter expectedWithout = new TlWriter();
        expectedWithout.writeInt32(BotCallbackAnswerId);
        expectedWithout.writeInt32(0);
        expectedWithout.writeInt32(0);
        assertArrayEquals(expectedWithout.toByteArray(), transcoder.protoToTlObject("messages.BotCallbackAnswer", new byte[0], null));
    }


    // ---------------------------------------------------------------- helpers


    private void assertBothWays(
        String type,
        byte[] tl,
        byte[] proto
    ) {
        assertArrayEquals(proto, transcoder.tlObjectToProto(type, tl));
        assertArrayEquals(tl, transcoder.protoToTlObject(type, proto, null));
    }


    private byte[] passkeyOptionsTl() {
        TlWriter tl = new TlWriter();
        tl.writeInt32(PasskeyRegistrationOptionsId);
        tl.writeInt32(DataJsonId);
        tl.writeString("");
        return tl.toByteArray();
    }


    private boolean hasZeroParamConstructor(String type) {
        for (ConstructorSpec candidate : schema.constructorsOf(type)) {
            if (candidate.params.isEmpty()) {
                return true;
            }
        }
        return false;
    }


    private static ResultSpec listResult(ParamSpec.Kind kind) {
        ResultSpec spec = new ResultSpec();
        spec.kind = "list";
        spec.elem = new ParamSpec();
        spec.elem.kind = kind;
        return spec;
    }


    private static byte[] wrap(
        int arm,
        ProtoWriter body
    ) {
        return wrap(arm, body.toByteArray());
    }


    private static byte[] wrap(
        int arm,
        byte[] body
    ) {
        ProtoWriter writer = new ProtoWriter();
        writer.writeBytesField(arm, body);
        return writer.toByteArray();
    }


    private static byte[] utf8(String value) {
        try {
            return value.getBytes("UTF-8");
        } catch (java.io.UnsupportedEncodingException e) {
            throw new IllegalStateException(e);
        }
    }
}
