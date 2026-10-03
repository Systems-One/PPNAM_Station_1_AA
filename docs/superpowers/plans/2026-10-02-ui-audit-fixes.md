# Station 1 UI Audit Fixes Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Close every Station 1 finding from the 2026-10-01 handheld UI audit (station1-01..21 plus the static rows that apply to S1) and make Station 1's Login / Settings / dialog code the canonical version Stations 3 and 5 copy.

**Architecture:** Station 1 is an XML + ViewBinding app with five activities (Login, Main, TagAssignment, Offload, Settings). The fixes add four small shared helpers — `SystemBars.kt` (one edge-to-edge + IME-inset setup), `EditorActions.kt` (Enter/IME submit), `AppDialogs.kt` (M3 dialogs, red destructive confirm) and `PinLockout.kt` (persisted supervisor PIN lockout, pure Kotlin) — then rework each activity against them. Settings moves from "Save & Restart" to "Test & Apply" (test in place, keep the session, visible confirmation). Strings become the single glossary for pill, timeout, dialog and PIN copy.

**Tech Stack:** Kotlin 2.2 / AGP 9.2 / Gradle 9.4, AppCompat 1.7, Material Components 1.12 (Material 3 theme), ViewBinding, HiveMQ MQTT client, JUnit 4 JVM unit tests (no Robolectric, no UI-test infra beyond an empty Espresso scaffold).

**Spec:** `C:\Users\Jonathan\AppData\Local\Temp\claude\C--Dev-Clients-PPNAM\ba7a1680-4205-4b04-bcb6-1b1f23c94914\scratchpad\audit\CONSOLIDATED_REPORT.md` (sections 3, 4 "Station 1" + static rows, 5, 6, 7), with `station1.md` and `static_consistency.md` in the same folder.

## Global Constraints

- Repo root: `C:\Dev\Clients\PPNAM\Station 1\PPNAM_Station_1_AA`. Sources: `app\src\main\java\com\mitas\ppnam\station1aa\`; layouts `app\src\main\res\layout\`; unit tests `app\src\test\java\com\mitas\ppnam\station1aa\`. **Ignore the stale root-level `src\` folder** — it is not built.
- Package `com.mitas.ppnam.station1aa`; app id unchanged; versionCode/versionName unchanged (4 / 1.3.1).
- Build (from repo root, PowerShell): `.\gradlew.bat :app:assembleDebug --offline` (drop `--offline` only if dependency resolution fails). APK: `app\build\outputs\apk\debug\app-debug.apk`. Unit tests: `.\gradlew.bat :app:testDebugUnitTest --offline`.
- Emulator verification build: `.\gradlew.bat :app:assembleDebug -PemulatorNoJni --offline` (Task 1 adds the flag; the Chainway aar is ARM-only). Install: `& "C:\Users\Jonathan\AppData\Local\Android\Sdk\platform-tools\adb.exe" -s emulator-5554 install -r -g app\build\outputs\apk\debug\app-debug.apk`. If install fails with `INSTALL_FAILED_UPDATE_INCOMPATIBLE`, run `adb -s emulator-5554 uninstall com.mitas.ppnam.station1aa` first and re-enter Settings (host `10.0.2.2`, port `9001`, WebSocket ON, TLS OFF, user `test`, password `test`, PIN `079545`). Logins `operator1`/`pass`, `manager1`/`secret`; badge `BADGE000000000000000001`. Backend modes: `python <SP>\fake_stations\set_mode.py --device scanner_59fcb3c4129d --mode error|timeout|clear` where `<SP>` = `C:\Users\Jonathan\AppData\Local\Temp\claude\C--Dev-Clients-PPNAM\ba7a1680-4205-4b04-bcb6-1b1f23c94914\scratchpad`.
- Unit tests are plain JUnit 4 on the JVM with the mockable android.jar: anything that touches Android classes must be split so the logic is a pure Kotlin unit. Where no JVM test is feasible the "test" step is the documented manual verification on `emulator-5554` plus the `assembleDebug` compile check — stated explicitly in each such task.
- OUT OF SCOPE (do not change): `BrokerSettings` defaults (`mqtt.sysone.co.za`, 443, WebSocket on, TLS on, blank credentials); any MQTT topic, payload, schema version or SCRAM code; session persistence across process restart; the station-offline overlay. Do not touch `.idea\deploymentTargetSelector.xml` (pre-existing dirty file).
- View ids the campaign scripts under `tools\test_campaign` rely on MUST keep their ids: `etUsername`, `etPassword`, `btnLogin`, `tvLoginError`, `tvStationOfflineBanner`, `btnSettings`, `tileTagAssignment`, `tileOffload`, `tvOperator`, `layoutOperator`, `etTag`, `etBarcode`, `btnMatchPallet`, `btnConfirmOffload`, `etBagWeight`, `tvLastTag`, `tvSendStatus`, `etTagCount`, `tvTagCountError`, `etPin`, `btnUnlock`, `tvPinError`, `tvPinLockout`, `etBrokerHost`, `etBrokerPort`, `etAutoLogout`, `btnSaveSettings`, `tvDeviceId`, `pillBroker`, `pillStation`, `connectionPill`.
- Copy (verbatim): pill `Offline` / `Reconnecting` / `Connected` / `Station 1 offline`; Diagnostics broker row uses the same three words, station row `Online` / `Offline` / `Unknown`; timeout `Station 1 did not respond. Check the station and retry.` with a `Retry` button; `Close the app?` [Stay | Close] on Login and Home; `Log out?` [Cancel | Log out]; login errors `Please fill in all fields`, `Incorrect username or password`; PIN `Incorrect PIN. N attempts left before lockout.` / `Too many attempts. Try again in Ns.`; Settings button `Test & Apply`, states `Testing connection…` / `Connected — settings saved`; inactivity `Signed out after 1 minute of inactivity.` / `Signed out after N minutes of inactivity.`
- Colours: errors in danger red `#E25C5C` (`@color/danger`); primary/brand `#1B4DA0` (`@color/primary_action`). No new colours.
- Every `<activity>` is `android:screenOrientation="portrait"` and `android:windowSoftInputMode="stateHidden|adjustResize"`.
- Git: branch `fix/ui-audit-2026-10-02` off `master`. Commit after every task; never `git add -A` — add only the task's files. Every commit message ends with:
  ```
  Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>
  Claude-Session: https://claude.ai/code/session_01ExhLEukYAu1CqjJUWPR64Q
  ```

## Review Focus

1. Supervisor PIN lockout after process death: five wrong PINs, kill the app, reopen Settings within 30 s — the gate must still be locked and the correct PIN still refused (`PinLockoutTest`, Task 6).
2. Test & Apply with a blank password and no stored credential: must show a field error and never call `MqttManager.connect()` (manual check in Task 10; `BrokerSettings.hasBrokerCredential` is the guard).
3. Hardware Enter on the C72 keypad delivers both KEY_DOWN and KEY_UP through `onEditorAction`: the submit must fire exactly once (`EditorActionsTest`, Task 5).
4. Tag Assignment Retry after a timeout when a newer tag was scanned meanwhile: Retry must re-send the newest tag only, never the one that timed out (manual check in Task 12 against the fake backend log).
5. A `res/request_rejected` envelope rejection during login (schema/replay) must not be reported as "Incorrect username or password" (`AuthFailureTest`, Task 7).

## File map

| File | Responsibility after this plan |
|---|---|
| `app\src\main\AndroidManifest.xml` | portrait lock + `stateHidden\|adjustResize` on all five activities |
| `app\build.gradle.kts` | `-PemulatorNoJni` strips ARM .so files for emulator builds |
| `…\SystemBars.kt` | `applyAppSystemBars()`, `View.padForSystemBarsAndIme()`, `View.scrollIntoView()` |
| `…\AppDialogs.kt` (new) | `neutralDialog()`, `destructiveDialog()`, `showExitAppDialog()`, `showLogoutDialog()` |
| `…\EditorActions.kt` (new) | `EditorActions.classify()` + `TextView.setOnSubmit()` |
| `…\ClickDebouncer.kt` (new) | `ClickDebouncer` + `View.setDebouncedClickListener()` |
| `…\PinLockout.kt` (new), `…\PrefsPinLockoutStore.kt` (new) | persisted 5-attempt / 30 s supervisor lockout |
| `…\AuthFailure.kt` (new), `…\AuthClient.kt` | classified login failures (no protocol text to operators) |
| `…\LoginActivity.kt`, `res\layout\activity_login.xml` | canonical login: error above fields, password toggle, Log In kept above the keyboard |
| `…\MainActivity.kt`, `res\layout\activity_main.xml` | Back → "Close the app?", debounced tiles, gear icon, chip constraint |
| `…\SettingsActivity.kt`, `res\layout\activity_settings.xml` | persisted PIN gate with ticker; Test & Apply with validation + confirmation |
| `…\OffloadActivity.kt`, `res\layout\activity_offload.xml` | spinner + Retry rows, timeout wording, stable validation row, close reason in dialog |
| `…\TagAssignmentActivity.kt`, `res\layout\activity_tag_assignment.xml` | spinner + Retry, state across recreation |
| `…\ScannerApp.kt` | settings-tag receiver only acts while the app is in front |
| `…\SessionGuard.kt`, `…\ConnectionPillView.kt`, `res\values\strings.xml`, `res\values\themes.xml`, `res\drawable\ic_settings.xml` (new) | strings glossary, plurals, M3 dialog theme, button styles, gear icon |
| `res\values-night\themes.xml` | deleted |

---

### Task 1: Branch, baseline build, emulator build flag

**Files:**
- Modify: `app\build.gradle.kts:67-70`

**Interfaces:**
- Produces: Gradle property `emulatorNoJni` used by every later verification step.

- [x] **Step 1: Record the working tree and branch**

Run from the repo root:
```powershell
git status --porcelain
```
Expected output (pre-existing, untouched for the whole plan):
```
 M .idea/deploymentTargetSelector.xml
```
Then:
```powershell
git switch -c fix/ui-audit-2026-10-02
```
Expected: `Switched to a new branch 'fix/ui-audit-2026-10-02'` (current branch was `master`, HEAD `4da3d6b`).

- [x] **Step 2: Baseline build and tests**

```powershell
.\gradlew.bat :app:assembleDebug --offline
.\gradlew.bat :app:testDebugUnitTest --offline
```
Expected: both `BUILD SUCCESSFUL`. If `--offline` fails on dependency resolution, rerun without it once (populates the cache), then keep `--offline`.

- [x] **Step 3: Add the emulator build flag**

In `app\build.gradle.kts` replace lines 67-70:
```kotlin
    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
```
with:
```kotlin
    packaging {
        jniLibs {
            useLegacyPackaging = true
            // The Chainway DeviceAPI aar ships ARM-only .so files, which an x86_64 emulator
            // refuses (INSTALL_FAILED_NO_MATCHING_ABIS). `-PemulatorNoJni` strips them for
            // emulator-only verification builds; emulator scanning is driven by adb broadcasts,
            // never by the SDK, so nothing on the emulator loads the library.
            if (project.hasProperty("emulatorNoJni")) excludes += "**/*.so"
        }
```

- [x] **Step 4: Verify both build variants**

```powershell
.\gradlew.bat :app:assembleDebug -PemulatorNoJni --offline
& "C:\Users\Jonathan\AppData\Local\Android\Sdk\platform-tools\adb.exe" -s emulator-5554 install -r -g app\build\outputs\apk\debug\app-debug.apk
```
Expected: `BUILD SUCCESSFUL`, then `Success`. If the install reports `INSTALL_FAILED_UPDATE_INCOMPATIBLE`, run `adb -s emulator-5554 uninstall com.mitas.ppnam.station1aa` and install again, then re-enter the broker settings (Global Constraints). Launch the app: `adb -s emulator-5554 shell am start -n com.mitas.ppnam.station1aa/.LoginActivity` — the Login screen appears, pill shows `Connected` within ~5 s.

- [x] **Step 5: Commit**

```powershell
git add app/build.gradle.kts
git commit -m "build: add -PemulatorNoJni flag for x86 emulator verification builds

Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01ExhLEukYAu1CqjJUWPR64Q"
```

---

### Task 2: Portrait lock and `adjustResize` on every activity (Tier 1)

Closes: station1-04 (rotation re-lock, root cause), station1-09 (rotation state loss, root cause), station1-02 (part: `adjustPan` on Settings/Offload/TagAssignment), §7 items 1-2.

**Files:**
- Modify: `app\src\main\AndroidManifest.xml:37-68`

**Interfaces:**
- Produces: nothing new; every activity now declares `screenOrientation="portrait"` and `windowSoftInputMode="stateHidden|adjustResize"`.

- [x] **Step 1: Replace the five activity declarations**

Replace lines 37-68 of `AndroidManifest.xml` (from `<activity android:name=".LoginActivity"` to the closing tag of `.SettingsActivity`) with:

```xml
        <!-- Every screen is portrait: the C72 has auto-rotate on, and rotation re-locked Settings,
             wiped Tag Assignment's last scan and put dialogs under the keyboard (audit group e).
             A handheld scanner gains nothing from landscape. -->
        <activity
            android:name=".LoginActivity"
            android:exported="true"
            android:label="Log In"
            android:screenOrientation="portrait"
            android:windowSoftInputMode="stateHidden|adjustResize"
            tools:ignore="LockedOrientationActivity">
            <intent-filter>
                <action android:name="android.intent.action.MAIN" />
                <category android:name="android.intent.category.LAUNCHER" />
            </intent-filter>
        </activity>

        <activity
            android:name=".MainActivity"
            android:exported="false"
            android:screenOrientation="portrait"
            android:windowSoftInputMode="stateHidden|adjustResize"
            tools:ignore="LockedOrientationActivity" />

        <activity
            android:name=".TagAssignmentActivity"
            android:exported="false"
            android:label="Tag Assignment"
            android:screenOrientation="portrait"
            android:theme="@style/Theme.SysOneScanner"
            android:windowSoftInputMode="stateHidden|adjustResize"
            tools:ignore="LockedOrientationActivity" />

        <activity
            android:name=".OffloadActivity"
            android:exported="false"
            android:label="Offload"
            android:screenOrientation="portrait"
            android:theme="@style/Theme.SysOneScanner"
            android:windowSoftInputMode="stateHidden|adjustResize"
            tools:ignore="LockedOrientationActivity" />

        <activity
            android:name=".SettingsActivity"
            android:exported="false"
            android:screenOrientation="portrait"
            android:theme="@style/Theme.SysOneScanner"
            android:windowSoftInputMode="stateHidden|adjustResize"
            tools:ignore="LockedOrientationActivity" />
```

- [x] **Step 2: Compile check**

```powershell
.\gradlew.bat :app:assembleDebug -PemulatorNoJni --offline
```
Expected: `BUILD SUCCESSFUL`.

- [x] **Step 3: Manual verification on emulator-5554**

Install (Global Constraints). Then:
```powershell
$adb = "C:\Users\Jonathan\AppData\Local\Android\Sdk\platform-tools\adb.exe"
& $adb -s emulator-5554 shell settings put system accelerometer_rotation 0
& $adb -s emulator-5554 shell settings put system user_rotation 1
cmd /c "$adb -s emulator-5554 exec-out screencap -p > $env:TEMP\s1_rotate.png"
& $adb -s emulator-5554 shell settings put system user_rotation 0
```
Expected: the screenshot is still portrait (1080 wide, 1920 tall) on Login, and the same holds after logging in (Main), opening Tag Assignment, Offload and Settings. Settings unlocked with a value typed into Host, rotate → the form stays unlocked and the typed value survives (station1-04 symptom gone).

- [x] **Step 4: Commit**

```powershell
git add app/src/main/AndroidManifest.xml
git commit -m "fix(ui): lock every activity to portrait and resize for the keyboard

Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01ExhLEukYAu1CqjJUWPR64Q"
```

---

### Task 3: One system-bars + IME insets helper on every activity (Tier 1)

Closes: station1-02 (remainder: `enableEdgeToEdge()` + `systemBars()`-only padding defeated `adjustResize` on Offload; Settings/TagAssignment get the same treatment), station1-14 (nav bar flips black / light-grey), §7 item 3, static-09's nav-bar half of group (h).

**Files:**
- Modify: `app\src\main\java\com\mitas\ppnam\station1aa\SystemBars.kt` (whole file)
- Modify: `…\MainActivity.kt:34-45`, `…\OffloadActivity.kt:89-102`, `…\TagAssignmentActivity.kt:48-61`, `…\SettingsActivity.kt:35-37`, `…\LoginActivity.kt:71-73`
- Modify: `app\src\main\res\values\themes.xml:23-25`

**Interfaces:**
- Produces: `fun ComponentActivity.applyAppSystemBars()` (call BEFORE `setContentView`), `fun View.padForSystemBarsAndIme(onImeVisibilityChanged: ((Boolean) -> Unit)? = null)`, `fun View.scrollIntoView()`. Later tasks' full-file rewrites of the activities already use these.

- [x] **Step 1: Rewrite `SystemBars.kt`**

Replace the whole file with:

```kotlin
package com.mitas.ppnam.station1aa

import android.app.Activity
import android.graphics.Rect
import android.view.View
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat

/**
 * Forces light (white) status bar icons, matching this app's always-dark background.
 * enableEdgeToEdge()'s own light/dark heuristic doesn't resolve consistently across every
 * screen, leaving status bar icons unreadable on some activities - this makes it explicit.
 */
fun Activity.forceLightStatusBarIcons() {
    WindowCompat.getInsetsController(window, window.decorView).isAppearanceLightStatusBars = false
}

/**
 * One edge-to-edge setup for every screen: both system bars painted window_background with
 * light icons. Before this, Login/Settings drew a black navigation bar while the edge-to-edge
 * screens got the system's light-grey contrast scrim, so the bottom edge flashed on every
 * navigation (audit station1-14). Call before setContentView.
 */
fun ComponentActivity.applyAppSystemBars() {
    val scrim = getColor(R.color.window_background)
    enableEdgeToEdge(
        statusBarStyle = SystemBarStyle.dark(scrim),
        navigationBarStyle = SystemBarStyle.dark(scrim),
    )
}

/**
 * Pads this root view by the system bars AND the soft keyboard. Once a window is edge-to-edge
 * (decorFitsSystemWindows = false) `adjustResize` no longer shrinks it for the IME, so the
 * keyboard inset has to be applied here — otherwise the scroll container keeps its full height
 * and the primary button stays under the keyboard (audit station1-02). Padding declared in XML
 * is preserved underneath the insets. [onImeVisibilityChanged] fires on each change so a screen
 * can bring its primary button into view when the keyboard opens.
 */
fun View.padForSystemBarsAndIme(onImeVisibilityChanged: ((visible: Boolean) -> Unit)? = null) {
    val base = Rect(paddingLeft, paddingTop, paddingRight, paddingBottom)
    var imeWasVisible = false
    ViewCompat.setOnApplyWindowInsetsListener(this) { v, insets ->
        val bars = insets.getInsets(
            WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime()
        )
        v.setPadding(
            base.left + bars.left,
            base.top + bars.top,
            base.right + bars.right,
            base.bottom + bars.bottom,
        )
        val imeVisible = insets.isVisible(WindowInsetsCompat.Type.ime())
        if (imeVisible != imeWasVisible) {
            imeWasVisible = imeVisible
            onImeVisibilityChanged?.invoke(imeVisible)
        }
        insets
    }
}

/** Asks the enclosing scroll container to scroll just enough that this whole view is on screen. */
fun View.scrollIntoView() {
    requestRectangleOnScreen(Rect(0, 0, width, height), false)
}
```

- [x] **Step 2: Use the helper in `MainActivity.kt`**

Replace lines 34-45:
```kotlin
        binding = ActivityMainBinding.inflate(layoutInflater)
        enableEdgeToEdge()
        setContentView(binding.root)
        forceLightStatusBarIcons()

        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_HIDDEN)

        ViewCompat.setOnApplyWindowInsetsListener(binding.main) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }
```
with:
```kotlin
        applyAppSystemBars()
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        forceLightStatusBarIcons()
        binding.main.padForSystemBarsAndIme()

        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_HIDDEN)
```
and delete the now-unused imports `androidx.activity.enableEdgeToEdge`, `androidx.core.view.ViewCompat`, `androidx.core.view.WindowInsetsCompat` (lines 7-9).

- [x] **Step 3: Use the helper in `OffloadActivity.kt`**

Replace lines 89-92:
```kotlin
        binding = ActivityOffloadBinding.inflate(layoutInflater)
        enableEdgeToEdge()
        setContentView(binding.root)
        forceLightStatusBarIcons()
```
with:
```kotlin
        applyAppSystemBars()
        binding = ActivityOffloadBinding.inflate(layoutInflater)
        setContentView(binding.root)
        forceLightStatusBarIcons()
        binding.main.padForSystemBarsAndIme()
```
and delete lines 98-102 (the `ViewCompat.setOnApplyWindowInsetsListener(binding.main) { … }` block) plus the imports `androidx.activity.enableEdgeToEdge`, `androidx.core.view.ViewCompat`, `androidx.core.view.WindowInsetsCompat` (lines 12, 14, 15).

- [x] **Step 4: Use the helper in `TagAssignmentActivity.kt`**

Replace lines 48-51:
```kotlin
        binding = ActivityTagAssignmentBinding.inflate(layoutInflater)
        enableEdgeToEdge()
        setContentView(binding.root)
        forceLightStatusBarIcons()
```
with:
```kotlin
        applyAppSystemBars()
        binding = ActivityTagAssignmentBinding.inflate(layoutInflater)
        setContentView(binding.root)
        forceLightStatusBarIcons()
        binding.main.padForSystemBarsAndIme()
```
and delete lines 57-61 (the insets listener block) plus the imports on lines 11, 13, 14 (`enableEdgeToEdge`, `ViewCompat`, `WindowInsetsCompat`).

- [x] **Step 5: Use the helper in `SettingsActivity.kt` and `LoginActivity.kt`**

`SettingsActivity.kt` lines 35-37:
```kotlin
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        forceLightStatusBarIcons()
```
become:
```kotlin
        applyAppSystemBars()
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        forceLightStatusBarIcons()
        binding.main.padForSystemBarsAndIme()
```

`LoginActivity.kt` lines 71-73:
```kotlin
        binding = ActivityLoginBinding.inflate(layoutInflater)
        setContentView(binding.root)
        forceLightStatusBarIcons()
```
become:
```kotlin
        applyAppSystemBars()
        binding = ActivityLoginBinding.inflate(layoutInflater)
        setContentView(binding.root)
        forceLightStatusBarIcons()
        binding.main.padForSystemBarsAndIme()
```

- [x] **Step 6: Theme fallback for the first frame**

In `app\src\main\res\values\themes.xml` replace lines 23-25:
```xml
        <!-- Status Bar -->
        <item name="android:statusBarColor">@color/window_background</item>
        <item name="android:windowLightStatusBar">false</item>
```
with:
```xml
        <!-- System bars: the same window colour top and bottom. applyAppSystemBars() sets the
             same values at runtime; these cover the first frame before onCreate runs. -->
        <item name="android:statusBarColor">@color/window_background</item>
        <item name="android:windowLightStatusBar">false</item>
        <item name="android:navigationBarColor">@color/window_background</item>
        <item name="android:windowLightNavigationBar" tools:targetApi="o_mr1">false</item>
```

- [x] **Step 7: Compile and verify the keyboard matrix rows**

```powershell
.\gradlew.bat :app:assembleDebug -PemulatorNoJni --offline
```
Expected: `BUILD SUCCESSFUL`. Install and check:

