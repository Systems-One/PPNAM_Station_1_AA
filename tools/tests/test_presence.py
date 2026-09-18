"""Station-node presence self-heal for the simulator.

Mirrors the app's PresenceSelfHeal: a simulator restart faster than the broker's
dead-connection detection lets the PREVIOUS connection's Last Will (retained
`offline`) land after the new connection's retained `online`, leaving the station
stuck `offline` while the simulator is running. Observed 2026-09-17: a campaign run
failed five login cases because of exactly this.
"""
from simlib.presence import should_restore_station_presence as should

STATION = "PPNAM/station_1"


def test_stale_offline_while_online_is_healed():
    assert should(STATION, "offline", STATION, desired="online", shutting_down=False)


def test_matching_value_is_left_alone():
    assert not should(STATION, "online", STATION, desired="online", shutting_down=False)


def test_deliberate_offline_is_not_healed():
    """The `station state=offline` control command must stick for the offline tests."""
    assert not should(STATION, "offline", STATION, desired="offline", shutting_down=False)
    # and an echo of a stray `online` while we want offline is corrected back
    assert should(STATION, "online", STATION, desired="offline", shutting_down=False)


def test_shutdown_echo_is_not_resurrected():
    assert not should(STATION, "offline", STATION, desired="online", shutting_down=True)


def test_other_topics_are_ignored():
    assert not should(f"{STATION}/scanner_abc", "offline", STATION,
                      desired="online", shutting_down=False)


def test_payload_is_normalised():
    assert should(STATION, "  OFFLINE\n", STATION, desired="online", shutting_down=False)
    assert not should(STATION, " Online ", STATION, desired="online", shutting_down=False)


def test_unknown_payload_is_ignored():
    assert not should(STATION, "", STATION, desired="online", shutting_down=False)
    assert not should(STATION, "garbage", STATION, desired="online", shutting_down=False)
