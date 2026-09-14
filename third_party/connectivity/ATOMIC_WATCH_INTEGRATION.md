# Atomic Watch integration

Source gist: https://gist.github.com/rodrigosambadesaa/729cca29a031fef4e2f15751863b655f

Pinned revision: `3b0497e976765653a7467e3bd7d6bff28b96bd7c` (2026-09-11)

Atomic Watch compiles the Java implementation directly from:

`app/src/main/java/net/i2p/android/router/util/ConnectivityAndInternetAccess.java`

That file is synchronized byte-for-byte from the pinned gist revision and retains its original package declaration.

`third_party/connectivity` intentionally does **not** duplicate `ConnectivityAndInternetAccess.java` or `ConnectivityAndInternetAccess.kt`. It only retains upstream documentation, licensing material, validation notes, and usage examples needed for attribution and reference.
