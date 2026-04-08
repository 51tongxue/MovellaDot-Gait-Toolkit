# MovellaDot Gait Toolkit（MovellaDot-Gait-Toolkit）

基于 Android 与 **Movella DOT**（原 Xsens DOT）惯性传感器的采集与步态分析工具集。内部代号 **XMDS**（Xsens-Multi-Dots-Streamer）。

## 概述

| 模块 | 说明 |
|------|------|
| **android-xsens-dot** | 多传感器 BLE 连接、实时数据展示、CSV 录制 |
| **android-gait-dashboard** | 读取采集数据，在设备端用 Python（Chaquopy）进行步态识别与时空参数计算 |

**数据流**：采集 APK 将数据存为 CSV → 步态 APK 读取并分析 → 展示 IC / TC / MS、接触时间、步频、vGRF 等指标。

---

## 环境要求

- **Android**：建议 API 与项目 `compileSdk` 一致（以各模块 `build.gradle` 为准）
- **采集端**：支持 Movella DOT，需已配置 **Movella DOT SDK**（见下文「SDK」）
- **步态分析端**：依赖 Chaquopy 打包的 Python 环境（`numpy`、`pandas`、`scipy` 等，见 `android-gait-dashboard` 配置）

---

## 快速开始

### 克隆仓库

```bash
git clone <你的远程仓库 URL> MovellaDot-Gait-Toolkit
cd MovellaDot-Gait-Toolkit
```

### 采集 APK（android-xsens-dot）

```bash
cd android-xsens-dot
./gradlew assembleDebug
./gradlew installDebug   # 需 USB 连接并已开启调试
```

更完整的脚本与 Cursor 开发说明见 [android-xsens-dot/README.md](android-xsens-dot/README.md)。

### 步态分析 APK（android-gait-dashboard）

```bash
cd android-gait-dashboard
./gradlew assembleDebug
./gradlew installDebug
```

---

## 项目结构

```
MovellaDot-Gait-Toolkit/
├── android-xsens-dot/           # 采集 APK（Movella DOT SDK）
├── android-gait-dashboard/      # 步态分析 APK（Python 步态管线）
├── legacy/                      # 旧版 XMDS 快照（归档，见下）
│   └── Xsens-Multi-Dots-Streamer/
├── SDK for Android v2025_1_1/   # SDK 文档与参考（随发行版更新）
├── Movella DOT SDK Programming Guide_Android.pdf
└── README.md
```

说明：根目录若存在本地数据目录（如 `data_logging/`），一般为运行时生成，可不纳入版本控制。

### 历史版本（归档）

独立成库之前，本工具集曾作为 [Xsens_GRF_estimation](https://github.com/51tongxue/Xsens_GRF_estimation) 仓库中的 `scripts/Xsens-Multi-Dots-Streamer` 维护；该路径已从上游 `main` 删除，快照已并入本仓库 **`legacy/Xsens-Multi-Dots-Streamer/`**（上游历史树见 [`6e6c36b`](https://github.com/51tongxue/Xsens_GRF_estimation/tree/6e6c36b/scripts/Xsens-Multi-Dots-Streamer)），详见 [legacy/README.md](legacy/README.md)。日常开发请以根目录两个 Android 工程为准。

---

## android-xsens-dot

基于 Movella DOT Android SDK 的多传感器采集应用。

**功能概要**：扫描连接、多传感器同步、实时欧拉角 / 四元数 / 加速度 / 角速度、Heading Reset、CSV 录制等。

**数据保存（录制）**：

- 路径示例：`Android/data/com.buct.xsens.dot/files/data_logging/`
- 列示例：`SampleTimeFine, roll, pitch, yaw, freeAccX, freeAccY, freeAccZ, gyroX, gyroY, gyroZ, Quat_W, Quat_X, Quat_Y, Quat_Z`

**导出示例**：

```bash
adb pull /storage/emulated/0/Android/data/com.buct.xsens.dot/files/data_logging/ ./
```

详见 [android-xsens-dot/README.md](android-xsens-dot/README.md)。

---

## android-gait-dashboard

在 Android 上对采集 CSV 进行步态分析（核心逻辑见 `app/src/main/python/gait_analyzer.py`），支持在线 CSV（含 `freeAcc`）与离线格式（含 `Acc_*` + 四元数）的自动识别与处理。

**数据来源示例**：

- 采集 APK 的 `data_logging/`
- 共享目录 `XsensData/` 或 `Documents/XsensData/`（以应用内实现为准）

**分析内容**：步态事件（IC / TC / MS）、接触时间、步频、步幅、vGRF 等相关指标。

---

## SDK

使用 **Movella DOT SDK v2025.1.1**（位于 `android-xsens-dot/app/libs/` 等路径，以仓库内实际文件为准）。更新 SDK 请从 [Movella 软件文档](https://www.movella.com/support/software-documentation) 下载并按子项目说明替换。

---

## 许可证

若未另行提供 `LICENSE` 文件，默认以仓库内各子项目或第三方 SDK 的许可条款为准；使用前请确认 Movella DOT SDK 与依赖库的商业与分发条款。
