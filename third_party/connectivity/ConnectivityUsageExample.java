package net.i2p.android.router.util.example;

import android.app.Activity;
import android.os.Build;
import android.os.Bundle;
import android.widget.TextView;

import net.i2p.android.router.util.ConnectivityAndInternetAccess;

/**
 * Minimal lifecycle-aware Activity example.
 *
 * <p>Add INTERNET and ACCESS_NETWORK_STATE to AndroidManifest.xml before using
 * this Activity.
 *
 * <p>The normal path is passive and event-driven. Tap the TextView to run an
 * explicit active Internet diagnostic. A real application should normally make
 * its backend request directly and reserve that diagnostic for network-like
 * failures or troubleshooting.
 */
public final class ConnectivityUsageExample extends Activity {
    private TextView status;
    private ConnectivityAndInternetAccess.NetworkObserver networkObserver;
    private ConnectivityAndInternetAccess.Request internetRequest;
    private ConnectivityAndInternetAccess connectivity;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        status = new TextView(this);
        int padding = (int) (24 * getResources().getDisplayMetrics().density);
        status.setPadding(padding, padding, padding, padding);
        status.setOnClickListener(view -> runActiveDiagnostic());
        setContentView(status);

        connectivity = new ConnectivityAndInternetAccess.Builder().build();
    }

    @Override
    protected void onStart() {
        super.onStart();
        networkObserver = ConnectivityAndInternetAccess.observeNetwork(
                this,
                state -> status.setText(describeState(state)
                        + "\n\nTap to run an explicit active diagnostic."));
    }

    private String describeState(ConnectivityAndInternetAccess.NetworkState state) {
        String validation;
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            validation = "validation unavailable before API 23";
        } else if (state.isCaptivePortalDetected()) {
            validation = "captive portal detected";
        } else if (state.isInternetValidated()) {
            validation = "Internet validated by Android";
        } else {
            validation = "not currently validated by Android";
        }
        return "Passive state: "
                + (state.isConnected() ? "network available" : "no usable network")
                + "\nAndroid: " + validation;
    }

    private void runActiveDiagnostic() {
        if (internetRequest != null) {
            internetRequest.cancel();
        }
        status.setText("Running explicit Internet diagnostic…");
        internetRequest = connectivity.checkInternetAsync(
                this,
                result -> {
                    internetRequest = null;
                    String diagnostic = result.isReachable()
                            ? "Diagnostic reached " + result.getReachedHost()
                            : "Diagnostic could not establish Internet reachability";
                    ConnectivityAndInternetAccess.NetworkState passive =
                            ConnectivityAndInternetAccess.snapshotNetworkState(this);
                    status.setText(diagnostic + "\n\n" + describeState(passive));
                });
    }

    @Override
    protected void onStop() {
        if (networkObserver != null) {
            networkObserver.close();
            networkObserver = null;
        }
        if (internetRequest != null) {
            internetRequest.cancel();
            internetRequest = null;
        }
        super.onStop();
    }
}
