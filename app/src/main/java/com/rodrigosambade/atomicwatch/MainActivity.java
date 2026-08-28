package com.rodrigosambade.atomicwatch;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.support.v7.app.AlertDialog;
import android.support.v7.app.AppCompatActivity;
import android.text.InputType;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import net.i2p.android.router.util.ConnectivityAndInternetAccess;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;

public final class MainActivity extends AppCompatActivity {
    private static final String PREFS = "atomic_watch";
    private static final String PREF_API_URL = "time_is_api_url";
    private static final String PREF_API_KEY = "time_is_api_key";
    private static final String PREF_AUTH_HEADER = "time_is_auth_header";
    private static final String PREF_AUTH_PREFIX = "time_is_auth_prefix";
    private static final String PREF_INTERVAL_SECONDS = "sync_interval_seconds";
    private static final long DEFAULT_INTERVAL_SECONDS = 300L;
    private static final long MIN_INTERVAL_SECONDS = 60L;

    private final Handler uiHandler = new Handler(Looper.getMainLooper());
    private final AtomicClockModel clockModel = new AtomicClockModel();

    private TextView atomicTime;
    private TextView atomicDate;
    private TextView utcTime;
    private TextView systemError;
    private TextView systemTime;
    private TextView syncStatus;
    private TextView sourceDetails;
    private TextView networkStatus;
    private Button syncButton;

    private SharedPreferences preferences;
    private TimeIsClient timeIsClient;
    private ConnectivityAndInternetAccess.NetworkObserver networkObserver;
    private ConnectivityAndInternetAccess.NetworkState currentNetworkState;

    private boolean started;
    private boolean syncInProgress;
    private long lastSuccessfulSyncElapsedMs;
    private long lastRttMs;
    private long lastUncertaintyMs;
    private String lastParserSource = "—";
    private long lastSyncWallMs;

    private final Runnable clockTicker = new Runnable() {
        @Override
        public void run() {
            renderClock();
            if (started) uiHandler.postDelayed(this, 50L);
        }
    };

