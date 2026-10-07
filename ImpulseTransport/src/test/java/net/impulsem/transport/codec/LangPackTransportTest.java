package net.impulsem.transport.codec;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import net.impulsem.transport.live.TlBuilder;
import net.impulsem.transport.schema.TlProtoSchema;
import net.impulsem.transport.wire.ProtoWriter;
import org.junit.Test;


public class LangPackTransportTest {

    private final Transcoder transcoder = new Transcoder(TlProtoSchema.load());


    @Test
    public void legacyLanguageListRequestsTheAndroidPack() {
        assertRequest(TlBuilder.legacyMethod(0x800fd57d), "GetLanguages", "android");
    }


    @Test
    public void legacyLanguageDownloadKeepsTheRequestedLanguage() {
        assertRequest(
            TlBuilder.legacyMethod(0x9ab5c58e).put("lang_code", "ru"),
            "GetLangPack",
            "android"
        );
    }


    @Test
    public void legacyStringLookupKeepsEveryRequestedKey() {
        assertRequest(
            TlBuilder.legacyMethod(0x2e1ee318)
                .put("lang_code", "ru")
                .put("keys", Arrays.asList("Cancel", "OK")),
            "GetStrings",
            "android"
        );
    }


    @Test
    public void allCurrentLanguageMethodsDefaultToAndroid() {
        for (String method : Arrays.asList(
            "GetLanguages", "GetLangPack", "GetStrings", "GetDifference", "GetLanguage"
        )) {
            assertRequest(currentRequest(method, ""), method, "android");
        }
    }


    @Test
    public void explicitlySelectedPacksArePreserved() {
        for (String method : Arrays.asList(
            "GetLanguages", "GetLangPack", "GetStrings", "GetDifference", "GetLanguage"
        )) {
            assertRequest(currentRequest(method, "android_x"), method, "android_x");
        }
    }


    private TlBuilder currentRequest(String method, String pack) {
        String tlMethod = "langpack." + Character.toLowerCase(method.charAt(0)) + method.substring(1);
        TlBuilder request = TlBuilder.method(tlMethod).put("lang_pack", pack);
        if (!method.equals("GetLanguages")) {
            request.put("lang_code", "ru");
        }
        if (method.equals("GetStrings")) {
            request.put("keys", Arrays.asList("Cancel", "OK"));
        }
        if (method.equals("GetDifference")) {
            request.put("from_version", 17);
        }
        return request;
    }


    private void assertRequest(TlBuilder request, String method, String pack) {
        ProtoWriter expected = new ProtoWriter();
        expected.writeBytesField(1, pack.getBytes(StandardCharsets.UTF_8));
        if (!method.equals("GetLanguages")) {
            expected.writeBytesField(2, "ru".getBytes(StandardCharsets.UTF_8));
        }
        if (method.equals("GetStrings")) {
            expected.writeBytesField(3, "Cancel".getBytes(StandardCharsets.UTF_8));
            expected.writeBytesField(3, "OK".getBytes(StandardCharsets.UTF_8));
        }
        if (method.equals("GetDifference")) {
            expected.writeVarintField(3, 17);
        }
        EncodedRequest encoded = transcoder.encodeRequest(request.toBytes());
        assertEquals("/v1.langpack.Langpack/" + method, encoded.path);
        assertArrayEquals(method, expected.toByteArray(), encoded.proto);
    }
}
