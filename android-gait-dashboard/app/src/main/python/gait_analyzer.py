import pandas as pd
import numpy as np
from scipy.signal import butter, sosfiltfilt, argrelextrema, savgol_filter, find_peaks
import json
import os

# ---------------------------------------------------------------------------
# 四元数辅助函数（用于离线格式的加速度坐标系转换）
# ---------------------------------------------------------------------------

def _quaternion_multiply(q1, q2):
    w0, x0, y0, z0 = q1
    w1, x1, y1, z1 = q2
    return np.array([
        w0*w1 - x0*x1 - y0*y1 - z0*z1,
        w0*x1 + x0*w1 + y0*z1 - z0*y1,
        w0*y1 - x0*z1 + y0*w1 + z0*x1,
        w0*z1 + x0*y1 - y0*x1 + z0*w1
    ])


def _rotate_vector_by_quaternion(v, q):
    """用四元数 q=[w,x,y,z] 将向量 v 从局部坐标系旋转到全局坐标系（q * v * q_conj）"""
    q_conj = np.array([q[0], -q[1], -q[2], -q[3]])
    v_q = np.array([0.0, v[0], v[1], v[2]])
    rotated = _quaternion_multiply(_quaternion_multiply(q, v_q), q_conj)
    return rotated[1:]


def _detect_format(idata):
    """检测 CSV 格式：离线格式含 Acc_X + Quat_W；在线格式含 freeAccX"""
    if 'Acc_X' in idata.columns and 'Quat_W' in idata.columns:
        return 'offline'
    return 'online'


# 步态分析核心配置
GRAVITY = 9.80665
MS_PER_S = 1000.0
LOW_CUTOFF_HZ = 7
BASE_FS_HZ = 60          # 基准采样率（用于 Savgol 窗口缩放）
BASE_SAVGOL_WINDOW = 15  # 基准 Savgol 窗口（60Hz，须为奇数）
# MSW_WINDOW_MS：仅用于 argrelextrema 的 order（局部极大邻域宽度），不保证相邻 MSW 的时间间隔
MSW_WINDOW_MS = 400
# 相邻 MSW（按时间）至少间隔多少 ms；过近则只保留 Gmax 更大的一侧，避免双支撑内毛刺被当成两次 MSW
MSW_MIN_INTERVAL_MS = 400
IC_WINDOW_MS = 50
TC_OFFSET_AFTER_IC_MS = 50

FS_ESTIMATE_MIN_HZ = 10
FS_ESTIMATE_MAX_HZ = 2000


def estimate_sample_rate_hz(timestamps_ms):
    """由相对时间戳（ms）差分的中位数估计采样率 (Hz)。有效样本不足时返回 60 作为兜底。"""
    ts = np.asarray(timestamps_ms, dtype=np.float64)
    if ts.size < 2:
        return 60.0
    d = np.diff(ts)
    d = d[d > 0]
    if d.size < 1:
        return 60.0
    dt_med = float(np.median(d))
    if dt_med <= 0:
        return 60.0
    fs = MS_PER_S / dt_med
    return float(np.clip(fs, FS_ESTIMATE_MIN_HZ, FS_ESTIMATE_MAX_HZ))


def _apply_filters_low(data, column, low_cutoff, fs):
    sos_low = butter(4, low_cutoff, 'lp', fs=fs, output='sos')
    data[column] = sosfiltfilt(sos_low, data[column].values)
    return data


def enforce_msw_minimum_interval_ms(idata, det, min_interval_ms):
    """
    保证相邻 MSW 事件在时间轴上至少相隔 min_interval_ms。
    argrelextrema 的 order 只定义「局部极大」邻域，不能禁止更近的第二个峰；
    过近时保留 Gmax（滤波后 det）较大者。
    """
    mask = idata['MSW'].notna()
    if mask.sum() <= 1:
        return
    rows = []
    for idx in idata.index[mask]:
        t = float(idata.at[idx, 'Timestamp'])
        g = float(det.at[idx, 'Gmax(°/s)'])
        rows.append((t, idx, g))
    rows.sort(key=lambda x: x[0])
    merged = [rows[0]]
    for t, idx, g in rows[1:]:
        pt, pidx, pg = merged[-1]
        if t - pt >= min_interval_ms:
            merged.append((t, idx, g))
        elif g > pg:
            merged[-1] = (t, idx, g)
    loc = idata.columns.get_loc('MSW')
    idata['MSW'] = np.nan
    for t, idx, g in merged:
        idata.loc[idx, 'MSW'] = g


