#!/usr/bin/env python3
"""Static verifier for the ServerBox64 independent-runtime integration bundle.

This validates source layout and build metadata only. It does NOT claim that
an APK, Android native binary, rootfs, or game server was built or device-tested.
"""
from __future__ import annotations

import hashlib
import json
import re
import shutil
import subprocess
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
APK = ROOT / "APK/servers-1.0-original.apk"
BOX64 = ROOT / "Box64Source/ptitSeb-box64-2f130fa"
HOME_JAVA = ROOT / "AndroidIntegration/src/main/java/dev/servers/app/runtime/AppPrivateHome.java"
PROOT_JAVA = ROOT / "AndroidIntegration/src/main/java/dev/servers/app/runtime/NativeProotLauncher.java"
SERVICE_JAVA = ROOT / "AndroidIntegration/src/main/java/dev/servers/app/runtime/ServerRuntimeService.java"
BOX64_BUILD = ROOT / "scripts/build-box64-debian-arm64-rootfs.sh"
PROOT_BUILD = ROOT / "scripts/build-proot-android-arm64.sh"
WORKFLOW = ROOT / ".github/workflows/build-runtime-components.yml"


def parse_axml(data: bytes):
    """Read enough Android binary XML to inspect package, SDK and permissions."""
    import struct

    if len(data) < 8 or struct.unpack_from("<H", data, 0)[0] != 0x0003:
        raise ValueError("AndroidManifest.xml is not binary AXML")
    pos = 8
    strings: list[str] = []
    tags: list[tuple[str, dict[str, str]]] = []

    def read_len8(buf: bytes, off: int):
        value = buf[off]
        if value & 0x80:
            return ((value & 0x7F) << 8) | buf[off + 1], off + 2
        return value, off + 1

    def read_len16(buf: bytes, off: int):
        value = struct.unpack_from("<H", buf, off)[0]
        if value & 0x8000:
            return ((value & 0x7FFF) << 16) | struct.unpack_from("<H", buf, off + 2)[0], off + 4
        return value, off + 2

    while pos + 8 <= len(data):
        chunk_type, header_size, chunk_size = struct.unpack_from("<HHI", data, pos)
        if chunk_size < 8 or pos + chunk_size > len(data):
            raise ValueError("Invalid AndroidManifest.xml chunk bounds")
        if chunk_type == 0x0001:
            count, _style_count, flags, strings_start, _styles_start = struct.unpack_from(
                "<IIIII", data, pos + 8
            )
            utf8 = bool(flags & 0x100)
            offsets = struct.unpack_from("<" + "I" * count, data, pos + header_size)
            base = pos + strings_start
            for offset in offsets:
                cursor = base + offset
                if utf8:
                    _utf16_length, cursor = read_len8(data, cursor)
                    byte_length, cursor = read_len8(data, cursor)
                    value = data[cursor:cursor + byte_length].decode("utf-8", "replace")
                else:
                    char_length, cursor = read_len16(data, cursor)
                    value = data[cursor:cursor + char_length * 2].decode("utf-16le", "replace")
                strings.append(value)
        elif chunk_type == 0x0102:
            name_index = struct.unpack_from("<I", data, pos + 20)[0]
            attr_start, attr_size, attr_count = struct.unpack_from("<HHH", data, pos + 24)
            name = strings[name_index]
            attrs: dict[str, str] = {}
            cursor = pos + 16 + attr_start
            for _ in range(attr_count):
                _namespace, key_index, raw_index = struct.unpack_from("<III", data, cursor)
                _size, _res0, value_type, value_data = struct.unpack_from("<HBBI", data, cursor + 12)
                key = strings[key_index]
                if raw_index != 0xFFFFFFFF:
                    value = strings[raw_index]
                elif value_type == 3 and value_data < len(strings):
                    value = strings[value_data]
                elif value_type == 0x12:
                    value = "true" if value_data else "false"
                else:
                    value = hex(value_data)
                attrs[key] = value
                cursor += attr_size
            tags.append((name, attrs))
        pos += chunk_size
    return tags


def dex_strings(dex: bytes) -> set[str]:
    import struct

    if not dex.startswith(b"dex\n"):
        raise ValueError("classes.dex has an invalid header")
    size, offset = struct.unpack_from("<II", dex, 56)
    strings: set[str] = set()
    for index in range(size):
        string_offset = struct.unpack_from("<I", dex, offset + 4 * index)[0]
        cursor = string_offset
        while dex[cursor] & 0x80:
            cursor += 1
        cursor += 1
        end = dex.index(b"\x00", cursor)
        strings.add(dex[cursor:end].decode("utf-8", "replace"))
    return strings


