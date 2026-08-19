# MovellaDot Gait Toolkit

基于 Movella DOT 惯性传感器的 Android 步态采集与分析系统。

## 功能

- 连接、配置和同步 Movella DOT 设备
- 支持左右脚双设备采集
- 支持实时采集和设备 Flash 离线录制
- 支持 1-3 名运动员同时采集
- 支持通用、跳远、竞走三种分析模式
- 提供步态指标、左右脚对比和图表分析
- 支持历史记录、数据导出和局域网共享文件夹上传

## 分析模式

| 模式 | 用途 |
| --- | --- |
| 通用 | 常规步态分析 |
| 跳远 | 跳远助跑和起跳步分析 |
| 竞走 | 竞走步态分析 |

## 项目结构

- `android-gait-dashboard/`：Android 应用
- `android-xsens-dot/`：DOT 设备采集模块
- `SDK for Android v2025_1_1/`：Movella DOT SDK

## 构建

需要 Android Studio、JDK 11 和 Android SDK 34。

```bash
cd android-gait-dashboard
./gradlew :app:assembleDebug
```

## 安装

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

采集数据默认保存在 Android 设备的 `Documents/XsensData/` 目录中。
