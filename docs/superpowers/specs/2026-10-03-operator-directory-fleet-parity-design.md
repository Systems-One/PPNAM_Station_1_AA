# Operator directory (login-screen user list) — fleet parity design

Date: 2026-10-03. Status: approved for implementation (user: "continue").

## 1. What Station 1 does (reference)

Station 1 serves the handheld login screen a **display-only list of operators** so the
operator picks a name and types only the password. Contract: `Station 1/PPNAM-Station-1-App/
docs rev 2/Station1_MQTT_OperatorDirectory_3_2_0.md` (MQTT contract 3.2.0 §4.5).

Wire (schema 4.1):

```
PPNAM/station_1/{deviceId}/req/operator_list_requested   scanner -> station  (bare envelope, no operatorSessionId)
PPNAM/station_1/{deviceId}/res/operator_list             station -> scanner
{ ..envelope.., "accepted": true, "nextAction": "login",
  "operators": [ { "username": "jsmith", "displayName": "J. Smith" }, ... ] }
```

Rules that carry to every station:

- Content: **active accounts that could complete a SCRAM password login** on that station
  (`IsActive` and a resolvable SCRAM verifier). Badge-only / verifier-less accounts excluded.
- Exactly two properties per entry: `username`, `displayName`. No role, no permissions, no
  badge tags, no verifier material. `displayName` falls back to `username`.
- Sorted by `displayName` (case-insensitive). Empty list with `accepted: true` is valid.
- Replay: identical `(deviceId, requestType, messageId)` + identical body returns the stored
  response; changed body is rejected (`message_id_reused` / `message_id_conflict`).
- Failures (`accepted: false`, timeout, rejection) must never break login: the scanner keeps
  the last cached list, or shows an empty dropdown, and a typed username still works.

Scanner (Station 1 AA reference `OperatorDirectory.kt`, `LoginActivity.showOperators`):

- Request once per broker connect while the login screen is showing (10 s timeout).
- Cache the last accepted list on the device (SharedPreferences `operator_directory`/`operators`,
  JSON array) so the dropdown is populated before, or without, an answer.
- Username field is an editable dropdown. Rows read `username — Display Name`; picking a row
  leaves only the username in the field and moves focus to the password. Dropdown arrow only
  visible when the list is non-empty; tapping the field opens the list when non-empty; typing a
  name that is not listed still logs in. Blank usernames dropped; missing displayName → username.

Windows side: every station's own WPF sign-in dialog already lists users in a combo box
(Stations 1–4 verified), so the only Windows gap is **answering the MQTT request**.

## 2. Current state per station

| Station | Windows (station-side handler) | Android (dropdown) |
|---|---|---|
| 1 | done | done |
| 2 | **missing** (rev2.1 processor, badge-login branch adds `login_requested`) | **missing** (Compose) |
| 3 | done on `main` (`RfidAuthenticationMessageProcessor`, contract `MQTT_CONTRACT.md` 2.0.0 §4.1); fleet-merge only in uncommitted WIP on `feature/user-sync-parity` — leave alone | wired; gaps: in-memory cache only, dropdown opens on tap only when field empty |
| 4 | **missing** (`MqttAuthenticationProcessor`, schema 4.1, async + SQLite replay store) | **missing** (Compose) |
| 5 | **no desktop/server repo exists anywhere in this tree**; nothing answers `PPNAM/station_5/+/req/+` | **missing** (Views, near-identical to Station 1 AA) |
| Launcher | n/a | no login screen — out of scope |

## 3. Design per repository

### 3.1 Station 2 desktop — `Station 2/PPNAM-Station-2`

Base branch: `feature/scanner-badge-login` (worktree `.worktrees/badge-login`, one commit on
top of `main` that already injects `UserAuthenticationService` into `Rev2ScannerProcessor`).
New branch `feature/operator-directory` in worktree `.worktrees/operator-directory`.

- `Rev2ScannerProcessor.RequestSuffixes` += `operator_list_requested`; suffix map
  `"operator_list_requested" => "operator_list"`.
- Handled inside the authentication branch (next to `login_requested`) so the existing
  60 s replay cache applies: identical body → replayed; different body → `message_id_conflict`.
  No `purpose` required, no `operatorSessionId` read, no fields beyond the rev2.1 envelope.
- Response is the standard rev2.1 reply with `success: true`, `operatorMessage: "Operator list."`,
  `data: { "operators": [ { "username", "displayName" } ] }` (`data` must be an object — the
  Android `parseOutcome` rejects a bare array).
- `ScramAuthenticationService.CanStartPasswordLogin(StationUser)` made `public static`
  (`IsActive && TryResolveVerifier`), used by new
  `UserAuthenticationService.ListPasswordLoginOperators()` → `IReadOnlyList<OperatorDirectoryEntry>`
  over `IStationUserAuthenticationStore.LoadAuthenticationUsers()`. **Local users only**: the
  scanner SCRAM path (`LoadScramUser`) does not consult the fleet mirror, so fleet accounts
  cannot complete a handheld password login and must not be offered. `displayName = Name`
  (no display-name column). Sorted by displayName, case-insensitive.
