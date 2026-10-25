package net.impulsem.transport.auth;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import net.impulsem.transport.wire.ProtoReader;
import org.junit.Test;


public class InitConnectionBodyTest {

    private static final class Decoded {

        final Map<Integer, Long> varints = new HashMap<Integer, Long>();
        final Map<Integer, String> strings = new HashMap<Integer, String>();
        final Map<Integer, Integer> wireTypes = new HashMap<Integer, Integer>();
    }


    private static Decoded decode(byte[] proto) {
        Decoded result = new Decoded();
        ProtoReader reader = new ProtoReader(proto, 0, proto.length);
        while (reader.next()) {
            int field = reader.field();
            result.wireTypes.put(field, reader.wireType());
            if (reader.wireType() == 0) {
                result.varints.put(field, reader.readVarint());
            } else if (reader.wireType() == 2) {
                result.strings.put(field, new String(reader.readBytes(), StandardCharsets.UTF_8));
            } else {
                reader.skip();
            }
        }
        return result;
    }


    private static byte[] declarationBytes(byte[] request) {
        ProtoReader reader = new ProtoReader(request, 0, request.length);
        assertTrue(reader.next());
        assertEquals(1, reader.field());
        assertEquals(2, reader.wireType());
        byte[] inner = reader.readBytes();
        assertFalse("the request holds only the declaration", reader.next());
        return inner;
    }


    @Test
    public void declarationFieldsUseTheContractFieldNumbersAndWireTypes() {
        ConnectionDeclaration declaration = new ConnectionDeclaration(
            3,
            "HUAWEI MNA-LX9",
            "SDK 35",
            "12.10.6-beta.7 (10007)",
            "en",
            "android",
            "ru"
        );

        Decoded decoded = decode(declarationBytes(ImpulseRpcPaths.encodeInitConnection(declaration)));

        assertEquals(Integer.valueOf(0), decoded.wireTypes.get(1));
        assertEquals(Long.valueOf(3L), decoded.varints.get(1));
        for (int field = 2; field <= 7; field++) {
            assertEquals("wire type of field " + field, Integer.valueOf(2), decoded.wireTypes.get(field));
        }
        assertEquals("HUAWEI MNA-LX9", decoded.strings.get(2));
        assertEquals("SDK 35", decoded.strings.get(3));
        assertEquals("12.10.6-beta.7 (10007)", decoded.strings.get(4));
        assertEquals("en", decoded.strings.get(5));
        assertEquals("android", decoded.strings.get(6));
        assertEquals("ru", decoded.strings.get(7));
        assertFalse("proxy stays unset", decoded.wireTypes.containsKey(8));
        assertFalse("params stay unset", decoded.wireTypes.containsKey(9));
    }


    @Test
    public void nonAsciiValuesAreUtf8() {
        ConnectionDeclaration declaration = new ConnectionDeclaration(3, "Модель", "SDK 35", "1", "ru", "android", "ru");

        Decoded decoded = decode(declarationBytes(ImpulseRpcPaths.encodeInitConnection(declaration)));

        assertEquals("Модель", decoded.strings.get(2));
    }


    @Test
    public void emptyLanguageIsOmittedLikeProto3() {
        ConnectionDeclaration declaration = new ConnectionDeclaration(3, "m", "SDK 35", "1", "en", "android", "");

        Decoded decoded = decode(declarationBytes(ImpulseRpcPaths.encodeInitConnection(declaration)));

        assertFalse(decoded.wireTypes.containsKey(7));
        assertEquals("en", decoded.strings.get(5));
    }
}
