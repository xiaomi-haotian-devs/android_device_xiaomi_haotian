package com.mipay.wallet.qr;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.IDN;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import javax.net.ssl.HttpsURLConnection;

/** Opt-in local snapshot of Xiaomi Scanner's scan-payment cloud configuration. */
public final class CloudRuleStore {
    private static final String CONFIG_URL =
            "https://aivision.xiaoaiscan.net/api/v1/scanner/configuration"
                    + "?id=scan_xiaomipay_rule";
    private static final String PREFERENCES = "qr_cloud_rules";
    private static final String FILE_NAME = "qr_cloud_https_prefixes.txt";
    private static final String KEY_UPDATED_AT = "updated_at";
    private static final String KEY_SOURCE_COUNT = "source_count";
    private static final String KEY_ACCEPTED_COUNT = "accepted_count";
    private static final String KEY_REJECTED_COUNT = "rejected_count";
    private static final int MAX_DOWNLOAD_BYTES = 4 * 1024 * 1024;
    private static final int MAX_SOURCE_RULES = 50000;
    private static final int MAX_RULE_LENGTH = 1024;
    private static final ExecutorService DOWNLOAD_EXECUTOR =
            Executors.newSingleThreadExecutor();

    public interface UpdateCallback {
        void onComplete(UpdateResult result);
    }

    public static final class Stats {
        public final long updatedAt;
        public final int sourceCount;
        public final int acceptedCount;
        public final int rejectedCount;

        private Stats(long updatedAt, int sourceCount, int acceptedCount, int rejectedCount) {
            this.updatedAt = updatedAt;
            this.sourceCount = sourceCount;
            this.acceptedCount = acceptedCount;
            this.rejectedCount = rejectedCount;
        }

        public boolean isDownloaded() {
            return updatedAt > 0L && acceptedCount > 0;
        }
    }

    public static final class UpdateResult {
        public final boolean success;
        public final Stats stats;
        public final String error;

        private UpdateResult(boolean success, Stats stats, String error) {
            this.success = success;
            this.stats = stats;
            this.error = error;
        }
    }

    private final Context context;
    private final SharedPreferences preferences;
    private static volatile Map<String, List<String>> cachedRules;

