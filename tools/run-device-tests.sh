#!/usr/bin/env bash
# Boots a headless Android emulator (default: Android 11 / API 30, like the target phone)
# and runs the instrumented end-to-end test (app/src/androidTest).
# Needs ANDROID_HOME with cmdline-tools and the emulator's system libraries
# (libpulse0, libnss3, libx* ...). Without KVM, run with EMULATOR_ACCEL=off
# (software emulation: slow, so boot and test timeouts are scaled up).
set -euo pipefail

API="${API_LEVEL:-30}"
IMAGE="system-images;android-${API};default;x86_64"
SDK="${ANDROID_HOME:?ANDROID_HOME is not set}"
BIN="$SDK/cmdline-tools/latest/bin"
ADB="$SDK/platform-tools/adb"
LOG="${RUNNER_TEMP:-${TMPDIR:-/tmp}}/emulator.log"
ACCEL="${EMULATOR_ACCEL:-auto}"
if [ "$ACCEL" = off ]; then
  export ANDROID_I_WANT_MY_TCG=yes # the emulator refuses software CPU emulation unless forced
  BOOT_TIMEOUT="${BOOT_TIMEOUT_SECONDS:-3600}"
  TIMEOUT_SCALE="${TIMEOUT_SCALE:-8}"
  CORES=4
  MEMORY=4096
else
  BOOT_TIMEOUT="${BOOT_TIMEOUT_SECONDS:-750}"
  TIMEOUT_SCALE="${TIMEOUT_SCALE:-1}"
  CORES=2
  MEMORY=3072
fi
# avdmanager and the emulator must agree on where AVDs live.
export ANDROID_AVD_HOME="${ANDROID_AVD_HOME:-$HOME/.android/avd}"
mkdir -p "$ANDROID_AVD_HOME"

yes 2> /dev/null | "$BIN/sdkmanager" --licenses > /dev/null 2>&1 || true
"$BIN/sdkmanager" --install "emulator" "platform-tools" "$IMAGE" > /dev/null
echo no | "$BIN/avdmanager" create avd --force --name awc --package "$IMAGE" --device pixel_5 > /dev/null
"$SDK/emulator/emulator" -list-avds | grep -qx awc || { echo "AVD was not created in $ANDROID_AVD_HOME"; exit 1; }
"$SDK/emulator/emulator" -accel-check || true

"$ADB" start-server > /dev/null 2>&1
"$SDK/emulator/emulator" -avd awc -no-window -no-audio -no-boot-anim -no-snapshot -no-metrics -wipe-data \
  -gpu swiftshader_indirect -camera-back emulated -camera-front none -memory "$MEMORY" -cores "$CORES" \
  -accel "$ACCEL" > "$LOG" 2>&1 &
EMULATOR_PID=$!

cleanup() {
  local status=$?
  if [ "$status" -ne 0 ]; then
    echo "----- emulator log (tail) -----"
    tail -n 60 "$LOG" 2> /dev/null || true
  fi
  "$ADB" emu kill > /dev/null 2>&1 || kill "$EMULATOR_PID" 2> /dev/null || true
}
trap cleanup EXIT

echo "Waiting for the emulator to boot..."
booted=false
for i in $(seq 1 $((BOOT_TIMEOUT / 5))); do
  if ! kill -0 "$EMULATOR_PID" 2> /dev/null; then
    echo "The emulator process exited."
    exit 1
  fi
  if [ "$("$ADB" shell getprop sys.boot_completed 2> /dev/null | tr -d '\r')" = "1" ]; then
    booted=true
    break
  fi
  if [ $((i % 12)) -eq 0 ]; then echo "  still booting after $((i * 5)) s: $("$ADB" devices | tail -n +2 | tr '\n' ' ')"; fi
  sleep 5
done
if [ "$booted" != true ]; then
  echo "The emulator did not finish booting in time."
  exit 1
fi
timeout 600 bash -c "until '$ADB' shell pm list packages > /dev/null 2>&1; do sleep 2; done"
for s in window_animation_scale transition_animation_scale animator_duration_scale; do
  "$ADB" shell settings put global "$s" 0
done
"$ADB" shell svc wifi enable > /dev/null 2>&1 || true
# The app only serves on LAN interfaces (Wi-Fi), never on the emulated mobile network.
timeout 120 bash -c "until '$ADB' shell ip -4 addr show wlan0 2> /dev/null | grep -q 'inet '; do sleep 2; done" \
  || echo "warning: wlan0 has no IPv4 address"
"$ADB" shell ip -4 addr 2> /dev/null | grep -E '^[0-9]+:|inet ' || true
"$ADB" shell wm dismiss-keyguard > /dev/null 2>&1 || true
"$ADB" shell input keyevent 82 > /dev/null 2>&1 || true
echo "Emulator ready: Android $("$ADB" shell getprop ro.build.version.release | tr -d '\r') (API $API)"

set +e
./gradlew --stacktrace :app:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.timeoutScale="$TIMEOUT_SCALE"
status=$?
set -e

echo "----- test output (logcat) -----"
"$ADB" logcat -d -v brief -s "System.out:I" "AndroidWebCam:*" "AndroidRuntime:E" "TestRunner:*" 2> /dev/null | tail -n 150 || true
exit "$status"