- Subscription bug fix (blocks every scanner request, not only this one): `RfidMqttService.
  InitializeAsync` with explicit suffixes subscribes `PPNAM/+/req/{suffix}` and `PPNAM/+/status`,
  but requests arrive on `PPNAM/station_2/{deviceId}/req/{suffix}` and presence on
  `PPNAM/station_2/{deviceId}` (see `TryParsePpnamTopic`). Subscribe `{StationBaseTopic}/+/req/{suffix}`
  and `{StationBaseTopic}/+` instead, and repair the two stale `RfidMqttServiceTests`.
- Contract doc `RFID_MQTT_CONTRACT.md` rev2.1 section: topic row, request/response example, rules
  above; "four request suffixes" sentence updated.
- Tests (`PPNAM.Station2.Tests`, new `OperatorDirectoryTests.cs`, xUnit, same construction as
  `Rev2MixingServiceTests` scanner-endpoint tests): suffix supported; answers on `operator_list`
  with `success:true` and `data.operators`; entries carry only `username`/`displayName`;
  inactive excluded; verifier-less excluded; fleet-only user excluded; sorted; empty list
  accepted; replay returns stored list; changed body → `message_id_conflict`; envelope
  validation still applies (bad schemaVersion → `invalid_envelope`).

### 3.2 Station 2 Android — `Station 2/PPNAM_Station_2_AA`

Base branch: `feature/badge-login` (worktree `.worktrees/badge-login`). New branch
`feature/operator-directory` in worktree `.worktrees/operator-directory` (copy `local.properties`).

- DTO `OperatorListResponse(val operators: List<OperatorEntryDto> = emptyList())`,
  `OperatorEntryDto(val username: String = "", val displayName: String = "")` in `dto/AuthMessages.kt`.
- Domain model `OperatorEntry(username, displayName)` with `label = "$username — $displayName"`.
- `MqttRepositoryImpl.request`: no session for `operator_list_requested` (extend the SCRAM rule).
- `OperatorDirectoryStore` interface + `PrefsOperatorDirectoryStore` (SharedPreferences
  `operator_directory`, key `operators`, JSON via `WireJson.gson`), Hilt-bound like `PinLockoutStore`.
- `OperatorDirectoryUseCase(mqttRepository, store)`: `cached()`, `suspend fun refresh(): List<OperatorEntry>?`
  (null on any failure, cache untouched; on success filters blank usernames, falls back displayName,
  sorts by displayName, persists, returns).
- `LoginViewModel`: `val operators: StateFlow<List<OperatorEntry>>` seeded from cache; collects
  `mqttRepository.connectionState.filter { it == CONNECTED }` and refreshes while
  `loginScreenActive` and nobody is signed in (`sessionHolder.session.value == null`).
- `LoginScreen`: wrap the username `OutlinedTextField` in `ExposedDropdownMenuBox` (editable;
  `menuAnchor()`), trailing arrow only when the list is non-empty, `ExposedDropdownMenu` of labels;
  pick → `username = entry.username`, close, `focusManager.moveFocus(Down)`. Typed names still work.
- Tests: `OperatorDirectoryUseCaseTest` (mock `MqttRepository`, in-memory store: parse/filter/
  fallback/sort/persist; failure keeps cache), `Rev2TransportTest` case "operator_list_requested
  never carries a session", `LoginViewModelTest` case "refreshes the directory when the broker
  connects on the login screen".
- `tools/backend-sim`: add an `operator_list_requested` handler returning the seeded SCRAM users
  (keeps the simulator usable for the login section).

### 3.3 Station 4 desktop — `Station 4/PPNAM-Station-4`

Base: `origin/main` (ab103c0, badge login merged; local `main` is stale). Worktree
`C:/Dev/Clients/PPNAM/Station 4/worktrees/PPNAM-Station-4-operator-directory`, branch
`feature/operator-directory`. Do not touch the dirty main checkout.

- `MqttAuthenticationProcessor`: `"operator_list_requested" => "operator_list"`; dispatch to
  `ProcessOperatorListAsync`; `BuildFailure` case producing `MqttOperatorListMessage` with
  `NextAction = "login"`. Same replay/commit path as the other auth requests.
- Models: `MqttOperatorListRequestedMessage : MqttWorkflowEnvelope;`,
  `MqttOperatorDirectoryEntry { Username, DisplayName }`,
  `MqttOperatorListMessage : MqttWorkflowEnvelope { Accepted, Reason?, Operators, NextAction }`.
  `operatorSessionId` is serialized by the base envelope as `""` — acceptable (handheld sends "" too).