1. Settings → PIN `079545` → tap Username (broker) → keyboard up. Run `adb -s emulator-5554 shell dumpsys window | findstr ITYPE_IME` and note the IME top (≈1023 for the text keyboard). Swipe up inside the form (`adb shell input swipe 540 900 540 300 300`) → `adb shell uiautomator dump /sdcard/ui.xml; adb pull /sdcard/ui.xml $env:TEMP\ui.xml` → the node with `resource-id=".../btnSaveSettings"` has bottom bound < IME top (it scrolls into view; before this task it was fixed at y 1612-1776).
2. Offload → scan a tag and a barcode (`adb shell am broadcast -a com.rscja.scanner.action.scanner.RFID --es data E2000000000000000000AAA1` and `… -a com.scanner.broadcast --es data PAL-0001`) → Match Pallet → tap Bag Weight → swipe up → `btnConfirmOffload` bottom < IME top.
3. Navigate Login → Main → Tag Assignment → back → Settings: the navigation bar stays the same dark `#07101A` on every screen (screenshot each; no light-grey bar on Main/Tag/Offload).

- [x] **Step 8: Commit**

```powershell
git add app/src/main/java/com/mitas/ppnam/station1aa/SystemBars.kt app/src/main/java/com/mitas/ppnam/station1aa/MainActivity.kt app/src/main/java/com/mitas/ppnam/station1aa/OffloadActivity.kt app/src/main/java/com/mitas/ppnam/station1aa/TagAssignmentActivity.kt app/src/main/java/com/mitas/ppnam/station1aa/SettingsActivity.kt app/src/main/java/com/mitas/ppnam/station1aa/LoginActivity.kt app/src/main/res/values/themes.xml
git commit -m "fix(ui): pad every screen for the keyboard and paint both system bars consistently

Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01ExhLEukYAu1CqjJUWPR64Q"
```

---

### Task 4: Theme — dark-mode regression, button casing, M3 dialogs, gear icon (Tier 1 + Tier 3 item 22)

Closes: static-09 (`values-night` regression), station1-12 (BACK TO SCAN / LOG OUT all-caps), station1-20 + static-15 (square edge-to-edge dialogs, both buttons brand-coloured), static-12 (wrench → gear, tint differs Login vs Home), "Log out" casing (group i).

**Files:**
- Delete: `app\src\main\res\values-night\themes.xml`
- Modify: `app\src\main\res\values\themes.xml` (whole file)
- Create: `app\src\main\res\drawable\ic_settings.xml`
- Create: `app\src\main\java\com\mitas\ppnam\station1aa\AppDialogs.kt`
- Modify: `app\src\main\res\layout\activity_offload.xml:256-263`, `app\src\main\res\layout\activity_settings.xml:499-508`, `app\src\main\res\layout\activity_login.xml:31-38`, `app\src\main\res\layout\activity_main.xml:78-89`
- Modify: `app\src\main\res\values\strings.xml:77`
- Modify: `…\LoginActivity.kt:213-220`, `…\MainActivity.kt:91-105`, `…\SettingsActivity.kt:177-191`

**Interfaces:**
- Produces: `Activity.neutralDialog(): MaterialAlertDialogBuilder`, `Activity.destructiveDialog(): MaterialAlertDialogBuilder`, `Activity.showExitAppDialog()`, `Activity.showLogoutDialog()`; styles `AppAlertDialogTheme`, `AppAlertDialogTheme.Destructive`, `Widget.SysOneScanner.Button.TextButton`, `Widget.SysOneScanner.Button.OutlinedButton`; drawable `@drawable/ic_settings`. Tasks 8, 10, 11 call these.

- [x] **Step 1: Delete the night theme**

```powershell
git rm app/src/main/res/values-night/themes.xml
```
(The file re-declared `Base.Theme.SysOneScanner` with no items, so in system dark mode every colour reverted to Material 3 purple.)

- [x] **Step 2: Rewrite `themes.xml`**

Replace the whole file with:

```xml
<resources xmlns:tools="http://schemas.android.com/tools">
    <!-- Base application theme. The app is always dark; there is deliberately NO values-night
         override (it used to re-declare this style empty and reset every colour in system dark
         mode — audit static-09). -->
    <style name="Base.Theme.SysOneScanner" parent="Theme.Material3.DayNight.NoActionBar">
        <!-- Base / Surface Colours -->
        <item name="android:windowBackground">@color/window_background</item>
        <item name="colorSurface">@color/card_background</item>
        <item name="colorSurfaceVariant">@color/card_background_alt</item>
        <!-- Material3 defaults colorOnSurface(Variant) to near-black; left unset, every
             MaterialComponents OutlinedBox field's resting hint (which resolves against
             colorOnSurface) renders near-invisible on this dark palette. -->
        <item name="colorOnSurface">@color/text_primary</item>
        <item name="colorOnSurfaceVariant">@color/text_muted</item>
        <item name="colorOutline">@color/border_primary</item>

        <!-- Primary / Accent -->
        <item name="colorPrimary">@color/primary_action</item>
        <item name="colorSecondary">@color/accent_action</item>
        <item name="colorError">@color/danger</item>

        <!-- Text Colours -->
        <item name="android:textColorPrimary">@color/text_primary</item>
        <item name="android:textColorSecondary">@color/text_muted</item>

        <!-- System bars: the same window colour top and bottom. applyAppSystemBars() sets the
             same values at runtime; these cover the first frame before onCreate runs. -->
        <item name="android:statusBarColor">@color/window_background</item>
        <item name="android:windowLightStatusBar">false</item>
        <item name="android:navigationBarColor">@color/window_background</item>
        <item name="android:windowLightNavigationBar" tools:targetApi="o_mr1">false</item>

        <!-- Dialogs: Material 3 (28dp corners, 24dp inset), neutral buttons; the destructive
             variant below colours the confirm red. Both attrs so AppCompat and Material
             builders agree. -->
        <item name="materialAlertDialogTheme">@style/AppAlertDialogTheme</item>
        <item name="alertDialogTheme">@style/AppAlertDialogTheme</item>
        <item name="android:alertDialogTheme">@style/AppAlertDialogTheme</item>
    </style>

    <style name="Theme.SysOneScanner" parent="Base.Theme.SysOneScanner" />

    <!-- Matches Station 2's M3 AlertDialog: card surface, rounded, dismiss in text colour. -->
    <style name="AppAlertDialogTheme" parent="ThemeOverlay.Material3.MaterialAlertDialog">
        <item name="colorSurface">@color/card_background</item>
        <item name="colorSurfaceContainerHigh">@color/card_background</item>
        <item name="colorOnSurface">@color/text_primary</item>
        <item name="colorOnSurfaceVariant">@color/text_muted</item>
        <item name="colorPrimary">@color/text_primary</item>
        <item name="android:textColorPrimary">@color/text_primary</item>
        <item name="android:textColorSecondary">@color/text_muted</item>
        <item name="buttonBarPositiveButtonStyle">@style/Widget.SysOneScanner.Button.DialogButton</item>
        <item name="buttonBarNegativeButtonStyle">@style/Widget.SysOneScanner.Button.DialogButton</item>
        <item name="buttonBarNeutralButtonStyle">@style/Widget.SysOneScanner.Button.DialogButton</item>
    </style>

    <!-- Positive action destroys something (closes the app, ends the session): red confirm. -->
    <style name="AppAlertDialogTheme.Destructive">
        <item name="buttonBarPositiveButtonStyle">@style/Widget.SysOneScanner.Button.DialogButton.Destructive</item>
    </style>

    <style name="Widget.SysOneScanner.Button.DialogButton" parent="Widget.Material3.Button.TextButton.Dialog">
        <item name="android:textColor">@color/text_primary</item>
        <item name="android:textAllCaps">false</item>
    </style>

    <style name="Widget.SysOneScanner.Button.DialogButton.Destructive">
        <item name="android:textColor">@color/danger</item>
    </style>

    <!-- Title Case everywhere: the MaterialComponents TextButton/OutlinedButton styles the
         layouts used to reference upper-case their labels ("BACK TO SCAN", "LOG OUT"). -->
    <style name="Widget.SysOneScanner.Button.TextButton" parent="Widget.Material3.Button.TextButton">
        <item name="android:textAllCaps">false</item>
    </style>

    <style name="Widget.SysOneScanner.Button.OutlinedButton" parent="Widget.Material3.Button.OutlinedButton">
        <item name="android:textAllCaps">false</item>
    </style>

    <!-- Optical sizing: large text wants slightly negative tracking (letters read too
         far apart as they grow) - tightened here rather than left at the Material
         default, which is tuned for body-size text, not a 20sp+ toolbar title. -->
    <style name="TextAppearance.SysOneScanner.ToolbarTitle" parent="TextAppearance.Material3.TitleLarge">
        <item name="android:letterSpacing">-0.015</item>
        <item name="android:textColor">@color/text_primary</item>
    </style>

    <!-- Station 2's SectionLabel/DiagnosticRow label: labelSmall (11sp medium) with 0.8sp
         tracking (0.8/11 in em), muted, uppercase. -->
    <style name="SettingsSectionLabel">
        <item name="android:textSize">11sp</item>
        <item name="android:fontFamily">sans-serif-medium</item>
        <item name="android:letterSpacing">0.073</item>
        <item name="android:textColor">@color/text_muted</item>
        <item name="android:textAllCaps">true</item>
    </style>
</resources>
```

- [x] **Step 3: Create the gear icon**

