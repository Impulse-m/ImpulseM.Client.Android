package net.impulsem.transport.rpc;

import io.github.centrifugal.centrifuge.ReplyError;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;


/** Why a call on a Centrifugo carrier did not succeed, as the SDK reported it. */
public final class CarrierFailure {

    /** ReplyError.getCode(); 0 when the failure is not a ReplyError. */
    public final int sdkCode;
    /** ReplyError.getAppCode(); 0 when unset or not a ReplyError. */
    public final int appCode;
    /** ReplyError.getAppType(); "" when unset or not a ReplyError. Never null. */
    public final String appType;
    /** ReplyError.getMetadata(); empty when unset or not a ReplyError. Never null, unmodifiable. */
    public final Map<String, String> metadata;
    /** What the SDK passed to the callback. Never null. */
    public final Throwable cause;


    private CarrierFailure(
        int sdkCode,
        int appCode,
        String appType,
        Map<String, String> metadata,
        Throwable cause
    ) {
        this.sdkCode = sdkCode;
        this.appCode = appCode;
        this.appType = appType;
        this.metadata = metadata;
        this.cause = cause;
    }


    /** Builds the failure from what the SDK passed to a result callback. */
    public static CarrierFailure of(Throwable error) {
        Objects.requireNonNull(error, "error");
        if (error instanceof ReplyError) {
            ReplyError replyError = (ReplyError) error;
            String appType = replyError.getAppType() == null ? "" : replyError.getAppType();
            Map<String, String> source = replyError.getMetadata();
            Map<String, String> metadata = source == null
                ? Collections.<String, String>emptyMap()
                : Collections.unmodifiableMap(new HashMap<String, String>(source));
            return new CarrierFailure(replyError.getCode(), replyError.getAppCode(), appType, metadata, error);
        }
        return new CarrierFailure(0, 0, "", Collections.<String, String>emptyMap(), error);
    }
}
