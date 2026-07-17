import numpy as np
from scipy.signal import butter, sosfiltfilt


GYRO_LOW_CUTOFF_HZ = 6.0
ACC_LOW_CUTOFF_HZ = 6.0
GAIT_CONFIRM_WINDOW_MS = 120.0
GAIT_REBOUND_MIN_DELAY_MS = 25.0
GAIT_MIN_SAME_FOOT_INTERVAL_MS = 250.0
GAIT_PROFILE_WINDOW = 5
GAIT_STARTUP_CANDIDATES = 4
GAIT_PROFILE_RATIO = 0.20
GAIT_CHANGE_POINT_CANDIDATES = 4


def zero_phase_lowpass(values, cutoff_hz, fs):
    """离线四阶 Butterworth 零相位低通。"""
    samples = np.asarray(values, dtype=np.float64)
    if samples.size == 0:
        return samples.copy()
    sos = butter(4, cutoff_hz, "lp", fs=fs, output="sos")
    return sosfiltfilt(sos, samples)


def _separated_otsu_threshold(values):
    """仅在样本形成两个明显幅值簇时返回 Otsu 分界。"""
    samples = np.asarray(values, dtype=np.float64)
    samples = samples[np.isfinite(samples)]
    if samples.size < 2 or np.allclose(samples, samples[0]):
        return None

    ordered = np.sort(samples)
    best_score = -1.0
    best_threshold = None
    for split_index in range(1, ordered.size):
        low = ordered[:split_index]
        high = ordered[split_index:]
        score = (
            float(low.size)
            * float(high.size)
            * (float(np.mean(high)) - float(np.mean(low))) ** 2
        )
        if score > best_score:
            best_score = score
            best_threshold = (
                ordered[split_index - 1] + ordered[split_index]
            ) / 2.0

    if best_threshold is None:
        return None
    low = samples[samples < best_threshold]
    high = samples[samples >= best_threshold]
    if low.size == 0 or high.size == 0:
        return None
    low_level = max(float(np.median(low)), 1e-6)
    high_level = float(np.median(high))
    if high_level / low_level < 2.0:
        return None
    return float(best_threshold)


def _extract_candidates(
    timestamps,
    filtered_gyro,
    filtered_acc=None,
):
    """批量提取满足 MSW -> 零交叉 -> 负向确认顺序的候选周期。"""
    timestamps = np.asarray(timestamps, dtype=np.float64)
    gyro = np.asarray(filtered_gyro, dtype=np.float64)
    if timestamps.size != gyro.size:
        raise ValueError("角速度与时间戳长度不一致")
    if gyro.size < 3:
        return []

    positive = gyro > 0.0
    starts = np.flatnonzero(positive & ~np.r_[False, positive[:-1]])
    ends = np.flatnonzero(positive & ~np.r_[positive[1:], False])
    if starts.size == 0 or ends.size == 0:
        return []

    acc_norm = None
    jerk = None
    if filtered_acc is not None:
        acceleration = np.asarray(filtered_acc, dtype=np.float64)
        if acceleration.shape != (timestamps.size, 3):
            raise ValueError("加速度数组尺寸不一致")
        acc_norm = np.linalg.norm(acceleration, axis=1)
        dt_s = np.diff(timestamps, prepend=timestamps[0]) / 1000.0
        positive_dt = dt_s[dt_s > 0.0]
        fallback_dt = (
            float(np.median(positive_dt))
            if positive_dt.size
            else 1.0
        )
        dt_s[dt_s <= 0.0] = fallback_dt
        jerk = np.abs(
            np.diff(acc_norm, prepend=acc_norm[0]) / dt_s
        )

    candidates = []
    end_cursor = 0
    for start_index in starts:
        while end_cursor < ends.size and ends[end_cursor] < start_index:
            end_cursor += 1
        if end_cursor >= ends.size:
            break
        end_index = int(ends[end_cursor])
        end_cursor += 1
        crossing_index = end_index + 1
        if crossing_index >= gyro.size:
            continue

        lobe = gyro[start_index:end_index + 1]
        peak_index = int(start_index + np.argmax(lobe))
        peak_value = float(gyro[peak_index])
        if peak_value < 5.0:
            continue

        crossing_time = float(timestamps[crossing_index])
        confirm_end = int(np.searchsorted(
            timestamps,
            crossing_time + GAIT_CONFIRM_WINDOW_MS,
            side="right",
        ))
        if confirm_end <= crossing_index:
            continue

        negative_threshold = max(5.0, peak_value * 0.05)
        confirmation_offsets = np.flatnonzero(
            gyro[crossing_index:confirm_end] <= -negative_threshold
        )
        if confirmation_offsets.size == 0:
            continue
        confirmation_index = int(
            crossing_index + confirmation_offsets[0]
        )

        rebound_start = int(np.searchsorted(
            timestamps,
            crossing_time + GAIT_REBOUND_MIN_DELAY_MS,
            side="left",
        ))
        rebound_limit = max(10.0, peak_value * 0.25)
        if (
            rebound_start < confirmation_index
            and np.any(
                gyro[rebound_start:confirmation_index] >= rebound_limit
            )
        ):
            continue

        negative_value = float(
            np.min(gyro[crossing_index:confirm_end])
        )
        has_acc = acc_norm is not None
        if has_acc:
            impact_segment = acc_norm[crossing_index:confirm_end]
            impact_range = float(np.ptp(impact_segment))
            jerk_peak = float(np.max(jerk[crossing_index:confirm_end]))
        else:
            impact_range = 0.0
            jerk_peak = 0.0

        candidates.append({
            "msw_time": float(timestamps[peak_index]),
            "msw_value": peak_value,
            "ic_time": crossing_time,
            "negative_value": negative_value,
            "impact_range": impact_range,
            "jerk_peak": jerk_peak,
            "has_acc": has_acc,
        })
    return candidates