    private final Runnable periodicSyncTicker = new Runnable() {
        @Override
        public void run() {
            if (!started) return;
            long intervalMs = getSettings().intervalSeconds * 1000L;
            long nowElapsed = SystemClock.elapsedRealtime();
            if (isApiConfigured()
                    && !syncInProgress
                    && (lastSuccessfulSyncElapsedMs == 0L
                        || nowElapsed - lastSuccessfulSyncElapsedMs >= intervalMs)) {
                syncNow(false);
            }
            uiHandler.postDelayed(this, 15_000L);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        preferences = getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        timeIsClient = new TimeIsClient();

        atomicTime = findViewById(R.id.atomicTime);
        atomicDate = findViewById(R.id.atomicDate);
        utcTime = findViewById(R.id.utcTime);
        systemError = findViewById(R.id.systemError);
        systemTime = findViewById(R.id.systemTime);
        syncStatus = findViewById(R.id.syncStatus);
        sourceDetails = findViewById(R.id.sourceDetails);
        networkStatus = findViewById(R.id.networkStatus);
        syncButton = findViewById(R.id.syncButton);

        syncButton.setOnClickListener(v -> syncNow(true));
        findViewById(R.id.settingsButton).setOnClickListener(v -> showSettingsDialog());
        findViewById(R.id.openTimeIsButton).setOnClickListener(v -> openTimeIs());

        if (!isApiConfigured()) {
            syncStatus.setText("Configure la URL y las credenciales de la API oficial de Time.is.");
        }
        renderClock();
        renderSourceDetails();
    }

    @Override
    protected void onStart() {
        super.onStart();
        started = true;
        renderNetworkState();
        networkObserver = ConnectivityAndInternetAccess.observeNetwork(
                this,
                new ConnectivityAndInternetAccess.NetworkStateCallback() {
                    @Override
                    public void onStateChanged(ConnectivityAndInternetAccess.NetworkState state) {
                        currentNetworkState = state;
                        renderNetworkState();
                        if (ConnectivityAndInternetAccess.isConnectedOrConnecting(MainActivity.this)
                                && isApiConfigured()
                                && needsSyncSoon()
                                && !syncInProgress) {
                            syncNow(false);
                        }
                    }
                });
        uiHandler.removeCallbacks(clockTicker);
        uiHandler.removeCallbacks(periodicSyncTicker);
        uiHandler.post(clockTicker);
        uiHandler.post(periodicSyncTicker);
    }

    @Override
    protected void onStop() {
        started = false;
        uiHandler.removeCallbacks(clockTicker);
        uiHandler.removeCallbacks(periodicSyncTicker);
        if (networkObserver != null) {
            networkObserver.close();
            networkObserver = null;
        }
        if (timeIsClient != null) timeIsClient.cancel();
        syncInProgress = false;
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        if (timeIsClient != null) timeIsClient.shutdown();
        super.onDestroy();
    }

    private void syncNow(boolean userInitiated) {
        if (syncInProgress) return;
        final TimeIsClient.Settings settings = getSettings();
        if (settings.endpoint.trim().isEmpty()) {
            syncStatus.setText("Falta configurar la API oficial de Time.is.");
            if (userInitiated) showSettingsDialog();
            return;
        }
        if (!settings.endpoint.startsWith("https://")) {
            syncStatus.setText("La URL de Time.is debe usar HTTPS.");
            return;
        }
        if (!ConnectivityAndInternetAccess.isConnectedOrConnecting(this)) {
            syncStatus.setText("Sin red disponible. Se reintentará al volver la conexión.");
            return;
        }

        syncInProgress = true;
        syncButton.setEnabled(false);
        syncStatus.setText("Sincronizando con Time.is…");
        syncStatus.setTextColor(getResources().getColor(R.color.text_primary));

        timeIsClient.fetch(settings, new TimeIsClient.Callback() {
            @Override
            public void onSuccess(TimeIsClient.SyncResult result) {
                if (!started) return;
                syncInProgress = false;
                syncButton.setEnabled(true);
                clockModel.calibrate(
                        result.estimatedServerAtReceiveMs,
                        result.responseElapsedRealtimeMs);
                lastSuccessfulSyncElapsedMs = SystemClock.elapsedRealtime();
                lastRttMs = result.rttMs;
                lastUncertaintyMs = result.uncertaintyMs;
                lastParserSource = result.serverTimeSource;
                lastSyncWallMs = System.currentTimeMillis();
                syncStatus.setText("Sincronizado con Time.is");
                syncStatus.setTextColor(getResources().getColor(R.color.ok));
                renderSourceDetails();
                renderClock();
            }

            @Override
            public void onFailure(String message, boolean receivedHttpResponse) {
                if (!started) return;
                syncInProgress = false;
                syncButton.setEnabled(true);
                syncStatus.setText("No se pudo sincronizar con Time.is: " + message);
                syncStatus.setTextColor(getResources().getColor(R.color.error));
                if (!receivedHttpResponse) runConnectivityDiagnosis();
            }
        });
    }

    private void runConnectivityDiagnosis() {
        networkStatus.setText("Diagnosticando acceso general a Internet…");
        ConnectivityAndInternetAccess.checkInternetAsyncDefault(
                this,
                new ConnectivityAndInternetAccess.InternetCallback() {
                    @Override
                    public void onResult(ConnectivityAndInternetAccess.InternetResult result) {
                        if (!started) return;
                        if (result != null && result.isReachable()) {
                            networkStatus.setText(
                                    "Internet general accesible (host: "
                                            + result.getReachedHost()
                                            + ", " + result.getElapsedMilliseconds() + " ms). "
                                            + "El fallo parece específico de Time.is/API/configuración.");
                        } else {
                            networkStatus.setText("El diagnóstico del gist no pudo verificar acceso general a Internet.");
                        }
                    }
                });
    }

    private void renderClock() {
        long systemNow = System.currentTimeMillis();
        long displayNow = clockModel.isCalibrated() ? clockModel.nowMs() : systemNow;

        SimpleDateFormat localTimeFormat = new SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault());
        SimpleDateFormat localDateFormat = new SimpleDateFormat("EEEE, d MMMM yyyy", Locale.getDefault());
        SimpleDateFormat utcFormat = new SimpleDateFormat("HH:mm:ss.SSS 'UTC'", Locale.US);
        utcFormat.setTimeZone(TimeZone.getTimeZone("UTC"));

        Date displayDate = new Date(displayNow);
        atomicTime.setText(localTimeFormat.format(displayDate));
        atomicDate.setText(localDateFormat.format(displayDate));
        utcTime.setText(utcFormat.format(displayDate));
        systemTime.setText("Hora del sistema: " + localTimeFormat.format(new Date(systemNow)));

        if (!clockModel.isCalibrated()) {
            systemError.setText("Sin calibrar");
            systemError.setTextColor(getResources().getColor(R.color.text_primary));
            return;
        }

        long signedErrorMs = systemNow - displayNow;
        String formatted = formatOffset(signedErrorMs);
        if (Math.abs(signedErrorMs) <= lastUncertaintyMs) {
            systemError.setText("Dentro de ±" + lastUncertaintyMs + " ms: " + formatted);
            systemError.setTextColor(getResources().getColor(R.color.ok));
        } else if (signedErrorMs > 0L) {
            systemError.setText("Adelantado " + formatted.substring(1));
            systemError.setTextColor(getResources().getColor(R.color.warning));
        } else {
            systemError.setText("Atrasado " + formatted.substring(1));
            systemError.setTextColor(getResources().getColor(R.color.warning));
        }
    }

