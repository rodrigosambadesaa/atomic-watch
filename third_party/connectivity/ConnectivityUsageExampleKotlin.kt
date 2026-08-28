package net.i2p.android.router.util.example

import android.app.Activity
import android.os.Build
import android.os.Bundle
import android.widget.TextView
import net.i2p.android.router.util.ConnectivityAndInternetAccess

/**
 * Minimal lifecycle-aware Activity example.
 *
 * The normal path is passive and event-driven. Tap the TextView to run an
 * explicit active Internet diagnostic. A real application should normally make
 * its backend request directly and reserve that diagnostic for network-like
 * failures or troubleshooting.
 */
class ConnectivityUsageExampleKotlin : Activity() {
    private lateinit var status: TextView
    private var networkObserver: ConnectivityAndInternetAccess.NetworkObserver? = null
    private var internetRequest: ConnectivityAndInternetAccess.Request? = null

    private val connectivity by lazy {
        ConnectivityAndInternetAccess.Builder().build()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val padding = (24 * resources.displayMetrics.density).toInt()
        status = TextView(this).apply {
            setPadding(padding, padding, padding, padding)
            setOnClickListener { runActiveDiagnostic() }
        }
        setContentView(status)
    }

    override fun onStart() {
        super.onStart()
        networkObserver = ConnectivityAndInternetAccess.observeNetwork(this) { state ->
            status.setText("${describeState(state)}\n\nTap to run an explicit active diagnostic.")
        }
    }

    private fun describeState(state: ConnectivityAndInternetAccess.NetworkState): String {
        val validation = when {
            Build.VERSION.SDK_INT < Build.VERSION_CODES.M ->
                "validation unavailable before API 23"
            state.captivePortalDetected -> "captive portal detected"
            state.internetValidated -> "Internet validated by Android"
            else -> "not currently validated by Android"
        }
        return "Passive state: ${if (state.connected) "network available" else "no usable network"}" +
            "\nAndroid: $validation"
    }

    private fun runActiveDiagnostic() {
        internetRequest?.cancel()
        status.setText("Running explicit Internet diagnostic…")
        internetRequest = connectivity.checkInternetAsync(this) { result ->
            internetRequest = null
            val diagnostic = if (result.reachable) {
                "Diagnostic reached ${result.reachedHost}"
            } else {
                "Diagnostic could not establish Internet reachability"
            }
            val passive = ConnectivityAndInternetAccess.snapshotNetworkState(this)
            status.setText("$diagnostic\n\n${describeState(passive)}")
        }
    }

    override fun onStop() {
        networkObserver?.close()
        networkObserver = null
        internetRequest?.cancel()
        internetRequest = null
        super.onStop()
    }
}
