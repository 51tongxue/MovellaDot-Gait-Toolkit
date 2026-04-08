#!/bin/bash
# Android 开发环境配置脚本（在 Cursor 中开发用）
# 用法: ./setup_env.sh

set -e
ANDROID_HOME_DEFAULT="$HOME/Library/Android/sdk"
CMD_TOOLS_URL="https://dl.google.com/android/repository/commandlinetools-mac-11076708_latest.zip"

echo "=== Xsens DOT Android 环境配置 ==="

# 1. 检查 Java
echo ""
echo "[1/4] 检查 Java..."
if java -version 2>&1 | grep -q "11\|17"; then
    echo "  ✓ Java 已安装: $(java -version 2>&1 | head -1)"
else
    echo "  ✗ 需要 Java 11 或 17。当前: $(java -version 2>&1 | head -1)"
    echo "    可从 https://adoptium.net 下载 Temurin 17"
    exit 1
fi

# 2. 创建 Android SDK 目录
echo ""
echo "[2/4] 配置 Android SDK..."
mkdir -p "$ANDROID_HOME_DEFAULT/cmdline-tools"
cd "$ANDROID_HOME_DEFAULT"

if [ ! -d "cmdline-tools/latest" ]; then
    echo "  下载 Android 命令行工具..."
    TMP_ZIP="/tmp/cmdline-tools.zip"
    if curl -L -o "$TMP_ZIP" "$CMD_TOOLS_URL" 2>/dev/null; then
        unzip -q -o "$TMP_ZIP" -d cmdline-tools
        mv cmdline-tools/cmdline-tools cmdline-tools/latest 2>/dev/null || true
        rm -f "$TMP_ZIP"
        echo "  ✓ 命令行工具已安装"
    else
        echo "  ✗ 自动下载失败（可能是网络/SSL 问题）"
        echo "    请手动操作："
        echo "    1. 浏览器打开: https://developer.android.com/studio#command-line-tools-only"
        echo "    2. 下载 Mac 版 commandlinetools"
        echo "    3. 解压到: $ANDROID_HOME_DEFAULT/cmdline-tools/latest/"
        echo "    4. 确保 cmdline-tools/latest/bin/sdkmanager 存在"
        exit 1
    fi
else
    echo "  ✓ 命令行工具已存在"
fi

# 3. 安装 SDK 组件
echo ""
echo "[3/4] 安装 SDK 组件（platform-tools, platform-34, build-tools）..."
export ANDROID_HOME="$ANDROID_HOME_DEFAULT"
export PATH="$ANDROID_HOME/cmdline-tools/latest/bin:$ANDROID_HOME/platform-tools:$PATH"

yes | sdkmanager --sdk_root="$ANDROID_HOME" "platform-tools" "platforms;android-34" "build-tools;34.0.0" 2>/dev/null || {
    echo "  使用 sdkmanager 安装..."
    sdkmanager --sdk_root="$ANDROID_HOME" "platform-tools"
    sdkmanager --sdk_root="$ANDROID_HOME" "platforms;android-34"
    sdkmanager --sdk_root="$ANDROID_HOME" "build-tools;34.0.0"
}
echo "  ✓ SDK 组件已安装"

# 4. 写入 .zshrc
echo ""
echo "[4/4] 配置 shell 环境变量..."
ZSHRC="$HOME/.zshrc"
MARKER="# Xsens DOT Android (Cursor)"
if ! grep -q "$MARKER" "$ZSHRC" 2>/dev/null; then
    cat >> "$ZSHRC" << EOF

$MARKER
export ANDROID_HOME="$ANDROID_HOME_DEFAULT"
export PATH="\$PATH:\$ANDROID_HOME/platform-tools:\$ANDROID_HOME/cmdline-tools/latest/bin"
EOF
    echo "  ✓ 已写入 $ZSHRC"
else
    echo "  ✓ 环境变量已存在"
fi

echo ""
echo "=== 配置完成 ==="
echo ""
echo "请执行: source ~/.zshrc"
echo "然后: cd $(dirname "$0") && ./gradlew assembleDebug"
echo ""