class OfflineGaitEventDetector:
    """对完整离线数组执行候选级步态状态验证。"""

    def __init__(
        self,
        min_same_foot_interval_ms=GAIT_MIN_SAME_FOOT_INTERVAL_MS,
    ):
        self.min_same_foot_interval_ms = float(
            min_same_foot_interval_ms
        )
        self.candidates = []
        self.events = []
        self.recent_peak_values = []
        self.recent_impact_ranges = []
        self.recent_jerk_peaks = []
        self.recent_stride_ms = []
        self.last_ic_time = None

    @staticmethod
    def _candidate_quality(candidate):
        return (
            float(candidate["msw_value"])
            + 20.0 * float(candidate["impact_range"])
            + 0.25 * float(candidate["jerk_peak"])
        )

    def _adaptive_min_interval(self):
        if not self.recent_stride_ms:
            return self.min_same_foot_interval_ms
        return max(
            self.min_same_foot_interval_ms,
            float(np.median(self.recent_stride_ms)) * 0.35,
        )

    def _merge_close_candidates(self, candidates):
        merged = []
        for candidate in sorted(candidates, key=lambda item: item["ic_time"]):
            if (
                not merged
                or candidate["ic_time"] - merged[-1]["ic_time"]
                >= self.min_same_foot_interval_ms
            ):
                merged.append(candidate)
            elif (
                self._candidate_quality(candidate)
                > self._candidate_quality(merged[-1])
            ):
                merged[-1] = candidate
        return merged

    def _accept_candidate(self, candidate):
        event = {
            key: float(candidate[key])
            for key in (
                "msw_time",
                "msw_value",
                "ic_time",
                "negative_value",
                "impact_range",
                "jerk_peak",
            )
        }
        if self.last_ic_time is not None:
            stride_ms = event["ic_time"] - self.last_ic_time
            if stride_ms > 0.0:
                self.recent_stride_ms.append(stride_ms)
                self.recent_stride_ms = self.recent_stride_ms[
                    -GAIT_PROFILE_WINDOW:
                ]
        self.last_ic_time = event["ic_time"]
        self.recent_peak_values.append(event["msw_value"])
        self.recent_peak_values = self.recent_peak_values[
            -GAIT_PROFILE_WINDOW:
        ]
        if candidate["has_acc"]:
            self.recent_impact_ranges.append(event["impact_range"])
            self.recent_impact_ranges = self.recent_impact_ranges[
                -GAIT_PROFILE_WINDOW:
            ]
            self.recent_jerk_peaks.append(event["jerk_peak"])
            self.recent_jerk_peaks = self.recent_jerk_peaks[
                -GAIT_PROFILE_WINDOW:
            ]
        self.events.append(event)

    def _accept_startup(self, candidates):
        peak_threshold = _separated_otsu_threshold([
            candidate["msw_value"] for candidate in candidates
        ])
        impact_threshold = _separated_otsu_threshold([
            candidate["impact_range"]
            for candidate in candidates
            if candidate["has_acc"]
        ])
        selected = []
        for candidate in candidates:
            peak_high = (
                peak_threshold is None
                or candidate["msw_value"] >= peak_threshold
            )
            impact_high = (
                impact_threshold is None
                or not candidate["has_acc"]
                or candidate["impact_range"] >= impact_threshold
            )
            if peak_threshold is not None and impact_threshold is not None:
                keep = peak_high or impact_high
            else:
                keep = peak_high and impact_high
            if keep:
                selected.append(candidate)
        for candidate in self._merge_close_candidates(selected):
            self._accept_candidate(candidate)

    def _matches_profile(self, candidate):
        if (
            self.last_ic_time is not None
            and candidate["ic_time"] - self.last_ic_time
            < self._adaptive_min_interval()
        ):
            return False

        peak_threshold = (
            float(np.median(self.recent_peak_values))
            * GAIT_PROFILE_RATIO
            if self.recent_peak_values
            else 0.0
        )
        peak_ok = candidate["msw_value"] >= peak_threshold
        if not candidate["has_acc"] or not self.recent_impact_ranges:
            return peak_ok

        impact_threshold = (
            float(np.median(self.recent_impact_ranges))
            * GAIT_PROFILE_RATIO
        )
        jerk_threshold = (
            float(np.median(self.recent_jerk_peaks))
            * GAIT_PROFILE_RATIO
            if self.recent_jerk_peaks
            else 0.0
        )
        return (
            peak_ok
            or candidate["impact_range"] >= impact_threshold
            or candidate["jerk_peak"] >= jerk_threshold
        )

    def _is_regular_change_point(self, candidates):
        if len(candidates) < GAIT_CHANGE_POINT_CANDIDATES:
            return False
        recent = candidates[-GAIT_CHANGE_POINT_CANDIDATES:]
        times = np.asarray(
            [candidate["ic_time"] for candidate in recent],
            dtype=np.float64,
        )
        intervals = np.diff(times)
        if (
            intervals.size == 0
            or np.min(intervals) < self.min_same_foot_interval_ms
            or np.std(intervals) / max(float(np.mean(intervals)), 1.0)
            > 0.20
        ):
            return False
        peaks = np.asarray(
            [candidate["msw_value"] for candidate in recent],
            dtype=np.float64,
        )
        if np.std(peaks) / max(float(np.mean(peaks)), 1.0) > 0.35:
            return False
        impacts = np.asarray([
            candidate["impact_range"]
            for candidate in recent
            if candidate["has_acc"]
        ])
        return not (
            impacts.size >= 2
            and np.std(impacts)
            / max(float(np.mean(impacts)), 1e-3) > 0.45
        )

    def validate(self, candidates):
        self.candidates = list(candidates)
        if not self.candidates:
            return []

        startup_count = min(
            GAIT_STARTUP_CANDIDATES,
            len(self.candidates),
        )
        self._accept_startup(self.candidates[:startup_count])
        rejected = []
        for candidate in self.candidates[startup_count:]:
            if self._matches_profile(candidate):
                rejected.clear()
                self._accept_candidate(candidate)
                continue

            if (
                self.last_ic_time is None
                or candidate["ic_time"] > self.last_ic_time
            ):
                rejected.append(candidate)
                rejected = rejected[-GAIT_CHANGE_POINT_CANDIDATES:]
            if not self._is_regular_change_point(rejected):
                continue

            self.recent_peak_values.clear()
            self.recent_impact_ranges.clear()
            self.recent_jerk_peaks.clear()
            self.recent_stride_ms.clear()
            for changed_candidate in rejected:
                if (
                    self.last_ic_time is None
                    or changed_candidate["ic_time"] - self.last_ic_time
                    >= self.min_same_foot_interval_ms
                ):
                    self._accept_candidate(changed_candidate)
            rejected.clear()
        return self.events

    def detect(
        self,
        timestamps,
        filtered_gyro,
        filtered_acc=None,
    ):
        candidates = _extract_candidates(
            timestamps,
            filtered_gyro,
            filtered_acc,
        )
        return self.validate(candidates)


