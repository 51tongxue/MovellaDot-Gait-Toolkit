# Xsens-Multi-Dots-Streamer (XMDS)

> **归档说明**：本目录为 [Xsens_GRF_estimation](https://github.com/51tongxue/Xsens_GRF_estimation) 曾有的 `scripts/Xsens-Multi-Dots-Streamer` 的快照（与提交 [`6e6c36b`](https://github.com/51tongxue/Xsens_GRF_estimation/tree/6e6c36b/scripts/Xsens-Multi-Dots-Streamer) 一致；上游 `main` 已移除该路径），对应 **MovellaDot-Gait-Toolkit** 的早期版本，**仅作历史参考**。当前开发与构建请使用本仓库根目录下的 `android-xsens-dot` 与 `android-gait-dashboard`。

---

基于 Android 的 Xsens DOT 惯性传感器采集与步态分析应用。

## 概述

| 应用 | 功能 |
|------|------|
| **android-xsens-dot** | 多传感器 BLE 连接、实时数据展示、CSV 录制 |
| **android-gait-dashboard** | 读取采集数据，进行步态识别与时空参数计算 |

**数据流**：采集 APK 录制 CSV → 步态 APK 读取并分析 → 展示 IC/TC/MS、接触时间、步频、vGRF 等。

---

## 快速开始

### 采集 APK (android-xsens-dot)

```bash
cd android-xsens-dot
./gradlew assembleDebug
./gradlew installDebug   # USB 连接手机
```

### 步态分析 APK (android-gait-dashboard)

```bash
cd android-gait-dashboard
./gradlew assembleDebug
./gradlew installDebug
```

---

## 项目结构

```
Xsens-Multi-Dots-Streamer/
├── android-xsens-dot/       # 采集 APK
├── android-gait-dashboard/   # 步态分析 APK
├── check_csv_quality.py     # CSV 质量检查工具
├── data_logging/            # 采集输出目录（自动创建）
├── SDK for Android/         # Movella DOT SDK 文档
└── SDK for Android v2025_1_1/
```

---

## android-xsens-dot

基于 Movella DOT Android SDK 的多传感器采集应用。

**功能**：扫描连接、多传感器同步、实时欧拉角/四元数/加速度/角速度、Heading Reset、CSV 录制。

**数据保存**：
- 路径：`Android/data/com.buct.xsens.dot/files/data_logging/`
- 格式：`SampleTimeFine,roll,pitch,yaw,freeAccX,freeAccY,freeAccZ,gyroX,gyroY,gyroZ,Quat_W,Quat_X,Quat_Y,Quat_Z`

**导出**：文件管理器或 `adb pull /storage/emulated/0/Android/data/com.buct.xsens.dot/files/data_logging/ ./`

详见 [android-xsens-dot/README.md](android-xsens-dot/README.md)

---

## android-gait-dashboard

在 Android 上对采集 CSV 进行步态分析，UI 参考 RunScribe。

**数据来源**：
- 采集 APK 的 `data_logging/`
- 共享目录 `XsensData/` 或 `Documents/XsensData/`

**分析内容**：步态事件（IC/TC/MS）、接触时间、步频、步幅、vGRF 等。

---

## check_csv_quality.py

检查采集数据质量（缺失值、时间戳连续性、多传感器对齐等）。

```bash
pip install numpy pandas
python check_csv_quality.py [data_dir]
```

`data_dir` 默认：`data_logging/data_logging/`

---

## SDK

使用 **Movella DOT SDK v2025.1.1**（`android-xsens-dot/app/libs/`）。更新 SDK 请从 [Movella 软件文档](https://www.movella.com/support/software-documentation) 下载并替换。