Create `app\src\main\res\drawable\ic_settings.xml` (Material "settings" glyph, the same one Compose's `Icons.Filled.Settings` draws in S2/S4/Launcher):

```xml
<?xml version="1.0" encoding="utf-8"?>
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="24dp"
    android:height="24dp"
    android:viewportWidth="24"
    android:viewportHeight="24"
    android:tint="?attr/colorOnSurface">
    <path
        android:fillColor="@android:color/white"
        android:pathData="M19.14,12.94c0.04,-0.3 0.06,-0.61 0.06,-0.94c0,-0.32 -0.02,-0.64 -0.07,-0.94l2.03,-1.58c0.18,-0.14 0.23,-0.41 0.12,-0.61l-1.92,-3.32c-0.12,-0.22 -0.37,-0.29 -0.59,-0.22l-2.39,0.96c-0.5,-0.38 -1.03,-0.7 -1.62,-0.94L14.4,2.81c-0.04,-0.24 -0.24,-0.41 -0.48,-0.41h-3.84c-0.24,0 -0.43,0.17 -0.47,0.41L9.25,5.35C8.66,5.59 8.12,5.92 7.63,6.29L5.24,5.33c-0.22,-0.08 -0.47,0 -0.59,0.22L2.74,8.87C2.62,9.08 2.66,9.34 2.86,9.48l2.03,1.58C4.84,11.36 4.8,11.69 4.8,12s0.02,0.64 0.07,0.94l-2.03,1.58c-0.18,0.14 -0.23,0.41 -0.12,0.61l1.92,3.32c0.12,0.22 0.37,0.29 0.59,0.22l2.39,-0.96c0.5,0.38 1.03,0.7 1.62,0.94l0.36,2.54c0.05,0.24 0.24,0.41 0.48,0.41h3.84c0.24,0 0.44,-0.17 0.47,-0.41l0.36,-2.54c0.59,-0.24 1.13,-0.56 1.62,-0.94l2.39,0.96c0.22,0.08 0.47,0 0.59,-0.22l1.92,-3.32c0.12,-0.22 0.07,-0.47 -0.12,-0.61L19.14,12.94zM12,15.6c-1.98,0 -3.6,-1.62 -3.6,-3.6s1.62,-3.6 3.6,-3.6s3.6,1.62 3.6,3.6S13.98,15.6 12,15.6z" />
</vector>
```

- [x] **Step 4: Swap the wrench for the gear, same tint on both screens**

`activity_login.xml` lines 31-38 become:
```xml
                <ImageButton
                    android:id="@+id/btnSettings"
                    android:layout_width="48dp"
                    android:layout_height="48dp"
                    android:background="?attr/selectableItemBackgroundBorderless"
                    android:contentDescription="Settings"
                    android:src="@drawable/ic_settings"
                    app:tint="@color/text_primary" />
```
`activity_main.xml` lines 78-89 become:
```xml
            <!-- Settings Button -->
            <ImageButton
                android:id="@+id/btnSettings"
                android:layout_width="48dp"
                android:layout_height="48dp"
                android:background="?attr/selectableItemBackgroundBorderless"
                android:contentDescription="Settings"
                android:src="@drawable/ic_settings"
                app:layout_constraintBottom_toBottomOf="parent"
                app:layout_constraintEnd_toStartOf="@id/connectionPill"
                app:layout_constraintTop_toTopOf="parent"
                app:tint="@color/text_primary" />
```

- [x] **Step 5: Title-case button styles in the two layouts**

`activity_offload.xml` lines 256-263: change `style="@style/Widget.MaterialComponents.Button.TextButton"` to `style="@style/Widget.SysOneScanner.Button.TextButton"` (rest of the `btnBackToScan` element unchanged).

`activity_settings.xml` lines 499-508: change `style="@style/Widget.MaterialComponents.Button.OutlinedButton"` to `style="@style/Widget.SysOneScanner.Button.OutlinedButton"` (rest of `btnLogOut` unchanged).

- [x] **Step 6: "Log out" casing**

`strings.xml` line 77: `<string name="btn_log_out">Log Out</string>` → `<string name="btn_log_out">Log out</string>`.

- [x] **Step 7: Create `AppDialogs.kt`**

```kotlin
package com.mitas.ppnam.station1aa

import android.app.Activity
import android.content.Intent
import androidx.appcompat.app.AlertDialog
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/**
 * The app's dialogs, matching Station 2's Material 3 style: rounded, inset from the edges,
 * dismiss in text colour, destructive confirm in danger red (audit station1-20 / static-15).
 * Every AlertDialog in the app goes through one of the two builders so the look cannot drift.
 */

/** Positive action is an ordinary step (Submit, Next Pallet, a close classification). */
fun Activity.neutralDialog(): MaterialAlertDialogBuilder =
    MaterialAlertDialogBuilder(this, R.style.AppAlertDialogTheme)

/** Positive action destroys something (closes the app, ends the session): red confirm. */
fun Activity.destructiveDialog(): MaterialAlertDialogBuilder =
    MaterialAlertDialogBuilder(this, R.style.AppAlertDialogTheme_Destructive)

/** "Close the app?" [Stay | Close] — the same dialog on Login and Home (audit station1-05). */
fun Activity.showExitAppDialog(): AlertDialog =
    destructiveDialog()
        .setTitle(R.string.exit_dialog_title)
        .setMessage(R.string.exit_dialog_message)
        .setPositiveButton(R.string.exit_dialog_close) { _, _ -> finishAffinity() }
        .setNegativeButton(R.string.exit_dialog_stay, null)
        .show()

/** "Log out?" [Cancel | Log out] — best-effort MQTT logout, session cleared, back to Login. */
fun Activity.showLogoutDialog(): AlertDialog =
    destructiveDialog()
        .setTitle(R.string.logout_dialog_title)
        .setMessage(R.string.logout_dialog_message)
        .setPositiveButton(R.string.btn_log_out) { _, _ ->
            AuthClient(this).logout {
                startActivity(Intent(this, LoginActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                })
                finish()
            }
        }
        .setNegativeButton(R.string.btn_cancel, null)
        .show()
```

- [x] **Step 8: Route the three existing dialogs through the helpers**

`LoginActivity.kt` lines 213-220 (`showExitDialog()`): delete the function and change line 102 `onBackPressedDispatcher.addCallback(this) { showExitDialog() }` to `onBackPressedDispatcher.addCallback(this) { showExitAppDialog() }`. Remove the import `androidx.appcompat.app.AlertDialog` (line 12).

`MainActivity.kt` lines 91-105 (`showLogoutDialog()` private function): delete it, and change line 72 `binding.layoutOperator.setOnClickListener { showLogoutDialog() }` to stay as is — it now resolves to the shared `Activity.showLogoutDialog()` extension.

`SettingsActivity.kt` lines 177-191: replace the whole `binding.btnLogOut.setOnClickListener { … }` block with:
```kotlin
        binding.btnLogOut.setOnClickListener { showLogoutDialog() }
```

- [x] **Step 9: Compile and verify**

```powershell
.\gradlew.bat :app:assembleDebug -PemulatorNoJni --offline
```
Expected: `BUILD SUCCESSFUL`. Install; on emulator-5554:
1. `adb -s emulator-5554 shell cmd uimode night yes` → Settings: the Unlock button, switches and field outlines stay brand blue / app colours (no purple). `cmd uimode night no` afterwards.
2. Login → Back: dialog has rounded corners, left/right margins ≈ 24dp (uiautomator bounds x ≈ 72..1008 instead of 27..1053), "Stay" in white text, "Close" in red.
3. Main → tap operator chip: "Log out?" with "Cancel" white and "Log out" red (sentence case).
4. Offload EDIT step: the text button reads "Back to Scan"; Settings session card reads "Log out".
5. Login and Main both show the gear icon in `text_primary`.

- [x] **Step 10: Commit**

```powershell
git add app/src/main/res/values-night/themes.xml app/src/main/res/values/themes.xml app/src/main/res/drawable/ic_settings.xml app/src/main/java/com/mitas/ppnam/station1aa/AppDialogs.kt app/src/main/res/layout/activity_offload.xml app/src/main/res/layout/activity_settings.xml app/src/main/res/layout/activity_login.xml app/src/main/res/layout/activity_main.xml app/src/main/res/values/strings.xml app/src/main/java/com/mitas/ppnam/station1aa/LoginActivity.kt app/src/main/java/com/mitas/ppnam/station1aa/MainActivity.kt app/src/main/java/com/mitas/ppnam/station1aa/SettingsActivity.kt
git commit -m "fix(theme): drop empty night theme, M3 dialogs with red destructive confirm, gear icon, title-case buttons

Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01ExhLEukYAu1CqjJUWPR64Q"
```

---

### Task 5: Enter submits + click debounce helpers (Tier 2, §7 item 9)

Closes: group (b) for S1 (hardware Enter arrives as `IME_NULL`; Offload Done does not confirm; tag-count dialog), station1-06 (double-tap opens a tile twice — helper here, applied in Task 8).

**Files:**
- Create: `app\src\main\java\com\mitas\ppnam\station1aa\EditorActions.kt`
- Create: `app\src\main\java\com\mitas\ppnam\station1aa\ClickDebouncer.kt`
- Test: `app\src\test\java\com\mitas\ppnam\station1aa\EditorActionsTest.kt`, `app\src\test\java\com\mitas\ppnam\station1aa\ClickDebouncerTest.kt`
- Modify: `…\LoginActivity.kt:82-89`, `…\SettingsActivity.kt:59-66`, `…\OffloadActivity.kt:382-389`

**Interfaces:**
- Produces: `EditorActions.classify(actionId: Int, keyCode: Int, keyAction: Int): EditorActions.Result` (`SUBMIT`, `CONSUME`, `IGNORE`); `fun TextView.setOnSubmit(action: () -> Unit)`; `class ClickDebouncer(windowMs: Long = 600L, now: () -> Long)` with `fun accept(): Boolean`; `fun View.setDebouncedClickListener(debouncer: ClickDebouncer, action: () -> Unit)`.

- [x] **Step 1: Write the failing tests**

`EditorActionsTest.kt`:
```kotlin
package com.mitas.ppnam.station1aa

import android.view.KeyEvent
import android.view.inputmethod.EditorInfo
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Gboard's tick arrives as an IME action id; the C72 keypad's Enter (and a scanner-wedge
 * suffix) arrives as IME_NULL with a KEYCODE_ENTER event, once for key-down and once for
 * key-up. The submit must fire exactly once per press.
 */
class EditorActionsTest {

    private val none = KeyEvent.KEYCODE_UNKNOWN

    @Test
    fun `IME Done and Go submit`() {
        assertEquals(EditorActions.Result.SUBMIT, EditorActions.classify(EditorInfo.IME_ACTION_DONE, none, -1))
        assertEquals(EditorActions.Result.SUBMIT, EditorActions.classify(EditorInfo.IME_ACTION_GO, none, -1))
    }

    @Test
    fun `IME Next and Search are left to the framework`() {
        assertEquals(EditorActions.Result.IGNORE, EditorActions.classify(EditorInfo.IME_ACTION_NEXT, none, -1))
        assertEquals(EditorActions.Result.IGNORE, EditorActions.classify(EditorInfo.IME_ACTION_SEARCH, none, -1))
    }

    @Test
    fun `hardware Enter submits on key-down only and swallows key-up`() {
        assertEquals(
            EditorActions.Result.SUBMIT,
            EditorActions.classify(EditorInfo.IME_NULL, KeyEvent.KEYCODE_ENTER, KeyEvent.ACTION_DOWN),
        )
        assertEquals(
            EditorActions.Result.CONSUME,
            EditorActions.classify(EditorInfo.IME_NULL, KeyEvent.KEYCODE_ENTER, KeyEvent.ACTION_UP),
        )
    }

    @Test
    fun `numpad Enter behaves like Enter`() {
        assertEquals(
            EditorActions.Result.SUBMIT,
            EditorActions.classify(EditorInfo.IME_NULL, KeyEvent.KEYCODE_NUMPAD_ENTER, KeyEvent.ACTION_DOWN),
        )
    }

    @Test
    fun `any other key is ignored`() {
        assertEquals(
            EditorActions.Result.IGNORE,
            EditorActions.classify(EditorInfo.IME_NULL, KeyEvent.KEYCODE_TAB, KeyEvent.ACTION_DOWN),
        )
        assertEquals(EditorActions.Result.IGNORE, EditorActions.classify(EditorInfo.IME_NULL, none, -1))
    }
}
```

`ClickDebouncerTest.kt`:
```kotlin
package com.mitas.ppnam.station1aa

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ClickDebouncerTest {

    private var now = 10_000L
    private val debouncer = ClickDebouncer(windowMs = 600L, now = { now })

    @Test
    fun `first click is accepted`() {
        assertTrue(debouncer.accept())
    }

    @Test
    fun `a second click inside the window is dropped`() {
        assertTrue(debouncer.accept())
        now += 100
        assertFalse(debouncer.accept())
        now += 499
        assertFalse(debouncer.accept())
    }

    @Test
    fun `a click after the window is accepted and restarts it`() {
        assertTrue(debouncer.accept())
        now += 600
        assertTrue(debouncer.accept())
        now += 10
        assertFalse(debouncer.accept())
    }
}
```

- [x] **Step 2: Run them to see them fail**

```powershell
.\gradlew.bat :app:testDebugUnitTest --offline --tests "com.mitas.ppnam.station1aa.EditorActionsTest" --tests "com.mitas.ppnam.station1aa.ClickDebouncerTest"
```
Expected: compilation error `Unresolved reference: EditorActions` / `ClickDebouncer`.

- [x] **Step 3: Implement `EditorActions.kt`**

```kotlin
package com.mitas.ppnam.station1aa

import android.view.KeyEvent
import android.view.inputmethod.EditorInfo
import android.widget.TextView

/**
 * One rule for "the operator pressed submit in a text field" (audit group b). Gboard's tick
 * sends IME_ACTION_DONE/GO; the C72's hardware Enter and a scanner-wedge suffix arrive as
 * IME_NULL with a KEYCODE_ENTER event — once for key-down and once for key-up. Only the
 * key-down fires the action; the key-up is consumed so TextView does not also move focus.
 */
object EditorActions {

    enum class Result { SUBMIT, CONSUME, IGNORE }

    fun classify(actionId: Int, keyCode: Int, keyAction: Int): Result {
        if (actionId == EditorInfo.IME_ACTION_DONE || actionId == EditorInfo.IME_ACTION_GO) {
            return Result.SUBMIT
        }
        if (keyCode == KeyEvent.KEYCODE_ENTER || keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER) {
            return if (keyAction == KeyEvent.ACTION_DOWN) Result.SUBMIT else Result.CONSUME
        }
        return Result.IGNORE
    }
}

/** Runs [action] when the IME action button or a hardware Enter submits this field. */
fun TextView.setOnSubmit(action: () -> Unit) {
    setOnEditorActionListener { _, actionId, event ->
        val keyCode = event?.keyCode ?: KeyEvent.KEYCODE_UNKNOWN
        val keyAction = event?.action ?: -1
        when (EditorActions.classify(actionId, keyCode, keyAction)) {
            EditorActions.Result.SUBMIT -> { action(); true }
            EditorActions.Result.CONSUME -> true
            EditorActions.Result.IGNORE -> false
        }
    }
}
```

- [x] **Step 4: Implement `ClickDebouncer.kt`**

```kotlin
package com.mitas.ppnam.station1aa

import android.os.SystemClock
import android.view.View

/**
 * Drops clicks that land within [windowMs] of an accepted one. A rapid double-tap on a
 * dashboard tile used to open the sub-screen twice (audit station1-06). Share one instance
 * across sibling controls so a tap on tile A followed by a tap on tile B also counts as one.
 */
class ClickDebouncer(
    private val windowMs: Long = 600L,
    private val now: () -> Long = { SystemClock.elapsedRealtime() },
) {
    private var lastAcceptedMs: Long? = null

    fun accept(): Boolean {
        val t = now()
        val last = lastAcceptedMs
        if (last != null && t - last < windowMs) return false
        lastAcceptedMs = t
        return true
    }
}

fun View.setDebouncedClickListener(debouncer: ClickDebouncer, action: () -> Unit) {
    setOnClickListener { if (debouncer.accept()) action() }
}
```

- [x] **Step 5: Run the tests**

```powershell
.\gradlew.bat :app:testDebugUnitTest --offline --tests "com.mitas.ppnam.station1aa.EditorActionsTest" --tests "com.mitas.ppnam.station1aa.ClickDebouncerTest"
```
Expected: `BUILD SUCCESSFUL`, 8 tests passed.

- [x] **Step 6: Use `setOnSubmit` in the three existing listeners**

`LoginActivity.kt` lines 82-89:
```kotlin
        binding.etPassword.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                submitCredentials()
                true
            } else {
                false
            }
        }
```
→
```kotlin
        binding.etPassword.setOnSubmit { submitCredentials() }
```
and remove the import `android.view.inputmethod.EditorInfo` (line 10).

`SettingsActivity.kt` lines 59-66 (the `binding.etPin.setOnEditorActionListener { … }` block) →
```kotlin
        binding.etPin.setOnSubmit { submitPin() }
```
and remove the import `android.view.inputmethod.EditorInfo` (line 7).

`OffloadActivity.kt` lines 382-389 (the `view.etTagCount.setOnEditorActionListener { … }` block) →
```kotlin
        view.etTagCount.setOnSubmit { submit() }
```

- [x] **Step 7: Compile and verify on the emulator**

```powershell
.\gradlew.bat :app:assembleDebug -PemulatorNoJni --offline
```
Install. Login: type `operator1` into Username, tap Password, type `pass`, then `adb -s emulator-5554 shell input keyevent KEYCODE_ENTER` → the spinner shows once and the dashboard opens (before: nothing happened). Settings: type the PIN, `KEYCODE_ENTER` → unlocks. Each press logs exactly one request in the fake backend output (no duplicate `scram_start_requested`).

- [x] **Step 8: Commit**

```powershell
git add app/src/main/java/com/mitas/ppnam/station1aa/EditorActions.kt app/src/main/java/com/mitas/ppnam/station1aa/ClickDebouncer.kt app/src/test/java/com/mitas/ppnam/station1aa/EditorActionsTest.kt app/src/test/java/com/mitas/ppnam/station1aa/ClickDebouncerTest.kt app/src/main/java/com/mitas/ppnam/station1aa/LoginActivity.kt app/src/main/java/com/mitas/ppnam/station1aa/SettingsActivity.kt app/src/main/java/com/mitas/ppnam/station1aa/OffloadActivity.kt
git commit -m "fix(input): hardware Enter submits like IME Done; add click debouncer

Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01ExhLEukYAu1CqjJUWPR64Q"
```

---

### Task 6: Persisted supervisor PIN lockout with ticker (Tier 2, §7 item 10)

Closes: station1-01 (lockout bypassed by Back + reopen), station1-15 (empty Unlock counts), station1-16 (static countdown, never clears), station1-03 (PIN error text under the PIN pad — moved above the field), station1-04 remainder (unlocked flag saved; PIN cleared on unlock).

**Files:**
- Create: `…\PinLockout.kt`, `…\PrefsPinLockoutStore.kt`
- Test: `app\src\test\java\com\mitas\ppnam\station1aa\PinLockoutTest.kt`
- Modify: `…\SettingsActivity.kt` (fields lines 23-31, `onCreate` PIN wiring, `submitPin()`..`hidePinMessages()` lines 194-237, `onDestroy`)
- Modify: `app\src\main\res\layout\activity_settings.xml:215-276`
- Modify: `app\src\main\res\values\strings.xml` (Settings block)

**Interfaces:**
- Produces: `interface PinLockoutStore { var failedAttempts: Int; var lockedOutUntilMs: Long }`; `class PinLockout(correctPin, store, now, maxAttempts = 5, lockoutMs = 30_000L)` with `fun submit(pin: String): Outcome`, `fun remainingLockoutMs(): Long`, `val isLockedOut: Boolean`; `sealed class PinLockout.Outcome { Blank, Unlocked, Rejected(attemptsLeft), LockedOut(remainingMs) }`; `class PrefsPinLockoutStore(context)`; strings `pin_prompt`, `hint_pin`, `btn_unlock`, `pin_blank`, `pin_locked_out`, plurals `pin_attempts_left`. Task 10's full `SettingsActivity.kt` builds on this.

- [x] **Step 1: Write the failing test**

`PinLockoutTest.kt`:
```kotlin
package com.mitas.ppnam.station1aa

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Supervisor PIN gate rules (audit station1-01/15/16): five wrong PINs lock the gate for 30 s,
 * the lockout and the attempt counter live in the store (so Back + reopen or a process restart
 * cannot reset them), a blank submit is not an attempt, and the correct PIN during a lockout is
 * refused without clearing the lockout.
 */
class PinLockoutTest {

    private var now = 1_000_000L
    private val store = InMemoryPinLockoutStore()
    private val gate = PinLockout(correctPin = "079545", store = store, now = { now })

    @Test
    fun `correct PIN unlocks and resets the counter`() {
        gate.submit("111111")
        assertEquals(PinLockout.Outcome.Unlocked, gate.submit("079545"))
        assertEquals(0, store.failedAttempts)
    }

    @Test
    fun `wrong PINs count down from four attempts left`() {
        assertEquals(PinLockout.Outcome.Rejected(4), gate.submit("000000"))
        assertEquals(PinLockout.Outcome.Rejected(3), gate.submit("000000"))
        assertEquals(PinLockout.Outcome.Rejected(2), gate.submit("000000"))
        assertEquals(PinLockout.Outcome.Rejected(1), gate.submit("000000"))
        assertEquals(4, store.failedAttempts)
    }

    @Test
    fun `blank submit is not an attempt`() {
        assertEquals(PinLockout.Outcome.Blank, gate.submit(""))
        assertEquals(PinLockout.Outcome.Blank, gate.submit("   "))
        assertEquals(0, store.failedAttempts)
    }

    @Test
    fun `fifth wrong PIN locks the gate for thirty seconds`() {
        repeat(4) { gate.submit("000000") }
        assertEquals(PinLockout.Outcome.LockedOut(30_000L), gate.submit("000000"))
        assertTrue(gate.isLockedOut)
        assertEquals(now + 30_000L, store.lockedOutUntilMs)
    }

    @Test
    fun `correct PIN during lockout is refused and does not clear the lockout`() {
        repeat(5) { gate.submit("000000") }
        now += 5_000
        assertEquals(PinLockout.Outcome.LockedOut(25_000L), gate.submit("079545"))
        assertEquals(now + 25_000L, store.lockedOutUntilMs)
    }

    @Test
    fun `lockout survives a new PinLockout over the same store`() {
        repeat(5) { gate.submit("000000") }
        now += 3_000
        val reopened = PinLockout(correctPin = "079545", store = store, now = { now })
        assertTrue(reopened.isLockedOut)
        assertEquals(27_000L, reopened.remainingLockoutMs())
        assertEquals(PinLockout.Outcome.LockedOut(27_000L), reopened.submit("079545"))
    }

    @Test
    fun `attempt counter survives a new PinLockout over the same store`() {
        repeat(4) { gate.submit("000000") }
        val reopened = PinLockout(correctPin = "079545", store = store, now = { now })
        assertEquals(PinLockout.Outcome.LockedOut(30_000L), reopened.submit("000000"))
    }

    @Test
    fun `after the lockout expires the gate accepts fresh attempts`() {
        repeat(5) { gate.submit("000000") }
        now += 30_000
        assertFalse(gate.isLockedOut)
        assertEquals(0L, gate.remainingLockoutMs())
        assertEquals(PinLockout.Outcome.Rejected(4), gate.submit("000000"))
        assertEquals(PinLockout.Outcome.Unlocked, gate.submit("079545"))
    }
}
```

- [x] **Step 2: Run it to see it fail**

```powershell
.\gradlew.bat :app:testDebugUnitTest --offline --tests "com.mitas.ppnam.station1aa.PinLockoutTest"
```
Expected: compilation error `Unresolved reference: PinLockout`.

- [x] **Step 3: Implement `PinLockout.kt`**

```kotlin
package com.mitas.ppnam.station1aa

/** Where the attempt counter and lockout deadline live — outside any one screen instance. */
interface PinLockoutStore {
    var failedAttempts: Int
    /** Wall-clock millis (System.currentTimeMillis) until which the gate is locked; 0 = not locked. */
    var lockedOutUntilMs: Long
}

/** Test double and the shape of the real store. */
class InMemoryPinLockoutStore : PinLockoutStore {
    override var failedAttempts: Int = 0
    override var lockedOutUntilMs: Long = 0L
}

/**
 * Supervisor PIN gate (ported from Station 2's SettingsViewModel; same PIN, 5 attempts, 30 s).
 * The counter and the deadline are read from and written to [store] on every call, so leaving
 * Settings, rotating, or restarting the process cannot reset them (audit station1-01).
 */
class PinLockout(
    private val correctPin: String,
    private val store: PinLockoutStore,
    private val now: () -> Long,
    private val maxAttempts: Int = MAX_ATTEMPTS,
    private val lockoutMs: Long = LOCKOUT_MS,
) {
    sealed class Outcome {
        /** Nothing typed — not an attempt (audit station1-15). */
        object Blank : Outcome()
        object Unlocked : Outcome()
        data class Rejected(val attemptsLeft: Int) : Outcome()
        data class LockedOut(val remainingMs: Long) : Outcome()
    }

    companion object {
        const val MAX_ATTEMPTS = 5
        const val LOCKOUT_MS = 30_000L
    }

    fun remainingLockoutMs(): Long = (store.lockedOutUntilMs - now()).coerceAtLeast(0L)

    val isLockedOut: Boolean
        get() = remainingLockoutMs() > 0L

    fun submit(pin: String): Outcome {
        val remaining = remainingLockoutMs()
        if (remaining > 0L) return Outcome.LockedOut(remaining)
        if (pin.isBlank()) return Outcome.Blank
        if (pin == correctPin) {
            store.failedAttempts = 0
            store.lockedOutUntilMs = 0L
            return Outcome.Unlocked
        }
        val failed = store.failedAttempts + 1
        if (failed >= maxAttempts) {
            store.failedAttempts = 0
            store.lockedOutUntilMs = now() + lockoutMs
            return Outcome.LockedOut(lockoutMs)
        }
        store.failedAttempts = failed
        return Outcome.Rejected(maxAttempts - failed)
    }
}
```

- [x] **Step 4: Implement `PrefsPinLockoutStore.kt`**

```kotlin
package com.mitas.ppnam.station1aa

import android.content.Context

/** SharedPreferences-backed lockout state: survives Back, rotation and process restart. */
class PrefsPinLockoutStore(context: Context) : PinLockoutStore {

    private val prefs = context.applicationContext
        .getSharedPreferences("supervisor_pin", Context.MODE_PRIVATE)

    override var failedAttempts: Int
        get() = prefs.getInt(KEY_FAILED_ATTEMPTS, 0)
        set(value) = prefs.edit().putInt(KEY_FAILED_ATTEMPTS, value).apply()

    override var lockedOutUntilMs: Long
        get() = prefs.getLong(KEY_LOCKED_OUT_UNTIL_MS, 0L)
        set(value) = prefs.edit().putLong(KEY_LOCKED_OUT_UNTIL_MS, value).apply()

    private companion object {
        const val KEY_FAILED_ATTEMPTS = "failed_attempts"
        const val KEY_LOCKED_OUT_UNTIL_MS = "locked_out_until_ms"
    }
}
```

- [x] **Step 5: Run the test**

```powershell
.\gradlew.bat :app:testDebugUnitTest --offline --tests "com.mitas.ppnam.station1aa.PinLockoutTest"
```
Expected: `BUILD SUCCESSFUL`, 8 tests passed.

- [x] **Step 6: Strings**

In `strings.xml`, directly under `<!-- Settings -->` (line 80) add:
```xml
    <string name="pin_prompt">Enter supervisor PIN to edit settings</string>
    <string name="hint_pin">PIN</string>
    <string name="btn_unlock">Unlock</string>
    <string name="pin_blank">Enter the 6-digit PIN</string>
    <plurals name="pin_attempts_left">
        <item quantity="one">Incorrect PIN. 1 attempt left before lockout.</item>
        <item quantity="other">Incorrect PIN. %d attempts left before lockout.</item>
    </plurals>
    <string name="pin_locked_out">Too many attempts. Try again in %1$ds.</string>
```

- [x] **Step 7: Layout — error text above the PIN field**

In `activity_settings.xml` replace lines 215-276 (everything inside `cardPinLock`'s vertical LinearLayout, from the prompt `<TextView` to the `tvPinLockout` `/>`) with:

```xml
                    <TextView
                        android:layout_width="wrap_content"
                        android:layout_height="wrap_content"
                        android:text="@string/pin_prompt"
                        android:textColor="@color/text_muted"
                        android:textSize="15sp" />

                    <!-- Messages sit ABOVE the field: below it they landed exactly under the PIN
                         pad's top edge and the supervisor saw the field clear with no reason
                         (audit station1-03). -->
                    <TextView
                        android:id="@+id/tvPinError"
                        android:layout_width="wrap_content"
                        android:layout_height="wrap_content"
                        android:layout_marginTop="10dp"
                        android:textColor="@color/danger"
                        android:textSize="13sp"
                        android:visibility="gone"
                        tools:visibility="visible"
                        tools:text="Incorrect PIN. 4 attempts left before lockout." />

                    <TextView
                        android:id="@+id/tvPinLockout"
                        android:layout_width="wrap_content"
                        android:layout_height="wrap_content"
                        android:layout_marginTop="10dp"
                        android:textColor="@color/danger"
                        android:textSize="13sp"
                        android:visibility="gone" />

                    <LinearLayout
                        android:layout_width="match_parent"
                        android:layout_height="wrap_content"
                        android:layout_marginTop="12dp"
                        android:gravity="center_vertical"
                        android:orientation="horizontal">

                        <com.google.android.material.textfield.TextInputLayout
                            android:id="@+id/tilPin"
                            style="@style/Widget.MaterialComponents.TextInputLayout.OutlinedBox"
                            android:layout_width="0dp"
                            android:layout_height="wrap_content"
                            android:layout_weight="1"
                            android:hint="@string/hint_pin"
                            app:boxStrokeColor="@color/outline_dark"
                            app:hintTextColor="@color/text_secondary_dark">

                            <com.google.android.material.textfield.TextInputEditText
                                android:id="@+id/etPin"
                                android:layout_width="match_parent"
                                android:layout_height="wrap_content"
                                android:imeOptions="actionDone"
                                android:inputType="numberPassword"
                                android:maxLength="6"
                                android:maxLines="1"
                                android:textColor="@color/text_primary_dark" />
                        </com.google.android.material.textfield.TextInputLayout>

                        <com.google.android.material.button.MaterialButton
                            android:id="@+id/btnUnlock"
                            android:layout_width="wrap_content"
                            android:layout_height="56dp"
                            android:layout_marginStart="12dp"
                            android:text="@string/btn_unlock" />
                    </LinearLayout>
```

- [x] **Step 8: `SettingsActivity.kt` — PIN gate on `PinLockout`**

(a) Replace the fields on lines 23-31:
```kotlin
    // Ported from Station 2's SettingsViewModel so both apps' supervisor lock behave identically.
    private val correctPin = "079545"
    private var failedPinAttempts = 0
    private var lockedOutUntilMs = 0L

    private companion object {
        const val MAX_PIN_ATTEMPTS = 5
        const val PIN_LOCKOUT_MS = 30_000L
    }
```
with:
```kotlin
    // Ported from Station 2's SettingsViewModel so both apps' supervisor lock behave identically;
    // the counter and the lockout deadline are persisted (audit station1-01).
    private lateinit var pinLockout: PinLockout
    private val mainHandler = Handler(Looper.getMainLooper())

    /** 1 s ticker while locked out: counts the message down and re-enables Unlock at the end. */
    private val lockoutTicker = object : Runnable {
        override fun run() { renderLockout() }
    }

    private companion object {
        const val CORRECT_PIN = "079545"
        const val KEY_UNLOCKED = "settings_unlocked"
    }
```
and add the imports `android.os.Handler` and `android.os.Looper`.

(b) In `onCreate`, directly after `binding.etPin.setOnSubmit { submitPin() }` add:
```kotlin
        pinLockout = PinLockout(CORRECT_PIN, PrefsPinLockoutStore(this), System::currentTimeMillis)
        // A lockout in progress must be visible (and Unlock disabled) the moment the screen opens,
        // and an unlocked form must survive recreation without asking for the PIN again.
        if (savedInstanceState?.getBoolean(KEY_UNLOCKED) == true) showUnlocked() else renderLockout()
```

(c) Replace `submitPin()` through `hidePinMessages()` (lines 194-237) with:
```kotlin
    private fun submitPin() {
        when (val outcome = pinLockout.submit(binding.etPin.text.toString())) {
            PinLockout.Outcome.Blank -> showErrorMessage(getString(R.string.pin_blank))
            PinLockout.Outcome.Unlocked -> {
                hidePinMessages()
                binding.etPin.setText("")
                showUnlocked()
            }
            is PinLockout.Outcome.Rejected -> {
                binding.etPin.setText("")
                showErrorMessage(
                    resources.getQuantityString(
                        R.plurals.pin_attempts_left, outcome.attemptsLeft, outcome.attemptsLeft,
                    )
                )
            }
            is PinLockout.Outcome.LockedOut -> {
                binding.etPin.setText("")
                renderLockout()
            }
        }
    }

    private fun showUnlocked() {
        binding.cardPinLock.visibility = View.GONE
        binding.groupSettingsFields.visibility = View.VISIBLE
    }

    /**
     * Reflects the persisted lockout: message with the live countdown, field and Unlock disabled,
     * re-armed every second until it expires (audit station1-16).
     */
    private fun renderLockout() {
        mainHandler.removeCallbacks(lockoutTicker)
        val remainingMs = pinLockout.remainingLockoutMs()
        if (remainingMs <= 0L) {
            binding.tvPinLockout.visibility = View.GONE
            binding.etPin.isEnabled = true
            binding.btnUnlock.isEnabled = true
            return
        }
        val seconds = ((remainingMs + 999) / 1_000).toInt()
        binding.tvPinLockout.text = getString(R.string.pin_locked_out, seconds)
        binding.tvPinLockout.visibility = View.VISIBLE
        binding.tvPinError.visibility = View.GONE
        binding.etPin.isEnabled = false
        binding.btnUnlock.isEnabled = false
        mainHandler.postDelayed(lockoutTicker, 1_000L)
    }

    private fun showErrorMessage(message: String) {
        binding.tvPinError.text = message
        binding.tvPinError.visibility = View.VISIBLE
        binding.tvPinLockout.visibility = View.GONE
    }

    private fun hidePinMessages() {
        binding.tvPinError.visibility = View.GONE
        binding.tvPinLockout.visibility = View.GONE
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(KEY_UNLOCKED, binding.groupSettingsFields.visibility == View.VISIBLE)
    }
```

(d) In `onDestroy()` add `mainHandler.removeCallbacks(lockoutTicker)` before the `super.onDestroy()` call.

- [x] **Step 9: Compile and verify**

```powershell
.\gradlew.bat :app:assembleDebug -PemulatorNoJni --offline
```
Install. On emulator-5554, Settings:
1. Tap Unlock with the field empty → "Enter the 6-digit PIN", no attempt consumed (next wrong PIN still says "4 attempts left before lockout").
2. Five wrong PINs → "Too many attempts. Try again in 30s." counting down 29s, 28s…; `etPin` and Unlock greyed. Press Back, reopen Settings → still locked, still counting. `adb -s emulator-5554 shell am force-stop com.mitas.ppnam.station1aa`, relaunch, open Settings within 30 s → still locked (Review Focus 1).
3. When the countdown reaches 0 the message disappears and Unlock is enabled; `079545` unlocks.
4. With the PIN pad open and a wrong PIN submitted, the error line is above the field, bounds y2 < 1155 (uiautomator dump).

- [x] **Step 10: Commit**

```powershell
git add app/src/main/java/com/mitas/ppnam/station1aa/PinLockout.kt app/src/main/java/com/mitas/ppnam/station1aa/PrefsPinLockoutStore.kt app/src/test/java/com/mitas/ppnam/station1aa/PinLockoutTest.kt app/src/main/java/com/mitas/ppnam/station1aa/SettingsActivity.kt app/src/main/res/layout/activity_settings.xml app/src/main/res/values/strings.xml
git commit -m "fix(settings): persist the supervisor PIN lockout, tick the countdown, ignore blank submits

Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01ExhLEukYAu1CqjJUWPR64Q"
```

---

### Task 7: Canonical Login — error above fields, password toggle, Log In above the keyboard, operator-facing auth errors (Tier 2, §7 items 11 + 13)

Closes: station1-07 ("SCRAM proof rejected."), station1-10 (Log In a 41 px sliver, no auto-scroll), station1-19 (dropdown arrow with no list), section 5 "Login layout" row (password visibility toggle, "Please fill in all fields" check kept, error line above the fields), group (f) and (j) for S1 (session-ended reason on Login — strings here, used by Tasks 11/12).

**Files:**
- Create: `…\AuthFailure.kt`
- Test: `app\src\test\java\com\mitas\ppnam\station1aa\AuthFailureTest.kt`
- Modify: `…\AuthClient.kt` (whole file), `…\LoginActivity.kt` (whole file), `app\src\main\res\layout\activity_login.xml` (whole file), `strings.xml` (Login block)

**Interfaces:**
- Consumes: `applyAppSystemBars()`, `padForSystemBarsAndIme()`, `scrollIntoView()` (Task 3); `setOnSubmit` (Task 5); `showExitAppDialog()` (Task 4).
- Produces: `class AuthFailure(val kind: Kind, detail: String) : Exception` with `enum Kind { NOT_CONNECTED, TIMEOUT, INVALID_CREDENTIALS, BADGE_REJECTED, REJECTED, PROTOCOL }`, `enum Step { SCRAM_START, SCRAM_PROOF, BADGE_LOGIN, OTHER }`, `AuthFailure.rejected(step, errorCode, reason)`, `AuthFailure.envelopeRejected(step, errorCode, reason)`; strings `login_invalid_credentials`, `login_badge_rejected`, `login_not_connected`, `login_timeout`, `login_failed_generic`, `signed_out_session_ended`; view id `scrollLogin`.

- [x] **Step 1: Write the failing test**

`AuthFailureTest.kt`:
```kotlin
package com.mitas.ppnam.station1aa

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Login failures are classified by the step they came from — never by parsing `reason`. */
class AuthFailureTest {

    @Test
    fun `a rejected SCRAM proof is bad credentials`() {
        val f = AuthFailure.rejected(AuthFailure.Step.SCRAM_PROOF, "scram_proof_invalid", "SCRAM proof rejected.")
        assertEquals(AuthFailure.Kind.INVALID_CREDENTIALS, f.kind)
    }

    @Test
    fun `a rejected SCRAM start is bad credentials too`() {
        val f = AuthFailure.rejected(AuthFailure.Step.SCRAM_START, "scram_start_invalid", "SCRAM start rejected.")
        assertEquals(AuthFailure.Kind.INVALID_CREDENTIALS, f.kind)
    }

    @Test
    fun `a rejected badge login is a badge problem`() {
        val f = AuthFailure.rejected(AuthFailure.Step.BADGE_LOGIN, "badge_rejected", "Unknown badge.")
        assertEquals(AuthFailure.Kind.BADGE_REJECTED, f.kind)
    }

    @Test
    fun `an envelope rejection is never reported as bad credentials`() {
        val f = AuthFailure.envelopeRejected(AuthFailure.Step.SCRAM_PROOF, "invalid_envelope", "Bad schema.")
        assertEquals(AuthFailure.Kind.REJECTED, f.kind)
        assertNotEquals(AuthFailure.Kind.INVALID_CREDENTIALS, f.kind)
    }

    @Test
    fun `the station's reason is kept for logs, not shown`() {
        val f = AuthFailure.rejected(AuthFailure.Step.SCRAM_PROOF, "scram_proof_invalid", "SCRAM proof rejected.")
        assertTrue(f.message!!.contains("SCRAM proof rejected."))
        assertTrue(f.message!!.contains("scram_proof_invalid"))
    }
}
```

- [x] **Step 2: Run it to see it fail**

```powershell
.\gradlew.bat :app:testDebugUnitTest --offline --tests "com.mitas.ppnam.station1aa.AuthFailureTest"
```
Expected: compilation error `Unresolved reference: AuthFailure`.

- [x] **Step 3: Create `AuthFailure.kt`**

```kotlin
package com.mitas.ppnam.station1aa

