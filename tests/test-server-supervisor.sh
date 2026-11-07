#!/usr/bin/env bash
set -Eeuo pipefail
ROOT="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
SUPERVISOR="$ROOT/scripts/server-supervisor.sh"
TMP="$(mktemp -d)"
cleanup() { rm -rf "$TMP"; }
trap cleanup EXIT

# One-shot start and log capture.
mkdir -p "$TMP/normal"
bash "$SUPERVISOR" "$TMP/normal" 'echo normal-ok; exit 0' 0 0
grep -q 'normal-ok' "$TMP/normal/.servers/server.log"
grep -q '^stopped$' "$TMP/normal/.servers/status"

# One bounded restart: first attempt + exactly one retry.
mkdir -p "$TMP/restart"
set +e
bash "$SUPERVISOR" "$TMP/restart" 'echo attempt >> attempts; exit 1' 1 1
restart_rc=$?
set -e
[[ "$restart_rc" -eq 1 ]]
[[ "$(wc -l < "$TMP/restart/attempts")" -eq 2 ]]
grep -q '^restart-limit-reached$' "$TMP/restart/.servers/status"

# A stop marker must terminate a currently running child and clean state.
mkdir -p "$TMP/stop"
bash "$SUPERVISOR" "$TMP/stop" 'exec sleep 60' 0 0 &
supervisor_pid=$!
for _ in $(seq 1 100); do
    [[ -s "$TMP/stop/.servers/server.pid" ]] && break
    sleep 0.05
done
[[ -s "$TMP/stop/.servers/server.pid" ]]
child_pid="$(cat "$TMP/stop/.servers/server.pid")"
touch "$TMP/stop/.servers/stop"
set +e
wait "$supervisor_pid"
stop_rc=$?
set -e
[[ "$stop_rc" -eq 143 ]]
! kill -0 "$child_pid" 2>/dev/null
grep -q '^stopped$' "$TMP/stop/.servers/status"
[[ ! -e "$TMP/stop/.servers/supervisor.pid" ]]


# A direct supervisor signal must stop the guest child and clear its PID state.
mkdir -p "$TMP/signal"
bash "$SUPERVISOR" "$TMP/signal" 'exec sleep 60' 0 0 &
signal_owner_pid=$!
for _ in $(seq 1 100); do
    [[ -s "$TMP/signal/.servers/server.pid" ]] && break
    sleep 0.05
done
[[ -s "$TMP/signal/.servers/server.pid" ]]
signal_child_pid="$(cat "$TMP/signal/.servers/server.pid")"
kill -TERM "$signal_owner_pid"
set +e
wait "$signal_owner_pid"
signal_rc=$?
set -e
[[ "$signal_rc" -eq 0 ]]
! kill -0 "$signal_child_pid" 2>/dev/null
grep -q '^stopped$' "$TMP/signal/.servers/status"
[[ ! -e "$TMP/signal/.servers/supervisor.pid" ]]

# A second manager must not start the same server while its lock is held.
mkdir -p "$TMP/duplicate"
bash "$SUPERVISOR" "$TMP/duplicate" 'exec sleep 60' 0 0 &
duplicate_owner_pid=$!
for _ in $(seq 1 100); do
    [[ -s "$TMP/duplicate/.servers/supervisor.pid" ]] && break
    sleep 0.05
done
[[ -s "$TMP/duplicate/.servers/supervisor.pid" ]]
second_output="$(bash "$SUPERVISOR" "$TMP/duplicate" 'echo must-not-run' 0 0)"
[[ "$second_output" == 'already-running' ]]
touch "$TMP/duplicate/.servers/stop"
wait "$duplicate_owner_pid" || true

# Log rotation keeps the most recent 5 MiB under .1 and starts a clean file.
mkdir -p "$TMP/rotate/.servers"
python3 - "$TMP/rotate/.servers/server.log" <<'PYLOG'
import sys
with open(sys.argv[1], 'wb') as f:
    f.write(b'A' * (6 * 1024 * 1024 - 8))
    f.write(b'TAILMARK')
PYLOG
bash "$SUPERVISOR" "$TMP/rotate" 'echo fresh-run' 0 0
[[ "$(wc -c < "$TMP/rotate/.servers/server.log.1")" -eq 5242880 ]]
grep -q 'TAILMARK' "$TMP/rotate/.servers/server.log.1"
grep -q 'fresh-run' "$TMP/rotate/.servers/server.log"

printf 'PASS: normal start and log capture\n'
printf 'PASS: bounded auto-restart (one retry)\n'
printf 'PASS: stop marker terminates child and clears supervisor state\n'
printf 'PASS: SIGTERM terminates child and cleans PID/state files\n'
printf 'PASS: duplicate server starts are rejected by flock\n'
printf 'PASS: bounded log rotation preserves the latest 5 MiB\n'
