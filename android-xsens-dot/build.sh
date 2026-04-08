#!/bin/bash
# 构建 APK（在 Cursor 终端运行）
# 用法: ./build.sh

cd "$(dirname "$0")"

# 加载环境变量
[ -f "$HOME/.zshrc" ] && source "$HOME/.zshrc" 2>/dev/null
export ANDROID_HOME="${ANDROID_HOME:-$HOME/Library/Android/sdk}"
export PATH="$PATH:$ANDROID_HOME/platform-tools:$ANDROID_HOME/cmdline-tools/latest/bin"

echo "构建 Xsens DOT Android..."
./gradlew assembleDebug

echo ""
echo "APK 位置: app/build/outputs/apk/debug/app-debug.apk"
echo "安装到设备: adb install -r app/build/outputs/apk/debug/app-debug.apk"
echo ""
