import sys
import unittest
from pathlib import Path

import numpy as np
import pandas as pd


PYTHON_SOURCE = Path(__file__).resolve().parents[1] / "app" / "src" / "main" / "python"
sys.path.insert(0, str(PYTHON_SOURCE))

from gait_analyzer import (
    BilateralGaitContactStateMachine,
    IncrementalBilateralGaitDetector,
    _find_ic_time,
    _recover_missing_tc_events_by_phase,
    _recovery_cycle_template_similarity,
    calculate_bilateral_flight_time,
    calculate_gait_status,
    detect_bilateral_gait_bouts,
    recover_missing_gait_events_short_delay,
    segment_bilateral_contacts,
)


def make_signal(duration_ms, phase=0.0, noise_seed=None):
    timestamps = np.arange(0, duration_ms + 1, 10, dtype=np.int64)
    if noise_seed is None:
        time_s = timestamps / 1000.0
        signal = (
            260.0 * np.sin(2.0 * np.pi * time_s + phase)
            + 90.0 * np.sin(4.0 * np.pi * time_s + phase)
        )
    else:
        signal = np.random.default_rng(noise_seed).normal(0.0, 220.0, timestamps.size)
    return pd.DataFrame({
        "Timestamp": timestamps,
        "Gmax(°/s)": signal,
    })


def make_events(segment_starts, strides_per_segment):
    primary_hs = []
    contra_hs = []
    for start_ms in segment_starts:
        primary_hs.extend(
            start_ms + stride * 1000 for stride in range(strides_per_segment)
        )
        contra_hs.extend(
            start_ms + 500 + stride * 1000
            for stride in range(strides_per_segment)
        )
    primary_to = [timestamp + 600 for timestamp in primary_hs]
    contra_to = [timestamp + 600 for timestamp in contra_hs]
    return primary_hs, primary_to, contra_hs, contra_to


def replace_with_random_activity(data, start_ms, end_ms, seed):
    result = data.copy()
    mask = (
        (result["Timestamp"] >= start_ms)
        & (result["Timestamp"] <= end_ms)
    )
    result.loc[mask, "Gmax(°/s)"] = np.random.default_rng(seed).normal(
        0.0,
        220.0,
        int(mask.sum()),
    )
    return result


def make_qualified_contacts(events):
    contacts = sorted(
        [(float(timestamp), "primary") for timestamp in events[0]]
        + [(float(timestamp), "contralateral") for timestamp in events[2]]
    )
    side_counts = {"primary": 0, "contralateral": 0}
    qualified = []
    for timestamp, side in contacts:
        side_counts[side] += 1
        cycle_valid = True if side_counts[side] >= 3 else None
        qualified.append((timestamp, side, cycle_valid, 0.95 if cycle_valid else None))
    return qualified


def make_event_aligned_signal(duration_ms=10000):
    timestamps = np.arange(0, duration_ms + 1, 10, dtype=np.int64)
    signal = np.zeros(timestamps.size, dtype=np.float64)
    for ic_time in range(1000, duration_ms, 1000):
        signal += 240.0 * np.exp(
            -0.5 * ((timestamps - (ic_time - 200)) / 55.0) ** 2
        )
        signal -= 190.0 * np.exp(
            -0.5 * ((timestamps - ic_time) / 38.0) ** 2
        )
        signal -= 120.0 * np.exp(
            -0.5 * ((timestamps - (ic_time + 600)) / 45.0) ** 2
        )
    zeros = np.zeros(timestamps.size, dtype=np.float64)
    msw = np.full(timestamps.size, np.nan, dtype=np.float64)
    for ic_time in range(1000, duration_ms, 1000):
        swing_time = ic_time - 200
        swing_index = int(np.argmin(np.abs(timestamps - swing_time)))
        msw[swing_index] = signal[swing_index]
    return pd.DataFrame({
        "Timestamp": timestamps,
        "Gmax(°/s)": signal,
        "Gyro.X": zeros,
        "Gyro.Y": signal,
        "Gyro.Z": zeros,
        "ACC.X": zeros,
        "ACC.Y": zeros,
        "MS": np.nan,
        "MSW": msw,
    })


