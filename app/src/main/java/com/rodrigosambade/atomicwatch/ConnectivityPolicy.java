package com.rodrigosambade.atomicwatch;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.Build;

import net.i2p.android.router.util.ConnectivityAndInternetAccess;

/**
 * Application-specific connectivity gate built on top of
 * {@link ConnectivityAndInternetAccess}.
 *
 * <p>This mirrors the current gist semantics: a VPN-only default route is not
 * considered usable when no non-VPN underlying network remains available.
 * The check is passive and performs no DNS/HTTP probes.</p>
 */
final class ConnectivityPolicy {
    private ConnectivityPolicy() {}

    static boolean isConnected(Context context) {
        if (!ConnectivityAndInternetAccess.isConnected(context)) {
            return false;
        }

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP
                || !ConnectivityAndInternetAccess.vpnActive(context)) {
            return true;
        }

        return hasUnderlyingNetwork(context);
    }

    static boolean hasUnderlyingNetwork(Context context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) {
            return ConnectivityAndInternetAccess.isConnected(context);
        }

        ConnectivityManager manager = (ConnectivityManager)
                context.getSystemService(Context.CONNECTIVITY_SERVICE);
        if (manager == null) {
            return false;
        }

        Network[] networks = manager.getAllNetworks();
        if (networks == null) {
            return false;
        }

        for (Network network : networks) {
            if (network == null) continue;
            NetworkCapabilities capabilities = manager.getNetworkCapabilities(network);
            if (capabilities == null
                    || capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) {
                continue;
            }
            if (ConnectivityAndInternetAccess.isConnected(context, network)) {
                return true;
            }
        }
        return false;
    }
}
