#!/usr/bin/env bash
# Browser end-to-end test of the viewer page, and of the AI bridge (MCP server), against the JVM
# dev server (no phone needed).
# Needs ffmpeg (libx264) and Node with Playwright + Chromium.
set -euo pipefail
cd "$(dirname "$0")/.."

PORT="${PORT:-18443}"
WORK="$(mktemp -d)"
cleanup() {
  pkill -f DevServerKt > /dev/null 2>&1 || true
  rm -rf "$WORK"
}
trap cleanup EXIT

# Test media with asymmetric text, so a mirrored picture would be obvious in screenshots.
FONT=$(fc-match -f '%{file}' 'DejaVu Sans:bold' 2> /dev/null || true)
TEXT=""
if [ -n "$FONT" ]; then TEXT=",drawtext=fontfile=$FONT:text='LEFT  ABC 123  RIGHT >':fontsize=48:fontcolor=white:x=30:y=30:box=1:boxcolor=black@0.8"; fi
mkdir -p "$WORK/jpeg"
ffmpeg -hide_banner -loglevel error -y -f lavfi -i testsrc2=size=1280x720:rate=30 -vf "null$TEXT" -t 4 \
  -c:v libx264 -profile:v high -pix_fmt yuv420p -bf 0 -g 30 -x264-params aud=1:repeat-headers=1 -f h264 "$WORK/test.h264"
ffmpeg -hide_banner -loglevel error -y -f lavfi -i testsrc2=size=640x360:rate=10 -vf "null$TEXT" -t 2 -q:v 5 "$WORK/jpeg/f_%03d.jpg"

./gradlew -q --console=plain :core:devServer --args="$PORT $WORK/test.h264 $WORK/jpeg $WORK/info.json" > "$WORK/server.log" 2>&1 &
for _ in $(seq 1 180); do
  [ -s "$WORK/info.json" ] && break
  sleep 1
done
if [ ! -s "$WORK/info.json" ]; then
  cat "$WORK/server.log"
  exit 1
fi
node tools/viewer-e2e.cjs "$PORT" "$WORK/info.json" "${SCREENSHOT_DIR:-$WORK}"
node tools/ai-bridge-e2e.mjs "$PORT" "$WORK/info.json" "${SCREENSHOT_DIR:-$WORK}"
