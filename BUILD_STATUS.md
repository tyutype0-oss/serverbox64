# Build Status

## Current Result

The supplied source bundle has been cleaned and its host-side regression suite passes.

## APK Status

No new standalone APK has been built. The supplied archive does not contain a complete Android application project: it is missing the root Gradle project, app module configuration, complete application manifest, Activity/UI, resources, and the original application data layer. The APK under `APK/` is retained strictly as an unchanged migration reference and still depends on Termux.

## Build Environment

This environment has Java 21 but does not have an Android SDK, Android NDK, or Gradle installation configured. Native Android PRoot binaries and the Debian/Box64 guest root filesystem have therefore not been built here.

## Validation

Run `bash tests/run-all-tests.sh` from the repository root. This runs host-side regression tests only; it does not prove APK installation or runtime behavior on Android.