/**
 * Why a login produced no session, classified so the UI can speak to the operator instead of
 * echoing protocol text ("SCRAM proof rejected.", audit station1-07). The station's `reason`
 * and `errorCode` are kept in the exception message for logcat only — never shown.
 */
class AuthFailure(val kind: Kind, detail: String) : Exception(detail) {

    enum class Kind {
        /** Broker link down, or the publish itself failed. */
        NOT_CONNECTED,
        /** No correlated response within AuthClient's 10 s window. */
        TIMEOUT,
        /** Either SCRAM half rejected: the username/password pair did not verify. */
        INVALID_CREDENTIALS,
        /** `login_requested` rejected (`badge_rejected`). */
        BADGE_REJECTED,
        /** Any other station rejection, including a refused envelope on res/request_rejected. */
        REJECTED,
        /** The station answered something this client cannot use (missing fields, bad signature). */
        PROTOCOL,
    }

    /** Which round trip a rejection answered — decides what the rejection means. */
    enum class Step { SCRAM_START, SCRAM_PROOF, BADGE_LOGIN, OTHER }

    companion object {
        /** `accepted: false` on the step's own response topic. */
        fun rejected(step: Step, errorCode: String, reason: String): AuthFailure {
            val detail = "$step rejected: ${errorCode.ifBlank { "-" }} $reason"
            return when (step) {
                // Which SCRAM half failed is a protocol detail the operator cannot act on.
                Step.SCRAM_START, Step.SCRAM_PROOF -> AuthFailure(Kind.INVALID_CREDENTIALS, detail)
                Step.BADGE_LOGIN -> AuthFailure(Kind.BADGE_REJECTED, detail)
                Step.OTHER -> AuthFailure(Kind.REJECTED, detail)
            }
        }

        /**
         * res/request_rejected: the envelope itself was refused (schema, replay, routing) — not a
         * credentials problem, so it must not be reported as one.
         */
        fun envelopeRejected(step: Step, errorCode: String, reason: String): AuthFailure =
            AuthFailure(Kind.REJECTED, "$step envelope rejected: ${errorCode.ifBlank { "-" }} $reason")
    }
}
```

- [x] **Step 4: Run the test**

```powershell
.\gradlew.bat :app:testDebugUnitTest --offline --tests "com.mitas.ppnam.station1aa.AuthFailureTest"
```
Expected: `BUILD SUCCESSFUL`, 5 tests passed.

- [x] **Step 5: Rewrite `AuthClient.kt`**

Replace the whole file (same topics, envelope, SCRAM and correlation; only the failure objects change):

```kotlin
package com.mitas.ppnam.station1aa

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.hivemq.client.mqtt.mqtt3.message.publish.Mqtt3Publish
import org.json.JSONObject
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Operator authentication over MQTT — the shared Station 2 schema 4.1 contract on Station 1's
 * namespaced topics (Station1_MQTT_Contract v3.0.0 §4):
 *
 *   req/scram_start_requested   -> res/scram_challenge
 *   req/scram_proof_requested   -> res/scram_proof_result
 *   req/login_requested         -> res/operator_context
 *   req/reader_logout_requested -> res/operator_context (fire-and-forget here)
 *   req/operator_list_requested -> res/operator_list (login dropdown, v3.2.0 §4.5)
 *
 * Every request carries the schema 4.1 envelope (Schema41). Responses are correlated on
 * inResponseToMessageId and branched on `accepted`/`errorCode` — free-text `reason` is logged,
 * never parsed and never shown (every failure is an [AuthFailure] the UI maps to its own copy).
 * Envelope and routing failures arrive on res/request_rejected, so every round trip listens
 * there too.
 *
 * Logout clears the local session regardless of the outcome: stranding an operator logged-in
 * because the network blipped would be worse than a server-side session that expires on its own.
 */
class AuthClient(context: Context) {

    private val appContext = context.applicationContext
    private val mqtt = MqttManager.getInstance(appContext)
    private val mainHandler = Handler(Looper.getMainLooper())

    private companion object {
        const val TAG = "AuthClient"
        const val REQUEST_TIMEOUT_MS = 10_000L
        const val PURPOSE_LOGIN = "login"
        const val REJECTED_SUFFIX = "request_rejected"
    }

    private fun deviceId(): String = DeviceIdentity.deviceId(appContext)

    fun login(username: String, password: String, onResult: (Result<OperatorSession>) -> Unit) {
        val clientNonce = ScramCrypto.generateClientNonce()

        val startPayload = Schema41.envelope(Schema41.newMessageId("auth-start"), deviceId()).apply {
            put("username", username)
            put("clientNonce", clientNonce)
            put("purpose", PURPOSE_LOGIN)
        }

        request(AuthFailure.Step.SCRAM_START, "scram_start_requested", "scram_challenge", startPayload) { startResult ->
            val challenge = startResult.getOrElse { return@request onResult(Result.failure(it)) }

            val challengeId = challenge.optString("challengeId", "")
            val serverFirstMessage = challenge.optString("serverFirstMessage", "")
            val iterations = challenge.optInt("iterations", 0)
            if (challengeId.isBlank() || serverFirstMessage.isBlank()) {
                return@request onResult(protocol("Station sent an incomplete authentication challenge"))
            }
            if (iterations <= 0) {
                return@request onResult(protocol("Station sent an invalid authentication challenge"))
            }

            // RFC 5802: the combined nonce must extend the one we sent. Anything else means this
            // challenge is not an answer to our start — refuse rather than proving against it.
            val serverNonce = ScramCrypto.parseServerNonce(serverFirstMessage, clientNonce)
                ?: challenge.optString("serverNonce", "").takeIf {
                    it.startsWith(clientNonce) && it.length > clientNonce.length
                }
                ?: return@request onResult(
                    protocol("Station's authentication challenge did not match this device's request")
                )

            val clientFinalWithoutProof = ScramCrypto.clientFinalWithoutProof(serverNonce)
            val proof = try {
                ScramCrypto.computeProof(
                    password = password,
                    saltBase64 = challenge.optString("salt", ""),
                    iterations = iterations,
                    authMessage = ScramCrypto.authMessage(
                        clientFirstBare = ScramCrypto.clientFirstBare(username, clientNonce),
                        serverFirstMessage = serverFirstMessage,
                        clientFinalWithoutProof = clientFinalWithoutProof,
                    ),
                )
            } catch (e: Exception) {
                // A malformed salt lands here. Deliberately not echoing the exception text, which
                // would put challenge material into a log line.
                return@request onResult(protocol("Station sent an unusable authentication challenge"))
            }

            val proofPayload = Schema41.envelope(Schema41.newMessageId("auth-proof"), deviceId()).apply {
                put("challengeId", challengeId)
                put("clientFinalWithoutProof", clientFinalWithoutProof)
                put("clientProof", proof.clientProofBase64)
                put("purpose", PURPOSE_LOGIN)
            }

            request(AuthFailure.Step.SCRAM_PROOF, "scram_proof_requested", "scram_proof_result", proofPayload) { proofResult ->
                val response = proofResult.getOrElse { return@request onResult(Result.failure(it)) }

                // Mutual authentication. Without this check anything that can answer on the
                // response topic could hand us a session we never actually proved for — which is
                // precisely what SCRAM's server signature exists to prevent.
                val serverSignature = response.optString("serverSignature", "")
                if (!ScramCrypto.verifyServerSignature(proof.expectedServerSignatureBase64, serverSignature)) {
                    return@request onResult(
                        protocol("Station failed authentication verification — this response is not trusted")
                    )
                }

                onResult(buildSession(response))
            }
        }
    }

    fun loginWithBadge(badgeTag: String, onResult: (Result<OperatorSession>) -> Unit) {
        val payload = Schema41.envelope(Schema41.newMessageId("badge-login"), deviceId()).apply {
            put("badgeTag", badgeTag)
        }
        request(AuthFailure.Step.BADGE_LOGIN, "login_requested", "operator_context", payload) { result ->
            onResult(result.fold({ buildSession(it) }, { Result.failure(it) }))
        }
    }

    /** Contract v3.2.0 §4.5: the display-only operator directory for the login dropdown. */
    fun operatorList(onResult: (Result<List<OperatorEntry>>) -> Unit) {
        val payload = Schema41.envelope(Schema41.newMessageId("operator-list"), deviceId())
        request(AuthFailure.Step.OTHER, "operator_list_requested", "operator_list", payload) { result ->
            onResult(result.map { OperatorListCodec.fromResponse(it) })
        }
    }

    fun logout(onComplete: () -> Unit = {}) {
        val payload = Schema41.envelope(Schema41.newMessageId("logout"), deviceId()).apply {
            put("operatorSessionId", OperatorSessionHolder.currentSessionIdOrEmpty())
        }
        val topic = MqttTopics.deviceRequest(deviceId(), "reader_logout_requested")
        mqtt.publish(topic, payload.toString()) { throwable ->
            if (throwable != null) Log.w(TAG, "Logout publish failed (session cleared anyway)", throwable)
        }
        OperatorSessionHolder.clear()
        mainHandler.post { onComplete() }
    }

    private fun buildSession(response: JSONObject): Result<OperatorSession> {
        val operatorSessionId = response.optString("operatorSessionId", "")
        val sessionState = response.optString("sessionState", "")
        return when {
            operatorSessionId.isBlank() ->
                protocol("Station accepted the login but issued no session")
            // Accepting an already-closed session would strand the operator in a UI that
            // rejects every action.
            sessionState.equals("closed", ignoreCase = true) ->
                protocol("Station closed this session immediately")
            else -> {
                val session = OperatorSession(
                    operatorSessionId = operatorSessionId,
                    operatorId = response.optString("operatorId", ""),
                    operatorName = response.optString("displayName", ""),
                    role = response.optString("role", ""),
                    allowedActions = response.optJSONArray("allowedActions").toStringList(),
                    allowedTabs = response.optJSONArray("allowedTabs").toStringList(),
                )
                OperatorSessionHolder.set(session)
                Result.success(session)
            }
        }
    }

    /**
     * One schema 4.1 request/response round trip, with a timeout. The response is accepted only
     * when its inResponseToMessageId matches this request; rejections — on the response topic or
     * on res/request_rejected — surface as [AuthFailure]s classified by [step].
     * The callback fires exactly once, on the main thread.
     */
    private fun request(
        step: AuthFailure.Step,
        requestType: String,
        responseType: String,
        payload: JSONObject,
        onResult: (Result<JSONObject>) -> Unit,
    ) {
        if (!mqtt.isConnected()) {
            mainHandler.post {
                onResult(Result.failure(AuthFailure(AuthFailure.Kind.NOT_CONNECTED, "Not connected to the broker")))
            }
            return
        }

        val device = deviceId()
        val requestMessageId = payload.getString("messageId")
        val responseTopic = MqttTopics.deviceResponse(device, responseType)
        val rejectedTopic = MqttTopics.deviceResponse(device, REJECTED_SUFFIX)
        val done = AtomicBoolean(false)

        lateinit var timeoutRunnable: Runnable
        lateinit var onResponse: (Mqtt3Publish) -> Unit
        lateinit var onRejected: (Mqtt3Publish) -> Unit

        fun finish(result: Result<JSONObject>) {
            if (!done.compareAndSet(false, true)) return
            mainHandler.removeCallbacks(timeoutRunnable)
            mqtt.unsubscribe(responseTopic, onResponse)
            mqtt.unsubscribe(rejectedTopic, onRejected)
            result.exceptionOrNull()?.let { Log.w(TAG, "$requestType failed: ${it.message}") }
            mainHandler.post { onResult(result) }
        }

        timeoutRunnable = Runnable {
            finish(Result.failure(AuthFailure(AuthFailure.Kind.TIMEOUT, "$requestType: no response in ${REQUEST_TIMEOUT_MS} ms")))
        }

        // With correlation, a message that isn't ours (wrong device, wrong messageId, or
        // unparseable) is ignored rather than failing the request — the timeout covers silence.
        fun parseCorrelated(publish: Mqtt3Publish): JSONObject? = try {
            val json = JSONObject(String(publish.payloadAsBytes, StandardCharsets.UTF_8))
            json.takeIf { mqtt.isRelevantToThisScanner(it) && Schema41.isResponseTo(it, requestMessageId) }
        } catch (e: Exception) {
            Log.e(TAG, "Malformed payload on ${publish.topic}", e)
            null
        }

        onResponse = { publish ->
            parseCorrelated(publish)?.let { json ->
                if (Schema41.isAccepted(json)) {
                    finish(Result.success(json))
                } else {
                    finish(Result.failure(
                        AuthFailure.rejected(step, json.optString("errorCode", ""), json.optString("reason", ""))
                    ))
                }
            }
        }

        onRejected = { publish ->
            parseCorrelated(publish)?.let { json ->
                finish(Result.failure(
                    AuthFailure.envelopeRejected(step, json.optString("errorCode", ""), json.optString("reason", ""))
                ))
            }
        }

        mqtt.subscribe(responseTopic, onResponse)
        mqtt.subscribe(rejectedTopic, onRejected)
        mainHandler.postDelayed(timeoutRunnable, REQUEST_TIMEOUT_MS)

        mqtt.publish(MqttTopics.deviceRequest(device, requestType), payload.toString()) { throwable ->
            if (throwable != null) {
                finish(Result.failure(AuthFailure(AuthFailure.Kind.NOT_CONNECTED, "$requestType publish failed: ${throwable.message}")))
            }
        }
    }

    private fun protocol(detail: String): Result<Nothing> =
        Result.failure(AuthFailure(AuthFailure.Kind.PROTOCOL, detail))
}

private fun org.json.JSONArray?.toStringList(): List<String> {
    if (this == null) return emptyList()
    return (0 until length()).mapNotNull { optString(it, "").takeIf { s -> s.isNotBlank() } }
}
```

- [x] **Step 6: Strings**

In `strings.xml`, directly under `<!-- Login / session -->` (line 65) add:
```xml
    <!-- Operator-facing login failures (audit station1-07): the station's protocol text is
         logged, never shown. -->
    <string name="login_invalid_credentials">Incorrect username or password</string>
    <string name="login_badge_rejected">Badge not recognised. Log in with your username and password.</string>
    <string name="login_not_connected">Not connected to the station</string>
    <string name="login_timeout">Station 1 did not respond. Check the station and try again.</string>
    <string name="login_failed_generic">Login failed. Try again.</string>
```
and under `<!-- Forced sign-out (spec §2, §3) -->` add:
```xml
    <string name="signed_out_session_ended">Your session ended. Sign in again.</string>
```

- [x] **Step 7: Rewrite `activity_login.xml`**

Replace the whole file. Changes: `scrollLogin` id; the error line moved ABOVE the fields; `textNoSuggestions` on the username (no red spell-check underline); password visibility toggle via `endIconMode`; gear icon from Task 4 kept.

```xml
<?xml version="1.0" encoding="utf-8"?>
<androidx.constraintlayout.widget.ConstraintLayout xmlns:android="http://schemas.android.com/apk/res/android"
    xmlns:app="http://schemas.android.com/apk/res-auto"
    xmlns:tools="http://schemas.android.com/tools"
    android:id="@+id/main"
    android:layout_width="match_parent"
    android:layout_height="match_parent"
    android:background="@color/window_background">

    <com.google.android.material.appbar.AppBarLayout
        android:id="@+id/appBarLayout"
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        app:layout_constraintTop_toTopOf="parent">

        <com.google.android.material.appbar.MaterialToolbar
            android:id="@+id/toolbar"
            android:layout_width="match_parent"
            android:layout_height="?attr/actionBarSize"
            android:background="@color/card_background"
            app:title="@string/title_log_in"
            app:titleTextAppearance="@style/TextAppearance.SysOneScanner.ToolbarTitle">

            <LinearLayout
                android:layout_width="wrap_content"
                android:layout_height="wrap_content"
                android:layout_gravity="end"
                android:gravity="center_vertical"
                android:orientation="horizontal">

                <ImageButton
                    android:id="@+id/btnSettings"
                    android:layout_width="48dp"
                    android:layout_height="48dp"
                    android:background="?attr/selectableItemBackgroundBorderless"
                    android:contentDescription="Settings"
                    android:src="@drawable/ic_settings"
                    app:tint="@color/text_primary" />

                <com.mitas.ppnam.station1aa.ConnectionPillView
                    android:id="@+id/connectionPill"
                    android:layout_width="wrap_content"
                    android:layout_height="wrap_content"
                    android:layout_marginEnd="12dp" />
            </LinearLayout>
        </com.google.android.material.appbar.MaterialToolbar>
    </com.google.android.material.appbar.AppBarLayout>

    <androidx.core.widget.NestedScrollView
        android:id="@+id/scrollLogin"
        android:layout_width="match_parent"
        android:layout_height="0dp"
        android:clipToPadding="false"
        android:fillViewport="true"
        android:overScrollMode="never"
        app:layout_constraintBottom_toBottomOf="parent"
        app:layout_constraintEnd_toEndOf="parent"
        app:layout_constraintStart_toStartOf="parent"
        app:layout_constraintTop_toBottomOf="@id/appBarLayout">

        <!-- Centered like Station 2's login: one card, vertically centred in the free space.
             With the keyboard up LoginActivity scrolls Log In into view (audit station1-10). -->
        <LinearLayout
            android:layout_width="match_parent"
            android:layout_height="match_parent"
            android:gravity="center"
            android:orientation="vertical"
            android:padding="24dp">

            <com.google.android.material.card.MaterialCardView
                android:layout_width="match_parent"
                android:layout_height="wrap_content"
                app:cardBackgroundColor="@color/card_background"
                app:cardCornerRadius="16dp"
                app:cardElevation="0dp"
                app:strokeColor="@color/border_primary"
                app:strokeWidth="1dp">

                <LinearLayout
                    android:layout_width="match_parent"
                    android:layout_height="wrap_content"
                    android:orientation="vertical"
                    android:padding="24dp">

                    <TextView
                        android:id="@+id/tvStationOfflineBanner"
                        android:layout_width="match_parent"
                        android:layout_height="wrap_content"
                        android:layout_marginBottom="16dp"
                        android:background="@color/danger"
                        android:padding="12dp"
                        android:text="@string/login_station_offline_banner"
                        android:textColor="@color/window_background"
                        android:textSize="14sp"
                        android:visibility="gone"
                        tools:visibility="visible" />

                    <!-- The error line sits ABOVE the fields: below them it grew the form and
                         pushed Log In under the keyboard (audit group a, sub-cause 4). -->
                    <TextView
                        android:id="@+id/tvLoginError"
                        android:layout_width="match_parent"
                        android:layout_height="wrap_content"
                        android:layout_marginBottom="16dp"
                        android:textColor="@color/danger"
                        android:textSize="15sp"
                        android:visibility="gone"
                        tools:text="Incorrect username or password"
                        tools:visibility="visible" />

                    <com.google.android.material.textfield.TextInputLayout
                        android:id="@+id/tilUsername"
                        style="@style/Widget.MaterialComponents.TextInputLayout.OutlinedBox.ExposedDropdownMenu"
                        android:layout_width="match_parent"
                        android:layout_height="wrap_content"
                        android:hint="@string/hint_username"
                        app:boxStrokeColor="@color/outline_dark"
                        app:endIconTint="@color/text_muted"
                        app:hintTextColor="@color/text_secondary_dark">

                        <com.google.android.material.textfield.MaterialAutoCompleteTextView
                            android:id="@+id/etUsername"
                            android:layout_width="match_parent"
                            android:layout_height="wrap_content"
                            android:completionThreshold="0"
                            android:imeOptions="actionNext"
                            android:inputType="text|textNoSuggestions"
                            android:maxLines="1"
                            android:textColor="@color/text_primary_dark" />
                    </com.google.android.material.textfield.TextInputLayout>

                    <com.google.android.material.textfield.TextInputLayout
                        android:id="@+id/tilPassword"
                        style="@style/Widget.MaterialComponents.TextInputLayout.OutlinedBox"
                        android:layout_width="match_parent"
                        android:layout_height="wrap_content"
                        android:layout_marginTop="16dp"
                        android:hint="@string/hint_password"
                        app:boxStrokeColor="@color/outline_dark"
                        app:endIconMode="password_toggle"
                        app:endIconTint="@color/text_muted"
                        app:hintTextColor="@color/text_secondary_dark">

                        <com.google.android.material.textfield.TextInputEditText
                            android:id="@+id/etPassword"
                            android:layout_width="match_parent"
                            android:layout_height="wrap_content"
                            android:imeOptions="actionDone"
                            android:inputType="textPassword"
                            android:maxLines="1"
                            android:textColor="@color/text_primary_dark" />
                    </com.google.android.material.textfield.TextInputLayout>

                    <FrameLayout
                        android:layout_width="match_parent"
                        android:layout_height="56dp"
                        android:layout_marginTop="16dp">

                        <com.google.android.material.button.MaterialButton
                            android:id="@+id/btnLogin"
                            android:layout_width="match_parent"
                            android:layout_height="match_parent"
                            android:text="@string/btn_log_in"
                            app:backgroundTint="@color/primary_action"
                            app:cornerRadius="14dp" />

                        <ProgressBar
                            android:id="@+id/progressLogin"
                            android:layout_width="20dp"
                            android:layout_height="20dp"
                            android:layout_gravity="center"
                            android:elevation="8dp"
                            android:indeterminateTint="@color/window_background"
                            android:visibility="gone" />
                    </FrameLayout>

                    <!-- Divider between the two login methods, matching Station 2's
                         "── or scan your badge ──" row -->
                    <LinearLayout
                        android:layout_width="match_parent"
                        android:layout_height="wrap_content"
                        android:layout_marginTop="16dp"
                        android:gravity="center_vertical"
                        android:orientation="horizontal">

                        <View
                            android:layout_width="0dp"
                            android:layout_height="1dp"
                            android:layout_weight="1"
                            android:background="@color/border_primary" />

                        <TextView
                            android:layout_width="wrap_content"
                            android:layout_height="wrap_content"
                            android:paddingStart="8dp"
                            android:paddingEnd="8dp"
                            android:text="@string/label_or_scan_badge"
                            android:textColor="@color/text_muted"
                            android:textSize="12sp" />

                        <View
                            android:layout_width="0dp"
                            android:layout_height="1dp"
                            android:layout_weight="1"
                            android:background="@color/border_primary" />
                    </LinearLayout>
                </LinearLayout>
            </com.google.android.material.card.MaterialCardView>
        </LinearLayout>
    </androidx.core.widget.NestedScrollView>

