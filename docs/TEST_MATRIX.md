# Station 1 App — Functional Test Matrix

Campaign against the v3.2.0 backend simulator (`tools/station_sim.py`) on the
physical Chainway C72 (`HC720DE260100322`), driven over adb with scans injected
as scanner broadcasts. Simulator itself verified by 83 pytest cases and a
17-check live protocol run (`tools/test_campaign/fake_scanner.py`).

**Overall: 46/46 passed** — generated 2026-09-18 04:34 UTC

## Login & Session (contract §4) — 12/12

| ID | Case | Verdict | Notes |
|---|---|---|---|
| L1 | SCRAM login (op.both) reaches workflow selection, both tiles enabled | PASS |  |
| L2 | Wrong password shows an error and stays on login | PASS | error shown: 'SCRAM proof rejected.' |
| L3 | Unknown username shows an error and stays on login | PASS | error shown: 'SCRAM start rejected.' |
| L4 | allowedTabs gating for op.tag -> {'tag': True, 'off': False} | PASS |  |
| L5 | allowedTabs gating for op.off -> {'tag': False, 'off': True} | PASS |  |
| L6 | allowedTabs gating for op.none -> {'tag': False, 'off': False} | PASS |  |
| L7 | Badge scan on login screen signs in (badge login) | PASS |  |
| L8 | Unknown badge is rejected and stays on login | PASS | error shown: 'Badge not recognized.' |
| L9 | Login timeout (station silent) surfaces an error, app stays usable | PASS | error shown: 'Station did not respond' |
| L10 | Logout returns to login and closes the session at the station | PASS |  |
| L11 | Login dropdown lists the station's operators; picking one fills the username | PASS | station served 4 operators; picked 'op.off' from the dropdown |
| L12 | Inactivity auto sign-out returns to login with a reason | PASS | signed out after ~66s: 'Signed out after 1 minutes of inactivity.' |

## Tag Assignment (contract §5) — 7/7

| ID | Case | Verdict | Notes |
|---|---|---|---|
| T1 | Accepted tag scan shows success and reaches the station | PASS |  |
| T2 | Tag held by another scanner surfaces TAG_ALREADY_IN_USE | PASS | status: 'Tag already in use.' |
| T3 | Unknown tag surfaces TAG_UNKNOWN | PASS | status: 'Tag unknown.' |
| T4 | Re-scanning an own tag is an idempotent success | PASS | status: 'Tag already assigned by this scanner.' |
| T5 | Station silence surfaces the 10s timeout, next scan recovers | PASS | timeout status: 'No response from station â€” try again' |
| T6 | Forced INTERNAL_ERROR is surfaced as a station error | PASS | status: 'Unexpected station error.' |
| T7 | Expired session sends the operator back to login | PASS |  |

## Offload (contract §6) — 15/15

| ID | Case | Verdict | Notes |
|---|---|---|---|
| O1 | Matched scan prefills values + document progress on the edit step | PASS |  |
| O2 | Scan rejection TAG-PAL-001+BC-002 returns to scanning | PASS | status: 'Pair mismatch.' |
| O3 | Scan rejection TAG-PAL-001+BC-NOPE returns to scanning | PASS | status: 'Barcode not found.' |
| O4 | Scan rejection TAG-PAL-OFF+BC-OFF returns to scanning | PASS | status: 'Tag already offloaded.' |
| O5 | Scan rejection TAG-PAL-NODOC+BC-NODOC returns to scanning | PASS | status: 'Document unknown.' |
| O6 | Confirm with an edited weight; done prompt shows 6 of 12; Next Pallet resumes | PASS |  |
| O7 | Client-side validation blocks a zero weight locally | PASS |  |
| O8 | Server-side INVALID_BAG_WEIGHT keeps the operator on the edit step | PASS | status: 'Invalid bag weight.' |
| O9 | Confirm timeout stays on edit; retry succeeds | PASS |  |
| O10 | Done -> Complete closes the document and returns home; its pallets stop resolving | PASS | returned to home after Complete; post-close scan status: 'Document unknown.' |
| O11 | Short and Over classifications are both accepted | PASS | Short Tags: sent shortTagCount=3, returned home; Over Tags: sent overTagCount=2, returned home |
| O12 | Failed completion re-offers the close prompt; retry closes | PASS |  |
| O13 | Back to scan from the edit step keeps the scanned pair | PASS |  |
| O14 | RFID and barcode fields ignore typing; only scans fill them | PASS |  |
| O15 | Short Tags asks how many; 0 is refused and Cancel returns to the classifications | PASS | refused zero: 'Enter a whole number of tags, 1 or more'; retry sent shortTagCount=5 |

## Settings, Provisioning & Diagnostics — 9/9

| ID | Case | Verdict | Notes |
|---|---|---|---|
| S1 | Wrong PIN shows the attempts-left error and keeps the gate | PASS | error: 'Incorrect PIN. 4 attempts left before lockout.' |
| S2 | Five wrong PINs trigger the 30s lockout | PASS | lockout: 'Too many attempts. Try again in 30s.' |
| S3 | Correct PIN reveals the broker form prefilled with current settings | PASS |  |
| S4 | Invalid port blocks the save locally | PASS |  |
| S5 | Diagnostics show the derived device id and app version | PASS | deviceId=scanner_5c64df8d86a8 version=v1.3.0 (3) |
| S6 | Diagnostics pills: broker Connected, station Online | PASS |  |
| S7 | Station offline flips the station pill without blaming the broker | PASS |  |
| S8 | Save with unchanged values restarts and reconnects (blank password kept) | PASS |  |
| S9 | Auto sign-out minutes are validated and saved | PASS | auto sign-out set to 1 minute (restored to 15 by L12) |

## Presence & Reconnect (contract §8) — 3/3

| ID | Case | Verdict | Notes |
|---|---|---|---|
| P1 | Station offline signs the operator out with a reason; login refuses while offline | PASS | reason: 'Station went offline â€” you were signed out. Sign in again when the station is back online.'; refused in ~6s: 'Station is offline. Sign-in is unavailable until the station app is running.' |
| P2 | Network drop fires the Last Will; reconnect republishes online presence | PASS | LWT offline observed; pill during outage: 'Reconnecting' |
| P3 | Workflows still work after the reconnect (fresh session) | PASS |  |
