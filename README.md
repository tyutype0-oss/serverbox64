# ServerBox64 Standalone Runtime Kit

This repository contains the runtime infrastructure and migration work for moving `dev.servers.app` away from Termux. It is **not a completed Android application or installable standalone APK**. The original APK is retained only as an unchanged reference for compatibility auditing.

## Current state

- Box64 source: version 0.4.4.
- Android host ABI targeted by the native launcher: `arm64-v8a`.
- Linux guest target: Debian Trixie ARM64 with glibc.
- Runtime integration classes and host-side tests are present.
- The original Android application source, Gradle project, complete manifest, Activity/UI, and resources were not included in the supplied archive.
- The included reference APK still calls Termux services and is not standalone.
- The test suite currently passes 42 static checks. This does not prove an APK build or on-device runtime.

## Architecture

The Android host runs the Android-built PRoot executable and external loader from the APK's `nativeLibraryDir`. PRoot maps a Debian ARM64 guest filesystem stored under the app-private home directory. Box64 is built inside that Linux guest, using a Linux ARM64 toolchain, to run compatible Linux x86-64 server binaries.

The intended private layout is created from `Context.getDataDir()`:

```text
home/
├── runtime/    Runtime configuration and backups
├── rootfs/     Debian ARM64 guest filesystem
├── servers/    Server installations and worlds
├── logs/       Server logs
├── cache/      Downloads and temporary files
└── config/     Persistent application configuration
```

The Android package name is `dev.servers.app`. Android may expose its private directory using either `/data/data/dev.servers.app` or the canonical `/data/user/0/dev.servers.app` path. Code must use Android APIs rather than hard-coded paths.

## Build components

### Debian guest and Box64

Build on a Linux ARM64 host with `debootstrap` and `binutils` installed:

```bash
sudo apt-get update
sudo apt-get install -y debootstrap binutils
sudo -E bash scripts/build-box64-debian-arm64-rootfs.sh
```

Expected outputs:

```text
dist/debian-trixie-arm64-box64-rootfs.tar.gz
dist/debian-trixie-arm64-box64-rootfs.sha256
```

### Android PRoot and loader

Install Android SDK/NDK and set the pinned NDK path:

```bash
export ANDROID_NDK="$ANDROID_HOME/ndk/29.0.14206865"
bash scripts/build-proot-android-arm64.sh
```

Expected outputs:

```text
AndroidIntegration/src/main/jniLibs/arm64-v8a/libproot.so
AndroidIntegration/src/main/jniLibs/arm64-v8a/libproot-loader.so
```

These `.so` files are executable host binaries. They are launched as processes; they must not be loaded with `System.loadLibrary()`. The final Android app must package/extract them under `ApplicationInfo.nativeLibraryDir`.

### GitHub Actions

`.github/workflows/build-runtime-components.yml` builds the guest rootfs and Android PRoot binaries as separate artifacts. It does not compile the Android APK.

## Runtime integration

The Java integration layer is under `AndroidIntegration/src/main/java/dev/servers/app/runtime/`:

- `AppPrivateHome`: creates and validates app-private runtime directories.
- `RootfsInstaller`: safely installs and validates the guest root filesystem.
- `RootfsRuntimeLock`: coordinates installation and active runtime leases.
- `NativeProotLauncher`: configures and starts PRoot with guest mounts and environment.
- `GuestNetworkConfig`: exports Android network DNS configuration to the guest.
- `ServerCommandResolver`: selects a compatible Bedrock ELF, Java JAR, or start script.
- `ServerRuntimeService`: owns server processes in an Android foreground service.
- `DesiredServerStore`: persists desired server state for best-effort service recovery.
- `ServerRuntimeController`: API for an Activity or UI to request start/stop actions.

The Activity/UI and the original app's data model have not yet been connected to these classes. The runtime layer is an integration component, not a drop-in complete app.

## Validation

Run the host-side checks:

```bash
bash tests/run-all-tests.sh
```

The suite compiles Java integration code against Android stubs and tests command resolution, DNS configuration, rootfs safety/recovery, process supervision, persistence, and workflow structure. A passing result is a static/host-side result only. It does not establish successful NDK builds, APK compilation, installation, or server execution on a physical device.

## Remaining work before a standalone APK exists

1. Obtain or reconstruct the complete Android application project (Gradle files, manifest, Activity/UI, resources, and data layer).
2. Connect the runtime controller and persistent server model to the app UI.
3. Build the PRoot/loader with the pinned NDK and produce the Debian/Box64 guest rootfs.
4. Implement or port missing app features such as SAF folder import, server updates that preserve worlds/configuration, pack management, and Java runtime selection.
5. Build and sign the APK, removing all Termux permissions, package queries, intents, and runtime paths.
6. Test installation, networking, Bedrock/Java execution, stop/restart, process death, reboot, storage pressure, and world/config preservation on a real ARM64 Android device.

Do not distribute the reference APK as a standalone build. See `docs/` for the migration audit, runtime notes, and third-party notices.
