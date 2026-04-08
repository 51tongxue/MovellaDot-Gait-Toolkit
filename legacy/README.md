# 历史版本（归档）

本目录存放从 **[Xsens_GRF_estimation](https://github.com/51tongxue/Xsens_GRF_estimation)** 仓库 **`scripts/Xsens-Multi-Dots-Streamer/`** 复制过来的 **Xsens-Multi-Dots-Streamer（XMDS）** 源码快照。

## 和「从那边挪过来」的关系

| 说明 | 内容 |
|------|------|
| **做了什么** | 把上游该文件夹的**一份拷贝**放进本仓库的 `legacy/Xsens-Multi-Dots-Streamer/`，便于在本项目里对照旧版。 |
| **没做什么** | **没有**在 `Xsens_GRF_estimation` 里删除或移动原目录；那边仓库照常保留，互不影响。 |
| **若要在上游删掉** | 需你在 [Xsens_GRF_estimation](https://github.com/51tongxue/Xsens_GRF_estimation) 里另开提交自行删除（一般论文/引用仍可同时保留该路径或 README 里链到本仓库）。 |

## 与 GitHub 上目录版本对应关系

| 项目 | 说明 |
|------|------|
| **上游路径** | [`scripts/Xsens-Multi-Dots-Streamer`](https://github.com/51tongxue/Xsens_GRF_estimation/tree/main/scripts/Xsens-Multi-Dots-Streamer) |
| **该目录最近一次提交** | [`6e6c36b`](https://github.com/51tongxue/Xsens_GRF_estimation/commit/6e6c36b)（*Fix hybrid calibration to use fixed 15s gait segment*）；其后 `main` 上未再改该目录下文件，与本快照内容一致。 |
| **整库 `main` 顶端（拉取时）** | `8308c562…`（若需可对照，子树与上述一致） |

**当前开发**：请只在本仓库根目录的 **`android-xsens-dot`** 与 **`android-gait-dashboard`** 上迭代，勿在 `legacy/` 里当主工程继续改（除非刻意做历史对照）。

子目录：`legacy/Xsens-Multi-Dots-Streamer/`（含旧版 `README.md`、`android-xsens-dot`、`android-gait-dashboard`、`XsensDotIMU-main` 等）。