class OfflineGaitEventPipeline:
    """完整文件的零相位滤波与批量事件检测。"""

    def __init__(
        self,
        fs,
        min_same_foot_interval_ms=GAIT_MIN_SAME_FOOT_INTERVAL_MS,
    ):
        self.fs = float(fs)
        self.detector = OfflineGaitEventDetector(
            min_same_foot_interval_ms
        )
        self.filtered_gyro = None
        self.filtered_acc = None

    def process(
        self,
        timestamps,
        gyro_y,
        acc_x=None,
        acc_y=None,
        acc_z=None,
    ):
        self.filtered_gyro = zero_phase_lowpass(
            gyro_y,
            GYRO_LOW_CUTOFF_HZ,
            self.fs,
        )
        acceleration_inputs = (acc_x, acc_y, acc_z)
        if all(values is not None for values in acceleration_inputs):
            self.filtered_acc = np.column_stack([
                zero_phase_lowpass(
                    values,
                    ACC_LOW_CUTOFF_HZ,
                    self.fs,
                )
                for values in acceleration_inputs
            ])
        else:
            self.filtered_acc = None
        events = self.detector.detect(
            timestamps,
            self.filtered_gyro,
            self.filtered_acc,
        )
        return self.filtered_gyro, events


def detect_offline_gait_events(
    filtered_gyro,
    timestamps,
    acc_x=None,
    acc_y=None,
    acc_z=None,
    min_same_foot_interval_ms=GAIT_MIN_SAME_FOOT_INTERVAL_MS,
):
    filtered_acc = None
    if all(values is not None for values in (acc_x, acc_y, acc_z)):
        filtered_acc = np.column_stack([acc_x, acc_y, acc_z])
    detector = OfflineGaitEventDetector(min_same_foot_interval_ms)
    return detector.detect(timestamps, filtered_gyro, filtered_acc)
