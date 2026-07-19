import numpy as np
from scipy.signal import butter, sosfiltfilt


GYRO_LOW_CUTOFF_HZ = 6.0
ACC_LOW_CUTOFF_HZ = 6.0
GAIT_CONFIRM_WINDOW_MS = 120.0
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


def _lobe_impulse(values, timestamps):
    """用波瓣平均幅值和持续时间描述其整体形态强度。"""
    samples = np.asarray(values, dtype=np.float64)
    times = np.asarray(timestamps, dtype=np.float64)
    if samples.size == 0:
        return 0.0
    if times.size > 1:
        sample_interval = float(np.median(np.diff(times)))
    else:
        sample_interval = 1.0
    duration = max(
        float(times[-1] - times[0]) + sample_interval,
        sample_interval,
    )
    return float(np.mean(np.maximum(samples, 0.0)) * duration)


def _candidate_morphology_strength(candidate):
    """合并摆动正波瓣和触地负波瓣，避免只按单点峰值判断。"""
    if "morphology_strength" in candidate:
        return float(candidate["morphology_strength"])
    positive = max(float(candidate["msw_value"]), 0.0)
    negative = max(-float(candidate["negative_value"]), 0.0)
    return float(np.sqrt(positive * negative))


def _cluster_high_mask(values):
    """仅在数据自身形成高低两簇时标记高簇，否则全部保留。"""
    samples = np.asarray(values, dtype=np.float64)
    threshold = _separated_otsu_threshold(samples)
    if threshold is None:
        return np.ones(samples.size, dtype=bool), None
    return samples >= threshold, threshold


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

    positive_lobes = []
    end_cursor = 0
    for start_index in starts:
        while end_cursor < ends.size and ends[end_cursor] < start_index:
            end_cursor += 1
        if end_cursor >= ends.size:
            break
        end_index = int(ends[end_cursor])
        end_cursor += 1
        lobe = gyro[start_index:end_index + 1]
        lobe_times = timestamps[start_index:end_index + 1]
        peak_index = int(start_index + np.argmax(lobe))
        positive_lobes.append({
            "start_index": int(start_index),
            "end_index": end_index,
            "peak_index": peak_index,
            "peak_value": float(gyro[peak_index]),
            "impulse": _lobe_impulse(lobe, lobe_times),
        })

    if not positive_lobes:
        return []

    peak_high, peak_threshold = _cluster_high_mask([
        lobe["peak_value"] for lobe in positive_lobes
    ])
    impulse_high, impulse_threshold = _cluster_high_mask([
        lobe["impulse"] for lobe in positive_lobes
    ])
    if peak_threshold is not None and impulse_threshold is not None:
        structural_lobes = peak_high | impulse_high
    elif peak_threshold is not None:
        structural_lobes = peak_high
    elif impulse_threshold is not None:
        structural_lobes = impulse_high
    else:
        structural_lobes = np.ones(len(positive_lobes), dtype=bool)

    negative = gyro < 0.0
    negative_starts = np.flatnonzero(
        negative & ~np.r_[False, negative[:-1]]
    )
    negative_ends = np.flatnonzero(
        negative & ~np.r_[negative[1:], False]
    )
    negative_lobes = []
    negative_end_cursor = 0
    for start_index in negative_starts:
        while (
            negative_end_cursor < negative_ends.size
            and negative_ends[negative_end_cursor] < start_index
        ):
            negative_end_cursor += 1
        if negative_end_cursor >= negative_ends.size:
            break
        end_index = int(negative_ends[negative_end_cursor])
        negative_end_cursor += 1
        lobe_values = -gyro[start_index:end_index + 1]
        lobe_times = timestamps[start_index:end_index + 1]
        valley_index = int(start_index + np.argmax(lobe_values))
        negative_lobes.append({
            "start_index": int(start_index),
            "end_index": end_index,
            "valley_index": valley_index,
            "depth": float(-gyro[valley_index]),
            "impulse": _lobe_impulse(lobe_values, lobe_times),
        })

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
    for lobe_index, lobe in enumerate(positive_lobes):
        if not structural_lobes[lobe_index]:
            continue

        end_index = lobe["end_index"]
        crossing_index = end_index + 1
        if crossing_index >= gyro.size:
            continue

        peak_index = lobe["peak_index"]
        peak_value = lobe["peak_value"]
        crossing_time = float(timestamps[crossing_index])
        confirm_end = int(np.searchsorted(
            timestamps,
            crossing_time + GAIT_CONFIRM_WINDOW_MS,
            side="right",
        ))
        next_structural_lobe_start = next((
            positive_lobes[next_lobe_index]["start_index"]
            for next_lobe_index in range(
                lobe_index + 1,
                len(positive_lobes),
            )
            if structural_lobes[next_lobe_index]
        ), None)
        if next_structural_lobe_start is not None:
            confirm_end = min(
                confirm_end,
                int(next_structural_lobe_start),
            )
        if confirm_end <= crossing_index:
            continue

        phase_negative_lobes = [
            negative_lobe
            for negative_lobe in negative_lobes
            if (
                negative_lobe["end_index"] >= crossing_index
                and negative_lobe["start_index"] < confirm_end
            )
        ]
        confirmation_lobe = max(
            phase_negative_lobes,
            key=lambda negative_lobe: np.sqrt(
                negative_lobe["depth"]
                * negative_lobe["impulse"]
            ),
            default=None,
        )

        if confirmation_lobe is None:
            continue

        confirmation_index = confirmation_lobe["valley_index"]
        confirmation_segment = gyro[crossing_index:confirm_end]
        negative_value = float(gyro[confirmation_index])
        negative_impulse = float(confirmation_lobe["impulse"])
        morphology_strength = float(np.sqrt(
            max(lobe["impulse"], 0.0)
            * max(negative_impulse, 0.0)
        ))
        if not np.isfinite(morphology_strength):
            continue

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
            "positive_impulse": float(lobe["impulse"]),
            "negative_impulse": negative_impulse,
            "morphology_strength": morphology_strength,
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
        self.recent_morphology_strengths = []
        self.recent_impact_ranges = []
        self.recent_jerk_peaks = []
        self.last_ic_time = None

    @staticmethod
    def _candidate_quality(candidate):
        return (
            _candidate_morphology_strength(candidate),
            float(candidate["impact_range"]),
            float(candidate["jerk_peak"]),
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
        self.last_ic_time = event["ic_time"]
        self.recent_morphology_strengths.append(
            _candidate_morphology_strength(candidate)
        )
        self.recent_morphology_strengths = (
            self.recent_morphology_strengths[
            -GAIT_PROFILE_WINDOW:
            ]
        )
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
        morphology_threshold = _separated_otsu_threshold([
            _candidate_morphology_strength(candidate)
            for candidate in candidates
        ])
        impact_threshold = _separated_otsu_threshold([
            candidate["impact_range"]
            for candidate in candidates
            if candidate["has_acc"]
        ])
        selected = []
        for candidate in candidates:
            morphology_high = (
                morphology_threshold is None
                or _candidate_morphology_strength(candidate)
                >= morphology_threshold
            )
            impact_high = (
                impact_threshold is None
                or not candidate["has_acc"]
                or candidate["impact_range"] >= impact_threshold
            )
            if (
                morphology_threshold is not None
                and impact_threshold is not None
            ):
                keep = morphology_high or impact_high
            else:
                keep = morphology_high and impact_high
            if keep:
                selected.append(candidate)
        for candidate in self._merge_close_candidates(selected):
            self._accept_candidate(candidate)

    def _matches_profile(self, candidate):
        if (
            self.last_ic_time is not None
            and candidate["ic_time"] - self.last_ic_time
            < self.min_same_foot_interval_ms
        ):
            return False

        morphology_threshold = (
            float(np.median(self.recent_morphology_strengths))
            * GAIT_PROFILE_RATIO
            if self.recent_morphology_strengths
            else 0.0
        )
        morphology_ok = (
            _candidate_morphology_strength(candidate)
            >= morphology_threshold
        )
        if not candidate["has_acc"] or not self.recent_impact_ranges:
            return morphology_ok

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
            morphology_ok
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
        morphology = np.asarray(
            [
                _candidate_morphology_strength(candidate)
                for candidate in recent
            ],
            dtype=np.float64,
        )
        if (
            np.std(morphology)
            / max(float(np.mean(morphology)), np.finfo(float).eps)
            > 0.35
        ):
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
        self.events.clear()
        self.recent_morphology_strengths.clear()
        self.recent_impact_ranges.clear()
        self.recent_jerk_peaks.clear()
        self.last_ic_time = None
        if not self.candidates:
            return []

        ordered_candidates = self._merge_close_candidates(
            self.candidates
        )
        startup_count = min(
            GAIT_STARTUP_CANDIDATES,
            len(ordered_candidates),
        )
        self._accept_startup(ordered_candidates[:startup_count])
        rejected = []
        for candidate in ordered_candidates[startup_count:]:
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

            self.recent_morphology_strengths.clear()
            self.recent_impact_ranges.clear()
            self.recent_jerk_peaks.clear()
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
