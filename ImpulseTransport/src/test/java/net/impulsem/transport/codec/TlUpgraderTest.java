package net.impulsem.transport.codec;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.util.ArrayList;
import java.util.List;
import net.impulsem.transport.live.TlBuilder;
import net.impulsem.transport.schema.LegacySpec;
import net.impulsem.transport.schema.TlProtoSchema;
import net.impulsem.transport.wire.TlReader;
import net.impulsem.transport.wire.TlWriter;
import org.junit.Test;


public class TlUpgraderTest {

    private static final int GetDifferenceLegacy = 0x25939651;
    private static final int GetDifferenceCurrent = 0x19c2f763;
    private static final int GetMessagesLegacy = 0x4222fa74;
    private static final int ChannelsGetMessagesLegacy = 0x93d7b347;
    private static final int UploadMediaLegacy = 0x519bc2b1;
    private static final int SignUpLegacy = 0x80eee427;
    private static final int JoinChannelLegacy = 0x24b524c5;
    private static final int VideoAttributeLegacy = 0x0ef02ce6;
    private static final int ContactsGetContactsLegacy = 0x22c6aa08;
    private static final int CreateThemeLegacy = 0x8432c21f;
    private static final int InvokeWithLayer = -627372787;

    private final TlProtoSchema schema = TlProtoSchema.load();
    private final Transcoder transcoder = new Transcoder(schema);
    private final TlUpgrader upgrader = new TlUpgrader(schema);


    private static TlBuilder inputChannel() {
        return TlBuilder.object("inputChannel").put("channel_id", 777L).put("access_hash", 99L);
    }


    private static List<Object> inputMessageIds(int... ids) {
        List<Object> list = new ArrayList<Object>();
        for (int id : ids) {
            list.add(TlBuilder.object("inputMessageID").put("id", id));
        }
        return list;
    }


    private static List<Object> intList(int... ids) {
        List<Object> list = new ArrayList<Object>();
        for (int id : ids) {
            list.add(Integer.valueOf(id));
        }
        return list;
    }


    /** The legacy request must encode to exactly the proto of its layer 229 equivalent. */
    private void assertEncodesAs(
        byte[] legacy,
        byte[] current
    ) {
        EncodedRequest expected = transcoder.encodeRequest(current);
        EncodedRequest actual = transcoder.encodeRequest(legacy);
        assertEquals(expected.methodId, actual.methodId);
        assertEquals(expected.path, actual.path);
        assertArrayEquals(expected.proto, actual.proto);
        assertArrayEquals(current, transcoder.decodeRequestForTest(actual.methodId, actual.proto));
    }


    @Test
    public void getDifferenceGainsOnlyOptionalFlags() {
        byte[] legacy = TlBuilder.legacyMethod(GetDifferenceLegacy)
            .put("pts", 100)
            .put("date", 1700000000)
            .put("qts", -1)
            .toBytes();
        byte[] current = TlBuilder.method("updates.getDifference")
            .put("pts", 100)
            .put("date", 1700000000)
            .put("qts", -1)
            .toBytes();
        assertArrayEquals(current, upgrader.upgradeRequest(legacy));
        assertEquals(GetDifferenceCurrent, transcoder.encodeRequest(legacy).methodId);
        assertEncodesAs(legacy, current);
    }


    @Test
    public void getMessagesWrapsIdsAsInputMessageId() {
        byte[] legacy = TlBuilder.legacyMethod(GetMessagesLegacy).put("id", intList(5, 7)).toBytes();
        byte[] current = TlBuilder.method("messages.getMessages").put("id", inputMessageIds(5, 7)).toBytes();
        assertArrayEquals(current, upgrader.upgradeRequest(legacy));
        assertEncodesAs(legacy, current);
    }


    @Test
    public void channelsGetMessagesKeepsTheChannel() {
        byte[] legacy = TlBuilder.legacyMethod(ChannelsGetMessagesLegacy)
            .put("channel", inputChannel())
            .put("id", intList(5, 7))
            .toBytes();
        byte[] current = TlBuilder.method("channels.getMessages")
            .put("channel", inputChannel())
            .put("id", inputMessageIds(5, 7))
            .toBytes();
        assertArrayEquals(current, upgrader.upgradeRequest(legacy));
        assertEncodesAs(legacy, current);
    }


