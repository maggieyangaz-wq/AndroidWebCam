#!/usr/bin/env bash
# Complete verification of a release candidate. Run it on the commit of a draft release;
# publish the draft only if it ends with "ALL CHECKS PASSED". Steps:
#   0. version convention (VERSION raised for APK changes, CHANGELOG.md entry; README "版本号规范")
#   1. unit tests (core + app) and Android Lint
#   2. browser end-to-end test of the viewer page, and the cloud relay (tools/run-relay-tests.sh)
#   3. Android 11 and Android 6.0 emulators: functional end-to-end test and upgrade-install tests
# Without KVM: EMULATOR_ACCEL=off tools/verify-release.sh
set -euo pipefail
cd "$(dirname "$0")/.."

echo "== Version"
git fetch -q --tags origin 2> /dev/null || true
tools/release-version.sh

echo "== Unit tests and Android Lint"
./gradlew -q --console=plain :core:test :app:testReleaseUnitTest :app:lintRelease

echo "== Browser end-to-end test"
bash tools/run-browser-tests.sh

echo "== Cloud relay (local Workers runtime)"
bash tools/run-relay-tests.sh

echo "== Android emulator tests: Android 11 (API 30, the main target phone)"
API_LEVEL=30 bash tools/run-device-tests.sh

echo "== Android emulator tests: Android 6.0 (API 23, the oldest supported version)"
API_LEVEL=23 bash tools/run-device-tests.sh

echo "ALL CHECKS PASSED"
