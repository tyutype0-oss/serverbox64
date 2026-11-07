#!/usr/bin/env bash
# Build a Debian ARM64 guest rootfs containing a Linux/ARM64 Box64 build.
# This script MUST run on a native ARM64 Linux host (e.g. ubuntu-24.04-arm).
set -Eeuo pipefail

ROOT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
BOX64_SOURCE="$ROOT_DIR/Box64Source/ptitSeb-box64-2f130fa"
ROOTFS="${ROOTFS_DIR:-$ROOT_DIR/build/debian-${DEBIAN_SUITE:-trixie}-arm64}"
WORK_IN_ROOTFS="/opt/serverbox64-build"
OUT="$ROOT_DIR/dist"
JOBS="${JOBS:-2}"
SUITE="${DEBIAN_SUITE:-trixie}"
MIRROR="${DEBIAN_MIRROR:-https://deb.debian.org/debian}"

if [[ "$(uname -m)" != "aarch64" && "$(uname -m)" != "arm64" ]]; then
  echo "ERROR: This builds a native Linux ARM64 guest. Host is $(uname -m)." >&2
  echo "Use an ARM64 Linux runner; do not build Box64 with Android NDK." >&2
  exit 2
fi
for tool in debootstrap chroot tar readelf cmake; do
  command -v "$tool" >/dev/null || { echo "ERROR: missing tool: $tool" >&2; exit 2; }
done
[[ -f "$BOX64_SOURCE/CMakeLists.txt" && -f "$BOX64_SOURCE/src/box64version.h" ]] || {
  echo "ERROR: Box64 source tree missing: $BOX64_SOURCE" >&2; exit 2;
}

if [[ ! -x "$ROOTFS/bin/bash" ]]; then
  mkdir -p "$(dirname "$ROOTFS")"
  debootstrap --arch=arm64 --variant=minbase "$SUITE" "$ROOTFS" "$MIRROR"
fi

umount_all() { umount -lf "$ROOTFS/proc" "$ROOTFS/dev" "$ROOTFS/sys" 2>/dev/null || true; }
trap umount_all EXIT
mount -t proc proc "$ROOTFS/proc" && mount --bind /dev "$ROOTFS/dev" && mount -t sysfs sys "$ROOTFS/sys"
# This is a guest Linux environment. Box64 is compiled with the guest's glibc
# toolchain, not Android bionic/NDK.
chroot "$ROOTFS" /bin/bash -Eeuc '
  export DEBIAN_FRONTEND=noninteractive
  apt-get update
  apt-get install -y --no-install-recommends \
    bash ca-certificates curl wget tar xz-utils gzip \
    procps psmisc util-linux iproute2 iputils-ping net-tools \
    libcurl4t64 libssl3t64 zlib1g libatomic1 libunwind8 \
    openjdk-21-jre-headless \
    build-essential cmake ninja-build python3 git binutils
'

rm -rf "$ROOTFS$WORK_IN_ROOTFS"
mkdir -p "$ROOTFS$WORK_IN_ROOTFS/source"
cp -a "$BOX64_SOURCE/." "$ROOTFS$WORK_IN_ROOTFS/source/"

chroot "$ROOTFS" /bin/bash -Eeuc "
  cmake -S '$WORK_IN_ROOTFS/source' -B '$WORK_IN_ROOTFS/build' -G Ninja \
    -DCMAKE_BUILD_TYPE=Release \
    -DCMAKE_INSTALL_PREFIX=/usr \
    -DARM64=ON \
    -DARM_DYNAREC=ON \
    -DBAD_SIGNAL=ON \
    -DNOGIT=ON \
    -DNO_LIB_INSTALL=OFF \
    -DNO_CONF_INSTALL=OFF \
    -DSTATICBUILD=OFF \
    -DBOX32=OFF \
    -DHAVE_TRACE=OFF
  cmake --build '$WORK_IN_ROOTFS/build' --parallel '$JOBS'
  cmake --install '$WORK_IN_ROOTFS/build'
  /usr/bin/box64 --version
  java -version
  ldd /usr/bin/box64 | tee /tmp/box64-ldd.txt
  ! grep -q "not found" /tmp/box64-ldd.txt
"

BOX64_BIN="$ROOTFS/usr/bin/box64"
[[ -s "$BOX64_BIN" ]] || { echo "ERROR: missing $BOX64_BIN" >&2; exit 3; }
# Preserve the MIT notice in the generated guest rootfs, not only the source ZIP.
install -D -m 0644 "$BOX64_SOURCE/LICENSE" "$ROOTFS/usr/share/doc/box64/copyright"
readelf -h "$BOX64_BIN" | grep -q 'Machine:.*AArch64' || {
  echo "ERROR: Box64 guest binary is not AArch64" >&2; readelf -h "$BOX64_BIN" >&2; exit 4;
}
[[ -d "$ROOTFS/usr/lib/box64-x86_64-linux-gnu" ]] || {
  echo "ERROR: Box64 x86_64 support libraries were not installed" >&2; exit 5;
}

# Install the app's supervisor in the guest. It replaces the old Termux
# supervisor and preserves log/state/auto-restart semantics without Termux APIs.
SUPERVISOR="$ROOT_DIR/scripts/server-supervisor.sh"
[[ -s "$SUPERVISOR" ]] || { echo "ERROR: missing guest supervisor: $SUPERVISOR" >&2; exit 6; }
install -D -m 0755 "$SUPERVISOR" "$ROOTFS/usr/local/sbin/serverbox64-supervisor"
test -x "$ROOTFS/usr/local/sbin/serverbox64-supervisor"

# Keep only runtime tools/libraries. Python stays because Box64 installs a
# python wrapper; apt stays available for optional server/JRE packages.
chroot "$ROOTFS" /bin/bash -Eeuc '
  export DEBIAN_FRONTEND=noninteractive
  apt-get purge -y build-essential cmake ninja-build binutils git
  apt-get autoremove -y --purge
  apt-get clean
  rm -rf /var/lib/apt/lists/* /var/cache/apt/archives/*.deb /tmp/*
'
rm -rf "$ROOTFS$WORK_IN_ROOTFS"
mkdir -p "$OUT"
umount_all
ARCHIVE="$OUT/debian-${SUITE}-arm64-box64-rootfs.tar.gz"
tar --numeric-owner -C "$ROOTFS" -czf "$ARCHIVE" .
sha256sum "$ARCHIVE" | tee "$OUT/debian-${SUITE}-arm64-box64-rootfs.sha256"
echo "Created guest rootfs: $ARCHIVE"
echo "This archive is Linux guest data; Android PRoot host binaries are built separately."