    @Test
    public void uploadMediaUpgradesWithFlagsAbsent() {
        byte[] legacy = TlBuilder.legacyMethod(UploadMediaLegacy)
            .put("peer", TlBuilder.object("inputPeerSelf"))
            .put("media", TlBuilder.object("inputMediaEmpty"))
            .toBytes();
        byte[] current = TlBuilder.method("messages.uploadMedia")
            .put("peer", TlBuilder.object("inputPeerSelf"))
            .put("media", TlBuilder.object("inputMediaEmpty"))
            .toBytes();
        assertArrayEquals(current, upgrader.upgradeRequest(legacy));
        assertEncodesAs(legacy, current);
    }


    @Test
    public void signUpUpgradesAndReplacesTheOldAdapter() {
        byte[] legacy = TlBuilder.legacyMethod(SignUpLegacy)
            .put("phone_number", "+1")
            .put("phone_code_hash", "hash")
            .put("first_name", "A")
            .put("last_name", "B")
            .toBytes();
        byte[] current = TlBuilder.method("auth.signUp")
            .put("phone_number", "+1")
            .put("phone_code_hash", "hash")
            .put("first_name", "A")
            .put("last_name", "B")
            .toBytes();
        assertArrayEquals(current, upgrader.upgradeRequest(legacy));
        assertTrue(transcoder.hasMethod(SignUpLegacy));
        assertEncodesAs(legacy, current);
    }


    @Test
    public void aliasJoinChannelOnlySwapsTheId() {
        LegacySpec spec = schema.legacyMethod(JoinChannelLegacy);
        assertNotNull(spec);
        assertTrue(spec.alias);
        byte[] legacy = TlBuilder.legacyMethod(JoinChannelLegacy).put("channel", inputChannel()).toBytes();
        byte[] current = TlBuilder.method("channels.joinChannel").put("channel", inputChannel()).toBytes();
        assertArrayEquals(current, upgrader.upgradeRequest(legacy));
        assertEncodesAs(legacy, current);
    }


    private static TlBuilder uploadedDocument(Object attribute) {
        List<Object> attributes = new ArrayList<Object>();
        attributes.add(attribute);
        return TlBuilder.object("inputMediaUploadedDocument")
            .put("file", TlBuilder.object("inputFile")
                .put("id", 1L)
                .put("parts", 1)
                .put("name", "v.mp4")
                .put("md5_checksum", ""))
            .put("mime_type", "video/mp4")
            .put("attributes", attributes);
    }


    private static byte[] uploadMediaWith(TlBuilder media) {
        return TlBuilder.method("messages.uploadMedia")
            .put("peer", TlBuilder.object("inputPeerSelf"))
            .put("media", media)
            .toBytes();
    }


    @Test
    public void nestedLegacyVideoAttributeDurationBecomesDouble() {
        TlBuilder legacyAttribute = TlBuilder.legacyObject(VideoAttributeLegacy)
            .put("duration", 12)
            .put("w", 640)
            .put("h", 480);
        TlBuilder currentAttribute = TlBuilder.object("documentAttributeVideo")
            .put("duration", 12.0)
            .put("w", 640)
            .put("h", 480);
        byte[] legacy = uploadMediaWith(uploadedDocument(legacyAttribute));
        byte[] current = uploadMediaWith(uploadedDocument(currentAttribute));
        assertEncodesAs(legacy, current);
    }


    @Test
    public void legacyVideoAttributeKeepsItsFlags() {
        TlBuilder legacyAttribute = TlBuilder.legacyObject(VideoAttributeLegacy)
            .put("round_message", Boolean.TRUE)
            .put("supports_streaming", Boolean.TRUE)
            .put("duration", 3)
            .put("w", 1)
            .put("h", 2);
        TlBuilder currentAttribute = TlBuilder.object("documentAttributeVideo")
            .put("round_message", Boolean.TRUE)
            .put("supports_streaming", Boolean.TRUE)
            .put("duration", 3.0)
            .put("w", 1)
            .put("h", 2);
        byte[] legacy = legacyAttribute.toBytes();
        byte[] upgraded = upgrader.upgradeObject(new TlReader(legacy, 0, legacy.length), "DocumentAttribute");
        assertArrayEquals(currentAttribute.toBytes(), upgraded);
    }