</androidx.constraintlayout.widget.ConstraintLayout>
```

- [x] **Step 8: Rewrite `LoginActivity.kt`**

Replace the whole file — this is the canonical login S3 and S5 copy:

```kotlin
package com.mitas.ppnam.station1aa

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.View
import androidx.activity.addCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.doOnNextLayout
import com.google.android.material.textfield.TextInputLayout
import com.mitas.ppnam.station1aa.databinding.ActivityLoginBinding

/**
 * Operator login, mirroring Station 2 AA's LoginScreen: username/password (SCRAM under the hood)
 * or an RFID badge scan, with the same connection pill and a Settings shortcut in the top bar.
 * This is the launcher activity — MainActivity requires a session.
 *
 * Canonical for the XML stations (S3/S5 copy this file): error line above the fields, password
 * visibility toggle, "Please fill in all fields" client check, Log In kept above the keyboard,
 * Enter submits, Back asks "Close the app?", every failure in operator wording.
 */
class LoginActivity : AppCompatActivity() {

    private lateinit var binding: ActivityLoginBinding
    private lateinit var authClient: AuthClient
    private lateinit var directory: OperatorDirectory
    private var operators: List<OperatorEntry> = emptyList()

    /** Blocks re-entry for the whole logging-in -> navigated span, exactly like Station 2's
     *  LoginViewModel: a repeat badge read arriving after success but before navigation must not
     *  start a second, concurrent login that could overwrite the just-established session. */
    private var loginInFlight = false
    private var loggedIn = false

    private val connectionStatusListener: (ConnectionStatus) -> Unit = { status ->
        runOnUiThread {
            binding.connectionPill.setStatus(status)
            binding.tvStationOfflineBanner.visibility =
                if (status == ConnectionStatus.STATION_OFFLINE) View.VISIBLE else View.GONE
        }
    }

    companion object {
        /** Why the operator landed here without asking to (spec §2-§3); shown as the error text. */
        const val EXTRA_SIGNED_OUT_REASON = "signed_out_reason"
        private const val TAG = "LoginActivity"
    }

    /** Refresh the directory each time the broker link comes up (spec §4). */
    private val connectionListener: (Boolean) -> Unit = { connected ->
        if (connected) directory.refresh { list -> runOnUiThread { showOperators(list) } }
    }

    private val badgeReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == "com.rscja.scanner.action.scanner.RFID") {
                val tag = intent.getStringExtra("data") ?: return
                if (tag.isNotBlank()) attemptBadgeLogin(tag)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Already logged in (e.g. relaunched from recents) — straight to the dashboard.
        if (OperatorSessionHolder.session != null) {
            loggedIn = true // also blocks a badge scan racing the finish() below
            goHome()
            return
        }

        applyAppSystemBars()
        binding = ActivityLoginBinding.inflate(layoutInflater)
        setContentView(binding.root)
        forceLightStatusBarIcons()
        // Pad for the keyboard and, once it is up, bring Log In above it: the centred card left
        // the button a 41 px sliver under the IME (audit station1-10).
        binding.main.padForSystemBarsAndIme { imeVisible ->
            if (imeVisible) binding.scrollLogin.doOnNextLayout { binding.btnLogin.scrollIntoView() }
        }

        authClient = AuthClient(this)
        directory = OperatorDirectory(this)
        showOperators(directory.cached())
        MqttManager.getInstance(this).addConnectionStatusListener(connectionStatusListener)
        MqttManager.getInstance(this).addConnectionListener(connectionListener)

        binding.btnLogin.setOnClickListener { submitCredentials() }
        binding.etPassword.setOnSubmit { submitCredentials() }

        binding.btnSettings.setOnClickListener {
            startActivityForward(Intent(this, SettingsActivity::class.java))
        }

        binding.btnLogin.applyPressScaleFeedback()

        intent.getStringExtra(EXTRA_SIGNED_OUT_REASON)?.takeIf { it.isNotBlank() }
            ?.let { showError(it) }

        // Back from the launcher screen would drop to the Android home screen without warning —
        // easy to hit by accident on a shared handheld. Ask first, like Station 2.
        onBackPressedDispatcher.addCallback(this) { showExitAppDialog() }
    }

    override fun onResume() {
        super.onResume()
        val filter = IntentFilter("com.rscja.scanner.action.scanner.RFID")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(badgeReceiver, filter, Context.RECEIVER_EXPORTED)
        } else {
            registerReceiver(badgeReceiver, filter)
        }
    }

    override fun onPause() {
        super.onPause()
        unregisterReceiver(badgeReceiver)
    }

    /** Spec §2: with the station's presence offline no login can succeed — say so at once. */
    private fun stationIsOffline(): Boolean {
        val mqtt = MqttManager.getInstance(this)
        return mqtt.isConnected() && !mqtt.isStationOnline
    }

    private fun submitCredentials() {
        val username = binding.etUsername.text.toString().trim()
        val password = binding.etPassword.text.toString()
        if (username.isEmpty() || password.isEmpty()) {
            showError(getString(R.string.error_fill_all_fields))
            return
        }
        if (stationIsOffline()) {
            showError(getString(R.string.login_station_offline_banner))
            return
        }
        if (loginInFlight || loggedIn) return
        setLoggingIn(true)
        authClient.login(username, password) { result -> onLoginResult(result) }
    }

    private fun attemptBadgeLogin(badgeTag: String) {
        if (loginInFlight || loggedIn) return
        runOnUiThread {
            if (stationIsOffline()) {
                showError(getString(R.string.login_station_offline_banner))
                return@runOnUiThread
            }
            setLoggingIn(true)
            authClient.loginWithBadge(badgeTag) { result -> onLoginResult(result) }
        }
    }

    private fun onLoginResult(result: Result<OperatorSession>) {
        result
            .onSuccess {
                loggedIn = true
                goHome()
            }
            .onFailure { e ->
                setLoggingIn(false)
                Log.w(TAG, "Login failed: ${e.message}")
                showError(messageFor(e))
            }
    }

    /** Operator wording per failure kind; the station's text stays in logcat (station1-07). */
    private fun messageFor(e: Throwable): String = when ((e as? AuthFailure)?.kind) {
        AuthFailure.Kind.INVALID_CREDENTIALS -> getString(R.string.login_invalid_credentials)
        AuthFailure.Kind.BADGE_REJECTED -> getString(R.string.login_badge_rejected)
        AuthFailure.Kind.NOT_CONNECTED -> getString(R.string.login_not_connected)
        AuthFailure.Kind.TIMEOUT -> getString(R.string.login_timeout)
        else -> getString(R.string.login_failed_generic)
    }

    private fun setLoggingIn(inFlight: Boolean) {
        loginInFlight = inFlight
        binding.btnLogin.isEnabled = !inFlight
        binding.etUsername.isEnabled = !inFlight
        binding.etPassword.isEnabled = !inFlight
        binding.btnLogin.text = if (inFlight) "" else getString(R.string.btn_log_in)
        binding.progressLogin.visibility = if (inFlight) View.VISIBLE else View.GONE
        if (inFlight) binding.tvLoginError.visibility = View.GONE
    }

    /**
     * Dropdown rows read "username — Display Name"; username first so the adapter's prefix
     * filter narrows on what the operator types. Picking a row leaves only the username,
     * which is what SCRAM authenticates. Typing a name that is not listed still works.
     * The dropdown arrow is only shown once there is a list to open (audit station1-19).
     */
    private fun showOperators(list: List<OperatorEntry>) {
        operators = list.sortedBy { it.displayName.lowercase() }
        val labels = operators.map { "${it.username} — ${it.displayName}" }
        binding.etUsername.setAdapter(
            android.widget.ArrayAdapter(this, android.R.layout.simple_list_item_1, labels)
        )
        binding.tilUsername.endIconMode =
            if (operators.isEmpty()) TextInputLayout.END_ICON_NONE
            else TextInputLayout.END_ICON_DROPDOWN_MENU
        // An editable autocomplete does not open its list on tap, and an empty field filters
        // to nothing — so with a threshold of 0 we open it ourselves when the field is touched.
        binding.etUsername.setOnClickListener {
            if (operators.isNotEmpty()) binding.etUsername.showDropDown()
        }
        binding.etUsername.setOnItemClickListener { _, _, position, _ ->
            val label = binding.etUsername.adapter.getItem(position) as String
            val picked = operators.firstOrNull { "${it.username} — ${it.displayName}" == label }
            binding.etUsername.setText(picked?.username ?: label, false)
            binding.etPassword.requestFocus()
        }
    }

    private fun showError(message: String) {
        binding.tvLoginError.text = message
        binding.tvLoginError.visibility = View.VISIBLE
        // The line is above the fields; with the keyboard still up make sure it is on screen.
        binding.tvLoginError.post { binding.tvLoginError.scrollIntoView() }
    }

    private fun goHome() {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        startActivity(intent)
        finish()
    }

    override fun onDestroy() {
        super.onDestroy()
        if (::authClient.isInitialized) {
            MqttManager.getInstance(this).removeConnectionStatusListener(connectionStatusListener)
            MqttManager.getInstance(this).removeConnectionListener(connectionListener)
        }
    }
}
```

- [x] **Step 9: Compile, run all unit tests, verify**

```powershell
.\gradlew.bat :app:testDebugUnitTest --offline
.\gradlew.bat :app:assembleDebug -PemulatorNoJni --offline
```
Expected: both `BUILD SUCCESSFUL` (existing `OperatorListCodecTest`, `Schema41Test` etc. still pass). Install and on emulator-5554:
1. Login, tap Username → keyboard up → `uiautomator dump`: `btnLogin` bottom bound < IME top (≈1023); `tilUsername` is also fully visible.
2. `manager1` / `wrong` + Done → "Incorrect username or password" ABOVE the fields, y2 < 1023, and `adb logcat -d -s LoginActivity:W` shows `Login failed: SCRAM_PROOF rejected: … SCRAM proof rejected.`.
3. Tap the eye icon in Password → characters revealed; tap again → masked.
4. Set broker port to 9002 in Settings (unreachable) → back on Login the dropdown arrow is gone while `Offline`; restore 9001 → arrow returns when `Connected`.
5. Empty submit → "Please fill in all fields". Badge `BADGE000000000000000009` (unknown) → "Badge not recognised. Log in with your username and password."
6. Back → "Close the app?" [Stay | Close].

- [x] **Step 10: Commit**

```powershell
git add app/src/main/java/com/mitas/ppnam/station1aa/AuthFailure.kt app/src/test/java/com/mitas/ppnam/station1aa/AuthFailureTest.kt app/src/main/java/com/mitas/ppnam/station1aa/AuthClient.kt app/src/main/java/com/mitas/ppnam/station1aa/LoginActivity.kt app/src/main/res/layout/activity_login.xml app/src/main/res/values/strings.xml
git commit -m "fix(login): operator-facing auth errors, error line above fields, password toggle, Log In kept above the keyboard

Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01ExhLEukYAu1CqjJUWPR64Q"
```

---

### Task 8: Back on Home asks "Close the app?"; debounced tiles; operator chip never runs under the buttons (Tier 2, §7 item 12 + singles)

Closes: station1-05, static-04 (S1 row), station1-06 (double-tap), static-26 (`tvOperator` has no end constraint).

**Files:**
- Modify: `…\MainActivity.kt` (whole file)
- Modify: `app\src\main\res\layout\activity_main.xml:44-56`

**Interfaces:**
- Consumes: `showExitAppDialog()`, `showLogoutDialog()` (Task 4); `ClickDebouncer`, `setDebouncedClickListener` (Task 5); `applyAppSystemBars()`, `padForSystemBarsAndIme()` (Task 3).

- [x] **Step 1: Rewrite `MainActivity.kt`**

```kotlin
package com.mitas.ppnam.station1aa

import android.content.Intent
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.addCallback
import com.mitas.ppnam.station1aa.databinding.ActivityMainBinding

class MainActivity : SessionActivity() {

    private lateinit var binding: ActivityMainBinding

    /** One debouncer for the whole dashboard: a rapid double-tap opens one screen (station1-06). */
    private val tileDebouncer = ClickDebouncer()

    private val connectionStatusListener: (ConnectionStatus) -> Unit = { status ->
        runOnUiThread {
            binding.connectionPill.setStatus(status)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // No session (fresh process, or logged out) — the dashboard requires an operator.
        if (OperatorSessionHolder.session == null) {
            startActivity(Intent(this, LoginActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            })
            finish()
            return
        }

        applyAppSystemBars()
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        forceLightStatusBarIcons()
        binding.main.padForSystemBarsAndIme()

        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_HIDDEN)

        setupDashboard()

        MqttManager.getInstance(this).addConnectionStatusListener(connectionStatusListener)

        // Back on the dashboard asks before leaving, exactly like Login (audit station1-05): on a
        // shared handheld an accidental Back dropped the operator into the launcher unannounced.
        onBackPressedDispatcher.addCallback(this) { showExitAppDialog() }
    }

    private fun setupDashboard() {
        binding.tileTagAssignment.setDebouncedClickListener(tileDebouncer) {
            startActivityForward(Intent(this, TagAssignmentActivity::class.java))
        }

        binding.tileOffload.setDebouncedClickListener(tileDebouncer) {
            startActivityForward(Intent(this, OffloadActivity::class.java))
        }

        binding.btnSettings.setDebouncedClickListener(tileDebouncer) {
            startActivityForward(Intent(this, SettingsActivity::class.java))
        }

        // Operator control, mirroring Station 2's top bar: shows "name · role", tapping it asks
        // to log out.
        OperatorSessionHolder.session?.let { session ->
            binding.tvOperator.text =
                if (session.role.isNotBlank()) "${session.operatorName} · ${session.role}"
                else session.operatorName
        }
        binding.layoutOperator.setOnClickListener { showLogoutDialog() }

        // The login response decides which sub-apps this operator gets (allowedTabs, fail-closed
        // on a missing/empty list — display gating only, the station re-checks server-side).
        val session = OperatorSessionHolder.session
        setTileEnabled(binding.tileTagAssignment, session?.canShow(StationTab.TAG_ASSIGNMENT) ?: false)
        setTileEnabled(binding.tileOffload, session?.canShow(StationTab.OFFLOAD) ?: false)

        binding.tileTagAssignment.applyPressScaleFeedback()
        binding.tileOffload.applyPressScaleFeedback()
    }

    private fun setTileEnabled(view: com.google.android.material.card.MaterialCardView, enabled: Boolean) {
        view.isEnabled = enabled
        view.alpha = if (enabled) 1.0f else 0.5f
        view.isClickable = enabled
        view.isFocusable = enabled
    }

    override fun onDestroy() {
        super.onDestroy()
        MqttManager.getInstance(this).removeConnectionStatusListener(connectionStatusListener)
    }
}
```

- [x] **Step 2: Constrain the operator chip**

In `activity_main.xml` replace lines 44-56 (the opening tag of `layoutOperator` through its last attribute) with:
```xml
            <LinearLayout
                android:id="@+id/layoutOperator"
                android:layout_width="wrap_content"
                android:layout_height="wrap_content"
                android:layout_marginTop="8dp"
                android:layout_marginEnd="8dp"
                android:background="?attr/selectableItemBackground"
                android:clickable="true"
                android:focusable="true"
                android:gravity="center_vertical"
                android:orientation="horizontal"
                android:padding="4dp"
                app:layout_constrainedWidth="true"
                app:layout_constraintEnd_toStartOf="@id/btnSettings"
                app:layout_constraintHorizontal_bias="0"
                app:layout_constraintStart_toStartOf="parent"
                app:layout_constraintTop_toBottomOf="@id/imgLogo">
```
(the two child views `ImageView` and `tvOperator` stay as they are; `tvOperator` already has `maxLines="1"` + `ellipsize="end"`, so it now ellipsises before reaching the gear instead of running under it).

- [x] **Step 3: Compile and verify**

```powershell
.\gradlew.bat :app:assembleDebug -PemulatorNoJni --offline
```
Install; log in as `operator1`:
1. Back on the dashboard → "Close the app?" [Stay | Close]; Stay keeps the dashboard; Close leaves to the launcher (and relaunch returns to the dashboard — session unchanged).
2. `adb -s emulator-5554 shell input tap 300 800; adb -s emulator-5554 shell input tap 300 800` (two taps < 100 ms apart on the Tag Assignment tile) → one `TagAssignmentActivity` in `adb shell dumpsys activity activities | findstr TagAssignmentActivity` (previously two).
3. The operator chip bounds (uiautomator `layoutOperator`) end before `btnSettings` starts.

- [x] **Step 4: Commit**

```powershell
git add app/src/main/java/com/mitas/ppnam/station1aa/MainActivity.kt app/src/main/res/layout/activity_main.xml
git commit -m "fix(home): confirm before leaving on Back, debounce tile taps, keep the operator chip clear of the header buttons

Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01ExhLEukYAu1CqjJUWPR64Q"
```

---

### Task 9: Strings glossary — plurals, pill/diagnostics vocabulary, timeout wording (Tier 2 item 13 + Tier 3 item 21)

Closes: station1-13 ("1 minutes"), section 5 "Pill vocabulary" row (Diagnostics broker row uses the pill's words; `Disconnected` → `Offline`), static-18 (S1 part), static-08 / section 5 "Timeout" row (S3 wording, 10 s single attempt — the `Retry` buttons land in Tasks 11-12).

**Files:**
- Modify: `strings.xml` (lines 3-5, 60-63, 100-102)
- Modify: `…\SessionGuard.kt:36-39`
- Modify: `…\ConnectionPillView.kt:56-71`
- Modify: `…\SettingsActivity.kt:139-161` (`updateDiagnostics`)

**Interfaces:**
- Produces: strings `status_connected`, `status_reconnecting`, `status_offline`, `status_station_offline`, `status_online`, `status_unknown`, `status_no_response` (new text), `btn_retry`; plurals `signed_out_inactivity` (replaces the string of the same name).

- [x] **Step 1: Strings**

Replace lines 3-5 of `strings.xml`:
```xml
    <string name="status_online">Online</string>
    <string name="status_offline">Offline</string>
    <string name="status_connecting">Connecting</string>
```
with:
```xml
    <!-- Connection vocabulary (audit §5 "Pill vocabulary"): the top-bar pill and the Settings
         Diagnostics broker row use exactly these words; the station row uses Online/Offline/Unknown. -->
    <string name="status_connected">Connected</string>
    <string name="status_reconnecting">Reconnecting</string>
    <string name="status_offline">Offline</string>
    <string name="status_station_offline">Station 1 offline</string>
    <string name="status_online">Online</string>
    <string name="status_unknown">Unknown</string>
```

Replace lines 60-63 (`<!-- Shared send states -->` block):
```xml
    <!-- Shared send states. Timeout wording and the explicit Retry follow Station 3 (audit §5). -->
    <string name="status_sending">Sending…</string>
    <string name="status_send_failed">Failed to send — check connection</string>
    <string name="status_no_response">Station 1 did not respond. Check the station and retry.</string>
    <string name="btn_retry">Retry</string>
```

Replace line 102 `<string name="signed_out_inactivity">Signed out after %1$d minutes of inactivity.</string>` with:
```xml
    <plurals name="signed_out_inactivity">
        <item quantity="one">Signed out after 1 minute of inactivity.</item>
        <item quantity="other">Signed out after %d minutes of inactivity.</item>
    </plurals>
```

- [x] **Step 2: `SessionGuard.kt` uses the plural**

Lines 36-39:
```kotlin
            onExpired = {
                val minutes = SettingsRepository(app).autoLogoutMinutes()
                signOut(app.getString(R.string.signed_out_inactivity, minutes))
            },
```
→
```kotlin
            onExpired = {
                val minutes = SettingsRepository(app).autoLogoutMinutes()
                signOut(app.resources.getQuantityString(R.plurals.signed_out_inactivity, minutes, minutes))
            },
```

- [x] **Step 3: `ConnectionPillView.kt` reads the glossary**

Replace lines 63-69:
```kotlin
        // Same wording as Station 2's pill ("Station 2 offline"), with this station's number.
        val text = when (status) {
            ConnectionStatus.CONNECTED -> "Connected"
            ConnectionStatus.RECONNECTING -> "Reconnecting"
            ConnectionStatus.STATION_OFFLINE -> "Station 1 offline"
            ConnectionStatus.OFFLINE -> "Offline"
        }
```
with:
```kotlin
        // Same wording as Station 2's pill ("Station 2 offline"), with this station's number.
        val text = context.getString(
            when (status) {
                ConnectionStatus.CONNECTED -> R.string.status_connected
                ConnectionStatus.RECONNECTING -> R.string.status_reconnecting
                ConnectionStatus.STATION_OFFLINE -> R.string.status_station_offline
                ConnectionStatus.OFFLINE -> R.string.status_offline
            }
        )
```

- [x] **Step 4: `SettingsActivity.updateDiagnostics` — same words and colours as the pill**

Replace lines 139-161 with:
```kotlin
    private fun updateDiagnostics(status: ConnectionStatus) {
        val green = getColor(R.color.success)
        val amber = getColor(R.color.warning)
        val red = getColor(R.color.danger)
        val muted = getColor(R.color.text_muted)

        // Same three words and colours as the top-bar pill (audit §5 "Pill vocabulary").
        when (status) {
            ConnectionStatus.CONNECTED, ConnectionStatus.STATION_OFFLINE ->
                binding.pillBroker.setAppearance(green, getString(R.string.status_connected))
            ConnectionStatus.RECONNECTING ->
                binding.pillBroker.setAppearance(amber, getString(R.string.status_reconnecting))
            ConnectionStatus.OFFLINE ->
                binding.pillBroker.setAppearance(red, getString(R.string.status_offline))
        }

        // With the broker down, the retained presence value is stale rather than false — saying
        // "offline" there would blame the station for the broker's fault.
        when (status) {
            ConnectionStatus.CONNECTED ->
                binding.pillStation.setAppearance(green, getString(R.string.status_online))
            ConnectionStatus.STATION_OFFLINE ->
                binding.pillStation.setAppearance(amber, getString(R.string.status_offline))
            else -> binding.pillStation.setAppearance(muted, getString(R.string.status_unknown))
        }
    }
