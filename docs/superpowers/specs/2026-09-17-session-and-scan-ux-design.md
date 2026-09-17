# Station 1 AA — session lifecycle and scan UX changes (v1.3.0)

Date: 2026-09-17. Approved in chat by the product owner before implementation.

## Requested changes

| # | Request (verbatim) | Resolution |
|---|---|---|
| 1 | Feature to close scans after offload is marked as short complete or over. On close return to home screen | Implement (§1) |
| 2 | Station offline out logout users | Implement (§2), station presence only |
| 3 | In settings set a configurable logout | Implement (§3), inactivity timeout |
| 4 | User Login drop down feature | Implement (§4), list served by the station over MQTT |
| 5 | Badge Scan Login | Already implemented; confirmed no change needed |
| 6 | Remove typing from barcode and RFID fields | Implement (§5) |
| 7 | Error message not explained and does not close to main window | The Station Offline overlay; resolved by §2 |

## 1. Offload close returns to the home screen

After `offload_complete` is **accepted** by the station, `OffloadActivity` finishes
(backward transition) and the operator lands on `MainActivity`. A rejected or timed-out
close keeps the current behaviour: red status text and the close prompt re-offered so the
operator can retry or cancel back to scanning.

## 2. Station offline forces a logout

- `ScannerApp` owns a single `SessionGuard` that subscribes to `MqttManager` station
  presence once, for the process lifetime.
- When presence flips to **offline** while `OperatorSessionHolder.session != null`:
  fire the best-effort `reader_logout_requested`, clear the session, and start
  `LoginActivity` with `NEW_TASK | CLEAR_TASK` carrying an `EXTRA_SIGNED_OUT_REASON`.
- `LoginActivity` shows that reason in its error text: "Station went offline — you were
  signed out. Sign in again when the station is back online."
- While `ConnectionStatus.STATION_OFFLINE`, the login screen shows a persistent banner
  ("Station is offline. Sign-in is unavailable until the station app is running.") and a
  password or badge attempt fails immediately with that text instead of waiting for the
  10-second timeout.
- Broker `RECONNECTING`/`OFFLINE` do **not** log anyone out (the station still holds the
  session; presence is stale, not false).
- `MainActivity` loses `layoutStationOffline` and its station status listener.

## 3. Inactivity auto-logout, configurable in Settings

- `SettingsRepository.autoLogoutMinutes` (Int, default 15, `0` = never), stored in the
  `settings` prefs file.
- Settings screen: a numeric field "Auto sign-out after (minutes), 0 = never" inside the
  PIN-locked group, saved with the broker settings (validated 0..1440).
- `InactivityMonitor` (pure Kotlin, injectable clock and scheduler for tests):
  `start(timeoutMs)`, `touch()`, `stop()`, `checkNow()`; fires `onExpired` once when
  `now - lastActivity >= timeout`. Deadline uses elapsed real time so backgrounding does not
  pause it; `checkNow()` runs on every signed-in `onResume`.
- `SessionGuard` starts the monitor when a session is set and stops it on clear.
  `SessionActivity` (base class for Main, TagAssignment, Offload, Settings) forwards
  `onUserInteraction()` and `onResume()`; scanner broadcast receivers call `touch()`.
- On expiry: same forced-logout path as §2 with reason "Signed out after N minutes of
  inactivity."

## 4. User dropdown on login

- Contract v3.2.0 §4.5 adds `req/operator_list_requested` → `res/operator_list` using the
  schema 4.1 envelope, no session required. Accepted response carries
  `operators: [{ "username": "op.both", "displayName": "Bongi Both" }, …]` (active
  password operators; display-only, never an authorisation hint).
- `OperatorDirectory` requests the list whenever `LoginActivity` becomes connected, keeps
  it in memory, and persists the last list to prefs so the dropdown is populated on the
  next launch before the station answers.
- The username field becomes an editable `MaterialAutoCompleteTextView` (exposed dropdown
  menu). Choosing an entry fills the username; typing a name not in the list still works.
  Password entry and the SCRAM flow are unchanged.
- Simulator (`tools/simlib/world.py`, `station_sim.py`) implements the handler from its
  `OPERATORS` table. The Windows station app must implement the same handler; this is
  documented in the contract as a station requirement and is out of scope for this pass.

## 5. Scan-only RFID and barcode fields (Offload)

`etTag` and `etBarcode` become non-focusable, no soft keyboard, no cursor; hints read
"Scan RFID tag" / "Scan barcode". Only scanner broadcasts populate them. Enabled/disabled
styling during MATCHING is kept.

## Testing

- Unit: `InactivityMonitor` (expiry, touch resets, stop cancels, checkNow after background),
  `SettingsRepository` auto-logout default/parse/clamp, `OperatorDirectory` JSON parsing and
  cache round-trip, `WorkflowMessages`/`AuthClient` payload for `operator_list_requested`.
- Simulator pytest: `operator_list_requested` accepted response; envelope rejection path.
- Device campaign (`tools/test_campaign`): new cases — offload close returns home; station
  offline signs out with reason; login banner while offline; dropdown lists simulator
  operators; auto-logout with a 1-minute setting; scan-only fields ignore typed input.
  Existing P1 (overlay) is rewritten to the forced-logout behaviour.
- `versionCode 3`, `versionName 1.3.0`.
