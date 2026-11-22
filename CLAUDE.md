# Android WebCam — instructions for Claude sessions

Android app that streams the rear camera to browsers on the local network (see README.md).
Talk to the user in Chinese. Develop directly on `main`.

## Release procedure (always automatic — never wait for the user to ask)

Every change that affects the app ends in a published release, in exactly this order:

1. Before pushing: raise `VERSION` following the version convention (README "版本号规范":
   PATCH for fixes, MINOR for compatible features, MAJOR for incompatible changes) and add the
   version's section to `CHANGELOG.md` (Chinese, written for the user). `tools/release-version.sh`
   and `./gradlew :core:test :app:testReleaseUnitTest :app:lintRelease` must pass.
2. Push to `main`. The workflow "Build and sign (draft release)" (`.github/workflows/release.yml`)
   builds and signs the APK and creates a **draft** release `vMAJOR.MINOR.PATCH`. Watch it with the
   GitHub MCP tools (`actions_list`, `get_job_logs`, `list_releases`). If it fails, fix and push again.
3. Test that commit in this environment: `EMULATOR_ACCEL=off tools/verify-release.sh`
   (no KVM here; drop the variable where KVM exists). It runs unit tests + lint, the browser
   end-to-end test, the cloud relay tests (local Workers runtime) and, on an Android 11 (API 30) emulator, the functional end-to-end test and the
   upgrade-install tests (in-place update keeps settings and TLS certificate; release build installs
   over the previous release; downgrade refused). `KEEP_EMULATOR=1` keeps the emulator for reruns.
4. Only when it ends with `ALL CHECKS PASSED`: run the workflow "Publish tested release"
   (`publish-release.yml`, input `tag`) with `mcp__github__actions_run_trigger`, confirm the release
   is published (not a draft), then tell the user the version and what changed.
5. If anything fails: fix it and push again with the same version (the new draft replaces the old
   one), repeat from step 3.
   Never publish a draft whose tests did not all pass.

Test-only, tool and documentation changes do not create drafts (see the `paths` filter).

## Invariants

- `applicationId` is `app.androidwebcam` forever, and the signing key never changes; otherwise
  updates cannot be installed over older versions. The key exists only in the GitHub secrets
  `ANDROID_KEYSTORE_BASE64` / `ANDROID_KEYSTORE_PASSWORD` (alias `androidwebcam`); the pinned
  certificate SHA-256 is in README.md and both workflows.
- Versions only increase and a published version is never reused; versionCode =
  MAJOR × 1000000 + MINOR × 1000 + PATCH (enforced by `tools/release-version.sh`).
- No tests in GitHub Actions, no Actions caches or artifacts (APKs go only to Releases).
- No personal information (user names, GitHub account) in code, package names or the APK.
- Rear camera only, picture never mirrored; LAN only (mobile data, VPN and loopback refused);
  no microphone, analytics or ads. No outbound connections, with one opt-in exception: with
  "cloud AI access" turned on, while streaming, one TLS connection to the user's own relay
  (`relay/worker.js`); the phone authenticates with its Keystore key and pictures are end-to-end
  encrypted per cloud agent, so the relay never sees them. Live video never leaves the LAN.
- Target device: OnePlus Nord N10 5G, Android 11 (API 30); minSdk 29.
