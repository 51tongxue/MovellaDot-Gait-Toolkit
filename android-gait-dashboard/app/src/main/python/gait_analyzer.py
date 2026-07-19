import pandas as pd
import numpy as np
from scipy.signal import (
    butter,
    sosfiltfilt,
    savgol_filter,
    find_peaks,
)
import json
import os
from offline_gait_event_detector import (
    ACC_LOW_CUTOFF_HZ,
    GAIT_CONFIRM_WINDOW_MS,
    GYRO_LOW_CUTOFF_HZ,
    OfflineGaitEventDetector,
    OfflineGaitEventPipeline,
    _separated_otsu_threshold,
    detect_offline_gait_events,
)
from offline_gait_metrics import (
    calculate_bilateral_double_support as _calculate_bilateral_double_support,
    calculate_stride_length as _calculate_stride_length,
)

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


def _rotate_vectors_by_quaternions(vectors, quaternions):
    """批量旋转向量，避免在移动端逐行执行 Python 四元数乘法。"""
    vectors = np.asarray(vectors, dtype=np.float64)
    quaternions = np.asarray(quaternions, dtype=np.float64)
    if vectors.ndim != 2 or quaternions.ndim != 2:
        raise ValueError("vectors and quaternions must be two-dimensional")
    if vectors.shape[0] != quaternions.shape[0]:
        raise ValueError("vector and quaternion counts must match")
    norms = np.linalg.norm(quaternions, axis=1, keepdims=True)
    normalized = quaternions / np.maximum(norms, 1e-12)
    scalar = normalized[:, :1]
    vector_part = normalized[:, 1:]
    twice_cross = 2.0 * np.cross(vector_part, vectors)
    return (
        vectors
        + scalar * twice_cross
        + np.cross(vector_part, twice_cross)
    )


def _detect_format(idata):
    """检测 CSV 格式：离线格式含 Acc_X + Quat_W；在线格式含 freeAccX"""
    if 'Acc_X' in idata.columns and 'Quat_W' in idata.columns:
        return 'offline'
    return 'online'


# 步态分析核心配置
GRAVITY = 9.80665
MS_PER_S = 1000.0
BASE_FS_HZ = 60          # 基准采样率（用于 Savgol 窗口缩放）
# 相邻 MSW（按时间）至少间隔多少 ms，避免双支撑内毛刺被当成两次 MSW。
MSW_MIN_INTERVAL_MS = 400
# TC 搜索要避开 IC 确认段，避免把确认谷重复标成 TC。
TC_OFFSET_AFTER_IC_MS = GAIT_CONFIRM_WINDOW_MS + 50

FS_ESTIMATE_MIN_HZ = 10
FS_ESTIMATE_MAX_HZ = 2000
SAMPLE_TIME_SYNC_START_TOLERANCE_FRAMES = 30.0

# 通用步态质量检测。先从左右脚候选 IC 中提取连续、交替的步态段；
# 转身中的完整周期继续保留，只有启动扰动、停顿或双侧持续失序才结束当前段。
GAIT_STEP_INTERVAL_MIN_MS = 120.0
GAIT_STEP_INTERVAL_MAX_MS = 1400.0
GAIT_STEP_INTERVAL_RATIO_MIN = 0.50
GAIT_STEP_INTERVAL_RATIO_MAX = 1.90
GAIT_BOUT_MIN_CONTACTS = 6
GAIT_BOUT_MIN_COMPLETE_CYCLE_RATIO = 0.75
GAIT_CYCLE_TEMPLATE_POINTS = 64
GAIT_CYCLE_MIN_SIMILARITY = 0.45
GAIT_RECOVERY_TEMPLATE_MIN_CORRELATION = 0.75
GAIT_RECOVERY_TEMPLATE_MAX_LAG_RATIO = 0.08
GAIT_GAP_RECOVERY_RATIO = 1.55
GAIT_GAP_RECOVERY_MAX_MISSING = 2
GAIT_GAP_RECOVERY_SEARCH_RATIO = 0.30
SINGLE_LEG_CYCLE_INTERVAL_RATIO_MAX = 2.50
UNPAIRED_RECORD_MIN_CYCLES_PER_SIDE = 3


class BilateralGaitContactStateMachine:
    """左右脚触地事件的增量步态区间状态机。

    实时分析逐个传入 IC 事件；离线分析将已有 IC 事件快速传入同一个状态机。
    每次新 IC 到来时，调用方同时传入刚完成的同脚周期质量。左右脚各有一个
    周期与前一周期一致后进入 ACTIVE，不需要固定秒数的启动窗口。
    """

    WAITING = "WAITING"
    CANDIDATE = "CANDIDATE"
    ACTIVE = "ACTIVE"
    VALID_SIDES = {"primary", "contralateral"}

    def __init__(self):
        self.state = self.WAITING
        self.current_contacts = []
        self.current_intervals = []
        self.confirmed_cycle_sides = set()
        self.low_quality_streak = {
            side: 0 for side in self.VALID_SIDES
        }
        self.low_quality_start_index = {
            side: None for side in self.VALID_SIDES
        }
        self.current_reached_active = False
        self.completed_sequences = []

    def _start_candidate(
        self,
        timestamp_ms,
        side,
        cycle_correlation=None,
    ):
        self.current_contacts = [(float(timestamp_ms), side)]
        self.current_intervals = []
        self.confirmed_cycle_sides = set()
        self.low_quality_streak = {
            candidate_side: 0 for candidate_side in self.VALID_SIDES
        }
        self.low_quality_start_index = {
            candidate_side: None
            for candidate_side in self.VALID_SIDES
        }
        self.current_reached_active = False
        self.state = self.CANDIDATE

    def _is_valid_link(self, timestamp_ms, side):
        if not self.current_contacts:
            return True
        previous_timestamp, previous_side = self.current_contacts[-1]
        interval_ms = float(timestamp_ms) - float(previous_timestamp)
        is_valid = (
            side != previous_side
            and GAIT_STEP_INTERVAL_MIN_MS
            <= interval_ms
            <= GAIT_STEP_INTERVAL_MAX_MS
        )
        if is_valid and len(self.current_intervals) >= 4:
            local_median = float(np.median(self.current_intervals[-6:]))
            is_valid = (
                local_median * GAIT_STEP_INTERVAL_RATIO_MIN
                <= interval_ms
                <= local_median * GAIT_STEP_INTERVAL_RATIO_MAX
            )
        return is_valid

    def _finish_current(self, reason):
        if not self.current_contacts:
            self.state = self.WAITING
            return
        self.completed_sequences.append({
            "contacts": list(self.current_contacts),
            "reached_active": bool(self.current_reached_active),
            "end_reason": reason,
        })
        self.current_contacts = []
        self.current_intervals = []
        self.confirmed_cycle_sides = set()
        self.low_quality_streak = {
            side: 0 for side in self.VALID_SIDES
        }
        self.low_quality_start_index = {
            side: None for side in self.VALID_SIDES
        }
        self.current_reached_active = False
        self.state = self.WAITING

    def _split_sustained_low_quality(self):
        break_index = min(
            int(index)
            for index in self.low_quality_start_index.values()
            if index is not None
        )
        stable_contacts = self.current_contacts[:break_index]
        unstable_contacts = self.current_contacts[break_index:]
        if stable_contacts:
            self.completed_sequences.append({
                "contacts": list(stable_contacts),
                "reached_active": True,
                "end_reason": "sustained_quality_break",
            })

        seed_contacts = []
        for contact in unstable_contacts[-2:]:
            if not seed_contacts:
                seed_contacts.append(contact)
                continue
            interval_ms = float(contact[0]) - float(seed_contacts[-1][0])
            if (
                contact[1] != seed_contacts[-1][1]
                and GAIT_STEP_INTERVAL_MIN_MS
                <= interval_ms
                <= GAIT_STEP_INTERVAL_MAX_MS
            ):
                seed_contacts.append(contact)
            else:
                seed_contacts = [contact]

        self.current_contacts = seed_contacts
        self.current_intervals = [
            float(seed_contacts[index][0])
            - float(seed_contacts[index - 1][0])
            for index in range(1, len(seed_contacts))
        ]
        self.confirmed_cycle_sides = set()
        self.low_quality_streak = {
            side: 0 for side in self.VALID_SIDES
        }
        self.low_quality_start_index = {
            side: None for side in self.VALID_SIDES
        }
        self.current_reached_active = False
        self.state = self.CANDIDATE if seed_contacts else self.WAITING

    def _restart_with_recent_pair(
        self,
        timestamp_ms,
        side,
    ):
        previous_contact = (
            self.current_contacts[-1]
            if self.current_contacts
            else None
        )
        self._finish_current("cycle_quality_break")
        if (
            previous_contact is not None
            and previous_contact[1] != side
            and GAIT_STEP_INTERVAL_MIN_MS
            <= float(timestamp_ms) - float(previous_contact[0])
            <= GAIT_STEP_INTERVAL_MAX_MS
        ):
            self.current_contacts = [
                previous_contact,
                (float(timestamp_ms), side),
            ]
            self.current_intervals = [
                float(timestamp_ms) - float(previous_contact[0])
            ]
            self.confirmed_cycle_sides = set()
            self.current_reached_active = False
            self.state = self.CANDIDATE
            return
        self._start_candidate(timestamp_ms, side)

    def push_contact(
        self,
        timestamp_ms,
        side,
        cycle_valid=None,
        cycle_correlation=None,
    ):
        """传入一个按时间递增的 IC 和刚完成的同脚周期质量。"""
        if side not in self.VALID_SIDES:
            raise ValueError(f"unsupported gait contact side: {side}")
        timestamp_ms = float(timestamp_ms)
        if self.current_contacts and timestamp_ms < self.current_contacts[-1][0]:
            raise ValueError("gait contacts must be strictly time ordered")

        if not self.current_contacts:
            self._start_candidate(timestamp_ms, side, cycle_correlation)
            return self.state

        if not self._is_valid_link(timestamp_ms, side):
            self._finish_current("sequence_break")
            self._start_candidate(timestamp_ms, side, cycle_correlation)
            return self.state

        if cycle_valid is False:
            self._restart_with_recent_pair(timestamp_ms, side)
            return self.state

        is_low_quality = (
            cycle_valid is None
            and cycle_correlation is not None
            and cycle_correlation < GAIT_CYCLE_MIN_SIMILARITY
        )
        if is_low_quality and self.state != self.ACTIVE:
            self._restart_with_recent_pair(timestamp_ms, side)
            return self.state

        interval_ms = timestamp_ms - self.current_contacts[-1][0]
        self.current_contacts.append((timestamp_ms, side))
        self.current_intervals.append(interval_ms)
        if cycle_valid is True:
            self.confirmed_cycle_sides.add(side)
            self.low_quality_streak[side] = 0
            self.low_quality_start_index[side] = None
        elif is_low_quality:
            if self.low_quality_streak[side] == 0:
                self.low_quality_start_index[side] = (
                    len(self.current_contacts) - 1
                )
            self.low_quality_streak[side] += 1
            if all(
                self.low_quality_streak[candidate_side] >= 2
                for candidate_side in self.VALID_SIDES
            ):
                self._split_sustained_low_quality()
                return self.state
        if (
            len(self.current_contacts) >= GAIT_BOUT_MIN_CONTACTS
            and self.confirmed_cycle_sides == self.VALID_SIDES
        ):
            self.state = self.ACTIVE
            self.current_reached_active = True
        return self.state

    def advance_time(self, timestamp_ms):
        """实时无新事件时推进时钟，超过最大步间隔即结束当前区间。"""
        if (
            self.current_contacts
            and float(timestamp_ms) - self.current_contacts[-1][0]
            > GAIT_STEP_INTERVAL_MAX_MS
        ):
            self._finish_current("timeout")
        return self.state

    def flush(self):
        """结束输入并提交最后一个候选区间。"""
        self._finish_current("flush")

    def drain_completed(self):
        completed = list(self.completed_sequences)
        self.completed_sequences.clear()
        return completed

    def snapshot(self):
        """返回可直接映射到实时 UI 的轻量状态。"""
        median_interval = (
            float(np.median(self.current_intervals[-6:]))
            if self.current_intervals
            else None
        )
        return {
            "state": self.state,
            "contact_count": int(len(self.current_contacts)),
            "last_contact_ms": (
                float(self.current_contacts[-1][0])
                if self.current_contacts
                else None
            ),
            "median_step_interval_ms": median_interval,
            "confirmed_cycle_sides": sorted(self.confirmed_cycle_sides),
        }


def segment_bilateral_contacts(contacts):
    """用增量状态机切分带周期质量的左右脚 IC。"""
    state_machine = BilateralGaitContactStateMachine()
    for contact in contacts:
        timestamp_ms, side = contact[:2]
        cycle_valid = contact[2] if len(contact) >= 3 else None
        cycle_correlation = contact[3] if len(contact) >= 4 else None
        state_machine.push_contact(
            timestamp_ms,
            side,
            cycle_valid=cycle_valid,
            cycle_correlation=cycle_correlation,
        )
    state_machine.flush()
    return state_machine.drain_completed()


def _normalize_cycle_values(signal_values):
    segment = np.asarray(signal_values, dtype=np.float64)
    segment = segment[np.isfinite(segment)]
    if segment.size < 12:
        return None
    normalized = np.interp(
        np.linspace(0.0, float(segment.size - 1), GAIT_CYCLE_TEMPLATE_POINTS),
        np.arange(segment.size, dtype=np.float64),
        segment,
    )
    scale = float(np.std(normalized))
    if scale < 1e-6:
        return None
    return (normalized - float(np.mean(normalized))) / scale


def _normalized_gait_cycle(idata, start_ms, end_ms):
    """提取一个已完成的同脚 IC->IC 周期并归一化。"""
    if idata is None or idata.empty or "Gmax(°/s)" not in idata.columns:
        return None
    return _normalized_gait_cycle_arrays(
        idata["Timestamp"].to_numpy(dtype=np.float64),
        idata["Gmax(°/s)"].to_numpy(dtype=np.float64),
        start_ms,
        end_ms,
    )


def _normalized_gait_cycle_arrays(
    timestamps_ms,
    signal_values,
    start_ms,
    end_ms,
):
    timestamps = np.asarray(timestamps_ms, dtype=np.float64)
    values = np.asarray(signal_values, dtype=np.float64)
    start_index = int(np.searchsorted(
        timestamps,
        float(start_ms),
        side="left",
    ))
    end_index = int(np.searchsorted(
        timestamps,
        float(end_ms),
        side="right",
    ))
    return _normalize_cycle_values(values[start_index:end_index])


def _cycle_similarity(reference, cycle):
    if reference is None or cycle is None:
        return None
    correlation = float(np.corrcoef(reference, cycle)[0, 1])
    return correlation if np.isfinite(correlation) else None


