# Android WebCam — instructions for Claude sessions

Android app that streams the rear camera to browsers on the local network (see README.md).
Talk to the user in Chinese. Develop directly on `main`.

## Release procedure (always automatic — never wait for the user to ask)

Every change that affects the app ends in a published release, in exactly this order:

1. Before pushing: `./gradlew :core:test :app:testReleaseUnitTest :app:lintRelease` must pass.
2. Push to `main`. The workflow "Build and sign (draft release)" (`.github/workflows/release.yml`)
   builds and signs the APK and creates a **draft** release `vMAJOR.MINOR.CODE`. Watch it with the
   GitHub MCP tools (`actions_list`, `get_job_logs`, `list_releases`). If it fails, fix and push again.
3. Test that commit in this environment: `EMULATOR_ACCEL=off tools/verify-release.sh`
   (no KVM here; drop the variable where KVM exists). It runs unit tests + lint, the browser
   end-to-end test and, on an Android 11 (API 30) emulator, the functional end-to-end test and the
   upgrade-install tests (in-place update keeps settings and TLS certificate; release build installs
   over the previous release; downgrade refused). `KEEP_EMULATOR=1` keeps the emulator for reruns.
4. Only when it ends with `ALL CHECKS PASSED`: run the workflow "Publish tested release"
   (`publish-release.yml`, input `tag`) with `mcp__github__actions_run_trigger`, confirm the release
   is published (not a draft), then tell the user the version and what changed.
5. If anything fails: fix it, push again (the new draft replaces the old one), repeat from step 3.
   Never publish a draft whose tests did not all pass.

Test-only, tool and documentation changes do not create drafts (see the `paths` filter).

## Invariants

- `applicationId` is `app.androidwebcam` forever, and the signing key never changes; otherwise
  updates cannot be installed over older versions. The key exists only in the GitHub secrets
  `ANDROID_KEYSTORE_BASE64` / `ANDROID_KEYSTORE_PASSWORD` (alias `androidwebcam`); the pinned
  certificate SHA-256 is in README.md and both workflows.
- versionCode only increases (CI: commit count, never below the newest `v*` tag).
- No tests in GitHub Actions, no Actions caches or artifacts (APKs go only to Releases).
- No personal information (user names, GitHub account) in code, package names or the APK.
- Rear camera only, picture never mirrored; LAN only (mobile data, VPN and loopback refused);
  no microphone, analytics, ads or outbound connections.
- Target device: OnePlus Nord N10 5G, Android 11 (API 30); minSdk 29.
