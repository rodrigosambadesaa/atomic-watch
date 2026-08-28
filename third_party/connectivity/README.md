# ConnectivityAndInternetAccess

Modernized fork of `str4d/22cac7a3f70bc227cdca`, itself derived from Emil Davtyan's original `emil2k/5130324` `Connectivity.java`.

This fork provides two equivalent implementations:

* `ConnectivityAndInternetAccess.java`
* `ConnectivityAndInternetAccess.kt`

It also includes one lifecycle-aware Activity example for each language:

* `ConnectivityUsageExample.java`
* `ConnectivityUsageExampleKotlin.kt`

Copy the implementation and example for the language used by your app. Do not include both main implementations in the same source set because they intentionally use the same package and class name.

The examples start the built-in passive `NetworkObserver` in `onStart()`, close it in `onStop()`, and only run the active DNS/HTTPS diagnostic when the user explicitly taps the status view. This mirrors the recommended application flow: observe network state cheaply, make the real backend request, and use active probes only when broader diagnosis is useful.

## Features and API compatibility

Both implementations preserve operations for general connectivity, Wi-Fi, mobile data, Ethernet, estimated connection speed, airplane mode, VPN, connection attempts, and actual Internet reachability while selecting APIs appropriate for the Android version:

* **API 16–20:** legacy `NetworkInfo` APIs.
* **API 21+:** `Network` and `NetworkCapabilities` where available.
* **API 24+:** the built-in passive observer uses `registerDefaultNetworkCallback(...)` to follow the application's actual default network.
* **API 16–23:** the observer falls back to a dynamically registered `CONNECTIVITY_ACTION` receiver because Android did not add `registerDefaultNetworkCallback(...)` until API 24. It still performs no polling and no DNS/HTTP traffic.
* **API 16–28:** the legacy `CONNECTING` state can still be read from `NetworkInfo`.
* **API 29+:** where the legacy connecting state is no longer relied upon, an application-initiated attempt can be tracked explicitly with `beginConnectionAttempt(context)` and `endConnectionAttempt()`.
* Outstanding application connection attempts expire automatically after 30 seconds. Each timeout belongs to the specific attempt that created it, so an old timeout cannot decrement a newer attempt.

`isConnected(...)` means that Android exposes a usable network with `NET_CAPABILITY_INTERNET`; it does **not** by itself prove that arbitrary Internet destinations are reachable. Use `checkInternetAsync(...)`, `checkInternetBlocking(...)`, or `isInternetReachable(...)` when real reachability is required.

### Android validation and captive-portal signals

On API 23+, `isInternetValidated(...)` reports whether Android most recently validated general Internet access on the application's effective default `Network`, while `isCaptivePortalDetected(...)` reports whether Android detected a captive portal the last time it probed that network. These are system snapshots, not fresh probes. They intentionally do not replace the active reachability APIs above.

This separation is deliberate:

* `isConnected(...)`: Android exposes a usable Internet-capable network.
* `isInternetValidated(...)`: Android most recently validated that network.
* `isCaptivePortalDetected(...)`: Android most recently detected a captive portal.
* `checkInternetAsync(...)` / `checkInternetBlocking(...)`: perform a fresh active reachability check with a bounded global deadline.

On API 16-22, Android does not expose `NET_CAPABILITY_VALIDATED` or `NET_CAPABILITY_CAPTIVE_PORTAL`, so the two corresponding helpers return `false`.

The class now also implements passive observation directly:

* `snapshotNetworkState(context)` returns a cheap point-in-time `NetworkState` containing `connected`, `internetValidated`, and `captivePortalDetected`.
* `observeNetwork(context, callback)` immediately posts the current state to the main thread and then emits changes.
* API 24+ uses the application's **default** `ConnectivityManager.NetworkCallback` and consumes the `NetworkCapabilities` delivered to `onCapabilitiesChanged(...)` directly.
* API 16-23 uses a dynamically registered legacy connectivity receiver. A regular `registerNetworkCallback(NetworkRequest, ...)` is deliberately not substituted on API 21-23 because it can report several matching networks at once and therefore is not equivalent to observing the application's default network.
* `NetworkObserver.close()` unregisters the callback/receiver. Call it from the owning lifecycle; do not leak observers.

The passive observer itself generates no DNS or HTTP traffic.

## How reachability is checked

Internet reachability is checked asynchronously or synchronously with a short-lived bounded probe executor dedicated to each reachability check:

