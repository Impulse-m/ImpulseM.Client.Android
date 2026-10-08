package org.telegram.messenger;


/**
 * Central switches for client features that the ImpulseM backend does not implement.
 * Each {@code false} constant hides a feature the ImpulseM backend does not implement.
 * Flip the constant to {@code true} when the backend ships the feature.
 * {@code grep -rn "TODO(impulsem-unimplemented)"} lists every site.
 */
public final class ImpulseFeatures {

    // TODO(impulsem-unimplemented): stories.sendStory, stories.canSendStory, stories.editStory and the rest
    public static final boolean STORIES_POSTING = false;

    // TODO(impulsem-unimplemented): premium.getBoostsStatus, premium.getBoostsList, premium.getMyBoosts, premium.applyBoost
    public static final boolean BOOSTS = false;

    // TODO(impulsem-unimplemented): payments.getStarsStatus, payments.getStarsTransactions, payments.sendStarsForm and the rest
    public static final boolean STARS_AND_TON = false;

    // TODO(impulsem-unimplemented): messages.requestEncryption, messages.acceptEncryption, messages.sendEncrypted* and the rest
    public static final boolean SECRET_CHATS = false;

    // TODO(impulsem-unimplemented): messages.composeMessageWithAI, messages.composeRichMessageWithAI, messages.summarizeText, aicompose.*
    public static final boolean AI_COMPOSE = false;

    // TODO(impulsem-unimplemented): messages.setChatTheme, messages.setChatWallPaper
    public static final boolean CHAT_WALLPAPER_AND_THEME = false;

    // TODO(impulsem-unimplemented): account.getWebBrowserSettings, account.updateWebBrowserSettings and the exceptions methods
    public static final boolean BROWSER_SETTINGS = false;

    // TODO(impulsem-unimplemented): communities.create and the rest
    public static final boolean COMMUNITIES = false;

    // TODO(impulsem-unimplemented): payments.clearSavedInfo
    public static final boolean PAYMENT_SHIPPING_INFO = false;

    // TODO(impulsem-unimplemented): MTProto and SOCKS proxies, the transport is gRPC-Web
    public static final boolean PROXY = false;

    // TODO(impulsem-unimplemented): Ask a Question / FAQ / Privacy Policy point at Telegram support and telegram.org
    public static final boolean HELP_SECTION = false;


    /** VLESS proxy through the in-process Xray core. */
    public static final boolean VLESS = true;


    private ImpulseFeatures() {
    }
}