def _max_lag_cycle_correlation(reference, cycle):
    if reference is None or cycle is None:
        return None
    reference = np.asarray(reference, dtype=np.float64)
    cycle = np.asarray(cycle, dtype=np.float64)
    if reference.shape != cycle.shape or reference.size < 12:
        return None
    max_lag = max(
        1,
        int(round(
            reference.size * GAIT_RECOVERY_TEMPLATE_MAX_LAG_RATIO
        )),
    )
    correlations = []
    for lag in range(-max_lag, max_lag + 1):
        if lag < 0:
            current = cycle[-lag:]
            template = reference[:lag]
        elif lag > 0:
            current = cycle[:-lag]
            template = reference[lag:]
        else:
            current = cycle
            template = reference
        if current.size < 12:
            continue
        correlation = float(np.corrcoef(template, current)[0, 1])
        if np.isfinite(correlation):
            correlations.append(correlation)
    return max(correlations) if correlations else None


def _build_prior_msw_cycle_template(idata, before_ms):
    if idata is None or idata.empty or 'MSW' not in idata.columns:
        return None
    msw = sorted(
        float(timestamp)
        for timestamp in idata[
            idata['MSW'].notna()
            & (idata['Timestamp'] <= float(before_ms))
        ]['Timestamp'].values
    )
    if len(msw) < 4:
        return None
    cycles = []
    for start_ms, end_ms in zip(msw, msw[1:]):
        cycle = _normalized_gait_cycle(idata, start_ms, end_ms)
        if cycle is not None:
            cycles.append(cycle)
    if len(cycles) < 3:
        return None
    template = np.median(
        np.asarray(cycles[-8:], dtype=np.float64),
        axis=0,
    )
    scale = float(np.std(template))
    if scale < 1e-6:
        return None
    return (template - float(np.mean(template))) / scale


def _recovery_cycle_template_similarity(
    idata,
    cycle_start_ms,
    cycle_end_ms,
):
    template = _build_prior_msw_cycle_template(
        idata,
        cycle_start_ms,
    )
    cycle = _normalized_gait_cycle(
        idata,
        cycle_start_ms,
        cycle_end_ms,
    )
    return _max_lag_cycle_correlation(template, cycle)


def _build_bilateral_cycle_contacts(
    primary_idata,
    primary_hs,
    primary_to,
    contralateral_idata,
    contralateral_hs,
    contralateral_to,
):
    """为每个 IC 生成刚完成周期的因果质量，离线与实时状态机输入一致。"""
    side_data = {
        "primary": {
            "timestamps": primary_idata["Timestamp"].to_numpy(
                dtype=np.float64
            ),
            "signal": primary_idata["Gmax(°/s)"].to_numpy(
                dtype=np.float64
            ),
            "toe_off": np.sort(np.asarray(primary_to, dtype=np.float64)),
        },
        "contralateral": {
            "timestamps": contralateral_idata["Timestamp"].to_numpy(
                dtype=np.float64
            ),
            "signal": contralateral_idata["Gmax(°/s)"].to_numpy(
                dtype=np.float64
            ),
            "toe_off": np.sort(np.asarray(
                contralateral_to,
                dtype=np.float64,
            )),
        },
    }
    contacts = sorted(
        [(float(t), "primary") for t in primary_hs]
        + [(float(t), "contralateral") for t in contralateral_hs],
        key=lambda item: item[0],
    )
    previous_ic = {}
    previous_cycle = {}
    cycle_contacts = []
    for timestamp_ms, side in contacts:
        cycle_valid = None
        cycle_correlation = None
        if side in previous_ic:
            cycle_start = float(previous_ic[side])
            data = side_data[side]
            toe_off = data["toe_off"]
            toe_start = int(np.searchsorted(
                toe_off,
                cycle_start,
                side="right",
            ))
            toe_end = int(np.searchsorted(
                toe_off,
                timestamp_ms,
                side="left",
            ))
            cycle = (
                _normalized_gait_cycle_arrays(
                    data["timestamps"],
                    data["signal"],
                    cycle_start,
                    timestamp_ms,
                )
                if toe_end - toe_start == 1
                else None
            )
            if cycle is None:
                cycle_valid = False
                previous_cycle.pop(side, None)
            else:
                reference = previous_cycle.get(side)
                if reference is not None:
                    cycle_correlation = _cycle_similarity(reference, cycle)
                    if (
                        cycle_correlation is not None
                        and cycle_correlation >= GAIT_CYCLE_MIN_SIMILARITY
                    ):
                        cycle_valid = True
                previous_cycle[side] = cycle
        previous_ic[side] = timestamp_ms
        cycle_contacts.append((
            timestamp_ms,
            side,
            cycle_valid,
            cycle_correlation,
        ))
    return cycle_contacts


class IncrementalBilateralGaitDetector:
    """实时/离线共用的事件驱动检测器。

    原始样本持续写入当前同脚周期缓冲区；因果事件检测器报告 IC/TC 后立即完成
    周期质量计算并推进双脚状态机。内存只保留当前周期，不依赖固定时长窗口。
    """

    VALID_EVENTS = {"IC", "TC"}

    def __init__(self):
        self.state_machine = BilateralGaitContactStateMachine()
        self.samples = {"primary": [], "contralateral": []}
        self.last_ic = {}
        self.tc_count = {"primary": 0, "contralateral": 0}
        self.previous_cycle = {}

    def push_sample(self, timestamp_ms, side, gait_signal):
        if side not in BilateralGaitContactStateMachine.VALID_SIDES:
            raise ValueError(f"unsupported gait sample side: {side}")
        timestamp_ms = float(timestamp_ms)
        side_samples = self.samples[side]
        if side_samples and timestamp_ms < side_samples[-1][0]:
            raise ValueError("gait samples must be time ordered per side")
        side_samples.append((timestamp_ms, float(gait_signal)))
        cycle_start = self.last_ic.get(side)
        if cycle_start is not None:
            self.samples[side] = [
                sample for sample in side_samples if sample[0] >= cycle_start
            ]
        elif len(side_samples) > 4000:
            self.samples[side] = side_samples[-4000:]

    def push_event(self, timestamp_ms, side, event_type):
        if side not in BilateralGaitContactStateMachine.VALID_SIDES:
            raise ValueError(f"unsupported gait event side: {side}")
        if event_type not in self.VALID_EVENTS:
            raise ValueError(f"unsupported gait event type: {event_type}")
        timestamp_ms = float(timestamp_ms)
        if event_type == "TC":
            if side in self.last_ic and timestamp_ms > self.last_ic[side]:
                self.tc_count[side] += 1
            return self.state_machine.state

        cycle_valid = None
        cycle_correlation = None
        cycle_start = self.last_ic.get(side)
        if cycle_start is not None:
            cycle_values = [
                value
                for sample_time, value in self.samples[side]
                if cycle_start <= sample_time <= timestamp_ms
            ]
            cycle = (
                _normalize_cycle_values(cycle_values)
                if self.tc_count[side] == 1
                else None
            )
            if cycle is None:
                cycle_valid = False
                self.previous_cycle.pop(side, None)
            else:
                reference = self.previous_cycle.get(side)
                if reference is not None:
                    cycle_correlation = _cycle_similarity(reference, cycle)
                    if (
                        cycle_correlation is not None
                        and cycle_correlation >= GAIT_CYCLE_MIN_SIMILARITY
                    ):
                        cycle_valid = True
                self.previous_cycle[side] = cycle

        state = self.state_machine.push_contact(
            timestamp_ms,
            side,
            cycle_valid=cycle_valid,
            cycle_correlation=cycle_correlation,
        )
        self.last_ic[side] = timestamp_ms
        self.tc_count[side] = 0
        self.samples[side] = [
            sample
            for sample in self.samples[side]
            if sample[0] >= timestamp_ms
        ]
        return state

    def advance_time(self, timestamp_ms):
        return self.state_machine.advance_time(timestamp_ms)

    def flush(self):
        self.state_machine.flush()

    def drain_completed(self):
        return self.state_machine.drain_completed()

    def snapshot(self):
        return self.state_machine.snapshot()


def estimate_sample_rate_hz(timestamps_ms):
    """由相对时间戳（ms）估计采样率；过滤丢帧间隔后取平均，避免整数毫秒量化造成 120Hz 被误判为 125Hz。"""
    ts = np.asarray(timestamps_ms, dtype=np.float64)
    if ts.size < 2:
        return 60.0
    d = np.diff(ts)
    d = d[d > 0]
    if d.size < 1:
        return 60.0
    dt_med = float(np.median(d))
    nominal_d = d[d <= dt_med * 1.5]
    if nominal_d.size < 1:
        return 60.0
    dt_mean = float(np.mean(nominal_d))
    if dt_mean <= 0:
        return 60.0
    fs = MS_PER_S / dt_mean
    return float(np.clip(fs, FS_ESTIMATE_MIN_HZ, FS_ESTIMATE_MAX_HZ))


def read_csv_header_metadata(file_path, max_lines=200):
    """读取导出 CSV 表头前的 key,value 元数据，同时返回真正数据表头所在行号。"""
    metadata = {}
    skip = 0
    try:
        with open(file_path, 'r', encoding='utf-8', errors='ignore') as f:
            for i, line in enumerate(f):
                if i >= max_lines:
                    break
                columns = [part.strip() for part in line.strip().split(',')]
                if 'PacketCounter' in columns or 'SampleTimeFine' in columns:
                    skip = i
                    break
                stripped = line.strip()
                if not stripped:
                    continue
                parts = stripped.split(',', 1)
                if len(parts) == 2 and parts[0].strip():
                    metadata[parts[0].strip().lower()] = parts[1].strip()
    except Exception:
        return 0, {}
    return skip, metadata


def normalize_device_id(raw):
    return str(raw or '').replace(':', '').replace('-', '').upper()


def find_paired_foot_file(
    file_path,
    left_device_id='D422CD007E6E',
    right_device_id='D422CD00937F',
    max_capture_delta_s=10,
):
    basename = os.path.basename(file_path)
    dirname = os.path.dirname(file_path) or '.'
    parts = basename.replace('.csv', '').split('_')
    if len(parts) < 3:
        return None
    left_id = normalize_device_id(left_device_id)
    right_id = normalize_device_id(right_device_id)
    basename_upper = basename.upper()
    if left_id in basename_upper:
        opposite_id = right_id
    elif right_id in basename_upper:
        opposite_id = left_id
    else:
        return None
    _, date_text, time_text = parts[:3]
    try:
        source_seconds = (
            int(time_text[:2]) * 3600
            + int(time_text[2:4]) * 60
            + int(time_text[4:6])
        )
    except (TypeError, ValueError):
        return None

    candidates = []
    for filename in os.listdir(dirname):
        if (
            not filename.endswith('.csv')
            or opposite_id not in filename.upper()
        ):
            continue
        candidate_parts = filename.replace('.csv', '').split('_')
        if len(candidate_parts) < 3 or candidate_parts[1] != date_text:
            continue
        candidate_time = candidate_parts[2]
        try:
            candidate_seconds = (
                int(candidate_time[:2]) * 3600
                + int(candidate_time[2:4]) * 60
                + int(candidate_time[4:6])
            )
        except (TypeError, ValueError):
            continue
        delta = abs(source_seconds - candidate_seconds)
        if delta <= max_capture_delta_s:
            candidates.append((delta, filename))
    if not candidates:
        return None
    candidates.sort(key=lambda item: (item[0], item[1]))
    return os.path.join(dirname, candidates[0][1])


def parse_bool_metadata(metadata, key):
    value = metadata.get(key.lower())
    if value is None:
        return None
    normalized = value.strip().lower()
    if normalized in ('1', 'true', 'yes', 'y', 'synced'):
        return True
    if normalized in ('0', 'false', 'no', 'n', 'unsynced'):
        return False
    return None


def nearest_timestamp_index(idata, timestamp):
    if idata is None or len(idata) == 0 or 'Timestamp' not in idata.columns:
        return None
    timestamps = idata['Timestamp'].to_numpy(dtype=np.float64)
    nearest_position = int(
        nearest_timestamp_positions(timestamps, [timestamp])[0]
    )
    return idata.index[nearest_position]


def nearest_timestamp_positions(timestamps, event_times):
    """批量返回事件时间在有序采样时间轴上的最近位置。"""
    samples = np.asarray(timestamps, dtype=np.float64)
    events = np.asarray(event_times, dtype=np.float64)
    if samples.size == 0 or events.size == 0:
        return np.asarray([], dtype=np.int64)
    right = np.searchsorted(samples, events, side='left')
    right = np.clip(right, 0, samples.size - 1)
    left = np.clip(right - 1, 0, samples.size - 1)
    choose_right = (
        np.abs(samples[right] - events)
        < np.abs(samples[left] - events)
    )
    return np.where(choose_right, right, left).astype(np.int64)


def estimate_initial_sample_time_interval(sample_times):
    values = pd.to_numeric(sample_times, errors='coerce').dropna().to_numpy(dtype=np.float64)
    if values.size < 2:
        return None
    diffs = np.diff(values[:min(values.size, 20)])
    diffs = diffs[diffs > 0]
    if diffs.size == 0:
        return None
    return float(np.median(diffs))


