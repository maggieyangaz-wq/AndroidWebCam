#!/usr/bin/env bash
# Android emulator tests for a release candidate (default: Android 11 / API 30, like the target phone):
#   1. functional end-to-end test (StreamingDeviceTest): service, camera, GL, H.264, MJPEG and the
#      TLS 1.3 server with its Keystore key, used over the device's LAN address like a browser;
#      cloud access (CloudDeviceTest): Keystore signature for the relay, protected agent keys;
#   2. upgrade install keeps data (UpgradeDeviceTest, debug build N -> N+1 with `adb install -r`):
#      in-place update, settings kept, same TLS certificate, streaming still works;
#   3. a release build installs over the previous release (built from the latest v* tag) and
#      downgrades are refused (release builds signed with a throwaway key).
#
# Drives the device directly with adb / am instrument (robust on slow, software-emulated devices).
# Reuses a running emulator; otherwise boots one, from the "awc_clean" snapshot when available.
# Needs ANDROID_HOME (cmdline-tools) and the emulator's system libraries (libpulse0, libnss3, libx*).
# Without KVM use EMULATOR_ACCEL=off (software emulation; boot and test timeouts are scaled up).
# KEEP_EMULATOR=1 leaves an emulator started by this script running.
set -euo pipefail
cd "$(dirname "$0")/.."

API="${API_LEVEL:-30}"
IMAGE="system-images;android-${API};default;x86_64"
SDK="${ANDROID_HOME:?ANDROID_HOME is not set}"
BIN="$SDK/cmdline-tools/latest/bin"
ADB="$SDK/platform-tools/adb"
WORK="$(mktemp -d)"
LOG="$WORK/emulator.log"
SNAPSHOT=awc_clean
DEBUG_PKG=app.androidwebcam.debug
RELEASE_PKG=app.androidwebcam
RUNNER="$DEBUG_PKG.test/androidx.test.runner.AndroidJUnitRunner"
export ANDROID_AVD_HOME="${ANDROID_AVD_HOME:-$HOME/.android/avd}" # avdmanager and emulator must agree
mkdir -p "$ANDROID_AVD_HOME"

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
STARTED_EMULATOR=false

log() { echo "== $*"; }

cleanup() {
  local status=$?
  if [ "$status" -ne 0 ] && [ -s "$LOG" ]; then
    echo "----- emulator log (tail) -----"
    tail -n 40 "$LOG" || true
  fi
  if [ "$STARTED_EMULATOR" = true ] && [ "${KEEP_EMULATOR:-0}" != 1 ]; then
    "$ADB" emu kill > /dev/null 2>&1 || true
  fi
  git worktree remove --force "$WORK/prev" > /dev/null 2>&1 || true
  rm -rf "$WORK"
}
trap cleanup EXIT

device_ready() { [ "$("$ADB" shell getprop sys.boot_completed 2> /dev/null | tr -d '\r')" = 1 ]; }

# Waits up to $1 seconds for an IPv4 address on the emulator's Wi-Fi interface.
wifi_ready() {
  timeout "$1" bash -c "until '$ADB' shell ip -4 addr show wlan0 2> /dev/null | grep -q 'inet '; do sleep 2; done"
}

prepare_device() {
  timeout 600 bash -c "until '$ADB' shell pm list packages > /dev/null 2>&1; do sleep 2; done"
  for s in window_animation_scale transition_animation_scale animator_duration_scale; do
    "$ADB" shell settings put global "$s" 0
  done
  "$ADB" shell settings put global verifier_verify_adb_installs 0 > /dev/null 2>&1 || true
  "$ADB" shell svc wifi enable > /dev/null 2>&1 || true
  # The app only serves on LAN interfaces (Wi-Fi), never on the emulated mobile network. After a
  # cold boot or a snapshot restore the emulator does not always rejoin its virtual Wi-Fi by itself.
  if ! wifi_ready 60; then
    echo "  joining the emulator's Wi-Fi (AndroidWifi)"
    "$ADB" shell cmd wifi connect-network AndroidWifi open > /dev/null 2>&1 || true
    if ! wifi_ready 300; then
      echo "The emulator's Wi-Fi did not connect (the app serves only on LAN interfaces)."
      exit 1
    fi
  fi
  "$ADB" shell wm dismiss-keyguard > /dev/null 2>&1 || true
  "$ADB" shell input keyevent 82 > /dev/null 2>&1 || true
  for p in "$DEBUG_PKG" "$DEBUG_PKG.test" "$RELEASE_PKG"; do "$ADB" uninstall "$p" > /dev/null 2>&1 || true; done
}

