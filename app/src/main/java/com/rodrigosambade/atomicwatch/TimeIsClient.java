package com.rodrigosambade.atomicwatch;

import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Iterator;
import java.util.Locale;
import java.util.TimeZone;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

final class TimeIsClient {
    private static final int CONNECT_TIMEOUT_MS = 5000;
    private static final int READ_TIMEOUT_MS = 5000;
    private static final long CONSERVATIVE_MIN_UNCERTAINTY_MS = 100L;

    interface Callback {
        void onSuccess(SyncResult result);
        void onFailure(String message, boolean receivedHttpResponse);
    }

    static final class Settings {
        final String endpoint;
        final String apiKey;
        final String authHeader;
        final String authPrefix;
        final long intervalSeconds;

        Settings(String endpoint, String apiKey, String authHeader, String authPrefix,
                 long intervalSeconds) {
            this.endpoint = endpoint == null ? "" : endpoint.trim();
            this.apiKey = apiKey == null ? "" : apiKey;
            this.authHeader = authHeader == null ? "" : authHeader.trim();
            this.authPrefix = authPrefix == null ? "" : authPrefix;
            this.intervalSeconds = intervalSeconds;
        }
    }

    static final class SyncResult {
        final long estimatedServerAtReceiveMs;
        final long responseElapsedRealtimeMs;
        final long rttMs;
        final long uncertaintyMs;
        final String serverTimeSource;

