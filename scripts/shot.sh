#!/bin/bash
# 抓模拟器截图: ./scripts/shot.sh <名字>
# 需要环境变量 ANDROID_HOME 指向本机 Android SDK。
export PATH="${ANDROID_HOME:-}/platform-tools:$PATH"
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
mkdir -p "$ROOT/.shots"
adb exec-out screencap -p > "$ROOT/.shots/${1:-shot}.png" && echo "$ROOT/.shots/${1:-shot}.png"