```

- [x] **Step 5: Compile and verify**

```powershell
.\gradlew.bat :app:assembleDebug -PemulatorNoJni --offline
```
(Build fails if any reference to the old `R.string.signed_out_inactivity` string remains — there is only the one in `SessionGuard`.) Install; Settings → set auto sign-out to `1` → Test & Apply (Task 10 not yet done: tap "Save & Restart") → log in → wait ~65 s → Login shows "Signed out after 1 minute of inactivity." Settings with port 9002: Diagnostics broker row reads "Offline" (red), station row "Unknown"; with 9001: "Connected" / "Online".

- [x] **Step 6: Commit**

```powershell
git add app/src/main/res/values/strings.xml app/src/main/java/com/mitas/ppnam/station1aa/SessionGuard.kt app/src/main/java/com/mitas/ppnam/station1aa/ConnectionPillView.kt app/src/main/java/com/mitas/ppnam/station1aa/SettingsActivity.kt
git commit -m "fix(copy): plural inactivity message, one connection vocabulary, Station 3 timeout wording

Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01ExhLEukYAu1CqjJUWPR64Q"
```

---

### Task 10: Settings — "Test & Apply" with validation, confirmation, IME chain, themed switches (Tier 3 item 20)

Closes: static-06 (Save & Restart vs Test & Apply), static-20 (password blank + toggle = keep; S1 already), section 5 "Settings action & field set" row (host/port validation + visible "Connected — settings saved" + auto sign-out minutes field), static-26 (auto sign-out hint too long), S3-08/S5-08-style switch tint (same layout as S1), group (b) remainder (Done on the last field applies). Diagnostics row order (MQTT Broker, Station 1, Version, Device ID) is already the S1 standard — no change (static-17).

**Files:**
- Modify: `app\src\main\res\layout\activity_settings.xml` (fields block + button block: the lines that were 312-436 before Task 6; search for `tilBrokerHost` and `btnSaveSettings`)
- Modify: `…\SettingsActivity.kt` (whole file — final version)
- Modify: `strings.xml` (Settings block)
- Modify: `tools\test_campaign\settings_section.py` (case S8 expectations)

**Interfaces:**
- Consumes: `PinLockout`, `PrefsPinLockoutStore` (Task 6); `setOnSubmit` (Task 5); `showLogoutDialog()` (Task 4); `applyAppSystemBars()`, `padForSystemBarsAndIme()`, `scrollIntoView()` (Task 3); `MqttManager.disconnect(onComplete)`, `MqttManager.connect()`, `MqttManager.addConnectionListener((Boolean) -> Unit)` (existing; the listener is replayed synchronously with the current state on add).
- Produces: view ids `layoutApplyStatus`, `progressApply`, `tvApplyStatus`; strings `btn_test_apply`, `apply_testing`, `apply_success`, `apply_failed`, `error_host_required`, `error_port_invalid`, `error_credentials_required`, `error_password_store`, `helper_auto_logout_minutes`; `hint_auto_logout_minutes` re-worded.

- [x] **Step 1: Strings**

In the `<!-- Settings -->` block of `strings.xml` replace
```xml
    <string name="hint_auto_logout_minutes">Auto sign-out after (minutes, 0 = never)</string>
    <string name="error_auto_logout_minutes">Enter 0–1440</string>
```
with
```xml
    <string name="hint_auto_logout_minutes">Auto sign-out (minutes)</string>
    <string name="helper_auto_logout_minutes">0 = never, up to 1440</string>
    <string name="error_auto_logout_minutes">Enter 0–1440</string>
    <string name="error_host_required">Host required</string>
    <string name="error_port_invalid">Invalid port (1–65535)</string>
    <string name="error_credentials_required">Enter the broker username and password</string>
    <string name="error_password_store">Could not store the password securely</string>
    <!-- Test &amp; Apply (audit §5 "Settings action"): test in place, keep the session, confirm. -->
    <string name="btn_test_apply">Test &amp; Apply</string>
    <string name="apply_testing">Testing connection…</string>
    <string name="apply_success">Connected — settings saved</string>
    <string name="apply_failed">Could not connect to %1$s:%2$d. Check the broker settings.</string>
```

- [x] **Step 2: Layout — IME chain, MaterialSwitch, helper text, status row, button**

In `activity_settings.xml`, inside `groupSettingsFields`:

(a) `etBrokerHost` element: add `android:imeOptions="actionNext"` and `android:maxLines="1"`.
(b) `etBrokerPort` element: add `android:imeOptions="actionNext"` and `android:maxLines="1"`.
(c) Replace the two `com.google.android.material.switchmaterial.SwitchMaterial` elements with (ids and attributes unchanged apart from the class):
```xml
                        <com.google.android.material.materialswitch.MaterialSwitch
                            android:id="@+id/swBrokerWebSocket"
                            android:layout_width="match_parent"
                            android:layout_height="wrap_content"
                            android:layout_marginTop="12dp"
                            android:text="@string/label_broker_websocket"
                            android:textColor="@color/text_primary_dark" />

                        <com.google.android.material.materialswitch.MaterialSwitch
                            android:id="@+id/swBrokerTls"
                            android:layout_width="match_parent"
                            android:layout_height="wrap_content"
                            android:text="@string/label_broker_tls"
                            android:textColor="@color/text_primary_dark" />
```
(`MaterialSwitch` takes its track from `colorPrimary`/`colorSurfaceVariant`/`colorOutline`, all set in the theme — no more near-white tracks.)
(d) `etBrokerUsername`: add `android:imeOptions="actionNext"` and `android:maxLines="1"`.
(e) `tilBrokerPassword`: replace `app:passwordToggleEnabled="true"` with `app:endIconMode="password_toggle"` and `app:endIconTint="@color/text_muted"`; `etBrokerPassword`: add `android:imeOptions="actionNext"` and `android:maxLines="1"`.
(f) `tilAutoLogout`: add `app:helperText="@string/helper_auto_logout_minutes"` and `app:helperTextEnabled="true"`; `etAutoLogout`: add `android:imeOptions="actionDone"` and `android:maxLines="1"`.
(g) Replace the `btnSaveSettings` element (the `MaterialButton` with text `Save &amp; Restart`) with:
```xml
                <!-- Test & Apply feedback row (Station 2's "Testing connection…" / "Connected —
                     settings saved" / failure line), in place of the old relaunch. -->
                <LinearLayout
                    android:id="@+id/layoutApplyStatus"
                    android:layout_width="match_parent"
                    android:layout_height="wrap_content"
                    android:layout_marginTop="16dp"
                    android:gravity="center_vertical"
                    android:orientation="horizontal"
                    android:visibility="gone"
                    tools:visibility="visible">

                    <ProgressBar
                        android:id="@+id/progressApply"
                        android:layout_width="20dp"
                        android:layout_height="20dp"
                        android:layout_marginEnd="10dp"
                        android:indeterminateTint="@color/accent_action" />

                    <TextView
                        android:id="@+id/tvApplyStatus"
                        android:layout_width="0dp"
                        android:layout_height="wrap_content"
                        android:layout_weight="1"
                        android:textColor="@color/text_muted"
                        android:textSize="15sp"
                        tools:text="Testing connection…" />
                </LinearLayout>

                <com.google.android.material.button.MaterialButton
                    android:id="@+id/btnSaveSettings"
                    android:layout_width="match_parent"
                    android:layout_height="56dp"
                    android:layout_marginTop="12dp"
                    android:text="@string/btn_test_apply"
                    app:backgroundTint="@color/primary_action"
                    app:cornerRadius="14dp" />
```

- [x] **Step 3: Final `SettingsActivity.kt`**

Replace the whole file (this is the canonical Settings S3 and S5 copy; S3/S5 omit the auto sign-out field unless they add it):

```kotlin
package com.mitas.ppnam.station1aa

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.MenuItem
import android.view.View
import androidx.activity.addCallback
import com.google.android.material.textfield.TextInputLayout
import com.mitas.ppnam.station1aa.databinding.ActivitySettingsBinding

/**
 * Supervisor settings behind a persisted PIN gate. "Test & Apply" (audit §5) validates the
 * form, reconnects to the typed broker in place, reports the verdict inline and keeps the
 * operator's session — nothing relaunches. Canonical for the XML stations (S3/S5 copy this).
 */
class SettingsActivity : SessionActivity() {

    private lateinit var binding: ActivitySettingsBinding
    private lateinit var settingsRepository: SettingsRepository
    private lateinit var pinLockout: PinLockout
    private val mainHandler = Handler(Looper.getMainLooper())

    private val connectionStatusListener: (ConnectionStatus) -> Unit = { status ->
        runOnUiThread {
            binding.connectionPill.setStatus(status)
            updateDiagnostics(status)
        }
    }

    /** 1 s ticker while locked out: counts the message down and re-enables Unlock at the end. */
    private val lockoutTicker = object : Runnable {
        override fun run() { renderLockout() }
    }

    /** Test & Apply in flight: the one-shot connection listener, its deadline and what it tests. */
    private var applyListener: ((Boolean) -> Unit)? = null
    private var applySettings: BrokerSettings? = null
    private val applyTimeout = Runnable { finishApply(connected = false) }

    private enum class ApplyState { IDLE, TESTING, SUCCESS, FAILED }

    private companion object {
        const val CORRECT_PIN = "079545"
        const val KEY_UNLOCKED = "settings_unlocked"
        /** Same window as AuthClient/WorkflowClient: one attempt, 10 s. */
        const val APPLY_TIMEOUT_MS = 10_000L
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        applyAppSystemBars()
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        forceLightStatusBarIcons()
        binding.main.padForSystemBarsAndIme()

        setupToolbar()
        MqttManager.getInstance(this).addConnectionStatusListener(connectionStatusListener)

        binding.tvVersion.text = "v${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})"
        binding.tvDeviceId.text = DeviceIdentity.deviceId(this)
        setupSessionSection()

        settingsRepository = SettingsRepository(this)
        val current = settingsRepository.brokerSettings()

        binding.etBrokerHost.setText(current.host)
        binding.etBrokerPort.setText(current.port.toString())
        binding.swBrokerWebSocket.isChecked = current.useWebSocket
        binding.swBrokerTls.isChecked = current.useTls
        binding.etBrokerUsername.setText(current.username)
        binding.etAutoLogout.setText(settingsRepository.autoLogoutMinutes().toString())
        // The password field stays empty: the stored credential is never echoed back into the UI.
        // A blank field on apply means "keep the provisioned password" (see testAndApply).

        binding.btnUnlock.setOnClickListener { submitPin() }
        binding.etPin.setOnSubmit { submitPin() }
        pinLockout = PinLockout(CORRECT_PIN, PrefsPinLockoutStore(this), System::currentTimeMillis)
        // A lockout in progress must be visible (and Unlock disabled) the moment the screen opens,
        // and an unlocked form must survive recreation without asking for the PIN again.
        if (savedInstanceState?.getBoolean(KEY_UNLOCKED) == true) showUnlocked() else renderLockout()

        binding.btnSaveSettings.setOnClickListener { testAndApply() }
        // Done on the last field applies (audit group b); Next chains through the ones above.
        binding.etAutoLogout.setOnSubmit { testAndApply() }

        // A field error clears as soon as the supervisor edits that field.
        clearErrorOnEdit(binding.tilBrokerHost)
        clearErrorOnEdit(binding.tilBrokerPort)
        clearErrorOnEdit(binding.tilBrokerPassword)
        clearErrorOnEdit(binding.tilAutoLogout)

        binding.btnUnlock.applyPressScaleFeedback()
        binding.btnSaveSettings.applyPressScaleFeedback()
        binding.btnLogOut.applyPressScaleFeedback()

        onBackPressedDispatcher.addCallback(this) { finishBackward() }
    }

    private fun setupToolbar() {
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
    }

    private fun clearErrorOnEdit(layout: TextInputLayout) {
        layout.editText?.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) { layout.error = null }
        })
    }

    // ---- Diagnostics -----------------------------------------------------------------------------

    /**
     * The Diagnostics card, mirroring Station 2's SettingsScreen: broker link and station
     * presence are separate failures with separate remedies, and the composite pill can only
     * name one of them at a time — so both get their own row here. Row order (MQTT Broker,
     * Station 1, Version, Device ID) is the suite standard (audit §5).
     */
    private fun updateDiagnostics(status: ConnectionStatus) {
        val green = getColor(R.color.success)
        val amber = getColor(R.color.warning)
        val red = getColor(R.color.danger)
        val muted = getColor(R.color.text_muted)

        // Same three words and colours as the top-bar pill (audit §5 "Pill vocabulary").
        when (status) {
            ConnectionStatus.CONNECTED, ConnectionStatus.STATION_OFFLINE ->
                binding.pillBroker.setAppearance(green, getString(R.string.status_connected))
            ConnectionStatus.RECONNECTING ->
                binding.pillBroker.setAppearance(amber, getString(R.string.status_reconnecting))
            ConnectionStatus.OFFLINE ->
                binding.pillBroker.setAppearance(red, getString(R.string.status_offline))
        }

        // With the broker down, the retained presence value is stale rather than false — saying
        // "offline" there would blame the station for the broker's fault.
        when (status) {
            ConnectionStatus.CONNECTED ->
                binding.pillStation.setAppearance(green, getString(R.string.status_online))
            ConnectionStatus.STATION_OFFLINE ->
                binding.pillStation.setAppearance(amber, getString(R.string.status_offline))
            else -> binding.pillStation.setAppearance(muted, getString(R.string.status_unknown))
        }
    }

    // ---- Session card ----------------------------------------------------------------------------

    /**
     * The Session card, mirroring Station 2's: the home screen's operator label is one route to
     * switching users, and Settings is the obvious second home for it.
     */
    private fun setupSessionSection() {
        val session = OperatorSessionHolder.session
        if (session == null) {
            binding.groupSession.visibility = View.GONE
            return
        }
        binding.groupSession.visibility = View.VISIBLE
        binding.tvSignedInAs.text =
            if (session.role.isNotBlank()) "${session.operatorName} · ${session.role}"
            else session.operatorName
        binding.btnLogOut.setOnClickListener { showLogoutDialog() }
    }

    // ---- PIN gate --------------------------------------------------------------------------------

    private fun submitPin() {
        when (val outcome = pinLockout.submit(binding.etPin.text.toString())) {
            PinLockout.Outcome.Blank -> showErrorMessage(getString(R.string.pin_blank))
            PinLockout.Outcome.Unlocked -> {
                hidePinMessages()
                binding.etPin.setText("")
                showUnlocked()
            }
            is PinLockout.Outcome.Rejected -> {
                binding.etPin.setText("")
                showErrorMessage(
                    resources.getQuantityString(
                        R.plurals.pin_attempts_left, outcome.attemptsLeft, outcome.attemptsLeft,
                    )
                )
            }
            is PinLockout.Outcome.LockedOut -> {
                binding.etPin.setText("")
                renderLockout()
            }
        }
    }

    private fun showUnlocked() {
        binding.cardPinLock.visibility = View.GONE
        binding.groupSettingsFields.visibility = View.VISIBLE
    }

    /**
     * Reflects the persisted lockout: message with the live countdown, field and Unlock disabled,
     * re-armed every second until it expires (audit station1-16).
     */
    private fun renderLockout() {
        mainHandler.removeCallbacks(lockoutTicker)
        val remainingMs = pinLockout.remainingLockoutMs()
        if (remainingMs <= 0L) {
            binding.tvPinLockout.visibility = View.GONE
            binding.etPin.isEnabled = true
            binding.btnUnlock.isEnabled = true
            return
        }
        val seconds = ((remainingMs + 999) / 1_000).toInt()
        binding.tvPinLockout.text = getString(R.string.pin_locked_out, seconds)
        binding.tvPinLockout.visibility = View.VISIBLE
        binding.tvPinError.visibility = View.GONE
        binding.etPin.isEnabled = false
        binding.btnUnlock.isEnabled = false
        mainHandler.postDelayed(lockoutTicker, 1_000L)
    }

    private fun showErrorMessage(message: String) {
        binding.tvPinError.text = message
        binding.tvPinError.visibility = View.VISIBLE
        binding.tvPinLockout.visibility = View.GONE
    }

    private fun hidePinMessages() {
        binding.tvPinError.visibility = View.GONE
        binding.tvPinLockout.visibility = View.GONE
    }

    // ---- Test & Apply ----------------------------------------------------------------------------

    /**
     * Validate, then: disconnect from the old broker (its retained presence goes offline), save,
     * reconnect against the new values and wait for the verdict. Success and failure are both
     * shown inline; the screen, the form and the operator's session all stay (audit static-06).
     */
    private fun testAndApply() {
        if (applyListener != null) return // a test is already running

        val host = binding.etBrokerHost.text.toString().trim()
        if (host.isBlank()) return showFieldError(binding.tilBrokerHost, R.string.error_host_required)
        val port = BrokerSettings.parsePort(binding.etBrokerPort.text.toString())
            ?: return showFieldError(binding.tilBrokerPort, R.string.error_port_invalid)
        val autoLogoutMinutes = AutoLogout.parseMinutes(binding.etAutoLogout.text.toString())
            ?: return showFieldError(binding.tilAutoLogout, R.string.error_auto_logout_minutes)

        val typedPassword = binding.etBrokerPassword.text.toString()
        val newSettings = BrokerSettings(
            host = host,
            port = port,
            useWebSocket = binding.swBrokerWebSocket.isChecked,
            useTls = binding.swBrokerTls.isChecked,
            username = binding.etBrokerUsername.text.toString().trim(),
            // Blank field keeps the already-provisioned password: the repository only
            // writes a non-blank password to the Keystore.
            password = typedPassword.ifBlank { settingsRepository.brokerSettings().password },
        )
        // MqttManager.connect() refuses to dial without a credential and would never report
        // back — say so here instead of letting the test time out.
        if (!newSettings.hasBrokerCredential) {
            return showFieldError(binding.tilBrokerPassword, R.string.error_credentials_required)
        }

        applySettings = newSettings
        showApplyState(ApplyState.TESTING, getString(R.string.apply_testing))

        val mqtt = MqttManager.getInstance(this)
        // 1. Properly disconnect from the OLD broker first
        mqtt.disconnect {
            runOnUiThread {
                // 2. Save the new settings after the old presence is offline
                settingsRepository.saveAutoLogoutMinutes(autoLogoutMinutes)
                SessionGuard.applyTimeout()
                if (!settingsRepository.save(newSettings)) {
                    applySettings = null
                    showApplyState(ApplyState.IDLE, "")
                    showFieldError(binding.tilBrokerPassword, R.string.error_password_store)
                    mqtt.connect()
                    return@runOnUiThread
                }

                // 3. Reconnect against the new broker and wait for the verdict. addConnectionListener
                //    replays the current (disconnected) state synchronously — skip that first call.
                var replayed = false
                val listener: (Boolean) -> Unit = { connected ->
                    if (!replayed) {
                        replayed = true
                    } else {
                        runOnUiThread { finishApply(connected) }
                    }
                }
                applyListener = listener
                mqtt.addConnectionListener(listener)
                mainHandler.postDelayed(applyTimeout, APPLY_TIMEOUT_MS)
                mqtt.connect()
            }
        }
    }

    private fun finishApply(connected: Boolean) {
        val listener = applyListener ?: return
        applyListener = null
        mainHandler.removeCallbacks(applyTimeout)
        MqttManager.getInstance(this).removeConnectionListener(listener)
        val tested = applySettings
        applySettings = null
        if (connected) {
            showApplyState(ApplyState.SUCCESS, getString(R.string.apply_success))
        } else {
            showApplyState(
                ApplyState.FAILED,
                getString(R.string.apply_failed, tested?.host ?: "", tested?.port ?: 0),
            )
        }
    }

    private fun showApplyState(state: ApplyState, message: String) {
        binding.layoutApplyStatus.visibility = if (state == ApplyState.IDLE) View.GONE else View.VISIBLE
        binding.progressApply.visibility = if (state == ApplyState.TESTING) View.VISIBLE else View.GONE
        binding.tvApplyStatus.text = message
        binding.tvApplyStatus.setTextColor(
            getColor(
                when (state) {
                    ApplyState.SUCCESS -> R.color.success
                    ApplyState.FAILED -> R.color.danger
                    else -> R.color.text_muted
                }
            )
        )
        binding.btnSaveSettings.isEnabled = state != ApplyState.TESTING
        if (state != ApplyState.IDLE) binding.layoutApplyStatus.post { binding.layoutApplyStatus.scrollIntoView() }
    }

    /** Inline field error (not the floating EditText popup), focused and scrolled into view. */
    private fun showFieldError(layout: TextInputLayout, messageRes: Int) {
        layout.error = getString(messageRes)
        layout.editText?.requestFocus()
        layout.post { layout.scrollIntoView() }
    }

    // ---- lifecycle -------------------------------------------------------------------------------

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(KEY_UNLOCKED, binding.groupSettingsFields.visibility == View.VISIBLE)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) {
            finishBackward()
            return true
        }
        return super.onOptionsItemSelected(item)
    }

    override fun onDestroy() {
        mainHandler.removeCallbacks(lockoutTicker)
        mainHandler.removeCallbacks(applyTimeout)
        applyListener?.let { MqttManager.getInstance(this).removeConnectionListener(it) }
        applyListener = null
        MqttManager.getInstance(this).removeConnectionStatusListener(connectionStatusListener)
        super.onDestroy()
    }
}
```

- [x] **Step 4: Campaign script expectation (case S8)**

In `tools\test_campaign\settings_section.py` the S8 case asserted the old relaunch. Replace the lines
```python
        with c.case("S8", "Save with unchanged values restarts and reconnects (blank password kept)") as case:
```
through
```python
            expect(d.find(id="etUsername", retries=10) is not None,
                   "app did not land back on the login screen")
```
with:
```python
        with c.case("S8", "Test & Apply with unchanged values reconnects in place (blank password kept)") as case:
            base = len(sim.events())
            save = d.scroll_to("btnSaveSettings")
            expect(save is not None, "Test & Apply button not reachable")
            d.tap(xy=save.center)
            time.sleep(6)
            # the app stays on Settings; device presence must come back online
            presence = sim.wait_for(
                lambda e: e["dir"] == "presence" and e["topic"].endswith(DEVICE_ID)
                and e["payload"] == "online", since=base, timeout=25)
            expect(presence is not None, "scanner did not republish online presence after apply")
            status = d.find(id="tvApplyStatus", retries=10)
            expect(status is not None and "settings saved" in status.text.lower(),
                   f"apply status {status and status.text!r}")
