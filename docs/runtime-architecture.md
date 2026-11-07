# Runtime Architecture and Android Service Notes

## Host and guest separation

There are two distinct execution environments:

1. **Android host:** PRoot and its external loader are built with the Android NDK for `arm64-v8a`. They are packaged as executable `.so` files and launched from `ApplicationInfo.nativeLibraryDir`.
2. **Linux guest:** Debian Trixie ARM64 with glibc is installed under the app-private home. Box64 is compiled inside this guest with a Linux ARM64 toolchain. It is not an Android NDK binary.

The PRoot loader maps guest executables and files. The Android kernel does not directly execute ordinary guest Linux binaries from the app's writable data directory.

## Foreground service contract

The Activity/UI should call only `ServerRuntimeController`:

```java
ServerRuntimeController.start(context, "bedrock-main", "./bedrock_server", true, 10);
ServerRuntimeController.stop(context, "bedrock-main");
ServerRuntimeController.stopAll(context);
```

The controller validates identifiers, command length, and restart limits before dispatching explicit foreground-service actions. Do not execute arbitrary commands from untrusted files or network input.

The service is expected to:

- Call `startForeground()` promptly and create its notification channel.
- Own PRoot processes and a partial WakeLock while servers are active.
- Release the WakeLock after the last server stops.
- Persist desired server state before process launch.
- Reconcile persisted state after service recreation on a best-effort basis.
- Clean up child processes and state on stop and termination signals.

`START_STICKY` is not a guarantee that Android or an OEM will keep the service alive. Android 12+ foreground-service start restrictions, battery optimization, process limits, and OEM policies require device testing. Android 13+ notification permission handling must be implemented by the final UI. The `specialUse` foreground-service type may require policy review for store distribution.

## Manifest integration example

Merge the following into the final application manifest and verify it against the target Android SDK and distribution policy:

```xml
<uses-permission android:name="android.permission.INTERNET" />
<uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_SPECIAL_USE" />
<uses-permission android:name="android.permission.POST_NOTIFICATIONS" />
<uses-permission android:name="android.permission.WAKE_LOCK" />

<application>
    <service
        android:name=".runtime.ServerRuntimeService"
        android:exported="false"
        android:foregroundServiceType="specialUse">
        <property
            android:name="android.app.PROPERTY_SPECIAL_USE_FGS_SUBTYPE"
            android:value="User-started local game server process supervision" />
    </service>
</application>
```

The final app must also enable native-library extraction/legacy packaging as required by the selected Gradle and Android packaging configuration.

## Network behavior

`GuestNetworkConfig` reads DNS servers from Android's active `ConnectivityManager` network and writes the guest resolver configuration. Local-only server execution should remain possible when no network is available; do not invent fallback DNS addresses.

## Runtime limitations

The code has host-side tests but has not been exercised on a physical Android device. Validate first install, foreground-service permissions, process kill, reboot, DNS, Bedrock x86-64 under Box64, ARM64 server binaries, Java servers, stop behavior, and OEM battery restrictions before release.