def try_align_pair_by_sample_time(primary_df, paired_df, time_scale, force_synced=False):
    """
    仅当两侧开头 SampleTimeFine 对得上时，才认定为同步录制并按 SampleTimeFine 对齐。
    未达到同步条件时返回 None，由调用方回退到 PacketCounter。
    """
    if 'SampleTimeFine' not in primary_df.columns or 'SampleTimeFine' not in paired_df.columns:
        return None, {"reason": "missing_sample_time_fine"}

    primary = primary_df.copy()
    paired = paired_df.copy()
    key_col = '_SampleTimeFineKey'
    primary[key_col] = pd.to_numeric(primary['SampleTimeFine'], errors='coerce')
    paired[key_col] = pd.to_numeric(paired['SampleTimeFine'], errors='coerce')
    primary = primary[primary[key_col].notna()]
    paired = paired[paired[key_col].notna()]
    if primary.empty or paired.empty:
        return None, {"reason": "empty_sample_time_fine"}

    primary_start = float(primary[key_col].iloc[0])
    paired_start = float(paired[key_col].iloc[0])
    primary_interval = estimate_initial_sample_time_interval(primary[key_col])
    paired_interval = estimate_initial_sample_time_interval(paired[key_col])
    intervals = [x for x in (primary_interval, paired_interval) if x is not None and x > 0]
    if not intervals:
        return None, {"reason": "missing_sample_time_interval"}
    initial_interval = min(intervals)
    start_tolerance = max(1.0, initial_interval * SAMPLE_TIME_SYNC_START_TOLERANCE_FRAMES)
    start_delta = abs(primary_start - paired_start)
    if not force_synced and start_delta > start_tolerance:
        return None, {
            "reason": "sample_time_start_mismatch",
            "start_delta": start_delta,
            "start_tolerance": start_tolerance,
        }

    overlap_start = max(float(primary[key_col].min()), float(paired[key_col].min()))
    overlap_end = min(float(primary[key_col].max()), float(paired[key_col].max()))
    if overlap_start > overlap_end:
        return None, {"reason": "no_sample_time_overlap"}

    primary = primary[(primary[key_col] >= overlap_start) & (primary[key_col] <= overlap_end)]
    paired = paired[(paired[key_col] >= overlap_start) & (paired[key_col] <= overlap_end)]
    primary = primary.drop_duplicates(subset=[key_col], keep='first')
    paired = paired.drop_duplicates(subset=[key_col], keep='first')

    common_times = np.intersect1d(
        primary[key_col].to_numpy(dtype=np.float64),
        paired[key_col].to_numpy(dtype=np.float64),
    )
    if common_times.size == 0:
        return None, {
            "reason": "no_common_sample_time_after_start_match",
            "start_delta": start_delta,
            "start_tolerance": start_tolerance,
        }

    primary = primary[primary[key_col].isin(common_times)].sort_values(key_col).reset_index(drop=True)
    paired = paired[paired[key_col].isin(common_times)].sort_values(key_col).reset_index(drop=True)
    sample_start = float(common_times[0])
    sample_end = float(common_times[-1])

    if 'Timestamp' in primary.columns:
        timestamp_by_sample = dict(
            zip(
                primary[key_col].to_numpy(dtype=np.float64),
                primary['Timestamp'].to_numpy(dtype=np.int64),
            )
        )
        primary['Timestamp'] = primary[key_col].map(timestamp_by_sample).astype(np.int64)
        paired['Timestamp'] = paired[key_col].map(timestamp_by_sample).astype(np.int64)
    else:
        primary['Timestamp'] = np.round((primary[key_col] - sample_start) * time_scale).astype(np.int64)
        paired['Timestamp'] = np.round((paired[key_col] - sample_start) * time_scale).astype(np.int64)

    primary = primary.drop(columns=[key_col])
    paired = paired.drop(columns=[key_col])
    info = {
        "start": sample_start,
        "end": sample_end,
        "rows": int(len(common_times)),
        "method": "SampleTimeFine",
        "start_delta": start_delta,
        "start_tolerance": start_tolerance,
        "force_synced": bool(force_synced),
    }
    return (primary, paired, info), {
        "reason": "synced_sample_time",
        "common_rows": int(common_times.size),
        "start_delta": start_delta,
        "start_tolerance": start_tolerance,
        "force_synced": bool(force_synced),
    }


def align_pair_by_packet_counter(primary_df, paired_df, dt_ms):
    """
    非同步左右脚数据按各自相对 PacketCounter 对齐。
    两侧从各自第一帧归零，取共同相对帧号，避免长度不一致。
    """
    primary = primary_df.copy()
    paired = paired_df.copy()
    key_col = '_RelativePacketCounter'

    def attach_relative_packet_counter(df):
        if 'PacketCounter' not in df.columns:
            df = df.copy()
            df['PacketCounter'] = np.arange(len(df), dtype=np.int64)
        raw = pd.to_numeric(df['PacketCounter'], errors='coerce')
        valid = raw.notna()
        df = df[valid].copy()
        raw = raw[valid].reset_index(drop=True)
        if df.empty:
            raise ValueError("PacketCounter 为空，无法配对左右脚数据")
        diff = raw.diff().fillna(0)
        diff[diff < 0] += 65536
        unwrapped = diff.cumsum() + raw.iloc[0]
        df[key_col] = (unwrapped - unwrapped.iloc[0]).to_numpy(dtype=np.float64)
        return df

    primary = attach_relative_packet_counter(primary)
    paired = attach_relative_packet_counter(paired)
    primary = primary.drop_duplicates(subset=[key_col], keep='first')
    paired = paired.drop_duplicates(subset=[key_col], keep='first')
    common_counts = np.intersect1d(
        primary[key_col].to_numpy(dtype=np.float64),
        paired[key_col].to_numpy(dtype=np.float64),
    )
    if common_counts.size == 0:
        raise ValueError("左右脚 PacketCounter 没有共同相对帧号，无法配对")

    primary = primary[primary[key_col].isin(common_counts)].sort_values(key_col).reset_index(drop=True)
    paired = paired[paired[key_col].isin(common_counts)].sort_values(key_col).reset_index(drop=True)
    counter_start = float(common_counts[0])
    counter_end = float(common_counts[-1])
    primary['Timestamp'] = np.round((primary[key_col] - counter_start) * dt_ms).astype(np.int64)
    paired['Timestamp'] = np.round((paired[key_col] - counter_start) * dt_ms).astype(np.int64)
    primary = primary.drop(columns=[key_col])
    paired = paired.drop(columns=[key_col])
    info = {
        "start": counter_start,
        "end": counter_end,
        "rows": int(len(common_counts)),
        "method": "PacketCounter",
    }
    return primary, paired, info


def apply_time_window(idata, start_time_s, end_time_s):
    data = idata
    if start_time_s >= 0:
        data = data[data['Timestamp'] >= start_time_s * 1000.0]
    if end_time_s >= 0:
        data = data[data['Timestamp'] <= end_time_s * 1000.0]
    data = data.copy()
    data.reset_index(drop=True, inplace=True)
    return data


def prepare_idata_for_analysis(idata, log_label=""):
    idata = idata.copy()
    idata.reset_index(drop=True, inplace=True)

    fmt = _detect_format(idata)
    label = f" ({log_label})" if log_label else ""
    print(f"GAIT_LOG_INFO: Detected CSV format{label}: {fmt}")

    if fmt == 'offline':
        # 离线格式：Acc_X/Y/Z 为局部坐标系原始加速度（m/s²），需用四元数旋转到全局坐标系后去重力
        acc_array = idata[['Acc_X', 'Acc_Y', 'Acc_Z']].to_numpy(dtype=float) / GRAVITY
        quat_array = idata[['Quat_W', 'Quat_X', 'Quat_Y', 'Quat_Z']].to_numpy(dtype=float)

        rotated = _rotate_vectors_by_quaternions(acc_array, quat_array)

        acc_xy = rotated[:, :2]
        if len(acc_xy) > 1 and len(quat_array) > 0:
            cov_mat = np.cov(acc_xy, rowvar=False)
            eigenvalues, eigenvectors = np.linalg.eigh(cov_mat)
            main_axis = eigenvectors[:, np.argmax(eigenvalues)]

            q0 = quat_array[0]
            w0, x0, y0, z0 = q0
            ref_vx = 1.0 - 2.0 * (y0**2 + z0**2)
            ref_vy = 2.0 * (x0*y0 + w0*z0)
            if np.dot(main_axis, [ref_vx, ref_vy]) < 0:
                main_axis = -main_axis

            yaw_pca = np.arctan2(main_axis[1], main_axis[0])
            cos_a, sin_a = np.cos(-yaw_pca), np.sin(-yaw_pca)
            rot_x = acc_xy[:, 0] * cos_a - acc_xy[:, 1] * sin_a
            rot_y = acc_xy[:, 0] * sin_a + acc_xy[:, 1] * cos_a
            rotated[:, 0] = rot_x
            rotated[:, 1] = rot_y

        idata['ACC.X'] = rotated[:, 0]
        idata['ACC.Y'] = rotated[:, 1]
        idata['ACC.Z'] = rotated[:, 2] - 1.0
        idata['Gmax(°/s)'] = idata['Gyr_Y']
        idata['Gyro.X'] = idata['Gyr_X']
        idata['Gyro.Y'] = idata['Gyr_Y']
        idata['Gyro.Z'] = idata['Gyr_Z']
    else:
        idata['ACC.X'] = idata['freeAccX'] / GRAVITY
        idata['ACC.Y'] = idata['freeAccY'] / GRAVITY
        idata['ACC.Z'] = idata['freeAccZ'] / GRAVITY
        idata['Gmax(°/s)'] = idata['gyroY']
        idata['Gyro.X'] = idata['gyroX']
        idata['Gyro.Y'] = idata['gyroY']
        idata['Gyro.Z'] = idata['gyroZ']

    fs_source = estimate_sample_rate_hz(idata['Timestamp'].values)
    print(f"GAIT_LOG_INFO: Estimated sample rate{label} ≈ {fs_source:.2f} Hz (no resampling)")

    HS, TO, MS, idata, ic_fusion = gait_identification(idata, fs=fs_source)
    print(f"DEBUG: Identified events{label} - HS: {len(HS)}, TO: {len(TO)}, MS: {len(MS)}")
    return idata, HS, TO, MS, ic_fusion, fs_source, fmt


def _apply_filters_low(data, column, low_cutoff, fs):
    sos_low = butter(4, low_cutoff, 'lp', fs=fs, output='sos')
    data[column] = sosfiltfilt(sos_low, data[column].values)
    return data


def _find_ic_time(seg_signal, seg_timestamps, gyro_noise_level=None):
    """在信号片段中按通用状态机寻找首个 IC。"""
    signal_values = np.asarray(seg_signal, dtype=np.float64)
    timestamps = np.asarray(seg_timestamps, dtype=np.float64)
    if signal_values.size < 3 or timestamps.size != signal_values.size:
        return None, False

    events = detect_offline_gait_events(
        signal_values,
        timestamps,
        min_same_foot_interval_ms=0.0,
    )
    if events:
        event = events[0]
        segment_threshold = _separated_otsu_threshold(
            np.abs(signal_values)
        )
        if (
            segment_threshold is None
            or -float(event["negative_value"]) >= segment_threshold
        ):
            return float(event["ic_time"]), True
    return None, False


def _detect_mid_stance_events(idata, hs_timestamps, to_timestamps):
    """按 IC/TC 支撑区间重新计算 MS，供初次检测和事件补偿共用。"""
    ms_timestamps = []
    ms_values = []
    a_last = np.array([0.0, 0.0])
    data_end = int(idata['Timestamp'].iloc[-1])
    timestamps_all = idata['Timestamp'].to_numpy(dtype=np.float64)
    gyro_xyz_all = idata[['Gyro.X', 'Gyro.Y', 'Gyro.Z']].to_numpy(
        dtype=np.float64
    )
    acc_xy_source = idata[['ACC.X', 'ACC.Y']].to_numpy(dtype=np.float64)
    to_array = np.asarray(sorted(to_timestamps), dtype=np.float64)
    for hs_idx, hs_time in enumerate(sorted(hs_timestamps)):
        to_index = int(np.searchsorted(to_array, hs_time, side='right'))
        if to_index < to_array.size:
            next_to = float(to_array[to_index])
        elif hs_idx == len(hs_timestamps) - 1:
            next_to = min(hs_time + 1000, data_end)
        else:
            continue

        stance_start = int(np.searchsorted(
            timestamps_all, hs_time, side='right'
        ))
        stance_end = int(np.searchsorted(
            timestamps_all, next_to, side='left'
        ))
        n_total = stance_end - stance_start
        if n_total < 10:
            continue

        edge_count = int(0.1 * n_total)
        if n_total - 2 * edge_count <= 5:
            continue
        core_start = stance_start + edge_count
        core_end = stance_end - edge_count
        times = timestamps_all[core_start:core_end]
        gyro_xyz = gyro_xyz_all[core_start:core_end]
        acc_xy_all = acc_xy_source[core_start:core_end]
        core_len = len(times)

        window_size = max(5, int(core_len * 0.3))
        if window_size % 2 == 0:
            window_size += 1
        half_window = window_size // 2
        if core_len <= window_size:
            continue

        kernel = np.full(window_size, 1.0 / window_size, dtype=np.float64)
        sample_gyro_energy = np.sum(gyro_xyz ** 2, axis=1)
        gyro_energy = np.convolve(sample_gyro_energy, kernel, mode='valid')
        mean_x = np.convolve(acc_xy_all[:, 0], kernel, mode='valid')
        mean_y = np.convolve(acc_xy_all[:, 1], kernel, mode='valid')
        mean_square = np.convolve(
            np.sum(acc_xy_all ** 2, axis=1),
            kernel,
            mode='valid',
        )
        acc_var = np.maximum(
            0.0,
            mean_square - mean_x ** 2 - mean_y ** 2,
        )
        if gyro_energy.size == 0 or acc_var.size == 0:
            continue

        gyro_index = int(np.argmin(gyro_energy))
        acc_index = int(np.argmin(acc_var))
        gyro_time = times[gyro_index + half_window]
        acc_time = times[acc_index + half_window]

        current_acc = acc_xy_all[gyro_index + half_window]
        difference = np.linalg.norm(a_last - current_acc)
        window_variance = float(acc_var[gyro_index])
        weight = window_variance / (
            window_variance + difference + 1e-6
        )
        estimated_time = weight * gyro_time + (1 - weight) * acc_time
        nearest = nearest_timestamp_index(idata, estimated_time)
        if nearest is None:
            continue
        mid_stance_time = int(idata.loc[nearest, 'Timestamp'])
        if mid_stance_time not in ms_timestamps:
            ms_timestamps.append(mid_stance_time)
            ms_values.append(float(gyro_energy[gyro_index]))
            a_last = current_acc.copy()

    return ms_timestamps, ms_values


def _recover_missing_tc_events_by_phase(
    idata,
    hs_timestamps,
    to_timestamps,
    target_cycle_starts,
    recovered_ic_swing_anchors,
):
    """按 IC -> TC -> MSW -> 下一 IC 的相位顺序补回缺失 TC。"""
    hs = sorted(float(timestamp) for timestamp in hs_timestamps)
    recovered_to = sorted(float(timestamp) for timestamp in to_timestamps)
    recovered = []
    timestamps = idata['Timestamp'].to_numpy(dtype=np.float64)
    signal_values = idata['Gmax(°/s)'].to_numpy(dtype=np.float64)
    msw_timestamps = (
        sorted(
            float(timestamp)
            for timestamp in idata[idata['MSW'].notna()][
                'Timestamp'
            ].values
        )
        if 'MSW' in idata.columns
        else []
    )
    target_starts = {
        float(timestamp) for timestamp in target_cycle_starts
    }

    for start_ms, end_ms in zip(hs, hs[1:]):
        cycle_to = [
            timestamp
            for timestamp in recovered_to
            if start_ms < timestamp < end_ms
        ]
        if cycle_to:
            continue
        if not any(
            abs(start_ms - target_start) <= 1.0
            for target_start in target_starts
        ):
            continue

        swing_anchor = recovered_ic_swing_anchors.get(float(end_ms))
        if swing_anchor is None:
            cycle_msw = [
                timestamp
                for timestamp in msw_timestamps
                if start_ms < timestamp < end_ms
            ]
            if len(cycle_msw) != 1:
                continue
            swing_anchor = cycle_msw[0]
        if not start_ms < swing_anchor < end_ms:
            continue

        search_start = start_ms + TC_OFFSET_AFTER_IC_MS
        mask = (
            (timestamps >= search_start)
            & (timestamps < swing_anchor)
        )
        window_signal = signal_values[mask]
        window_times = timestamps[mask]
        if window_signal.size < 3:
            continue
        valleys, _ = find_peaks(-window_signal)
        if valleys.size == 0:
            continue
        negative_valleys = [
            int(index)
            for index in valleys
            if float(window_signal[index]) < 0.0
        ]
        if not negative_valleys:
            continue
        best_index = min(
            negative_valleys,
            key=lambda index: float(window_signal[index]),
        )
        recovered_time = float(window_times[best_index])
        recovered_to.append(recovered_time)
        recovered_to.sort()
        recovered.append(recovered_time)
    return recovered_to, recovered


