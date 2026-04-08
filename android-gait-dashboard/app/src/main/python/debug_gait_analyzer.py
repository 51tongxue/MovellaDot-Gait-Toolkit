#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
步态算法调试可视化工具

用法：
  python debug_gait_analyzer.py                  # 自动列出设备文件，交互选择
  python debug_gait_analyzer.py --file <path>    # 直接指定本地 CSV
  python debug_gait_analyzer.py --adb-only       # 仅拉取，不绘图

调试方式：
  1. 修改下方「★ 可调参数区」调整滤波/窗口参数
  2. 直接修改「★ 算法区 gait_identification」函数内的检测逻辑
  3. 保存后重新运行，观察图形变化
  4. 满意后把改动同步回 gait_analyzer.py 的同名函数
"""

import os
import sys
import subprocess
import tempfile
import argparse

import pandas as pd
import numpy as np
import matplotlib.pyplot as plt
from scipy.signal import butter, sosfiltfilt, argrelextrema, savgol_filter, find_peaks

# 只从 gait_analyzer 复用预处理辅助函数，算法本体已内联在下方
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from gait_analyzer import (
    _rotate_vector_by_quaternion,
    _detect_format,
    GRAVITY,
    estimate_sample_rate_hz,
    enforce_msw_minimum_interval_ms,
    MSW_MIN_INTERVAL_MS,
)

# =============================================================================
# ★ 可调参数区 — 修改这里，保存，重新运行即可观察变化
# =============================================================================
LOW_CUTOFF_HZ         = 7      # 低通滤波截止频率 (Hz)
MSW_WINDOW_MS         = 400    # argrelextrema 局部极大邻域（ms 换算为 order），非最小间隔
# MSW_MIN_INTERVAL_MS 从 gait_analyzer 导入：相邻 MSW 至少间隔
TC_OFFSET_AFTER_IC_MS = 50     # IC 后多少 ms 开始搜 TC
# 采样率由 Timestamp 差分自动估计（与 gait_analyzer 一致，不重采样）

BASE_FS_HZ       = 60          # 基准采样率（用于 Savgol 窗口缩放）
BASE_SAVGOL_WIN  = 15          # 基准 Savgol 窗口（60Hz，须为奇数）
# =============================================================================


# =============================================================================
# ★ 算法区 gait_identification — 直接在这里改检测逻辑，保存后重跑即可
#   对应 gait_analyzer.py 的 gait_identification()
# =============================================================================
def gait_identification(idata, fs=None):
    """
    步态事件识别（与 gait_analyzer.gait_identification 等价，本文件为内联调试副本）。
    fs 默认由 Timestamp 估计（与生产管线一致）。
    返回 (HS_timestamps, TO_timestamps, MS_timestamps, idata_with_event_cols)
    """
    if fs is None:
        fs = estimate_sample_rate_hz(idata['Timestamp'].values)
    idata = idata.copy()

    # ---------- MSW 检测 ----------
    # order_n：argrelextrema 的半窗点数，由固定时间窗（MSW_WINDOW_MS）换算
    order_n   = max(3, round(fs * MSW_WINDOW_MS / 2000))
    scale     = fs / BASE_FS_HZ
    savgol_win = max(3, round(BASE_SAVGOL_WIN * scale))
    if savgol_win % 2 == 0:
        savgol_win += 1

    def _lp_filter(data, col):
        sos = butter(4, LOW_CUTOFF_HZ, 'lp', fs=fs, output='sos')
        data[col] = sosfiltfilt(sos, data[col].values)
        return data

    # 内部滤波副本：对所有检测相关信号滤波，仅用于事件检测
    # ACC 不写回 idata，保持原始值供步幅 ZUPT 积分
    # Gmax 写回 idata 供绘图展示，事件标记 Y 坐标与信号线保持一致
    det = idata.copy()
    for col in ['Gmax(°/s)', 'ACC.X', 'ACC.Y', 'ACC.Z']:
        if col in det.columns:
            det = _lp_filter(det, col)
    idata['Gmax(°/s)'] = det['Gmax(°/s)'].values

    gyro_noise = det['Gmax(°/s)'].std()
    wl = min(savgol_win, len(det) // 2 * 2 + 1)
    gmax_smooth = savgol_filter(det['Gmax(°/s)'].values, wl, 3) if wl >= 3 else det['Gmax(°/s)'].values
    extrema_idx = argrelextrema(gmax_smooth, np.greater_equal, order=order_n)[0]

    idata['MSW'] = np.nan
    for i in extrema_idx:
        val = det['Gmax(°/s)'].iloc[i]
        if val > gyro_noise and val > 0:
            idata.iloc[i, idata.columns.get_loc('MSW')] = val
    enforce_msw_minimum_interval_ms(idata, det, MSW_MIN_INTERVAL_MS)
    MSW_timestamps = idata[['Timestamp', 'MSW']].dropna()['Timestamp'].values

    # ---------- IC 检测 ----------
    # 策略：相邻两个 MSW 之间找 Gmax 谷底，在 [MSW→谷底] 段找第一个正→负过零点
    idata['IC'] = np.nan
    msw_indices  = idata[idata['MSW'].notna()].index.tolist()
    sentinel_idx = idata.index[-1]
    search_ends  = msw_indices[1:] + [sentinel_idx]

    for msw_idx, end_idx in zip(msw_indices, search_ends):
        seg = det.loc[(det.index >= msw_idx) & (det.index <= end_idx),
                      ['Timestamp', 'Gmax(°/s)']].copy()
        if len(seg) < 3:
            continue
        gyro = seg['Gmax(°/s)'].values
        ts   = seg['Timestamp'].values

        valley_local = int(np.argmin(gyro))          # 谷底点
        ic_time = None
        for j in range(valley_local - 1):            # MSW → 谷底 找第一个正→负过零点
            if gyro[j] > 0 and gyro[j + 1] <= 0:
                ic_time = ts[j] + (ts[j+1] - ts[j]) * (-gyro[j]) / (gyro[j+1] - gyro[j])
                break
        if ic_time is None:
            ic_time = ts[valley_local]               # 兜底用谷底时刻

        idx = (np.abs(idata['Timestamp'] - ic_time)).idxmin()
        idata.loc[idx, 'IC'] = det.loc[idx, 'Gmax(°/s)']

    # ---------- TC 检测 ----------
    # 策略：IC + TC_OFFSET_AFTER_IC_MS → 下一个 MSW 区间内找 Gmax 负值谷底
    def _find_tc(sig, ts):
        if len(sig) < 3:
            return None
        wl = min(5, len(sig) // 2 * 2 - 1 if (len(sig) % 2 == 0) else len(sig))
        wl = max(3, wl)
        try:
            filtered = savgol_filter(sig, window_length=wl, polyorder=2)
        except ValueError:
            filtered = sig
        valleys = [v for v in argrelextrema(filtered, np.less)[0] if filtered[v] < 0]
        if not valleys:
            return None
        return ts[min(valleys, key=lambda x: filtered[x])]

    idata['TC'] = np.nan
    IC_timestamps = idata[idata['IC'].notna()]['Timestamp'].values
    for ic_t in IC_timestamps:
        next_msws = MSW_timestamps[MSW_timestamps > ic_t]
        if len(next_msws) > 0:
            w_start = ic_t + TC_OFFSET_AFTER_IC_MS
            w_end   = next_msws[0]
            if w_start < w_end:
                mask   = (det['Timestamp'] >= w_start) & (det['Timestamp'] <= w_end)
                tc_t   = _find_tc(det.loc[mask, 'Gmax(°/s)'].values,
                                   det.loc[mask, 'Timestamp'].values)
                if tc_t is not None:
                    idx = (np.abs(idata['Timestamp'] - tc_t)).idxmin()
                    idata.loc[idx, 'TC'] = det.loc[idx, 'Gmax(°/s)']

    HS = sorted(idata[idata['IC'].notna()]['Timestamp'].values)
    TO = sorted(idata[idata['TC'].notna()]['Timestamp'].values)

    # ---------- IC 细化（ACC.Y 波谷 × 0.7 + 陀螺仪波谷 × 0.3）----------
    HS_refined = list(HS)
    for i, ic_time in enumerate(HS):
        if len(HS) < 2:
            continue
        if i < len(HS) - 1:
            window_size = (HS[i + 1] - HS[i]) * 0.15
        else:
            window_size = (HS[i] - HS[i - 1]) * 0.15

        window = idata[(idata['Timestamp'] >= ic_time - window_size) &
                       (idata['Timestamp'] <= ic_time + window_size)]
        if window.empty:
            continue

        acc_y_vals = window['ACC.Y'].values
        acc_y_valleys, _ = find_peaks(-acc_y_vals, distance=5)
        if len(acc_y_valleys) == 0:
            continue
        min_valley_local = acc_y_valleys[np.argmin(acc_y_vals[acc_y_valleys])]
        acc_y_valley_time = window['Timestamp'].iloc[min_valley_local]

        gyro_vals = window['Gmax(°/s)'].values
        gyro_valleys, _ = find_peaks(-gyro_vals, distance=5)
        if len(gyro_valleys) > 0:
            gyro_valley_times = window['Timestamp'].iloc[gyro_valleys].values
            closest_local = gyro_valleys[np.argmin(np.abs(gyro_valley_times - ic_time))]
            gyro_valley_time = window['Timestamp'].iloc[closest_local]
        else:
            gyro_valley_time = ic_time

        fused = 0.7 * acc_y_valley_time + 0.3 * gyro_valley_time
        nearest_idx = (np.abs(idata['Timestamp'] - fused)).idxmin()
        HS_refined[i] = int(idata.loc[nearest_idx, 'Timestamp'])

    HS = sorted(set(HS_refined))

    # ---------- TC 细化（ACC.Z 波峰 × 0.3 + 陀螺仪谷底 × 0.7）----------
    TO_refined = list(TO)
    for i, tc_time in enumerate(TO):
        if len(TO) < 2:
            continue
        if i < len(TO) - 1:
            window_size = (TO[i + 1] - TO[i]) * 0.15
        else:
            window_size = (TO[i] - TO[i - 1]) * 0.15

        window = idata[(idata['Timestamp'] >= tc_time - window_size) &
                       (idata['Timestamp'] <= tc_time + window_size)]
        if window.empty:
            continue

        acc_z_vals = window['ACC.Z'].values
        peaks, _ = find_peaks(acc_z_vals, distance=5)
        if len(peaks) == 0:
            continue

        max_peak_local = peaks[np.argmax(acc_z_vals[peaks])]
        acc_z_peak_time = window['Timestamp'].iloc[max_peak_local]

        fused = 0.3 * acc_z_peak_time + 0.7 * tc_time
        nearest_idx = (np.abs(idata['Timestamp'] - fused)).idxmin()
        TO_refined[i] = int(idata.loc[nearest_idx, 'Timestamp'])

    TO = sorted(set(TO_refined))

    # ---------- MS 检测（Method 3）----------
    # 去除支撑期前后 10%，在核心段用滑窗计算角速度能量 T_ω 和加速度方差 T_v，
    # 以当前窗口方差与相邻步连续性加权融合得到支撑中期时刻
    MS = []
    a_last = np.array([0.0, 0.0])
    data_end = int(idata['Timestamp'].iloc[-1])
    for hs_idx, hs_t in enumerate(HS):
        following_to = [t for t in TO if t > hs_t]
        if following_to:
            next_TO = following_to[0]
        elif hs_idx == len(HS) - 1:
            # 最后一个 IC 后无 TC：以 IC+500ms 为右边界，仍尝试检测 MS
            next_TO = min(hs_t + 500, data_end)
        else:
            continue

        mask   = (idata['Timestamp'] > hs_t) & (idata['Timestamp'] < next_TO)
        stance = idata.loc[mask].reset_index(drop=True)
        N_total = len(stance)
        if N_total < 10:
            continue

        first10 = int(0.1 * N_total)
        last10  = int(0.1 * N_total)
        if N_total - first10 - last10 <= 5:
            continue
        mid_core = stance.iloc[first10: N_total - last10].reset_index(drop=True)
        core_len = len(mid_core)
        times    = mid_core['Timestamp'].values

        window_size = max(5, int(core_len * 0.3))
        if window_size % 2 == 0:
            window_size += 1
        half_w = window_size // 2
        if core_len <= window_size:
            continue

        acc_xy_all  = mid_core[['ACC.X', 'ACC.Y']].values
        gyro_energy = []
        acc_var     = []
        for i in range(half_w, core_len - half_w):
            wdata   = mid_core.iloc[i - half_w: i + half_w + 1]
            T_omega = ((wdata[['Gyro.X', 'Gyro.Y', 'Gyro.Z']] ** 2).sum(axis=1)).mean()
            gyro_energy.append(T_omega)
            accel = wdata[['ACC.X', 'ACC.Y']].values
            T_v   = np.mean(np.sum((accel - accel.mean(axis=0)) ** 2, axis=1))
            acc_var.append(T_v)

        if not gyro_energy or not acc_var:
            continue

        idx_w = int(np.argmin(gyro_energy))
        idx_v = int(np.argmin(acc_var))
        t_w   = times[idx_w + half_w]
        t_v   = times[idx_v + half_w]

        a_curr   = acc_xy_all[idx_w + half_w]
        diff_mag = np.linalg.norm(a_last - a_curr)
        win      = acc_xy_all[idx_w: idx_w + window_size] if idx_w + window_size <= core_len \
                   else acc_xy_all[-window_size:]
        var_win  = np.mean(np.sum((win - win.mean(axis=0)) ** 2, axis=1))
        w        = var_win / (var_win + diff_mag + 1e-6)

        t_sp_est = w * t_w + (1 - w) * t_v
        nearest  = (np.abs(idata['Timestamp'] - t_sp_est)).argmin()
        t_sp     = int(idata.loc[nearest, 'Timestamp'])

        if t_sp not in MS:
            MS.append(t_sp)
            a_last = a_curr.copy()

    return HS, TO, MS, idata
# =============================================================================

# 设备上的离线导出目录
DEVICE_OFFLINE_DIR = "/storage/emulated/0/Documents/XsensData/offline_export"
DEVICE_ONLINE_DIR  = "/storage/emulated/0/Documents/XsensData/data_logging"
LOCAL_PULL_DIR     = os.path.join(tempfile.gettempdir(), "gait_debug")


# ---------------------------------------------------------------------------
# adb 工具函数
# ---------------------------------------------------------------------------

def _adb_check():
    """检查 adb 是否可用且有设备连接"""
    try:
        result = subprocess.run(["adb", "devices"], capture_output=True, text=True, timeout=5)
        lines = [l.strip() for l in result.stdout.splitlines() if l.strip() and "List" not in l]
        if not lines:
            print("[adb] 未检测到已连接设备，请确认 USB 调试已开启")
            return False
        print(f"[adb] 已连接设备: {lines[0]}")
        return True
    except FileNotFoundError:
        print("[adb] 未找到 adb 命令，请确认 Android SDK Platform-Tools 已加入 PATH")
        return False


def _adb_ls(remote_dir):
    """列出设备目录下的 CSV 文件，返回文件名列表"""
    try:
        result = subprocess.run(
            ["adb", "shell", f"ls {remote_dir}"],
            capture_output=True, text=True, timeout=10
        )
        if result.returncode != 0:
            print(f"[adb] 目录不存在或无权限: {remote_dir}")
            return []
        files = [f.strip() for f in result.stdout.splitlines()
                 if f.strip().lower().endswith(".csv")]
        return files
    except Exception as e:
        print(f"[adb] ls 失败: {e}")
        return []


def _adb_pull(remote_path, local_path):
    """从设备拉取单个文件"""
    os.makedirs(os.path.dirname(local_path), exist_ok=True)
    result = subprocess.run(
        ["adb", "pull", remote_path, local_path],
        capture_output=True, text=True, timeout=30
    )
    if result.returncode == 0:
        print(f"[adb] 已拉取: {remote_path} → {local_path}")
        return True
    else:
        print(f"[adb] 拉取失败: {result.stderr.strip()}")
        return False


def pick_file_from_device():
    """交互式从设备选择文件，返回本地路径"""
    if not _adb_check():
        return None

    all_files = []
    for label, remote_dir in [("离线", DEVICE_OFFLINE_DIR), ("在线", DEVICE_ONLINE_DIR)]:
        files = _adb_ls(remote_dir)
        for f in files:
            all_files.append((label, remote_dir, f))

    if not all_files:
        print("[adb] 设备上未找到 CSV 文件")
        return None

    print("\n设备上的 CSV 文件：")
    for i, (label, _, fname) in enumerate(all_files):
        print(f"  [{i}] {label}  {fname}")

    while True:
        try:
            choice = input("\n请输入编号选择文件（回车退出）: ").strip()
            if not choice:
                return None
            idx = int(choice)
            if 0 <= idx < len(all_files):
                break
            print(f"请输入 0 ~ {len(all_files) - 1}")
        except ValueError:
            print("请输入数字")

    label, remote_dir, fname = all_files[idx]
    remote_path = f"{remote_dir}/{fname}"
    local_path  = os.path.join(LOCAL_PULL_DIR, fname)
    if _adb_pull(remote_path, local_path):
        return local_path
    return None


# ---------------------------------------------------------------------------
# 数据预处理（与 gait_analyzer.process_gait_data 完全相同的逻辑）
# ---------------------------------------------------------------------------

def preprocess(file_path):
    """
    读取 CSV，自动跳过 Xsens 元数据行，进行坐标变换，
    返回包含 Timestamp / ACC / Gyro 的 DataFrame 与格式 fmt（与 process_gait_data 一致，不重采样）。
    """
    # 找到真正的表头行
    skip = 0
    with open(file_path, 'r', encoding='utf-8', errors='ignore') as f:
        for i, line in enumerate(f):
            if i >= 200:
                break
            if 'PacketCounter' in line or 'SampleTimeFine' in line:
                skip = i
                break

    idata = pd.read_csv(file_path, skiprows=skip if skip > 0 else None)
    idata.columns = idata.columns.str.strip()

    if 'SampleTimeFine' not in idata.columns:
        raise ValueError("CSV 缺少 SampleTimeFine 列，请确认文件格式")

    # 时间戳换算为 ms（相对）
    t0 = idata['SampleTimeFine'].iloc[0]
    dt_series = idata['SampleTimeFine'].diff().dropna()
    dt_avg    = dt_series.mean() if not dt_series.empty else 16666.0

    if dt_avg > 500:       # 微秒
        time_scale = 0.001
    elif dt_avg > 0.5:     # 毫秒
        time_scale = 1.0
    else:                  # 秒
        time_scale = 1000.0

    idata['Timestamp'] = np.round((idata['SampleTimeFine'] - t0) * time_scale).astype(np.int64)

    fmt = _detect_format(idata)

    # 信号列映射
    if fmt == 'offline':
        acc_array  = idata[['Acc_X', 'Acc_Y', 'Acc_Z']].to_numpy(dtype=float) / GRAVITY
        quat_array = idata[['Quat_W', 'Quat_X', 'Quat_Y', 'Quat_Z']].to_numpy(dtype=float)
        rotated    = np.array([_rotate_vector_by_quaternion(acc_array[i], quat_array[i])
                               for i in range(len(idata))])
        idata['ACC.X']     = rotated[:, 0]
        idata['ACC.Y']     = rotated[:, 1]
        idata['ACC.Z']     = rotated[:, 2] - 1.0
        idata['Gmax(°/s)'] = idata['Gyr_Y']
        idata['Gyro.X']    = idata['Gyr_X']
        idata['Gyro.Y']    = idata['Gyr_Y']
        idata['Gyro.Z']    = idata['Gyr_Z']
    else:
        idata['ACC.X']     = idata['freeAccX'] / GRAVITY
        idata['ACC.Y']     = idata['freeAccY'] / GRAVITY
        idata['ACC.Z']     = idata['freeAccZ'] / GRAVITY
        idata['Gmax(°/s)'] = idata['gyroY']
        idata['Gyro.X']    = idata['gyroX']
        idata['Gyro.Y']    = idata['gyroY']
        idata['Gyro.Z']    = idata['gyroZ']

    idata.reset_index(drop=True, inplace=True)
    return idata, fmt


# ---------------------------------------------------------------------------
# 可视化
# ---------------------------------------------------------------------------

def plot_results(idata_proc, fmt, source_fs_hz, file_name):
    """仅绘制滤波后 Gmax（角速度 Y）及步态事件标记（MSW / IC / TC / MS）。"""
    plt.rcParams['font.sans-serif'] = ['Arial Unicode MS', 'SimHei', 'DejaVu Sans']
    plt.rcParams['axes.unicode_minus'] = False

    t_sec = idata_proc['Timestamp'] / 1000.0  # ms → s

    msw_mask = idata_proc['MSW'].notna()
    ic_mask  = idata_proc['IC'].notna()
    tc_mask  = idata_proc['TC'].notna()

    ms_times = sorted(idata_proc[idata_proc['IC'].notna()]['Timestamp'].values)
    to_times = sorted(idata_proc[idata_proc['TC'].notna()]['Timestamp'].values)
    ms_ts    = []
    for hs_t in ms_times:
        following_to = [t for t in to_times if t > hs_t]
        if following_to:
            seg = idata_proc[(idata_proc['Timestamp'] > hs_t) &
                             (idata_proc['Timestamp'] < following_to[0])]
            if len(seg) > 5:
                energy = (seg['Gyro.X']**2 + seg['Gyro.Y']**2 + seg['Gyro.Z']**2)
                ms_ts.append(idata_proc.loc[energy.idxmin(), 'Timestamp'])
    ms_mask = idata_proc['Timestamp'].isin(ms_ts)

    legend_font = {'weight': 'bold', 'size': 8}
    s = 70

    fig, ax = plt.subplots(1, 1, figsize=(14, 5))
    fig.suptitle(
        f"步态事件  |  {file_name}  |  {fmt}  fs≈{source_fs_hz:.1f}Hz  "
        f"低通={LOW_CUTOFF_HZ}Hz  MSW窗={MSW_WINDOW_MS}ms  MSW间隔≥{MSW_MIN_INTERVAL_MS}ms",
        fontsize=10, fontweight='bold', y=1.02
    )

    ax.plot(t_sec, idata_proc['Gmax(°/s)'], color='#c0baca', linewidth=2,
            label='Gmax (滤波后)', alpha=0.9)
    ax.scatter(t_sec[msw_mask], idata_proc.loc[msw_mask, 'Gmax(°/s)'],
               edgecolors='black', facecolors='none', marker='s', s=s, zorder=5,
               label='MSW', linewidths=1.5)
    ax.scatter(t_sec[ic_mask], idata_proc.loc[ic_mask, 'Gmax(°/s)'],
               edgecolors='red', facecolors='none', marker='o', s=s, zorder=5,
               label='IC', linewidths=1.5)
    ax.scatter(t_sec[tc_mask], idata_proc.loc[tc_mask, 'Gmax(°/s)'],
               edgecolors='green', facecolors='none', marker='^', s=s, zorder=5,
               label='TC', linewidths=1.5)
    ax.scatter(t_sec[ms_mask], idata_proc.loc[ms_mask, 'Gmax(°/s)'],
               edgecolors='#FFA500', facecolors='none', marker='v', s=s, zorder=5,
               label='MS', linewidths=1.5)
    ax.set_xlabel('时间 (s)', fontsize=9, fontweight='bold')
    ax.set_ylabel('角速度 Y (°/s)', fontsize=9, fontweight='bold')
    ax.legend(loc='upper right', prop=legend_font, frameon=False)
    ax.spines['top'].set_visible(False)
    ax.spines['right'].set_visible(False)
    n_ic  = ic_mask.sum()
    n_tc  = tc_mask.sum()
    n_msw = msw_mask.sum()
    ax.set_title(
        f"MSW={n_msw}  IC={n_ic}  TC={n_tc}  MS={len(ms_ts)}  步数≈{n_ic}",
        fontsize=9, loc='left', color='#333'
    )

    plt.tight_layout()
    plt.show(block=True)


# ---------------------------------------------------------------------------
# 主函数
# ---------------------------------------------------------------------------

def main():
    parser = argparse.ArgumentParser(description='步态算法调试可视化工具')
    parser.add_argument('--file',     type=str, default=None, help='本地 CSV 文件路径（跳过 adb）')
    parser.add_argument('--adb-only', action='store_true',    help='仅拉取文件，不运行算法')
    parser.add_argument('--start',    type=float, default=None, help='裁剪开始时间 (s)')
    parser.add_argument('--end',      type=float, default=None, help='裁剪结束时间 (s)')
    args = parser.parse_args()

    # 1. 确定本地文件路径
    local_file = args.file
    if local_file is None:
        local_file = pick_file_from_device()
        if local_file is None:
            print("未选择文件，退出")
            return

    if not os.path.exists(local_file):
        print(f"文件不存在: {local_file}")
        return

    if args.adb_only:
        print(f"文件已拉取至: {local_file}")
        return

    # 2. 预处理
    print(f"\n正在处理: {local_file}")
    idata_raw, fmt = preprocess(local_file)
    print(f"格式={fmt}  原始行数={len(idata_raw)}")

    # 3. 时间范围裁剪（与 gait_analyzer.process_gait_data 一致：先裁剪再估计 fs）
    idata = idata_raw.copy()
    if args.start is not None:
        idata = idata[idata['Timestamp'] >= args.start * 1000.0]
    if args.end is not None:
        idata = idata[idata['Timestamp'] <= args.end * 1000.0]
    if idata.empty:
        print("裁剪后数据为空，请检查 --start / --end 参数")
        return
    idata = idata.reset_index(drop=True)
    print(f"裁剪后行数: {len(idata)}，时长: {idata['Timestamp'].iloc[-1]/1000:.1f}s")

    source_fs = estimate_sample_rate_hz(idata['Timestamp'].values)
    print(f"估计采样率≈{source_fs:.2f}Hz（不重采样）")

    # 4. 运行算法（调用上方「★ 算法区」的内联函数，可直接修改）
    print("运行步态事件识别算法...")
    HS, TO, MS, idata_proc = gait_identification(idata, fs=source_fs)
    duration_s = (idata_proc['Timestamp'].iloc[-1] - idata_proc['Timestamp'].iloc[0]) / 1000.0
    print(f"结果 → IC(HS)={len(HS)}  TC(TO)={len(TO)}  MS={len(MS)}")
    print(f"时长={duration_s:.1f}s  步频≈{len(HS)/duration_s*60:.0f}步/分" if duration_s > 0 else "")

    # 5. 绘图
    file_name = os.path.basename(local_file)
    plot_results(idata_proc, fmt, source_fs, file_name)


if __name__ == '__main__':
    main()