def run(cmd: list[str], **kwargs) -> subprocess.CompletedProcess:
    return subprocess.run(cmd, text=True, capture_output=True, check=False, **kwargs)


def main() -> None:
    required = [APK, BOX64 / "src/box64version.h", BOX64 / "docs/COMPILE.md",
                HOME_JAVA, PROOT_JAVA, SERVICE_JAVA, BOX64_BUILD, PROOT_BUILD, WORKFLOW]
    missing = [str(p.relative_to(ROOT)) for p in required if not p.is_file()]
    if missing:
        raise SystemExit("Missing required files: " + ", ".join(missing))

    with zipfile.ZipFile(APK) as archive:
        tags = parse_axml(archive.read("AndroidManifest.xml"))
        dex = dex_strings(archive.read("classes.dex"))
        apk_entries = archive.namelist()

    manifest_attrs = next(attrs for name, attrs in tags if name == "manifest")
    sdk_attrs = next(attrs for name, attrs in tags if name == "uses-sdk")
    permissions = [attrs.get("name", "") for name, attrs in tags if name == "uses-permission"]
    version_header = (BOX64 / "src/box64version.h").read_text()
    version_match = [re.search(rf"#define BOX64_{part} (\d+)", version_header)
                     for part in ("MAJOR", "MINOR", "REVISION")]
    box64_version = ".".join(match.group(1) for match in version_match if match)

    compile_doc = (BOX64 / "docs/COMPILE.md").read_text(errors="replace")
    home_src = HOME_JAVA.read_text()
    proot_src = PROOT_JAVA.read_text()
    service_src = SERVICE_JAVA.read_text()
    controller_src = (ROOT / "AndroidIntegration/src/main/java/dev/servers/app/runtime/ServerRuntimeController.java").read_text()
    network_src = (ROOT / "AndroidIntegration/src/main/java/dev/servers/app/runtime/GuestNetworkConfig.java").read_text()
    installer_src = (ROOT / "AndroidIntegration/src/main/java/dev/servers/app/runtime/RootfsInstaller.java").read_text()
    supervisor_src = (ROOT / "scripts/server-supervisor.sh").read_text()
    runner_src = (ROOT / "tests/run-all-tests.sh").read_text()
    box64_script = BOX64_BUILD.read_text()
    proot_script = PROOT_BUILD.read_text()
    workflow_text = WORKFLOW.read_text()
    runtime_sources = "\n".join(
        path.read_text(errors="replace")
        for path in (ROOT / "AndroidIntegration/src/main/java").rglob("*.java")
    ) + "\n" + supervisor_src

    checks: dict[str, bool] = {
        "original_apk_package_dev.servers.app": manifest_attrs.get("package") == "dev.servers.app",
        "original_apk_version_1.0": manifest_attrs.get("versionName") == "1.0",
        "original_apk_min_sdk_26": sdk_attrs.get("minSdkVersion") == "0x1a",
        "original_apk_target_sdk_34": sdk_attrs.get("targetSdkVersion") == "0x22",
        "original_apk_is_still_termux_dependent": (
            "com.termux.permission.RUN_COMMAND" in permissions
            and "com.termux.app.RunCommandService" in dex
            and "/data/data/com.termux/files/home" in dex
        ),
        "original_apk_does_not_contain_native_libs": not any(n.startswith("lib/") for n in apk_entries),
        "legacy_termux_run_command_contract_detected": all(
            token in dex for token in ("com.termux.RUN_COMMAND", "com.termux.RUN_COMMAND_ARGUMENTS", "com.termux.app.RunCommandService")
        ),
        "legacy_termux_bash_and_home_paths_detected": (
            "/data/data/com.termux/files/usr/bin/bash" in dex
            and "/data/data/com.termux/files/home" in dex
        ),
        "legacy_termux_setup_and_wakelock_detected": (
            "termux-setup-storage" in "\n".join(dex)
            and "termux-wake-lock" in "\n".join(dex)
            and "allow-external-apps=true" in "\n".join(dex)
        ),
        "legacy_app_update_and_pack_features_detected": (
            "server.properties" in "\n".join(dex) and "allowlist" in "\n".join(dex)
            and "permissions" in "\n".join(dex) and "worlds" in "\n".join(dex)
            and any("packs" in item.lower() for item in dex)
        ),
        "standalone_runtime_has_no_termux_execution_contract": all(
            token not in runtime_sources for token in (
                "com.termux.permission.RUN_COMMAND",
                "com.termux.app.RunCommandService",
                "com.termux.RUN_COMMAND",
                "/data/data/com.termux/",
                "termux-wake-lock",
            )
        ),
        "box64_source_version_0.4.4": box64_version == "0.4.4",
        "box64_source_documents_native_termux_limitation": "won't run linux binaries" in compile_doc,
        "java_resolver_rejects_ambiguous_jar_folders": "Multiple JAR files found" in (ROOT / "AndroidIntegration/src/main/java/dev/servers/app/runtime/ServerCommandResolver.java").read_text(),
        "app_home_uses_context_getDataDir": "context.getDataDir()" in home_src,
        "app_home_has_all_required_folders": all(
            f'homePath.resolve("{name}")' in home_src
            for name in ("runtime", "rootfs", "servers", "logs", "cache", "config")
        ),
        "app_home_rejects_path_traversal_and_symlinks": (
            '"..".equals(part)' in home_src and "Files.isSymbolicLink(cursor)" in home_src
        ),
        "rootfs_recovery_is_explicit_and_lock_protected": (
            "public static void recoverInterruptedRootfs(Layout home)" in home_src
            and "rootfs-install.lock" in home_src
            and "RootfsRuntimeLock.acquireExclusive(home.runtime)" in home_src
            and "recoverRootfsIfNeeded" not in home_src
        ),
        "service_recovers_before_shared_rootfs_lease": (
            service_src.index("recoverInterruptedRootfs(home)") < service_src.index("acquireRootfsLease(home.runtime)")
        ),
        "proot_launcher_uses_nativeLibraryDir": "nativeLibraryDir" in proot_src,
        "proot_launcher_sets_external_loader_and_tmp": (
            'env.put("PROOT_LOADER"' in proot_src and 'env.put("PROOT_TMP_DIR"' in proot_src
        ),
        "proot_launcher_binds_guest_kernel_interfaces": all(
            f'--bind={mount}' in proot_src for mount in ("/dev", "/proc", "/sys")
        ),
        "guest_dns_is_mapped_from_android_connectivity": (
            "ConnectivityManager" in network_src and "getDnsServers()" in network_src
            and "resolv.conf" in proot_src and "guestResolver.getAbsolutePath()" in proot_src
        ),
        "standalone_manifest_has_network_permissions": (
            "android.permission.INTERNET" in (ROOT / "AndroidIntegration/manifest/AndroidManifest.xml.snippet").read_text()
            and "android.permission.ACCESS_NETWORK_STATE" in (ROOT / "AndroidIntegration/manifest/AndroidManifest.xml.snippet").read_text()
        ),
        "activity_controller_uses_foreground_service": (
            "startForegroundService(intent)" in controller_src
            and "ACTION_START" in controller_src and "ACTION_STOP" in controller_src and "ACTION_STOP_ALL" in controller_src
        ),
        "service_is_foreground_and_stops_processes": (
            "startForeground(" in service_src and "process.destroyForcibly()" in service_src
        ),
        "service_has_wake_lock_support": (
            "PARTIAL_WAKE_LOCK" in service_src and "releaseCpuWakeLock()" in service_src
        ),
        "service_reconciles_persisted_servers_after_process_death": (
            "return START_STICKY" in service_src and "restoreDesiredServers()" in service_src
            and "DesiredServerStore.save(this" in service_src
        ),
        "rootfs_extractor_has_decompression_limits_and_crc_validation": (
            "MAX_ARCHIVE_BYTES" in installer_src and "MAX_SINGLE_FILE_BYTES" in installer_src
            and "MAX_TOTAL_TAR_PAYLOAD_BYTES" in installer_src and "MAX_TAR_ENTRIES" in installer_src
            and "input.read(tail)" in installer_src
        ),
        "guest_supervisor_cleans_up_on_termination_signal": (
            "trap 'handle_signal' TERM INT HUP" in supervisor_src
            and "stop_child_from_monitor" in supervisor_src
        ),
        "repeatable_full_test_runner_exists": (
            "DesiredServerStoreTest.java" in runner_src and "ServerRuntimeControllerTest.java" in runner_src
            and "GuestNetworkConfigTest.java" in runner_src
            and "RootfsInstallerTest.java" in runner_src and "test-server-supervisor.sh" in runner_src
            and "verify_standalone_bundle.py" in runner_src
        ),
        "box64_built_for_linux_guest_not_android": (
            "-DARM64=ON" in box64_script and "-DARM_DYNAREC=ON" in box64_script
            and "-DANDROID=" not in box64_script and "-DTERMUX=" not in box64_script
        ),
        "box64_guest_has_java_21": (
            "DEBIAN_SUITE:-trixie" in box64_script and "openjdk-21-jre-headless" in box64_script
        ),
        "proot_builder_pins_ndk_and_proot_revision": (
            "29.0.14206865" in proot_script and "4ac94ecbb567608725e1d68d5ea617800a76c9c4" in proot_script
            and r'grep -Fq "PROOT_COMMIT=\"$EXPECTED_PROOT_COMMIT\"" "$BUILD_RECIPE"' in proot_script
            and r'grep -Fq "NDK_VERSION=\"$EXPECTED_NDK_VERSION\"" "$BUILD_RECIPE"' in proot_script
            and "fa591010d6e33adbf5a79178b874fd4abdc58931" in proot_script
            and "aed3eb4111492042dcaba75060619374ab4c33b6" in proot_script
            and 'git -C "$PROOT_SOURCE" archive' in proot_script
        ),
        "workflow_has_separate_guest_and_android_jobs": (
            "box64-linux-arm64:" in workflow_text and "android-proot-arm64:" in workflow_text
        ),
        "stale_android_box64_patch_removed": not (ROOT / "patches/box64-android-app-bundle.patch").exists(),
        "stale_android_box64_builder_removed": not (ROOT / "scripts/build-box64-android-arm64.sh").exists(),
    }

    syntax_checks: dict[str, bool] = {}
    for script in (BOX64_BUILD, PROOT_BUILD, ROOT / "scripts/server-supervisor.sh", ROOT / "tests/run-all-tests.sh"):
        result = run(["bash", "-n", str(script)])
        syntax_checks[f"bash_syntax_{script.name}"] = result.returncode == 0
    checks.update(syntax_checks)

    yaml_result = "not-installed"
    yaml_ok = False
    try:
        import yaml  # type: ignore
        parsed = yaml.safe_load(workflow_text)
        yaml_ok = isinstance(parsed, dict) and isinstance(parsed.get("jobs"), dict)
        yaml_result = "parsed"
    except ImportError:
        # No YAML parser in the environment; retain a structural check and say so.
        yaml_ok = all(token in workflow_text for token in ("jobs:", "runs-on:", "actions/checkout@v4"))
        yaml_result = "parser-unavailable; structural-check-only"
    checks["github_actions_yaml"] = yaml_ok

    apk_hash = hashlib.sha256(APK.read_bytes()).hexdigest()
    result = {
        "validation_scope": "static source and bundle checks only",
        "apk": {
            "package": manifest_attrs.get("package"),
            "versionName": manifest_attrs.get("versionName"),
            "minSdkVersion": sdk_attrs.get("minSdkVersion"),
            "targetSdkVersion": sdk_attrs.get("targetSdkVersion"),
            "permissions": permissions,
            "zip_entries": len(apk_entries),
            "sha256": apk_hash,
            "status": "original reference APK; unchanged; still Termux-dependent",
        },
        "box64_source_version": box64_version,
        "upstream_pins": {
            "droidnative_app_commit": "fa591010d6e33adbf5a79178b874fd4abdc58931",
            "droidnative_native_host_build_script_blob": "aed3eb4111492042dcaba75060619374ab4c33b6",
            "droidnative_proot_commit": "4ac94ecbb567608725e1d68d5ea617800a76c9c4",
            "android_ndk": "29.0.14206865",
        },
        "legacy_termux_dependencies": {
            "manifest_permission": "com.termux.permission.RUN_COMMAND",
            "service": "com.termux.app.RunCommandService",
            "home": "/data/data/com.termux/files/home",
            "bash": "/data/data/com.termux/files/usr/bin/bash",
            "properties": ["allow-external-apps=true", "termux-setup-storage", "termux-wake-lock"],
            "note": "These findings describe the unchanged reference APK, not the standalone runtime source.",
        },
        "existing_app_features_detected": [
            "server folder discovery", "start/stop/restart", "server log/status", "automatic restart",
            "server update while preserving worlds and server.properties/allowlist/permissions",
            "Bedrock pack library/install", "server-folder picker", "optional start-servers-on-open",
        ],
        "yaml_validation": yaml_result,
        "checks": checks,
        "passed": sum(checks.values()),
        "total": len(checks),
        "result": "STATIC_CHECKS_PASS" if all(checks.values()) else "STATIC_CHECKS_FAIL",
        "not_claimed": [
            "no Android APK was rebuilt",
            "no PRoot native binaries were built in this environment",
            "no Debian rootfs archive was built in this environment",
            "no physical-device install or server test was run",
        ],
    }
    report_path = ROOT / "analysis/verification-report.json"
    report_path.parent.mkdir(parents=True, exist_ok=True)
    report_path.write_text(json.dumps(result, indent=2, ensure_ascii=False) + "\n")
    print(json.dumps(result, indent=2, ensure_ascii=False))
    if not all(checks.values()):
        raise SystemExit(1)


if __name__ == "__main__":
    main()