def recover_missing_gait_events_short_delay(
    idata,
    hs_timestamps,
    to_timestamps,
    opposite_hs_timestamps,
):
    """用一周期短延迟确认恢复漏检事件。

    候选 IC 必须由局部 MSW 正峰、正到负零交叉和后续负向波形构成，并满足左右脚交替。
    TC 不做幅值阈值判断，而是按 IC -> TC -> MSW -> 下一 IC 的相位顺序，
    取当前支撑相内的主负极值。
    """
    hs = sorted(float(timestamp) for timestamp in hs_timestamps)
    to_events = sorted(float(timestamp) for timestamp in to_timestamps)
    opposite_hs = sorted(
        float(timestamp) for timestamp in opposite_hs_timestamps
    )
    diagnostics = {
        "recovered_ic": [],
        "recovered_msw": [],
        "recovered_tc": [],
        "rejected_template_cycles": [],
    }
    existing_ms = (
        sorted(
            float(timestamp)
            for timestamp in idata[idata["MS"].notna()][
                "Timestamp"
            ].values
        )
        if "MS" in idata.columns
        else []
    )
    recovered_ic_swing_anchors = {}
    if len(hs) < 5 or len(opposite_hs) < 2:
        return idata, hs, to_events, existing_ms, diagnostics

    original_hs = list(hs)
    intervals = np.diff(np.asarray(original_hs, dtype=np.float64))
    regular_intervals = intervals[
        (intervals >= 2.0 * GAIT_STEP_INTERVAL_MIN_MS)
        & (intervals <= 2.0 * GAIT_STEP_INTERVAL_MAX_MS)
    ]
    if regular_intervals.size < 3:
        return idata, hs, to_events, existing_ms, diagnostics

    suspicious_gaps = []
    for gap_index, (left_ms, right_ms) in enumerate(
        zip(original_hs, original_hs[1:])
    ):
        neighboring = []
        for index in range(max(0, gap_index - 4), min(
            len(intervals), gap_index + 5
        )):
            if index == gap_index:
                continue
            interval = float(intervals[index])
            if (
                2.0 * GAIT_STEP_INTERVAL_MIN_MS
                <= interval
                <= 2.0 * GAIT_STEP_INTERVAL_MAX_MS
            ):
                neighboring.append(interval)
        if len(neighboring) < 2:
            continue
        local_stride_ms = float(np.median(neighboring))
        gap_ms = float(right_ms - left_ms)
        if gap_ms < local_stride_ms * GAIT_GAP_RECOVERY_RATIO:
            continue

        missing_count = int(round(gap_ms / local_stride_ms)) - 1
        if not 1 <= missing_count <= GAIT_GAP_RECOVERY_MAX_MISSING:
            continue
        expected_spacing = gap_ms / float(missing_count + 1)
        if not (
            0.65 * local_stride_ms
            <= expected_spacing
            <= 1.35 * local_stride_ms
        ):
            continue
        suspicious_gaps.append((
            float(left_ms),
            float(right_ms),
            local_stride_ms,
            missing_count,
            expected_spacing,
        ))

    if not suspicious_gaps:
        return idata, hs, to_events, existing_ms, diagnostics

    timestamps = idata["Timestamp"].to_numpy(dtype=np.float64)
    signal_values = idata["Gmax(°/s)"].to_numpy(dtype=np.float64)
    opposite_array = np.asarray(opposite_hs, dtype=np.float64)
    for (
        left_ms,
        right_ms,
        local_stride_ms,
        missing_count,
        expected_spacing,
    ) in suspicious_gaps:
        selected = []
        previous_boundary = float(left_ms)
        for slot in range(1, missing_count + 1):
            expected_time = float(left_ms + slot * expected_spacing)
            search_radius = local_stride_ms * GAIT_GAP_RECOVERY_SEARCH_RATIO
            window_start = int(np.searchsorted(
                timestamps,
                expected_time - search_radius,
                side="left",
            ))
            window_end = int(np.searchsorted(
                timestamps,
                expected_time + search_radius,
                side="right",
            ))
            if window_end - window_start < 5:
                selected = []
                break
            window_signal = signal_values[window_start:window_end]
            window_times = timestamps[window_start:window_end]
            candidate_events = detect_offline_gait_events(
                window_signal,
                window_times,
                min_same_foot_interval_ms=0.0,
            )
            candidates = []
            for event in candidate_events:
                candidate_time = float(event["ic_time"])
                candidate_value = float(event["negative_value"])
                if candidate_time - previous_boundary < 0.55 * local_stride_ms:
                    continue
                following_boundary = (
                    float(right_ms)
                    if slot == missing_count
                    else expected_time + expected_spacing
                )
                if following_boundary - candidate_time < 0.45 * local_stride_ms:
                    continue

                swing_time = float(event["msw_time"])
                swing_value = float(event["msw_value"])

                opposite_position = int(np.searchsorted(
                    opposite_array,
                    candidate_time,
                    side="left",
                ))
                previous_opposite = (
                    float(opposite_array[opposite_position - 1])
                    if opposite_position > 0
                    else None
                )
                next_position = int(np.searchsorted(
                    opposite_array,
                    candidate_time,
                    side="right",
                ))
                next_opposite = (
                    float(opposite_array[next_position])
                    if next_position < opposite_array.size
                    else None
                )
                if previous_opposite is None or next_opposite is None:
                    continue
                previous_step_ms = candidate_time - previous_opposite
                next_step_ms = next_opposite - candidate_time
                if not (
                    GAIT_STEP_INTERVAL_MIN_MS
                    <= previous_step_ms
                    <= GAIT_STEP_INTERVAL_MAX_MS
                    and GAIT_STEP_INTERVAL_MIN_MS
                    <= next_step_ms
                    <= GAIT_STEP_INTERVAL_MAX_MS
                ):
                    continue

                timing_score = float(np.exp(
                    -0.5
                    * (
                        (candidate_time - expected_time)
                        / max(search_radius * 0.55, 1.0)
                    ) ** 2
                ))
                bilateral_score = float(np.exp(
                    -abs(previous_step_ms - next_step_ms)
                    / max(local_stride_ms, 1.0)
                ))
                local_range = max(
                    float(np.max(window_signal) - np.min(window_signal)),
                    np.finfo(np.float64).eps,
                )
                waveform_score = min(
                    (swing_value - candidate_value) / local_range,
                    1.0,
                )
                candidates.append((
                    0.55 * timing_score
                    + 0.30 * bilateral_score
                    + 0.15 * waveform_score,
                    candidate_time,
                    swing_time,
                ))
            if not candidates:
                selected = []
                break
            _, recovered_time, swing_time = max(
                candidates,
                key=lambda item: item[0],
            )
            selected.append((recovered_time, swing_time))
            previous_boundary = recovered_time

        if len(selected) == missing_count:
            validated = []
            for recovered_time, swing_time in selected:
                previous_msw = max(
                    (
                        float(timestamp)
                        for timestamp in idata[
                            idata['MSW'].notna()
                            & (idata['Timestamp'] < swing_time)
                        ]['Timestamp'].values
                    ),
                    default=None,
                )
                similarity = (
                    _recovery_cycle_template_similarity(
                        idata,
                        previous_msw,
                        swing_time,
                    )
                    if previous_msw is not None
                    else None
                )
                if (
                    similarity is None
                    or similarity
                    < GAIT_RECOVERY_TEMPLATE_MIN_CORRELATION
                ):
                    diagnostics["rejected_template_cycles"].append({
                        "start_ms": (
                            int(round(previous_msw))
                            if previous_msw is not None
                            else None
                        ),
                        "end_ms": int(round(swing_time)),
                        "correlation": (
                            round(float(similarity), 3)
                            if similarity is not None
                            else None
                        ),
                    })
                    continue
                validated.append((recovered_time, swing_time))
            selected = validated

        if len(selected) == missing_count:
            recovered_times = [
                recovered_time for recovered_time, _ in selected
            ]
            hs.extend(recovered_times)
            recovered_ic_swing_anchors.update({
                float(recovered_time): float(swing_time)
                for recovered_time, swing_time in selected
            })
            diagnostics["recovered_ic"].extend(
                int(round(timestamp)) for timestamp in recovered_times
            )
            diagnostics["recovered_msw"].extend(
                int(round(swing_time)) for _, swing_time in selected
            )
            msw_after_recovery = sorted(
                float(timestamp)
                for timestamp in idata[idata['MSW'].notna()][
                    'Timestamp'
                ].values
                if float(timestamp) > recovered_times[-1]
            )
            if msw_after_recovery:
                recovered_ic_swing_anchors.update({
                    float(recovered_time): msw_after_recovery[0]
                    for recovered_time in recovered_times
                })

    hs = sorted(set(hs))
    if not diagnostics["recovered_ic"]:
        filtered_idata = sync_idata_ic_tc_to_event_lists(
            idata,
            hs,
            to_events,
        )
        filtered_idata = sync_idata_ms_to_event_list(
            filtered_idata,
            existing_ms,
        )
        return filtered_idata, hs, to_events, existing_ms, diagnostics

    target_cycle_starts = set()
    for recovered_ic in diagnostics["recovered_ic"]:
        recovered_ic = float(recovered_ic)
        previous_ic = max(
            (
                timestamp
                for timestamp in hs
                if timestamp < recovered_ic
            ),
            default=None,
        )
        if previous_ic is not None:
            target_cycle_starts.add(previous_ic)
        target_cycle_starts.add(recovered_ic)

    for swing_time in recovered_ic_swing_anchors.values():
        swing_index = nearest_timestamp_index(idata, swing_time)
        if swing_index is not None:
            idata.loc[swing_index, 'MSW'] = idata.loc[
                swing_index,
                'Gmax(°/s)',
            ]

    to_events, recovered_to = _recover_missing_tc_events_by_phase(
        idata,
        hs,
        to_events,
        target_cycle_starts,
        recovered_ic_swing_anchors,
    )
    diagnostics["recovered_tc"] = [
        int(round(timestamp)) for timestamp in recovered_to
    ]

    recovered_idata = sync_idata_ic_tc_to_event_lists(
        idata,
        hs,
        to_events,
    )
    ms_timestamps, ms_values = _detect_mid_stance_events(
        recovered_idata,
        hs,
        to_events,
    )
    recovered_idata['MS'] = np.nan
    for timestamp, value in zip(ms_timestamps, ms_values):
        index = nearest_timestamp_index(recovered_idata, timestamp)
        if index is not None:
            recovered_idata.loc[index, 'MS'] = value
    return recovered_idata, hs, to_events, ms_timestamps, diagnostics