boot_emulator() {
  yes 2> /dev/null | "$BIN/sdkmanager" --licenses > /dev/null 2>&1 || true
  "$BIN/sdkmanager" --install "emulator" "platform-tools" "$IMAGE" > /dev/null
  if ! "$SDK/emulator/emulator" -list-avds | grep -qx awc; then
    echo no | "$BIN/avdmanager" create avd --force --name awc --package "$IMAGE" --device pixel_5 > /dev/null
  fi
  local fresh=true
  local boot_args=(-no-snapshot-load -wipe-data)
  if [ -d "$ANDROID_AVD_HOME/awc.avd/snapshots/$SNAPSHOT" ]; then
    fresh=false
    boot_args=(-snapshot "$SNAPSHOT")
  fi
  log "Starting emulator (accel=$ACCEL, $([ "$fresh" = true ] && echo "cold boot" || echo "snapshot $SNAPSHOT"))"
  "$ADB" start-server > /dev/null 2>&1
  "$SDK/emulator/emulator" -avd awc -no-window -no-audio -no-boot-anim -no-metrics -no-snapshot-save "${boot_args[@]}" \
    -gpu swiftshader_indirect -camera-back emulated -camera-front none -memory "$MEMORY" -cores "$CORES" \
    -accel "$ACCEL" > "$LOG" 2>&1 &
  local pid=$!
  STARTED_EMULATOR=true
  for i in $(seq 1 $((BOOT_TIMEOUT / 5))); do
    if ! kill -0 "$pid" 2> /dev/null; then
      echo "The emulator process exited."
      exit 1
    fi
    device_ready && break
    if [ $((i % 12)) -eq 0 ]; then echo "  still booting after $((i * 5)) s"; fi
    sleep 5
  done
  if ! device_ready; then
    echo "The emulator did not finish booting in time."
    exit 1
  fi
  if [ "$fresh" = false ] && grep -q "Failed to load snapshot" "$LOG"; then
    echo "  snapshot $SNAPSHOT could not be loaded (cold boot instead); it will be saved again"
    "$ADB" emu avd snapshot delete "$SNAPSHOT" > /dev/null 2>&1 || true
    fresh=true
  fi
  prepare_device
  if [ "$fresh" = true ]; then
    log "Saving snapshot $SNAPSHOT for faster runs"
    "$ADB" emu avd snapshot save "$SNAPSHOT" > /dev/null 2>&1 || echo "warning: snapshot not saved"
  fi
}

# Runs one instrumented test; prints the runner output; fails unless it reports OK.
instrument() {
  local target=$1
  shift
  local out
  out=$("$ADB" shell am instrument -w -e class "$target" -e timeoutScale "$TIMEOUT_SCALE" "$@" "$RUNNER" 2>&1 | tr -d '\r') || true
  echo "$out" | grep -vE '^\s*$' | tail -n 25
  grep -q '^OK (' <<< "$out"
}

install_apk() {
  local out
  out=$("$ADB" install "$@" 2>&1 | tr -d '\r') || true
  echo "  adb install $*: $(echo "$out" | tail -n 1)"
  grep -q '^Success' <<< "$out"
}

package_field() {
  "$ADB" shell dumpsys package "$1" 2> /dev/null | tr -d '\r' > "$WORK/dumpsys.txt" || true
  { grep -oE "$2=[^ ]+( [0-9:]+)?" "$WORK/dumpsys.txt" | head -n 1 | cut -d= -f2-; } || true
}

fail() {
  echo "FAILED: $*"
  "$ADB" logcat -d -v brief -s "AndroidWebCam:*" "AndroidRuntime:E" "System.out:I" 2> /dev/null | tail -n 60 || true
  exit 1
}

# ------------------------------------------------------------------------------ device
if [ "$("$ADB" get-state 2> /dev/null || true)" = device ] && device_ready; then
  log "Using the running emulator/device"
  prepare_device
else
  boot_emulator
fi
echo "Device: Android $("$ADB" shell getprop ro.build.version.release | tr -d '\r') (API $("$ADB" shell getprop ro.build.version.sdk | tr -d '\r'))"

# ------------------------------------------------------------------------------ builds
log "Building debug APKs (versionCode 100 and 101) and the test APK"
./gradlew -q --console=plain :app:assembleDebug :app:assembleDebugAndroidTest -PawcVersionCode=100 -PawcVersionName=test-100
cp app/build/outputs/apk/debug/app-debug.apk "$WORK/debug-100.apk"
cp app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk "$WORK/debug-test.apk"
./gradlew -q --console=plain :app:assembleDebug -PawcVersionCode=101 -PawcVersionName=test-101
cp app/build/outputs/apk/debug/app-debug.apk "$WORK/debug-101.apk"

install_apk "$WORK/debug-100.apk" || fail "install debug APK"
install_apk "$WORK/debug-test.apk" || fail "install test APK"