    @Test
    public void topLevelLegacyInsideWrappersUpgrades() {
        byte[] legacy = TlBuilder.legacyMethod(GetMessagesLegacy).put("id", intList(1)).toBytes();
        TlWriter wrapped = new TlWriter();
        wrapped.writeInt32(InvokeWithLayer);
        wrapped.writeInt32(229);
        wrapped.writeRaw(legacy);
        TlWriter expected = new TlWriter();
        expected.writeInt32(InvokeWithLayer);
        expected.writeInt32(229);
        expected.writeRaw(TlBuilder.method("messages.getMessages").put("id", inputMessageIds(1)).toBytes());
        assertArrayEquals(
            transcoder.encodeRequest(expected.toByteArray()).proto,
            transcoder.encodeRequest(wrapped.toByteArray()).proto
        );
    }


    @Test
    public void themeSettingsObjectBecomesOneElementVector() {
        TlBuilder settings = TlBuilder.object("inputThemeSettings")
            .put("base_theme", TlBuilder.object("baseThemeClassic"))
            .put("accent_color", 1);
        byte[] legacy = TlBuilder.legacyMethod(CreateThemeLegacy)
            .put("slug", "s")
            .put("title", "t")
            .put("settings", settings)
            .toBytes();
        List<Object> one = new ArrayList<Object>();
        one.add(settings);
        byte[] current = TlBuilder.method("account.createTheme")
            .put("slug", "s")
            .put("title", "t")
            .put("settings", one)
            .toBytes();
        assertArrayEquals(current, upgrader.upgradeRequest(legacy));
        assertEncodesAs(legacy, current);
    }


    @Test
    public void unsupportedKindChangeThrowsWithPath() {
        LegacySpec spec = schema.legacyMethod(ContactsGetContactsLegacy);
        assertNotNull(spec);
        assertFalse(spec.supported);
        // contacts.getContacts#22c6aa08 took hash:string, layer 229 takes hash:long.
        TlWriter writer = new TlWriter();
        writer.writeInt32(ContactsGetContactsLegacy);
        writer.writeString("abc");
        try {
            transcoder.encodeRequest(writer.toByteArray());
            fail("expected TranscodeException");
        } catch (TranscodeException e) {
            assertTrue(e.getMessage(), e.getMessage().contains("contacts.getContacts.hash"));
            assertTrue(e.getMessage(), e.getMessage().contains("string"));
        }
    }


    @Test
    public void unknownIdsStillThrow() {
        TlWriter writer = new TlWriter();
        writer.writeInt32(0x12345678);
        try {
            transcoder.encodeRequest(writer.toByteArray());
            fail("expected TranscodeException");
        } catch (TranscodeException e) {
            assertTrue(e.getMessage(), e.getMessage().contains("unknown method id"));
        }
    }


    @Test
    public void everySupportedLegacyEntryUpgradesAndEncodes() {
        int checked = 0;
        TlSynth synth = new TlSynth(schema);
        for (boolean allFlags : new boolean[] {false, true}) {
            for (LegacySpec spec : schema.legacyMethods()) {
                if (!spec.supported) {
                    continue;
                }
                byte[] legacy = synth.legacy(spec, allFlags);
                byte[] upgraded = upgrader.upgradeRequest(legacy);
                assertEquals(spec.name, spec.targetId, new TlReader(upgraded, 0, 4).readInt32());
                transcoder.encodeRequest(legacy);
                checked++;
            }
            for (LegacySpec spec : schema.legacyConstructors()) {
                if (!spec.supported) {
                    continue;
                }
                byte[] legacy = synth.legacy(spec, allFlags);
                String type = schema.constructor(spec.targetId).type;
                byte[] upgraded = upgrader.upgradeObject(new TlReader(legacy, 0, legacy.length), type);
                assertEquals(spec.name, spec.targetId, new TlReader(upgraded, 0, 4).readInt32());
                transcoder.tlObjectToProto(type, legacy);
                checked++;
            }
        }
        assertTrue("checked " + checked, checked > 1000);
    }
}
