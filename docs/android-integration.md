# Android Integration Guide

The `AndroidIntegration` directory contains Java runtime integration classes and manifest examples. It is not a complete Gradle application module: the supplied archive does not contain the original app's Gradle project, Activity/UI, resources, or complete manifest.

## Required application integration

1. Add the runtime Java sources to the final app module.
2. Merge the service and permission declarations from `manifest/` into the app manifest.
3. Configure native library packaging so PRoot and its loader are extracted to `nativeLibraryDir`.
4. Connect the Activity/UI to `ServerRuntimeController` rather than launching shell commands directly.
5. Add the rootfs bootstrap/download flow and connect server metadata to the persistent application data model.
6. Implement SAF import and ensure server files are copied into app-private storage before execution.
7. Add UI handling for notification permission, foreground-service restrictions, and runtime errors.

## Native library directory

The `src/main/jniLibs/arm64-v8a/` directory intentionally contains no placeholder binaries. The pinned build workflow generates `libproot.so` and `libproot-loader.so`. Do not copy these files into the unchanged reference APK; that APK still uses the Termux command contract.

## Important distinction

This integration kit provides runtime building blocks. It does not provide a ready-to-open Android Studio project or a standalone APK.
