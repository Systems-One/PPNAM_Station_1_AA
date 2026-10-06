# Station 1 REV2 MQTT Topic Alignment Implementation Plan

> Relocated 2026-10-06 from the loose `PPNAM/Docs/superpowers/` folder. Paths in this document are as written at the time: `Windows/` and `Andriod/` are today's `Station 1/` folders, and `MQTT_TOPIC_STRUCTURE.md` is now `PPNAM_Server/docs/mqtt/MQTT_TOPIC_STRUCTURE.md`.

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make the other dev's REV2 drop (Station 1 Windows backend authentication + Rev 2 contract docs) interoperate with the fleet-wide namespaced MQTT topic structure, so the Android Station 1 app's login actually reaches the new backend.

**Architecture:** The REV2 authentication code and Rev 2 contract doc were written against the retired pre-2026-08-17 topic layout (`PPNAM/{deviceId}/req|res/...`, presence on `/status` sub-topics), while the whole fleet — including this repo's own workflow code — ships the namespaced layout (`PPNAM/station_1/{deviceId}/req|res/...`, presence retained on base nodes). We fix the backend's auth topic routing (parse + publish), prune dead legacy subscriptions, rewrite the Rev 2 docs onto the namespaced layout, and flip one Android response suffix to converge on the Station 2 v4.1 authority.

**Tech Stack:** C# / .NET 8 WPF + xunit (`dotnet test`), Kotlin Android + Gradle, Markdown contract docs.

**Spec:** `C:\Dev\Clients\PPNAM\MQTT_TOPIC_STRUCTURE.md` (fleet-wide normative topic structure). Decision log (2026-08-25, approved by Jonathan): (1) keep lowercase namespaced topics — Rev 2 aligns to the fleet, not vice versa; (2) SCRAM proof response suffix converges on `scram_proof_result` — the **Android client changes**, the backend and Rev 2 doc keep `scram_proof_result`.

## Global Constraints

