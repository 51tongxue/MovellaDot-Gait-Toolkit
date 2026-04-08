# Xsens DOT Android 采集系统

基于 Movella DOT Android SDK 的多传感器采集应用，解决 macOS 丢包与同步问题。深色主题、卡片布局。

## SDK

项目使用 **Movella DOT SDK v2025.1.1**（`app/libs/Movella_DOT_SDK_Core_Android_v2025.1.1-release.aar`），参考 `scripts/../SDK for Android v2025_1_1`。若需更新，从 [Movella 软件文档](https://www.movella.com/support/software-documentation) 下载后替换。

## 构建与运行

### 在 Cursor 中开发

1. 配置环境：`./setup_env.sh`（首次运行）
2. 构建：`./build.sh` 或 `./gradlew assembleDebug`
3. 安装：`./install.sh`（需 USB 连接手机）

详见 [CURSOR_DEV.md](CURSOR_DEV.md)

### 使用 Android Studio

打开 `android-xsens-dot` 目录，等待 Gradle 同步完成后运行。若网络受限，可先用 Android Studio 完成首次同步，再在 Cursor 中继续开发。

## 功能

- 扫描与连接：扫描 Xsens DOT、勾选设备、连接/断开
- 状态栏：FSM、连接状态、传感器数、接收数
- Payload 类型、Heading Reset/Revert
- 多传感器同步：SDK 硬件同步（官方流程），同步失败也可正常采集
- 实时数据卡片：欧拉角、四元数、加速度、角速度
- 录制：点击「开始录制」将数据保存为 CSV
- 离线采集：将设备内置 Flash 录制与导出（需保持测量与传感器融合运行；能力与边界说明见 [FLASH_AND_ONDEVICE_COMPUTE.md](FLASH_AND_ONDEVICE_COMPUTE.md)）

## 采集数据保存位置

录制后的 CSV 文件保存在应用私有目录：

- **路径**：`Android/data/com.buct.xsens.dot/files/data_logging/`
- **文件名**：`Xsens DOT_<MAC地址>_<时间戳>.csv`，例如 `Xsens DOT_D422CD007E6E_20260317_130209.csv`
- **格式**：列包括 `SampleTimeFine,roll,pitch,yaw,freeAccX,freeAccY,freeAccZ,gyroX,gyroY,gyroZ,Quat_W,Quat_X,Quat_Y,Quat_Z`

**导出方式**：
- 手机连接电脑，在文件管理器中进入 `Android/data/com.buct.xsens.dot/files/data_logging/` 复制文件
- 或使用 adb：`adb pull /storage/emulated/0/Android/data/com.buct.xsens.dot/files/data_logging/ ./`
