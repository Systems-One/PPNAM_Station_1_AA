"""Station-node presence self-heal, mirroring the app's PresenceSelfHeal.

A simulator restart faster than the broker's dead-connection detection lets the
previous connection's Last Will — retained `offline` — land AFTER the new
connection's retained `online`, leaving `PPNAM/station_1` stuck at `offline` while
the simulator is running and answering. The simulator subscribes to its own station
node so it can correct the retained value back to whatever state it intends.

`shutting_down` guards the graceful-shutdown race: shutdown() publishes retained
`offline` while still connected and the broker echoes it back, which must not
resurrect `online`.
"""
from __future__ import annotations

VALID_STATES = ("online", "offline")


def should_restore_station_presence(
    topic: str,
    payload: str,
    station_topic: str,
    desired: str,
    shutting_down: bool,
) -> bool:
    """True when `desired` must be republished retained on the station node."""
    if shutting_down or topic != station_topic:
        return False
    seen = (payload or "").strip().lower()
    if seen not in VALID_STATES:
        return False
    return seen != desired