def gait_identification(idata, fs=BASE_FS_HZ):
    """步态事件识别。fs 为实际采样率（Hz），窗口/order 参数随 fs 等比缩放。"""
    # idata：含原始 ACC，用于写入事件列后返回给步幅积分
    idata = idata.copy()

    # MSW 峰值检测：argrelextrema order 由 MSW_WINDOW_MS 换算为采样点数（局部极大邻域，≠最小峰间距）
    # 相邻 MSW 最小间隔见 MSW_MIN_INTERVAL_MS + enforce_msw_minimum_interval_ms
    # order 含义：该点两侧各 order 个样本内必须是最大值，总窗口 = 2*order+1 个样本
    order_n = max(3, round(fs * MSW_WINDOW_MS / 2000))
    # Savgol 窗口仍按采样率等比缩放（须为奇数）
    scale = fs / BASE_FS_HZ
    savgol_win = max(3, round(BASE_SAVGOL_WINDOW * scale))
    if savgol_win % 2 == 0:
        savgol_win += 1

    # 内部滤波副本：对所有检测相关信号滤波，仅在函数内用于事件检测
    # ACC 不写回 idata，保持原始值供步幅 ZUPT 积分
    # Gmax 写回 idata 供前端/绘图展示，事件标记 Y 坐标与信号线保持一致
    det = idata.copy()
    for col in ['Gmax(°/s)', 'ACC.X', 'ACC.Y', 'ACC.Z']:
        if col in det.columns:
            det = _apply_filters_low(det, col, LOW_CUTOFF_HZ, fs)
    idata['Gmax(°/s)'] = det['Gmax(°/s)'].values
    idata['gyroscopic_energy'] = np.sqrt(idata['Gyro.X'] ** 2 + idata['Gyro.Y'] ** 2 + idata['Gyro.Z'] ** 2)

    gyro_noise_level = det['Gmax(°/s)'].std() * 0.75
    MSW_dynamHS_threshold = gyro_noise_level
    wl_gmax = min(savgol_win, len(det) // 2 * 2 + 1)
    if wl_gmax < 3:
        gmax_smooth = det['Gmax(°/s)'].values
    else:
        gmax_smooth = savgol_filter(det['Gmax(°/s)'].values, wl_gmax, 3)
    extrema_idx = argrelextrema(gmax_smooth, np.greater_equal, order=order_n)[0]

    idata['MSW'] = np.nan
    for i in extrema_idx:
        val = det['Gmax(°/s)'].iloc[i]
        if val > MSW_dynamHS_threshold and val > 0:
            idata.iloc[i, idata.columns.get_loc('MSW')] = val
    enforce_msw_minimum_interval_ms(idata, det, MSW_MIN_INTERVAL_MS)
    MSW_timestamps = idata[['Timestamp', 'MSW']].dropna()['Timestamp'].values

    idata['IC'] = np.nan
    idata['IC_raw'] = np.nan
    idata['IC_is_zc'] = False

    def find_ic_time(seg_signal, seg_timestamps):
        if len(seg_signal) < 3: return None, False
        
        from scipy.signal import find_peaks
        valleys, _ = find_peaks(-seg_signal)
        
        valid_valleys = [v for v in valleys if seg_signal[v] < gyro_noise_level]
        
        if len(valid_valleys) == 0:
            zc_idxs = [j for j in range(len(seg_signal)-1) if seg_signal[j] > 0 and seg_signal[j+1] <= 0]
            if zc_idxs:
                j = zc_idxs[0]
                cal_time = seg_timestamps[j] + (seg_timestamps[j+1]-seg_timestamps[j]) * (-seg_signal[j])/(seg_signal[j+1]-seg_signal[j])
                return cal_time, True
            return None, False

        first_valley_idx = valid_valleys[0]
        
        if seg_signal[first_valley_idx] >= 0:
            return seg_timestamps[first_valley_idx], False
        else:
            zc_idxs = [j for j in range(first_valley_idx) if seg_signal[j] > 0 and seg_signal[j+1] <= 0]
            if zc_idxs:
                j = zc_idxs[0]
                cal_time = seg_timestamps[j] + (seg_timestamps[j+1]-seg_timestamps[j]) * (-seg_signal[j])/(seg_signal[j+1]-seg_signal[j])
                return cal_time, True
            else:
                return seg_timestamps[first_valley_idx], False

    ic_windows = []
    for i in range(len(MSW_timestamps) - 1):
        ic_windows.append((MSW_timestamps[i], MSW_timestamps[i + 1]))
        
    if len(MSW_timestamps) > 0:
        ic_windows.append((MSW_timestamps[-1], idata['Timestamp'].iloc[-1]))

    for idx_w, (w_start, w_end) in enumerate(ic_windows):
        mask = (idata['Timestamp'] >= w_start) & (idata['Timestamp'] <= w_end)
        ic_time, is_zc = find_ic_time(idata.loc[mask, 'Gmax(°/s)'].values, idata.loc[mask, 'Timestamp'].values)
        
        # 强制兜底
        if ic_time is None and idx_w == len(ic_windows) - 1:
            seg_ts = idata.loc[mask, 'Timestamp'].values
            if len(seg_ts) > 0:
                ic_time = seg_ts[-1]
                is_zc = False
                
        if ic_time is not None:
            idx = (np.abs(idata['Timestamp'] - ic_time)).idxmin()
            idata.loc[idx, 'IC'] = idata.loc[idx, 'Gmax(°/s)']
            idata.loc[idx, 'IC_is_zc'] = is_zc

    idata['TC'] = np.nan
    idata['TC_raw'] = np.nan
    idata['IC_alt'] = np.nan

    def find_tc_time(seg_signal, seg_timestamps, is_ic_zc):
        if len(seg_signal) < 3: return None, None
        
        wl = min(5, len(seg_signal)//2*2+1)
        wl = max(3, wl)
        try:
            filtered = savgol_filter(seg_signal, window_length=wl, polyorder=2)
        except ValueError:
            filtered = seg_signal

        from scipy.signal import find_peaks
        peaks, _ = find_peaks(-filtered)
        valid_valleys = [v for v in peaks if filtered[v] < 0]
        
        if not valid_valleys: return None, None
        
        if is_ic_zc:
            ic_alt_time = seg_timestamps[valid_valleys[0]]
            if len(valid_valleys) > 1:
                tc_time = seg_timestamps[min(valid_valleys[1:], key=lambda x: filtered[x])]
                return tc_time, ic_alt_time
            else:
                tc_time = seg_timestamps[valid_valleys[0]]
                return tc_time, ic_alt_time
        else:
            tc_time = seg_timestamps[min(valid_valleys, key=lambda x: filtered[x])]
            return tc_time, None

    tc_windows = []
    if len(MSW_timestamps) > 0:
        tc_windows.append((idata['Timestamp'].iloc[0], MSW_timestamps[0], False))

    IC_timestamps = idata[idata['IC'].notna()]['Timestamp'].values
    for ic_time in IC_timestamps:
        next_msws = MSW_timestamps[MSW_timestamps > ic_time]
        if len(next_msws) > 0:
            tc_windows.append((ic_time, next_msws[0], True))

    for w_start, w_end, incl_end in tc_windows:
        mask = (idata['Timestamp'] >= w_start) & (idata['Timestamp'] <= w_end if incl_end else idata['Timestamp'] < w_end)
        
        is_ic_zc = False
        idx_start = (np.abs(idata['Timestamp'] - w_start)).idxmin()
        if pd.notna(idata.loc[idx_start, 'IC']):
            is_ic_zc = idata.loc[idx_start, 'IC_is_zc']
            
        tc_time, ic_alt_time = find_tc_time(idata.loc[mask, 'Gmax(°/s)'].values, idata.loc[mask, 'Timestamp'].values, is_ic_zc)
        
        if tc_time is not None:
            idx = (np.abs(idata['Timestamp'] - tc_time)).idxmin()
            idata.loc[idx, 'TC'] = idata.loc[idx, 'Gmax(°/s)']
            
        if ic_alt_time is not None:
            idx_alt = (np.abs(idata['Timestamp'] - ic_alt_time)).idxmin()
            idata.loc[idx_alt, 'IC_alt'] = idata.loc[idx_alt, 'Gmax(°/s)']

    HS_timestamps = sorted(idata[idata['IC'].notna()]['Timestamp'].values)
    TO_timestamps = sorted(idata[idata['TC'].notna()]['Timestamp'].values)

    # # ---------- IC 细化（ACC.Y 波谷 × 0.7 + 陀螺仪波谷 × 0.3）----------
    # # 对每个粗检 IC，在动态窗口（相邻 IC 间距 15%）内找 ACC.Y 最小谷，
    # # 同时在窗口内找最近陀螺仪谷底，加权融合得到更精确的 IC
    # HS_refined = list(HS_timestamps)
    # for i, ic_time in enumerate(HS_timestamps):
    #     if len(HS_timestamps) < 2:
    #         continue
    #     if i < len(HS_timestamps) - 1:
    #         window_size = (HS_timestamps[i + 1] - HS_timestamps[i]) * 0.15
    #     else:
    #         window_size = (HS_timestamps[i] - HS_timestamps[i - 1]) * 0.15

    #     window = idata[(idata['Timestamp'] >= ic_time - window_size) &
    #                    (idata['Timestamp'] <= ic_time + window_size)]
    #     if window.empty:
    #         continue

    #     # ACC.Y 最小谷（负峰）
    #     acc_y_vals = window['ACC.Y'].values
    #     acc_y_valleys, _ = find_peaks(-acc_y_vals, distance=5)
    #     if len(acc_y_valleys) == 0:
    #         continue
    #     min_valley_local = acc_y_valleys[np.argmin(acc_y_vals[acc_y_valleys])]
    #     acc_y_valley_time = window['Timestamp'].iloc[min_valley_local]

    #     # 陀螺仪最近谷底
    #     gyro_vals = window['Gmax(°/s)'].values
    #     gyro_valleys, _ = find_peaks(-gyro_vals, distance=5)
    #     if len(gyro_valleys) > 0:
    #         gyro_valley_times = window['Timestamp'].iloc[gyro_valleys].values
    #         closest_local = gyro_valleys[np.argmin(np.abs(gyro_valley_times - ic_time))]
    #         gyro_valley_time = window['Timestamp'].iloc[closest_local]
    #     else:
    #         gyro_valley_time = ic_time

    #     # 加权融合（ACC.Y 主导 70%，陀螺仪辅助 30%）
    #     fused = 0.7 * acc_y_valley_time + 0.3 * gyro_valley_time
    #     nearest_idx = (np.abs(idata['Timestamp'] - fused)).idxmin()
    #     HS_refined[i] = int(idata.loc[nearest_idx, 'Timestamp'])

    # HS_timestamps = sorted(set(HS_refined))

    # ---------- TC 细化（ACC.Z 波峰 × 0.3 + 陀螺仪谷底 × 0.7）----------
    # 对每个粗检 TC，在动态窗口（相邻 TC 间距 15%）内找 ACC.Z 最大正峰，
    # 与陀螺仪给出的 TC 时刻加权融合，得到更精确的 TC
    TO_refined = list(TO_timestamps)
    for i, tc_time in enumerate(TO_timestamps):
        # 动态窗口：用相邻 TC 间距的 15%，TC 不足两个时跳过细化
        if len(TO_timestamps) < 2:
            continue
        if i < len(TO_timestamps) - 1:
            window_size = (TO_timestamps[i + 1] - TO_timestamps[i]) * 0.15
        else:
            window_size = (TO_timestamps[i] - TO_timestamps[i - 1]) * 0.15

        window = idata[(idata['Timestamp'] >= tc_time - window_size) &
                       (idata['Timestamp'] <= tc_time + window_size)]
        if window.empty:
            continue

        acc_z_vals = window['ACC.Z'].values
        peaks, _ = find_peaks(acc_z_vals, distance=5)
        if len(peaks) == 0:
            continue

        # 选窗口内最大 ACC.Z 正峰
        max_peak_local = peaks[np.argmax(acc_z_vals[peaks])]
        acc_z_peak_time = window['Timestamp'].iloc[max_peak_local]

        # 加权融合（陀螺仪主导 70%，ACC.Z 辅助 30%）
        fused = 0.3 * acc_z_peak_time + 0.7 * tc_time
        nearest_idx = (np.abs(idata['Timestamp'] - fused)).idxmin()
        TO_refined[i] = int(idata.loc[nearest_idx, 'Timestamp'])

    TO_timestamps = sorted(set(TO_refined))

    # --------- 1️⃣ method
    MS_timestamps = []
    MS_values = []
    data_end = int(idata['Timestamp'].iloc[-1])
    for hs_idx, hs_time in enumerate(HS_timestamps):
        following_TO_times = [to_time for to_time in TO_timestamps if to_time > hs_time]
        if following_TO_times:
            next_TO_time = following_TO_times[0]
        elif hs_idx == len(HS_timestamps) - 1:
            next_TO_time = min(hs_time + 1000, data_end)
        else:
            continue
        # 提取支撑期数据
        mask = (idata['Timestamp'] > hs_time) & (idata['Timestamp'] < next_TO_time)
        support_data = idata.loc[mask].reset_index(drop=True)
        N = len(support_data)
        if N < 5:
            continue
        # --------- 1. 去除前后10% ---------
        ten_percent = int(0.1 * N)
        if N - 2 * ten_percent < 1:
            continue
        core_data = support_data.iloc[ten_percent:N - ten_percent].reset_index(drop=True)
        timestamps = core_data['Timestamp'].values
        # --------- 2. 滑动窗口平均（窗口大小为30%） ---------
        window_ratio = 0.3
        window_size = max(3, int(len(core_data) * window_ratio))
        if window_size % 2 == 0:
            window_size += 1  # 保证是奇数，中心对称
        half_window = window_size // 2
        gyro_energy_mean = []
        for i in range(half_window, len(core_data) - half_window):
            window = core_data.iloc[i - half_window: i + half_window + 1]
            mean_energy = window['gyroscopic_energy'].mean()
            gyro_energy_mean.append(mean_energy)
        if not gyro_energy_mean:
            continue
        # --------- 3. 找滑动均值最小处，对应窗口中心时间 ---------
        min_idx = np.argmin(gyro_energy_mean)
        ms_candidate_time = timestamps[min_idx + half_window]
        if ms_candidate_time not in MS_timestamps:
            MS_timestamps.append(int(ms_candidate_time))
            MS_values.append(gyro_energy_mean[min_idx])

    # # ---------- MS 检测（Method 3）----------
    # # 去除支撑期前后 10%，在核心段用滑窗计算角速度能量 T_ω 和加速度方差 T_v，
    # # 以当前窗口方差与相邻步连续性加权融合得到支撑中期时刻
    # MS_timestamps = []
    # a_last = np.array([0.0, 0.0])   # 上一步窗口中心 ACC.XY（用于连续性权重）
    # data_end = int(idata['Timestamp'].iloc[-1])
    # for hs_idx, hs_time in enumerate(HS_timestamps):
    #     following_TO = [t for t in TO_timestamps if t > hs_time]
    #     if following_TO:
    #         next_TO = following_TO[0]
    #     elif hs_idx == len(HS_timestamps) - 1:
    #         # 最后一个 IC 后无 TC：以 IC+1000ms 为右边界，仍尝试检测 MS
    #         next_TO = min(hs_time + 1000, data_end)
    #     else:
    #         continue

    #     mask = (idata['Timestamp'] > hs_time) & (idata['Timestamp'] < next_TO)
    #     stance = idata.loc[mask].reset_index(drop=True)
    #     N_total = len(stance)
    #     if N_total < 10:
    #         continue

    #     # 去除前后各 10%
    #     first10 = int(0.1 * N_total)
    #     last10  = int(0.1 * N_total)
    #     if N_total - first10 - last10 <= 5:
    #         continue
    #     mid_core = stance.iloc[first10: N_total - last10].reset_index(drop=True)
    #     core_len = len(mid_core)
    #     times    = mid_core['Timestamp'].values

    #     # 滑窗大小 = 核心长度 30%（奇数）
    #     window_size = max(5, int(core_len * 0.3))
    #     if window_size % 2 == 0:
    #         window_size += 1
    #     half_w = window_size // 2
    #     if core_len <= window_size:
    #         continue

    #     acc_xy_all = mid_core[['ACC.X', 'ACC.Y']].values
    #     gyro_energy = []
    #     acc_var     = []
    #     for i in range(half_w, core_len - half_w):
    #         wdata = mid_core.iloc[i - half_w: i + half_w + 1]
    #         # T_ω：角速度能量
    #         T_omega = ((wdata[['Gyro.X', 'Gyro.Y', 'Gyro.Z']] ** 2).sum(axis=1)).mean()
    #         gyro_energy.append(T_omega)
    #         # T_v：XY 加速度方差
    #         accel    = wdata[['ACC.X', 'ACC.Y']].values
    #         T_v      = np.mean(np.sum((accel - accel.mean(axis=0)) ** 2, axis=1))
    #         acc_var.append(T_v)

    #     if not gyro_energy or not acc_var:
    #         continue

    #     idx_w = int(np.argmin(gyro_energy))
    #     idx_v = int(np.argmin(acc_var))
    #     t_w   = times[idx_w + half_w]
    #     t_v   = times[idx_v + half_w]

    #     # 连续性权重 w
    #     a_curr   = acc_xy_all[idx_w + half_w]
    #     diff_mag = np.linalg.norm(a_last - a_curr)
    #     win      = acc_xy_all[idx_w: idx_w + window_size] if idx_w + window_size <= core_len \
    #                else acc_xy_all[-window_size:]
    #     var_win  = np.mean(np.sum((win - win.mean(axis=0)) ** 2, axis=1))
    #     w        = var_win / (var_win + diff_mag + 1e-6)

    #     # 加权融合并对齐到最近采样点
    #     t_sp_est = w * t_w + (1 - w) * t_v
    #     nearest  = (np.abs(idata['Timestamp'] - t_sp_est)).argmin()
    #     t_sp     = int(idata.loc[nearest, 'Timestamp'])

    #     if t_sp not in MS_timestamps:
    #         MS_timestamps.append(t_sp)
    #         a_last = a_curr.copy()

    idata['MS'] = np.nan
    if MS_timestamps:
        t_arr = idata['Timestamp'].values
        for t, val in zip(MS_timestamps, MS_values):
            idx = np.abs(t_arr - t).argmin()
            idata.loc[idx, 'MS'] = val

    return HS_timestamps, TO_timestamps, MS_timestamps, idata


# ---------------------------------------------------------------------------
# 参数计算函数（参考 scripts/Gait_parameter 结构，按指标拆分）
# ---------------------------------------------------------------------------

def calculate_contact_time(HS_timestamps, TO_timestamps):
    """接触时间 (ms)，返回 [(to_time, contact_time_ms), ...]"""
    contact_time_info = []
    for i in range(len(HS_timestamps) - 1):
        ic_time = HS_timestamps[i]
        next_ic_time = HS_timestamps[i + 1]
        between_TOs = [t for t in TO_timestamps if ic_time < t < next_ic_time]
        if between_TOs:
            closest_to_time = min(between_TOs)
            contact_time = closest_to_time - ic_time
            contact_time_info.append((closest_to_time, contact_time))
    return contact_time_info


def calculate_swing_time(HS_timestamps, TO_timestamps):
    """摆动时间 (ms)，返回 [(to_time, swing_time_ms), ...]"""
    swing_time_info = []
    for to_time in TO_timestamps:
        next_ic_times = [t for t in HS_timestamps if t > to_time]
        if next_ic_times:
            next_ic_time = min(next_ic_times)
            swing_time = next_ic_time - to_time
            swing_time_info.append((to_time, swing_time))
    return swing_time_info


def calculate_stride_time(HS_timestamps, TO_timestamps):
    """步态周期 (ms)，返回 [(to_time, stride_time_ms), ...]"""
    stride_time_info = []
    for i in range(len(HS_timestamps) - 1):
        start_ic = HS_timestamps[i]
        end_ic = HS_timestamps[i + 1]
        between_TOs = [t for t in TO_timestamps if start_ic < t < end_ic]
        if between_TOs:
            closest_to = min(between_TOs)
            stride_time = end_ic - start_ic
            stride_time_info.append((closest_to, stride_time))
    return stride_time_info


def calculate_step_frequency(HS_timestamps, TO_timestamps):
    """步频 (Hz)，返回 [(to_time, freq_hz), ...]，公式 2.0/(stride_time_s)"""
    step_frequency_info = []
    stride_times_info = calculate_stride_time(HS_timestamps, TO_timestamps)
    for closest_to, stride_time in stride_times_info:
        if stride_time > 0:
            step_frequency = 2.0 / (stride_time / 1000.0)
            step_frequency_info.append((closest_to, step_frequency))
    return step_frequency_info


def infer_takeoff_step_by_cadence_drop(
    HS_timestamps,
    TO_timestamps,
    min_drop_ratio=0.20,
    align_tol_ms=120.0,
    search_last_n=6,
):
    """
    自动估计跳远起跳步（第几个 TC，1-based）：在助跑段步频相对稳定后，
    第一次出现相对基线明显骤降的那一步，视为起跳（腾空导致步态周期拉长 → 瞬时步频下降）。

    基线取当前步之前若干步频的中位数（最多看前 3 步），降幅 = (基线 - 当前) / 基线。

    返回 (takeoff_step_1based 或 -1, 说明字符串)。
    """
    TO = sorted(float(t) for t in TO_timestamps)
    if len(TO) < 3:
        return -1, "数据不足"
    sf_info = calculate_step_frequency(HS_timestamps, TO_timestamps)
    if len(sf_info) < 2:
        return -1, "数据不足"
    sf_sorted = sorted(sf_info, key=lambda x: float(x[0]))
    freqs = np.array([float(f) for _, f in sf_sorted], dtype=np.float64)
    to_times = [float(t) for t, _ in sf_sorted]

    best_score = -1.0
    best_i = -1
    search_start = max(1, len(freqs) - search_last_n) if search_last_n > 0 else 1
    for i in range(search_start, len(freqs)):
        window = freqs[max(0, i - 3) : i]
        baseline = float(np.median(window)) if window.size > 0 else freqs[i - 1]
        prev = max(float(baseline), 0.05)
        drop = (prev - freqs[i]) / prev
        if drop > best_score:
            best_score = drop
            best_i = i

    if best_i < 0 or best_score < min_drop_ratio:
        return -1, "未识别到起跳步"

    t_takeoff = to_times[best_i]
    dists = [abs(TO[k] - t_takeoff) for k in range(len(TO))]
    j = int(np.argmin(dists))
    if dists[j] > align_tol_ms:
        return -1, "对齐失败"
    n = j + 1
    print(
        f"GAIT_LOG_INFO: auto takeoff step={n} (cadence drop {best_score:.1%}, "
        f"seq_idx={best_i})"
    )
    return n, ""


def calculate_gait_status(contact_time_info, swing_time_info):
    """步态状态、腾空时间、双支撑时间，返回 (gait_status_info, flight_time_info, double_support_time_info)"""
    gait_status_info = []
    flight_time_info = []
    double_support_time_info = []
    sw_dict = dict(swing_time_info)
    for to_time, contact_time in contact_time_info:
        swing_time = sw_dict.get(to_time)
        if swing_time is not None:
            flight_time = max(0, (swing_time - contact_time) / 2.0)
            if swing_time > contact_time and flight_time > 0:
                gait_status_info.append((to_time, "Run"))
                flight_time_info.append((to_time, flight_time))
            else:
                gait_status_info.append((to_time, "Walk"))
                double_support_time = max(0, (contact_time - swing_time) / 2.0)
                double_support_time_info.append((to_time, double_support_time))
    return gait_status_info, flight_time_info, double_support_time_info


def calculate_stride_length(idata, HS_timestamps, TO_timestamps, MS_timestamps):
    """步幅 (m)，ZUPT 积分 + 线性漂移修正，返回 [(to_time, length_m), ...]

    与 reference spatio_temporal_parameter.py 完全对齐：
      - 积分窗口 = MS[i] → MS[i+1]（两端 foot-flat，近似零速）
      - 前向梯形积分加速度 → 速度
      - 线性漂移修正：corrected[j] = raw[j] - (j/(n-1)) * raw[-1]
      - 再次梯形积分修正后速度 → 位移
      - 步幅 = sqrt(ΔX² + ΔY²)
    """
    stride_length_info = []

    # 1. 获取 MS 在 numpy 数组中的索引
    ts_all = idata['Timestamp'].values
    MS_indices = []
    for t in MS_timestamps:
        idx_arr = np.where(ts_all == t)[0]
        if len(idx_arr) > 0:
            MS_indices.append(idx_arr[0])

    if len(MS_indices) < 1 or len(HS_timestamps) < 2:
        return stride_length_info

    ts_sec = ts_all / 1000.0
    ax_all = (idata['ACC.X'] * GRAVITY).values
    ay_all = (idata['ACC.Y'] * GRAVITY).values
    az_all = (idata['ACC.Z'] * GRAVITY).values

    vX_global = np.zeros(len(idata))
    vY_global = np.zeros(len(idata))
    vZ_global = np.zeros(len(idata))

    # 2. MS 到 MS 之间的 ZUPT 修正积分
    if len(MS_indices) >= 2:
        for i in range(len(MS_indices) - 1):
            start_idx = MS_indices[i]
            end_idx   = MS_indices[i + 1]
            n = end_idx - start_idx + 1
            if n < 2:
                continue

            vX = [0.0]
            vY = [0.0]
            vZ = [0.0]

            for j in range(1, n):
                idx_curr = start_idx + j
                idx_prev = start_idx + j - 1
                dt = ts_sec[idx_curr] - ts_sec[idx_prev]
                vX.append(vX[-1] + (ax_all[idx_curr] + ax_all[idx_prev]) / 2.0 * dt)
                vY.append(vY[-1] + (ay_all[idx_curr] + ay_all[idx_prev]) / 2.0 * dt)
                vZ.append(vZ[-1] + (az_all[idx_curr] + az_all[idx_prev]) / 2.0 * dt)

            for j in range(n):
                wj = j / (n - 1) if n > 1 else 0.0
                idx_global = start_idx + j
                vX_global[idx_global] = vX[j] - wj * vX[-1]
                vY_global[idx_global] = vY[j] - wj * vY[-1]
                vZ_global[idx_global] = vZ[j] - wj * vZ[-1]

            if i == len(MS_indices) - 2:
                import matplotlib.pyplot as plt
                plot_t = ts_sec[start_idx:end_idx+1]
                plot_vx = vX_global[start_idx:end_idx+1]
                plot_vy = vY_global[start_idx:end_idx+1]
                plot_vz = vZ_global[start_idx:end_idx+1]
                
                plt.figure(figsize=(10, 5))
                plt.rcParams['font.sans-serif'] = ['Arial Unicode MS', 'SimHei', 'DejaVu Sans']
                plt.rcParams['axes.unicode_minus'] = False
                plt.plot(plot_t, plot_vx, label='vX')
                plt.plot(plot_t, plot_vy, label='vY')
                plt.plot(plot_t, plot_vz, label='vZ')
                plt.title(f"最后一个 MS ({plot_t[0]:.2f}s) 到 MS ({plot_t[-1]:.2f}s) 的三轴速度变化图")
                plt.xlabel("时间 (s)")
                plt.ylabel("速度 (m/s)")
                plt.legend()
                plt.grid(True)
                plt.tight_layout()
                plt.show(block=False)

    # 3. 对第一个 MS 之前的数据进行逆向积分（假设起始速度为0）
    first_ms_idx = MS_indices[0]
    for i in range(first_ms_idx - 1, -1, -1):
        dt = ts_sec[i + 1] - ts_sec[i]
        vX_global[i] = vX_global[i + 1] - (ax_all[i + 1] + ax_all[i]) / 2.0 * dt
        vY_global[i] = vY_global[i + 1] - (ay_all[i + 1] + ay_all[i]) / 2.0 * dt
        vZ_global[i] = vZ_global[i + 1] - (az_all[i + 1] + az_all[i]) / 2.0 * dt

    # 4. 对最后一个 MS 之后的数据进行正向积分（起始速度为0）
    last_ms_idx = MS_indices[-1]
    for i in range(last_ms_idx + 1, len(idata)):
        dt = ts_sec[i] - ts_sec[i - 1]
        vX_global[i] = vX_global[i - 1] + (ax_all[i] + ax_all[i - 1]) / 2.0 * dt
        vY_global[i] = vY_global[i - 1] + (ay_all[i] + ay_all[i - 1]) / 2.0 * dt
        vZ_global[i] = vZ_global[i - 1] + (az_all[i] + az_all[i - 1]) / 2.0 * dt

    # 5. 在 HS 到 HS 之间进行位移积分计算步幅
    HS = sorted(float(x) for x in HS_timestamps)
    for i in range(len(HS) - 1):
        hs_start = HS[i]
        hs_end = HS[i+1]

        # 寻找对应的 start_idx 和 end_idx
        idx_start_arr = np.where(ts_all == hs_start)[0]
        idx_end_arr = np.where(ts_all == hs_end)[0]
        if len(idx_start_arr) == 0 or len(idx_end_arr) == 0:
            continue
            
        idx_start = idx_start_arr[0]
        idx_end = idx_end_arr[0]
        
        if idx_start >= idx_end:
            continue

        ts_seg = ts_sec[idx_start:idx_end+1]
        vx_seg = vX_global[idx_start:idx_end+1]
        vy_seg = vY_global[idx_start:idx_end+1]
        vz_seg = vZ_global[idx_start:idx_end+1]
        
        n_seg = len(ts_seg)
        if n_seg < 2:
            continue
            
        lX = 0.0
        lY = 0.0
        lZ = 0.0
        for j in range(1, n_seg):
            dt = ts_seg[j] - ts_seg[j-1]
            lX += (vx_seg[j] + vx_seg[j-1]) / 2.0 * dt
            lY += (vy_seg[j] + vy_seg[j-1]) / 2.0 * dt
            lZ += (vz_seg[j] + vz_seg[j-1]) / 2.0 * dt
            
        stride_length = float(np.sqrt(lX**2 + lY**2 + lZ**2))
        
        # 找到 HS 到 HS 之间的那一次 TO，作为标识
        between_TOs = [t for t in TO_timestamps if hs_start < t < hs_end]
        if between_TOs:
            to_t = between_TOs[0]
            stride_length_info.append((to_t, stride_length))

    return stride_length_info


def calculate_vGRF(gait_status_info, flight_time_info, double_support_time_info, contact_time_info):
    """vGRF 峰值 (BW)，返回 vGRF_peak_info"""
    vGRF_peak_info = []
    ft_dict = dict(flight_time_info)
    dst_dict = dict(double_support_time_info)
    ct_dict = dict(contact_time_info)

    for to_time, gait_status in gait_status_info:
        contact_time = ct_dict.get(to_time)
        if contact_time is None or contact_time <= 0:
            continue
        tc_sec = contact_time / 1000.0

        if gait_status == "Run":
            flight_time = ft_dict.get(to_time, 0)
            tf_sec = flight_time / 1000.0
        else:
            double_support = dst_dict.get(to_time, 0)
            tf_sec = abs(double_support) / 1000.0

        vgrf_peak_bw = (np.pi / 2.0) * (tf_sec / tc_sec + 1.0)
        vGRF_peak_info.append((to_time, vgrf_peak_bw))

    return vGRF_peak_info


def filter_to_for_long_jump(TO_timestamps, takeoff_step_1based):
    """
    跳远模式：第 takeoff_step_1based 个 TO（TC，1-based 时间序）视为起跳 TC；
    该次起跳之后不再存在 TC，故仅保留时间序上第 1～n 个 TO（含起跳），其后的 TO 全部丢弃。

    返回 (filtered_TO_sorted, applied: bool)。applied 为 False 时表示未改 TO（未开启、非法或 TO 不足）。
    """
    TO = sorted(float(t) for t in TO_timestamps)
    n = int(takeoff_step_1based)
    if n <= 0:
        return TO, False
    if len(TO) < n:
        print(
            f"GAIT_LOG_WARN: long_jump takeoff step {n} exceeds TO count {len(TO)}, skip filter"
        )
        return TO, False
    # 起跳 TC 之后不再有 TC：只保留 TO[0..n-1]
    return TO[:n], True


def filter_hs_for_long_jump(HS_timestamps, TO_timestamps, takeoff_step_1based):
    """
    第 n 个 TO 为起跳时：保留该 TO 时刻 t_cut 之前的所有 IC（HS），
    t_cut 之后仅保留时间上最后一个 IC（落地），去掉腾空段多检的 IC。

    返回 (filtered_HS_sorted_int, applied: bool)。TO 不足 n 时不改 HS。
    """
    HS = sorted(float(t) for t in HS_timestamps)
    TO = sorted(float(t) for t in TO_timestamps)
    n = int(takeoff_step_1based)
    if n <= 0:
        return [int(round(h)) for h in HS], False
    if len(TO) < n:
        print(
            f"GAIT_LOG_WARN: long_jump HS filter: step {n} exceeds TO count {len(TO)}, skip HS filter"
        )
        return [int(round(h)) for h in HS], False
    t_cut = TO[n - 1]
    pre = [h for h in HS if h <= t_cut]
    post = [h for h in HS if h > t_cut]
    if not post:
        return [int(round(h)) for h in HS], True
    return [int(round(h)) for h in pre + [post[-1]]], True


def filter_ms_for_long_jump(MS_timestamps, TO_timestamps, takeoff_step_1based, HS_timestamps=None):
    """
    跳远起跳 TC 之后：
    如果在最后的着陆 IC 之后没有检测到 MS（通常因为数据提前结束），直接将最后的 IC 当作 MS。
    防止在起跳后的腾空阶段截取到误检测的噪音 MS。
    """
    MS = sorted(float(t) for t in MS_timestamps)
    TO = sorted(float(t) for t in TO_timestamps)
    n = int(takeoff_step_1based)
    if n <= 0:
        return [int(round(m)) for m in MS], False
    if len(TO) < n:
        return [int(round(m)) for m in MS], False
    
    t_cut = TO[n - 1]
    pre = [m for m in MS if m <= t_cut]
    
    # 获取着陆的 IC
    final_ms = None
    if HS_timestamps is not None:
        HS = sorted(float(t) for t in HS_timestamps)
        post_hs = [h for h in HS if h > t_cut]
        if post_hs:
            last_ic = post_hs[-1]
            # 看在这个 last_ic 之后，是否有真实的 MS
            ms_after_ic = [m for m in MS if m > last_ic]
            if ms_after_ic:
                final_ms = ms_after_ic[-1]
            else:
                final_ms = last_ic  # 如果由于末尾数据过短没检测出MS，把IC当做MS
                
    if final_ms is not None:
        return [int(round(m)) for m in pre + [final_ms]], True
    else:
        return [int(round(m)) for m in pre], True
def filter_msw_for_long_jump(MSW_timestamps, TO_timestamps, takeoff_step_1based):
    """
    起跳 TC 之后只保留时间上最后一个 MSW（与落地侧 IC 一致）。
    """
    MSW = sorted(float(t) for t in MSW_timestamps)
    TO = sorted(float(t) for t in TO_timestamps)
    n = int(takeoff_step_1based)
    if n <= 0:
        return [int(round(x)) for x in MSW], False
    if len(TO) < n:
        return [int(round(x)) for x in MSW], False
    t_cut = TO[n - 1]
    pre = [x for x in MSW if x <= t_cut]
    post = [x for x in MSW if x > t_cut]
    if not post:
        return [int(round(x)) for x in MSW], True
    return [int(round(x)) for x in pre + [post[-1]]], True


def sync_idata_ic_tc_to_event_lists(idata, HS_list, TO_list):
    """仅在与 HS_list、TO_list 对齐的最近邻采样行保留 IC/TC 标记（供前端曲线叠加）。"""
    idata = idata.copy()
    idata['IC'] = np.nan
    idata['TC'] = np.nan
    gcol = 'Gmax(°/s)'
    for t in HS_list:
        idx = (np.abs(idata['Timestamp'] - t)).idxmin()
        idata.loc[idx, 'IC'] = idata.loc[idx, gcol]
    for t in TO_list:
        idx = (np.abs(idata['Timestamp'] - t)).idxmin()
        idata.loc[idx, 'TC'] = idata.loc[idx, gcol]
    return idata


def sync_idata_msw_to_event_list(idata, MSW_timestamps_kept):
    """仅在与 MSW_timestamps_kept 对齐的最近邻采样行保留 MSW（与 IC/TC 同步策略一致，保留原检测幅值）。"""
    idata = idata.copy()
    mask = idata['MSW'].notna()
    if not mask.any():
        return idata
    orig_times = idata.loc[mask, 'Timestamp'].astype(float).values
    orig_vals = idata.loc[mask, 'MSW'].values
    idata['MSW'] = np.nan
    for t in MSW_timestamps_kept:
        idx = (np.abs(idata['Timestamp'] - t)).idxmin()
        row_t = float(idata.loc[idx, 'Timestamp'])
        j = int(np.argmin(np.abs(orig_times - row_t)))
        idata.loc[idx, 'MSW'] = float(orig_vals[j])
    return idata


def sync_idata_ms_to_event_list(idata, MS_timestamps_kept):
    """仅在与 MS_timestamps_kept 对齐的最近邻采样行保留 MS。"""
    idata = idata.copy()
    mask = idata['MS'].notna()
    if not mask.any():
        return idata
    orig_times = idata.loc[mask, 'Timestamp'].astype(float).values
    orig_vals = idata.loc[mask, 'MS'].values
    idata['MS'] = np.nan
    for t in MS_timestamps_kept:
        idx = (np.abs(idata['Timestamp'] - t)).idxmin()
        row_t = float(idata.loc[idx, 'Timestamp'])
        j = int(np.argmin(np.abs(orig_times - row_t)))
        idata.loc[idx, 'MS'] = float(orig_vals[j])
    return idata


def calculate_spatio_temporal(HS, TO, MS, idata, is_long_jump=False):
    """编排器：调用各独立函数，按 contact_time_info 遍历组装 stride 字典"""
    ct_info = calculate_contact_time(HS, TO)
    sw_info = calculate_swing_time(HS, TO)
    st_info = calculate_stride_time(HS, TO)
    sf_info = calculate_step_frequency(HS, TO)
    gs_info, ft_info, dst_info = calculate_gait_status(ct_info, sw_info)
    sl_info = calculate_stride_length(idata, HS, TO, MS)
    vgrf_info = calculate_vGRF(gs_info, ft_info, dst_info, ct_info)

    ct_dict = dict(ct_info)
    sw_dict = dict(sw_info)
    st_dict = dict(st_info)
    sf_dict = dict(sf_info)
    gs_dict = dict(gs_info)
    ft_dict = dict(ft_info)
    dst_dict = dict(dst_info)
    sl_dict = dict(sl_info)
    vgrf_dict = dict(vgrf_info)

    strides = []
    for i, (to_time, contact_time) in enumerate(ct_info):
        before = [h for h in HS if h < to_time]
        after = [h for h in HS if h > to_time]
        hs_start = max(before) if before else None
        hs_next = min(after) if after else None
        if hs_start is None or hs_next is None:
            continue

        stride_time = st_dict.get(to_time, hs_next - hs_start)
        swing_time = sw_dict.get(to_time, hs_next - to_time)
        frequency_hz = sf_dict.get(to_time, 0)
        stride_length = sl_dict.get(to_time, 0)
        gait_status = gs_dict.get(to_time, "Walk")
        flight_time = ft_dict.get(to_time, 0)
        double_support = dst_dict.get(to_time, 0)
        vgrf_peak_bw = vgrf_dict.get(to_time, 1.0)

        # 针对跳远起跳步的特殊逻辑：起跳步无摆动时间，TC 到 IC 均为腾空时间
        if is_long_jump and i == len(ct_info) - 1:
            swing_time = 0.0
            flight_time = hs_next - to_time
            gait_status = "Jump"
            if contact_time > 0:
                vgrf_peak_bw = (np.pi / 2.0) * ((flight_time / 1000.0) / (contact_time / 1000.0) + 1.0)

        stride_velocity = stride_length / (stride_time / 1000.0) if stride_time > 0 else 0

        strides.append({
            "hs_timestamp_ms": int(hs_start),
            "to_timestamp_ms": int(to_time),
            "stride_time_s": float(stride_time / 1000.0),
            "contact_time_s": float(contact_time / 1000.0),
            "swing_time_s": float(swing_time / 1000.0),
            "step_frequency_hz": float(frequency_hz),
            "stride_length_m": float(stride_length),
            "stride_velocity_mps": float(stride_velocity),
            "vGRF_peak_BW": float(vgrf_peak_bw),
            "double_support_time_s": float(max(0, double_support) / 1000.0),
            "flight_time_s": float(max(0, flight_time) / 1000.0),
            "gait_status": gait_status
        })
    return strides

def _parse_long_jump_takeoff_step(val):
    """解析起跳步：-1 关闭，0 自动识别，≥1 为手动第 N 个 TC。"""
    if val is None:
        return -1
    try:
        x = int(float(val))
        return x
    except (TypeError, ValueError):
        return -1


def _parse_long_jump_is_takeoff_foot(val):
    """是否佩戴在起跳脚一侧：自动识别起跳步仅在此为 True 时执行。默认 True（兼容旧参数）。"""
    if val is None:
        return True
    if isinstance(val, bool):
        return val
    if isinstance(val, (int, float)) and not isinstance(val, bool):
        return int(val) != 0
    s = str(val).strip().lower()
    if s in ("0", "false", "no", "off", ""):
        return False
    if s in ("1", "true", "yes", "on"):
        return True
    return True


def cross_leg_hop_correction(idata_L, idata_R):
    df_l = idata_L.copy()
    df_r = idata_R.copy()
    
    def process_hop_for_primary_leg(primary_df, secondary_df):
        import scipy.signal as signal
        prim_df = primary_df.copy()
        sec_df = secondary_df.copy()
        
        gmax_vals = prim_df['Gmax(°/s)'].values
        timestamps = prim_df['Timestamp'].values
        
        gyro_noise_level = prim_df['Gmax(°/s)'].std() * 0.5
        peaks, _ = signal.find_peaks(gmax_vals, height=gyro_noise_level, prominence=gyro_noise_level * 0.5)
        
        m_shapes = []
        for i in range(len(peaks) - 1):
            t1 = timestamps[peaks[i]]
            t2 = timestamps[peaks[i+1]]
            if t2 - t1 <= 300:
                m_shapes.append((t1, t2))
                
        ic_times = prim_df[prim_df['IC'].notna()]['Timestamp'].values
        
        for i in range(len(ic_times) - 1):
            t_ic_start = ic_times[i]
            t_ic_end = ic_times[i+1]
            
            has_m_shape = False
            for (m_t1, m_t2) in m_shapes:
                if m_t1 >= t_ic_start and m_t2 <= t_ic_end:
                    has_m_shape = True
                    break
                    
            if has_m_shape:
                sec_mask = (sec_df['Timestamp'] >= t_ic_start) & (sec_df['Timestamp'] <= t_ic_end)
                sec_df.loc[sec_mask & sec_df['IC'].notna(), 'IC'] = np.nan
                sec_df.loc[sec_mask & sec_df['TC'].notna(), 'TC'] = np.nan
                sec_df.loc[sec_mask & sec_df['MSW'].notna(), 'MSW'] = np.nan
                if 'MS' in sec_df.columns:
                    sec_df.loc[sec_mask & sec_df['MS'].notna(), 'MS'] = np.nan
                sec_df.loc[sec_mask & sec_df['TC_raw'].notna(), 'TC_raw'] = np.nan
                
                tcs_after_hop = prim_df[(prim_df['Timestamp'] > t_ic_end) & prim_df['TC'].notna()]['Timestamp'].values
                if len(tcs_after_hop) > 0:
                    tc_after_m = tcs_after_hop[0]
                    ics_post_hop = prim_df[(prim_df['Timestamp'] > tc_after_m) & prim_df['IC'].notna()]['Timestamp'].values
                    if len(ics_post_hop) > 0:
                        final_ic = ics_post_hop[-1]
                        prim_mask = (prim_df['Timestamp'] > tc_after_m) & (prim_df['Timestamp'] < final_ic)
                        prim_df.loc[prim_mask & prim_df['IC'].notna(), 'IC'] = np.nan
                        prim_df.loc[prim_mask & prim_df['TC'].notna(), 'TC'] = np.nan
                        if 'MSW' in prim_df.columns:
                            prim_df.loc[prim_mask & prim_df['MSW'].notna(), 'MSW'] = np.nan
                        if 'MS' in prim_df.columns:
                            prim_df.loc[prim_mask & prim_df['MS'].notna(), 'MS'] = np.nan
                        prim_df.loc[prim_mask & prim_df['TC_raw'].notna(), 'TC_raw'] = np.nan
                
        return prim_df, sec_df

    df_r_clean, df_l_clean = process_hop_for_primary_leg(df_r, df_l)
    return df_l_clean, df_r_clean

def process_gait_data(
    file_path,
    weight_kg=75.0,
    start_time_s=-1.0,
    end_time_s=-1.0,
    long_jump_takeoff_step=-1,
    long_jump_is_takeoff_foot=True,
    is_triple_jump=False
):
    try:
        print(f"GAIT_LOG_START: Processing file {file_path}")

        # 容错：Xsens 离线导出文件在表头前可能存在若干元数据行，
        # 找到含 PacketCounter 或 SampleTimeFine 的行作为真正的表头行。
        skip = 0
        try:
            with open(file_path, 'r', encoding='utf-8', errors='ignore') as _f:
                for _i, _line in enumerate(_f):
                    if _i >= 200:
                        break
                    if 'PacketCounter' in _line or 'SampleTimeFine' in _line:
                        skip = _i
                        break
        except Exception:
            skip = 0

        idata = pd.read_csv(file_path, skiprows=skip if skip > 0 else None)
        idata.columns = idata.columns.str.strip()
        num_rows = len(idata)
        print(f"GAIT_LOG_INFO: Total rows read: {num_rows}, header_skip={skip}")
        
        if 'SampleTimeFine' not in idata.columns:
            print("GAIT_LOG_ERROR: Missing SampleTimeFine column")
            return json.dumps({"ok": False, "error": "CSV 缺少 SampleTimeFine 列"})

        # 丢包检查逻辑
        t_diffs = idata['SampleTimeFine'].diff().dropna()
        if not t_diffs.empty:
            dt_avg = t_diffs.mean()
            dt_std = t_diffs.std()
            max_gap = t_diffs.max()
            # 60Hz 采样下，dt 应该是 16666 us (如果是微秒) 或 0.01666 s (如果是秒)
            # 允许 10% 的抖动
            expected_dt = 16666 if dt_avg > 500 else (1.0/60.0 if dt_avg < 0.5 else 16.66)
            gaps = t_diffs[t_diffs > expected_dt * 1.5]
            packet_loss_ratio = len(gaps) / num_rows if num_rows > 0 else 0
            print(f"GAIT_LOG_PACKET: dt_avg={dt_avg:.4f}, dt_std={dt_std:.4f}, max_gap={max_gap:.4f}")
            print(f"GAIT_LOG_PACKET: Potential gaps count: {len(gaps)}, Loss ratio estimate: {packet_loss_ratio:.2%}")
        
        t0_local = idata['PacketCounter'].iloc[0]
        dt_series = idata['SampleTimeFine'].diff().dropna()
        dt_avg = dt_series.mean() if not dt_series.empty else 16666.0
        
        # 启发式判断时间单位
        time_scale = 1.0
        if dt_avg > 500: # 可能是微秒 (60Hz dt~16666, 120Hz dt~8333)
            time_scale = 0.001
            unit_guess = "MICROSECONDS"
        elif dt_avg > 0.5: # 可能是毫秒 (60Hz dt~16.6, 120Hz dt~8.3)
            time_scale = 1.0
            unit_guess = "MILLISECONDS"
        else: # 可能是秒 (60Hz dt~0.0166, 120Hz dt~0.0083)
            time_scale = 1000.0
            unit_guess = "SECONDS"

        dt_ms = dt_avg * time_scale
        print(f"DEBUG: dt_avg={dt_avg:.6f}, Guessing unit: {unit_guess}, time_scale={time_scale}, dt_ms={dt_ms}")
        
        # 展开 PacketCounter 防止因为 16 bit 导致的中途溢出重置（+65536）
        raw_pc = idata['PacketCounter']
        diff_pc = raw_pc.diff().fillna(0)
        diff_pc[diff_pc < 0] += 65536
        unwrapped_pc = diff_pc.cumsum() + raw_pc.iloc[0]
            
        idata['Timestamp'] = np.round((unwrapped_pc - t0_local) * dt_ms).astype(np.int64)
        
        # 应用时间范围裁剪
        if start_time_s >= 0:
            start_ms = start_time_s * 1000.0
            idata = idata[idata['Timestamp'] >= start_ms]
        if end_time_s >= 0:
            end_ms = end_time_s * 1000.0
            idata = idata[idata['Timestamp'] <= end_ms]
            
        if idata.empty:
            return json.dumps({"ok": False, "error": "裁剪后的数据为空，请检查时间范围"})
            
        idata = idata.copy()
        idata.reset_index(drop=True, inplace=True)
            
        fmt = _detect_format(idata)
        print(f"GAIT_LOG_INFO: Detected CSV format: {fmt}")

        if fmt == 'offline':
            # 离线格式：Acc_X/Y/Z 为局部坐标系原始加速度（m/s²），需用四元数旋转到全局坐标系后去重力
            acc_array = idata[['Acc_X', 'Acc_Y', 'Acc_Z']].to_numpy(dtype=float) / GRAVITY
            quat_array = idata[['Quat_W', 'Quat_X', 'Quat_Y', 'Quat_Z']].to_numpy(dtype=float)
            
            # --- 增加：离线数据自动 Heading Reset ---
            # 提取首帧的航向角（Yaw），构建绕 Z 轴的逆补偿四元数，将所有姿态的朝向归零
            if len(quat_array) > 0:
                q0 = quat_array[0]
                w0, x0, y0, z0 = q0
                # 取首帧在 ENU 坐标系下的水平 Yaw 角
                yaw0 = np.arctan2(2.0 * (w0 * z0 + x0 * y0), 1.0 - 2.0 * (y0**2 + z0**2))
                # 构建补偿四元数：绕 Z 轴反向旋转 yaw0
                q_offset = np.array([np.cos(-yaw0 / 2.0), 0.0, 0.0, np.sin(-yaw0 / 2.0)])
                for i in range(len(quat_array)):
                    quat_array[i] = _quaternion_multiply(q_offset, quat_array[i])
            # --------------------------------------

            rotated = np.zeros_like(acc_array)
            for i in range(len(idata)):
                rotated[i] = _rotate_vector_by_quaternion(acc_array[i], quat_array[i])
            idata['ACC.X'] = rotated[:, 0]
            idata['ACC.Y'] = rotated[:, 1]
            idata['ACC.Z'] = rotated[:, 2] - 1.0  # 去除重力分量（全局 Z 轴）
            # 角速度：Gyr_X/Y/Z 已是 deg/s，直接使用
            idata['Gmax(°/s)'] = idata['Gyr_Y']
            idata['Gyro.X'] = idata['Gyr_X']
            idata['Gyro.Y'] = idata['Gyr_Y']
            idata['Gyro.Z'] = idata['Gyr_Z']
        else:
            # 在线格式：freeAccX/Y/Z 已是全局坐标系自由加速度（m/s²）
            idata['ACC.X'] = idata['freeAccX'] / GRAVITY
            idata['ACC.Y'] = idata['freeAccY'] / GRAVITY
            idata['ACC.Z'] = idata['freeAccZ'] / GRAVITY
            # gyroX/Y/Z 由 CsvRecorder 直接写入，单位已是 deg/s，无需换算
            idata['Gmax(°/s)'] = idata['gyroY']
            idata['Gyro.X'] = idata['gyroX']
            idata['Gyro.Y'] = idata['gyroY']
            idata['Gyro.Z'] = idata['gyroZ']

        fs_source = estimate_sample_rate_hz(idata['Timestamp'].values)
        print(f"GAIT_LOG_INFO: Estimated sample rate ≈ {fs_source:.2f} Hz (no resampling)")

        HS, TO, MS, idata = gait_identification(idata, fs=fs_source)
        print(f"DEBUG: Primary Identified events - HS: {len(HS)}, TO: {len(TO)}, MS: {len(MS)}")

        # 解析长跳的起跳发力脚（L/R）
        takeoff_side_req = None
        if isinstance(long_jump_is_takeoff_foot, str) and long_jump_is_takeoff_foot in ['L', 'R']:
            takeoff_side_req = long_jump_is_takeoff_foot
        
        takeoff_raw = _parse_long_jump_takeoff_step(long_jump_takeoff_step)
        is_long_jump_mode = (takeoff_raw >= 0)
        
        # 判断是否需要三级跳专属逻辑（包含跳跃清洗伴飞校准）
        is_triple_jump_mode = getattr(is_triple_jump, '__bool__', lambda: True)() or (isinstance(is_triple_jump, str) and is_triple_jump in ['L', 'R'])
        
        # 判断是否双脚模式（三级跳或者跳远）
        is_dual_leg_mode = is_triple_jump_mode or is_long_jump_mode
        
        idata_contra = None
        if is_dual_leg_mode:
            print(f"GAIT_LOG_INFO: Dual Leg mode enabled (Triple Jump: {is_triple_jump_mode}). Attempting dual leg sync...")
            import os
            basename = os.path.basename(file_path)
            dirname = os.path.dirname(file_path) or '.'
            parts = basename.replace('.csv', '').split('_')
            
            # Find contralateral paired file
            paired_path = None
            if len(parts) >= 3:
                mac, date_str, time_str = parts[0], parts[1], parts[2]
                opposite_mac = 'D422CD007E6E' if '937F' in mac else 'D422CD00937F'
                candidate_files = []
                for fname in os.listdir(dirname):
                    if fname.endswith('.csv') and fname.startswith(opposite_mac):
                        f_parts = fname.replace('.csv', '').split('_')
                        if len(f_parts) >= 3 and f_parts[1] == date_str:
                            try:
                                t1 = int(time_str[:2])*3600 + int(time_str[2:4])*60 + int(time_str[4:6])
                                t2 = int(f_parts[2][:2])*3600 + int(f_parts[2][2:4])*60 + int(f_parts[2][4:6])
                                if abs(t1 - t2) <= 10:
                                    candidate_files.append((abs(t1 - t2), fname))
                            except: pass
                if candidate_files:
                    candidate_files.sort(key=lambda x: x[0])
                    paired_path = os.path.join(dirname, candidate_files[0][1])
            
            if paired_path is not None:
                print(f"GAIT_LOG_INFO: Found paired foot file: {paired_path}")
                try:
                    # 读取并提取对侧文件
                    skip_contra = 0
                    with open(paired_path, 'r', encoding='utf-8', errors='ignore') as _f:
                        for _i, _line in enumerate(_f):
                            if _i >= 200: break
                            if 'PacketCounter' in _line or 'SampleTimeFine' in _line:
                                skip_contra = _i
                                break
                    
                    idata_c = pd.read_csv(paired_path, skiprows=skip_contra if skip_contra > 0 else None)
                    idata_c.columns = idata_c.columns.str.strip()
                    t0_c = idata_c['PacketCounter'].iloc[0]
                    
                    # 保证同频对齐的绝对 PacketCounter 计算
                    raw_pc_c = idata_c['PacketCounter']
                    diff_pc_c = raw_pc_c.diff().fillna(0)
                    diff_pc_c[diff_pc_c < 0] += 65536
                    unwrapped_pc_c = diff_pc_c.cumsum() + raw_pc_c.iloc[0]
                    # 注意：对侧文件的零点使用当前主文件的 t0_local 以保证零时间强行对齐
                    idata_c['Timestamp'] = np.round((unwrapped_pc_c - t0_local) * dt_ms).astype(np.int64)
                    
                    if start_time_s >= 0: idata_c = idata_c[idata_c['Timestamp'] >= start_time_s * 1000.0]
                    if end_time_s >= 0: idata_c = idata_c[idata_c['Timestamp'] <= end_time_s * 1000.0]
                    idata_c.reset_index(drop=True, inplace=True)
                    
                    fmt_c = _detect_format(idata_c)
                    if fmt_c == 'offline':
                        acc_arr_c = idata_c[['Acc_X', 'Acc_Y', 'Acc_Z']].to_numpy(dtype=float) / GRAVITY
                        quat_arr_c = idata_c[['Quat_W', 'Quat_X', 'Quat_Y', 'Quat_Z']].to_numpy(dtype=float)
                        if len(quat_arr_c) > 0:
                            w0, x0, y0, z0 = quat_arr_c[0]
                            yaw0 = np.arctan2(2.0*(w0*z0+x0*y0), 1.0-2.0*(y0**2+z0**2))
                            q_off_c = np.array([np.cos(-yaw0/2.0), 0.0, 0.0, np.sin(-yaw0/2.0)])
                            for i in range(len(quat_arr_c)):
                                quat_arr_c[i] = _quaternion_multiply(q_off_c, quat_arr_c[i])
                        rot_c = np.zeros_like(acc_arr_c)
                        for i in range(len(idata_c)):
                            rot_c[i] = _rotate_vector_by_quaternion(acc_arr_c[i], quat_arr_c[i])
                        idata_c['ACC.X'] = rot_c[:, 0]
                        idata_c['ACC.Y'] = rot_c[:, 1]
                        idata_c['ACC.Z'] = rot_c[:, 2] - 1.0
                        idata_c['Gmax(°/s)'] = idata_c['Gyr_Y']
                        idata_c['Gyro.X'] = idata_c['Gyr_X']
                        idata_c['Gyro.Y'] = idata_c['Gyr_Y']
                        idata_c['Gyro.Z'] = idata_c['Gyr_Z']
                    else:
                        idata_c['ACC.X'] = idata_c['freeAccX'] / GRAVITY
                        idata_c['ACC.Y'] = idata_c['freeAccY'] / GRAVITY
                        idata_c['ACC.Z'] = idata_c['freeAccZ'] / GRAVITY
                        idata_c['Gmax(°/s)'] = idata_c['gyroY']
                    
                    fs_c = estimate_sample_rate_hz(idata_c['Timestamp'].values)
                    _, _, _, idata_c = gait_identification(idata_c, fs=fs_c)
                    
                    if is_triple_jump_mode:
                        # 三级跳：区分左右脚投喂三级跳清洗库
                        if '7E6E' in basename.upper():
                            idata_l, idata_r = idata, idata_c
                        else:
                            idata_l, idata_r = idata_c, idata
                            
                        clean_l, clean_r = cross_leg_hop_correction(idata_l, idata_r)
                        
                        # 替换为主脚结果并刷新事件矩阵
                        idata = clean_l if '7E6E' in basename.upper() else clean_r
                        idata_contra = clean_r if '7E6E' in basename.upper() else clean_l
                    else:
                        # 跳远：不执行伴飞校正，直接使用计算的事件
                        idata_contra = idata_c
                        
                    is_main_left = '7E6E' in basename.upper()
                    
                    HS = sorted(idata[idata['IC'].notna()]['Timestamp'].values)
                    TO = sorted(idata[idata['TC'].notna()]['Timestamp'].values)
                    MS = sorted(idata[idata['MS'].notna()]['Timestamp'].values) if 'MS' in idata.columns else sorted(idata[idata['MSW'].notna()]['Timestamp'].values)
                    print(f"GAIT_LOG_INFO: Dual leg sync complete. Filtered events - HS: {len(HS)}, TO: {len(TO)}, MS: {len(MS)}")
                    
                    # 伴飞脚解析
                    HS_c = sorted(idata_contra[idata_contra['IC'].notna()]['Timestamp'].values)
                    TO_c = sorted(idata_contra[idata_contra['TC'].notna()]['Timestamp'].values)
                    MS_c = sorted(idata_contra[idata_contra['MS'].notna()]['Timestamp'].values) if 'MS' in idata_contra.columns else sorted(idata_contra[idata_contra['MSW'].notna()]['Timestamp'].values)
                    MSW_c = idata_contra[idata_contra['MSW'].notna()]['Timestamp'].values.tolist()
                    strides_c = calculate_spatio_temporal(HS_c, TO_c, MS_c, idata_contra, is_long_jump=False)
                    
                    contra_data = {
                        "strides": strides_c,
                        "signals": {
                            "timestamps": idata_contra["Timestamp"].tolist(),
                            "acc_x": idata_contra["ACC.X"].tolist(),
                            "acc_y": idata_contra["ACC.Y"].tolist(),
                            "acc_z": idata_contra["ACC.Z"].tolist(),
                            "gyro_y": idata_contra["Gmax(°/s)"].tolist(),
                        },
                        "events": {
                            "hs": [int(x) for x in HS_c],
                            "to": [int(x) for x in TO_c],
                            "ms": [int(x) for x in MS_c],
                            "msw": [int(x) for x in MSW_c]
                        },
                        "side_main": "L" if is_main_left else "R",
                        "side_contra": "R" if is_main_left else "L"
                    }
                except Exception as e:
                    print(f"GAIT_LOG_ERROR: Dual leg sync failed, falling back to single leg mode. Exception: {e}")
                    contra_data = None
            else:
                print("GAIT_LOG_WARN: Could not find paired contralateral file. Running in single leg triple jump mode.")
                contra_data = None
        else:
            contra_data = None

        # 判断长跳当前处理的文件是否为起跳脚
        is_takeoff_foot = False
        import os
        fname_upper = os.path.basename(file_path).upper()
        if takeoff_side_req == 'L' and '7E6E' in fname_upper:
            is_takeoff_foot = True
        elif takeoff_side_req == 'R' and '937F' in fname_upper:
            is_takeoff_foot = True
        elif _parse_long_jump_is_takeoff_foot(long_jump_is_takeoff_foot):
            is_takeoff_foot = True
        
        takeoff_raw = _parse_long_jump_takeoff_step(long_jump_takeoff_step)
        takeoff_auto_message = ""
        takeoff_req = -1
        if takeoff_raw == 0:
            if not is_takeoff_foot:
                print(
                    "GAIT_LOG_INFO: long_jump auto takeoff skipped (sensor not on takeoff foot)"
                )
            else:
                inferred, takeoff_auto_message = infer_takeoff_step_by_cadence_drop(
                    HS, TO
                )
                takeoff_req = inferred if inferred > 0 else -1
                if takeoff_req <= 0:
                    print(
                        f"GAIT_LOG_WARN: long_jump auto takeoff failed: {takeoff_auto_message}"
                    )
        elif takeoff_raw > 0:
            takeoff_req = takeoff_raw

        TO_for_metrics = list(TO)
        HS_for_metrics = list(HS)
        MS_for_metrics = list(MS)
        long_jump_applied = False
        if takeoff_req > 0:
            TO_for_metrics, lj_to = filter_to_for_long_jump(TO, takeoff_req)
            HS_for_metrics, lj_hs = filter_hs_for_long_jump(HS, TO, takeoff_req)
            MS_for_metrics, lj_ms = filter_ms_for_long_jump(MS, TO, takeoff_req, HS)
            msw_times = idata[idata['MSW'].notna()]['Timestamp'].astype(float).values.tolist()
            MSW_for_metrics, lj_msw = filter_msw_for_long_jump(msw_times, TO, takeoff_req)
            long_jump_applied = lj_to or lj_hs or lj_ms or lj_msw
            if lj_to and len(TO_for_metrics) != len(TO):
                print(
                    f"GAIT_LOG_INFO: Long jump TO filter: takeoff_step={takeoff_req}, "
                    f"TO count {len(TO)} -> {len(TO_for_metrics)}"
                )
            if lj_hs and len(HS_for_metrics) != len(HS):
                print(
                    f"GAIT_LOG_INFO: Long jump IC filter: after TO#{takeoff_req}, "
                    f"keep last IC only; HS count {len(HS)} -> {len(HS_for_metrics)}"
                )
            if lj_ms and len(MS_for_metrics) != len(MS):
                print(
                    f"GAIT_LOG_INFO: Long jump MS filter: after TO#{takeoff_req}, "
                    f"keep last MS only; MS count {len(MS)} -> {len(MS_for_metrics)}"
                )
            if lj_msw and len(MSW_for_metrics) != len(msw_times):
                print(
                    f"GAIT_LOG_INFO: Long jump MSW filter: after TO#{takeoff_req}, "
                    f"keep last MSW only; MSW count {len(msw_times)} -> {len(MSW_for_metrics)}"
                )
            if takeoff_req > 0 and len(TO) >= takeoff_req:
                idata = sync_idata_ic_tc_to_event_lists(idata, HS_for_metrics, TO_for_metrics)
                idata = sync_idata_msw_to_event_list(idata, MSW_for_metrics)
                idata = sync_idata_ms_to_event_list(idata, MS_for_metrics)
                
                # 如果存在伴飞脚，必须同步裁切伴飞脚以防出现无用的落地后步态
                if contra_data is not None and idata_contra is not None:
                    cutoff_time = TO_for_metrics[-1] if len(TO_for_metrics) > 0 else float('inf')
                    primary_landing_ic = HS_for_metrics[-1] if len(HS_for_metrics) > 0 else cutoff_time
                    
                    c_hs_pre = [t for t in contra_data['events']['hs'] if t <= cutoff_time]
                    
                    # 伴飞脚的落地 IC 必须在主脚落地 IC 附近或之后发生
                    c_hs_post_valid = [t for t in contra_data['events']['hs'] if t >= primary_landing_ic - 300]
                    
                    if c_hs_post_valid:
                        c_landing_ic = c_hs_post_valid[-1]
                    else:
                        # 如果由于数据中断等原因伴飞脚未检测到有效的沙坑 IC（比如只在腾空中误检了噪音），则彻底丢弃误检并强制对齐为主脚落地时间
                        c_landing_ic = primary_landing_ic
                        
                    c_hs = c_hs_pre + [c_landing_ic]
                    
                    c_ms_pre = [t for t in contra_data['events']['ms'] if t <= cutoff_time]
                    ms_after_landing = [m for m in contra_data['events']['ms'] if m > c_landing_ic]
                    
                    if ms_after_landing:
                        c_ms = c_ms_pre + [ms_after_landing[-1]]
                    else:
                        c_ms = c_ms_pre + [c_landing_ic]  # 如果也没有合法的沙坑 MS，等同于沙坑 IC
                    
                    c_to = [t for t in contra_data['events']['to'] if t <= cutoff_time]
                    c_msw = [t for t in contra_data['events']['msw'] if t <= cutoff_time]
                    
                    # 重新生成裁切后的伴飞脚步态参数
                    new_strides_c = calculate_spatio_temporal(c_hs, c_to, c_ms, idata_contra, is_long_jump=False)
                    contra_data['events']['hs'] = c_hs
                    contra_data['events']['to'] = c_to
                    contra_data['events']['ms'] = c_ms
                    contra_data['events']['msw'] = c_msw
                    contra_data['strides'] = new_strides_c

        # 获取 MSW 时间戳用于前端可视化
        MSW = idata[idata['MSW'].notna()]['Timestamp'].values.tolist()

        strides = calculate_spatio_temporal(HS_for_metrics, TO_for_metrics, MS_for_metrics, idata, is_long_jump=long_jump_applied)
        print(f"DEBUG: Strides calculated: {len(strides)}")
        
        # 结果汇总 - 包含所有前端需要的核心指标
        def safe_mean(key):
            vals = [s[key] for s in strides if s.get(key) is not None]
            return float(np.mean(vals)) if vals else 0.0

        summary = {
            "n_strides": len(strides),
            "stride_time_s": safe_mean("stride_time_s"),
            "contact_time_s": safe_mean("contact_time_s"),
            "swing_time_s": safe_mean("swing_time_s"),
            "step_frequency_hz": safe_mean("step_frequency_hz"),
            "stride_length_m": safe_mean("stride_length_m"),
            "stride_velocity_mps": safe_mean("stride_velocity_mps"),
            "vGRF_peak_BW": safe_mean("vGRF_peak_BW"),
            "double_support_time_ms": safe_mean("double_support_time_s") * 1000.0,
            "flight_time_ms": safe_mean("flight_time_s") * 1000.0,
            "duration_s": float((idata['Timestamp'].iloc[-1] - idata['Timestamp'].iloc[0]) / 1000.0),
            "gait_status_last": strides[-1]["gait_status"] if strides else "Unknown",
            "source_sample_rate_hz": float(fs_source),
            "long_jump_takeoff_step": int(takeoff_req) if takeoff_req > 0 else -1,
            "long_jump_to_filter_applied": bool(takeoff_req > 0 and long_jump_applied),
            "long_jump_takeoff_auto": bool(takeoff_raw == 0),
            "long_jump_takeoff_auto_applied": bool(takeoff_raw == 0 and takeoff_req > 0),
            "long_jump_takeoff_auto_message": takeoff_auto_message
            if takeoff_raw == 0
            else "",
            "long_jump_is_takeoff_foot": bool(is_takeoff_foot),
        }

        result = {
            "ok": True,
            "summary": summary,
            "strides": strides,
            "signals": {
                "timestamps": idata["Timestamp"].tolist(),
                "acc_x": idata["ACC.X"].tolist(),
                "acc_y": idata["ACC.Y"].tolist(),
                "acc_z": idata["ACC.Z"].tolist(),
                "gyro_y": idata["Gmax(°/s)"].tolist(),
                "gyro_x": idata["Gyro.X"].tolist(),
                "gyro_y_raw": idata["Gyro.Y"].tolist(),
                "gyro_z": idata["Gyro.Z"].tolist(),
            },
            "events": {
                "hs":  [int(x) for x in HS_for_metrics],
                "to":  [int(x) for x in TO_for_metrics],
                "ms":  [int(x) for x in MS_for_metrics],
                "msw": [int(x) for x in MSW]
            }
        }
        
        if contra_data:
            result["contra_data"] = contra_data
            
        return json.dumps(result)
    except Exception as e:
        import traceback
        print(f"ERROR: {str(e)}\n{traceback.format_exc()}")
        return json.dumps({"ok": False, "error": str(e)})
