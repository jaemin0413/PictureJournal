#!/usr/bin/env bash
set -euo pipefail

PACKAGE='com.picturejournal.mobile'
ARTIFACTS_DIR="$PWD/../../artifacts/mobile"
METRO_PID=''

capture_artifacts() {
  mkdir -p "$ARTIFACTS_DIR"
  adb shell dumpsys activity activities > "$ARTIFACTS_DIR/android-activity-dump.txt"
  adb logcat -d > "$ARTIFACTS_DIR/android-share-logcat.txt"
  adb shell run-as "$PACKAGE" find shared_prefs -maxdepth 1 -type f -print > "$ARTIFACTS_DIR/android-secure-store-files.txt"
  adb exec-out screencap -p > "$ARTIFACTS_DIR/android-share-receipt.png"
}

capture_failure_diagnostics() {
  mkdir -p "$ARTIFACTS_DIR"
  adb shell dumpsys activity activities > "$ARTIFACTS_DIR/android-failure-activity-dump.txt" || true
  adb logcat -d > "$ARTIFACTS_DIR/android-failure-logcat.txt" || true
  adb shell run-as "$PACKAGE" find shared_prefs -maxdepth 1 -type f -print > "$ARTIFACTS_DIR/android-failure-secure-store-files.txt" || true
  adb exec-out screencap -p > "$ARTIFACTS_DIR/android-failure-screenshot.png" || true
}

validate_artifacts() {
  local artifact
  for artifact in \
    android-resolved-share-activity.txt \
    android-share-start.txt \
    android-metro.log \
    android-activity-dump.txt \
    android-share-logcat.txt \
    android-secure-store-files.txt \
    android-share-receipt.png; do
    test -s "$ARTIFACTS_DIR/$artifact"
  done
  node scripts/verify-png.mjs "$ARTIFACTS_DIR/android-share-receipt.png"
}

cleanup() {
  local status=$?
  local metro_status=0
  trap - EXIT
  if [[ -n "$METRO_PID" ]]; then
    if kill -0 "$METRO_PID" 2>/dev/null; then
      kill "$METRO_PID" 2>/dev/null || true
      wait "$METRO_PID" 2>/dev/null || true
    else
      if wait "$METRO_PID" 2>/dev/null; then
        metro_status=0
      else
        metro_status=$?
      fi
      if [[ "$status" -eq 0 ]]; then
        if [[ "$metro_status" -eq 0 ]]; then
          status=1
        else
          status="$metro_status"
        fi
      fi
    fi
  fi
  if [[ "$status" -ne 0 ]]; then
    capture_failure_diagnostics || true
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
share_received=0
for attempt in $(seq 1 60); do
  if grep -q 'PICTUREJOURNAL_SHARE_RECEIVED' "$ARTIFACTS_DIR/android-metro.log"; then
    share_received=1
    break
  fi
  kill -0 "$METRO_PID"
  sleep 2
done
test "$share_received" -eq 1
kill -0 "$METRO_PID"
curl -fsS http://127.0.0.1:8081/status
capture_artifacts
validate_artifacts
grep -q "$PACKAGE" "$ARTIFACTS_DIR/android-activity-dump.txt"
grep -q 'SecureStore' "$ARTIFACTS_DIR/android-secure-store-files.txt"
grep -q 'PICTUREJOURNAL_SHARE_RECEIVED' "$ARTIFACTS_DIR/android-metro.log"
