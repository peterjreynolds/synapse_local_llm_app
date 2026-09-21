#!/usr/bin/env bash
set -euo pipefail

: "${ANDROID_HOME:?ANDROID_HOME is required}"
: "${JAVA_HOME:?JAVA_HOME is required}"
repository_directory="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
distribution_apk="${1:?Pass the minified distribution APK path}"
probe_serial="${SYNAPSE_PROBE_SERIAL:-emulator-5554}"
probe_adb_port="${SYNAPSE_PROBE_ADB_PORT:-5037}"
probe_adb=("$ANDROID_HOME/platform-tools/adb" -P "$probe_adb_port" -s "$probe_serial")
test -f "$distribution_apk"
# Never replace an APK on a physical or implicitly selected user device.
test "$("${probe_adb[@]}" shell getprop ro.kernel.qemu | tr -d '\r')" = "1"

signal_probe="$(bash "$repository_directory/scripts/ci/build-packaged-signal-probe.sh")"
webrtc_probe="$(bash "$repository_directory/scripts/ci/build-packaged-webrtc-probe.sh")"
"${probe_adb[@]}" install --no-streaming -r -d "$distribution_apk"
"${probe_adb[@]}" install --no-streaming -r "$signal_probe"
"${probe_adb[@]}" install --no-streaming -r "$webrtc_probe"

for component in \
  app.synapse.privatechat.packagedprobe/app.synapse.privatechat.data.chat.PackagedSignalProbe \
  app.synapse.privatechat.webrtcprobe/app.synapse.privatechat.data.chat.PackagedWebRtcProbe; do
  "${probe_adb[@]}" logcat -c
  probe_receipt="$(timeout 180 "${probe_adb[@]}" shell am instrument -w -r "$component")"
  printf '%s\n' "$probe_receipt"
  # adb can exit zero even when the target's native process crashes.
  if ! (printf '%s\n' "$probe_receipt" | grep -F 'INSTRUMENTATION_RESULT: result=PASS:' >/dev/null && printf '%s\n' "$probe_receipt" | tr -d '\r' | grep -Fx 'INSTRUMENTATION_CODE: -1' >/dev/null); then
    printf '%s\n' '--- Android crash buffer ---' >&2
    "${probe_adb[@]}" logcat -b crash -d -v threadtime >&2 || true
    printf '%s\n' '--- Fatal runtime log tail ---' >&2
    "${probe_adb[@]}" logcat -d -v threadtime -t 500 '*:S' 'AndroidRuntime:E' 'libc:F' 'DEBUG:F' 'crash_dump64:I' 'crash_dump32:I' >&2 || true
    exit 1
  fi
done
