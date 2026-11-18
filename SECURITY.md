# Java4Shell (CVE-2026-114938)

GitHub Actions builds this Android/Kotlin-based project using OpenJDK tooling.
The installed app runs on **ART**, not the vulnerable OpenJDK HotSpot JVM.

The repository's CI/build/release workflows temporarily set
`JAVA_TOOL_OPTIONS=-XX:-UseBASE64Intrinsics` to turn off the affected
hardware-accelerated Base64 decoder for Java build processes.

For **local builds and self-hosted CI**, install vendor-confirmed patched
OpenJDK 17/21/25 (whichever your project requires). If not yet available,
use the interim flag and restart Java and Gradle daemons. Verify with
`java -XX:+PrintFlagsFinal -version` (expect `UseBASE64Intrinsics = false`).
PowerShell: `$env:JAVA_TOOL_OPTIONS = '-XX:-UseBASE64Intrinsics'`.

This flag is a mitigation, not a substitute for patching the JDK.

Sources: https://www.cert.europa.eu/publications/security-advisories/2026-021/
and https://adoptium.net/news/2026/11/temurin-november-2026-cspu-java4shell/.