# ------------------------------------------------------------------------------ 1
log "1/3 Functional end-to-end test"
"$ADB" logcat -c || true
instrument app.androidwebcam.StreamingDeviceTest,app.androidwebcam.CloudDeviceTest || fail "functional end-to-end test"

# ------------------------------------------------------------------------------ 2
log "2/3 Upgrade install keeps settings and TLS identity (debug 100 -> 101)"
"$ADB" logcat -c || true
instrument "app.androidwebcam.UpgradeDeviceTest#prepareBeforeUpgrade" -e upgradePhase prepare || fail "upgrade prepare"
fingerprint=$({ "$ADB" logcat -d -s "System.out:I" 2> /dev/null | tr -d '\r' \
  | grep -oE 'AWC-TEST upgrade-fingerprint=[0-9A-F:]+' | tail -n 1 | cut -d= -f2; } || true)
[ -n "$fingerprint" ] || fail "certificate fingerprint before the upgrade not reported"
echo "  certificate before upgrade: $fingerprint"
install_apk -r "$WORK/debug-101.apk" || fail "in-place upgrade install (debug)"
instrument "app.androidwebcam.UpgradeDeviceTest#verifyAfterUpgrade" -e upgradePhase verify -e expectedVersionCode 101 \
  -e expectedFingerprint "$fingerprint" || fail "state after upgrade"

# ------------------------------------------------------------------------------ 3
log "3/3 Release build installs over the previous release; downgrade refused"
export ANDROID_KEYSTORE_PASSWORD
ANDROID_KEYSTORE_PASSWORD=$(od -An -tx1 -N16 /dev/urandom | tr -d ' \n') # no early-closing pipe (pipefail)
keytool -genkeypair -storetype PKCS12 -keystore "$WORK/test.p12" -alias androidwebcam -keyalg EC -groupname secp256r1 \
  -validity 30 -dname "CN=Upgrade Test" -storepass "$ANDROID_KEYSTORE_PASSWORD" -keypass "$ANDROID_KEYSTORE_PASSWORD" > /dev/null 2>&1
export ANDROID_KEYSTORE_FILE="$WORK/test.p12" ANDROID_KEY_ALIAS=androidwebcam
git fetch -q --tags origin 2> /dev/null || true
previous=$(git describe --tags --abbrev=0 --match 'v*' 2> /dev/null || true)
if [ -n "$previous" ]; then
  echo "  previous release: $previous"
  git worktree add -q --detach "$WORK/prev" "$previous"
  (cd "$WORK/prev" && ./gradlew -q --console=plain :app:assembleRelease -PawcVersionCode=200 -PawcVersionName=previous)
  cp "$WORK/prev/app/build/outputs/apk/release/app-release.apk" "$WORK/release-200.apk"
  git worktree remove --force "$WORK/prev"
else
  echo "  no previous release yet: using the current code as the old version"
  ./gradlew -q --console=plain :app:assembleRelease -PawcVersionCode=200 -PawcVersionName=previous
  cp app/build/outputs/apk/release/app-release.apk "$WORK/release-200.apk"
fi
./gradlew -q --console=plain :app:assembleRelease -PawcVersionCode=201 -PawcVersionName=candidate
cp app/build/outputs/apk/release/app-release.apk "$WORK/release-201.apk"
unset ANDROID_KEYSTORE_FILE ANDROID_KEYSTORE_PASSWORD ANDROID_KEY_ALIAS

install_apk "$WORK/release-200.apk" || fail "install previous release"
"$ADB" shell am start -W -n "$RELEASE_PKG/.MainActivity" > /dev/null || fail "launch previous release"
first_installed=$(package_field "$RELEASE_PKG" firstInstallTime)
install_apk -r "$WORK/release-201.apk" || fail "upgrade install over the previous release"
[ "$(package_field "$RELEASE_PKG" versionCode)" = 201 ] || fail "versionCode after upgrade"
[ "$(package_field "$RELEASE_PKG" firstInstallTime)" = "$first_installed" ] || fail "upgrade must not reinstall"
"$ADB" logcat -b crash -c > /dev/null 2>&1 || true
"$ADB" shell am start -W -n "$RELEASE_PKG/.MainActivity" > /dev/null || fail "launch upgraded release"
sleep $((5 * TIMEOUT_SCALE))
[ -n "$("$ADB" shell pidof "$RELEASE_PKG" | tr -d '\r')" ] || fail "upgraded release is not running"
if "$ADB" logcat -d -b crash 2> /dev/null | grep -q "$RELEASE_PKG"; then fail "upgraded release crashed"; fi
if install_apk -r "$WORK/release-200.apk"; then fail "downgrade must be refused"; fi
echo "  downgrade refused as expected"
"$ADB" uninstall "$RELEASE_PKG" > /dev/null 2>&1 || true

log "ALL DEVICE TESTS PASSED"
