#!/usr/bin/env bash
# Complete verification of a release candidate. Run it on the commit of a draft release;
# publish the draft only if it ends with "ALL CHECKS PASSED". Steps:
#   1. unit tests (core + app) and Android Lint
#   2. browser end-to-end test of the viewer page
#   3. Android 11 emulator: functional end-to-end test and upgrade-install tests
# Without KVM: EMULATOR_ACCEL=off tools/verify-release.sh
set -euo pipefail
cd "$(dirname "$0")/.."

echo "== Unit tests and Android Lint"
./gradlew -q --console=plain :core:test :app:testReleaseUnitTest :app:lintRelease

echo "== Browser end-to-end test"
bash tools/run-browser-tests.sh

echo "== Android emulator tests"
bash tools/run-device-tests.sh

echo "ALL CHECKS PASSED"