1. **Effective/system DNS first (default strategy only).** Resolve `example.com` using the DNS configuration of the selected Android `Network`. On API 21+ this uses `Network.getAllByName(...)`, so resolution follows the app's effective network path and is compatible with VPN/Private-DNS routing. This preflight has a short 350 ms budget.
2. **Direct public DNS fallback.** If effective DNS does not succeed in that short window, send a real DNS `A` query over UDP to Cloudflare (`1.1.1.1`), Google (`8.8.8.8`), Quad9 (`9.9.9.9`), and OpenDNS (`208.67.222.222`) in parallel. The complete DNS phase remains bounded to 700 ms from the beginning of the check.
3. **HTTPS/HTTP reachability.** Only when the DNS phase does not produce a result, contact Google, Facebook, Wolfram Alpha, Apple, and Amazon in parallel with the remaining global time.

A structurally valid response from a direct public DNS resolver, including a negative DNS result such as NXDOMAIN, proves that that resolver was reached. A successful effective-DNS lookup shows that the DNS path actually configured for the selected network can resolve the query. The first successful DNS or HTTP(S) probe wins; the remaining futures in that stage are cancelled.

The total probe uses a monotonic deadline of about two seconds rather than accumulating endpoint timeouts. `attemptedHosts` contains probes that actually started executing, rather than every probe that was merely submitted. Effective DNS is reported as `dns://system/example.com`.

Setting an empty DNS-resolver list still disables the entire DNS phase (as used by strict captive-portal mode). Supplying a custom `DnsProbeStrategy` also takes full ownership of the DNS phase, so the built-in effective-DNS preflight is skipped.

On Android API 21 and newer, effective DNS resolution and HTTP(S) probes use the selected Android `Network`. On API 22 and newer, direct UDP DNS sockets are also bound to that selected `Network`.

## Recommended application pattern

For ordinary application logic, do not treat active probes as a mandatory gate before every API call. A practical default is:

1. Start one lifecycle-appropriate `NetworkObserver` (or one application-level observer if many screens need the same cached state).
2. Use its `NetworkState`, or `snapshotNetworkState(...)` / the individual snapshot helpers, as cheap passive signals.
3. Let the application's real backend request answer the service-specific question: **can this app reach the service it actually needs?**
4. If that request fails without an HTTP response and broader diagnosis is useful, run `checkInternetAsync(...)` / `checkInternetBlocking(...)` explicitly.

```text
passive default-network state
            ↓
    real backend request
            ↓
active generic diagnosis only when useful
```

This distinction avoids unnecessary probe traffic while keeping the active diagnostic API available for cases where Android's cached capability state or one backend endpoint is not enough.

## Basic usage

### Passive observation: preferred normal path

Kotlin:

```kotlin
private var networkObserver: ConnectivityAndInternetAccess.NetworkObserver? = null

override fun onStart() {
    super.onStart()
    networkObserver = ConnectivityAndInternetAccess.observeNetwork(this) { state ->
        println("connected=${state.connected}")
        println("validated=${state.internetValidated}")
        println("captivePortal=${state.captivePortalDetected}")
    }
}

override fun onStop() {
    networkObserver?.close()
    networkObserver = null
    super.onStop()
}
```

Java:

```java
private ConnectivityAndInternetAccess.NetworkObserver networkObserver;

@Override
protected void onStart() {
    super.onStart();
    networkObserver = ConnectivityAndInternetAccess.observeNetwork(
            this,
            state -> System.out.println(
                    "connected=" + state.isConnected()
                    + ", validated=" + state.isInternetValidated()
                    + ", captivePortal=" + state.isCaptivePortalDetected()));
}

@Override
protected void onStop() {
    if (networkObserver != null) {
        networkObserver.close();
        networkObserver = null;
    }
    super.onStop();
}
```

### Explicit active diagnostic

Use this when a broader Internet diagnosis is actually useful, rather than before every backend request:

```kotlin
val connectivity = ConnectivityAndInternetAccess.Builder().build()
val request = connectivity.checkInternetAsync(context) { result ->
    println("reachable=${result.reachable}, via=${result.reachedHost}")
}

// Later, if the owning lifecycle ends before the callback:
// request.cancel()
```

The equivalent Java call is:

```java
ConnectivityAndInternetAccess connectivity =
        new ConnectivityAndInternetAccess.Builder().build();

ConnectivityAndInternetAccess.Request request =
        connectivity.checkInternetAsync(context, result ->
                System.out.println(
                        "reachable=" + result.isReachable()
                        + ", via=" + result.getReachedHost()));

// Later, if the owning lifecycle ends before the callback:
// request.cancel();
```

