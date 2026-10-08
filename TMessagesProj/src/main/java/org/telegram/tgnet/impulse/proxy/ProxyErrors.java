package org.telegram.tgnet.impulse.proxy;


/**
 * Language-neutral error codes the controller stores and reports. The UI maps them to localized text; a value
 * that is not a code (for example plain English persisted by an older build) is shown as-is.
 * A code is Prefix + name, optionally followed by ':' and a number.
 */
public final class ProxyErrors {

    public static final String Prefix = "err:";
    public static final String NotRunning = Prefix + "notRunning";
    public static final String InvalidSubscription = Prefix + "invalidSubscription";
    public static final String SubscriptionNotFound = Prefix + "subscriptionNotFound";
    public static final String NoLinks = Prefix + "noLinks";
    public static final String Http = Prefix + "http";
    public static final String Network = Prefix + "network";
    public static final String InvalidConfig = Prefix + "invalidConfig";
    public static final String CoreStart = Prefix + "coreStart";
    public static final String CoreError = Prefix + "coreError";
    public static final String CoreStopped = Prefix + "coreStopped";
    public static final String NoServer = Prefix + "noServer";


    private ProxyErrors() {
    }


    public static String withArg(
        String code,
        int argument
    ) {
        return code + ":" + argument;
    }


    /** The code without its numeric argument, or null when the value is not a code. */
    public static String nameOf(String value) {
        if (value == null || !value.startsWith(Prefix)) {
            return null;
        }
        int split = value.indexOf(':', Prefix.length());
        return split < 0 ? value : value.substring(0, split);
    }


    /** The numeric argument, or -1 when there is none. */
    public static int argumentOf(String value) {
        if (value == null || !value.startsWith(Prefix)) {
            return -1;
        }
        int split = value.indexOf(':', Prefix.length());
        if (split < 0) {
            return -1;
        }
        try {
            return Integer.parseInt(value.substring(split + 1));
        } catch (NumberFormatException e) {
            return -1;
        }
    }
}