```
(the pill loop and screenshot that follow stay as they are).

- [x] **Step 5: Compile, unit tests, verify**

```powershell
.\gradlew.bat :app:testDebugUnitTest --offline
.\gradlew.bat :app:assembleDebug -PemulatorNoJni --offline
```
Install; log in as `operator1`, open Settings, unlock:
1. Tap Test & Apply with current values → row shows a spinner + "Testing connection…", button disabled; within ~3 s "Connected — settings saved" in green; the screen stays, the session card still shows "Operator One · Operator", Back returns to the dashboard still logged in.
2. Port `9002` → Test & Apply → after ≤10 s "Could not connect to 10.0.2.2:9002. Check the broker settings." in red; pill `Reconnecting`/`Offline`. Restore `9001` → green again.
3. Clear Host → Test & Apply → inline "Host required" under the Host field (no floating popup), field focused; type one character → error clears. Port `0` → "Invalid port (1–65535)". Auto sign-out `5000` → "Enter 0–1440".
4. Review Focus 2: `adb -s emulator-5554 shell pm clear com.mitas.ppnam.station1aa` (wipes the stored credential), relaunch, Settings → unlock → leave Username/Password blank → Test & Apply → "Enter the broker username and password" under Password; `adb logcat -d -s MqttManager:I` shows no new "Connected"/"Connection failed" line. Re-provision `test`/`test`.
5. Tap Password field → keyboard → press the IME Next key through to Auto sign-out → Done → Test & Apply runs (status row appears).
6. Switch tracks: WebSocket/TLS switches render with the brand-blue track when on, graphite when off (no pink/lavender).
7. Run `python tools\test_campaign\settings_section.py` if the campaign harness is set up (optional; it needs the simulator from `tools\station_sim.py`).

- [x] **Step 6: Commit**

```powershell
git add app/src/main/res/layout/activity_settings.xml app/src/main/java/com/mitas/ppnam/station1aa/SettingsActivity.kt app/src/main/res/values/strings.xml tools/test_campaign/settings_section.py
git commit -m "feat(settings): Test & Apply in place with validation and inline confirmation; IME chain; themed switches

Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01ExhLEukYAu1CqjJUWPR64Q"
```

---

### Task 11: Offload — spinner + Retry rows, timeout wording, stable validation row, close reason in the dialog, dialog fixes (Tier 3 items 21 + 24)

Closes: station1-08 (rejected close reason hidden behind the re-shown dialog), station1-11 (stale validation text; Confirm jumps 100 px), station1-17 ("Pallet recorded" dismissable by scrim/Back), station1-18 (tag-count field not auto-focused), station1-21 (no spinner while "Sending…"), static-08 (Retry affordance), group (j) S1 (session-ended reason on Login).

**Files:**
- Modify: `app\src\main\res\layout\activity_offload.xml` (the `tvScanStatus` element, lines 119-128; the `tvConfirmStatus` element, lines 236-245; the three edit fields' `imeOptions`)
- Modify: `…\OffloadActivity.kt` (whole file — final version)

**Interfaces:**
- Consumes: `applyAppSystemBars()`, `padForSystemBarsAndIme()` (Task 3); `neutralDialog()` (Task 4); `setOnSubmit` (Task 5); strings `status_no_response` (new text), `btn_retry`, `signed_out_session_ended` (Tasks 7, 9).
- Produces: view ids `layoutScanStatus`, `progressScan`, `btnRetryScan`, `layoutConfirmStatus`, `progressConfirm`, `btnRetryConfirm` (existing `tvScanStatus` / `tvConfirmStatus` keep their ids).

- [x] **Step 1: Layout — status rows and IME options**

(a) Replace the `tvScanStatus` `TextView` (lines 119-128) with:
```xml
                    <!-- Status row like Station 3's: spinner while pending (audit station1-21),
                         text, and an explicit Retry after a timeout/send failure (audit §5). -->
                    <LinearLayout
                        android:id="@+id/layoutScanStatus"
                        android:layout_width="match_parent"
                        android:layout_height="wrap_content"
                        android:layout_marginTop="12dp"
                        android:gravity="center_vertical"
                        android:orientation="horizontal"
                        android:visibility="gone"
                        tools:visibility="visible">

                        <ProgressBar
                            android:id="@+id/progressScan"
                            android:layout_width="20dp"
                            android:layout_height="20dp"
                            android:layout_marginEnd="10dp"
                            android:indeterminateTint="@color/accent_action"
                            android:visibility="gone"
                            tools:visibility="visible" />

                        <TextView
                            android:id="@+id/tvScanStatus"
                            android:layout_width="0dp"
                            android:layout_height="wrap_content"
                            android:layout_weight="1"
                            android:textColor="@color/text_muted"
                            android:textSize="15sp"
                            tools:text="Tag and barcode match" />

                        <com.google.android.material.button.MaterialButton
                            android:id="@+id/btnRetryScan"
                            style="@style/Widget.SysOneScanner.Button.OutlinedButton"
                            android:layout_width="wrap_content"
                            android:layout_height="wrap_content"
                            android:layout_marginStart="8dp"
                            android:text="@string/btn_retry"
                            android:textColor="@color/text_primary"
                            android:visibility="gone"
                            app:strokeColor="@color/border_primary"
                            tools:visibility="visible" />
                    </LinearLayout>
```

(b) Replace the `tvConfirmStatus` `TextView` (lines 236-245) with the same row shape, but reserving height so Confirm Offload never moves when a message appears (audit station1-11):
```xml
                    <LinearLayout
                        android:id="@+id/layoutConfirmStatus"
                        android:layout_width="match_parent"
                        android:layout_height="wrap_content"
                        android:layout_marginTop="12dp"
                        android:gravity="center_vertical"
                        android:minHeight="24dp"
                        android:orientation="horizontal"
                        android:visibility="invisible"
                        tools:visibility="visible">

                        <ProgressBar
                            android:id="@+id/progressConfirm"
                            android:layout_width="20dp"
                            android:layout_height="20dp"
                            android:layout_marginEnd="10dp"
                            android:indeterminateTint="@color/accent_action"
                            android:visibility="gone"
                            tools:visibility="visible" />

                        <TextView
                            android:id="@+id/tvConfirmStatus"
                            android:layout_width="0dp"
                            android:layout_height="wrap_content"
                            android:layout_weight="1"
                            android:textColor="@color/text_muted"
                            android:textSize="15sp"
                            tools:text="Offload recorded" />

                        <com.google.android.material.button.MaterialButton
                            android:id="@+id/btnRetryConfirm"
                            style="@style/Widget.SysOneScanner.Button.OutlinedButton"
                            android:layout_width="wrap_content"
                            android:layout_height="wrap_content"
                            android:layout_marginStart="8dp"
                            android:text="@string/btn_retry"
                            android:textColor="@color/text_primary"
                            android:visibility="gone"
                            app:strokeColor="@color/border_primary"
                            tools:visibility="visible" />
                    </LinearLayout>
```

(c) IME chain on the edit step: `etBagWeight` add `android:imeOptions="actionNext"`; `etBagCount` add `android:imeOptions="actionNext"`; `etBatchRef` add `android:imeOptions="actionDone"`.

- [x] **Step 2: Final `OffloadActivity.kt`**

Replace the whole file:

```kotlin
package com.mitas.ppnam.station1aa

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Bundle
import android.view.MenuItem
import android.view.View
import android.view.WindowManager
import androidx.activity.addCallback
import androidx.appcompat.app.AlertDialog
import com.mitas.ppnam.station1aa.databinding.ActivityOffloadBinding
import org.json.JSONObject

/**
 * Offload (contract v3.1.0 §6):
 *
 *  1. Scan the pallet's RFID tag and barcode, then Match Pallet -> `offload_scan`. A matched
 *     result carries the pallet's expected bagWeight/bagCount/batchReference as prefill plus
 *     the open document the pallet belongs to (documentType/documentNumber and pallet
 *     progress) — the scanner is never locked to a document; the reference is per-pallet
 *     metadata from this lookup.
 *  2. Review/edit the prefilled values, then Confirm Offload -> `offload_confirm` with the
 *     final typed values and the document reference repeated verbatim. The station
 *     re-validates the pair at confirm time, so no client-side pairing state must survive
 *     between the two steps.
 *  3. After each accepted confirm: "Are you done?" — Done closes the looked-up document as
 *     Short Tags / Complete / Over Tags via `offload_complete` and returns to the home
 *     screen; Next Pallet just keeps scanning. A short or over close asks how many tags the
 *     receipt differs by and sends it as `shortTagCount`/`overTagCount` (§6.4).
 *
 * Value-validation rejections (INVALID_BAG_WEIGHT / INVALID_BAG_COUNT /
 * BATCH_REFERENCE_REQUIRED) keep the operator on the edit step; any other rejection returns
 * to scanning. Every send shows a spinner, and a timeout or send failure offers Retry.
 */
class OffloadActivity : SessionActivity() {

    private enum class Step { SCAN, MATCHING, EDIT, CONFIRMING, CLOSING }

    private lateinit var binding: ActivityOffloadBinding
    private lateinit var workflow: WorkflowClient
    private var step = Step.SCAN
    private var matchedTag = ""
    private var matchedBarcode = ""
    private var currentDocument: OffloadDocument? = null

    private val editStepErrors = setOf("INVALID_BAG_WEIGHT", "INVALID_BAG_COUNT", "BATCH_REFERENCE_REQUIRED")

    private val connectionStatusListener: (ConnectionStatus) -> Unit = { status ->
        runOnUiThread { binding.connectionPill.setStatus(status) }
    }

    private val rfidReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == "com.rscja.scanner.action.scanner.RFID") {
                val data = intent.getStringExtra("data")
                if (!data.isNullOrEmpty() && step == Step.SCAN) {
                    // Only a read this screen actually consumes counts as operator activity:
                    // the scanner action is an exported broadcast, so resetting the inactivity
                    // deadline for every matching intent would let a bare broadcast hold a
                    // session open indefinitely.
                    SessionGuard.touch()
                    binding.etTag.setText(data)
                    updateMatchEnabled()
                }
            }
        }
    }

    private val barcodeReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == "com.scanner.broadcast") {
                val data = intent.getStringExtra("data")
                if (!data.isNullOrEmpty() && step == Step.SCAN) {
                    SessionGuard.touch()
                    binding.etBarcode.setText(data)
                    updateMatchEnabled()
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        applyAppSystemBars()
        binding = ActivityOffloadBinding.inflate(layoutInflater)
        setContentView(binding.root)
        forceLightStatusBarIcons()
        binding.main.padForSystemBarsAndIme()

        setupToolbar()
        workflow = WorkflowClient(this)
        MqttManager.getInstance(this).addConnectionStatusListener(connectionStatusListener)

        binding.etTag.addTextChangedListener(SimpleTextWatcher { updateMatchEnabled() })
        binding.etBarcode.addTextChangedListener(SimpleTextWatcher { updateMatchEnabled() })
        // A validation message must not outlive the edit that fixes it (audit station1-11).
        val clearConfirmMessage = SimpleTextWatcher { if (step == Step.EDIT) hideConfirmStatus() }
        binding.etBagWeight.addTextChangedListener(clearConfirmMessage)
        binding.etBagCount.addTextChangedListener(clearConfirmMessage)
        binding.etBatchRef.addTextChangedListener(clearConfirmMessage)

        binding.btnMatchPallet.setOnClickListener { matchPallet() }
        binding.btnMatchPallet.applyPressScaleFeedback()
        binding.btnRetryScan.setOnClickListener { matchPallet() }
        binding.btnConfirmOffload.setOnClickListener { confirmOffload() }
        binding.btnConfirmOffload.applyPressScaleFeedback()
        binding.btnRetryConfirm.setOnClickListener { confirmOffload() }
        // Done on the last edit field confirms (audit group b).
        binding.etBatchRef.setOnSubmit { confirmOffload() }
        binding.btnBackToScan.setOnClickListener { enterScanStep(clearScan = false) }

        onBackPressedDispatcher.addCallback(this) { finishBackward() }
    }

    private fun setupToolbar() {
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = getString(R.string.tab_offload)
    }

    // ---- step transitions --------------------------------------------------------------------

    private fun enterScanStep(clearScan: Boolean) {
        step = Step.SCAN
        currentDocument = null
        binding.cardValues.visibility = View.GONE
        hideConfirmStatus()
        hideScanStatus()
        binding.etTag.isEnabled = true
        binding.etBarcode.isEnabled = true
        if (clearScan) {
            binding.etTag.setText("")
            binding.etBarcode.setText("")
        }
        binding.scrollOffload.post { binding.scrollOffload.smoothScrollTo(0, 0) }
        updateMatchEnabled()
    }

    private fun enterEditStep(
        tagId: String,
        barcode: String,
        prefill: OffloadPrefill,
        document: OffloadDocument,
    ) {
        step = Step.EDIT
        matchedTag = tagId
        matchedBarcode = barcode
        currentDocument = document
        hideScanStatus()
        binding.cardValues.visibility = View.VISIBLE
        hideConfirmStatus()
        binding.tvDocumentInfo.text = getString(
            R.string.label_document_progress,
            document.documentNumber, document.palletsScanned, document.palletsExpected,
        )
        binding.etBagWeight.setText(WorkflowMessages.formatWeight(prefill.bagWeight))
        binding.etBagCount.setText(prefill.bagCount.toString())
        binding.etBatchRef.setText(prefill.batchReference)
        binding.btnConfirmOffload.isEnabled = true
        // On the C72's display the values card lands below the fold; bring it into view so
        // the operator sees the prefill and the Confirm button without hunting for them.
        binding.scrollOffload.post { binding.scrollOffload.smoothScrollTo(0, binding.cardValues.top) }
    }

    private fun updateMatchEnabled() {
        binding.btnMatchPallet.isEnabled = step == Step.SCAN &&
            binding.etTag.text.toString().isNotBlank() &&
            binding.etBarcode.text.toString().isNotBlank()
    }

    // ---- step 1: offload_scan ----------------------------------------------------------------

    private fun matchPallet() {
        if (step != Step.SCAN) return
        val tagId = binding.etTag.text.toString().trim()
        val barcode = binding.etBarcode.text.toString().trim()
        if (tagId.isBlank() || barcode.isBlank()) return

        step = Step.MATCHING
        binding.btnMatchPallet.isEnabled = false
        binding.etTag.isEnabled = false
        binding.etBarcode.isEnabled = false
        showScanStatus(getString(R.string.status_sending), R.color.text_muted, pending = true)

        val payload = WorkflowMessages.offloadScan(
            deviceId = DeviceIdentity.deviceId(this),
            operatorSessionId = OperatorSessionHolder.currentSessionIdOrEmpty(),
            tagId = tagId,
            barcode = barcode,
        )
        workflow.request(
            requestType = "offload_scan",
            responseType = "offload_scan_result",
            payload = payload,
            matches = { it.optString("tagId") == tagId && it.optString("barcode") == barcode },
        ) { result ->
            if (step != Step.MATCHING) return@request
            result
                .onSuccess { json ->
                    if (json.optBoolean("matched", false)) {
                        val prefill = OffloadPrefill.fromScanResult(json)
                        val document = OffloadDocument.fromScanResult(json)
                        if (prefill != null && document != null) {
                            enterEditStep(tagId, barcode, prefill, document)
                        } else {
                            // "matched" without usable prefill or document breaks §6.1/§6.2 —
                            // treat as no match.
                            backToScanWithError(getString(R.string.error_incomplete_match))
                        }
                    } else {
                        if (handleSessionRejection(json)) return@request
                        backToScanWithError(stationReason(json))
                    }
                }
                .onFailure { e -> backToScanWithError(failureText(e), retry = true) }
        }
    }

    private fun backToScanWithError(message: String, retry: Boolean = false) {
        enterScanStep(clearScan = false)
        showScanStatus(message, R.color.danger, retry = retry)
    }

    // ---- step 2: offload_confirm -------------------------------------------------------------

    private fun confirmOffload() {
        if (step != Step.EDIT) return
        val document = currentDocument
            ?: return backToScanWithError(getString(R.string.error_incomplete_match))
        val weight = OffloadInput.parseWeight(binding.etBagWeight.text.toString())
            ?: return showConfirmStatus(getString(R.string.error_invalid_weight), R.color.danger)
        val count = OffloadInput.parseCount(binding.etBagCount.text.toString())
            ?: return showConfirmStatus(getString(R.string.error_invalid_count), R.color.danger)
        val batch = OffloadInput.parseBatch(binding.etBatchRef.text.toString())
            ?: return showConfirmStatus(getString(R.string.error_batch_required), R.color.danger)

        step = Step.CONFIRMING
        binding.btnConfirmOffload.isEnabled = false
        showConfirmStatus(getString(R.string.status_sending), R.color.text_muted, pending = true)

        val payload = WorkflowMessages.offloadConfirm(
            deviceId = DeviceIdentity.deviceId(this),
            operatorSessionId = OperatorSessionHolder.currentSessionIdOrEmpty(),
            tagId = matchedTag,
            barcode = matchedBarcode,
            documentType = document.documentType,
            documentNumber = document.documentNumber,
            bagWeight = weight,
            bagCount = count,
            batchReference = batch,
        )
        workflow.request(
            requestType = "offload_confirm",
            responseType = "offload_confirm_result",
            payload = payload,
            matches = { it.optString("tagId") == matchedTag && it.optString("barcode") == matchedBarcode },
        ) { result ->
            if (step != Step.CONFIRMING) return@request
            result
                .onSuccess { json ->
                    when {
                        json.optBoolean("accepted", false) -> {
                            val scanned = json.optInt("palletsScanned", -1)
                            val expected = json.optInt("palletsExpected", -1)
                            enterScanStep(clearScan = true)
                            showScanStatus(getString(R.string.msg_offload_recorded), R.color.success)
                            showDonePrompt(document, scanned, expected)
                        }
                        json.optString("errorCode", "") in editStepErrors -> {
                            step = Step.EDIT
                            binding.btnConfirmOffload.isEnabled = true
                            showConfirmStatus(stationReason(json), R.color.danger)
                        }
                        else -> {
                            if (handleSessionRejection(json)) return@request
                            backToScanWithError(stationReason(json))
                        }
                    }
                }
                .onFailure { e ->
                    step = Step.EDIT
                    binding.btnConfirmOffload.isEnabled = true
                    showConfirmStatus(failureText(e), R.color.danger, retry = true)
                }
        }
    }

    // ---- step 3: "Are you done?" and offload_complete ----------------------------------------

    /**
     * §6.4: after every accepted confirm the operator may close the looked-up document.
     * Custom view: the two choices carry distinct colors (continue = blue, done = green)
     * instead of the theme's identical dialog buttons. Not cancelable: a scrim tap or Back used
     * to act as a silent "Next Pallet" (audit station1-17).
     */
    private fun showDonePrompt(document: OffloadDocument, scanned: Int, expected: Int) {
        val message =
            if (scanned >= 0 && expected >= 0) {
                getString(R.string.dialog_done_message, scanned, expected, document.documentNumber)
            } else {
                getString(R.string.dialog_done_message_no_progress, document.documentNumber)
            }
        val view = com.mitas.ppnam.station1aa.databinding.DialogOffloadDoneBinding
            .inflate(layoutInflater)
        view.tvDoneMessage.text = message
        val dialog = neutralDialog()
            .setTitle(getString(R.string.dialog_done_title))
            .setView(view.root)
            .setCancelable(false)
            .show()
        view.btnNextPallet.setOnClickListener { dialog.dismiss() }
        view.btnDoneClose.setOnClickListener {
            dialog.dismiss()
            showClosePrompt(document)
        }
        view.btnNextPallet.applyPressScaleFeedback()
        view.btnDoneClose.applyPressScaleFeedback()
    }

    /**
     * Short = amber, Complete = green, Over = red — the classification is color-coded.
     * [reason] is the station's rejection of the previous close attempt; it is shown inside
     * this dialog so the operator sees why they are being asked again (audit station1-08).
     */
    private fun showClosePrompt(document: OffloadDocument, reason: String? = null) {
        val view = com.mitas.ppnam.station1aa.databinding.DialogOffloadCloseBinding
            .inflate(layoutInflater)
        val builder = neutralDialog()
            .setTitle(getString(R.string.dialog_close_title, document.documentNumber))
            .setView(view.root)
            .setNegativeButton(getString(R.string.btn_cancel), null)
        if (!reason.isNullOrBlank()) builder.setMessage(reason)
        val dialog = builder.show()
        val choices = listOf(
            view.btnCloseShort to OffloadStatus.SHORT,
            view.btnCloseComplete to OffloadStatus.COMPLETE,
            view.btnCloseOver to OffloadStatus.OVER,
        )
        for ((button, wireValue) in choices) {
            button.setOnClickListener {
                dialog.dismiss()
                val label = button.text.toString()
                // Short and Over declare how many tags the receipt differs by; Complete
                // declares no discrepancy and goes straight out.
                if (wireValue == OffloadStatus.COMPLETE) {
                    sendCompletion(document, wireValue, label, tagCount = null)
                } else {
                    showTagCountPrompt(document, wireValue, label)
                }
            }
            button.applyPressScaleFeedback()
        }
    }

    /**
     * §6.4: how many tags short/over. Cancel returns to the classification choices rather
     * than abandoning the closure, so a mis-tap on Short is one step from being corrected.
     * The dialog stays open on an invalid count — dismissing it would lose the choice.
     */
    private fun showTagCountPrompt(document: OffloadDocument, status: String, statusLabel: String) {
        val view = com.mitas.ppnam.station1aa.databinding.DialogTagCountBinding
            .inflate(layoutInflater)
        val title = if (status == OffloadStatus.SHORT) {
            getString(R.string.dialog_tag_count_short_title)
        } else {
            getString(R.string.dialog_tag_count_over_title)
        }
        val dialog = neutralDialog()
            .setTitle(title)
            .setView(view.root)
            .setPositiveButton(getString(R.string.btn_submit), null)
            .setNegativeButton(getString(R.string.btn_cancel)) { _, _ -> showClosePrompt(document) }
            .create()
        // The field is the dialog's whole purpose: open the keyboard with it (audit station1-18).
        dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE)
        dialog.show()

        fun submit() {
            val count = OffloadInput.parseTagCount(view.etTagCount.text.toString())
            if (count == null) {
                view.tvTagCountError.text = getString(R.string.error_invalid_tag_count)
                view.tvTagCountError.visibility = View.VISIBLE
                return
            }
            dialog.dismiss()
            sendCompletion(document, status, statusLabel, count)
        }

        // Set after show() so an invalid entry does not dismiss the dialog.
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener { submit() }
        view.etTagCount.setOnSubmit { submit() }
        view.etTagCount.requestFocus()
    }

    private fun sendCompletion(
        document: OffloadDocument,
        status: String,
        statusLabel: String,
        tagCount: Int?,
    ) {
        step = Step.CLOSING
        updateMatchEnabled()
        showScanStatus(getString(R.string.status_sending), R.color.text_muted, pending = true)

        val payload = WorkflowMessages.offloadComplete(
            deviceId = DeviceIdentity.deviceId(this),
            operatorSessionId = OperatorSessionHolder.currentSessionIdOrEmpty(),
            documentType = document.documentType,
            documentNumber = document.documentNumber,
            status = status,
            tagCount = tagCount,
        )
        workflow.request(
            requestType = "offload_complete",
            responseType = "offload_complete_result",
            payload = payload,
            matches = { it.optString("status") == status },
        ) { result ->
            if (step != Step.CLOSING) return@request
            step = Step.SCAN
            updateMatchEnabled()
            result
                .onSuccess { json ->
                    if (json.optBoolean("accepted", false)) {
                        // §6.4 accepted close: the document is done, so the Offload screen is done.
                        // The toast survives the finish so the operator still sees the confirmation.
                        val closedMessage = if (tagCount != null) {
                            getString(
                                R.string.msg_document_closed_tags,
                                document.documentNumber, statusLabel, tagCount,
                            )
                        } else {
                            getString(R.string.msg_document_closed, document.documentNumber, statusLabel)
                        }
                        android.widget.Toast.makeText(this, closedMessage, android.widget.Toast.LENGTH_LONG)
                            .show()
                        finishBackward()
                    } else {
                        if (handleSessionRejection(json)) return@request
                        val reason = stationReason(json)
                        showScanStatus(reason, R.color.danger)
                        // Let the operator retry the closure (or cancel back to scanning) —
                        // with the reason in the dialog itself, not hidden behind it.
                        showClosePrompt(document, reason)
                    }
                }
                .onFailure { e ->
                    val reason = failureText(e)
                    showScanStatus(reason, R.color.danger)
                    showClosePrompt(document, reason)
                }
        }
    }

    // ---- shared ------------------------------------------------------------------------------

    private fun stationReason(json: JSONObject): String =
        json.optString("reason", "").ifBlank {
            json.optString("errorCode", "").ifBlank { getString(R.string.status_send_failed) }
        }

    /** §8: a closed/expired session sends the operator back to login, with the reason. */
    private fun handleSessionRejection(json: JSONObject): Boolean {
        if (!WorkflowClient.isSessionRejection(json)) return false
        OperatorSessionHolder.clear()
        startActivity(Intent(this, LoginActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            putExtra(LoginActivity.EXTRA_SIGNED_OUT_REASON, getString(R.string.signed_out_session_ended))
        })
        finish()
        return true
    }

    private fun failureText(e: Throwable): String =
        if (e is WorkflowTimeout) getString(R.string.status_no_response)
        else getString(R.string.status_send_failed)

    private fun showScanStatus(
        message: String,
        colorRes: Int,
        pending: Boolean = false,
        retry: Boolean = false,
    ) {
        binding.layoutScanStatus.visibility = View.VISIBLE
        binding.progressScan.visibility = if (pending) View.VISIBLE else View.GONE
        binding.tvScanStatus.text = message
        binding.tvScanStatus.setTextColor(getColor(colorRes))
        binding.btnRetryScan.visibility = if (retry) View.VISIBLE else View.GONE
    }

    private fun hideScanStatus() {
        binding.layoutScanStatus.visibility = View.GONE
        binding.progressScan.visibility = View.GONE
        binding.btnRetryScan.visibility = View.GONE
    }

    private fun showConfirmStatus(
        message: String,
        colorRes: Int,
        pending: Boolean = false,
        retry: Boolean = false,
    ) {
        binding.layoutConfirmStatus.visibility = View.VISIBLE
        binding.progressConfirm.visibility = if (pending) View.VISIBLE else View.GONE
        binding.tvConfirmStatus.text = message
        binding.tvConfirmStatus.setTextColor(getColor(colorRes))
        binding.btnRetryConfirm.visibility = if (retry) View.VISIBLE else View.GONE
    }

    /** INVISIBLE, not GONE: the row keeps its minHeight so Confirm Offload never jumps. */
    private fun hideConfirmStatus() {
        binding.layoutConfirmStatus.visibility = View.INVISIBLE
        binding.progressConfirm.visibility = View.GONE
        binding.btnRetryConfirm.visibility = View.GONE
        binding.tvConfirmStatus.text = ""
    }

    override fun onResume() {
        super.onResume()
        val barcodeFilter = IntentFilter("com.scanner.broadcast")
        val rfidFilter = IntentFilter("com.rscja.scanner.action.scanner.RFID")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(barcodeReceiver, barcodeFilter, Context.RECEIVER_EXPORTED)
            registerReceiver(rfidReceiver, rfidFilter, Context.RECEIVER_EXPORTED)
        } else {
            registerReceiver(barcodeReceiver, barcodeFilter)
            registerReceiver(rfidReceiver, rfidFilter)
        }
    }

    override fun onPause() {
        super.onPause()
        unregisterReceiver(barcodeReceiver)
        unregisterReceiver(rfidReceiver)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) {
            finishBackward()
            return true
        }
        return super.onOptionsItemSelected(item)
    }

    override fun onDestroy() {
        super.onDestroy()
        MqttManager.getInstance(this).removeConnectionStatusListener(connectionStatusListener)
    }
}

