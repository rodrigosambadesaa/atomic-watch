# Validation — built-in passive Android network observer

## What was found

The previous final Java/Kotlin implementations exposed point-in-time helpers such as
`isConnected(...)`, `isInternetValidated(...)`, and `isCaptivePortalDetected(...)`,
but did not implement `ConnectivityManager.NetworkCallback`. The README/examples only
recommended that applications add their own observer.

## What was added

- `NetworkState` with:
  - connected;
  - Internet validated (API 23+);
  - captive portal detected (API 23+);
  - monotonic observation timestamp.
- `snapshotNetworkState(context)`.
- `observeNetwork(context, callback)`.
- `NetworkObserver.close()` for lifecycle-safe unregistration.
- API 24+: `registerDefaultNetworkCallback(...)`.
- API 16-23: dynamically registered `CONNECTIVITY_ACTION` fallback.
- Main-thread observer callback delivery.
- No DNS/HTTP work inside passive observation.
- Examples now observe passively by default and run an active diagnostic only on an
  explicit user action.

The API 21-23 fallback intentionally does **not** use
`registerNetworkCallback(NetworkRequest, ...)`: that API can report multiple matching
networks and is not equivalent to observing the application's default network.
`registerDefaultNetworkCallback(...)` was added in API 24.

## Validation performed here

- Java core + Java example: `javac --release 8 -Xlint:all,-options` against Android API
  stubs: PASS, no Java source warnings/errors.
- Java API 24 observer simulation:
  - initial state delivery;
  - `onCapabilitiesChanged` state update;
  - duplicate-state suppression;
  - captive-portal update;
  - idempotent `close()` and callback unregister: PASS.
- Java API 23 fallback simulation:
  - initial state;
  - dynamic receiver update;
  - receiver unregister: PASS.
- Kotlin core + Kotlin example: `kotlinc 1.9`, JVM target 1.8, against the same stubs:
  PASS after removing `@ConsistentCopyVisibility` only from a temporary validation copy.
  The distributable source retains the annotation already used by the previous final
  Kotlin revision.

## Physical-device validation

Validated successfully on 2026-08-18 using a real Samsung Galaxy S25 Ultra:

- Model: `SM-S938B` (`samsung/pa3q`), Android 16, API 36.
- Security patch level: `2026-07-05`.
- A temporary signed APK containing `ConnectivityAndInternetAccess.java` and
  `ConnectivityUsageExample.java` installed successfully with package
  `net.i2p.android.router.util.device.test`.
- The launcher activity started successfully and was confirmed as the resumed
  activity by Android; no application crash was reported.
- Passive observer result: `network available`; Android reported `Internet
  validated by Android`.
- After tapping the status view, the active diagnostic succeeded with
  `Diagnostic reached dns://system/example.com`.

This confirms the Java implementation and its lifecycle-aware example on a physical
API 36 device. The Kotlin source and the legacy API 16–35 compatibility paths still
require coverage on their respective device or emulator matrix.

## Remaining validation

Run the existing Android test matrix, especially API 16, 23/24 and 29, and exercise
Wi-Fi/mobile handover, VPN default network changes, captive portal state, and observer
start/stop cycles.
