#!/usr/bin/env bash
# Release version of the current commit, checked against the version convention (README, "版本号规范"):
#   - VERSION holds MAJOR.MINOR.PATCH (semantic versioning; MINOR and PATCH below 1000);
#   - a released version is never reused: once the APK changes, VERSION must be raised;
#   - versions only increase, and every version has a section in CHANGELOG.md;
#   - versionCode = MAJOR * 1000000 + MINOR * 1000 + PATCH (so it only increases, too).
# Prints key=value lines (for $GITHUB_OUTPUT): skip=true when this version is already released
# and nothing in the APK changed since; otherwise skip=false, name, code and tag.
# Exits with an explanation when the convention is not met.
set -euo pipefail
cd "$(dirname "$0")/.."

# Everything that ends up in the APK (tests, tools and documentation do not).
APK_PATHS=(app/src/main app/build.gradle.kts app/proguard-rules.pro core/src/main core/build.gradle.kts
  gradle build.gradle.kts settings.gradle.kts gradle.properties gradlew)

fail() {
  echo "::error::$*" >&2
  exit 1
}

version=$(tr -d '[:space:]' < VERSION)
if ! [[ "$version" =~ ^([1-9][0-9]{0,2})\.(0|[1-9][0-9]{0,2})\.(0|[1-9][0-9]{0,2})$ ]]; then
  fail "VERSION must be MAJOR.MINOR.PATCH with numbers below 1000 (e.g. 1.2.0), not '$version'."
fi
major=${BASH_REMATCH[1]}
minor=${BASH_REMATCH[2]}
patch=${BASH_REMATCH[3]}
code=$((major * 1000000 + minor * 1000 + patch))
tag="v$version"

if git rev-parse -q --verify "refs/tags/$tag" > /dev/null; then
  if git diff --quiet "$tag" HEAD -- "${APK_PATHS[@]}"; then
    echo "$tag is already released and the APK has not changed since: nothing to release." >&2
    echo "skip=true"
    exit 0
  fi
  fail "$tag is already released, but the APK changed since. Raise VERSION (PATCH: fixes, MINOR: new features, MAJOR: incompatible changes) and add its CHANGELOG.md section."
fi

newest=$(git tag --list 'v*' | sed 's/^v//' | grep -E '^[0-9]+\.[0-9]+\.[0-9]+$' | sort -V | tail -n 1 || true)
if [ -n "$newest" ] && [ "$(printf '%s\n%s\n' "$newest" "$version" | sort -V | tail -n 1)" != "$version" ]; then
  fail "VERSION $version is lower than the newest release v$newest: versions only increase."
fi

if ! awk -v heading="## [$version]" 'index($0, heading) == 1 { found = 1 } END { exit !found }' CHANGELOG.md; then
  fail "CHANGELOG.md has no section for $version (a line starting with '## [$version]')."
fi

echo "skip=false"
echo "name=$version"
echo "code=$code"
echo "tag=$tag"
