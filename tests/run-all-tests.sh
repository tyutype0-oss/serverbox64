#!/usr/bin/env bash
set -Eeuo pipefail
ROOT="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
TESTS="$ROOT/tests"
BUILD_DIR="${TMPDIR:-/tmp}/serverbox64-test-classes"
rm -rf "$BUILD_DIR"
mkdir -p "$BUILD_DIR"

mapfile -t STUBS < <(find "$TESTS/android-stubs" -type f -name '*.java' -print | sort)
mapfile -t SOURCES < <(find "$ROOT/AndroidIntegration/src/main/java" -type f -name '*.java' -print | sort)

javac -source 8 -target 8 -Xlint:all -d "$BUILD_DIR" \
  "${STUBS[@]}" "${SOURCES[@]}" \
  "$TESTS/RootfsInstallerTest.java" \
  "$TESTS/ServerCommandResolverTest.java" \
  "$TESTS/GuestNetworkConfigTest.java" \
  "$TESTS/DesiredServerStoreTest.java" \
  "$TESTS/ServerRuntimeControllerTest.java"

java -cp "$BUILD_DIR" dev.servers.app.runtime.ServerRuntimeControllerTest
java -cp "$BUILD_DIR" dev.servers.app.runtime.DesiredServerStoreTest
java -cp "$BUILD_DIR" dev.servers.app.runtime.GuestNetworkConfigTest
java -cp "$BUILD_DIR" dev.servers.app.runtime.ServerCommandResolverTest
java -cp "$BUILD_DIR" dev.servers.app.runtime.RootfsInstallerTest
bash "$TESTS/test-server-supervisor.sh"

bash -n "$ROOT/scripts/server-supervisor.sh"
bash -n "$ROOT/scripts/build-box64-debian-arm64-rootfs.sh"
bash -n "$ROOT/scripts/build-proot-android-arm64.sh"
python3 "$ROOT/scripts/verify_standalone_bundle.py"