/** Minimal TextWatcher wrapper so field listeners read as one line at the call site. */
private class SimpleTextWatcher(private val onChanged: () -> Unit) : android.text.TextWatcher {
    override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
    override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
    override fun afterTextChanged(s: android.text.Editable?) = onChanged()
}
```

- [x] **Step 3: Compile and verify**

```powershell
.\gradlew.bat :app:assembleDebug -PemulatorNoJni --offline
```
Install; log in; open Offload. Scan: `adb -s emulator-5554 shell am broadcast -a com.rscja.scanner.action.scanner.RFID --es data E2000000000000000000AAA1` and `adb -s emulator-5554 shell am broadcast -a com.scanner.broadcast --es data PAL-0001`.
1. Happy mode: Match Pallet → spinner next to "Sending…" (uiautomator shows `progressScan`), then the values card. Clear Number of Bags → Confirm → "Enter a whole number of bags greater than 0"; `btnConfirmOffload` bounds are identical before and after the message (dump both); type `40` → the message disappears at once.
2. Timeout mode (`set_mode.py --mode timeout`): Match Pallet → after 10 s "Station 1 did not respond. Check the station and retry." + `Retry` button; `set_mode.py --mode clear`; tap Retry → the match goes through without rescanning. Same on Confirm Offload (Retry next to the confirm row).
3. Happy: Confirm → "Pallet recorded": tap the scrim and press Back → the dialog stays. Done → "Close PO-000123 as…" → Short Tags → the keyboard opens with the count field focused (`dumpsys input_method | findstr mInputShown` → `true`); Enter with `3` submits.
4. Error mode: Done → Complete → the close dialog re-opens with the station's reason as its message (e.g. "No open purchase order or stock transfer for this pallet.") above the three buttons.
5. Edit step: Bag Weight → Next → Number of Bags → Next → Batch Reference → Done → confirm runs.

- [x] **Step 4: Commit**

```powershell
git add app/src/main/res/layout/activity_offload.xml app/src/main/java/com/mitas/ppnam/station1aa/OffloadActivity.kt
git commit -m "fix(offload): spinner and Retry on every send, stable validation row, close reason inside the dialog, dialog focus/cancel fixes

Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01ExhLEukYAu1CqjJUWPR64Q"
```

---

### Task 12: Tag Assignment — spinner + Retry, timeout wording, state across recreation (Tier 3 items 21 + 24, Tier 2 item 16)

Closes: station1-21 (no spinner), station1-09 ("Last scanned" + status lost on recreation — belt-and-braces after the portrait lock), static-08 (Retry), group (j) S1 (session-ended reason).

**Files:**
- Modify: `app\src\main\res\layout\activity_tag_assignment.xml` (the `tvSendStatus` element, lines 126-135)
- Modify: `…\TagAssignmentActivity.kt` (whole file — final version)

**Interfaces:**
- Consumes: `applyAppSystemBars()`, `padForSystemBarsAndIme()` (Task 3); strings `status_no_response`, `btn_retry`, `signed_out_session_ended`.
- Produces: view ids `layoutSendStatus`, `progressSend`, `btnRetrySend` (`tvLastTag`, `tvSendStatus` keep their ids).

- [x] **Step 1: Layout — status row**

Replace the `tvSendStatus` `TextView` (lines 126-135) with:
```xml
                    <!-- Status row like Station 3's: spinner while pending (audit station1-21),
                         text, and an explicit Retry after a timeout/send failure (audit §5). -->
                    <LinearLayout
                        android:id="@+id/layoutSendStatus"
                        android:layout_width="match_parent"
                        android:layout_height="wrap_content"
                        android:layout_marginTop="8dp"
                        android:gravity="center_vertical"
                        android:orientation="horizontal"
                        android:visibility="gone"
                        tools:visibility="visible">

                        <ProgressBar
                            android:id="@+id/progressSend"
                            android:layout_width="20dp"
                            android:layout_height="20dp"
                            android:layout_marginEnd="10dp"
                            android:indeterminateTint="@color/accent_action"
                            android:visibility="gone"
                            tools:visibility="visible" />

                        <TextView
                            android:id="@+id/tvSendStatus"
                            android:layout_width="0dp"
                            android:layout_height="wrap_content"
                            android:layout_weight="1"
                            android:textColor="@color/text_muted"
                            android:textSize="14sp"
                            tools:text="Sending…" />

                        <com.google.android.material.button.MaterialButton
                            android:id="@+id/btnRetrySend"
                            style="@style/Widget.SysOneScanner.Button.OutlinedButton"
                            android:layout_width="wrap_content"
                            android:layout_height="wrap_content"
                            android:layout_marginStart="8dp"
                            android:text="@string/btn_retry"
                            android:textColor="@color/text_primary"
                            android:visibility="gone"
                            app:strokeColor="@color/border_primary"
                            tools:visibility="visible" />
                    </LinearLayout>
```

- [x] **Step 2: Final `TagAssignmentActivity.kt`**

Replace the whole file:

```kotlin
package com.mitas.ppnam.station1aa

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Bundle
import android.view.MenuItem
import android.view.View
import androidx.activity.addCallback
import com.mitas.ppnam.station1aa.databinding.ActivityTagAssignmentBinding

/**
 * Tag Assignment (contract v3.0.0 §5): every scanned RFID tag is sent automatically as
 * `tag_scan`; the station decides what the tag means and answers `tag_scan_result` echoing the
 * tagId. The UI stays pending (spinner) until that result or the 10-second timeout — a PUBACK
 * is transport-only and never shown as success. A timeout or send failure offers Retry, which
 * re-sends the most recently scanned tag.
 */
class TagAssignmentActivity : SessionActivity() {

    private lateinit var binding: ActivityTagAssignmentBinding
    private lateinit var workflow: WorkflowClient
    private var lastScannedTag: String? = null

    /** What the status row shows, kept so recreation can restore it (audit station1-09). */
    private var statusText: String? = null
    private var statusColorRes: Int = R.color.text_muted
    private var statusPending = false
    private var statusRetry = false

    private val connectionStatusListener: (ConnectionStatus) -> Unit = { status ->
        runOnUiThread { binding.connectionPill.setStatus(status) }
    }

    private val rfidReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == "com.rscja.scanner.action.scanner.RFID") {
                val data = intent.getStringExtra("data")
                if (!data.isNullOrEmpty()) {
                    // See OffloadActivity: only a read this screen consumes counts as activity.
                    SessionGuard.touch()
                    onTagScanned(data)
                }
            }
        }
    }

    private companion object {
        const val KEY_LAST_TAG = "last_tag"
        const val KEY_STATUS_TEXT = "status_text"
        const val KEY_STATUS_COLOR = "status_color"
        const val KEY_STATUS_PENDING = "status_pending"
        const val KEY_STATUS_RETRY = "status_retry"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        applyAppSystemBars()
        binding = ActivityTagAssignmentBinding.inflate(layoutInflater)
        setContentView(binding.root)
        forceLightStatusBarIcons()
        binding.main.padForSystemBarsAndIme()

        setupToolbar()
        workflow = WorkflowClient(this)
        MqttManager.getInstance(this).addConnectionStatusListener(connectionStatusListener)

        binding.btnRetrySend.setOnClickListener { lastScannedTag?.let { onTagScanned(it) } }

        restoreState(savedInstanceState)

        onBackPressedDispatcher.addCallback(this) { finishBackward() }
    }

    private fun setupToolbar() {
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = getString(R.string.tab_tag_assignment)
    }

    /**
     * Portrait lock means recreation is rare (locale/font changes, process restore), but the
     * last tag and its outcome must not vanish when it happens. A send that was still pending
     * cannot be resumed — its callback died with the old instance — so it is restored as the
     * timeout state with Retry, which is exactly what the operator should do.
     */
    private fun restoreState(saved: Bundle?) {
        if (saved == null) return
        lastScannedTag = saved.getString(KEY_LAST_TAG)
        lastScannedTag?.let { binding.tvLastTag.text = it }
        val text = saved.getString(KEY_STATUS_TEXT) ?: return
        if (saved.getBoolean(KEY_STATUS_PENDING)) {
            showStatus(getString(R.string.status_no_response), R.color.danger, retry = true)
        } else {
            showStatus(text, saved.getInt(KEY_STATUS_COLOR, R.color.text_muted), retry = saved.getBoolean(KEY_STATUS_RETRY))
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(KEY_LAST_TAG, lastScannedTag)
        outState.putString(KEY_STATUS_TEXT, statusText)
        outState.putInt(KEY_STATUS_COLOR, statusColorRes)
        outState.putBoolean(KEY_STATUS_PENDING, statusPending)
        outState.putBoolean(KEY_STATUS_RETRY, statusRetry)
    }

    private fun onTagScanned(tagId: String) {
        runOnUiThread {
            lastScannedTag = tagId
            binding.tvLastTag.text = tagId
            showStatus(getString(R.string.status_sending), R.color.text_muted, pending = true)
        }
        sendTag(tagId)
    }

    private fun sendTag(tagId: String) {
        val payload = WorkflowMessages.tagScan(
            deviceId = DeviceIdentity.deviceId(this),
            operatorSessionId = OperatorSessionHolder.currentSessionIdOrEmpty(),
            tagId = tagId,
        )
        workflow.request(
            requestType = "tag_scan",
            responseType = "tag_scan_result",
            payload = payload,
            matches = { it.optString("tagId") == tagId },
        ) { result ->
            // A newer scan owns the status line by now — its own result will drive the UI.
            if (tagId != lastScannedTag) return@request
            result
                .onSuccess { json ->
                    if (json.optBoolean("accepted", false)) {
                        showStatus(
                            json.optString("reason", "").ifBlank { getString(R.string.status_tag_assigned) },
                            R.color.success,
                        )
                    } else {
                        if (handleSessionRejection(json)) return@request
                        showStatus(stationReason(json), R.color.danger)
                    }
                }
                .onFailure { e ->
                    val message = if (e is WorkflowTimeout) getString(R.string.status_no_response)
                    else getString(R.string.status_send_failed)
                    showStatus(message, R.color.danger, retry = true)
                }
        }
    }

    private fun stationReason(json: org.json.JSONObject): String =
        json.optString("reason", "").ifBlank {
            json.optString("errorCode", "").ifBlank { getString(R.string.status_send_failed) }
        }

    /** §8: a closed/expired session sends the operator back to login, with the reason. */
    private fun handleSessionRejection(json: org.json.JSONObject): Boolean {
        if (!WorkflowClient.isSessionRejection(json)) return false
        OperatorSessionHolder.clear()
        startActivity(Intent(this, LoginActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            putExtra(LoginActivity.EXTRA_SIGNED_OUT_REASON, getString(R.string.signed_out_session_ended))
        })
        finish()
        return true
    }

    private fun showStatus(
        message: String,
        colorRes: Int,
        pending: Boolean = false,
        retry: Boolean = false,
    ) {
        statusText = message
        statusColorRes = colorRes
        statusPending = pending
        statusRetry = retry
        binding.layoutSendStatus.visibility = View.VISIBLE
        binding.progressSend.visibility = if (pending) View.VISIBLE else View.GONE
        binding.tvSendStatus.text = message
        binding.tvSendStatus.setTextColor(getColor(colorRes))
        binding.btnRetrySend.visibility = if (retry) View.VISIBLE else View.GONE
    }

    override fun onResume() {
        super.onResume()
        val rfidFilter = IntentFilter("com.rscja.scanner.action.scanner.RFID")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(rfidReceiver, rfidFilter, Context.RECEIVER_EXPORTED)
        } else {
            registerReceiver(rfidReceiver, rfidFilter)
        }
    }

    override fun onPause() {
        super.onPause()
        unregisterReceiver(rfidReceiver)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) {
            finishBackward()
            return true
        }
        return super.onOptionsItemSelected(item)
    }

    override fun onDestroy() {
        super.onDestroy()
        MqttManager.getInstance(this).removeConnectionStatusListener(connectionStatusListener)
    }
}
```

- [x] **Step 3: Compile and verify**

```powershell
.\gradlew.bat :app:assembleDebug -PemulatorNoJni --offline
```
Install; log in; open Tag Assignment.
1. Happy: broadcast tag `E2000000000000000000AAA1` → spinner + "Sending…" then green "Tag assigned." (no spinner, no Retry).
2. Timeout mode: broadcast `…AAA2`, then 2 s later `…AAA3` → after 10 s the row reads "Station 1 did not respond. Check the station and retry." with Retry and `tvLastTag` = `…AAA3`. `set_mode.py --mode clear`, tap Retry → the fake backend log shows exactly one new `tag_scan` for `…AAA3` and none for `…AAA2` (Review Focus 4); the row turns green.
3. Recreation: with "Tag assigned." showing, `adb -s emulator-5554 shell am broadcast -a android.intent.action.LOCALE_CHANGED` is not enough on its own — instead toggle a font size (`adb shell settings put system font_scale 1.15`, then back to `1.0`): the last tag and its green status are still shown afterwards.

- [x] **Step 4: Commit**

```powershell
git add app/src/main/res/layout/activity_tag_assignment.xml app/src/main/java/com/mitas/ppnam/station1aa/TagAssignmentActivity.kt
git commit -m "fix(tag-assignment): spinner while sending, Retry after a timeout, keep last tag across recreation

Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01ExhLEukYAu1CqjJUWPR64Q"
```

---

### Task 13: Settings-shortcut tag only acts while the app is in front (Tier 3 item 23)

Closes: group (l) for S1 (`ScannerApp.kt` app-wide receiver consumed the settings tag from the background; S5-11/S4-18 cross-app).

**Files:**
- Modify: `…\ScannerApp.kt` (whole file)

**Interfaces:**
- Produces: nothing new. No new dependency (`ProcessLifecycleOwner` would need `lifecycle-process`; the resumed-activity counter below does the same with what is already on the classpath).

- [x] **Step 1: Rewrite `ScannerApp.kt`**

```kotlin
package com.mitas.ppnam.station1aa

import android.app.Activity
import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Bundle

class ScannerApp : Application() {

    private val SETTINGS_RFID = "E28011700000021B2F6E9827"

    /**
     * How many of this app's activities are resumed right now. The Chainway RFID action is an
     * exported broadcast every station app receives; acting on it from the background pulled
     * Settings over whichever app the operator was actually using (audit group l).
     */
    private var resumedActivities = 0

    private val rfidShortcutReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != "com.rscja.scanner.action.scanner.RFID") return
            if (resumedActivities == 0) return
            val data = intent.getStringExtra("data")
            if (data == SETTINGS_RFID) {
                val settingsIntent = Intent(context, SettingsActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
                }
                startActivity(settingsIntent)
            }
        }
    }

    override fun onCreate() {
        super.onCreate()

        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            override fun onActivityResumed(activity: Activity) { resumedActivities++ }
            override fun onActivityPaused(activity: Activity) { resumedActivities-- }
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
            override fun onActivityStarted(activity: Activity) {}
            override fun onActivityStopped(activity: Activity) {}
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
            override fun onActivityDestroyed(activity: Activity) {}
        })

        // Register Global RFID Shortcut Receiver
        val rfidFilter = IntentFilter("com.rscja.scanner.action.scanner.RFID")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(rfidShortcutReceiver, rfidFilter, Context.RECEIVER_EXPORTED)
        } else {
            registerReceiver(rfidShortcutReceiver, rfidFilter)
        }

        // Initialize and connect MQTT globally
        MqttManager.getInstance(this).connect()

        // Station-offline and inactivity sign-outs (spec §2-§3).
        SessionGuard.install(this)
    }
}
```

- [x] **Step 2: Compile and verify**

```powershell
.\gradlew.bat :app:assembleDebug -PemulatorNoJni --offline
```
Install; on the dashboard broadcast `adb -s emulator-5554 shell am broadcast -a com.rscja.scanner.action.scanner.RFID --es data E28011700000021B2F6E9827` → Settings opens. Back, then `adb -s emulator-5554 shell input keyevent KEYCODE_HOME`, broadcast again → `adb -s emulator-5554 shell dumpsys activity activities | findstr mResumedActivity` still names the launcher, not `SettingsActivity`. Reopen the app → the broadcast opens Settings again.

- [x] **Step 3: Commit**

```powershell
git add app/src/main/java/com/mitas/ppnam/station1aa/ScannerApp.kt
git commit -m "fix(scan): ignore the settings-shortcut tag while the app is in the background

Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01ExhLEukYAu1CqjJUWPR64Q"
```

---

### Task 14: Final sweep — tests, full build, keyboard matrix re-check

**Files:** none modified (verification only). If anything below fails, fix it in the task that owns the file and commit there.

- [x] **Step 1: All unit tests and both builds**

```powershell
.\gradlew.bat :app:testDebugUnitTest --offline
.\gradlew.bat :app:assembleDebug --offline
.\gradlew.bat :app:assembleDebug -PemulatorNoJni --offline
```
Expected: all `BUILD SUCCESSFUL`; the test report at `app\build\reports\tests\testDebugUnitTest\index.html` lists `EditorActionsTest`, `ClickDebouncerTest`, `PinLockoutTest`, `AuthFailureTest` plus the pre-existing suites, all green. Lint is not part of `assembleDebug`; `.\gradlew.bat :app:lintDebug --offline` is optional and must show no new `NewApi` errors (`windowLightNavigationBar` is guarded by `tools:targetApi`).

- [ ] **Step 2: Keyboard matrix (section 6) on emulator-5554**

Install the `-PemulatorNoJni` APK. For each row, tap the field, run `adb -s emulator-5554 shell dumpsys window | findstr ITYPE_IME` (IME top ≈ 1023 text / 1155 numeric), dump uiautomator and check the named button's bottom bound is above the IME top, scrolling with `adb shell input swipe 540 900 540 300 300` where the row says "Scrolls":

| Screen | Field | Button that must be visible or scrollable into view | Enter submits |
|---|---|---|---|
| Login | Username / Password | `btnLogin` (visible without scrolling) | yes (Password) |
| Settings PIN gate | PIN | `btnUnlock`; `tvPinError` above the field | yes |
| Settings form | Host … Auto sign-out | `btnSaveSettings` (scrolls) | Done on Auto sign-out applies |
| Offload edit | Bag Weight / Bags / Batch | `btnConfirmOffload` (scrolls) | Done on Batch confirms |
| Tag-count dialog | Number of tags | Submit; keyboard opens on its own | yes |

- [x] **Step 3: Crash check**

`adb -s emulator-5554 shell logcat -d -s AndroidRuntime:E` → empty after the whole sweep.

- [x] **Step 4: Branch summary**

```powershell
git log --oneline master..fix/ui-audit-2026-10-02
git status --porcelain
```
Expected: 13 commits (Tasks 1-13); the only uncommitted entry is the pre-existing ` M .idea/deploymentTargetSelector.xml`. Do not commit it. Hand the branch over for review (no merge, no push, no version bump — those are the reviewer's calls).

---

## Coverage

| Finding | Task |
|---|---|
| station1-01 | 6 |
| station1-02 | 2, 3 |
| station1-03 | 6 |
| station1-04 | 2, 6 |
| station1-05 | 8 |
| station1-06 | 5, 8 |
| station1-07 | 7 |
| station1-08 | 11 |
| station1-09 | 2, 12 |
| station1-10 | 7 |
| station1-11 | 11 |
| station1-12 | 4 |
| station1-13 | 9 |
| station1-14 | 3 |
| station1-15 | 6 |
| station1-16 | 6 |
| station1-17 | 11 |
| station1-18 | 11 |
| station1-19 | 7 |
| station1-20 | 4 |
| station1-21 | 11, 12 |
| static-04 (Back on Home) | 8 |
| static-06 (Settings standard) | 10 |
| static-08 (timeout + Retry) | 9, 11, 12 |
| static-09 (values-night) | 4 |
| static-12 (gear icon) | 4 |
| static-15 (dialog style) | 4 |
| static-17 (Diagnostics order — already correct) | 10 (no change, noted) |
| static-18 (pill vocabulary) | 9 |
| static-20 (password toggle, blank = keep) | 10 |
| static-26 (chip / hint) | 8, 10 |
| group (b) Enter submits | 5, 7, 10, 11 |
| group (e) portrait | 2 |
| group (f)/(j) reason text | 7, 11, 12 |
| group (l) background scans | 13 |
| §5 Login layout row | 7 |
| §5 Settings row | 10 |
| §5 Dialog style / "Log out" | 4 |

Not covered (deliberately): static-03 (S2 badge login — not S1), static-05/session persistence (OUT per brief; S1 is the reference), static-07 (station-offline handling — S1 is the reference behaviour and the state was not reproducible in the harness), static-10/11/13/16/22/24/27 (status-bar strip, shapes, home chrome, motion, toolbar size, operator dropdown, toast — S1 is the reference the other apps align to; no S1 change prescribed), broker defaults (OUT).
