## ImpulseM Android client

ImpulseM Android client for the ImpulseM platform: messaging over the ImpulseM transport, with LiveKit calls.

## Build

Requirements: Android Studio, Android SDK 36, Android NDK 27.2.12479018, CMake 3.22.1, JDK 17.

1. Clone with submodules:
   ```bash
   git clone --recursive --shallow-submodules https://github.com/Impulse-m/ImpulseM.Client.Android.git ImpulseM
   ```
   If you forgot `--recursive`, run `git submodule update --init --recursive --depth=1` inside the checkout.
2. Build values are read from Gradle properties (`-P`, `gradle.properties`) or environment variables of the same name. See the table below.
3. Release builds need a private signing keystore kept outside the repository. Point `impulsem.signing.properties` in `local.properties` at an external properties file containing `storeFile`, `storePassword`, `keyAlias` and `keyPassword`.
4. Release builds also need a `google-services.json` for your own Firebase project in the folder of `TMessagesProj_App`.
5. Build a debug APK, which does not need signing:
   ```bash
   ./gradlew :TMessagesProj_App:assembleAfatDebug -PIMPULSEM_RPC_URL=https://rpc.example.org
   ```
   Release APK: `./gradlew :TMessagesProj_App:assembleAfatRelease` with the same values.

Tests of the transport layer run with `./gradlew :ImpulseTransport:test :ImpulseProxy:test`.

## Build variables

| Name | What it does | When it is empty |
| --- | --- | --- |
| `IMPULSEM_RPC_URL` | Base URL of the transport host, for example `https://rpc.example.org`. The app derives its websocket endpoints from it. | Release builds fail with a clear message. Debug builds start, make no connection and stay not connected. |
| `IMPULSEM_SITE_URL` | Base URL of the project website, for example `https://example.org`, without a trailing slash. Website links (terms, privacy, FAQ, downloads) are built from it. | Website links are hidden or shown as plain text. |
| `IMPULSEM_LINK_HOST` | Host of public share and deep links, for example `o.example.org`, without a scheme. | No host is recognised statically, and UI that needs it to build a link is hidden. The app's own URL scheme still works. |
| `IMPULSEM_WEB_URL` | URL of the web client, for example `https://web.example.org/k`. | "Open the web version" links are hidden. |

CI reads the same names from repository or organisation variables (`vars.IMPULSEM_*`) in `.github/workflows/android-release.yml`.

## Releases

GitHub Actions workflow `Android releases` builds signed APKs on every push to `master` (beta prerelease) and on version tags (release). It needs the repository secrets `ANDROID_KEYSTORE_BASE64` and `ANDROID_SIGNING_PROPERTIES_BASE64`, the variable `ANDROID_SIGNING_CERT_SHA256` that pins the expected signing certificate, and the `IMPULSEM_*` variables above.

## License

Licensed under the GNU General Public License, version 2. See [LICENSE](LICENSE).
