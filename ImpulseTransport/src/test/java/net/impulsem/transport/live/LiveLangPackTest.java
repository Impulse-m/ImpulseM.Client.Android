package net.impulsem.transport.live;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import net.impulsem.transport.codec.EncodedRequest;
import net.impulsem.transport.codec.Transcoder;
import net.impulsem.transport.grpcweb.GrpcWebClient;
import net.impulsem.transport.grpcweb.GrpcWebResponse;
import net.impulsem.transport.schema.TlProtoSchema;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import org.junit.Assume;
import org.junit.Test;


/** Read-only, unauthenticated language RPC checks, enabled with -Dimpulse.live=true. */
public class LiveLangPackTest {

    private final TlProtoSchema schema = TlProtoSchema.load();
    private final Transcoder transcoder = new Transcoder(schema);


    @Test
    public void androidCanListDownloadAndUpdateRussianTranslations() throws IOException {
        Assume.assumeTrue(Boolean.getBoolean("impulse.live"));
        OkHttpClient http = new OkHttpClient.Builder().callTimeout(30, TimeUnit.SECONDS).build();
        GrpcWebClient client = new GrpcWebClient(http, HttpUrl.get("https://rpc.impulsem.net"));
        try {
            List<?> languages = (List<?>) call(
                client, "langpack.getLanguages", TlBuilder.legacyMethod(0x800fd57d)
            );
            assertTrue(languages.stream().anyMatch(value -> "ru".equals(((Map<?, ?>) value).get("lang_code"))));

            Map<?, ?> pack = (Map<?, ?>) call(
                client,
                "langpack.getLangPack",
                TlBuilder.legacyMethod(0x9ab5c58e).put("lang_code", "ru")
            );
            assertEquals("ru", pack.get("lang_code"));
            assertFalse(((List<?>) pack.get("strings")).isEmpty());

            List<?> strings = (List<?>) call(
                client,
                "langpack.getStrings",
                TlBuilder.legacyMethod(0x2e1ee318)
                    .put("lang_code", "ru")
                    .put("keys", Collections.singletonList("Cancel"))
            );
            assertEquals(1, strings.size());
            assertEquals("Cancel", ((Map<?, ?>) strings.get(0)).get("key"));
            assertEquals("\u041e\u0442\u043c\u0435\u043d\u0430", ((Map<?, ?>) strings.get(0)).get("value"));

            Map<?, ?> difference = (Map<?, ?>) call(
                client,
                "langpack.getDifference",
                TlBuilder.method("langpack.getDifference")
                    .put("lang_pack", "")
                    .put("lang_code", "ru")
                    .put("from_version", 0)
            );
            assertEquals("ru", difference.get("lang_code"));
            assertFalse(((List<?>) difference.get("strings")).isEmpty());

            Map<?, ?> language = (Map<?, ?>) call(
                client,
                "langpack.getLanguage",
                TlBuilder.method("langpack.getLanguage").put("lang_pack", "").put("lang_code", "ru")
            );
            assertEquals("ru", language.get("lang_code"));
        } finally {
            http.connectionPool().evictAll();
            http.dispatcher().executorService().shutdown();
        }
    }


    private Object call(GrpcWebClient client, String method, TlBuilder request) throws IOException {
        EncodedRequest encoded = transcoder.encodeRequest(request.toBytes());
        GrpcWebResponse response = client.execute(client.newCall(encoded.path, encoded.proto, null));
        assertEquals(method + ": " + response.grpcMessage, 0, response.grpcStatus);
        byte[] result = transcoder.decodeResult(encoded.methodId, response.body, null);
        return new TlView(schema).result(method, result);
    }
}
