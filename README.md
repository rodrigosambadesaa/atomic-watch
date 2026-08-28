# Atomic Watch

Android atomic-clock style application that calibrates against the **official Time.is API**, continuously renders the calibrated time from a monotonic anchor, and shows the signed error of the Android system clock.

## Important: Time.is API, not scraping

Time.is states in its Terms of Use that automatic refresh and usage from scripts/apps is forbidden and asks app developers to contact them about their API. Therefore this project **does not scrape or periodically reload https://time.is/**.

Configure the official API URL and credentials supplied by Time.is from the app's **Configurar Time.is** dialog. The client supports a normal authentication header and also a `{key}` placeholder in the URL for APIs that require a query/path key.

## Clock model

* A valid Time.is response establishes an epoch anchor at `SystemClock.elapsedRealtime()`.
* The visible clock advances locally from that monotonic anchor, so it does not need a server request every second and is not affected if Android later corrects its wall clock.
* System-clock error is `System.currentTimeMillis() - calibratedTime`: positive means Android is ahead, negative means it is behind.
* Round-trip time (RTT) is measured with the monotonic clock and half of RTT is used as a one-way transport estimate.
* The UI exposes RTT and a conservative uncertainty bound; values within that bound are not presented as a definite system-clock error.
* Default resynchronization interval is 300 seconds, configurable from 60 to 3600 seconds while the app is in the foreground.

## Connectivity

The complete requested connectivity gist is vendored at `third_party/connectivity/`, pinned to revision `ce7bd07ed17d10d48785a0c21527c8100ea6da7e`. `ConnectivityAndInternetAccess.java` is copied byte-for-byte into the Android source set under its original package `net.i2p.android.router.util`.

The app uses the gist's passive `NetworkObserver` for normal state changes. It makes the real Time.is API request first. Only when that request fails without receiving an HTTP response does it run the gist's generic active DNS/HTTP reachability diagnostic.

## Android

* Java 8 source/bytecode compatibility
* minSdk 16
* target/compile SDK 35
* INTERNET + ACCESS_NETWORK_STATE only
* No permission or attempt to modify Android system time

## Build

```bash
./gradlew assembleDebug assembleRelease
```

The `v1.0.0` APK is signed with the debug signing configuration for direct device testing, not for Play Store production distribution.
