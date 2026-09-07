package com.mipay.wallet.qr;

import android.content.Context;
import android.net.Uri;

import java.util.Locale;

/** Validates QR payloads before they are handed to Xiaomi's scan-pay component. */
public final class QrRouting {
    public static final int MAX_PAYLOAD_LENGTH = 4096;

    public enum MatchType {
        FIXED,
        CLOUD,
        WECHAT,
        NONE
    }

    public static final class Match {
        public final MatchType type;
        public final String payload;
        public final String displayHost;

        private Match(MatchType type, String payload, String displayHost) {
            this.type = type;
            this.payload = payload;
            this.displayHost = displayHost;
        }

        public boolean isPayment() {
            return type != MatchType.NONE;
        }
    }

    private QrRouting() {
    }

    public static Match classify(Context context, String rawPayload) {
        String payload = normalizePayload(rawPayload);
        if (payload == null) {
            return new Match(MatchType.NONE, "", "");
        }

        String lower = payload.toLowerCase(Locale.ROOT);
        if (lower.startsWith("wxp://f2f")) {
            return new Match(MatchType.WECHAT, payload, "微信聚合收款码");
        }

        Uri uri = Uri.parse(payload);
        if (!isSafeUri(uri)) {
            return new Match(MatchType.NONE, payload, safeDisplayValue(uri));
        }

        String host = uri.getHost().toLowerCase(Locale.ROOT);
        if ("qr.95516.com".equals(host)
                || "trade.sdpay.mipay.com".equals(host)) {
            return new Match(MatchType.FIXED, payload, host);
        }

        if (isWechatHttpsPaymentUri(uri)) {
            return new Match(MatchType.WECHAT, payload, host);
        }

        CloudRuleStore cloudRules = new CloudRuleStore(context);
        if (cloudRules.matches(uri)) {
            return new Match(MatchType.CLOUD, payload, host);
        }

        return new Match(MatchType.NONE, payload, host);
    }

    public static String normalizePayload(String rawPayload) {
        if (rawPayload == null) {
            return null;
        }
        String payload = rawPayload.trim();
        if (payload.isEmpty() || payload.length() > MAX_PAYLOAD_LENGTH) {
            return null;
        }
        for (int i = 0; i < payload.length(); i++) {
            char c = payload.charAt(i);
            if (Character.isISOControl(c)) {
                return null;
            }
        }
        return payload;
    }

    public static boolean isSafeWebUri(String payload) {
        String normalized = normalizePayload(payload);
        if (normalized == null) {
            return false;
        }
        Uri uri = Uri.parse(normalized);
        String scheme = uri.getScheme();
        return scheme != null
                && ("https".equalsIgnoreCase(scheme) || "http".equalsIgnoreCase(scheme))
                && uri.getHost() != null
                && uri.getUserInfo() == null;
    }

    private static boolean isSafeUri(Uri uri) {
        String scheme = uri.getScheme();
        String host = uri.getHost();
        if (!"https".equalsIgnoreCase(scheme) || host == null || host.isEmpty()) {
            return false;
        }
        if (uri.getUserInfo() != null || (uri.getPort() != -1 && uri.getPort() != 443)) {
            return false;
        }
        return CloudRuleStore.isPublicDnsHost(host);
    }

    private static boolean isWechatHttpsPaymentUri(Uri uri) {
        String host = uri.getHost().toLowerCase(Locale.ROOT);
        String path = uri.getPath() == null ? "/" : uri.getPath();
        return ("wx.tenpay.com".equals(host) && path.startsWith("/f2f"))
                || ("payapp.weixin.qq.com".equals(host) && path.startsWith("/qr/"))
                || ("payapp.wechatpay.cn".equals(host) && path.startsWith("/qr/"));
    }

    private static String safeDisplayValue(Uri uri) {
        return uri.getHost() == null ? "未知格式" : uri.getHost();
    }
}