        SyncResult(long estimatedServerAtReceiveMs,
                   long responseElapsedRealtimeMs,
                   long rttMs,
                   long uncertaintyMs,
                   String serverTimeSource) {
            this.estimatedServerAtReceiveMs = estimatedServerAtReceiveMs;
            this.responseElapsedRealtimeMs = responseElapsedRealtimeMs;
            this.rttMs = rttMs;
            this.uncertaintyMs = uncertaintyMs;
            this.serverTimeSource = serverTimeSource;
        }
    }

    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "time-is-sync");
        thread.setDaemon(true);
        return thread;
    });
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private volatile Future<?> inFlight;

    void fetch(final Settings settings, final Callback callback) {
        cancel();
        inFlight = executor.submit(new Runnable() {
            @Override
            public void run() {
                try {
                    final SyncResult result = perform(settings);
                    if (!Thread.currentThread().isInterrupted()) {
                        mainHandler.post(new Runnable() {
                            @Override
                            public void run() {
                                callback.onSuccess(result);
                            }
                        });
                    }
                } catch (final HttpStatusException e) {
                    mainHandler.post(new Runnable() {
                        @Override
                        public void run() {
                            callback.onFailure(e.getMessage(), true);
                        }
                    });
                } catch (Exception e) {
                    if (!Thread.currentThread().isInterrupted()) {
                        String message = e.getMessage();
                        if (message == null || message.trim().isEmpty()) {
                            message = e.getClass().getSimpleName();
                        }
                        final String finalMessage = message;
                        mainHandler.post(new Runnable() {
                            @Override
                            public void run() {
                                callback.onFailure(finalMessage, false);
                            }
                        });
                    }
                }
            }
        });
    }

    void cancel() {
        Future<?> task = inFlight;
        if (task != null) {
            task.cancel(true);
            inFlight = null;
        }
    }

    void shutdown() {
        cancel();
        executor.shutdownNow();
    }

    private static SyncResult perform(Settings settings) throws Exception {
        if (settings.endpoint.isEmpty()) throw new IOException("API no configurada");
        String endpoint = settings.endpoint;
        if (!settings.apiKey.isEmpty() && endpoint.contains("{key}")) {
            endpoint = endpoint.replace("{key}", URLEncoder.encode(settings.apiKey, "UTF-8"));
        }
        URL url = new URL(endpoint);
        if (!"https".equalsIgnoreCase(url.getProtocol())) {
            throw new IOException("La API debe usar HTTPS");
        }

        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        connection.setRequestMethod("GET");
        connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
        connection.setReadTimeout(READ_TIMEOUT_MS);
        connection.setUseCaches(false);
        connection.setRequestProperty("Accept", "application/json, text/plain;q=0.9, */*;q=0.1");
        connection.setRequestProperty("User-Agent", "AtomicWatch/1.0 Android");
        if (!settings.apiKey.isEmpty()
                && !settings.endpoint.contains("{key}")
                && !settings.authHeader.isEmpty()) {
            connection.setRequestProperty(settings.authHeader, settings.authPrefix + settings.apiKey);
        }

        long requestMonoMs = SystemClock.elapsedRealtime();
        try {
            int status = connection.getResponseCode();
            InputStream stream = status >= 200 && status < 300
                    ? connection.getInputStream() : connection.getErrorStream();
            String body = readBody(stream);
            long responseMonoMs = SystemClock.elapsedRealtime();
            long rttMs = Math.max(0L, responseMonoMs - requestMonoMs);

            if (status < 200 || status >= 300) {
                String suffix = body.isEmpty() ? "" : ": " + abbreviate(body, 160);
                throw new HttpStatusException("HTTP " + status + suffix);
            }

            ParsedServerTime parsed = ServerTimeParser.parse(body, connection);
            long estimatedAtReceive = parsed.epochMs + rttMs / 2L;
            long transportUncertainty = rttMs / 2L + parsed.resolutionMs / 2L;
            long uncertainty = Math.max(CONSERVATIVE_MIN_UNCERTAINTY_MS, transportUncertainty);
            return new SyncResult(
                    estimatedAtReceive,
                    responseMonoMs,
                    rttMs,
                    uncertainty,
                    parsed.source);
        } finally {
            connection.disconnect();
        }
    }

    private static String readBody(InputStream stream) throws IOException {
        if (stream == null) return "";
        BufferedReader reader = new BufferedReader(new InputStreamReader(stream, "UTF-8"));
        try {
            StringBuilder out = new StringBuilder();
            char[] buffer = new char[2048];
            int read;
            while ((read = reader.read(buffer)) != -1) {
                out.append(buffer, 0, read);
                if (out.length() > 256_000) throw new IOException("Respuesta demasiado grande");
            }
            return out.toString().trim();
        } finally {
            reader.close();
        }
    }

    private static String abbreviate(String value, int max) {
        String normalized = value.replace('\n', ' ').replace('\r', ' ').trim();
        return normalized.length() <= max ? normalized : normalized.substring(0, max) + "…";
    }

    private static final class HttpStatusException extends IOException {
        HttpStatusException(String message) { super(message); }
    }

    private static final class ParsedServerTime {
        final long epochMs;
        final long resolutionMs;
        final String source;

        ParsedServerTime(long epochMs, long resolutionMs, String source) {
            this.epochMs = epochMs;
            this.resolutionMs = resolutionMs;
            this.source = source;
        }
    }

    private static final class ServerTimeParser {
        private static final String[] PRIORITY_KEYS = {
                "epochMillis", "epoch_millis", "unixMillis", "unix_millis",
                "unixtime", "unix_time", "unix", "timestamp", "epoch",
                "datetime", "dateTime", "utc_datetime", "serverTime",
                "server_time", "time", "now"
        };

        static ParsedServerTime parse(String body, HttpURLConnection connection)
                throws IOException {
            ParsedServerTime parsed = parseBody(body);
            if (parsed != null) return parsed;

            long headerDate = connection.getHeaderFieldDate("Date", -1L);
            if (headerDate > 0L) {
                return new ParsedServerTime(headerDate, 1000L, "HTTP Date header");
            }
            throw new IOException(
                    "La respuesta no contiene un timestamp reconocible. Ajuste el adaptador a la respuesta de su API de Time.is.");
        }

        private static ParsedServerTime parseBody(String body) {
            if (body == null || body.trim().isEmpty()) return null;
            String trimmed = body.trim();

            ParsedServerTime direct = parseScalar(trimmed, "body");
            if (direct != null) return direct;

            try {
                if (trimmed.startsWith("{")) {
                    return parseJsonObject(new JSONObject(trimmed), 0, "json");
                }
                if (trimmed.startsWith("[")) {
                    JSONArray array = new JSONArray(trimmed);
                    for (int i = 0; i < Math.min(array.length(), 8); i++) {
                        ParsedServerTime hit = parseJsonValue(array.opt(i), 0, "json[" + i + "]");
                        if (hit != null) return hit;
                    }
                }
            } catch (JSONException ignored) {
            }
            return null;
        }

        private static ParsedServerTime parseJsonObject(JSONObject object, int depth, String path) {
            if (depth > 3) return null;
            for (String key : PRIORITY_KEYS) {
                if (object.has(key)) {
                    ParsedServerTime hit = parseJsonValue(object.opt(key), depth + 1, path + "." + key);
                    if (hit != null) return hit;
                }
            }
            Iterator<String> keys = object.keys();
            while (keys.hasNext()) {
                String key = keys.next();
                Object value = object.opt(key);
                if (value instanceof JSONObject || value instanceof JSONArray) {
                    ParsedServerTime hit = parseJsonValue(value, depth + 1, path + "." + key);
                    if (hit != null) return hit;
                }
            }
            return null;
        }

        private static ParsedServerTime parseJsonValue(Object value, int depth, String path) {
            if (value == null || value == JSONObject.NULL || depth > 4) return null;
            if (value instanceof Number || value instanceof String) {
                return parseScalar(String.valueOf(value), path);
            }
            if (value instanceof JSONObject) {
                return parseJsonObject((JSONObject) value, depth, path);
            }
            if (value instanceof JSONArray) {
                JSONArray array = (JSONArray) value;
                for (int i = 0; i < Math.min(array.length(), 8); i++) {
                    ParsedServerTime hit = parseJsonValue(array.opt(i), depth + 1, path + "[" + i + "]");
                    if (hit != null) return hit;
                }
            }
            return null;
        }

        private static ParsedServerTime parseScalar(String raw, String source) {
            if (raw == null) return null;
            String value = raw.trim();
            if (value.length() >= 2 && value.startsWith("\"") && value.endsWith("\"")) {
                value = value.substring(1, value.length() - 1).trim();
            }
            try {
                double numeric = Double.parseDouble(value);
                long epoch = normalizeEpoch(numeric);
                if (isPlausibleEpoch(epoch)) {
                    long resolution = value.contains(".") ? 1L
                            : (Math.abs(numeric) < 100_000_000_000L ? 1000L : 1L);
                    return new ParsedServerTime(epoch, resolution, source);
                }
            } catch (NumberFormatException ignored) {
            }

            Long iso = parseIso8601(value);
            if (iso != null && isPlausibleEpoch(iso)) {
                long resolution = value.contains(".") ? 1L : 1000L;
                return new ParsedServerTime(iso, resolution, source);
            }
            return null;
        }

        private static long normalizeEpoch(double value) {
            double abs = Math.abs(value);
            if (abs > 1.0e17) return Math.round(value / 1.0e6);
            if (abs > 1.0e14) return Math.round(value / 1.0e3);
            if (abs > 1.0e11) return Math.round(value);
            return Math.round(value * 1000.0);
        }

        private static boolean isPlausibleEpoch(long epochMs) {
            long year2000 = 946684800000L;
            long year2200 = 7258118400000L;
            return epochMs >= year2000 && epochMs <= year2200;
        }

        private static Long parseIso8601(String input) {
            if (input == null || input.length() < 10) return null;
            String normalized = input.trim();
            if (normalized.endsWith("Z")) {
                normalized = normalized.substring(0, normalized.length() - 1) + "+0000";
            } else if (normalized.matches(".*[+-]\\d\\d:\\d\\d$")) {
                int colon = normalized.length() - 3;
                normalized = normalized.substring(0, colon) + normalized.substring(colon + 1);
            }

            String[] patterns = {
                    "yyyy-MM-dd'T'HH:mm:ss.SSSZ",
                    "yyyy-MM-dd'T'HH:mm:ssZ",
                    "yyyy-MM-dd HH:mm:ss.SSSZ",
                    "yyyy-MM-dd HH:mm:ssZ",
                    "yyyy-MM-dd'T'HH:mm:ss.SSS",
                    "yyyy-MM-dd'T'HH:mm:ss",
                    "yyyy-MM-dd HH:mm:ss.SSS",
                    "yyyy-MM-dd HH:mm:ss"
            };
            for (String pattern : patterns) {
                SimpleDateFormat format = new SimpleDateFormat(pattern, Locale.US);
                format.setLenient(false);
                format.setTimeZone(TimeZone.getTimeZone("UTC"));
                try {
                    Date date = format.parse(normalized);
                    if (date != null) return date.getTime();
                } catch (ParseException ignored) {
                }
            }
            return null;
        }
    }
}