    private void renderSourceDetails() {
        String lastSync = "—";
        if (lastSyncWallMs > 0L) {
            lastSync = new SimpleDateFormat("HH:mm:ss", Locale.getDefault())
                    .format(new Date(lastSyncWallMs));
        }
        sourceDetails.setText(
                "Fuente: Time.is API (" + lastParserSource + ")"
                        + "\nÚltima sincronización: " + lastSync
                        + "\nRTT: " + (lastRttMs > 0 ? lastRttMs + " ms" : "—")
                        + "\nIncertidumbre estimada: "
                        + (lastUncertaintyMs > 0 ? "±" + lastUncertaintyMs + " ms" : "—")
                        + "\nIntervalo: " + getSettings().intervalSeconds + " s");
    }

    private void renderNetworkState() {
        boolean connectedOrConnecting = ConnectivityAndInternetAccess.isConnectedOrConnecting(this);
        boolean connected = ConnectivityAndInternetAccess.isConnected(this);
        boolean wifi = ConnectivityAndInternetAccess.isConnectedWifi(this);
        boolean mobile = ConnectivityAndInternetAccess.isConnectedMobile(this);
        boolean vpn = ConnectivityAndInternetAccess.vpnActive(this);
        boolean airplane = ConnectivityAndInternetAccess.isAirplaneModeOn(this);
        boolean validated = ConnectivityAndInternetAccess.isInternetValidated(this);
        boolean captive = ConnectivityAndInternetAccess.isCaptivePortalDetected(this);

        StringBuilder text = new StringBuilder("Red: ");
        if (!connectedOrConnecting && !connected) {
            text.append(airplane ? "modo avión" : "sin conexión");
        } else if (captive) {
            text.append("portal cautivo");
        } else {
            text.append(wifi ? "Wi-Fi" : (mobile ? "móvil" : "conectada"));
            if (vpn) text.append(" + VPN");
            text.append(validated ? " · Internet validado" : " · sin validar");
        }
        networkStatus.setText(text.toString());
    }

    private boolean needsSyncSoon() {
        if (lastSuccessfulSyncElapsedMs == 0L) return true;
        return SystemClock.elapsedRealtime() - lastSuccessfulSyncElapsedMs
                >= getSettings().intervalSeconds * 1000L;
    }

    private boolean isApiConfigured() {
        return !preferences.getString(PREF_API_URL, "").trim().isEmpty();
    }

