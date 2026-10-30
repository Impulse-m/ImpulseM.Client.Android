package org.telegram.tgnet.impulse;

import org.telegram.messenger.BuildConfig;

import java.util.Locale;


/**
 * The build-time values of this client, the only reader of the IMPULSEM_* BuildConfig fields. Each accessor returns
 * null when the build did not set the value, so a caller can hide the feature instead of using an empty string.
 * Values are normalised here: URLs lose trailing slashes, the link host becomes a bare lowercase host.
 */
public final class ImpulseConfig {

    private static final String HttpsScheme = "https://";
    private static final String HttpScheme = "http://";


    private ImpulseConfig() {
    }


    /** The transport host base URL, e.g. https://rpc.example.org; null when the build has none. */
    public static String rpcUrl() {
        return normalizeUrl(BuildConfig.IMPULSEM_RPC_URL);
    }


    /** The project website base URL without a trailing slash; null when the build has none. */
    public static String siteUrl() {
        return normalizeUrl(BuildConfig.IMPULSEM_SITE_URL);
    }


    /** The host of public share and deep links, e.g. o.example.org; null when the build has none. */
    public static String linkHost() {
        return normalizeHost(BuildConfig.IMPULSEM_LINK_HOST);
    }


    /** The URL of the web client, e.g. https://web.example.org/k; null when the build has none. */
    public static String webUrl() {
        return normalizeUrl(BuildConfig.IMPULSEM_WEB_URL);
    }


    private static String normalizeUrl(String value) {
        String url = trimTrailingSlashes(value == null ? "" : value.trim());
        return url.isEmpty() ? null : url;
    }


    private static String normalizeHost(String value) {
        String host = value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
        if (host.startsWith(HttpsScheme)) {
            host = host.substring(HttpsScheme.length());
        } else if (host.startsWith(HttpScheme)) {
            host = host.substring(HttpScheme.length());
        }
        host = trimTrailingSlashes(host);
        return host.isEmpty() ? null : host;
    }


    private static String trimTrailingSlashes(String value) {
        int end = value.length();
        while (end > 0 && value.charAt(end - 1) == '/') {
            end--;
        }
        return value.substring(0, end);
    }
}
