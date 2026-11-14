#!/usr/bin/env bash
set -euo pipefail

cd "$(dirname "$0")"

SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/Library/Android/sdk}}"
ADB="$SDK/platform-tools/adb"
APK="${1:-dist/Less-v2.2.5-debug.apk}"

if [[ ! -x "$ADB" ]]; then
  echo "找不到 adb：$ADB" >&2
  echo "请设置 ANDROID_HOME，或先把 platform-tools 安装好。" >&2
  exit 1
fi

if [[ ! -f "$APK" ]]; then
  echo "找不到 APK：$APK" >&2
  echo "先执行：JAVA_HOME=/opt/homebrew/opt/openjdk@17 ./gradlew assembleDebug" >&2
  exit 1
fi

echo "等待设备…"
"$ADB" wait-for-device

echo "安装 $APK"
"$ADB" install -r "$APK"

echo "启动 Less"
"$ADB" shell am start -n com.kkorpse.less/.ui.MainActivity
