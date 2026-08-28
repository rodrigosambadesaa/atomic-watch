/*
 * SPDX-License-Identifier: MIT
 *
 * Based on Connectivity.java by Emil Davtyan (emil2k), later modified by str4d.
 * Further modernized for thread-safety, Android API compatibility,
 * extensible probe strategies, and captive-portal-aware reachability checks.
 */
@file:Suppress("DEPRECATION")

package net.i2p.android.router.util

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkInfo
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.telephony.TelephonyManager
import java.io.Closeable
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URL
import java.net.URLConnection
import java.security.GeneralSecurityException
import java.util.ArrayDeque
import java.util.concurrent.Callable
import java.util.concurrent.CancellationException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.ExecutionException
import java.util.concurrent.ExecutorCompletionService
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.ThreadFactory
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

class ConnectivityAndInternetAccess private constructor(
    private val instanceHosts: List<String>,
    private val instanceResolvers: List<String>,
    private val instanceDnsStrategy: DnsProbeStrategy,
    private val instanceHttpStrategy: HttpProbeStrategy
) {

    /**
     * Legacy constructor retained for compatibility. It also updates the global host list,
     * matching the historical mutable-global behavior. New code should prefer Builder.
     */
    constructor(hosts: ArrayList<String>) : this(
        normalizeHosts(hosts),
        DEFAULT_DNS_RESOLVERS,
        DefaultDnsProbe(),
        DefaultHttpProbe()
    ) {
        globalHosts = instanceHosts
    }

    fun interface DnsProbeStrategy {
        fun checkDns(resolver: String, network: Network?): Boolean
    }

    fun interface HttpProbeStrategy {
        fun checkHttp(address: String, network: Network?): Boolean
    }

    fun interface InternetCallback {
        fun onResult(result: InternetResult)
    }

    @ConsistentCopyVisibility
    data class InternetResult internal constructor(
        val reachable: Boolean,
        val reachedHost: String?,
        val attemptedHosts: List<String>,
        val elapsedMilliseconds: Long
    ) {
        fun isReachable(): Boolean = reachable
    }

    class Request internal constructor() {
        private val cancelled = AtomicBoolean(false)

        @Volatile
        private var future: Future<*>? = null

        fun cancel() {
            cancelled.set(true)
            future?.cancel(true)
        }

        fun isCancelled(): Boolean = cancelled.get()

        internal fun attach(task: Future<*>) {
            future = task
            if (cancelled.get()) {
                task.cancel(true)
            }
        }
    }

    fun interface NetworkStateCallback {
        fun onStateChanged(state: NetworkState)
    }

    /** Cheap passive state of the application's default network. */
    @ConsistentCopyVisibility
    data class NetworkState internal constructor(
        val connected: Boolean,
        val internetValidated: Boolean,
        val captivePortalDetected: Boolean,
        val observedAtElapsedRealtime: Long
    ) {
        internal fun sameConnectivityState(other: NetworkState): Boolean =
            connected == other.connected &&
                internetValidated == other.internetValidated &&
                captivePortalDetected == other.captivePortalDetected
    }

    /**
     * Lifecycle-friendly passive observer. API 24+ uses the application's default
     * NetworkCallback. API 16-23 uses a dynamically registered CONNECTIVITY_ACTION
     * receiver because registerDefaultNetworkCallback() did not exist before API 24.
     * The observer itself sends no DNS or HTTP traffic.
     */
    class NetworkObserver internal constructor(
        context: Context,
        private val callback: NetworkStateCallback
    ) : Closeable {
        private val applicationContext: Context =
            context.applicationContext ?: context
        private val connectivityManager = manager(applicationContext)
        private val closed = AtomicBoolean(false)

        @Volatile
        private var latestState: NetworkState = snapshotNetworkState(applicationContext)

        private var networkCallback: ConnectivityManager.NetworkCallback? = null
        private var legacyReceiver: BroadcastReceiver? = null

        init {
            deliver(latestState)
            register()
        }

        fun getLatestState(): NetworkState = latestState

        private fun register() {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                val observerCallback = object : ConnectivityManager.NetworkCallback() {
                    @Volatile
                    private var currentDefaultNetwork: Network? = null

                    override fun onAvailable(network: Network) {
                        // Android explicitly recommends waiting for onCapabilitiesChanged
                        // instead of synchronously querying capabilities from here.
                        currentDefaultNetwork = network
                    }

                    override fun onCapabilitiesChanged(
                        network: Network,
                        networkCapabilities: NetworkCapabilities
                    ) {
                        currentDefaultNetwork = network
                        publish(networkStateFromCapabilities(networkCapabilities))
                    }

                    override fun onLost(network: Network) {
                        if (network == currentDefaultNetwork) {
                            currentDefaultNetwork = null
                            publish(disconnectedNetworkState())
                        }
                    }
                }
                networkCallback = observerCallback
                connectivityManager.registerDefaultNetworkCallback(observerCallback)
                return
            }

            val receiver = object : BroadcastReceiver() {
                override fun onReceive(context: Context?, intent: Intent?) {
                    publish(snapshotNetworkState(applicationContext))
                }
            }
            legacyReceiver = receiver
            applicationContext.registerReceiver(
                receiver,
                IntentFilter(ConnectivityManager.CONNECTIVITY_ACTION)
            )
        }

        private fun publish(state: NetworkState) {
            if (closed.get()) return
            val previous = latestState
            latestState = state
            if (!state.sameConnectivityState(previous)) {
                deliver(state)
            }
        }

        private fun deliver(state: NetworkState) {
            mainHandler.post {
                if (!closed.get()) {
                    callback.onStateChanged(state)
                }
            }
        }

        override fun close() {
            if (!closed.compareAndSet(false, true)) return

            networkCallback?.let { registered ->
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                    try {
                        connectivityManager.unregisterNetworkCallback(registered)
                    } catch (_: IllegalArgumentException) {
                        // Already unregistered or registration failed during teardown.
                    }
                }
            }
            networkCallback = null

            legacyReceiver?.let { receiver ->
                try {
                    applicationContext.unregisterReceiver(receiver)
                } catch (_: IllegalArgumentException) {
                    // Receiver was already unregistered.
                }
            }
            legacyReceiver = null
        }
    }

    class Builder {
        private var hosts: List<String> = DEFAULT_HOSTS
        private var dnsResolvers: List<String> = DEFAULT_DNS_RESOLVERS
        private var dnsStrategy: DnsProbeStrategy = DefaultDnsProbe()
        private var httpStrategy: HttpProbeStrategy = DefaultHttpProbe()

        fun setHosts(hosts: List<String>) = apply {
            this.hosts = hosts
        }

        fun setDnsResolvers(resolvers: List<String>) = apply {
            this.dnsResolvers = resolvers
        }

        fun setDnsProbeStrategy(strategy: DnsProbeStrategy) = apply {
            this.dnsStrategy = strategy
        }

        fun setHttpProbeStrategy(strategy: HttpProbeStrategy) = apply {
            this.httpStrategy = strategy
        }

        fun build(): ConnectivityAndInternetAccess = ConnectivityAndInternetAccess(
            normalizeHosts(hosts),
            normalizeDnsResolvers(dnsResolvers),
            dnsStrategy,
            httpStrategy
        )
    }

    // Instance API: preferred for new code.

    fun checkInternetAsync(
        context: Context,
        callback: InternetCallback
    ): Request = executeAsync(
        context,
        instanceResolvers,
        instanceHosts,
        instanceDnsStrategy,
        instanceHttpStrategy,
        callback
    )

    fun checkInternetBlocking(context: Context): InternetResult = executeBlocking(
        context,
        instanceResolvers,
        instanceHosts,
        instanceDnsStrategy,
        instanceHttpStrategy
    )

    companion object {
        private const val MINIMUM_FAST_KBPS = 3_072
        private const val CONNECT_TIMEOUT_MS = 800
        private const val READ_TIMEOUT_MS = 800
        private const val DNS_TIMEOUT_MS = 650
        private const val EFFECTIVE_DNS_STAGE_TIMEOUT_MS = 350L
        private const val DNS_STAGE_TIMEOUT_MS = 700L
        private const val TOTAL_PROBE_TIMEOUT_MS = 2_000L
        private const val MAX_PARALLEL_PROBES = 9
        private const val DNS_PORT = 53
        private const val CONNECTION_ATTEMPT_TIMEOUT_MS = 30_000L
        private const val DNS_QUERY_NAME = "example.com"

        private val DEFAULT_DNS_RESOLVERS = listOf(
            "1.1.1.1",
            "8.8.8.8",
            "9.9.9.9",
            "208.67.222.222"
        )

        private val DEFAULT_HOSTS = listOf(
            "https://www.google.com/generate_204",
            "https://www.facebook.com/",
            "https://www.wolframalpha.com/",
            "https://www.apple.com/",
            "https://www.amazon.com/"
        )

        @Volatile
        private var globalHosts: List<String> = DEFAULT_HOSTS

        @Volatile
        private var globalResolvers: List<String> = DEFAULT_DNS_RESOLVERS

        @Volatile
        private var globalDnsStrategy: DnsProbeStrategy = DefaultDnsProbe()

        @Volatile
        private var globalHttpStrategy: HttpProbeStrategy = DefaultHttpProbe()

        private val connectionAttemptLock = Any()
        private val connectionAttemptQueue = ArrayDeque<ConnectionAttempt>()
        private val connectionAttempts = AtomicInteger(0)
        private val dnsTransactionId = AtomicInteger(System.nanoTime().toInt())
        private val probeThreadNumber = AtomicInteger(0)

        private val mainHandler = Handler(Looper.getMainLooper())
        private val tls12SocketFactory: SSLSocketFactory? = createTls12Factory()

        private val executor = Executors.newCachedThreadPool(object : ThreadFactory {
            private var number = 0

            @Synchronized
            override fun newThread(runnable: Runnable): Thread =
                Thread(runnable, "connectivity-check-${++number}").apply {
                    isDaemon = true
                }
        })

        @JvmStatic
        fun strictCaptivePortalBuilder(): Builder = Builder()
            .setDnsResolvers(emptyList())
            .setHosts(
                listOf("https://connectivitycheck.gstatic.com/generate_204")
            )
            .setHttpProbeStrategy(StrictHttpProbe())

        @JvmStatic
        fun isActiveNetworkConnected(context: Context): Boolean = isConnected(context)

        @JvmStatic
        fun isConnected(context: Context, network: Network?): Boolean {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) {
                return isConnected(context)
            }
            if (network == null) {
                return false
            }
            return manager(context).getNetworkCapabilities(network).isUsable()
        }

        @JvmStatic
        fun isConnecting(context: Context?): Boolean {
            context ?: throw IllegalArgumentException("context == null")

            if (isConnected(context)) {
                return false
            }

            if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
                if (legacyNetworks(manager(context)).any { info ->
                        info != null &&
                            info.isAvailable &&
                            info.state == NetworkInfo.State.CONNECTING
                    }
                ) {
                    return true
                }
            }

            return connectionAttempts.get() > 0
        }

        @JvmStatic
        fun beginConnectionAttempt(context: Context) {
            // Keep the context parameter for API compatibility and its non-null contract.
            context.applicationContext

            val attempt = ConnectionAttempt()
            synchronized(connectionAttemptLock) {
                connectionAttemptQueue.addLast(attempt)
                connectionAttempts.incrementAndGet()
            }

            mainHandler.postDelayed(
                { closeConnectionAttempt(attempt) },
                CONNECTION_ATTEMPT_TIMEOUT_MS
            )
        }

        @JvmStatic
        fun endConnectionAttempt() {
            synchronized(connectionAttemptLock) {
                while (connectionAttemptQueue.isNotEmpty()) {
                    val attempt = connectionAttemptQueue.removeFirst()
                    if (!attempt.closed) {
                        attempt.closed = true
                        connectionAttempts.updateAndGet { value ->
                            if (value > 0) value - 1 else 0
                        }
                        return
                    }
                }
            }
        }

        @JvmStatic
        fun isConnectedOrConnecting(context: Context?): Boolean {
            context ?: throw IllegalArgumentException("context == null")

            if (isConnected(context)) {
                return true
            }

            if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
                if (legacyNetworks(manager(context)).any { info ->
                        info != null &&
                            info.isAvailable &&
                            info.isConnectedOrConnecting
                    }
                ) {
                    return true
                }
            }

            return connectionAttempts.get() > 0
        }

        @JvmStatic
        fun isConnected(context: Context?): Boolean {
            context ?: throw IllegalArgumentException("context == null")
            val connectivityManager = manager(context)

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                val active = connectivityManager.activeNetwork
                if (active != null &&
                    connectivityManager.getNetworkCapabilities(active).isUsable()
                ) {
                    clearConnectionAttempts()
                    return true
                }
                return false
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                if (connectivityManager.allNetworks.any { network ->
                        connectivityManager.getNetworkCapabilities(network).isUsable()
                    }
                ) {
                    clearConnectionAttempts()
                    return true
                }
                return false
            }

            val connected = connectivityManager.activeNetworkInfo.isConnectedLegacy()
            if (connected) {
                clearConnectionAttempts()
            }
            return connected
        }

        /** Cheap point-in-time snapshot of the application's default network. */
        @JvmStatic
        fun snapshotNetworkState(context: Context): NetworkState {
            val connectivityManager = manager(context)

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                val active = connectivityManager.activeNetwork
                    ?: return disconnectedNetworkState()
                return networkStateFromCapabilities(
                    connectivityManager.getNetworkCapabilities(active)
                )
            }

            val connected = connectivityManager.activeNetworkInfo.isConnectedLegacy()
            return NetworkState(
                connected = connected,
                internetValidated = false,
                captivePortalDetected = false,
                observedAtElapsedRealtime = SystemClock.elapsedRealtime()
            )
        }

        /**
         * Starts passive default-network observation and immediately posts the current
         * state to the main thread. Close the returned observer when no longer needed.
         */
        @JvmStatic
        fun observeNetwork(
            context: Context,
            callback: NetworkStateCallback
        ): NetworkObserver = NetworkObserver(context, callback)

        /**
         * Returns whether Android most recently validated general Internet access on the
         * application's current default network. This is a system snapshot, not a fresh
         * reachability probe. API levels below 23 do not expose VALIDATED and return false.
         */
        @JvmStatic
        fun isInternetValidated(context: Context): Boolean {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
                return false
            }

            val connectivityManager = manager(context)
            val active = connectivityManager.activeNetwork ?: return false
            return isInternetValidated(context, active)
        }

        /** Network-specific variant of [isInternetValidated]. */
        @JvmStatic
        fun isInternetValidated(context: Context, network: Network?): Boolean {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M || network == null) {
                return false
            }

            val capabilities = manager(context).getNetworkCapabilities(network)
            return capabilities != null &&
                capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        }

        /**
         * Returns whether Android detected a captive portal on the application's current
         * default network the last time that network was probed. API levels below 23 do not
         * expose CAPTIVE_PORTAL and return false.
         */
        @JvmStatic
        fun isCaptivePortalDetected(context: Context): Boolean {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
                return false
            }

            val connectivityManager = manager(context)
            val active = connectivityManager.activeNetwork ?: return false
            return isCaptivePortalDetected(context, active)
        }

        /** Network-specific variant of [isCaptivePortalDetected]. */
        @JvmStatic
        fun isCaptivePortalDetected(context: Context, network: Network?): Boolean {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M || network == null) {
                return false
            }

            val capabilities = manager(context).getNetworkCapabilities(network)
            return capabilities?.hasCapability(
                NetworkCapabilities.NET_CAPABILITY_CAPTIVE_PORTAL
            ) == true
        }

        @JvmStatic
        fun isConnectedWifi(context: Context?): Boolean =
            hasTransport(context, NetworkCapabilities.TRANSPORT_WIFI)

        @JvmStatic
        fun isConnectedWifi(context: Context, network: Network?): Boolean =
            hasTransport(context, network, NetworkCapabilities.TRANSPORT_WIFI)

        @JvmStatic
        fun isConnectedWifiOverAirplaneMode(context: Context): Boolean =
            isAirplaneModeOn(context) && isConnectedWifi(context)

        @JvmStatic
        fun isConnectedWifiOverAirplaneMode(
            context: Context,
            network: Network?
        ): Boolean = isAirplaneModeOn(context) && isConnectedWifi(context, network)

        @JvmStatic
        fun isConnectedMobileTelephonyManager(context: Context): Boolean {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                return isConnectedMobile(context)
            }

            return try {
                val telephonyManager =
                    context.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
                telephonyManager?.dataState == TelephonyManager.DATA_CONNECTED
            } catch (_: SecurityException) {
                isConnectedMobile(context)
            }
        }

        @JvmStatic
        fun isConnectedMobile(context: Context, network: Network?): Boolean =
            hasTransport(context, network, NetworkCapabilities.TRANSPORT_CELLULAR)

        @JvmStatic
        fun isConnectedMobile(context: Context?): Boolean =
            hasTransport(context, NetworkCapabilities.TRANSPORT_CELLULAR)

        @JvmStatic
        fun isConnectedEthernet(context: Context?): Boolean =
            hasTransport(context, NetworkCapabilities.TRANSPORT_ETHERNET)

        @JvmStatic
        fun isConnectedEthernet(context: Context, network: Network?): Boolean =
            hasTransport(context, network, NetworkCapabilities.TRANSPORT_ETHERNET)

        @JvmStatic
        fun isConnectedFast(context: Context): Boolean {
            val connectivityManager = manager(context)

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                return connectivityManager.allNetworks.any { network ->
                    isFast(connectivityManager.getNetworkCapabilities(network))
                }
            }

            return legacyNetworks(connectivityManager).any { info ->
                info != null &&
                    info.isConnectedLegacy() &&
                    isConnectionFast(info.type, info.subtype)
            }
        }

        @JvmStatic
        fun isConnectedFast(context: Context, network: Network?): Boolean {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) {
                val info = manager(context).activeNetworkInfo
                return info != null &&
                    info.isConnectedLegacy() &&
                    isConnectionFast(info.type, info.subtype)
            }

            if (network == null) {
                return false
            }

            return isFast(manager(context).getNetworkCapabilities(network))
        }

        @JvmStatic
        fun isAirplaneModeOn(context: Context): Boolean =
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.JELLY_BEAN_MR1) {
                Settings.System.getInt(
                    context.contentResolver,
                    Settings.System.AIRPLANE_MODE_ON,
                    0
                ) != 0
            } else {
                Settings.Global.getInt(
                    context.contentResolver,
                    Settings.Global.AIRPLANE_MODE_ON,
                    0
                ) != 0
            }

        @JvmStatic
        fun vpnActive(context: Context): Boolean {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) {
                return false
            }

            val connectivityManager = manager(context)
            return connectivityManager.allNetworks.any { network ->
                connectivityManager.getNetworkCapabilities(network)
                    ?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true
            }
        }

        // Static compatibility helpers. Their names intentionally differ from
        // the instance methods to avoid duplicate JVM signatures.

        @JvmStatic
        fun isInternetReachable(context: Context?): Boolean {
            context ?: throw IllegalArgumentException("context == null")
            return executeBlocking(
                context,
                globalResolvers,
                globalHosts,
                globalDnsStrategy,
                globalHttpStrategy
            ).reachable
        }

        @JvmStatic
        fun isInternetReachable(
            context: Context?,
            hosts: ArrayList<String>
        ): Boolean {
            context ?: throw IllegalArgumentException("context == null")
            return executeBlocking(
                context,
                globalResolvers,
                normalizeHosts(hosts),
                globalDnsStrategy,
                globalHttpStrategy
            ).reachable
        }

        @JvmStatic
        fun checkInternetAsyncDefault(
            context: Context,
            callback: InternetCallback
        ): Request = executeAsync(
            context,
            globalResolvers,
            globalHosts,
            globalDnsStrategy,
            globalHttpStrategy,
            callback
        )

        @JvmStatic
        fun checkInternetAsyncDefault(
            context: Context,
            hosts: List<String>,
            callback: InternetCallback
        ): Request = executeAsync(
            context,
            globalResolvers,
            hosts,
            globalDnsStrategy,
            globalHttpStrategy,
            callback
        )

        @JvmStatic
        fun checkInternetAsyncDefault(
            context: Context,
            dnsResolvers: List<String>,
            hosts: List<String>,
            callback: InternetCallback
        ): Request = executeAsync(
            context,
            dnsResolvers,
            hosts,
            globalDnsStrategy,
            globalHttpStrategy,
            callback
        )

        @JvmStatic
        fun checkInternetBlockingDefault(
            context: Context
        ): InternetResult = executeBlocking(
            context,
            globalResolvers,
            globalHosts,
            globalDnsStrategy,
            globalHttpStrategy
        )

        @JvmStatic
        fun checkInternetBlockingDefault(
            context: Context,
            hosts: List<String>
        ): InternetResult = executeBlocking(
            context,
            globalResolvers,
            hosts,
            globalDnsStrategy,
            globalHttpStrategy
        )

        @JvmStatic
        fun checkInternetBlockingDefault(
            context: Context,
            dnsResolvers: List<String>,
            hosts: List<String>
        ): InternetResult = executeBlocking(
            context,
            dnsResolvers,
            hosts,
            globalDnsStrategy,
            globalHttpStrategy
        )

        @JvmStatic
        fun defaultHosts(): List<String> = DEFAULT_HOSTS

        @JvmStatic
        fun defaultDnsResolvers(): List<String> = DEFAULT_DNS_RESOLVERS

        private fun executeAsync(
            context: Context,
            dnsResolvers: List<String>,
            hosts: List<String>,
            dnsStrategy: DnsProbeStrategy,
            httpStrategy: HttpProbeStrategy,
            callback: InternetCallback
        ): Request {
            val appContext = context.applicationContext ?: context
            val normalizedResolvers = normalizeDnsResolvers(dnsResolvers)
            val normalizedHosts = normalizeHosts(hosts)
            val request = Request()

            request.attach(
                executor.submit {
                    val result = executeBlocking(
                        appContext,
                        normalizedResolvers,
                        normalizedHosts,
                        dnsStrategy,
                        httpStrategy
                    )

                    if (!request.isCancelled()) {
                        mainHandler.post {
                            if (!request.isCancelled()) {
                                callback.onResult(result)
                            }
                        }
                    }
                }
            )

            return request
        }

        private fun executeBlocking(
            context: Context,
            dnsResolvers: List<String>,
            hosts: List<String>,
            dnsStrategy: DnsProbeStrategy,
            httpStrategy: HttpProbeStrategy
        ): InternetResult {
            val started = SystemClock.elapsedRealtime()
            val deadline = started + TOTAL_PROBE_TIMEOUT_MS
            val attempted = CopyOnWriteArrayList<String>()
            val connectivityManager = manager(context)
            val network = selectProbeNetwork(connectivityManager)

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                if (network == null) {
                    return InternetResult(
                        false,
                        null,
                        attempted.toList(),
                        SystemClock.elapsedRealtime() - started
                    )
                }
            } else if (!isConnected(context)) {
                return InternetResult(
                    false,
                    null,
                    attempted.toList(),
                    SystemClock.elapsedRealtime() - started
                )
            }

            val probeExecutor = newProbeExecutor()
            try {
                val normalizedResolvers = normalizeDnsResolvers(dnsResolvers)
                var reached: String?

                /*
                 * Prefer the DNS configuration of the selected Android Network before
                 * contacting public resolvers directly. This respects the effective
                 * network path (including VPN and Private DNS on modern Android).
                 *
                 * An empty resolver list still disables the entire DNS stage, preserving
                 * the historical Builder semantics and strict captive-portal mode.
                 * A custom DnsProbeStrategy also owns the DNS stage completely, so the
                 * built-in effective-DNS preflight is only used with DefaultDnsProbe.
                 */
                if (normalizedResolvers.isNotEmpty() &&
                    dnsStrategy is DefaultDnsProbe
                ) {
                    reached = raceProbes(
                        listOf(
                            ProbeAttempt(effectiveDnsLabel()) {
                                checkEffectiveDns(network)
                            }
                        ),
                        attempted,
                        minOf(
                            deadline,
                            started + EFFECTIVE_DNS_STAGE_TIMEOUT_MS
                        ),
                        probeExecutor
                    )

                    if (reached != null) {
                        return InternetResult(
                            true,
                            reached,
                            attempted.toList(),
                            SystemClock.elapsedRealtime() - started
                        )
                    }
                }

                val dnsAttempts = normalizedResolvers.map { resolver ->
                    ProbeAttempt(dnsEndpointLabel(resolver)) {
                        dnsStrategy.checkDns(resolver, network)
                    }
                }

                reached = raceProbes(
                    dnsAttempts,
                    attempted,
                    minOf(deadline, started + DNS_STAGE_TIMEOUT_MS),
                    probeExecutor
                )

                if (reached != null) {
                    return InternetResult(
                        true,
                        reached,
                        attempted.toList(),
                        SystemClock.elapsedRealtime() - started
                    )
                }

                val hostAttempts = normalizeHosts(hosts).map { host ->
                    ProbeAttempt(host) {
                        httpStrategy.checkHttp(host, network)
                    }
                }

                reached = raceProbes(
                    hostAttempts,
                    attempted,
                    deadline,
                    probeExecutor
                )

                if (reached != null) {
                    return InternetResult(
                        true,
                        reached,
                        attempted.toList(),
                        SystemClock.elapsedRealtime() - started
                    )
                }

                return InternetResult(
                    false,
                    null,
                    attempted.toList(),
                    SystemClock.elapsedRealtime() - started
                )
            } finally {
                probeExecutor.shutdownNow()
            }
        }

        private fun raceProbes(
            probes: List<ProbeAttempt>,
            attempted: MutableList<String>,
            deadline: Long,
            probeExecutor: ExecutorService
        ): String? {
            if (probes.isEmpty() || Thread.currentThread().isInterrupted) {
                return null
            }

            val completion = ExecutorCompletionService<String?>(probeExecutor)
            val futures = probes.map { probe ->
                completion.submit(
                    Callable {
                        attempted += probe.label
                        if (Thread.currentThread().isInterrupted) {
                            null
                        } else if (probe.operation()) {
                            probe.label
                        } else {
                            null
                        }
                    }
                )
            }

            var remaining = futures.size
            try {
                while (remaining-- > 0) {
                    val wait = deadline - SystemClock.elapsedRealtime()
                    if (wait <= 0) {
                        return null
                    }

                    val completed = completion.poll(wait, TimeUnit.MILLISECONDS)
                        ?: return null

                    try {
                        completed.get()?.let { return it }
                    } catch (_: CancellationException) {
                        // A failed probe does not fail the whole stage.
                    } catch (_: ExecutionException) {
                        // A failed probe does not fail the whole stage.
                    }
                }
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            } finally {
                futures.forEach { it.cancel(true) }
            }

            return null
        }

        private fun checkEffectiveDns(network: Network?): Boolean =
            try {
                val addresses = if (
                    network != null &&
                    Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP
                ) {
                    network.getAllByName(DNS_QUERY_NAME)
                } else {
                    InetAddress.getAllByName(DNS_QUERY_NAME)
                }
                addresses.isNotEmpty()
            } catch (_: IOException) {
                false
            } catch (_: RuntimeException) {
                false
            }

        private fun effectiveDnsLabel(): String =
            "dns://system/$DNS_QUERY_NAME"

        class DefaultDnsProbe : DnsProbeStrategy {
            override fun checkDns(resolver: String, network: Network?): Boolean {
                val endpoint = parseDnsResolver(resolver)
                var socket: DatagramSocket? = null

                return try {
                    val transactionId = dnsTransactionId.incrementAndGet() and 0xffff
                    val query = createDnsQuery(transactionId)

                    socket = DatagramSocket()
                    if (network != null &&
                        Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP_MR1
                    ) {
                        network.bindSocket(socket)
                    }

                    socket.soTimeout = DNS_TIMEOUT_MS
                    socket.connect(InetSocketAddress(endpoint.host, endpoint.port))
                    socket.send(DatagramPacket(query, query.size))

                    val buffer = ByteArray(512)
                    val response = DatagramPacket(buffer, buffer.size)
                    socket.receive(response)

                    isValidDnsResponse(
                        transactionId,
                        response.data,
                        response.length
                    )
                } catch (_: IOException) {
                    false
                } catch (_: RuntimeException) {
                    false
                } finally {
                    socket?.close()
                }
            }

            private fun createDnsQuery(transactionId: Int): ByteArray {
                val labels = DNS_QUERY_NAME.split(".")
                var length = 12 + 1 + 4
                labels.forEach { label ->
                    length += 1 + label.length
                }

                val query = ByteArray(length)
                query[0] = (transactionId ushr 8).toByte()
                query[1] = transactionId.toByte()
                query[2] = 0x01
                query[5] = 0x01

                var offset = 12
                for (label in labels) {
                    query[offset++] = label.length.toByte()
                    for (character in label) {
                        query[offset++] = character.code.toByte()
                    }
                }

                query[offset++] = 0x00
                query[offset++] = 0x00
                query[offset++] = 0x01
                query[offset++] = 0x00
                query[offset] = 0x01
                return query
            }

            private fun isValidDnsResponse(
                transactionId: Int,
                response: ByteArray?,
                length: Int
            ): Boolean {
                if (response == null || length < 12) {
                    return false
                }

                val responseId =
                    ((response[0].toInt() and 0xff) shl 8) or
                        (response[1].toInt() and 0xff)
                val flags =
                    ((response[2].toInt() and 0xff) shl 8) or
                        (response[3].toInt() and 0xff)
                val questionCount =
                    ((response[4].toInt() and 0xff) shl 8) or
                        (response[5].toInt() and 0xff)
                val responseCode = flags and 0x000f

                return responseId == transactionId &&
                    (flags and 0x8000) != 0 &&
                    (flags and 0x7800) == 0 &&
                    questionCount > 0 &&
                    responseCode <= 5
            }
        }

        class DefaultHttpProbe : HttpProbeStrategy {
            override fun checkHttp(address: String, network: Network?): Boolean {
                var connection: HttpURLConnection? = null

                return try {
                    val url = URL(address)
                    val raw: URLConnection =
                        if (network != null &&
                            Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP
                        ) {
                            network.openConnection(url)
                        } else {
                            url.openConnection()
                        }

                    connection = raw as HttpURLConnection
                    configureTlsIfNecessary(connection)

                    connection.requestMethod = "GET"
                    connection.instanceFollowRedirects = false
                    connection.connectTimeout = CONNECT_TIMEOUT_MS
                    connection.readTimeout = READ_TIMEOUT_MS
                    connection.useCaches = false
                    connection.setRequestProperty("Accept", "*/*")
                    connection.setRequestProperty("Accept-Encoding", "identity")
                    connection.setRequestProperty("Connection", "close")
                    connection.setRequestProperty(
                        "User-Agent",
                        "ConnectivityAndInternetAccess/5"
                    )

                    connection.responseCode in 100..599
                } catch (_: IOException) {
                    false
                } catch (_: RuntimeException) {
                    false
                } finally {
                    connection?.disconnect()
                }
            }
        }

        class StrictHttpProbe : HttpProbeStrategy {
            override fun checkHttp(address: String, network: Network?): Boolean {
                var connection: HttpURLConnection? = null

                return try {
                    val url = URL(address)
                    val raw: URLConnection =
                        if (network != null &&
                            Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP
                        ) {
                            network.openConnection(url)
                        } else {
                            url.openConnection()
                        }

                    connection = raw as HttpURLConnection
                    configureTlsIfNecessary(connection)

                    connection.requestMethod = "GET"
                    connection.instanceFollowRedirects = false
                    connection.connectTimeout = CONNECT_TIMEOUT_MS
                    connection.readTimeout = READ_TIMEOUT_MS
                    connection.useCaches = false
                    connection.setRequestProperty("Accept", "*/*")
                    connection.setRequestProperty("Accept-Encoding", "identity")
                    connection.setRequestProperty("Connection", "close")
                    connection.setRequestProperty(
                        "User-Agent",
                        "ConnectivityAndInternetAccess/5"
                    )

                    val response = connection.responseCode
                    if (address.contains("generate_204")) {
                        response == HttpURLConnection.HTTP_NO_CONTENT
                    } else {
                        response == HttpURLConnection.HTTP_OK ||
                            response == HttpURLConnection.HTTP_NO_CONTENT
                    }
                } catch (_: IOException) {
                    false
                } catch (_: RuntimeException) {
                    false
                } finally {
                    connection?.disconnect()
                }
            }
        }

        private fun configureTlsIfNecessary(connection: HttpURLConnection) {
            if (connection is HttpsURLConnection &&
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN &&
                Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP
            ) {
                tls12SocketFactory?.let { factory ->
                    connection.sslSocketFactory = factory
                }
            }
        }

        private fun networkStateFromCapabilities(
            capabilities: NetworkCapabilities?
        ): NetworkState {
            val connected = capabilities.isUsable()
            val validated = connected &&
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.M &&
                capabilities?.hasCapability(
                    NetworkCapabilities.NET_CAPABILITY_VALIDATED
                ) == true
            val captivePortal = Build.VERSION.SDK_INT >= Build.VERSION_CODES.M &&
                capabilities?.hasCapability(
                    NetworkCapabilities.NET_CAPABILITY_CAPTIVE_PORTAL
                ) == true
            return NetworkState(
                connected = connected,
                internetValidated = validated,
                captivePortalDetected = captivePortal,
                observedAtElapsedRealtime = SystemClock.elapsedRealtime()
            )
        }

        private fun disconnectedNetworkState(): NetworkState = NetworkState(
            connected = false,
            internetValidated = false,
            captivePortalDetected = false,
            observedAtElapsedRealtime = SystemClock.elapsedRealtime()
        )

        private fun manager(context: Context): ConnectivityManager =
            context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
                ?: throw IllegalStateException("ConnectivityManager unavailable")

        private fun NetworkCapabilities?.isUsable(): Boolean {
            if (this == null ||
                !hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            ) {
                return false
            }

            return Build.VERSION.SDK_INT < Build.VERSION_CODES.P ||
                hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_SUSPENDED)
        }

        private fun hasTransport(context: Context?, transport: Int): Boolean {
            context ?: throw IllegalArgumentException("context == null")
            val connectivityManager = manager(context)

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                return connectivityManager.allNetworks.any { network ->
                    val capabilities =
                        connectivityManager.getNetworkCapabilities(network)
                    capabilities.isUsable() &&
                        capabilities?.hasTransport(transport) == true
                }
            }

            return legacyNetworks(connectivityManager).any { info ->
                info != null &&
                    info.isConnectedLegacy() &&
                    legacyTypeMatches(info.type, transport)
            }
        }

        private fun hasTransport(
            context: Context,
            network: Network?,
            transport: Int
        ): Boolean {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) {
                return hasTransport(context, transport)
            }

            if (network == null) {
                return false
            }

            val capabilities = manager(context).getNetworkCapabilities(network)
            return capabilities.isUsable() &&
                capabilities?.hasTransport(transport) == true
        }

        private fun legacyTypeMatches(type: Int, transport: Int): Boolean =
            when (transport) {
                NetworkCapabilities.TRANSPORT_WIFI ->
                    type == ConnectivityManager.TYPE_WIFI

                NetworkCapabilities.TRANSPORT_CELLULAR ->
                    type == ConnectivityManager.TYPE_MOBILE

                NetworkCapabilities.TRANSPORT_ETHERNET ->
                    type == ConnectivityManager.TYPE_ETHERNET

                else -> false
            }

        private fun isFast(capabilities: NetworkCapabilities?): Boolean =
            capabilities.isUsable() &&
                capabilities!!.linkDownstreamBandwidthKbps >= MINIMUM_FAST_KBPS &&
                capabilities.linkUpstreamBandwidthKbps >= MINIMUM_FAST_KBPS

        private fun legacyNetworks(
            connectivityManager: ConnectivityManager
        ): Array<NetworkInfo?> = connectivityManager.allNetworkInfo ?: emptyArray()

        private fun NetworkInfo?.isConnectedLegacy(): Boolean =
            this != null && isAvailable && isConnected

        private fun isConnectionFast(type: Int, subType: Int): Boolean {
            if (type == ConnectivityManager.TYPE_WIFI ||
                type == ConnectivityManager.TYPE_ETHERNET
            ) {
                return true
            }

            if (type != ConnectivityManager.TYPE_MOBILE) {
                return false
            }

            return when (subType) {
                TelephonyManager.NETWORK_TYPE_EVDO_0,
                TelephonyManager.NETWORK_TYPE_EVDO_A,
                TelephonyManager.NETWORK_TYPE_HSDPA,
                TelephonyManager.NETWORK_TYPE_HSPA,
                TelephonyManager.NETWORK_TYPE_HSUPA,
                TelephonyManager.NETWORK_TYPE_UMTS,
                TelephonyManager.NETWORK_TYPE_EHRPD,
                TelephonyManager.NETWORK_TYPE_EVDO_B,
                TelephonyManager.NETWORK_TYPE_HSPAP,
                TelephonyManager.NETWORK_TYPE_LTE -> true

                else -> false
            }
        }

        private fun selectProbeNetwork(
            connectivityManager: ConnectivityManager
        ): Network? {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) {
                return null
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                val active = connectivityManager.activeNetwork
                if (active != null &&
                    connectivityManager.getNetworkCapabilities(active).isUsable()
                ) {
                    return active
                }
                return null
            }

            return connectivityManager.allNetworks.firstOrNull { network ->
                connectivityManager.getNetworkCapabilities(network).isUsable()
            }
        }

        private fun normalizeHosts(hosts: List<String>): List<String> {
            val normalized = LinkedHashSet<String>()

            for (raw in hosts) {
                val trimmed = raw.trim()
                if (trimmed.isEmpty()) {
                    continue
                }

                val value =
                    if (trimmed.startsWith("https://", ignoreCase = true) ||
                        trimmed.startsWith("http://", ignoreCase = true)
                    ) {
                        trimmed
                    } else {
                        "https://$trimmed/"
                    }

                require(isValidURL(value)) {
                    "Invalid HTTP(S) URL: $value"
                }
                normalized += value
            }

            require(normalized.isNotEmpty()) {
                "hosts cannot be empty"
            }

            return normalized.toList()
        }

        private fun normalizeDnsResolvers(resolvers: List<String>): List<String> {
            val normalized = LinkedHashSet<String>()

            for (raw in resolvers) {
                val value = raw.trim()
                if (value.isNotEmpty()) {
                    parseDnsResolver(value)
                    normalized += value
                }
            }

            return normalized.toList()
        }

        private fun isValidURL(address: String?): Boolean {
            address ?: throw IllegalArgumentException("url == null")

            return try {
                val parsed = URL(address)
                parsed.toURI()
                (parsed.protocol.equals("http", ignoreCase = true) ||
                    parsed.protocol.equals("https", ignoreCase = true)) &&
                    parsed.host.isNotEmpty()
            } catch (_: Exception) {
                false
            }
        }

        private fun dnsEndpointLabel(resolver: String): String {
            val endpoint = parseDnsResolver(resolver)
            val host = if (':' in endpoint.host) {
                "[${endpoint.host}]"
            } else {
                endpoint.host
            }
            return "dns://$host:${endpoint.port}"
        }

        private fun parseDnsResolver(resolver: String): DnsResolver {
            val value = resolver.trim()
            require(value.isNotEmpty()) {
                "Invalid DNS resolver"
            }

            var host: String
            var port = DNS_PORT

            if (value.startsWith("[")) {
                val closingBracket = value.indexOf(']')
                require(closingBracket > 1) {
                    "Invalid DNS resolver"
                }

                host = value.substring(1, closingBracket).trim()
                val remainder = value.substring(closingBracket + 1).trim()
                if (remainder.isNotEmpty()) {
                    require(remainder.startsWith(":")) {
                        "Invalid DNS resolver"
                    }
                    port = parsePort(remainder.substring(1))
                }
            } else {
                val firstColon = value.indexOf(':')
                val lastColon = value.lastIndexOf(':')

                if (firstColon > 0 && firstColon == lastColon) {
                    host = value.substring(0, firstColon).trim()
                    port = parsePort(value.substring(firstColon + 1))
                } else {
                    host = value
                }
            }

            require(host.isNotEmpty() && port in 1..65_535) {
                "Invalid DNS resolver"
            }

            return DnsResolver(host, port)
        }

        private fun parsePort(rawPort: String): Int {
            val port = rawPort.trim().toIntOrNull()
                ?: throw IllegalArgumentException("Invalid DNS resolver port")
            require(port in 1..65_535) {
                "Invalid DNS resolver port"
            }
            return port
        }

        private fun newProbeExecutor(): ExecutorService =
            Executors.newFixedThreadPool(
                MAX_PARALLEL_PROBES,
                ThreadFactory { runnable ->
                    Thread(
                        runnable,
                        "connectivity-probe-${probeThreadNumber.incrementAndGet()}"
                    ).apply {
                        isDaemon = true
                    }
                }
            )

        private fun closeConnectionAttempt(attempt: ConnectionAttempt): Boolean {
            synchronized(connectionAttemptLock) {
                if (attempt.closed) {
                    return false
                }

                attempt.closed = true
                connectionAttemptQueue.remove(attempt)
                connectionAttempts.updateAndGet { value ->
                    if (value > 0) value - 1 else 0
                }
                return true
            }
        }

        private fun clearConnectionAttempts() {
            synchronized(connectionAttemptLock) {
                connectionAttemptQueue.forEach { attempt ->
                    attempt.closed = true
                }
                connectionAttemptQueue.clear()
                connectionAttempts.set(0)
            }
        }

        private data class ProbeAttempt(
            val label: String,
            val operation: () -> Boolean
        )

        private data class DnsResolver(
            val host: String,
            val port: Int
        )

        private class ConnectionAttempt {
            var closed: Boolean = false
        }

        private fun createTls12Factory(): SSLSocketFactory? {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.JELLY_BEAN ||
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP
            ) {
                return null
            }

            return try {
                val context = SSLContext.getInstance("TLSv1.2")
                context.init(null, null, null)
                Tls12SocketFactory(context.socketFactory)
            } catch (_: GeneralSecurityException) {
                null
            }
        }

        private class Tls12SocketFactory(
            private val delegate: SSLSocketFactory
        ) : SSLSocketFactory() {

            override fun getDefaultCipherSuites(): Array<String> =
                delegate.defaultCipherSuites

            override fun getSupportedCipherSuites(): Array<String> =
                delegate.supportedCipherSuites

            override fun createSocket(
                socket: Socket,
                host: String,
                port: Int,
                autoClose: Boolean
            ): Socket = enable(delegate.createSocket(socket, host, port, autoClose))

            override fun createSocket(host: String, port: Int): Socket =
                enable(delegate.createSocket(host, port))

            override fun createSocket(
                host: String,
                port: Int,
                localHost: InetAddress,
                localPort: Int
            ): Socket = enable(delegate.createSocket(host, port, localHost, localPort))

            override fun createSocket(host: InetAddress, port: Int): Socket =
                enable(delegate.createSocket(host, port))

            override fun createSocket(
                address: InetAddress,
                port: Int,
                localAddress: InetAddress,
                localPort: Int
            ): Socket = enable(
                delegate.createSocket(address, port, localAddress, localPort)
            )

            private fun enable(socket: Socket): Socket {
                if (socket is SSLSocket &&
                    socket.supportedProtocols.contains("TLSv1.2")
                ) {
                    socket.enabledProtocols = arrayOf("TLSv1.2")
                }
                return socket
            }
        }
    }
}
