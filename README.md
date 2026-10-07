## ImpulseM for Android

[Telegram](https://telegram.org) is a messaging app with a focus on speed and security. It’s superfast, simple and free.
This repository contains the ImpulseM Android client, based on Telegram for Android, with ImpulseM transport and LiveKit calls.

### Downloads and automated releases

Download signed APKs from [GitHub Releases](https://github.com/Impulse-m/ImpulseM.Client.Android/releases).
Every push to `master` publishes an ImpulseM Beta prerelease (`net.impulsem.messenger.beta`).
Pushing a version tag such as `v1.0.0` publishes an optimized release APK (`net.impulsem.messenger`).
The two editions install separately and use the same private signing certificate.
The **Android releases** workflow also supports manual runs from `master` or a version tag.

Each release includes `SHA256SUMS.txt`. CI assigns increasing Android version codes from its run number.
Repository secrets `ANDROID_KEYSTORE_BASE64` and `ANDROID_SIGNING_PROPERTIES_BASE64` hold the
keystore and signing properties; repository variable `ANDROID_SIGNING_CERT_SHA256` pins the expected certificate.

## Creating your Telegram Application

We welcome all developers to use our API and source code to create applications on our platform.
There are several things we require from **all developers** for the moment.

1. [**Obtain your own api_id**](https://core.telegram.org/api/obtaining_api_id) for your application.
2. Please **do not** use the name Telegram for your app — or make sure your users understand that it is unofficial.
3. Kindly **do not** use our standard logo (white paper plane in a blue circle) as your app's logo.
3. Please study our [**security guidelines**](https://core.telegram.org/mtproto/security_guidelines) and take good care of your users' data and privacy.
4. Please remember to publish **your** code too in order to comply with the licences.

### API, Protocol documentation

Telegram API manuals: https://core.telegram.org/api

MTproto protocol manuals: https://core.telegram.org/mtproto

### Compilation Guide

**Note**: In order to support [reproducible builds](https://core.telegram.org/reproducible-builds), this repo contains dummy release.keystore,  google-services.json and filled variables inside BuildVars.java. Before publishing your own APKs please make sure to replace all these files with your own.

You will require Android Studio 2025.1.4, Android NDK 27.2.12479018 and Android SDK 36.

1. Clone the Telegram source code with its submodules:
   ```bash
   git clone --recursive --shallow-submodules https://github.com/Impulse-m/ImpulseM.Client.Android.git ImpulseM
   ```
   In case you forgot the `--recursive` flag, change to the `ImpulseM` directory and run:
   ```bash
   git submodule init && git submodule update --init --recursive --depth=1
   ```
2. Create a private ImpulseM keystore outside the repository. The bundled Telegram keystore is not used by this fork.
3. Set `impulsem.signing.properties` in `local.properties` to an external properties file containing `storeFile`, `storePassword`, `keyAlias`, and `keyPassword`. Do not put passwords in the tracked `gradle.properties`.
4.  Go to https://console.firebase.google.com/, create two android apps with application IDs org.telegram.messenger and org.telegram.messenger.beta, turn on firebase messaging and download google-services.json, which should be copied to the same folder as TMessagesProj.
5. Open the project in the Studio (note that it should be opened, NOT imported).
6. Fill out values in TMessagesProj/src/main/java/org/telegram/messenger/BuildVars.java – there’s a link for each of the variables showing where and which data to obtain.
7. You are ready to compile Telegram.

### Localization

We moved all translations to https://translations.telegram.org/en/android/. Please use it.