### Static compatibility helpers

Static helpers are still available, but their names intentionally differ from the instance methods so Java and Kotlin/JVM do not generate duplicate signatures:

```kotlin
val request = ConnectivityAndInternetAccess.checkInternetAsyncDefault(context) { result ->
    println(result.isReachable)
}
```

Blocking static calls use `checkInternetBlockingDefault(...)`.

The legacy boolean helpers remain available as `isInternetReachable(...)`.

## Required permissions in AndroidManifest.xml

```xml
<uses-permission android:name="android.permission.INTERNET" />
<uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" />
```

`observeNetwork(...)` requires `ACCESS_NETWORK_STATE`. Active DNS/HTTPS diagnostics additionally require `INTERNET`. Android limits the number of outstanding network callbacks per UID, so always close observers that are no longer needed; a single application-level observer is usually preferable when many screens consume the same state.

## Advanced usage: Builder and Strategy patterns

The modernized architecture allows isolated instances with custom hosts, DNS resolvers, and probe strategies without mutating the global compatibility configuration.

Custom DNS and HTTP strategies can be used for alternative networking logic such as DNS over HTTPS or an HTTP client such as OkHttp.

### Kotlin example

```kotlin
val customConnectivity = ConnectivityAndInternetAccess.Builder()
    .setHosts(listOf("https://my-custom-api.com/health"))
    .setHttpProbeStrategy { address, _ ->
        myCustomPingLogic(address)
    }
    .build()

val request = customConnectivity.checkInternetAsync(context) { result ->
    // Handle result.
}
```

### Java example

```java
ConnectivityAndInternetAccess customConnectivity =
        new ConnectivityAndInternetAccess.Builder()
                .setDnsResolvers(Arrays.asList("9.9.9.9", "1.1.1.1"))
                .setDnsProbeStrategy((resolver, network) -> {
                    // Implement custom DNS-over-HTTPS logic here.
                    return checkDoH(resolver);
                })
                .build();

customConnectivity.checkInternetAsync(context, result -> {
    // Handle result.
});
```

## Captive Portal strict mode

The default probe answers a relaxed question: **can the app reach a real DNS or HTTP(S) endpoint?** A captive portal can sometimes satisfy that relaxed definition because it may spoof DNS or return an HTTP login/redirect response.

If the app requires transparent Internet access instead, use the preconfigured strict builder:

```kotlin
val strictConnectivity =
    ConnectivityAndInternetAccess.strictCaptivePortalBuilder().build()

strictConnectivity.checkInternetAsync(context) { result ->
    if (result.isReachable) {
        println("Full transparent Internet access verified")
    } else {
        println("Offline or behind a captive portal")
    }
}
```

Java:

```java
ConnectivityAndInternetAccess strictConnectivity =
        ConnectivityAndInternetAccess.strictCaptivePortalBuilder().build();

strictConnectivity.checkInternetAsync(context, result -> {
    if (result.isReachable()) {
        System.out.println("Full transparent Internet access verified");
    } else {
        System.out.println("Offline or behind a captive portal");
    }
});
```

Strict mode disables the DNS stage and probes Google's `generate_204` connectivity endpoint without following redirects. For a `generate_204` endpoint, only an actual HTTP `204 No Content` response is accepted.

## Threading and cancellation

* `NetworkObserver` receives OS connectivity events without polling and posts `NetworkStateCallback` delivery to the main thread.
* `NetworkObserver.close()` unregisters the underlying callback/receiver and is idempotent.
* `checkInternetAsync(...)` runs work off the caller thread.
* Its callback is posted to the main thread.
* `Request.cancel()` suppresses future callback delivery and interrupts the outer task.
* Individual network operations also have short socket/connect/read timeouts, because interrupting a Java networking call does not necessarily close an already-blocked socket immediately.
* Each reachability check owns a bounded probe executor, preventing unrelated concurrent checks from consuming one another's two-second deadline while waiting in a shared probe queue.

## License and attribution

MIT. The original work is by Emil Davtyan (`emil2k`), with subsequent modifications by `str4d`, followed by the modernized Java/Kotlin work in this fork. See `LICENSE` for the retained notices and license text.

Both variants are written for Android projects with `minSdk 16` and Java 8-compatible bytecode. As with any networking helper, applications should test the exact version they ship on their own target API/device matrix, especially captive portals, VPNs, dual-network devices, and OEM-specific networking behavior.