    public CloudRuleStore(Context context) {
        this.context = context.getApplicationContext();
        preferences = this.context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE);
    }

    public boolean matches(Uri uri) {
        if (uri == null || !"https".equalsIgnoreCase(uri.getScheme())
                || uri.getHost() == null || uri.getUserInfo() != null
                || (uri.getPort() != -1 && uri.getPort() != 443)) {
            return false;
        }
        String host = normalizeHost(uri.getHost());
        if (host == null) {
            return false;
        }
        List<String> prefixes = getRules().get(host);
        if (prefixes == null) {
            return false;
        }
        String suffix = uriSuffix(uri.toString());
        for (String prefix : prefixes) {
            if (suffix.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    public Stats getStats() {
        return new Stats(
                preferences.getLong(KEY_UPDATED_AT, 0L),
                preferences.getInt(KEY_SOURCE_COUNT, 0),
                preferences.getInt(KEY_ACCEPTED_COUNT, 0),
                preferences.getInt(KEY_REJECTED_COUNT, 0));
    }

    public void update(UpdateCallback callback) {
        DOWNLOAD_EXECUTOR.execute(() -> {
            UpdateResult result;
            try {
                result = downloadAndStore();
            } catch (Exception e) {
                String message = e.getMessage();
                result = new UpdateResult(false, getStats(),
                        message == null ? e.getClass().getSimpleName() : message);
            }
            callback.onComplete(result);
        });
    }

    public void delete() {
        try {
            Files.deleteIfExists(getRuleFile().toPath());
        } catch (Exception ignored) {
        }
        cachedRules = Collections.emptyMap();
        preferences.edit().clear().apply();
    }

    private UpdateResult downloadAndStore() throws Exception {
        URL url = new URL(CONFIG_URL);
        HttpsURLConnection connection = (HttpsURLConnection) url.openConnection();
        connection.setConnectTimeout(15000);
        connection.setReadTimeout(20000);
        connection.setInstanceFollowRedirects(false);
        connection.setRequestProperty("Accept", "application/json");
        connection.setRequestProperty("User-Agent", "HaotianWallet/QRRules");

        byte[] body;
        try {
            int status = connection.getResponseCode();
            if (status != HttpURLConnection.HTTP_OK) {
                throw new IllegalStateException("HTTP " + status);
            }
            int declaredLength = connection.getContentLength();
            if (declaredLength > MAX_DOWNLOAD_BYTES) {
                throw new IllegalStateException("配置文件过大");
            }
            try (BufferedInputStream input = new BufferedInputStream(connection.getInputStream());
                    ByteArrayOutputStream output = new ByteArrayOutputStream(
                            Math.max(0, Math.min(declaredLength, MAX_DOWNLOAD_BYTES)))) {
                byte[] buffer = new byte[8192];
                int total = 0;
                int read;
                while ((read = input.read(buffer)) != -1) {
                    total += read;
                    if (total > MAX_DOWNLOAD_BYTES) {
                        throw new IllegalStateException("配置文件过大");
                    }
                    output.write(buffer, 0, read);
                }
                body = output.toByteArray();
            }
        } finally {
            connection.disconnect();
        }

        JSONObject object = new JSONObject(new String(body, StandardCharsets.UTF_8));
        JSONArray rules = object.getJSONArray("xiaoMipayRules");
        if (rules.length() == 0 || rules.length() > MAX_SOURCE_RULES) {
            throw new IllegalStateException("云控规则数量异常");
        }

        LinkedHashSet<String> acceptedTargets = new LinkedHashSet<>();
        for (int i = 0; i < rules.length(); i++) {
            String rule = rules.optString(i, "");
            String target = extractHttpsTarget(rule);
            String host = target == null ? null : target.substring(0, target.indexOf('\t'));
            if (target == null || isTestHost(host)) {
                continue;
            } else {
                acceptedTargets.add(target);
            }
        }
        if (acceptedTargets.isEmpty()) {
            throw new IllegalStateException("没有可用的 HTTPS 规则");
        }

        File destination = getRuleFile();
        File temporary = new File(destination.getParentFile(), FILE_NAME + ".new");
        try (FileOutputStream output = new FileOutputStream(temporary, false)) {
            for (String target : acceptedTargets) {
                output.write(target.getBytes(StandardCharsets.UTF_8));
                output.write('\n');
            }
            output.getFD().sync();
        }
        try {
            Files.move(temporary.toPath(), destination.toPath(),
                    StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(temporary.toPath(), destination.toPath(),
                    StandardCopyOption.REPLACE_EXISTING);
        }

        long updatedAt = System.currentTimeMillis();
        int rejectedCount = rules.length() - acceptedTargets.size();
        preferences.edit()
                .putLong(KEY_UPDATED_AT, updatedAt)
                .putInt(KEY_SOURCE_COUNT, rules.length())
                .putInt(KEY_ACCEPTED_COUNT, acceptedTargets.size())
                .putInt(KEY_REJECTED_COUNT, rejectedCount)
                .apply();
        cachedRules = immutableRules(acceptedTargets);
        Stats stats = new Stats(updatedAt, rules.length(), acceptedTargets.size(), rejectedCount);
        return new UpdateResult(true, stats, null);
    }

    private Map<String, List<String>> getRules() {
        Map<String, List<String>> rules = cachedRules;
        if (rules != null) {
            return rules;
        }
        synchronized (CloudRuleStore.class) {
            if (cachedRules == null) {
                cachedRules = readRules();
            }
            return cachedRules;
        }
    }

    private Map<String, List<String>> readRules() {
        LinkedHashSet<String> targets = new LinkedHashSet<>();
        File file = getRuleFile();
        if (!file.isFile()) {
            return Collections.emptyMap();
        }
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                new FileInputStream(file), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                int separator = line.indexOf('\t');
                if (separator > 0) {
                    String host = normalizeHost(line.substring(0, separator));
                    String suffix = line.substring(separator + 1);
                    if (host != null && suffix.indexOf('\t') < 0) {
                        targets.add(host + '\t' + suffix);
                    }
                }
            }
        } catch (Exception ignored) {
            targets.clear();
        }
        return immutableRules(targets);
    }

    private File getRuleFile() {
        return new File(context.getFilesDir(), FILE_NAME);
    }

    private static String extractHttpsTarget(String rule) {
        if (rule == null || rule.isEmpty() || rule.length() > MAX_RULE_LENGTH
                || !rule.startsWith("^https://")) {
            return null;
        }
        StringBuilder hostBuilder = new StringBuilder();
        int i = "^https://".length();
        for (; i < rule.length(); i++) {
            char c = rule.charAt(i);
            if (c == '/') {
                break;
            }
            if (c == ':' || c == '?' || c == '#'
                    || c == '$' || c == '[' || c == '(' || c == '{' || c == '|') {
                return null;
            }
            if (c == '\\') {
                if (++i >= rule.length()) {
                    return null;
                }
                char escaped = rule.charAt(i);
                if (escaped != '.' && escaped != '-') {
                    return null;
                }
                hostBuilder.append(escaped);
                continue;
            }
            if (c == '.' && i + 1 < rule.length()) {
                char next = rule.charAt(i + 1);
                if (next == '+' || next == '*' || next == '?') {
                    break;
                }
            }
            if (Character.isLetterOrDigit(c) || c == '.' || c == '-') {
                hostBuilder.append(c);
            } else {
                return null;
            }
        }
        String host = normalizeHost(hostBuilder.toString());
        if (host == null) {
            return null;
        }

        StringBuilder suffix = new StringBuilder();
        for (; i < rule.length(); i++) {
            char c = rule.charAt(i);
            if (c == '\\') {
                if (++i >= rule.length()) {
                    return null;
                }
                char escaped = rule.charAt(i);
                if (escaped == '\n' || escaped == '\r' || escaped == '\t') {
                    return null;
                }
                suffix.append(escaped);
                continue;
            }
            if (c == '.' && i + 1 < rule.length()
                    && (rule.charAt(i + 1) == '+' || rule.charAt(i + 1) == '*')) {
                break;
            }
            if (c == '[' || c == '(' || c == '{' || c == '|'
                    || c == '$' || c == '^' || c == '+') {
                break;
            }
            if (c == '\n' || c == '\r' || c == '\t') {
                return null;
            }
            suffix.append(c);
        }
        return host + '\t' + suffix;
    }

    private static String uriSuffix(String uri) {
        int scheme = uri.indexOf("://");
        if (scheme < 0) {
            return "";
        }
        int authorityStart = scheme + 3;
        for (int i = authorityStart; i < uri.length(); i++) {
            char c = uri.charAt(i);
            if (c == '/' || c == '?' || c == '#') {
                return uri.substring(i);
            }
        }
        return "";
    }

    private static Map<String, List<String>> immutableRules(Set<String> targets) {
        HashMap<String, List<String>> mutable = new HashMap<>();
        for (String target : targets) {
            int separator = target.indexOf('\t');
            if (separator <= 0) {
                continue;
            }
            String host = target.substring(0, separator);
            String suffix = target.substring(separator + 1);
            mutable.computeIfAbsent(host, unused -> new ArrayList<>()).add(suffix);
        }
        HashMap<String, List<String>> immutable = new HashMap<>();
        for (Map.Entry<String, List<String>> entry : mutable.entrySet()) {
            immutable.put(entry.getKey(),
                    Collections.unmodifiableList(new ArrayList<>(entry.getValue())));
        }
        return Collections.unmodifiableMap(immutable);
    }

    private static String normalizeHost(String rawHost) {
        if (rawHost == null || rawHost.isEmpty()) {
            return null;
        }
        final String host;
        try {
            host = IDN.toASCII(rawHost, IDN.USE_STD3_ASCII_RULES)
                    .toLowerCase(Locale.ROOT);
        } catch (IllegalArgumentException e) {
            return null;
        }
        if (!isPublicDnsHost(host)) {
            return null;
        }
        return host;
    }

    public static boolean isPublicDnsHost(String rawHost) {
        if (rawHost == null) {
            return false;
        }
        String host = rawHost.toLowerCase(Locale.ROOT);
        if (host.length() > 253 || host.startsWith(".") || host.endsWith(".")
                || host.indexOf('.') <= 0 || "localhost".equals(host)) {
            return false;
        }
        String[] labels = host.split("\\.");
        for (String label : labels) {
            if (label.isEmpty() || label.length() > 63 || label.startsWith("-")
                    || label.endsWith("-")) {
                return false;
            }
            for (int i = 0; i < label.length(); i++) {
                char c = label.charAt(i);
                if (!Character.isLetterOrDigit(c) && c != '-') {
                    return false;
                }
            }
        }
        // Check syntax only. Matching a QR code must never perform a DNS lookup or leak its host.
        if (host.matches("[0-9.]+") || host.indexOf(':') >= 0) {
            return false;
        }
        return true;
    }

    private static boolean isTestHost(String host) {
        if (host.endsWith(".ngrok.io") || host.contains(".ngrok.")
                || host.endsWith(".iok.la") || host.endsWith(".wicp.net")
                || host.endsWith(".test") || host.endsWith(".local")) {
            return true;
        }
        String[] labels = host.split("\\.");
        for (String label : labels) {
            if ("test".equals(label) || "testing".equals(label) || "uat".equals(label)
                    || "staging".equals(label) || "stage".equals(label)
                    || "dev".equals(label) || "pre".equals(label)
                    || "preview".equals(label)) {
                return true;
            }
        }
        return false;
    }
}