def gait_identification(idata, fs=BASE_FS_HZ):
    """离线零相位滤波、批量候选提取和事件识别。"""
    idata = idata.copy()
    det = idata.copy()
    timestamps = det['Timestamp'].to_numpy(dtype=np.float64)
    gyro_pipeline = OfflineGaitEventPipeline(
        fs,
        min_same_foot_interval_ms=MSW_MIN_INTERVAL_MS,
    )
    filtered_gyro, _ = gyro_pipeline.process(
        timestamps,
        det['Gmax(°/s)'].to_numpy(dtype=np.float64),
        det['ACC.X'].to_numpy(dtype=np.float64),
        det['ACC.Y'].to_numpy(dtype=np.float64),
        det['ACC.Z'].to_numpy(dtype=np.float64),
    )
    det['Gmax(°/s)'] = filtered_gyro
    if gyro_pipeline.filtered_acc is not None:
        det[['ACC.X', 'ACC.Y', 'ACC.Z']] = (
            gyro_pipeline.filtered_acc
        )
    idata['Gmax(°/s)'] = det['Gmax(°/s)'].values
    idata['gyroscopic_energy'] = np.linalg.norm(
        idata[['Gyro.X', 'Gyro.Y', 'Gyro.Z']].to_numpy(
            dtype=np.float64
        ),
        axis=1,
    )

    ic_events = gyro_pipeline.detector.events
    MSW_timestamps = np.asarray(
        [event["msw_time"] for event in ic_events],
        dtype=np.float64,
    )
    IC_timestamps = np.asarray(
        [event["ic_time"] for event in ic_events],
        dtype=np.float64,
    )
    event_count = len(idata)
    gyro_values = idata['Gmax(°/s)'].to_numpy(dtype=np.float64)
    tc_signal = (
        savgol_filter(gyro_values, window_length=5, polyorder=2)
        if gyro_values.size >= 5
        else gyro_values
    )
    msw_column = np.full(event_count, np.nan, dtype=np.float64)
    ic_column = np.full(event_count, np.nan, dtype=np.float64)
    ic_is_zc = np.zeros(event_count, dtype=bool)
    if ic_events:
        msw_positions = nearest_timestamp_positions(
            timestamps,
            MSW_timestamps,
        )
        ic_positions = nearest_timestamp_positions(
            timestamps,
            IC_timestamps,
        )
        msw_column[msw_positions] = np.asarray([
            event["msw_value"] for event in ic_events
        ])
        ic_column[ic_positions] = gyro_values[ic_positions]
        ic_is_zc[ic_positions] = True
    idata['MSW'] = msw_column
    idata['IC'] = ic_column
    idata['IC_raw'] = np.nan
    idata['IC_is_zc'] = ic_is_zc

    def find_tc_position(start_position, end_position, is_ic_zc):
        if end_position - start_position < 2:
            return None, None
        smoothed = tc_signal[start_position:end_position + 1]
        valleys, _ = find_peaks(-smoothed)
        valid_valleys = valleys[smoothed[valleys] < 0.0]
        if valid_valleys.size == 0:
            return None, None
        if is_ic_zc:
            ic_alt_position = int(
                start_position + valid_valleys[0]
            )
            tc_candidates = valid_valleys[1:]
            if tc_candidates.size == 0:
                return ic_alt_position, ic_alt_position
            tc_local = int(
                tc_candidates[np.argmin(smoothed[tc_candidates])]
            )
            return start_position + tc_local, ic_alt_position
        tc_local = int(
            valid_valleys[np.argmin(smoothed[valid_valleys])]
        )
        return start_position + tc_local, None

    tc_windows = []
    if len(MSW_timestamps) > 0:
        first_msw_position = int(
            nearest_timestamp_positions(
                timestamps,
                [MSW_timestamps[0]],
            )[0]
        )
        tc_windows.append((0, max(0, first_msw_position - 1), False))
    for ic_time in IC_timestamps:
        next_msw_index = int(
            np.searchsorted(MSW_timestamps, ic_time, side='right')
        )
        if next_msw_index >= MSW_timestamps.size:
            continue
        start_position, end_position = nearest_timestamp_positions(
            timestamps,
            [ic_time, MSW_timestamps[next_msw_index]],
        )
        tc_windows.append((
            int(start_position),
            int(end_position),
            True,
        ))

    tc_positions = []
    ic_alt_positions = []
    for start_position, end_position, is_ic_zc in tc_windows:
        tc_position, ic_alt_position = find_tc_position(
            start_position,
            end_position,
            is_ic_zc,
        )
        if tc_position is not None:
            tc_positions.append(tc_position)
        if ic_alt_position is not None:
            ic_alt_positions.append(ic_alt_position)

    tc_column = np.full(event_count, np.nan, dtype=np.float64)
    ic_alt_column = np.full(event_count, np.nan, dtype=np.float64)
    if tc_positions:
        tc_positions = np.unique(np.asarray(tc_positions, dtype=np.int64))
        tc_column[tc_positions] = gyro_values[tc_positions]
    if ic_alt_positions:
        ic_alt_positions = np.unique(
            np.asarray(ic_alt_positions, dtype=np.int64)
        )
        ic_alt_column[ic_alt_positions] = gyro_values[ic_alt_positions]
    idata['TC'] = tc_column
    idata['TC_raw'] = np.nan
    idata['IC_alt'] = ic_alt_column

    HS_timestamps = timestamps[np.flatnonzero(~np.isnan(ic_column))]
    TO_timestamps = timestamps[np.flatnonzero(~np.isnan(tc_column))]

    # # 记录原始 IC 供调试绘图
    # original_hs = list(HS_timestamps)
    # ic_windows = []
    # acc_x_valleys_list = []

    # # ---------- IC 细化（ACC.X 波谷 × 0.7 + 陀螺仪波谷 × 0.3）----------
    # # 对每个粗检 IC，在动态窗口（相邻 IC 间距 15%）内找 ACC.X 最小谷，
    # # 同时在窗口内找最近陀螺仪谷底，加权融合得到更精确的 IC
    # HS_refined = list(HS_timestamps)
    # for i, ic_time in enumerate(HS_timestamps):
    #     if len(HS_timestamps) < 2:
    #         continue
    #     if i < len(HS_timestamps) - 1:
    #         window_size = (HS_timestamps[i + 1] - HS_timestamps[i]) * 0.15
    #     else:
    #         window_size = (HS_timestamps[i] - HS_timestamps[i - 1]) * 0.15

    #     w_start = ic_time - window_size
    #     w_end = ic_time + window_size
    #     window = idata[(idata['Timestamp'] >= w_start) &
    #                    (idata['Timestamp'] <= w_end)]
    #     if window.empty:
    #         continue

    #     ic_windows.append({'start': float(w_start), 'end': float(w_end)})

    #     # ACC.X 最小谷（负峰）
    #     acc_x_vals = window['ACC.X'].values
    #     acc_x_valleys, _ = find_peaks(-acc_x_vals, distance=5)
    #     if len(acc_x_valleys) == 0:
    #         continue
    #     min_valley_local = acc_x_valleys[np.argmin(acc_x_vals[acc_x_valleys])]
    #     acc_x_valley_time = window['Timestamp'].iloc[min_valley_local]
    #     acc_x_valleys_list.append([float(acc_x_valley_time), float(acc_x_vals[min_valley_local])])

    #     # 陀螺仪最近谷底
    #     gyro_vals = window['Gmax(°/s)'].values
    #     gyro_valleys, _ = find_peaks(-gyro_vals, distance=5)
    #     if len(gyro_valleys) > 0:
    #         gyro_valley_times = window['Timestamp'].iloc[gyro_valleys].values
    #         closest_local = gyro_valleys[np.argmin(np.abs(gyro_valley_times - ic_time))]
    #         gyro_valley_time = window['Timestamp'].iloc[closest_local]
    #     else:
    #         gyro_valley_time = ic_time

    #     # 加权融合（ACC.X 主导 70%，陀螺仪辅助 30%）
    #     fused = 0.7 * acc_x_valley_time + 0.3 * gyro_valley_time
    #     nearest_idx = (np.abs(idata['Timestamp'] - fused)).idxmin()
    #     HS_refined[i] = int(idata.loc[nearest_idx, 'Timestamp'])

    # HS_timestamps = sorted(set(HS_refined))

    # # --------- 1️⃣ method
    # MS_timestamps = []
    # MS_values = []
    # data_end = int(idata['Timestamp'].iloc[-1])
    # for hs_idx, hs_time in enumerate(HS_timestamps):
    #     following_TO_times = [to_time for to_time in TO_timestamps if to_time > hs_time]
    #     if following_TO_times:
    #         next_TO_time = following_TO_times[0]
    #     elif hs_idx == len(HS_timestamps) - 1:
    #         next_TO_time = min(hs_time + 1000, data_end)
    #     else:
    #         continue
    #     # 提取支撑期数据
    #     mask = (idata['Timestamp'] > hs_time) & (idata['Timestamp'] < next_TO_time)
    #     support_data = idata.loc[mask].reset_index(drop=True)
    #     N = len(support_data)
    #     if N < 5:
    #         continue
    #     # --------- 1. 原逻辑：去除前后10% ---------
    #     ten_percent = int(0.1 * N)
    #     if N - 2 * ten_percent < 1:
    #         continue
    #     core_data = support_data.iloc[ten_percent:N - ten_percent].reset_index(drop=True)
    #     timestamps = core_data['Timestamp'].values
    #     # --------- 2. 滑动窗口平均（窗口大小为30%） ---------
    #     window_ratio = 0.3
    #     window_size = max(3, int(len(core_data) * window_ratio))
    #     if window_size % 2 == 0:
    #         window_size += 1  # 保证是奇数，中心对称
    #     half_window = window_size // 2
    #     gyro_energy_mean = []
    #     for i in range(half_window, len(core_data) - half_window):
    #         window = core_data.iloc[i - half_window: i + half_window + 1]
    #         mean_energy = window['gyroscopic_energy'].mean()
    #         gyro_energy_mean.append(mean_energy)
    #     if not gyro_energy_mean:
    #         continue
    #     # --------- 3. 找滑动均值最小处，对应窗口中心时间 ---------
    #     min_idx = np.argmin(gyro_energy_mean)
    #     ms_candidate_time = timestamps[min_idx + half_window]

    #     # # --------- 新逻辑：直接在整个 hs 到 to 间找到 gyro_energy 最小的区间 (保留滑窗过滤毛刺) ---------
    #     # timestamps = support_data['Timestamp'].values
    #     # window_ratio = 0.3
    #     # window_size = max(3, int(N * window_ratio))
    #     # if window_size % 2 == 0:
    #     #     window_size += 1
    #     # half_window = window_size // 2

    #     # gyro_energy_mean = []
    #     # for i in range(half_window, N - half_window):
    #     #     window = support_data.iloc[i - half_window: i + half_window + 1]
    #     #     gyro_energy_mean.append(window['gyroscopic_energy'].mean())

    #     # if not gyro_energy_mean:
    #     #     continue

    #     # min_idx = np.argmin(gyro_energy_mean)
    #     # ms_candidate_time = timestamps[min_idx + half_window]

    #     if ms_candidate_time not in MS_timestamps:
    #         MS_timestamps.append(int(ms_candidate_time))
    #         MS_values.append(gyro_energy_mean[min_idx])

    # ---------- MS 检测（Method 3）----------
    # 去除支撑期前后 10%，在核心段用滑窗计算角速度能量 T_ω 和加速度方差 T_v，
    # 以当前窗口方差与相邻步连续性加权融合得到支撑中期时刻
    MS_timestamps, MS_values = _detect_mid_stance_events(
        idata,
        HS_timestamps,
        TO_timestamps,
    )

    ms_column = np.full(len(idata), np.nan, dtype=np.float64)
    if MS_timestamps:
        if timestamps.size == 0:
            return HS_timestamps, TO_timestamps, [], idata, None
        ms_positions = nearest_timestamp_positions(
            timestamps,
            MS_timestamps,
        )
        ms_column[ms_positions] = np.asarray(
            MS_values,
            dtype=np.float64,
        )
    idata['MS'] = ms_column

    # ic_fusion = {
    #     'original_hs': [int(x) for x in original_hs],
    #     'ic_windows': ic_windows,
    #     'acc_x_valleys': acc_x_valleys_list
    # }
    ic_fusion = None

    return HS_timestamps, TO_timestamps, MS_timestamps, idata, ic_fusion


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
    search_last_n=0,
):
    """
    自动估计跳远起跳步（第几个 TC，1-based）：在助跑段步频相对稳定后，
    第一次出现相对基线明显骤降的那一步，视为起跳（腾空导致步态周期拉长 → 瞬时步频下降）。

    基线取当前步之前若干步频的中位数（最多看前 3 步），降幅 = (基线 - 当前) / 基线。
    默认扫描全段，避免跳跃后还继续走动时把真正起跳段排除在搜索范围外。

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


def infer_takeoff_step_by_signal_burst(
    TO_timestamps,
    idata,
    min_peak_z=4.0,
    max_to_after_peak_ms=250.0,
):
    """
    自动识别的后备策略：用角速度/加速度的强峰值定位跳跃冲击，再取峰值前最近的 TC。

    这主要覆盖两类情况：
    1. 起跳后还有较长的走动数据，步频突降不在最后几个周期；
    2. 步频变化不稳定，但 IMU 信号在起跳/落地区有明显峰值。
    """
    TO = sorted(float(t) for t in TO_timestamps)
    if len(TO) < 3 or idata is None or "Timestamp" not in idata.columns:
        return -1, "数据不足"

    ts = idata["Timestamp"].to_numpy(dtype=float)
    if ts.size == 0:
        return -1, "数据不足"

    score_parts = []

    def robust_z(values):
        arr = np.asarray(values, dtype=np.float64)
        arr = np.nan_to_num(arr, nan=0.0, posinf=0.0, neginf=0.0)
        med = float(np.median(arr))
        mad = float(np.median(np.abs(arr - med)))
        scale = max(1.4826 * mad, 1e-6)
        return (arr - med) / scale

    if "Gmax(°/s)" in idata.columns:
        gyro_abs = np.abs(idata["Gmax(°/s)"].to_numpy(dtype=float))
        score_parts.append(robust_z(gyro_abs))

    acc_cols = [c for c in ("ACC.X", "ACC.Y", "ACC.Z") if c in idata.columns]
    if len(acc_cols) == 3:
        acc = idata[acc_cols].to_numpy(dtype=float)
        acc_norm = np.linalg.norm(np.nan_to_num(acc, nan=0.0), axis=1)
        score_parts.append(robust_z(acc_norm))

    if not score_parts:
        return -1, "缺少角速度/加速度信号"

    score = np.max(np.vstack(score_parts), axis=0)
    peak_idx = int(np.argmax(score))
    peak_score = float(score[peak_idx])
    if not np.isfinite(peak_score) or peak_score < min_peak_z:
        return -1, "未找到明显起跳冲击"

    peak_time = float(ts[peak_idx])
    candidates = [
        (i, t)
        for i, t in enumerate(TO)
        if t <= peak_time + max_to_after_peak_ms
    ]
    if candidates:
        j, t_takeoff = candidates[-1]
    else:
        j = int(np.argmin([abs(t - peak_time) for t in TO]))
        t_takeoff = TO[j]

    print(
        f"GAIT_LOG_INFO: auto takeoff step={j + 1} by signal burst "
        f"(peak_z={peak_score:.1f}, peak_time={peak_time:.0f}ms, tc_time={t_takeoff:.0f}ms)"
    )
    return j + 1, "信号峰值识别"


def calculate_bilateral_flight_time(
    primary_hs,
    primary_to,
    contralateral_hs,
):
    """按双脚真实事件计算腾空时间 (ms)。

    对每个主脚 TC，寻找其之后的第一个对侧 IC。只有当这个对侧 IC
    早于主脚下一次 IC 时，TC -> 对侧 IC 才是无支撑的真实腾空区间；
    如果主脚先再次 IC，则属于行走/双支撑时序，不记录腾空时间。
    """
    primary_hs_sorted = sorted(float(t) for t in primary_hs)
    primary_to_sorted = sorted(float(t) for t in primary_to)
    contralateral_hs_sorted = sorted(float(t) for t in contralateral_hs)
    flight_time_info = []
    for to_time in primary_to_sorted:
        next_primary_hs = next(
            (hs_time for hs_time in primary_hs_sorted if hs_time > to_time),
            None,
        )
        next_contralateral_hs = next(
            (
                hs_time
                for hs_time in contralateral_hs_sorted
                if hs_time > to_time
            ),
            None,
        )
        if (
            next_primary_hs is not None
            and next_contralateral_hs is not None
            and next_contralateral_hs < next_primary_hs
        ):
            flight_time_info.append(
                (to_time, next_contralateral_hs - to_time)
            )
    return flight_time_info


def calculate_gait_status(
    contact_time_info,
    swing_time_info,
    bilateral_flight_time_info=None,
):
    """根据真实双脚事件判断跑/走，并返回腾空时间和双支撑时间。"""
    gait_status_info = []
    flight_time_info = []
    double_support_time_info = []
    if bilateral_flight_time_info is None:
        return gait_status_info, flight_time_info, double_support_time_info

    flight_dict = dict(bilateral_flight_time_info)
    for to_time, contact_time in contact_time_info:
        flight_time = flight_dict.get(to_time)
        if flight_time is not None and flight_time > 0:
            gait_status_info.append((to_time, "Run"))
            flight_time_info.append((to_time, flight_time))
        else:
            gait_status_info.append((to_time, "Walk"))
    return gait_status_info, flight_time_info, double_support_time_info


def calculate_bilateral_double_support(
    primary_hs,
    primary_to,
    contralateral_hs,
    contralateral_to,
):
    """按左右脚真实支撑区间交集计算双足支撑时间。

    每个支撑区间由同一只脚的 IC -> 下一次 IC 前最早 TC 构成。返回值以主脚
    TC 为键，数值是该次主脚支撑期间与对侧所有支撑区间的交叠总时长（ms）。
    跑步时两侧支撑区间不相交，结果自然为 0。
    """
    return _calculate_bilateral_double_support(
        primary_hs,
        primary_to,
        contralateral_hs,
        contralateral_to,
    )


def calculate_stride_length(idata, HS_timestamps, TO_timestamps, MS_timestamps):
    """步幅 (m)，ZUPT 积分 + 线性漂移修正，返回 [(to_time, length_m), ...]

    与 reference spatio_temporal_parameter.py 完全对齐：
      - 积分窗口 = MS[i] → MS[i+1]（两端 foot-flat，近似零速）
      - 前向梯形积分加速度 → 速度
      - 线性漂移修正：corrected[j] = raw[j] - (j/(n-1)) * raw[-1]
      - 再次梯形积分修正后速度 → 位移
      - 步幅 = sqrt(ΔX² + ΔY²)
    """
    return _calculate_stride_length(
        idata,
        HS_timestamps,
        TO_timestamps,
        MS_timestamps,
        GRAVITY,
    )


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
    第 n 个 TO 为起跳时：保留该 TO 时刻 t_cut 之前及同一时刻的所有 IC（HS），
    t_cut 之后的 IC 全部丢弃，避免把腾空/落地后的点继续当作助跑事件。

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
    kept = [h for h in HS if h <= t_cut]
    return [int(round(h)) for h in kept], True


def filter_ms_for_long_jump(MS_timestamps, TO_timestamps, takeoff_step_1based, HS_timestamps=None):
    """
    跳远起跳 TC 之后不再识别 MS。
    只保留起跳 TC 时刻 t_cut 之前及同一时刻的 MS。
    """
    MS = sorted(float(t) for t in MS_timestamps)
    TO = sorted(float(t) for t in TO_timestamps)
    n = int(takeoff_step_1based)
    if n <= 0:
        return [int(round(m)) for m in MS], False
    if len(TO) < n:
        return [int(round(m)) for m in MS], False

    t_cut = TO[n - 1]
    kept = [m for m in MS if m <= t_cut]
    return [int(round(m)) for m in kept], True


def filter_msw_for_long_jump(MSW_timestamps, TO_timestamps, takeoff_step_1based):
    """
    起跳 TC 之后不再识别 MSW。
    只保留起跳 TC 时刻 t_cut 之前及同一时刻的 MSW。
    """
    MSW = sorted(float(t) for t in MSW_timestamps)
    TO = sorted(float(t) for t in TO_timestamps)
    n = int(takeoff_step_1based)
    if n <= 0:
        return [int(round(x)) for x in MSW], False
    if len(TO) < n:
        return [int(round(x)) for x in MSW], False
    t_cut = TO[n - 1]
    kept = [x for x in MSW if x <= t_cut]
    return [int(round(x)) for x in kept], True


def truncate_signal_at_takeoff(idata, takeoff_to_ms):
    """以起跳脚离地 TO 为助跑分析硬终点，彻底排除其后的信号样本。"""
    if idata is None or idata.empty or 'Timestamp' not in idata.columns:
        return idata
    truncated = idata[idata['Timestamp'] <= float(takeoff_to_ms)].copy()
    truncated.reset_index(drop=True, inplace=True)
    return truncated


def sync_idata_ic_tc_to_event_lists(idata, HS_list, TO_list):
    """仅在与 HS_list、TO_list 对齐的最近邻采样行保留 IC/TC 标记（供前端曲线叠加）。"""
    idata = idata.copy()
    if idata.empty:
        idata['IC'] = np.nan
        idata['TC'] = np.nan
        return idata
    timestamps = idata['Timestamp'].to_numpy(dtype=np.float64)
    gyro = idata['Gmax(°/s)'].to_numpy(dtype=np.float64)
    ic_values = np.full(len(idata), np.nan, dtype=np.float64)
    tc_values = np.full(len(idata), np.nan, dtype=np.float64)
    ic_positions = nearest_timestamp_positions(timestamps, HS_list)
    tc_positions = nearest_timestamp_positions(timestamps, TO_list)
    ic_values[ic_positions] = gyro[ic_positions]
    tc_values[tc_positions] = gyro[tc_positions]
    idata['IC'] = ic_values
    idata['TC'] = tc_values
    return idata


def sync_idata_msw_to_event_list(idata, MSW_timestamps_kept):
    """仅在与 MSW_timestamps_kept 对齐的最近邻采样行保留 MSW（与 IC/TC 同步策略一致，保留原检测幅值）。"""
    idata = idata.copy()
    mask = idata['MSW'].notna()
    if not mask.any():
        return idata
    orig_times = idata.loc[mask, 'Timestamp'].astype(float).values
    orig_vals = idata.loc[mask, 'MSW'].values
    if len(orig_times) == 0:
        return idata
    values = np.full(len(idata), np.nan, dtype=np.float64)
    timestamps = idata['Timestamp'].to_numpy(dtype=np.float64)
    target_positions = nearest_timestamp_positions(
        timestamps,
        MSW_timestamps_kept,
    )
    source_positions = nearest_timestamp_positions(
        orig_times,
        timestamps[target_positions],
    )
    values[target_positions] = orig_vals[source_positions]
    idata['MSW'] = values
    return idata


def sync_idata_ms_to_event_list(idata, MS_timestamps_kept):
    """仅在与 MS_timestamps_kept 对齐的最近邻采样行保留 MS。"""
    idata = idata.copy()
    mask = idata['MS'].notna()
    if not mask.any():
        return idata
    orig_times = idata.loc[mask, 'Timestamp'].astype(float).values
    orig_vals = idata.loc[mask, 'MS'].values
    if len(orig_times) == 0:
        return idata
    values = np.full(len(idata), np.nan, dtype=np.float64)
    timestamps = idata['Timestamp'].to_numpy(dtype=np.float64)
    target_positions = nearest_timestamp_positions(
        timestamps,
        MS_timestamps_kept,
    )
    source_positions = nearest_timestamp_positions(
        orig_times,
        timestamps[target_positions],
    )
    values[target_positions] = orig_vals[source_positions]
    idata['MS'] = values
    return idata


def filter_events_to_gait_bouts(events, gait_bouts):
    """仅保留稳定步态段中的事件时间戳。"""
    return [
        int(round(float(timestamp)))
        for timestamp in events
        if any(
            float(bout["start_ms"]) <= float(timestamp) <= float(bout["end_ms"])
            for bout in gait_bouts
        )
    ]


def _complete_stance_cycle_ratio(hs_events, to_events, start_ms, end_ms):
    """计算区间内具有唯一、时序合理 TC 的同脚 IC->IC 周期比例。"""
    hs = sorted(
        float(t) for t in hs_events if float(start_ms) <= float(t) <= float(end_ms)
    )
    to = sorted(
        float(t) for t in to_events if float(start_ms) <= float(t) <= float(end_ms)
    )
    total = 0
    complete = 0
    for hs_start, hs_end in zip(hs, hs[1:]):
        stride_ms = hs_end - hs_start
        if stride_ms <= 0:
            continue
        total += 1
        candidates = [t for t in to if hs_start < t < hs_end]
        if len(candidates) != 1:
            continue
        contact_ms = candidates[0] - hs_start
        if 60.0 <= contact_ms <= stride_ms * 0.90:
            complete += 1
    return float(complete / total) if total > 0 else 0.0


def detect_bilateral_gait_bouts(
    primary_idata,
    primary_hs,
    primary_to,
    contralateral_idata,
    contralateral_hs,
    contralateral_to,
):
    """检测可用于指标计算的连续稳定双侧步态段。

    判定条件：
    - 左右脚 IC 必须交替出现；
    - 相邻脚步时间处于合理范围，并与当前局部节律一致；
    - 每侧至少存在完整 IC->TC->下一 IC 周期；
    - 周期相关性用于启动确认，不作为单步硬过滤；
    - 左右脚各确认一个重复周期后进入 ACTIVE；
    - 同侧重复、节律越界或周期不完整结束当前段；
    - 单侧形态变化视为转身等正常步态，双侧连续多周期异常才结束当前段。
    """
    contacts = _build_bilateral_cycle_contacts(
        primary_idata,
        primary_hs,
        primary_to,
        contralateral_idata,
        contralateral_hs,
        contralateral_to,
    )
    diagnostics = {
        "method": "bilateral_event_state_v5_short_delay",
        "candidate_contact_count": int(len(contacts)),
        "activation_contact_count": int(GAIT_BOUT_MIN_CONTACTS),
        "rejected_sequence_count": 0,
        "valid_bouts": [],
    }
    if len(contacts) < GAIT_BOUT_MIN_CONTACTS:
        return [], diagnostics

    segmented_sequences = segment_bilateral_contacts(contacts)

    gait_bouts = []
    for segmented in segmented_sequences:
        sequence = segmented["contacts"]
        if (
            not segmented["reached_active"]
            or len(sequence) < GAIT_BOUT_MIN_CONTACTS
        ):
            diagnostics["rejected_sequence_count"] += 1
            continue

        stable_contacts = (
            sequence[:-1]
            if segmented["end_reason"] == "cycle_quality_break"
            else sequence
        )
        if len(stable_contacts) < 4:
            diagnostics["rejected_sequence_count"] += 1
            continue
        start_ms = float(stable_contacts[0][0])
        end_ms = float(stable_contacts[-1][0])
        primary_cycle_ratio = _complete_stance_cycle_ratio(
            primary_hs, primary_to, start_ms, end_ms
        )
        contralateral_cycle_ratio = _complete_stance_cycle_ratio(
            contralateral_hs, contralateral_to, start_ms, end_ms
        )
        if (
            primary_cycle_ratio < GAIT_BOUT_MIN_COMPLETE_CYCLE_RATIO
            or contralateral_cycle_ratio
            < GAIT_BOUT_MIN_COMPLETE_CYCLE_RATIO
        ):
            diagnostics["rejected_sequence_count"] += 1
            continue

        correlations = [
            float(contact[3])
            for contact in contacts
            if (
                start_ms <= float(contact[0]) <= end_ms
                and contact[3] is not None
                and np.isfinite(contact[3])
            )
        ]
        bout = {
            "start_ms": int(round(start_ms)),
            "end_ms": int(round(end_ms)),
            "duration_s": float((end_ms - start_ms) / MS_PER_S),
            "contact_count": int(len(stable_contacts)),
            "primary_complete_cycle_ratio": round(primary_cycle_ratio, 3),
            "contralateral_complete_cycle_ratio": round(
                contralateral_cycle_ratio, 3
            ),
            "median_cycle_correlation": round(
                float(np.median(correlations)),
                3,
            ) if correlations else None,
            "end_reason": segmented["end_reason"],
        }
        gait_bouts.append(bout)
        diagnostics["valid_bouts"].append(dict(bout))

    diagnostics["valid_bout_count"] = int(len(gait_bouts))
    diagnostics["valid_duration_s"] = float(
        sum(float(bout["duration_s"]) for bout in gait_bouts)
    )
    return gait_bouts, diagnostics


def _complete_single_leg_cycles(hs_events, to_events):
    """返回相位完整且时长合理的单脚 IC->TC->下一 IC 周期。"""
    hs = sorted(float(t) for t in hs_events)
    toe_off = sorted(float(t) for t in to_events)
    candidates = []
    for cycle_start, cycle_end in zip(hs, hs[1:]):
        cycle_ms = cycle_end - cycle_start
        if cycle_ms < GAIT_STEP_INTERVAL_MIN_MS * 2.0:
            continue
        cycle_to = [
            timestamp
            for timestamp in toe_off
            if cycle_start < timestamp < cycle_end
        ]
        if len(cycle_to) != 1:
            continue
        contact_ms = cycle_to[0] - cycle_start
        if not 60.0 <= contact_ms <= cycle_ms * 0.90:
            continue
        candidates.append({
            "start_ms": cycle_start,
            "end_ms": cycle_end,
            "to_ms": cycle_to[0],
            "duration_ms": cycle_ms,
        })

    if not candidates:
        return []
    median_cycle_ms = float(np.median([
        cycle["duration_ms"] for cycle in candidates
    ]))
    max_cycle_ms = max(
        GAIT_STEP_INTERVAL_MAX_MS * 2.0,
        median_cycle_ms * SINGLE_LEG_CYCLE_INTERVAL_RATIO_MAX,
    )
    return [
        cycle
        for cycle in candidates
        if cycle["duration_ms"] <= max_cycle_ms
    ]


def _single_leg_events_for_cycles(
    cycles,
    hs_events,
    to_events,
    ms_events,
    msw_events,
):
    """仅保留至少属于一个有效单脚周期的事件。"""
    if not cycles:
        return {"hs": [], "to": [], "ms": [], "msw": []}

    def in_cycle(timestamp):
        value = float(timestamp)
        return any(
            cycle["start_ms"] <= value <= cycle["end_ms"]
            for cycle in cycles
        )

    cycle_hs = sorted({
        int(round(timestamp))
        for cycle in cycles
        for timestamp in (cycle["start_ms"], cycle["end_ms"])
    })
    cycle_to = sorted({
        int(round(cycle["to_ms"]))
        for cycle in cycles
    })
    return {
        "hs": cycle_hs,
        "to": cycle_to,
        "ms": sorted(
            int(round(timestamp))
            for timestamp in ms_events
            if in_cycle(timestamp)
        ),
        "msw": sorted(
            int(round(timestamp))
            for timestamp in msw_events
            if in_cycle(timestamp)
        ),
    }


def calculate_single_leg_strides_with_pairing(
    hs_events,
    to_events,
    ms_events,
    idata,
    cycles,
    paired_strides,
):
    """计算全部完整单脚周期，并只为锁定周期附加双脚联合指标。"""
    valid_to = {
        int(round(cycle["to_ms"]))
        for cycle in cycles
    }
    paired_by_to = {
        int(round(stride["to_timestamp_ms"])): stride
        for stride in paired_strides
        if stride.get("to_timestamp_ms") is not None
    }
    single_leg_strides = calculate_spatio_temporal(
        hs_events,
        to_events,
        ms_events,
        idata,
        is_long_jump=False,
        contralateral_hs=None,
        contralateral_to=None,
    )
    merged = []
    for stride in single_leg_strides:
        to_time = stride.get("to_timestamp_ms")
        if to_time is None:
            continue
        to_key = int(round(to_time))
        if to_key not in valid_to:
            continue
        paired = paired_by_to.get(to_key)
        if paired is not None:
            combined = dict(paired)
            combined["bilaterally_paired"] = True
            merged.append(combined)
            continue

        unpaired = dict(stride)
        unpaired["double_support_time_ms"] = None
        unpaired["flight_time_ms"] = None
        unpaired["vGRF_peak_BW"] = None
        unpaired["gait_status"] = "Unpaired"
        unpaired["bilaterally_paired"] = False
        merged.append(unpaired)
    merged.sort(key=lambda stride: stride.get("to_timestamp_ms", 0))
    return merged


def calculate_spatio_temporal_by_bouts(
    hs_events,
    to_events,
    ms_events,
    idata,
    gait_bouts,
    contralateral_hs=None,
    contralateral_to=None,
):
    """逐连续步态段计算指标；保留转身周期，不跨停顿连接周期。"""
    strides = []
    for bout_index, bout in enumerate(gait_bouts):
        start_ms = float(bout["start_ms"])
        end_ms = float(bout["end_ms"])
        hs = [t for t in hs_events if start_ms <= float(t) <= end_ms]
        to = [t for t in to_events if start_ms <= float(t) <= end_ms]
        ms = [t for t in ms_events if start_ms <= float(t) <= end_ms]
        if len(hs) < 2 or len(to) < 1:
            continue
        segment = idata[
            (idata["Timestamp"] >= start_ms) & (idata["Timestamp"] <= end_ms)
        ].copy()
        if segment.empty:
            continue
        contra_hs = (
            [
                t
                for t in contralateral_hs
                if start_ms <= float(t) <= end_ms
            ]
            if contralateral_hs is not None
            else None
        )
        contra_to = (
            [
                t
                for t in contralateral_to
                if start_ms <= float(t) <= end_ms
            ]
            if contralateral_to is not None
            else None
        )
        segment_strides = calculate_spatio_temporal(
            hs,
            to,
            ms,
            segment,
            is_long_jump=False,
            contralateral_hs=contra_hs,
            contralateral_to=contra_to,
        )
        for stride in segment_strides:
            stride["gait_bout_index"] = int(bout_index)
        strides.extend(segment_strides)
    strides.sort(key=lambda stride: stride.get("to_timestamp_ms", 0))
    return strides


def calculate_spatio_temporal(
    HS,
    TO,
    MS,
    idata,
    is_long_jump=False,
    include_terminal_contact=False,
    contralateral_hs=None,
    contralateral_to=None,
):
    """编排器：调用各独立函数，按 contact_time_info 遍历组装 stride 字典"""
    ct_info = calculate_contact_time(HS, TO)
    terminal_to = max(TO) if (is_long_jump or include_terminal_contact) and TO else None
    if terminal_to is not None and not any(to_time == terminal_to for to_time, _ in ct_info):
        terminal_ic = max((h for h in HS if h < terminal_to), default=None)
        if terminal_ic is not None:
            ct_info.append((terminal_to, terminal_to - terminal_ic))
            ct_info.sort(key=lambda item: item[0])
    sw_info = calculate_swing_time(HS, TO)
    st_info = calculate_stride_time(HS, TO)
    sf_info = calculate_step_frequency(HS, TO)
    has_contralateral_hs = contralateral_hs is not None
    bilateral_ft_info = (
        calculate_bilateral_flight_time(HS, TO, contralateral_hs)
        if has_contralateral_hs
        else None
    )
    gs_info, ft_info, inferred_dst_info = calculate_gait_status(
        ct_info,
        sw_info,
        bilateral_flight_time_info=bilateral_ft_info,
    )
    has_bilateral_events = contralateral_hs is not None and contralateral_to is not None
    bilateral_dst_info = (
        calculate_bilateral_double_support(HS, TO, contralateral_hs, contralateral_to)
        if has_bilateral_events
        else []
    )
    sl_info = calculate_stride_length(idata, HS, TO, MS)
    # vGRF 的既有估算公式仍需要步行/跑步状态对应的单脚周期项；输出给用户和
    # manifest 的双足支撑时间只能使用双侧事件交叠结果。
    vgrf_support_info = bilateral_dst_info if has_bilateral_events else inferred_dst_info
    vgrf_info = calculate_vGRF(gs_info, ft_info, vgrf_support_info, ct_info)

    ct_dict = dict(ct_info)
    sw_dict = dict(sw_info)
    st_dict = dict(st_info)
    sf_dict = dict(sf_info)
    gs_dict = dict(gs_info)
    ft_dict = dict(ft_info)
    dst_dict = dict(bilateral_dst_info)
    sl_dict = dict(sl_info)
    vgrf_dict = dict(vgrf_info)

    strides = []
    for i, (to_time, contact_time) in enumerate(ct_info):
        before = [h for h in HS if h < to_time]
        after = [h for h in HS if h > to_time]
        hs_start = max(before) if before else None
        hs_next = min(after) if after else None
        if hs_start is None:
            continue
        if hs_next is None:
            if terminal_to is None or to_time != terminal_to:
                continue
            strides.append({
                "hs_timestamp_ms": int(hs_start),
                "to_timestamp_ms": int(to_time),
                "stride_time_s": None,
                "contact_time_ms": int(round(contact_time)),
                "double_support_time_ms": None,
                "swing_time_ms": None,
                "step_frequency_spm": None,
                "stride_length_m": None,
                "stride_velocity_mps": None,
                "vGRF_peak_BW": None,
                "flight_time_ms": None,
                "gait_status": "Takeoff" if is_long_jump else "TerminalContact"
            })
            continue

        stride_time = st_dict.get(to_time, hs_next - hs_start)
        swing_time = sw_dict.get(to_time, hs_next - to_time)
        frequency_hz = sf_dict.get(to_time, 0)
        stride_length = sl_dict.get(to_time, 0)
        gait_status = gs_dict.get(to_time, "Walk")
        flight_time = ft_dict.get(to_time)
        double_support = dst_dict.get(to_time)
        vgrf_peak_bw = vgrf_dict.get(to_time, 1.0)

        stride_velocity = stride_length / (stride_time / 1000.0) if stride_time > 0 else 0

        strides.append({
            "hs_timestamp_ms": int(hs_start),
            "to_timestamp_ms": int(to_time),
            "stride_time_s": float(stride_time / 1000.0),
            "contact_time_ms": int(round(contact_time)),
            "double_support_time_ms": (
                int(round(max(0, double_support)))
                if double_support is not None
                else None
            ),
            "swing_time_ms": int(round(swing_time)),
            "step_frequency_spm": int(round(frequency_hz * 60.0)),
            "stride_length_m": round(float(stride_length), 2),
            "stride_velocity_mps": round(float(stride_velocity), 2),
            "vGRF_peak_BW": round(float(vgrf_peak_bw), 2),
            "flight_time_ms": (
                int(round(max(0, flight_time)))
                if flight_time is not None
                else None
            ),
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


def build_display_signals(
    idata,
    include_raw_gyro=False,
    max_points=1800,
    event_timestamps=None,
):
    """压缩前端绘图数据，并保留所有步态事件对应的原始采样点。"""
    if idata is None or len(idata) == 0:
        return {}
    if len(idata) > max_points:
        timestamps = idata["Timestamp"].to_numpy(dtype=np.float64)
        if isinstance(event_timestamps, dict):
            timestamp_groups = event_timestamps.values()
        elif event_timestamps is None:
            timestamp_groups = []
        else:
            timestamp_groups = [event_timestamps]

        event_times = []
        for group in timestamp_groups:
            if group is None or isinstance(group, dict):
                continue
            if np.isscalar(group):
                group = [group]
            for value in group:
                try:
                    event_time = float(value)
                except (TypeError, ValueError):
                    continue
                if (
                    np.isfinite(event_time)
                    and timestamps[0] <= event_time <= timestamps[-1]
                ):
                    event_times.append(event_time)

        event_indices = nearest_timestamp_positions(timestamps, event_times)
        required_indices = np.unique(np.concatenate((
            np.asarray([0, len(idata) - 1], dtype=np.int64),
            event_indices,
        )))
        remaining_points = max(0, max_points - required_indices.size)
        if remaining_points > 0:
            uniform_indices = np.linspace(
                0,
                len(idata) - 1,
                remaining_points,
                dtype=np.int64,
            )
            indices = np.unique(np.concatenate((
                required_indices,
                uniform_indices,
            )))
        else:
            indices = required_indices
        display = idata.iloc[indices]
    else:
        display = idata
    signals = {
        "timestamps": display["Timestamp"].tolist(),
        "acc_x": display["ACC.X"].tolist(),
        "acc_y": display["ACC.Y"].tolist(),
        "acc_z": display["ACC.Z"].tolist(),
        "gyro_y": display["Gmax(°/s)"].tolist(),
    }
    if include_raw_gyro:
        signals.update({
            "gyro_x": display["Gyro.X"].tolist(),
            "gyro_y_raw": display["Gyro.Y"].tolist(),
            "gyro_z": display["Gyro.Z"].tolist(),
        })
    return signals


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
    is_triple_jump=False,
    left_device_id='D422CD007E6E',
    right_device_id='D422CD00937F',
):
    try:
        print(f"GAIT_LOG_START: Processing file {file_path}")

        # 容错：Xsens 离线导出文件在表头前可能存在若干元数据行，
        # 找到含 PacketCounter 或 SampleTimeFine 的行作为真正的表头行。
        skip, main_metadata = read_csv_header_metadata(file_path)

        idata = pd.read_csv(file_path, skiprows=skip if skip > 0 else None)
        idata.columns = idata.columns.str.strip()
        num_rows = len(idata)
        print(f"GAIT_LOG_INFO: Total rows read: {num_rows}, header_skip={skip}")

        if 'SampleTimeFine' not in idata.columns:
            print("GAIT_LOG_ERROR: Missing SampleTimeFine column")
            return json.dumps({"ok": False, "error": "CSV 缺少 SampleTimeFine 列"})
        if 'PacketCounter' not in idata.columns:
            print("GAIT_LOG_WARN: Missing PacketCounter column, using row index as fallback")
            idata['PacketCounter'] = np.arange(len(idata), dtype=np.int64)

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
        idata_full = idata.copy()

        # 单脚路径先按用户时间窗裁剪；左右脚配对路径会改用 idata_full 对齐后再裁剪一次。
        idata = apply_time_window(idata, start_time_s, end_time_s)
        if idata.empty:
            return json.dumps({"ok": False, "error": "裁剪后的数据为空，请检查时间范围"})

        basename = os.path.basename(file_path)
        left_device_id = normalize_device_id(
            main_metadata.get('left_device_id') or left_device_id
        )
        right_device_id = normalize_device_id(
            main_metadata.get('right_device_id') or right_device_id
        )
        is_main_left = left_device_id in basename.upper()
        paired_path = find_paired_foot_file(
            file_path,
            left_device_id=left_device_id,
            right_device_id=right_device_id,
        )
        single_leg_idata = idata
        if paired_path is None:
            (
                idata,
                HS,
                TO,
                MS,
                ic_fusion,
                fs_source,
                _,
            ) = prepare_idata_for_analysis(single_leg_idata, "primary")
        else:
            HS = []
            TO = []
            MS = []
            ic_fusion = None
            fs_source = estimate_sample_rate_hz(
                single_leg_idata['Timestamp'].values
            )

        # 解析长跳的起跳发力脚（L/R）
        takeoff_side_req = None
        if isinstance(long_jump_is_takeoff_foot, str) and long_jump_is_takeoff_foot in ['L', 'R']:
            takeoff_side_req = long_jump_is_takeoff_foot

        takeoff_raw = _parse_long_jump_takeoff_step(long_jump_takeoff_step)
        is_long_jump_mode = (takeoff_raw >= 0)
        gait_bouts = []
        gait_quality = None
        gait_recovery = None
        detected_events = None
        detected_contra_events = None
        primary_single_cycles = []
        contra_single_cycles = []

        # 判断是否需要三级跳专属逻辑（包含跳跃清洗伴飞校准）
        is_triple_jump_mode = getattr(is_triple_jump, '__bool__', lambda: True)() or (isinstance(is_triple_jump, str) and is_triple_jump in ['L', 'R'])

        # 默认尝试左右脚配对：同目录存在同时间对侧 CSV 时输出双脚指标；
        # 未找到配对文件时自动降级为单脚分析。
        is_dual_leg_mode = True

        idata_contra = None
        if is_dual_leg_mode:
            print(f"GAIT_LOG_INFO: Paired leg mode enabled (Triple Jump: {is_triple_jump_mode}). Attempting dual leg sync...")

            if paired_path is not None:
                print(f"GAIT_LOG_INFO: Found paired foot file: {paired_path}")
                try:
                    # 读取并提取对侧文件
                    skip_contra, paired_metadata = read_csv_header_metadata(paired_path)

                    idata_c = pd.read_csv(paired_path, skiprows=skip_contra if skip_contra > 0 else None)
                    idata_c.columns = idata_c.columns.str.strip()
                    if 'PacketCounter' not in idata_c.columns:
                        print("GAIT_LOG_WARN: Paired CSV missing PacketCounter column, using row index as fallback")
                        idata_c['PacketCounter'] = np.arange(len(idata_c), dtype=np.int64)

                    rows_main_before = len(idata_full)
                    rows_contra_before = len(idata_c)
                    main_synced_meta = parse_bool_metadata(main_metadata, "recording_is_synced")
                    paired_synced_meta = parse_bool_metadata(paired_metadata, "recording_is_synced")
                    if main_synced_meta is False or paired_synced_meta is False:
                        sample_aligned = None
                        sample_sync_check = {
                            "reason": "metadata_unsynced",
                            "main_synced": main_synced_meta,
                            "paired_synced": paired_synced_meta,
                        }
                    else:
                        sample_aligned, sample_sync_check = try_align_pair_by_sample_time(
                            idata_full,
                            idata_c,
                            time_scale,
                            force_synced=(main_synced_meta is True and paired_synced_meta is True),
                        )
                    if sample_aligned is not None:
                        aligned_main, aligned_contra, pair_sync = sample_aligned
                        print(
                            "GAIT_LOG_INFO: SampleTimeFine synced pair alignment "
                            f"{pair_sync['start']:.0f}->{pair_sync['end']:.0f}, "
                            f"rows main {rows_main_before}->{len(aligned_main)}, "
                            f"paired {rows_contra_before}->{len(aligned_contra)}, "
                            f"start_delta={pair_sync['start_delta']:.0f}, "
                            f"tolerance={pair_sync['start_tolerance']:.0f}, "
                            f"force_synced={pair_sync['force_synced']}"
                        )
                    else:
                        aligned_main, aligned_contra, pair_sync = align_pair_by_packet_counter(idata_full, idata_c, dt_ms)
                        delta = sample_sync_check.get("start_delta")
                        tolerance = sample_sync_check.get("start_tolerance")
                        start_text = (
                            f", sample_start_delta={delta:.0f}, tolerance={tolerance:.0f}"
                            if delta is not None and tolerance is not None
                            else ""
                        )
                        print(
                            "GAIT_LOG_INFO: PacketCounter pair alignment "
                            f"{pair_sync['start']:.0f}->{pair_sync['end']:.0f}, "
                            f"rows main {rows_main_before}->{len(aligned_main)}, "
                            f"paired {rows_contra_before}->{len(aligned_contra)}, "
                            f"sample_time_check={sample_sync_check.get('reason')}{start_text}"
                        )

                    idata_pair = apply_time_window(aligned_main, start_time_s, end_time_s)
                    idata_c_pair = apply_time_window(aligned_contra, start_time_s, end_time_s)
                    if idata_pair.empty or idata_c_pair.empty:
                        raise ValueError("左右脚配对后裁剪的数据为空，请检查时间范围")

                    idata, HS, TO, MS, ic_fusion, fs_source, _ = prepare_idata_for_analysis(idata_pair, "primary paired")
                    idata_c, _, _, _, ic_fusion_c, fs_c, _ = prepare_idata_for_analysis(idata_c_pair, "contra paired")

                    if is_triple_jump_mode:
                        # 三级跳：区分左右脚投喂三级跳清洗库
                        if is_main_left:
                            idata_l, idata_r = idata, idata_c
                        else:
                            idata_l, idata_r = idata_c, idata

                        clean_l, clean_r = cross_leg_hop_correction(idata_l, idata_r)

                        # 替换为主脚结果并刷新事件矩阵
                        idata = clean_l if is_main_left else clean_r
                        idata_contra = clean_r if is_main_left else clean_l
                    else:
                        # 跳远：不执行伴飞校正，直接使用计算的事件
                        idata_contra = idata_c

                    HS = sorted(idata[idata['IC'].notna()]['Timestamp'].values)
                    TO = sorted(idata[idata['TC'].notna()]['Timestamp'].values)
                    MS = sorted(idata[idata['MS'].notna()]['Timestamp'].values) if 'MS' in idata.columns else sorted(idata[idata['MSW'].notna()]['Timestamp'].values)
                    print(f"GAIT_LOG_INFO: Dual leg sync complete. Filtered events - HS: {len(HS)}, TO: {len(TO)}, MS: {len(MS)}")

                    # 伴飞脚解析
                    HS_c = sorted(idata_contra[idata_contra['IC'].notna()]['Timestamp'].values)
                    TO_c = sorted(idata_contra[idata_contra['TC'].notna()]['Timestamp'].values)
                    MS_c = sorted(idata_contra[idata_contra['MS'].notna()]['Timestamp'].values) if 'MS' in idata_contra.columns else sorted(idata_contra[idata_contra['MSW'].notna()]['Timestamp'].values)
                    # 正式分析不自动补回事件。漏检宁可保留为空，也不能根据
                    # 局部周期或模板合成新的 IC、TC、MS，避免把误检写入结果。
                    MSW_c = idata_contra[idata_contra['MSW'].notna()]['Timestamp'].values.tolist()
                    strides_c = calculate_spatio_temporal(
                        HS_c,
                        TO_c,
                        MS_c,
                        idata_contra,
                        is_long_jump=False,
                        contralateral_hs=HS,
                        contralateral_to=TO,
                    )

                    contra_data = {
                        "strides": strides_c,
                        "signals": build_display_signals(idata_contra),
                        "events": {
                            "hs": [int(x) for x in HS_c],
                            "to": [int(x) for x in TO_c],
                            "ms": [int(x) for x in MS_c],
                            "msw": [int(x) for x in MSW_c],
                            "ic_fusion": ic_fusion_c
                        },
                        "side_main": "L" if is_main_left else "R",
                            "side_contra": "R" if is_main_left else "L"
                    }
                    if not is_long_jump_mode:
                        detected_events = {
                            "hs": [int(x) for x in HS],
                            "to": [int(x) for x in TO],
                            "ms": [int(x) for x in MS],
                            "msw": [
                                int(x)
                                for x in idata[
                                    idata["MSW"].notna()
                                ]["Timestamp"].values
                            ],
                        }
                        detected_contra_events = {
                            key: list(value)
                            for key, value in contra_data["events"].items()
                            if key != "ic_fusion"
                        }
                except Exception as e:
                    print(f"GAIT_LOG_ERROR: Dual leg sync failed, falling back to single leg mode. Exception: {e}")
                    (
                        idata,
                        HS,
                        TO,
                        MS,
                        ic_fusion,
                        fs_source,
                        _,
                    ) = prepare_idata_for_analysis(
                        single_leg_idata,
                        "primary fallback",
                    )
                    contra_data = None
            else:
                print("GAIT_LOG_WARN: Could not find paired contralateral file. Running in single leg mode.")
                contra_data = None
        else:
            contra_data = None

        if not is_long_jump_mode and contra_data is None:
            return json.dumps({
                "ok": False,
                "error": "常规步态分析需要同一次采集的左右脚配对数据，当前缺少对侧文件",
            }, ensure_ascii=False)

        if not is_long_jump_mode:
            contra_events = contra_data["events"]
            primary_single_cycles = _complete_single_leg_cycles(
                HS,
                TO,
            )
            contra_single_cycles = _complete_single_leg_cycles(
                contra_events["hs"],
                contra_events["to"],
            )
            gait_bouts, gait_quality = detect_bilateral_gait_bouts(
                idata,
                HS,
                TO,
                idata_contra,
                contra_events["hs"],
                contra_events["to"],
            )
            if (
                not gait_bouts
                and (
                    len(primary_single_cycles)
                    < UNPAIRED_RECORD_MIN_CYCLES_PER_SIDE
                    or len(contra_single_cycles)
                    < UNPAIRED_RECORD_MIN_CYCLES_PER_SIDE
                )
            ):
                return json.dumps({
                    "ok": False,
                    "error": "未检测到完整步态周期，请重新选择有效行走数据",
                    "gait_quality": gait_quality,
                }, ensure_ascii=False)

            HS = filter_events_to_gait_bouts(HS, gait_bouts)
            TO = filter_events_to_gait_bouts(TO, gait_bouts)
            MS = filter_events_to_gait_bouts(MS, gait_bouts)
            MSW = filter_events_to_gait_bouts(
                idata[idata["MSW"].notna()]["Timestamp"].values.tolist(),
                gait_bouts,
            )
            contra_hs = filter_events_to_gait_bouts(
                contra_events["hs"], gait_bouts
            )
            contra_to = filter_events_to_gait_bouts(
                contra_events["to"], gait_bouts
            )
            contra_ms = filter_events_to_gait_bouts(
                contra_events["ms"], gait_bouts
            )
            contra_msw = filter_events_to_gait_bouts(
                contra_events["msw"], gait_bouts
            )

            idata = sync_idata_ic_tc_to_event_lists(idata, HS, TO)
            idata = sync_idata_msw_to_event_list(idata, MSW)
            idata = sync_idata_ms_to_event_list(idata, MS)
            idata_contra = sync_idata_ic_tc_to_event_lists(
                idata_contra, contra_hs, contra_to
            )
            idata_contra = sync_idata_msw_to_event_list(
                idata_contra, contra_msw
            )
            idata_contra = sync_idata_ms_to_event_list(
                idata_contra, contra_ms
            )

            contra_data["events"]["hs"] = contra_hs
            contra_data["events"]["to"] = contra_to
            contra_data["events"]["ms"] = contra_ms
            contra_data["events"]["msw"] = contra_msw
            contra_data["strides"] = calculate_spatio_temporal_by_bouts(
                contra_hs,
                contra_to,
                contra_ms,
                idata_contra,
                gait_bouts,
                contralateral_hs=HS,
                contralateral_to=TO,
            )
            contra_data["signals"] = build_display_signals(idata_contra)
            print(
                "GAIT_LOG_INFO: Event-driven gait filter "
                f"bouts={len(gait_bouts)}, "
                f"valid_duration={gait_quality['valid_duration_s']:.2f}s, "
                f"primary IC={len(HS)}, contralateral IC={len(contra_hs)}"
            )

        # 判断长跳当前处理的文件是否为起跳脚
        is_takeoff_foot = False
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
                    inferred, burst_message = infer_takeoff_step_by_signal_burst(TO, idata)
                    if inferred > 0:
                        takeoff_req = inferred
                        takeoff_auto_message = burst_message
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
                    f"drop post-takeoff IC; HS count {len(HS)} -> {len(HS_for_metrics)}"
                )
            if lj_ms and len(MS_for_metrics) != len(MS):
                print(
                    f"GAIT_LOG_INFO: Long jump MS filter: after TO#{takeoff_req}, "
                    f"drop post-takeoff MS; MS count {len(MS)} -> {len(MS_for_metrics)}"
                )
            if lj_msw and len(MSW_for_metrics) != len(msw_times):
                print(
                    f"GAIT_LOG_INFO: Long jump MSW filter: after TO#{takeoff_req}, "
                    f"drop post-takeoff MSW; MSW count {len(msw_times)} -> {len(MSW_for_metrics)}"
                )
            if takeoff_req > 0 and len(TO) >= takeoff_req:
                cutoff_time = float(TO_for_metrics[-1])
                idata = sync_idata_ic_tc_to_event_lists(idata, HS_for_metrics, TO_for_metrics)
                idata = sync_idata_msw_to_event_list(idata, MSW_for_metrics)
                idata = sync_idata_ms_to_event_list(idata, MS_for_metrics)
                idata = truncate_signal_at_takeoff(idata, cutoff_time)

                # 如果存在伴飞脚，必须同步裁切伴飞脚以防出现起跳后的无用步态事件
                if contra_data is not None and idata_contra is not None:
                    c_hs = [t for t in contra_data['events']['hs'] if t <= cutoff_time]
                    c_ms = [t for t in contra_data['events']['ms'] if t <= cutoff_time]
                    c_to = [t for t in contra_data['events']['to'] if t <= cutoff_time]
                    c_msw = [t for t in contra_data['events']['msw'] if t <= cutoff_time]
                    idata_contra = truncate_signal_at_takeoff(idata_contra, cutoff_time)

                    # 重新生成裁切后的伴飞脚步态参数
                    new_strides_c = calculate_spatio_temporal(
                        c_hs,
                        c_to,
                        c_ms,
                        idata_contra,
                        is_long_jump=False,
                        include_terminal_contact=True,
                        contralateral_hs=HS_for_metrics,
                        contralateral_to=TO_for_metrics,
                    )
                    contra_data['events']['hs'] = c_hs
                    contra_data['events']['to'] = c_to
                    contra_data['events']['ms'] = c_ms
                    contra_data['events']['msw'] = c_msw
                    contra_data['strides'] = new_strides_c
                    contra_data['signals'] = build_display_signals(idata_contra)

        # 获取 MSW 时间戳用于前端可视化
        MSW = idata[idata['MSW'].notna()]['Timestamp'].values.tolist()

        contra_events = contra_data.get('events') if contra_data is not None else None
        if not is_long_jump_mode:
            paired_strides = calculate_spatio_temporal_by_bouts(
                HS_for_metrics,
                TO_for_metrics,
                MS_for_metrics,
                idata,
                gait_bouts,
                contralateral_hs=contra_events.get("hs"),
                contralateral_to=contra_events.get("to"),
            )
            paired_contra_strides = list(contra_data.get("strides", []))
            strides = calculate_single_leg_strides_with_pairing(
                detected_events["hs"],
                detected_events["to"],
                detected_events["ms"],
                idata,
                primary_single_cycles,
                paired_strides,
            )
            contra_data["strides"] = (
                calculate_single_leg_strides_with_pairing(
                    detected_contra_events["hs"],
                    detected_contra_events["to"],
                    detected_contra_events["ms"],
                    idata_contra,
                    contra_single_cycles,
                    paired_contra_strides,
                )
            )
            analysis_events = _single_leg_events_for_cycles(
                primary_single_cycles,
                detected_events["hs"],
                detected_events["to"],
                detected_events["ms"],
                detected_events["msw"],
            )
            analysis_contra_events = _single_leg_events_for_cycles(
                contra_single_cycles,
                detected_contra_events["hs"],
                detected_contra_events["to"],
                detected_contra_events["ms"],
                detected_contra_events["msw"],
            )
            HS_for_metrics = analysis_events["hs"]
            TO_for_metrics = analysis_events["to"]
            MS_for_metrics = analysis_events["ms"]
            MSW = analysis_events["msw"]
            contra_data["events"]["hs"] = analysis_contra_events["hs"]
            contra_data["events"]["to"] = analysis_contra_events["to"]
            contra_data["events"]["ms"] = analysis_contra_events["ms"]
            contra_data["events"]["msw"] = analysis_contra_events["msw"]
        else:
            strides = calculate_spatio_temporal(
                HS_for_metrics,
                TO_for_metrics,
                MS_for_metrics,
                idata,
                is_long_jump=long_jump_applied,
                contralateral_hs=(
                    contra_events.get("hs")
                    if contra_events is not None
                    else None
                ),
                contralateral_to=(
                    contra_events.get("to")
                    if contra_events is not None
                    else None
                ),
            )
        print(f"DEBUG: Strides calculated: {len(strides)}")

        # 结果汇总 - 包含所有前端需要的核心指标
        def safe_mean(key):
            vals = [s[key] for s in strides if s.get(key) is not None]
            return float(np.mean(vals)) if vals else 0.0

        def safe_optional_mean(key):
            vals = [s[key] for s in strides if s.get(key) is not None]
            return float(np.mean(vals)) if vals else None

        flight_time_mean = safe_optional_mean("flight_time_ms")
        double_support_mean = safe_optional_mean(
            "double_support_time_ms"
        )
        vgrf_mean = safe_optional_mean("vGRF_peak_BW")
        summary = {
            "analysis_mode": "long_jump" if is_long_jump_mode else "general_gait",
            "n_strides": len(strides),
            "stride_time_s": safe_mean("stride_time_s"),
            "contact_time_ms": int(round(safe_mean("contact_time_ms"))),
            "double_support_time_ms": (
                None
                if is_long_jump_mode or double_support_mean is None
                else int(round(double_support_mean))
            ),
            "swing_time_ms": int(round(safe_mean("swing_time_ms"))),
            "step_frequency_spm": int(round(safe_mean("step_frequency_spm"))),
            "stride_length_m": round(safe_mean("stride_length_m"), 2),
            "stride_velocity_mps": round(safe_mean("stride_velocity_mps"), 2),
            "vGRF_peak_BW": (
                round(vgrf_mean, 2)
                if vgrf_mean is not None
                else None
            ),
            "flight_time_ms": (
                int(round(flight_time_mean))
                if flight_time_mean is not None
                else None
            ),
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
        if gait_quality is not None:
            summary["gait_bout_count"] = int(gait_quality["valid_bout_count"])
            summary["valid_gait_duration_s"] = round(
                float(gait_quality["valid_duration_s"]), 3
            )

        result_events = {
            "hs":  [int(x) for x in HS_for_metrics],
            "to":  [int(x) for x in TO_for_metrics],
            "ms":  [int(x) for x in MS_for_metrics],
            "msw": [int(x) for x in MSW],
        }
        primary_display_events = (
            detected_events
            if detected_events is not None
            else result_events
        )
        result = {
            "ok": True,
            "analysis_mode": "long_jump" if is_long_jump_mode else "general_gait",
            "summary": summary,
            "strides": strides,
            "signals": build_display_signals(
                idata,
                include_raw_gyro=True,
                event_timestamps=primary_display_events,
            ),
            "events": result_events,
        }
        if gait_quality is not None:
            result["gait_quality"] = gait_quality
        if detected_events is not None:
            result["detected_events"] = detected_events

        if contra_data:
            if detected_contra_events is not None:
                contra_data["detected_events"] = detected_contra_events
            contra_display_events = (
                detected_contra_events
                if detected_contra_events is not None
                else contra_data.get("events")
            )
            contra_data["signals"] = build_display_signals(
                idata_contra,
                event_timestamps=contra_display_events,
            )
            result["contra_data"] = contra_data

        return json.dumps(result)
    except Exception as e:
        import traceback
        print(f"ERROR: {str(e)}\n{traceback.format_exc()}")
        return json.dumps({"ok": False, "error": str(e)})
