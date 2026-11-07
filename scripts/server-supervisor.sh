#!/bin/bash
# Guest-side server supervisor. Runs inside the Debian PRoot guest.
# Args: server-directory start-command auto-restart(0|1) max-restarts(0=unlimited)
set -u

SERVER_DIR="${1:-}"
START_COMMAND="${2:-}"
AUTO_RESTART="${3:-0}"
MAX_RESTARTS="${4:-0}"

fail() { printf 'serverbox64-supervisor: %s\n' "$*" >&2; exit 2; }
[[ -n "$SERVER_DIR" && -n "$START_COMMAND" ]] || fail 'server directory and start command are required'
[[ "$AUTO_RESTART" == 0 || "$AUTO_RESTART" == 1 ]] || fail 'auto-restart must be 0 or 1'
[[ "$MAX_RESTARTS" =~ ^[0-9]{1,5}$ ]] || fail 'max-restarts must be an integer from 0 to 99999'

cd -- "$SERVER_DIR" || fail "cannot enter server directory: $SERVER_DIR"
STATE_DIR="$SERVER_DIR/.servers"
mkdir -p -- "$STATE_DIR" || fail 'cannot create .servers state directory'
chmod 700 "$STATE_DIR" 2>/dev/null || true

# flock prevents duplicate supervisors even when the Android service is started twice.
command -v flock >/dev/null 2>&1 || fail 'flock is missing from the Linux guest (install util-linux)'
exec 9>"$STATE_DIR/supervisor.lock"
flock -n 9 || { echo 'already-running'; exit 0; }

STOP_FILE="$STATE_DIR/stop"
PID_FILE="$STATE_DIR/supervisor.pid"
CHILD_PID_FILE="$STATE_DIR/server.pid"
LOG_FILE="$STATE_DIR/server.log"
STATUS_FILE="$STATE_DIR/status"
STATE_FILE="$STATE_DIR/state"
RESTARTS_FILE="$STATE_DIR/restarts"

printf '%s\n' "$$" > "$PID_FILE"
printf '0\n' > "$RESTARTS_FILE"
printf 'starting\n' > "$STATUS_FILE"
CHILD_PID=''
MONITOR_PID=''
FINAL_STATUS='stopped'

rotate_log() {
    local size previous temporary
    [[ -f "$LOG_FILE" ]] || return 0
    size=$(wc -c < "$LOG_FILE" 2>/dev/null || echo 0)
    [[ "$size" =~ ^[0-9]+$ ]] || size=0
    (( size > 5242880 )) || return 0
    previous="$LOG_FILE.1"
    temporary="$LOG_FILE.rotate.tmp"
    # Keep the newest 5 MiB as the previous log, then create a fresh active
    # log. Rotation is called only between server runs, never while a writer
    # still has the active file open.
    tail -c 5242880 "$LOG_FILE" > "$temporary" || { rm -f -- "$temporary"; return 1; }
    mv -f -- "$temporary" "$previous" || return 1
    : > "$LOG_FILE" || return 1
    return 0
}

stop_child_from_monitor() {
    local pid i
    [[ -f "$CHILD_PID_FILE" ]] || return 0
    pid=$(cat "$CHILD_PID_FILE" 2>/dev/null || true)
    [[ "$pid" =~ ^[0-9]+$ ]] || return 0
    kill -TERM "$pid" 2>/dev/null || true
    # Also ask direct children (e.g. a Java/Box64 wrapper) to stop cleanly.
    if command -v pkill >/dev/null 2>&1; then pkill -TERM -P "$pid" 2>/dev/null || true; fi
    for ((i=0; i<12; i++)); do
        kill -0 "$pid" 2>/dev/null || return 0
        sleep 1
    done
    kill -KILL "$pid" 2>/dev/null || true
    if command -v pkill >/dev/null 2>&1; then pkill -KILL -P "$pid" 2>/dev/null || true; fi
}

