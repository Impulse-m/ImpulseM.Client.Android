package net.impulsem.proxy;

import static org.junit.Assert.assertEquals;

import org.junit.Test;


public class InputKindTest {

    @Test
    public void singleAndMultipleLinks() {
        assertEquals(InputKind.LINKS, InputKind.detect("vless://a@b:1#x"));
        assertEquals(InputKind.LINKS, InputKind.detect("  vless://a@b:1\r\nvless://c@d:2\n"));
    }


    @Test
    public void subscriptionUrl() {
        assertEquals(InputKind.SUBSCRIPTION, InputKind.detect(" https://panel.example/sub/abc "));
        assertEquals(InputKind.SUBSCRIPTION, InputKind.detect("HTTP://panel.example/sub"));
    }


    @Test
    public void everythingElseIsUnknown() {
        assertEquals(InputKind.UNKNOWN, InputKind.detect(""));
        assertEquals(InputKind.UNKNOWN, InputKind.detect("vmess://abc"));
        assertEquals(InputKind.UNKNOWN, InputKind.detect("https://a b"));
    }
}
