# Legacy APK Migration Audit

This audit is based on the manifest and DEX strings extracted from `APK/servers-1.0-original.apk`. It is a static analysis, not a complete source-level reconstruction.

## Confirmed legacy dependencies

| Legacy item | Current role | Standalone replacement |
|---|---|---|
| `com.termux.permission.RUN_COMMAND` | Permission to invoke Termux | Remove; use the app's own runtime controller |
| `com.termux.app.RunCommandService` | Background command execution | `ServerRuntimeService` as an Android foreground service |
| `com.termux.RUN_COMMAND_*` extras | Command, working directory, path, and background state | Internal app intents with validated server identifiers and policies |
| `/data/data/com.termux/files/usr/bin/bash` | Host Bash executable | `/bin/bash` inside the Debian guest |
| `/data/data/com.termux/files/home` | Server working directory | App-private home mapped into the guest |
| `termux-wake-lock` | Prevent CPU sleep | `PowerManager.PARTIAL_WAKE_LOCK` owned by the foreground service |
| `termux-setup-storage` | Shared-storage access | Android Storage Access Framework (SAF), followed by import into app-private storage |
| Termux supervisor/PID/log files | Process lifecycle and monitoring | Guest supervisor plus Android service lifecycle management |

## Features identified in the reference APK

- Server directory discovery.
- Start, stop, and restart controls.
- Server status and log display.
- Automatic restart after unexpected exit.
- Server updates that preserve worlds and configuration files.
- Bedrock pack library and installation.
- Server folder picker.
- Optional start-on-app-open behavior.

The supplied runtime work focuses on process execution, supervision, logs, restart handling, rootfs installation, and DNS. The UI, original data model, SAF import flow, server update workflow, pack library, and JRE selection still need integration or porting.

## Storage and update requirements

Server data should be imported into app-private storage before execution. A selected shared-storage URI is not itself an executable Linux filesystem path. Update operations must preserve `worlds/`, `server.properties`, allowlists, permissions, and other user data using backup/rollback or transactional replacement.

## Conclusion

The reference APK is still Termux-dependent. The new Java runtime classes do not use the Termux execution contract, but the original app UI and data layer are absent from the archive. Therefore, feature parity and a complete standalone APK cannot yet be claimed.