monitor_loop() {
    while kill -0 "$$" 2>/dev/null; do
        local status restarts now
        status=$(cat "$STATUS_FILE" 2>/dev/null || echo unknown)
        restarts=$(cat "$RESTARTS_FILE" 2>/dev/null || echo 0)
        now=$(date +%s)
        printf '%s %s %s\n' "$now" "$status" "$restarts" > "$STATE_FILE.tmp" 2>/dev/null || true
        mv -f "$STATE_FILE.tmp" "$STATE_FILE" 2>/dev/null || true
        if [[ -f "$STOP_FILE" ]]; then
            stop_child_from_monitor
            break
        fi
        sleep 1
    done
}

CLEANUP_DONE=0
cleanup() {
    local code=${1:-0}
    (( CLEANUP_DONE == 0 )) || return 0
    CLEANUP_DONE=1
    trap - TERM INT HUP
    # Stop the child and its direct descendants before removing their PID files.
    if [[ -n "$CHILD_PID" ]] && kill -0 "$CHILD_PID" 2>/dev/null; then
        stop_child_from_monitor
        wait "$CHILD_PID" 2>/dev/null || true
    fi
    if [[ -n "$MONITOR_PID" ]]; then
        kill "$MONITOR_PID" 2>/dev/null || true
        wait "$MONITOR_PID" 2>/dev/null || true
    fi
    rm -f -- "$PID_FILE" "$CHILD_PID_FILE" "$STATE_FILE.tmp"
    printf '%s\n' "$FINAL_STATUS" > "$STATUS_FILE"
    printf '%s %s %s exit=%s\n' "$(date +%s)" "$FINAL_STATUS" "$(cat "$RESTARTS_FILE" 2>/dev/null || echo 0)" "$code" > "$STATE_FILE"
}
handle_signal() {
    touch "$STOP_FILE"
    FINAL_STATUS='stopped'
    cleanup 0
    exit 0
}
trap 'handle_signal' TERM INT HUP

monitor_loop &
MONITOR_PID=$!

exit_code=0
restart_delay=3
restarts=0
while [[ ! -f "$STOP_FILE" ]]; do
    rotate_log || true
    printf '\n=== %s starting: %s ===\n' "$(date)" "$START_COMMAND" >> "$LOG_FILE"
    printf 'running\n' > "$STATUS_FILE"

    # The command is provided by the manager as one argument; bash -c is kept
    # intentionally for compatibility with existing start.sh/custom commands.
    /bin/bash -c "$START_COMMAND" >> "$LOG_FILE" 2>&1 < /dev/null &
    CHILD_PID=$!
    printf '%s\n' "$CHILD_PID" > "$CHILD_PID_FILE"
    wait "$CHILD_PID"
    exit_code=$?
    CHILD_PID=''
    rm -f -- "$CHILD_PID_FILE"

    [[ -f "$STOP_FILE" ]] && break
    printf '=== exited with code %s ===\n' "$exit_code" >> "$LOG_FILE"
    [[ "$AUTO_RESTART" == 1 ]] || break
    if (( MAX_RESTARTS > 0 && restarts >= MAX_RESTARTS )); then
        FINAL_STATUS='restart-limit-reached'
        printf '%s\n' "$FINAL_STATUS" > "$STATUS_FILE"
        break
    fi

    restarts=$((restarts + 1))
    printf '%s\n' "$restarts" > "$RESTARTS_FILE"
    printf 'restarting\n' > "$STATUS_FILE"
    printf '=== restarting in %s seconds (restart %s) ===\n' "$restart_delay" "$restarts" >> "$LOG_FILE"
    for ((i=0; i<restart_delay; i++)); do
        [[ -f "$STOP_FILE" ]] && break
        sleep 1
    done
    restart_delay=$((restart_delay * 2))
    (( restart_delay > 60 )) && restart_delay=60
done

cleanup "$exit_code"
exit "$exit_code"