    private TimeIsClient.Settings getSettings() {
        String endpoint = preferences.getString(PREF_API_URL, "");
        String apiKey = preferences.getString(PREF_API_KEY, "");
        String authHeader = preferences.getString(PREF_AUTH_HEADER, "Authorization");
        String authPrefix = preferences.getString(PREF_AUTH_PREFIX, "Bearer ");
        long interval = preferences.getLong(PREF_INTERVAL_SECONDS, DEFAULT_INTERVAL_SECONDS);
        if (interval < MIN_INTERVAL_SECONDS) interval = MIN_INTERVAL_SECONDS;
        return new TimeIsClient.Settings(endpoint, apiKey, authHeader, authPrefix, interval);
    }

    private void showSettingsDialog() {
        TimeIsClient.Settings current = getSettings();
        LinearLayout container = new LinearLayout(this);
        container.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(20);
        container.setPadding(pad, dp(8), pad, 0);

        final EditText endpoint = field("URL HTTPS de la API oficial de Time.is", current.endpoint, false);
        final EditText apiKey = field("API key (si la API la requiere)", current.apiKey, true);
        final EditText header = field("Cabecera de autenticación", current.authHeader, false);
        final EditText prefix = field("Prefijo de autenticación", current.authPrefix, false);
        final EditText interval = field(
                "Intervalo de sincronización (segundos, mínimo 60)",
                String.valueOf(current.intervalSeconds), false);
        interval.setInputType(InputType.TYPE_CLASS_NUMBER);

        container.addView(endpoint);
        container.addView(apiKey);
        container.addView(header);
        container.addView(prefix);
        container.addView(interval);

        new AlertDialog.Builder(this)
                .setTitle("API oficial de Time.is")
                .setMessage("Time.is prohíbe el refresco automático de su web desde apps. Introduzca la URL y credenciales de la API oficial que le proporcione Time.is. Puede usar {key} en la URL si la clave debe ir como parámetro.")
                .setView(container)
                .setNegativeButton("Cancelar", null)
                .setNeutralButton("Abrir Time.is", (dialog, which) -> openTimeIs())
                .setPositiveButton("Guardar", (dialog, which) -> {
                    String url = endpoint.getText().toString().trim();
                    if (!url.isEmpty() && !url.startsWith("https://")) {
                        Toast.makeText(this, "La URL debe usar HTTPS", Toast.LENGTH_LONG).show();
                        return;
                    }
                    long seconds = DEFAULT_INTERVAL_SECONDS;
                    try {
                        seconds = Long.parseLong(interval.getText().toString().trim());
                    } catch (NumberFormatException ignored) {
                    }
                    seconds = Math.max(MIN_INTERVAL_SECONDS, Math.min(seconds, 3600L));
                    preferences.edit()
                            .putString(PREF_API_URL, url)
                            .putString(PREF_API_KEY, apiKey.getText().toString())
                            .putString(PREF_AUTH_HEADER, header.getText().toString().trim())
                            .putString(PREF_AUTH_PREFIX, prefix.getText().toString())
                            .putLong(PREF_INTERVAL_SECONDS, seconds)
                            .apply();
                    renderSourceDetails();
                    if (!url.isEmpty()) syncNow(true);
                })
                .show();
    }

    private EditText field(String hint, String value, boolean secret) {
        EditText editText = new EditText(this);
        editText.setHint(hint);
        editText.setText(value);
        editText.setSingleLine(true);
        editText.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        if (secret) {
            editText.setInputType(
                    InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        }
        return editText;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private void openTimeIs() {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("https://time.is/")));
        } catch (Exception e) {
            Toast.makeText(this, "No se pudo abrir el navegador", Toast.LENGTH_SHORT).show();
        }
    }

    private static String formatOffset(long ms) {
        String sign = ms >= 0 ? "+" : "−";
        long abs = Math.abs(ms);
        if (abs < 1000L) return sign + abs + " ms";
        return String.format(Locale.US, "%s%.3f s", sign, abs / 1000.0);
    }
}