- `MqttScramAuthenticationService.ListPasswordLoginOperatorsAsync(DateTime utcNow, ct)`:
  local `GetUsersAsync` merged with `GetFleetUsersAsync(includeInactive:false)` (local wins by
  case-insensitive trimmed name — fleet users CAN complete handheld SCRAM here because
  `FindUserByNameAsync` falls back to `fleet.users`), filtered `IsActive && TryResolveVerifier`,
  `displayName = Name`, sorted. On `SqlException`/`InvalidOperationException` fall back to
  `_local` offline users not older than `AuthenticationService.OfflineValidity` with the same
  filter; if that fails too, `accepted:false`, `errorCode: "directory_unavailable"`, `nextAction: "login"`.
- Contract `DOCS/Station4_Wastage_MQTT_Contract.md`: version 5.3.0 changelog line, topic row,
  new "Operator directory (pre-login)" section with request/response and rules.
- Tests: new `MqttOperatorListTests.cs` (same fixture as `MqttBadgeLoginProcessorTests`):
  topic/suffix; `accepted:true` + `nextAction:"login"` + entries; only two props per entry;
  inactive excluded; `ScramVerifierVersion == 0` (shadow-provisioned) excluded; fleet user
  included; local wins over same-named fleet user; sorted; empty accepted; replay returns stored
  payload with `IsReplay`; changed body → `message_id_reused`; repository exception → `accepted:false`.

### 3.4 Station 4 Android — `Station 4/PPNAM_Station_4_AA` (Compose, manual DI)

Branch `feature/operator-directory` off `master`, in place (tree clean).

- DTOs in `dto/AuthMessages.kt` as in 3.2 (Gson, defaults on every param).
- `domain/model/OperatorEntry`; `domain/.../OperatorDirectoryStore` interface;
  `data/settings/SharedPrefsOperatorDirectoryStore` (prefs `operator_directory`, key `operators`).
- `domain/usecase/OperatorDirectoryUseCase(requestChannel: RequestChannel, store, deviceId)`:
  `cached()`, `suspend fun refresh(): List<OperatorEntry>?` — request
  `operator_list_requested` with `EmptyPayload`, `OperatorListResponse::class.java`, no session.
- `AppContainer`: construct; `AppNavGraph`: pass into `LoginViewModel`.
- `LoginViewModel(..., operatorDirectory)`: `operators` StateFlow seeded from cache; refresh on
  `connectionManager.connectionState.filter { it == CONNECTED }` while no session.
- `LoginScreen`: `ExposedDropdownMenuBox` as in 3.2.
- Tests: `OperatorDirectoryUseCaseTest` with a fake `RequestChannel` (as `SyncWasteCatalogueUseCaseTest`).
- `CLAUDE.md` pointer to the contract path is stale (`Windows\...`); correct it to `Station 4\PPNAM-Station-4\DOCS\...`.

### 3.5 Station 5 Android — `Station 5/PPNAM_Station_5_AA` (Views, Station 1 clone)

Branch `feature/operator-directory` off `master`. Port Station 1 AA verbatim, package
`com.mitas.ppnam.station5aa`: `OperatorDirectory.kt` (`OperatorEntry`, `OperatorListCodec`,
`OperatorDirectory` with prefs cache), `AuthClient.operatorList(...)` (its `request()` has no
`Step` parameter), `LoginActivity` (`directory`, `connectionListener` via
`MqttManager.addConnectionListener`, `showOperators`, remove listener in `onDestroy`),
`activity_login.xml` (`ExposedDropdownMenu` style + `MaterialAutoCompleteTextView`,
`completionThreshold=0`, `textNoSuggestions`), `OperatorListCodecTest.kt`. KDoc notes there is
no Station 5 backend in this tree yet; the dropdown stays empty and typed login is unaffected.

### 3.6 Station 3 Android — `Station 3/PPNAM_Station_3_AA`

Branch `feature/operator-directory-parity` off `master`.

- Persist the cache: `OperatorDirectory` gains `load(context)` (called once from
  `LoginActivity.onCreate` before `setupOperatorDropdown`) and `update(context, entries)` writing
  SharedPreferences `operator_directory`/`operators` as a JSON array (codec `encode`/`decode`,
  tolerant of garbage). `AuthClient.requestOperatorList` persists via the context it already has.
- Tap opens the dropdown whenever the adapter is non-empty (drop the "field empty" condition),
  matching Station 1.
- Test: codec round-trip and garbage tolerance added to `OperatorDirectoryTest`.

### 3.7 Not changed

Station 1 (both), Station 3 desktop (feature already on `main`; the fleet-merge WIP on
`feature/user-sync-parity` is someone's uncommitted work), Launcher, Account Management,
Central DB, Dashboard. No version bumps (release process bumps them). Nothing pushed.

## 4. Verification

- Desktop: `dotnet test` of the station test project; the new test class green, no new failures
  versus the base branch (Station 2 `main` has 4 pre-existing failures, two of which this work fixes).
- Android: `.\gradlew.bat testDebugUnitTest` and `assembleDebug` from PowerShell (gradlew does not
  run from the Bash tool), new tests green.
- Device verification against the C72 and a real station is a follow-up (see memory
  `station1-scanner-testing`, `c72-emulator-ui-audit-harness`).
