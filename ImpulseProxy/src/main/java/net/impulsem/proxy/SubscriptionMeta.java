package net.impulsem.proxy;

import java.nio.charset.StandardCharsets;
import java.util.Base64;


/** Panel headers: profile-title, subscription-userinfo, profile-update-interval. */
public final class SubscriptionMeta {
    public static final int DefaultIntervalHours = 24;

    public final String title;
    public final long upload;
    public final long download;
    public final long total;
    public final long expire;
    public final int updateIntervalHours;


    public SubscriptionMeta(
        String title,
        long upload,
        long download,
        long total,
        long expire,
        int updateIntervalHours
    ) {
        this.title = title;
        this.upload = upload;
        this.download = download;
        this.total = total;
        this.expire = expire;
        this.updateIntervalHours = updateIntervalHours;
    }


    public interface Base64Decoder {
        byte[] decode(String text);
    }


    public static SubscriptionMeta parse(
        String profileTitle,
        String userInfo,
        String updateInterval
    ) {
        return parse(profileTitle, userInfo, updateInterval, new Base64Decoder() {
            @Override
            public byte[] decode(String text) {
                return Base64.getMimeDecoder().decode(text);
            }
        });
    }


    public static SubscriptionMeta parse(
        String profileTitle,
        String userInfo,
        String updateInterval,
        Base64Decoder decoder
    ) {
        String title = parseTitle(profileTitle, decoder);
        long upload = userInfoValue(userInfo, "upload");
        long download = userInfoValue(userInfo, "download");
        long total = userInfoValue(userInfo, "total");
        long expire = userInfoValue(userInfo, "expire");
        int interval = DefaultIntervalHours;
        if (updateInterval != null) {
            try {
                interval = Math.max(1, Integer.parseInt(updateInterval.trim()));
            } catch (NumberFormatException e) {
                interval = DefaultIntervalHours;
            }
        }
        return new SubscriptionMeta(title, upload, download, total, expire, interval);
    }


    private static String parseTitle(
        String header,
        Base64Decoder decoder
    ) {
        if (header == null || header.trim().isEmpty()) {
            return null;
        }
        String value = header.trim();
        if (value.startsWith("base64:")) {
            try {
                byte[] bytes = decoder.decode(value.substring("base64:".length()));
                return new String(bytes, StandardCharsets.UTF_8).trim();
            } catch (IllegalArgumentException e) {
                return null;
            }
        }
        return value;
    }


    private static long userInfoValue(
        String header,
        String key
    ) {
        if (header == null) {
            return -1L;
        }
        for (String part : header.split(";")) {
            String[] pair = part.trim().split("=", 2);
            if (pair.length == 2 && pair[0].trim().equalsIgnoreCase(key)) {
                try {
                    return Long.parseLong(pair[1].trim());
                } catch (NumberFormatException e) {
                    return -1L;
                }
            }
        }
        return -1L;
    }
}
