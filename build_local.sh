#!/usr/bin/env bash
set -euo pipefail
if ! command -v gradle >/dev/null 2>&1; then
  echo "Gradle 9.6 is required (or open this project in Android Studio)." >&2
  exit 1
fi
gradle :app:assembleDebug
printf '\nAPK: app/build/outputs/apk/debug/app-debug.apk\n'
