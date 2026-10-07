package net.impulsem.proxy;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import java.nio.charset.StandardCharsets;
import org.junit.Test;


public class SubscriptionMetaTest {

    @Test
    public void plainTitleAndFullUserInfo() {
        SubscriptionMeta meta = SubscriptionMeta.parse(
            "My VPN",
            "upload=10; download=20; total=1000; expire=1790000000",
            "12"
        );
        assertEquals("My VPN", meta.title);
        assertEquals(10L, meta.upload);
        assertEquals(20L, meta.download);
        assertEquals(1000L, meta.total);
        assertEquals(1790000000L, meta.expire);
        assertEquals(12, meta.updateIntervalHours);
    }


    @Test
    public void base64Title() {
        assertEquals("Привет", SubscriptionMeta.parse("base64:0J/RgNC40LLQtdGC", null, null).title);
    }


    @Test
    public void missingAndBrokenValuesUseDefaults() {
        SubscriptionMeta meta = SubscriptionMeta.parse(null, "upload=x; total=", "abc");
        assertNull(meta.title);
        assertEquals(-1L, meta.upload);
        assertEquals(-1L, meta.download);
        assertEquals(-1L, meta.total);
        assertEquals(-1L, meta.expire);
        assertEquals(24, meta.updateIntervalHours);
    }


    @Test
    public void intervalIsAtLeastOneHour() {
        assertEquals(1, SubscriptionMeta.parse(null, null, "0").updateIntervalHours);
    }


    @Test
    public void injectedDecoderIsUsedForBase64Title() {
        SubscriptionMeta.Base64Decoder decoder = new SubscriptionMeta.Base64Decoder() {
            @Override
            public byte[] decode(String text) {
                return "Injected".getBytes(StandardCharsets.UTF_8);
            }
        };
        assertEquals("Injected", SubscriptionMeta.parse("base64:AAAA", null, null, decoder).title);
    }
}
