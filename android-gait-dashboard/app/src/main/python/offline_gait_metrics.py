import numpy as np


def _exact_timestamp_indices(timestamps_ms, event_times_ms):
    timestamps = np.asarray(timestamps_ms, dtype=np.float64)
    events = np.asarray(event_times_ms, dtype=np.float64)
    if timestamps.size == 0 or events.size == 0:
        return np.empty(0, dtype=np.int64), np.empty(0, dtype=np.float64)

    positions = np.searchsorted(timestamps, events, side="left")
    valid = positions < timestamps.size
    valid_positions = positions[valid]
    valid_events = events[valid]
    exact = timestamps[valid_positions] == valid_events
    return valid_positions[exact], valid_events[exact]


def _support_intervals(hs_events, to_events):
    hs = np.sort(np.asarray(hs_events, dtype=np.float64))
    toe_off = np.sort(np.asarray(to_events, dtype=np.float64))
    if hs.size == 0 or toe_off.size == 0:
        return np.empty((0, 2), dtype=np.float64)

    toe_positions = np.searchsorted(toe_off, hs, side="right")
    valid = toe_positions < toe_off.size
    ends = np.empty(hs.size, dtype=np.float64)
    ends.fill(np.nan)
    ends[valid] = toe_off[toe_positions[valid]]

    if hs.size > 1:
        valid[:-1] &= ends[:-1] < hs[1:]
    valid &= np.isfinite(ends)
    return np.column_stack((hs[valid], ends[valid]))


def calculate_bilateral_double_support(
    primary_hs,
    primary_to,
    contralateral_hs,
    contralateral_to,
):
    """Return overlap duration for each primary support interval in linear time."""
    primary = _support_intervals(primary_hs, primary_to)
    contralateral = _support_intervals(contralateral_hs, contralateral_to)
    if primary.size == 0:
        return []
    if contralateral.size == 0:
        return [(float(end), 0.0) for _, end in primary]

    result = []
    contra_index = 0
    contra_count = len(contralateral)
    for primary_start, primary_end in primary:
        while (
            contra_index < contra_count
            and contralateral[contra_index, 1] <= primary_start
        ):
            contra_index += 1

        overlap_ms = 0.0
        current = contra_index
        while (
            current < contra_count
            and contralateral[current, 0] < primary_end
        ):
            contra_start, contra_end = contralateral[current]
            overlap_ms += max(
                0.0,
                min(primary_end, contra_end)
                - max(primary_start, contra_start),
            )
            current += 1
        result.append((float(primary_end), float(overlap_ms)))
    return result


def _trapezoid_increments(values, timestamps_sec):
    values = np.asarray(values, dtype=np.float64)
    timestamps = np.asarray(timestamps_sec, dtype=np.float64)
    if values.shape[0] < 2:
        return np.empty((0,) + values.shape[1:], dtype=np.float64)
    dt_shape = (timestamps.size - 1,) + (1,) * (values.ndim - 1)
    dt = np.diff(timestamps).reshape(dt_shape)
    return 0.5 * (values[1:] + values[:-1]) * dt


def calculate_stride_length(
    idata,
    hs_timestamps,
    to_timestamps,
    ms_timestamps,
    gravity,
):
    """Vectorized ZUPT stride length with the original MS-to-MS semantics."""
    if idata is None or idata.empty or len(hs_timestamps) < 2:
        return []

    timestamps_ms = idata["Timestamp"].to_numpy(dtype=np.float64)
    ms_indices, _ = _exact_timestamp_indices(timestamps_ms, ms_timestamps)
    if ms_indices.size < 1:
        return []
    ms_indices = np.unique(ms_indices)

    timestamps_sec = timestamps_ms / 1000.0
    acceleration = (
        idata[["ACC.X", "ACC.Y", "ACC.Z"]].to_numpy(dtype=np.float64)
        * float(gravity)
    )
    velocity = np.zeros_like(acceleration)
    acceleration_increments = _trapezoid_increments(
        acceleration,
        timestamps_sec,
    )

    for start_index, end_index in zip(ms_indices, ms_indices[1:]):
        if end_index <= start_index:
            continue
        raw_velocity = np.vstack((
            np.zeros((1, 3), dtype=np.float64),
            np.cumsum(
                acceleration_increments[start_index:end_index],
                axis=0,
            ),
        ))
        weights = np.linspace(
            0.0,
            1.0,
            raw_velocity.shape[0],
            dtype=np.float64,
        )[:, None]
        velocity[start_index:end_index + 1] = (
            raw_velocity - weights * raw_velocity[-1]
        )

    first_ms_index = int(ms_indices[0])
    if first_ms_index > 0:
        velocity[:first_ms_index] = -np.cumsum(
            acceleration_increments[:first_ms_index][::-1],
            axis=0,
        )[::-1]

    last_ms_index = int(ms_indices[-1])
    if last_ms_index < len(timestamps_ms) - 1:
        velocity[last_ms_index + 1:] = np.cumsum(
            acceleration_increments[last_ms_index:],
            axis=0,
        )

    displacement_increments = _trapezoid_increments(
        velocity,
        timestamps_sec,
    )
    displacement = np.vstack((
        np.zeros((1, 3), dtype=np.float64),
        np.cumsum(displacement_increments, axis=0),
    ))

    hs_indices, exact_hs = _exact_timestamp_indices(
        timestamps_ms,
        np.sort(np.asarray(hs_timestamps, dtype=np.float64)),
    )
    if hs_indices.size < 2:
        return []

    toe_off = np.sort(np.asarray(to_timestamps, dtype=np.float64))
    result = []
    for start_index, end_index, start_ms, end_ms in zip(
        hs_indices,
        hs_indices[1:],
        exact_hs,
        exact_hs[1:],
    ):
        if start_index >= end_index:
            continue
        toe_position = int(np.searchsorted(toe_off, start_ms, side="right"))
        if (
            toe_position >= toe_off.size
            or toe_off[toe_position] >= end_ms
        ):
            continue
        delta = displacement[end_index] - displacement[start_index]
        result.append((
            float(toe_off[toe_position]),
            float(np.linalg.norm(delta)),
        ))
    return result
