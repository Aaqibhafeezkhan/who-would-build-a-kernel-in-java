#!/usr/bin/env bash
# Boots headless and passes once MARKER shows up on the serial port. Fails on a kernel panic,
# on QEMU exiting early (a triple fault exits because of -no-reboot), or after TIMEOUT seconds.
# On a timeout or an early exit it says where each CPU was (Postmortem.java).
set -euo pipefail
esp=$1 log=$2 marker=$3 timeout=${4:-120}
tools=$(dirname "$0")
# Unix socket paths max out at 108 bytes, which a CI checkout path alone can come close to.
monitor=$(mktemp -d)/monitor.sock
qemu_log=${log%.*}.qemu.log

rm -f "$log" "$qemu_log"
"$tools/qemu.sh" "$esp" -serial file:"$log" -monitor unix:"$monitor",server=on,wait=off \
    -d guest_errors,cpu_reset,unimp -D "$qemu_log" &
pid=$!
trap 'kill $pid 2>/dev/null || true; rm -rf "$(dirname "$monitor")"' EXIT

result=timeout
for ((i = 0; i < timeout * 10; i++)); do
    if grep -q "$marker" "$log" 2>/dev/null; then
        result=pass
        break
    fi
    if grep -q "PANIC" "$log" 2>/dev/null; then
        result=panic
        break
    fi
    if ! kill -0 $pid 2>/dev/null; then
        result=qemu-exited
        break
    fi
    sleep 0.1
done

# Strip firmware screen-control noise so the log reads cleanly in CI.
tr -d '\r' < "$log" | sed -e 's/\x1b\[[0-9;=]*[A-Za-z]//g' || true
echo "boot-test: $result"
if [[ $result == timeout || $result == qemu-exited ]]; then
    java "$tools/Postmortem.java" "$monitor" "$(dirname "$esp")/kernel.map" "$qemu_log" || true
fi
[[ $result == pass ]]