class GaitQualityTests(unittest.TestCase):
    def test_flight_time_uses_contralateral_ic_after_primary_tc(self):
        flight = calculate_bilateral_flight_time(
            primary_hs=[0, 800, 1600],
            primary_to=[300, 1100],
            contralateral_hs=[500, 1300, 2100],
        )

        self.assertEqual([(300.0, 200.0), (1100.0, 200.0)], flight)

    def test_walking_sequence_does_not_create_flight_time(self):
        flight = calculate_bilateral_flight_time(
            primary_hs=[0, 800, 1600],
            primary_to=[400, 1200],
            contralateral_hs=[200, 1000, 1800],
        )

        self.assertEqual([], flight)

    def test_gait_status_does_not_fallback_to_single_leg_flight_estimate(self):
        gait_status, flight, _ = calculate_gait_status(
            contact_time_info=[(300, 300)],
            swing_time_info=[(300, 500)],
            bilateral_flight_time_info=[],
        )

        self.assertEqual([(300, "Walk")], gait_status)
        self.assertEqual([], flight)

    def test_positive_local_valley_is_not_initial_contact(self):
        ic_time, is_zero_crossing = _find_ic_time(
            np.asarray([420.0, 310.0, 125.0, 260.0, 180.0]),
            np.asarray([0.0, 10.0, 20.0, 30.0, 40.0]),
            gyro_noise_level=200.0,
        )

        self.assertIsNone(ic_time)
        self.assertFalse(is_zero_crossing)

    def test_initial_contact_uses_zero_crossing_before_negative_valley(self):
        ic_time, is_zero_crossing = _find_ic_time(
            np.asarray([420.0, 160.0, -80.0, -310.0, -120.0]),
            np.asarray([0.0, 10.0, 20.0, 30.0, 40.0]),
            gyro_noise_level=200.0,
        )

        self.assertEqual(20.0, ic_time)
        self.assertTrue(is_zero_crossing)

    def test_zero_crossing_without_confirmed_negative_lobe_is_rejected(self):
        ic_time, is_zero_crossing = _find_ic_time(
            np.asarray([180.0, 35.0, -3.0, -5.0, -2.0, 20.0]),
            np.asarray([0.0, 10.0, 20.0, 30.0, 40.0, 50.0]),
            gyro_noise_level=200.0,
        )

        self.assertIsNone(ic_time)
        self.assertFalse(is_zero_crossing)

    def test_later_valid_zero_crossing_is_used_when_first_is_unconfirmed(self):
        ic_time, is_zero_crossing = _find_ic_time(
            np.asarray([
                120.0, 15.0, -3.0, 18.0, 40.0, -40.0, -120.0, -70.0,
            ]),
            np.asarray([0.0, 10.0, 20.0, 30.0, 40.0, 50.0, 60.0, 70.0]),
            gyro_noise_level=200.0,
        )

        self.assertEqual(50.0, ic_time)
        self.assertTrue(is_zero_crossing)

    def test_incremental_contact_chunks_match_offline_segmentation(self):
        events = make_events([1000, 10000], 7)
        contacts = make_qualified_contacts(events)
        offline = segment_bilateral_contacts(contacts)

        state_machine = BilateralGaitContactStateMachine()
        for chunk in np.array_split(np.asarray(contacts, dtype=object), 5):
            for timestamp, side, cycle_valid, correlation in chunk:
                state_machine.push_contact(
                    float(timestamp),
                    str(side),
                    cycle_valid=cycle_valid,
                    cycle_correlation=correlation,
                )
        state_machine.flush()
        incremental = state_machine.drain_completed()

        self.assertEqual(offline, incremental)
        self.assertEqual(
            BilateralGaitContactStateMachine.WAITING,
            state_machine.snapshot()["state"],
        )

    def test_active_realtime_bout_ends_after_contact_timeout(self):
        events = make_events([1000], 7)
        contacts = make_qualified_contacts(events)
        state_machine = BilateralGaitContactStateMachine()
        for timestamp, side, cycle_valid, correlation in contacts:
            state_machine.push_contact(
                timestamp,
                side,
                cycle_valid=cycle_valid,
                cycle_correlation=correlation,
            )

        self.assertEqual(
            BilateralGaitContactStateMachine.ACTIVE,
            state_machine.snapshot()["state"],
        )
        state_machine.advance_time(
            contacts[-1][0] + 1401.0
        )
        completed = state_machine.drain_completed()

        self.assertEqual(BilateralGaitContactStateMachine.WAITING, state_machine.state)
        self.assertEqual(1, len(completed))
        self.assertTrue(completed[0]["reached_active"])
        self.assertEqual("timeout", completed[0]["end_reason"])

    def test_realtime_activation_depends_on_cycles_not_fixed_seconds(self):
        state_machine = BilateralGaitContactStateMachine()
        contacts = [
            (0, "primary", None),
            (250, "contralateral", None),
            (500, "primary", None),
            (750, "contralateral", None),
            (1000, "primary", True),
            (1250, "contralateral", True),
        ]
        for timestamp, side, cycle_valid in contacts:
            state_machine.push_contact(
                timestamp,
                side,
                cycle_valid=cycle_valid,
                cycle_correlation=0.9 if cycle_valid else None,
            )

        self.assertEqual(BilateralGaitContactStateMachine.ACTIVE, state_machine.state)
        self.assertLess(contacts[-1][0] - contacts[0][0], 2000)

    def test_reentry_keeps_recent_bilateral_pair_after_turn_cycle(self):
        state_machine = BilateralGaitContactStateMachine()
        contacts = [
            (61673, "contralateral", None),
            (62298, "primary", True),
            (62864, "contralateral", False),
            (63439, "primary", True),
            (64014, "contralateral", True),
            (64614, "primary", True),
            (65164, "contralateral", True),
        ]
        for timestamp, side, cycle_valid in contacts:
            state_machine.push_contact(
                timestamp,
                side,
                cycle_valid=cycle_valid,
                cycle_correlation=0.95 if cycle_valid else None,
            )

        self.assertEqual(BilateralGaitContactStateMachine.ACTIVE, state_machine.state)
        self.assertEqual(
            [(62298.0, "primary"), (62864.0, "contralateral")],
            state_machine.current_contacts[:2],
        )

    def test_single_side_turn_shape_change_does_not_end_active_gait(self):
        state_machine = BilateralGaitContactStateMachine()
        contacts = [
            (0, "primary", None, None),
            (500, "contralateral", None, None),
            (1000, "primary", True, 0.97),
            (1500, "contralateral", True, 0.97),
            (2000, "primary", True, 0.97),
            (2500, "contralateral", True, 0.97),
            (3000, "primary", True, 0.96),
            (3500, "contralateral", None, 0.62),
            (4000, "primary", True, 0.95),
            (4500, "contralateral", None, 0.58),
            (5000, "primary", True, 0.97),
            (5500, "contralateral", True, 0.96),
        ]
        for timestamp, side, cycle_valid, similarity in contacts:
            state_machine.push_contact(
                timestamp,
                side,
                cycle_valid=cycle_valid,
                cycle_correlation=similarity,
            )

        self.assertEqual(BilateralGaitContactStateMachine.ACTIVE, state_machine.state)
        self.assertEqual(len(contacts), len(state_machine.current_contacts))
        self.assertEqual([], state_machine.drain_completed())

    def test_short_delay_recovers_missing_ic_and_adjacent_tc(self):
        data = make_event_aligned_signal()
        hs = [1000, 2000, 3000, 4000, 6000, 7000, 8000, 9000]
        to = [1600, 2600, 3600, 4600, 6600, 7600, 8600, 9600]
        opposite_hs = [
            500, 1500, 2500, 3500, 4500,
            5500, 6500, 7500, 8500, 9500,
        ]

        recovered_data, recovered_hs, recovered_to, recovered_ms, diagnostics = (
            recover_missing_gait_events_short_delay(
                data,
                hs,
                to,
                opposite_hs,
            )
        )

        self.assertIn(5000.0, recovered_hs)
        self.assertIn(5600.0, recovered_to)
        self.assertEqual([5000], diagnostics["recovered_ic"])
        self.assertEqual([4800], diagnostics["recovered_msw"])
        self.assertEqual([5600], diagnostics["recovered_tc"])
        self.assertTrue(recovered_data["IC"].notna().any())
        self.assertTrue(recovered_data["TC"].notna().any())
        self.assertGreater(len(recovered_ms), 0)

    def test_phase_order_selects_main_tc_before_midswing(self):
        data = make_event_aligned_signal()
        timestamps = data["Timestamp"].to_numpy(dtype=np.float64)
        tc_window = (timestamps >= 5350) & (timestamps <= 5850)
        data.loc[tc_window, "Gmax(°/s)"] = 0.0
        data["Gmax(°/s)"] += (
            -13.5
            * np.exp(-0.5 * ((timestamps - 5600.0) / 25.0) ** 2)
            - 105.0
            * np.exp(-0.5 * ((timestamps - 5750.0) / 35.0) ** 2)
        )
        data["Gyro.Y"] = data["Gmax(°/s)"]
        hs = [1000, 2000, 3000, 4000, 6000, 7000, 8000, 9000]
        to = [1600, 2600, 3600, 4600, 6600, 7600, 8600, 9600]
        opposite_hs = [
            500, 1500, 2500, 3500, 4500,
            5500, 6500, 7500, 8500, 9500,
        ]

        _, recovered_hs, recovered_to, _, diagnostics = (
            recover_missing_gait_events_short_delay(
                data,
                hs,
                to,
                opposite_hs,
            )
        )

        self.assertIn(5000.0, recovered_hs)
        self.assertNotIn(5600.0, recovered_to)
        self.assertIn(5750.0, recovered_to)
        self.assertEqual([5750], diagnostics["recovered_tc"])

    def test_phase_based_tc_selection_is_scale_invariant(self):
        data = make_event_aligned_signal()
        timestamps = data["Timestamp"].to_numpy(dtype=np.float64)
        tc_window = (timestamps >= 5350) & (timestamps <= 5850)
        data.loc[tc_window, "Gmax(°/s)"] = 0.0
        data["Gmax(°/s)"] += (
            -13.5
            * np.exp(-0.5 * ((timestamps - 5600.0) / 25.0) ** 2)
            - 105.0
            * np.exp(-0.5 * ((timestamps - 5750.0) / 35.0) ** 2)
        )
        data["Gmax(°/s)"] *= 0.05
        data["Gyro.Y"] = data["Gmax(°/s)"]
        hs = [1000, 2000, 3000, 4000, 6000, 7000, 8000, 9000]
        to = [1600, 2600, 3600, 4600, 6600, 7600, 8600, 9600]

        recovered_to, recovered = _recover_missing_tc_events_by_phase(
            data,
            sorted(hs + [5000]),
            to,
            {5000},
            {},
        )

        self.assertNotIn(5600.0, recovered_to)
        self.assertIn(5750.0, recovered_to)
        self.assertEqual([5750.0], recovered)

    def test_phase_based_tc_selection_does_not_read_future_cycles(self):
        data = make_event_aligned_signal()
        timestamps = data["Timestamp"].to_numpy(dtype=np.float64)
        tc_window = (timestamps >= 5350) & (timestamps <= 5850)
        data.loc[tc_window, "Gmax(°/s)"] = 0.0
        data["Gmax(°/s)"] += (
            -13.5
            * np.exp(-0.5 * ((timestamps - 5600.0) / 25.0) ** 2)
            - 105.0
            * np.exp(-0.5 * ((timestamps - 5750.0) / 35.0) ** 2)
        )
        data["Gyro.Y"] = data["Gmax(°/s)"]
        future_changed = data.copy()
        future_mask = timestamps > 6000.0
        future_changed.loc[future_mask, "Gmax(°/s)"] *= 25.0
        future_changed.loc[future_mask, "Gyro.Y"] = future_changed.loc[
            future_mask,
            "Gmax(°/s)",
        ]
        hs = [1000, 2000, 3000, 4000, 5000, 6000, 7000, 8000, 9000]
        to = [1600, 2600, 3600, 4600, 6600, 7600, 8600, 9600]

        original_to, original_recovered = (
            _recover_missing_tc_events_by_phase(
                data,
                hs,
                to,
                {5000},
                {},
            )
        )
        changed_to, changed_recovered = (
            _recover_missing_tc_events_by_phase(
                future_changed,
                hs,
                to,
                {5000},
                {},
            )
        )

        self.assertEqual(original_recovered, changed_recovered)
        self.assertEqual([5750.0], original_recovered)
        self.assertIn(5750.0, original_to)
        self.assertIn(5750.0, changed_to)

    def test_msw_cycle_template_rejects_nonperiodic_candidate(self):
        periodic = make_event_aligned_signal()
        distorted = periodic.copy()
        timestamps = distorted["Timestamp"].to_numpy(dtype=np.float64)
        mask = (timestamps >= 3800.0) & (timestamps <= 4800.0)
        phase = (timestamps[mask] - 3800.0) / 1000.0
        distorted.loc[mask, "Gmax(°/s)"] = (
            80.0 * np.sin(6.0 * np.pi * phase)
        )

        periodic_similarity = _recovery_cycle_template_similarity(
            periodic,
            3800.0,
            4800.0,
        )
        distorted_similarity = _recovery_cycle_template_similarity(
            distorted,
            3800.0,
            4800.0,
        )

        self.assertGreater(periodic_similarity, 0.95)
        self.assertLess(distorted_similarity, 0.75)

    def test_incremental_samples_and_events_activate_after_two_cycles_per_side(self):
        primary = make_signal(5000)
        contralateral = make_signal(5000, phase=np.pi)
        events = make_events([0], 5)
        event_map = {}
        for timestamp in events[0]:
            event_map.setdefault(timestamp, []).append(("primary", "IC"))
        for timestamp in events[1]:
            event_map.setdefault(timestamp, []).append(("primary", "TC"))
        for timestamp in events[2]:
            event_map.setdefault(timestamp, []).append(("contralateral", "IC"))
        for timestamp in events[3]:
            event_map.setdefault(timestamp, []).append(("contralateral", "TC"))

        detector = IncrementalBilateralGaitDetector()
        active_at = None
        for row_index in range(len(primary)):
            timestamp = int(primary.iloc[row_index]["Timestamp"])
            detector.push_sample(
                timestamp,
                "primary",
                primary.iloc[row_index]["Gmax(°/s)"],
            )
            detector.push_sample(
                timestamp,
                "contralateral",
                contralateral.iloc[row_index]["Gmax(°/s)"],
            )
            for side, event_type in event_map.get(timestamp, []):
                state = detector.push_event(timestamp, side, event_type)
                if state == BilateralGaitContactStateMachine.ACTIVE:
                    active_at = timestamp
                    break
            if active_at is not None:
                break

        self.assertIsNotNone(active_at)
        self.assertLessEqual(active_at, 2500)

    def test_simultaneous_bilateral_contacts_break_sequence_without_crashing(self):
        state_machine = BilateralGaitContactStateMachine()
        state_machine.push_contact(1000, "primary")
        state_machine.push_contact(1500, "contralateral")
        state_machine.push_contact(1500, "primary")
        state_machine.flush()

        completed = state_machine.drain_completed()
        self.assertEqual(2, len(completed))
        self.assertEqual("sequence_break", completed[0]["end_reason"])

    def test_periodic_bilateral_gait_is_detected(self):
        events = make_events([1000], 10)
        bouts, diagnostics = detect_bilateral_gait_bouts(
            make_signal(12000),
            events[0],
            events[1],
            make_signal(12000, phase=np.pi),
            events[2],
            events[3],
        )

        self.assertEqual(1, len(bouts))
        self.assertEqual(1, diagnostics["valid_bout_count"])
        self.assertGreater(bouts[0]["median_cycle_correlation"], 0.9)

    def test_turn_gap_splits_stable_gait_bouts(self):
        events = make_events([1000, 10000], 7)
        bouts, diagnostics = detect_bilateral_gait_bouts(
            make_signal(18000),
            events[0],
            events[1],
            make_signal(18000, phase=np.pi),
            events[2],
            events[3],
        )

        self.assertEqual(2, len(bouts))
        self.assertEqual(2, diagnostics["valid_bout_count"])
        self.assertLess(bouts[0]["end_ms"], bouts[1]["start_ms"])

    def test_initial_activity_is_trimmed_without_losing_later_gait(self):
        events = make_events([1000], 20)
        bouts, diagnostics = detect_bilateral_gait_bouts(
            replace_with_random_activity(make_signal(22000), 0, 6000, 10),
            events[0],
            events[1],
            replace_with_random_activity(
                make_signal(22000, phase=np.pi),
                0,
                6000,
                20,
            ),
            events[2],
            events[3],
        )

        self.assertEqual(1, diagnostics["valid_bout_count"])
        self.assertGreater(bouts[0]["start_ms"], 6000)
        self.assertGreater(bouts[0]["end_ms"], 18000)

    def test_nonperiodic_turn_signal_splits_regular_contact_sequence(self):
        events = make_events([1000], 20)
        bouts, diagnostics = detect_bilateral_gait_bouts(
            replace_with_random_activity(make_signal(22000), 8000, 12000, 10),
            events[0],
            events[1],
            replace_with_random_activity(
                make_signal(22000, phase=np.pi),
                8000,
                12000,
                20,
            ),
            events[2],
            events[3],
        )

        self.assertEqual(2, diagnostics["valid_bout_count"])
        self.assertLessEqual(bouts[0]["end_ms"], 9000)
        self.assertGreaterEqual(bouts[1]["start_ms"], 12000)

    def test_regular_timestamps_with_random_activity_are_rejected(self):
        events = make_events([1000], 10)
        bouts, diagnostics = detect_bilateral_gait_bouts(
            make_signal(12000, noise_seed=10),
            events[0],
            events[1],
            make_signal(12000, noise_seed=20),
            events[2],
            events[3],
        )

        self.assertEqual([], bouts)
        self.assertEqual(0, diagnostics["valid_bout_count"])


if __name__ == "__main__":
    unittest.main()
