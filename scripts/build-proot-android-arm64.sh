#!/usr/bin/env bash
# Build Android-native PRoot + its external loader for targetSdk 29+.
# Uses DroidNative's pinned PRoot fork build recipe. Only the headless PRoot
# executable and external loader are copied; graphics components are ignored.
set -Eeuo pipefail

ROOT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
CACHE="$ROOT_DIR/build/droidnative-app"
OUT="$ROOT_DIR/AndroidIntegration/src/main/jniLibs/arm64-v8a"
ANDROID_NDK="${ANDROID_NDK:-${ANDROID_NDK_HOME:-}}"
EXPECTED_DROIDNATIVE_APP_COMMIT="fa591010d6e33adbf5a79178b874fd4abdc58931"
EXPECTED_BUILD_SCRIPT_BLOB="aed3eb4111492042dcaba75060619374ab4c33b6"
EXPECTED_PROOT_COMMIT="4ac94ecbb567608725e1d68d5ea617800a76c9c4"
EXPECTED_NDK_VERSION="29.0.14206865"

if [[ -z "$ANDROID_NDK" || ! -x "$ANDROID_NDK/toolchains/llvm/prebuilt/linux-x86_64/bin/aarch64-linux-android24-clang" ]]; then
  echo "ERROR: Set ANDROID_NDK / ANDROID_NDK_HOME to NDK 29.0.14206865." >&2
  exit 2
fi
for tool in git curl make python3 meson ninja pkg-config rsync readelf sha256sum; do
  command -v "$tool" >/dev/null || { echo "ERROR: missing build tool: $tool" >&2; exit 2; }
done

if [[ ! -d "$CACHE/.git" ]]; then
  rm -rf "$CACHE"
  git clone --filter=blob:none --no-checkout https://github.com/DroidNative/DroidNativeApp.git "$CACHE"
fi
# Pin the exact upstream build recipe, not the moving main branch.
git -C "$CACHE" fetch --depth 1 origin "$EXPECTED_DROIDNATIVE_APP_COMMIT"
git -C "$CACHE" checkout --detach -q "$EXPECTED_DROIDNATIVE_APP_COMMIT"
ACTUAL_APP_COMMIT="$(git -C "$CACHE" rev-parse HEAD)"
[[ "$ACTUAL_APP_COMMIT" == "$EXPECTED_DROIDNATIVE_APP_COMMIT" ]] || {
  echo "ERROR: DroidNativeApp source commit does not match the reviewed pin." >&2; exit 3;
}
BUILD_RECIPE="$CACHE/native-host/build.sh"
ACTUAL_BUILD_SCRIPT_BLOB="$(git -C "$CACHE" rev-parse "$EXPECTED_DROIDNATIVE_APP_COMMIT:native-host/build.sh")"
[[ "$ACTUAL_BUILD_SCRIPT_BLOB" == "$EXPECTED_BUILD_SCRIPT_BLOB" ]] || {
  echo "ERROR: upstream native-host/build.sh blob changed." >&2; exit 3;
}
grep -Fq "PROOT_COMMIT=\"$EXPECTED_PROOT_COMMIT\"" "$BUILD_RECIPE" || {
  echo "ERROR: DroidNative build recipe no longer pins the reviewed PRoot commit." >&2; exit 3;
}
grep -Fq "NDK_VERSION=\"$EXPECTED_NDK_VERSION\"" "$BUILD_RECIPE" || {
  echo "ERROR: DroidNative build recipe NDK version changed." >&2; exit 3;
}

# Upstream script pins the DroidNative/proot commit and checks SHA-256 hashes
# for talloc and its other native build inputs. It outputs unbundled loaders.
ANDROID_NDK="$ANDROID_NDK" bash "$BUILD_RECIPE" arm64-v8a

SOURCE="$CACHE/app/src/main/jniLibs/arm64-v8a"
for file in libproot.so libproot-loader.so; do
  [[ -s "$SOURCE/$file" ]] || { echo "ERROR: upstream build did not create $file" >&2; exit 3; }
done
mkdir -p "$OUT"
cp "$SOURCE/libproot.so" "$SOURCE/libproot-loader.so" "$OUT/"
# This project currently targets 64-bit Linux guests only (Box32 is off).
# Do not package the optional 32-bit loader into the arm64-only runtime.

for file in "$OUT"/*.so; do
  readelf -h "$file" | grep -q 'Machine:.*AArch64' || {
    echo "ERROR: wrong architecture in $file" >&2; readelf -h "$file" >&2; exit 4;
  }
  readelf -l "$file" | grep -q '/system/bin/linker64' || {
    echo "ERROR: $file does not use Android's 64-bit linker" >&2; readelf -l "$file" >&2; exit 5;
  }
  chmod 755 "$file"
done
mkdir -p "$ROOT_DIR/dist"
sha256sum "$OUT"/*.so | tee "$ROOT_DIR/dist/android-arm64-proot.sha256"
printf '%s\n' "$EXPECTED_DROIDNATIVE_APP_COMMIT" | tee "$ROOT_DIR/dist/droidnative-app-source.commit"
PROOT_SOURCE="$CACHE/.cache/proot-src"
[[ -d "$PROOT_SOURCE/.git" ]] || { echo "ERROR: pinned PRoot source checkout was not created" >&2; exit 6; }
git -C "$PROOT_SOURCE" archive --format=tar.gz \
  --prefix="droidnative-proot-$EXPECTED_PROOT_COMMIT/" "$EXPECTED_PROOT_COMMIT" \
  > "$ROOT_DIR/dist/droidnative-proot-$EXPECTED_PROOT_COMMIT-source.tar.gz"
printf '%s\n' "$EXPECTED_PROOT_COMMIT" > "$ROOT_DIR/dist/droidnative-proot-source.commit"
echo "Created Android host runtime libraries under: $OUT"
echo "Package these .so-named executables in app/src/main/jniLibs/arm64-v8a/ with native library extraction enabled."
