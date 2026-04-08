#!/bin/bash
# 安装 APK 到已连接的 Android 设备
# 用法: ./install.sh

cd "$(dirname "$0")"

[ -f "$HOME/.zshrc" ] && source "$HOME/.zshrc" 2>/dev/null
export ANDROID_HOME="${ANDROID_HOME:-$HOME/Library/Android/sdk}"
export PATH="$PATH:$ANDROID_HOME/platform-tools"

APK="app/build/outputs/apk/debug/app-debug.apk"
if [ ! -f "$APK" ]; then
    echo "APK 不存在，请先运行 ./build.sh"
    exit 1
fi

echo "安装到设备..."
adb install -r "$APK"
echo "完成。请在手机上打开「Xsens DOT 采集」"
echo ""