- All topics lowercase, namespaced: `PPNAM/station_1/{deviceId}/req|res/{type}`; presence retained on base nodes `PPNAM/station_1` and `PPNAM/station_1/{deviceId}`; station broadcasts on `PPNAM/station_1/res/{type}`. MQTT topics are case-sensitive.
- Payloads, QoS, schema `"4.1"`, and message semantics are **unchanged** everywhere. Only topic paths, one Android response-type string, and doc wording change.
- Windows repo: `C:\Dev\Clients\PPNAM\Windows\PPNAM-Station-1-App` (branch `main`). Android repo: `C:\Dev\Clients\PPNAM\Andriod\PPNAM_Station_1_AA` (branch `master`). Both have pre-existing uncommitted changes (`MQTT_CONTRACT.md`, `LOGIN_MQTT_CONTRACT.md` — yesterday's canonical-reference edits). Fold those files into this plan's doc commits; do NOT commit `graphify-out/` or any other unrelated dirty file. Work directly in the existing checkouts (no worktree — the plan spans two repos and depends on their uncommitted doc state).
- Backend tests: `dotnet test PPNAM_Station1.Tests/PPNAM_Station1.Tests.csproj` from the Windows repo root. Android build: `./gradlew.bat assembleDebug` (and `testDebugUnitTest`) from the Android repo root.
- Do not push. Commits stay local; Jonathan coordinates with the other dev.

### Canonical topic mapping (used by every doc task)

| Old (REV2 doc) | New (fleet standard) |
|---|---|
| `PPNAM/{readerDeviceId}/req/{type}` | `PPNAM/station_1/{readerDeviceId}/req/{type}` |
| `PPNAM/{readerDeviceId}/res/{type}` | `PPNAM/station_1/{readerDeviceId}/res/{type}` |
| `PPNAM/{readerDeviceId}/{command}` (bare, e.g. `assignment_v2`, `offload_v2`, `all_offloaded`, `unassign`, `reassign`) | `PPNAM/station_1/{readerDeviceId}/req/{command}` |
| `PPNAM/station_1/{eventOrResult}` (bare broadcast, e.g. `tag_assignment_request`, `assignment_result`, `offload_start`, `offload_result`, `all_offloaded_result`, `unassign_result`, `reassign_result`) | `PPNAM/station_1/res/{eventOrResult}` |
| `PPNAM/station_1/status` | `PPNAM/station_1` (retained presence on the base node) |
| `PPNAM/{readerDeviceId}/status`, `PPNAM/{ownDeviceId}/status` | `PPNAM/station_1/{readerDeviceId}` (retained presence on the device's base node) |
| Station subscribes `PPNAM/+/req/+` | Station subscribes `PPNAM/station_1/+` and `PPNAM/station_1/+/req/+` |
| Scanner subscribes `PPNAM/{ownReaderDeviceId}/res/+` | Scanner subscribes `PPNAM/station_1/{ownReaderDeviceId}/res/+`, `PPNAM/station_1/res/+`, and `PPNAM/station_1` |

---

### Task 1: Backend — namespaced authentication topic routing

The auth entry point `TryProcessAuthenticationRequestAsync` only accepts retired 4-segment topics (`PPNAM/{deviceId}/req/{type}`) and replies on `PPNAM/{deviceId}/res/{type}`. The Android app publishes 5-segment namespaced topics, so login requests are silently ignored. Route auth through the existing namespaced parser, gated on the auth request-type set (the processor **rejects** unknown types rather than ignoring them, so the gate must come first or workflow requests like `assignment_v2` would be rejected as `authentication_request_unsupported` instead of falling through to `ProcessIncomingMessage`).

**Files:**
- Modify: `PPNAM_Station1.csproj` (add InternalsVisibleTo)
- Modify: `Services/RfidAuthenticationMessageProcessor.cs` (~line 24, add public static predicate)
- Modify: `Device_Initializing/RfidDeviceInitializer.cs:266-313` (`TryProcessAuthenticationRequestAsync`)
- Test: `PPNAM_Station1.Tests/AuthenticationTopicRoutingTests.cs` (new)

**Interfaces:**
- Consumes: existing `internal static bool RfidDeviceInitializer.TryParsePpnamTopic(string topic, out string deviceId, out string direction, out string suffix)` (line 839; accepts only `PPNAM/station_1/...`) and `internal static string RfidDeviceInitializer.BuildResponseTopic(string deviceId, string suffix)` (line 562; produces `PPNAM/station_1/{deviceId}/res/{suffix}`).
- Produces: `public static bool RfidAuthenticationMessageProcessor.IsAuthenticationRequestType(string? requestType)` — used by Task 1 only, but part of the public surface after this task.

- [ ] **Step 1: Expose internals to the test assembly**

In `PPNAM_Station1.csproj`, add inside the root `<Project>`:

```xml
  <ItemGroup>
    <InternalsVisibleTo Include="PPNAM_Station1.Tests" />
  </ItemGroup>
```

- [ ] **Step 2: Write the failing tests**

Create `PPNAM_Station1.Tests/AuthenticationTopicRoutingTests.cs`:

```csharp
using PPNAM_Station1.Device_Initializing;
using PPNAM_Station1.Services;

namespace PPNAM_Station1.Tests;

public sealed class AuthenticationTopicRoutingTests
{
    [Theory]
    [InlineData("scram_start_requested")]
    [InlineData("scram_proof_requested")]
    [InlineData("login_requested")]
    [InlineData("reader_logout_requested")]
    public void AuthenticationRequestTypes_AreRecognized(string requestType)
    {
        Assert.True(RfidAuthenticationMessageProcessor.IsAuthenticationRequestType(requestType));
    }

    [Theory]
    [InlineData("assignment_v2")]
    [InlineData("offload_v2")]
    [InlineData("")]
    [InlineData(null)]
    public void WorkflowAndEmptyRequestTypes_AreNotAuthentication(string? requestType)
    {
        Assert.False(RfidAuthenticationMessageProcessor.IsAuthenticationRequestType(requestType));
    }

    [Fact]
    public void NamespacedAuthRequestTopic_ParsesDeviceAndSuffix()
    {
        bool ok = RfidDeviceInitializer.TryParsePpnamTopic(
            "PPNAM/station_1/scanner_5c64df8d86a8/req/scram_start_requested",
            out string deviceId, out string direction, out string suffix);

        Assert.True(ok);
        Assert.Equal("scanner_5c64df8d86a8", deviceId);
        Assert.Equal("req", direction);
        Assert.Equal("scram_start_requested", suffix);
    }

    [Fact]
    public void RetiredUnnamespacedAuthTopic_IsRejected()
    {
        Assert.False(RfidDeviceInitializer.TryParsePpnamTopic(
            "PPNAM/scanner_5c64df8d86a8/req/scram_start_requested",
            out _, out _, out _));
    }

    [Fact]
    public void AuthenticationResponse_PublishesUnderStationNamespace()
    {
        Assert.Equal(
            "PPNAM/station_1/scanner_5c64df8d86a8/res/scram_challenge",
            RfidDeviceInitializer.BuildResponseTopic("scanner_5c64df8d86a8", "scram_challenge"));
    }
}
```

Adjust the two `using` lines to the actual namespaces of the two classes (open each file and copy its `namespace` declaration verbatim — do not guess).

- [ ] **Step 3: Run tests to verify the compile failure**

Run: `dotnet test PPNAM_Station1.Tests/PPNAM_Station1.Tests.csproj --filter AuthenticationTopicRouting`
Expected: build FAILS — `IsAuthenticationRequestType` does not exist yet (the parser/builder tests would pass, but the file cannot compile).

- [ ] **Step 4: Add the predicate to the processor**

In `Services/RfidAuthenticationMessageProcessor.cs`, directly below the `SupportedRequestTypes` field (line ~30):

```csharp
    /// <summary>Request-type gate for topic routing: only these suffixes are
    /// authentication messages; anything else falls through to workflow handling.</summary>
    public static bool IsAuthenticationRequestType(string? requestType) =>
        requestType is not null && SupportedRequestTypes.Contains(requestType);
```

- [ ] **Step 5: Rewrite the auth topic routing**

In `Device_Initializing/RfidDeviceInitializer.cs`, replace the shape check at the top of `TryProcessAuthenticationRequestAsync` (lines 266-275):

```csharp
        private async Task<bool> TryProcessAuthenticationRequestAsync(string topic, string payload)
        {
            // Authentication rides the same namespaced request topics as the workflow
            // (PPNAM/station_1/{deviceId}/req/{type}); the request-type set decides which
            // path owns the message, because the processor rejects unknown types rather
            // than ignoring them.
            if (!TryParsePpnamTopic(topic, out string deviceId, out string direction, out string suffix) ||
                !direction.Equals("req", StringComparison.OrdinalIgnoreCase) ||
                !RfidAuthenticationMessageProcessor.IsAuthenticationRequestType(suffix))
            {
                return false;
            }
```

Then replace every remaining `parts[1]` with `deviceId` and every `parts[3]` with `suffix` in the rest of the method — the try block becomes:

```csharp
            RfidAuthenticationProcessResult result;
            try
            {
                result = _authenticationProcessor?.Process(
                        deviceId,
                        suffix,
                        payload ?? string.Empty,
                        DateTimeOffset.UtcNow)
                    ?? BuildAuthenticationUnavailableResult(deviceId, payload ?? string.Empty);
            }
            catch (Exception ex)
            {
                string exceptionId = Guid.NewGuid().ToString("N");
                LoggingService.Technical.Error(
                    ex,
                    "MQTT authentication request failed before a response was persisted. ExceptionId={ExceptionId} DeviceId={DeviceId} RequestType={RequestType}",
                    exceptionId,
                    deviceId,
                    suffix);
                result = BuildAuthenticationUnavailableResult(
                    deviceId,
                    payload ?? string.Empty,
                    exceptionId);
            }
```

and the success-path log near line 319 that passes `parts[3]` as `RequestType` likewise passes `suffix`.

Finally, replace the response publish at line ~308:

```csharp
            string responseTopic = BuildResponseTopic(result.ResponseDeviceId, result.ResponseSuffix);
```

(was `$"{BaseTopic}/{result.ResponseDeviceId}/res/{result.ResponseSuffix}"`).

- [ ] **Step 6: Run the routing tests, then the full suite**

Run: `dotnet test PPNAM_Station1.Tests/PPNAM_Station1.Tests.csproj --filter AuthenticationTopicRouting`
Expected: PASS (6 test cases).
Run: `dotnet test PPNAM_Station1.Tests/PPNAM_Station1.Tests.csproj`
Expected: PASS — no existing test pins the old 4-segment auth topics (verified during planning).

- [ ] **Step 7: Commit**

```bash
git add PPNAM_Station1.csproj Services/RfidAuthenticationMessageProcessor.cs Device_Initializing/RfidDeviceInitializer.cs PPNAM_Station1.Tests/AuthenticationTopicRoutingTests.cs
git commit -m "fix(mqtt): route authentication over namespaced station_1 topics"
```

---

### Task 2: Backend — prune retired legacy subscriptions

`BuildSubscriptionTopics` (REV2 merge) re-added 15 subscription filters for the retired un-namespaced layout (`PPNAM/+/status`, `PPNAM/+/req/+`, bare command topics). Nothing publishes on them since the 2026-08-17 restructure, and `ProcessIncomingMessage` → `TryParsePpnamTopic` cannot parse them — every match would be logged "Unhandled RFID topic". They are dead weight and a wildcard-overlap hazard.

**Files:**
- Modify: `Device_Initializing/RfidDeviceInitializer.cs:105-132` (`BuildSubscriptionTopics`)

**Interfaces:**
- Consumes: `StationBaseTopic` constant (`PPNAM/station_1`).
- Produces: subscription set = exactly `PPNAM/station_1/+` and `PPNAM/station_1/+/req/+` (matches `MQTT_CONTRACT.md` §2).

- [ ] **Step 1: Replace the topic list**

```csharp
        private IEnumerable<string> BuildSubscriptionTopics()
        {
            // Contract (MQTT_CONTRACT.md §2): exactly two filters. Device presence on the
            // base node PPNAM/station_1/{deviceId}, and every device request under
            // PPNAM/station_1/{deviceId}/req/{suffix} — authentication included.
            // The pre-2026-08-17 un-namespaced topics are retired; nothing publishes them.
            var topics = new List<string>
            {
                $"{StationBaseTopic}/+",
                $"{StationBaseTopic}/+/req/+"
            };

            return topics
                .Where(x => !string.IsNullOrWhiteSpace(x))
                .Distinct(StringComparer.OrdinalIgnoreCase);
        }
```

- [ ] **Step 2: Run the full test suite**

Run: `dotnet test PPNAM_Station1.Tests/PPNAM_Station1.Tests.csproj`
Expected: PASS.

- [ ] **Step 3: Commit**

```bash
git add Device_Initializing/RfidDeviceInitializer.cs
git commit -m "chore(mqtt): drop retired un-namespaced subscription filters"
```

---

### Task 3: Rewrite `docs rev 2/Station1_MQTT_Contract_Rev_2.md` onto the namespaced layout

The Rev 2 contract (1,253 lines) is normative for REV2's new message families but states every topic in the retired layout. Apply the canonical topic mapping (see Global Constraints) throughout, and rewrite the layout-defining sections.

**Files:**
- Modify: `docs rev 2/Station1_MQTT_Contract_Rev_2.md`

**Interfaces:**
- Consumes: canonical mapping table (Global Constraints); `C:\Dev\Clients\PPNAM\MQTT_TOPIC_STRUCTURE.md`.
- Produces: a Rev 2 contract whose every topic is `PPNAM/station_1/...`; Task 5 links to it from `MQTT_CONTRACT.md`.

- [ ] **Step 1: Add the fleet-structure preamble**

Directly under the `# PPNAM Station 1 - MQTT Contract Rev 2` heading, insert:

```markdown
> **Topic structure (2026-08-25 alignment):** All topics in this contract follow the
> fleet-wide namespaced structure in `C:\Dev\Clients\PPNAM\MQTT_TOPIC_STRUCTURE.md`:
> `PPNAM/station_1/{readerDeviceId}/req|res/{type}`, presence retained on the base
> nodes `PPNAM/station_1` and `PPNAM/station_1/{readerDeviceId}` (no `/status`
> sub-topics), station broadcasts on `PPNAM/station_1/res/{type}`. Topics are
> case-sensitive and lowercase. Payloads and semantics are unchanged from the
> original Rev 2 draft, which used the retired pre-2026-08-17 topic layout.
```

- [ ] **Step 2: Rewrite section "2. Topic contract" (lines ~188-209)**

Replace the four code blocks and the subscription sentence with:

```markdown
Rev 2 receiving commands are reader-specific requests under the station namespace:

​```text
PPNAM/station_1/{readerDeviceId}/req/{command}
​```

Station broadcasts/results are station-scoped:

​```text
PPNAM/station_1/res/{eventOrResult}
​```

Schema 4.1 authentication uses the same device-addressed request/response topics:

​```text
Request:  PPNAM/station_1/{readerDeviceId}/req/{requestType}
Response: PPNAM/station_1/{readerDeviceId}/res/{responseType}
​```

Station 1 subscribes to `PPNAM/station_1/+` (device presence) and
`PPNAM/station_1/+/req/+` (all device requests). A scanner subscribes only to
`PPNAM/station_1/{ownReaderDeviceId}/res/+`, the broadcast tree
`PPNAM/station_1/res/+`, and the station presence node `PPNAM/station_1`.
```

(Remove the zero-width markers `​` — they exist only to keep this plan's fences intact.)

- [ ] **Step 3: Apply the canonical mapping to sections 2.2, 2.3, 0.3, and 16**

Mechanical rewrite using the mapping table. In §2.2's topic table every `PPNAM/{readerDeviceId}/{command}` becomes `PPNAM/station_1/{readerDeviceId}/req/{command}` and every `PPNAM/station_1/{eventOrResult}` becomes `PPNAM/station_1/res/{eventOrResult}`; the two presence rows become base-node topics (`PPNAM/station_1/{readerDeviceId}` / `PPNAM/station_1`). In §2.3, retitle to "Compatibility message types", map the eight `PPNAM/{reader}/{suffix}` rows to `PPNAM/station_1/{reader}/req/{suffix}`, and replace the "remain subscribed during migration" sentence with: "The retired pre-2026-08-17 un-namespaced topics (`PPNAM/{deviceId}/...`) are no longer subscribed; legacy *message types* remain accepted on the namespaced request topics under the rules below." In §0.3 and §16, apply the same mapping to every listed topic (including `PPNAM/{ownDeviceId}/...` → `PPNAM/station_1/{ownDeviceId}/...` and all `/status` → base-node rewrites, e.g. §16's presence topics become `PPNAM/station_1` and `PPNAM/station_1/{readerDeviceId}` with retained `online`/`offline` and LWT).

- [ ] **Step 4: Sweep the remainder of the file**

Apply the mapping to every other occurrence (sections 4-18 examples and prose). Then verify nothing old-shaped remains:

Run (repo root): `grep -nE 'PPNAM/\{|PPNAM/station_1/status|station_1/(tag_assignment_request|assignment_result|unassign_result|reassign_result|offload_start|offload_result|all_offloaded_result)' "docs rev 2/Station1_MQTT_Contract_Rev_2.md"`
Expected: zero matches (every `PPNAM/{...}` now reads `PPNAM/station_1/{...}`; bare broadcast names now sit under `res/`).

- [ ] **Step 5: Commit**

```bash
git add "docs rev 2/Station1_MQTT_Contract_Rev_2.md"
git commit -m "docs(rev2): restate MQTT contract on namespaced station_1 topics"
```

---

### Task 4: Align `MQTT_Design.md`, verify `HANDSCANNER_MQTT_FLOW.md`

`MQTT_Design.md` is mixed: workflow sections already namespaced, authentication sections still `PPNAM/{deviceId}/req|res/...`. `HANDSCANNER_MQTT_FLOW.md` looked fully namespaced during planning — verify, don't assume.

**Files:**
- Modify: `MQTT_Design.md`
- Verify: `HANDSCANNER_MQTT_FLOW.md`

- [ ] **Step 1: Rewrite the authentication topics in `MQTT_Design.md`**

Run `grep -nE 'PPNAM/\{' MQTT_Design.md` to list the old-shape lines, then apply the canonical mapping to each (`PPNAM/{deviceId}/req` → `PPNAM/station_1/{deviceId}/req`, `PPNAM/{ownDeviceId}/res/+` → `PPNAM/station_1/{ownDeviceId}/res/+`, etc.).

- [ ] **Step 2: Verify both files are clean**

Run: `grep -nE 'PPNAM/\{|/status"|station_1/status' MQTT_Design.md HANDSCANNER_MQTT_FLOW.md`
Expected: zero matches. If `HANDSCANNER_MQTT_FLOW.md` shows any, apply the same mapping there.

- [ ] **Step 3: Commit**

```bash
git add MQTT_Design.md HANDSCANNER_MQTT_FLOW.md
git commit -m "docs: finish namespaced topic alignment in design and flow docs"
```

---

### Task 5: Link the contract family together and commit the pending `MQTT_CONTRACT.md` edits

`MQTT_CONTRACT.md` has uncommitted edits from 2026-08-24 (fleet-structure reference, case-sensitivity fix, scanner-id note). Fold in a pointer to the Rev 2 contract and commit it all.

**Files:**
- Modify: `MQTT_CONTRACT.md` (top preamble, after the fleet-structure paragraph added 2026-08-24)

- [ ] **Step 1: Add the Rev 2 pointer**

After the preamble paragraph that references `MQTT_TOPIC_STRUCTURE.md`, insert:

```markdown
Rev 2 goods receiving and schema 4.1 authentication add further message types on
these same topic shapes; they are specified in
`docs rev 2/Station1_MQTT_Contract_Rev_2.md`, which is normative for the Rev 2
message families. This document remains normative for the pre-Rev-2 workflow
message types and the topic grammar.
```

- [ ] **Step 2: Commit (this file only — not `graphify-out/`)**

```bash
git add MQTT_CONTRACT.md
git commit -m "docs: reference fleet topic structure and Rev 2 contract"
```

---

### Task 6: Android — converge the SCRAM proof response on `scram_proof_result`

Decision 2026-08-25: the Android client changes. `AuthClient.kt` awaits `operator_context` after `scram_proof_requested`; the backend (and Station 2's authoritative v4.1 contract, and Station 4) responds on `scram_proof_result`. Payload fields are compatible (verified during planning: the backend's proof response carries `serverSignature`, `operatorSessionId`, operator identity, `role`, `sessionState`, `allowedTabs`). Repo: `C:\Dev\Clients\PPNAM\Andriod\PPNAM_Station_1_AA`.

**Files:**
- Modify: `app/src/main/java/com/mitas/ppnam/station1/AuthClient.kt` (kdoc line ~27, call site line ~107)
- Modify: `LOGIN_MQTT_CONTRACT.md` (topics table + payload section)

**Interfaces:**
- Consumes: existing `AuthClient.request(requestType, responseType, payload, callback)` helper — only the `responseType` argument changes.
- Produces: the login flow `scram_proof_requested` → `res/scram_proof_result`; badge login and logout keep `operator_context`.

- [ ] **Step 1: Update `AuthClient.kt`**

In the class kdoc, change:

```kotlin
 *   req/scram_proof_requested  -> res/scram_proof_result
```

At the proof call site (line ~107), change:

```kotlin
            request("scram_proof_requested", "scram_proof_result", proofPayload) { proofResult ->
```

Nothing else changes — the payload parser stays as-is because the field set is identical.

- [ ] **Step 2: Update `LOGIN_MQTT_CONTRACT.md`**

This file already has uncommitted 2026-08-24 edits (fleet-structure reference) — keep them. In the Topics table, change the `scram_proof_requested` row's response from `operator_context` to `scram_proof_result`. In the Payloads section, rename the response description accordingly: the `serverSignature`-bearing SCRAM response is now `scram_proof_result` (same fields); `operator_context` remains the badge-login/logout response. Add a line to the doc's opening paragraph: "2026-08-25: the SCRAM proof response converged on `scram_proof_result`, matching the Station 2 v4.1 authentication authority and the Station 1 Windows backend (REV2)." Also delete or update the opening claim that "The Station 1 Windows app does not yet implement the station side of these messages" — the REV2 backend now implements all four flows.

- [ ] **Step 3: Build and run unit tests**

Run: `./gradlew.bat testDebugUnitTest assembleDebug`
Expected: BUILD SUCCESSFUL. (There are no AuthClient unit tests; `MqttTopicsTest` must still pass.)

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/mitas/ppnam/station1/AuthClient.kt LOGIN_MQTT_CONTRACT.md
git commit -m "fix(auth): await scram_proof_result per Station 2 v4.1 authority"
```

---

### Task 7: Update the canonical structure doc and final cross-repo sweep

**Files:**
- Modify: `C:\Dev\Clients\PPNAM\MQTT_TOPIC_STRUCTURE.md` (per-station contract table; not a git repo — no commit)

- [ ] **Step 1: Point Station 1's row at the Rev 2 contract**

In the per-station contracts table, replace the Station 1 row with:

```markdown
| Station 1 | `Windows/PPNAM-Station-1-App/MQTT_CONTRACT.md` (topic grammar + pre-Rev-2 workflows), `Windows/PPNAM-Station-1-App/docs rev 2/Station1_MQTT_Contract_Rev_2.md` (Rev 2 receiving + schema 4.1 authentication), `Andriod/PPNAM_Station_1_AA/LOGIN_MQTT_CONTRACT.md` (Android login client) |
```

- [ ] **Step 2: Final verification sweep**

From `C:\Dev\Clients\PPNAM`:

- `grep -rnE 'PPNAM/\{' "Windows/PPNAM-Station-1-App" --include=*.md | grep -v graphify | grep -v "Migration from the previous contract"` → only historical/migration-section references may remain (MQTT_CONTRACT.md §8 documents the old layout deliberately — leave it).
- `dotnet test` in the Windows repo → PASS.
- `git -C Windows/PPNAM-Station-1-App status -sb` and `git -C Andriod/PPNAM_Station_1_AA status -sb` → only `graphify-out/` (untracked) and unrelated pre-existing dirt remain; all plan files committed.

- [ ] **Step 3: Report**

Summarize for Jonathan: what changed in each repo, that commits are local (not pushed), and that end-to-end login testing on the C72 (device `scanner_5c64df8d86a8`, see memory: Station 1 scanner testing) is now unblocked and is the recommended next verification.
