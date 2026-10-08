package net.impulsem.proxy;

import java.util.Locale;


/** What the user pasted into "Add": share links, a subscription URL, or neither. */
public enum InputKind {
    LINKS,
    SUBSCRIPTION,
    UNKNOWN;


    public static InputKind detect(String text) {
        String value = text == null ? "" : text.trim();
        if (value.isEmpty()) {
            return UNKNOWN;
        }
        String lower = value.toLowerCase(Locale.ROOT);
        if ((lower.startsWith("https://") || lower.startsWith("http://")) && !containsWhitespace(value)) {
            return SUBSCRIPTION;
        }
        for (String line : value.split("\\r?\\n")) {
            if (line.trim().toLowerCase(Locale.ROOT).startsWith("vless://")) {
                return LINKS;
            }
        }
        return UNKNOWN;
    }


    private static boolean containsWhitespace(String value) {
        for (int i = 0; i < value.length(); i++) {
            if (Character.isWhitespace(value.charAt(i))) {
                return true;
            }
        }
        return false;
    }
}
