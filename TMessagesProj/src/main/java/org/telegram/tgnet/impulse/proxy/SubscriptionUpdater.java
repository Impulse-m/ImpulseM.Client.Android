package org.telegram.tgnet.impulse.proxy;

import android.util.Base64;
import com.google.gson.JsonArray;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import net.impulsem.proxy.LibXrayClient;
import net.impulsem.proxy.OutboundFilter;
import net.impulsem.proxy.ProxyServer;
import net.impulsem.proxy.Subscription;
import net.impulsem.proxy.SubscriptionMeta;
import net.impulsem.proxy.XrayException;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.telegram.messenger.FileLog;
import org.telegram.tgnet.impulse.ImpulseConnection;


/** Downloads and parses one VLESS subscription. The URL is a secret: it never reaches logs or error text. */
public final class SubscriptionUpdater {

    private static final String UserAgent = "v2rayNG/1.10.0";
    private static final long TimeoutSeconds = 30L;
    private static final long MaxBodyBytes = 4L * 1024L * 1024L;
    private static final int IdLength = 24;


    private SubscriptionUpdater() {
    }


    /** Stable per URL: the first 24 hex characters of its SHA-256. */
    public static String idFor(String url) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(url.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(digest.length * 2);
            for (byte value : digest) {
                hex.append(String.format("%02x", value & 0xff));
            }
            return hex.substring(0, IdLength);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }


    /**
     * Fetches the subscription and returns the new state. On any failure the previous servers and metadata are
     * kept and only lastError and updatedAt change; with no previous state the result has no servers and a
     * non-null lastError. Blocking: call off the UI thread.
     */
    public static Subscription fetch(
        LibXrayClient xray,
        String url,
        Subscription previous
    ) {
        String id = idFor(url);
        long now = System.currentTimeMillis();
        FileLog.d("impulse proxy: fetching subscription " + id + " from " + hostOf(url));
        String error;
        try {
            Subscription fresh = download(xray, url, id, now);
            FileLog.d("impulse proxy: subscription " + id + " ok, " + fresh.servers.size() + " servers, " + fresh.skipped + " skipped");
            return fresh;
        } catch (FetchException e) {
            error = e.getMessage();
        }
        FileLog.d("impulse proxy: subscription " + id + " failed: " + error);
        if (previous != null) {
            return new Subscription(id, url, previous.meta, now, error, previous.skipped, previous.servers);
        }
        return new Subscription(id, url, null, now, error, 0, new ArrayList<ProxyServer>());
    }


    private static Subscription download(
        LibXrayClient xray,
        String url,
        String id,
        long now
    ) throws FetchException {
        Request request;
        try {
            request = new Request.Builder()
                .url(url)
                .header("User-Agent", UserAgent)
                .get()
                .build();
        } catch (IllegalArgumentException e) {
            throw new FetchException(ProxyErrors.InvalidSubscription);
        }
        OkHttpClient client = ImpulseConnection.httpClient()
            .newBuilder()
            .callTimeout(TimeoutSeconds, TimeUnit.SECONDS)
            .build();
        String text;
        SubscriptionMeta meta;
        try (Response response = client.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                throw new FetchException(ProxyErrors.withArg(ProxyErrors.Http, response.code()));
            }
            byte[] bytes = response.peekBody(MaxBodyBytes + 1L).bytes();
            if (bytes.length > MaxBodyBytes) {
                throw new FetchException(ProxyErrors.InvalidSubscription);
            }
            text = stripBom(new String(bytes, StandardCharsets.UTF_8));
            meta = SubscriptionMeta.parse(
                response.header("profile-title"),
                response.header("subscription-userinfo"),
                response.header("profile-update-interval"),
                new SubscriptionMeta.Base64Decoder() {
                    @Override
                    public byte[] decode(String value) {
                        return Base64.decode(value, Base64.DEFAULT);
                    }
                }
            );
        } catch (IOException e) {
            throw new FetchException(ProxyErrors.Network);
        }

        JsonArray outbounds;
        try {
            outbounds = xray.convertShareLinks(text);
        } catch (XrayException e) {
            String message = e.getMessage();
            if (message != null && message.toLowerCase(Locale.ROOT).contains("no valid outbound")) {
                throw new FetchException(ProxyErrors.NoLinks);
            }
            throw new FetchException(ProxyErrors.InvalidSubscription);
        }
        OutboundFilter.Result result = OutboundFilter.filter(outbounds);
        if (result.servers.isEmpty()) {
            if (result.skipped > 0) {
                throw new FetchException(ProxyErrors.withArg(ProxyErrors.NoLinks, result.skipped));
            }
            throw new FetchException(ProxyErrors.NoLinks);
        }
        return new Subscription(id, url, meta, now, null, result.skipped, result.servers);
    }


    private static String stripBom(String text) {
        return !text.isEmpty() && text.charAt(0) == '\uFEFF' ? text.substring(1) : text;
    }


    private static String hostOf(String url) {
        HttpUrl parsed = HttpUrl.parse(url);
        return parsed == null ? "?" : parsed.host();
    }


    private static final class FetchException extends Exception {

        FetchException(String message) {
            super(message);
        }
    }
}
