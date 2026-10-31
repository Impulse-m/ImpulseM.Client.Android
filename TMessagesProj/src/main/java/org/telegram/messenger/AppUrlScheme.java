package org.telegram.messenger;

public class AppUrlScheme {

    public static final String SCHEME = "im";
    public static final String CANONICAL_SCHEME = "tg";

    public static boolean isAppScheme(String scheme) {
        return SCHEME.equalsIgnoreCase(scheme) || CANONICAL_SCHEME.equalsIgnoreCase(scheme);
    }

    public static boolean isAppUrl(String url) {
        return url != null && (url.startsWith(SCHEME + ":") || url.startsWith(CANONICAL_SCHEME + ":"));
    }

    public static String toCanonicalUrl(String url) {
        if (url != null && url.startsWith(SCHEME + ":")) {
            return CANONICAL_SCHEME + url.substring(SCHEME.length());
        }
        return url;
    }
}
