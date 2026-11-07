# Build and Validation Notes

## Pinned build inputs

- Box64 source version: `0.4.4`.
- DroidNativeApp commit: `fa591010d6e33adbf5a79178b874fd4abdc58931`.
- DroidNative native-host build script blob: `aed3eb4111492042dcaba75060619374ab4c33b6`.
- PRoot commit: `4ac94ecbb567608725e1d68d5ea617800a76c9c4`.
- Android NDK: `29.0.14206865`.

The GitHub Actions workflow builds two separate runtime artifacts: the Debian ARM64/Box64 guest rootfs and the Android ARM64 PRoot native libraries. It does not build the Android application APK.

## Host-side test command

```bash
bash tests/run-all-tests.sh
```

The current suite covers foreground-service dispatch, desired-server persistence, DNS online/offline behavior, ELF/JAR command selection, rootfs recovery and validation, archive traversal and symlink defenses, gzip integrity, runtime locking, supervisor stop/restart behavior, duplicate-start protection, log rotation, shell syntax, and workflow configuration.

## Latest supplied validation report

The most recent report records 42 of 42 static checks passing. Re-running the test command is recommended after every source change. The test runner emits `analysis/verification-report.json`.

## Explicitly not validated

- No Android APK was rebuilt.
- No PRoot native binaries were built in the supplied environment.
- No Debian rootfs archive was produced in the supplied environment.
- No install or server execution was tested on a physical Android device.

A successful host-side test run must not be described as a successful APK build or on-device test.
