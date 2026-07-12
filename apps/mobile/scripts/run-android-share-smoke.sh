#!/usr/bin/env bash
set -euo pipefail

PACKAGE='com.picturejournal.mobile'
ARTIFACTS_DIR="$PWD/../../artifacts/mobile"
METRO_PID=''

capture_artifacts() {
  mkdir -p "$ARTIFACTS_DIR"
  adb shell dumpsys activity activities > "$ARTIFACTS_DIR/android-activity-dump.txt" || true
  adb logcat -d > "$ARTIFACTS_DIR/android-share-logcat.txt" || true
  adb shell run-as "$PACKAGE" sh -c 'find shared_prefs -maxdepth 1 -type f -print' > "$ARTIFACTS_DIR/android-secure-store-files.txt" || true
}

cleanup() {
  local status=$?
  capture_artifacts
  if [[ -n "$METRO_PID" ]]; then
    kill "$METRO_PID" 2>/dev/null || true
    wait "$METRO_PID" 2>/dev/null || true
  fi
  exit "$status"
}
trap cleanup EXIT

mkdir -p "$ARTIFACTS_DIR"
adb install android/app/build/outputs/apk/debug/app-debug.apk
adb shell cmd package query-activities --brief -a android.intent.action.SEND -t text/plain "$PACKAGE" | tee "$ARTIFACTS_DIR/android-resolved-share-activity.txt"
grep -q "$PACKAGE" "$ARTIFACTS_DIR/android-resolved-share-activity.txt"

CI=1 npx expo start --dev-client --port 8081 > "$ARTIFACTS_DIR/android-metro.log" 2>&1 &
METRO_PID=$!
metro_ready=0
for attempt in $(seq 1 30); do
  if curl -fsS http://127.0.0.1:8081/status; then
    metro_ready=1
    break
  fi
  sleep 2
done
test "$metro_ready" -eq 1

adb reverse tcp:8081 tcp:8081
adb logcat -c
adb shell am force-stop "$PACKAGE"
adb shell am start -W -a android.intent.action.SEND -t text/plain -p "$PACKAGE" --es android.intent.extra.TEXT 'place: Cafe Onion from GitHub Actions' --es android.intent.extra.TITLE 'Cafe Onion' | tee "$ARTIFACTS_DIR/android-share-start.txt"
sleep 10
capture_artifacts

grep -q "$PACKAGE" "$ARTIFACTS_DIR/android-activity-dump.txt"
grep -q 'SecureStore' "$ARTIFACTS_DIR/android-secure-store-files.txt"
grep -q 'PICTUREJOURNAL_SHARE_RECEIVED' "$ARTIFACTS_DIR/android-metro.log"
