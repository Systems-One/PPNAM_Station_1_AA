# Session Lifecycle and Scan UX (v1.3.0) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Offload close returns home, station-offline and inactivity force a sign-out with a plain-English reason, the login screen gets a station-served user dropdown, and the Offload scan fields become scan-only.

**Architecture:** A process-wide `SessionGuard` (installed from `ScannerApp`) owns the two forced-logout triggers: station presence going offline and an `InactivityMonitor` fed by every signed-in screen's `onUserInteraction()` and scanner broadcast. Both funnel into one `signOut(reason)` that fires the best-effort MQTT logout, clears the session, and lands on `LoginActivity` with the reason as an intent extra. The user dropdown is a new schema-4.1 request pair (`operator_list_requested` → `operator_list`) added to the contract, the Python simulator, and `AuthClient`, with the last list cached in prefs.

**Tech Stack:** Kotlin/Android (AppCompat, Material Components, ViewBinding), HiveMQ MQTT, org.json, JUnit 4 unit tests; Python 3 + pytest for the simulator; adb-driven campaign scripts for the C72.

**Spec:** `docs/superpowers/specs/2026-09-17-session-and-scan-ux-design.md`

## Global Constraints

- Package: `com.mitas.ppnam.station1aa`; sources under `app/src/main/java/com/mitas/ppnam/station1aa/`, unit tests under `app/src/test/java/com/mitas/ppnam/station1aa/`.
- Unit tests: plain JUnit 4, no Robolectric. Anything touching Android classes must be split so the logic is a pure Kotlin unit.
- Build/test commands (Windows, run from repo root): `.\gradlew.bat :app:testDebugUnitTest` and `.\gradlew.bat :app:assembleDebug`. Simulator tests: `cd tools && python -m pytest tests -q`.
- Campaign scripts keep the view ids they already rely on: `etUsername`, `etPassword`, `btnLogin`, `tileTagAssignment`, `tileOffload`, `etTag`, `etBarcode`, `tvScanStatus`, `connectionPill`, `etPin`, `btnUnlock`, `btnSaveSettings`.
- Contract version becomes `3.2.0`; app becomes `versionCode 3`, `versionName "1.3.0"`.
- Copy: "Station went offline — you were signed out. Sign in again when the station is back online." / "Signed out after %1$d minutes of inactivity." / "Station is offline. Sign-in is unavailable until the station app is running." / hints "Scan RFID tag", "Scan barcode" / setting "Auto sign-out after (minutes, 0 = never)".
- Commit after every task with the trailer `Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>`.

---

### Task 1: Offload close returns to the home screen

**Files:**
- Modify: `app/src/main/java/com/mitas/ppnam/station1aa/OffloadActivity.kt` (sendCompletion, ~line 330-365)
- Modify: `tools/test_campaign/offload_section.py` (cases O10, O11, O12)

**Interfaces:**
- Consumes: `finishBackward()` from `ScreenTransitions.kt`.
- Produces: nothing new.

- [ ] **Step 1: Change the accepted branch of `sendCompletion`**

Replace the `onSuccess` accepted branch so the activity finishes after a toast:

```kotlin
result
    .onSuccess { json ->
        if (json.optBoolean("accepted", false)) {
            // §6.4 accepted close: the document is done, so the Offload screen is done.
            // The toast survives the finish so the operator still sees the confirmation.
            android.widget.Toast.makeText(
                this,
                getString(R.string.msg_document_closed, document.documentNumber, statusLabel),
                android.widget.Toast.LENGTH_LONG,
            ).show()
            finishBackward()
        } else {
            if (handleSessionRejection(json)) return@request
            showScanStatus(stationReason(json), R.color.danger)
            // Let the operator retry the closure (or cancel back to scanning).
            showClosePrompt(document)
        }
    }
```

Update the class KDoc bullet 3 to read: "Done closes the looked-up document as Short / Complete / Over via `offload_complete` and returns to the home screen; Next Pallet just keeps scanning."

- [ ] **Step 2: Build**

Run: `.\gradlew.bat :app:assembleDebug`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Update campaign cases O10 and O11**

In `tools/test_campaign/offload_section.py`, after `d.tap(text="Complete")` in O10, replace the `wait_text("tvScanStatus", "closed")` / `PO-000123 in shown` lines with:

```python
            expect(d.find(id="tileOffload", retries=10) is not None,
                   "accepted close did not return to the home screen")
            case.note("returned to home after Complete")
```

Then, before the "pallets of the closed document no longer resolve" block, re-open Offload:

```python
            d.tap(id="tileOffload")
            expect(d.find(id="etTag", retries=6) is not None, "Offload did not reopen")
```

In O11, replace the two `shown` lines inside the loop with:

```python
                expect(d.find(id="tileOffload", retries=10) is not None,
                       f"{status_label}: did not return home")
```

and change `case.note(f"{status_label}: {shown!r}")` to `case.note(f"{status_label}: returned home")`. Rename O10's description to "Done -> Complete closes the document and returns home; its pallets stop resolving".

O12 (failed completion re-offers the prompt, retry closes): read its final assertion; if it waits for `tvScanStatus` containing "closed" after the retry, replace that with the same `tileOffload` expectation.

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/mitas/ppnam/station1aa/OffloadActivity.kt tools/test_campaign/offload_section.py
git commit -m "Offload: return to the home screen after an accepted document close"
```

---

### Task 2: InactivityMonitor (pure Kotlin, unit-tested)

**Files:**
- Create: `app/src/main/java/com/mitas/ppnam/station1aa/InactivityMonitor.kt`
- Test: `app/src/test/java/com/mitas/ppnam/station1aa/InactivityMonitorTest.kt`

**Interfaces:**
- Produces:
  ```kotlin
  class InactivityMonitor(
      private val now: () -> Long,                       // monotonic ms
      private val schedule: (Long, Runnable) -> Unit,    // delayMs, runnable
      private val cancel: (Runnable) -> Unit,
      private val onExpired: () -> Unit,
  ) {
      val isRunning: Boolean
      fun start(timeoutMs: Long)   // <= 0 disables (stop)
      fun touch()
      fun checkNow()
      fun stop()
  }
  ```

- [ ] **Step 1: Write the failing tests**

```kotlin
package com.mitas.ppnam.station1aa

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Inactivity auto-logout timer. Time and scheduling are injected so the tests are
 * deterministic: `scheduled` holds the pending runnable (at most one) and `fire()`
 * advances the clock to its due time and runs it.
 */
class InactivityMonitorTest {

    private var now = 1_000_000L
    private var scheduled: Pair<Long, Runnable>? = null
    private var expired = 0

    private val monitor = InactivityMonitor(
        now = { now },
        schedule = { delay, r -> scheduled = (now + delay) to r },
        cancel = { r -> if (scheduled?.second === r) scheduled = null },
        onExpired = { expired++ },
    )

    private fun fireScheduled() {
        val (due, r) = scheduled ?: error("nothing scheduled")
        scheduled = null
        now = maxOf(now, due)
        r.run()
    }

    @Test
    fun `expires once the timeout elapses without activity`() {
        monitor.start(60_000)
        assertTrue(monitor.isRunning)
        fireScheduled()
        assertEquals(1, expired)
        assertFalse(monitor.isRunning)
    }

    @Test
    fun `touch defers the deadline`() {
        monitor.start(60_000)
        now += 40_000
        monitor.touch()
        // The original deadline arrives: only 20s since the touch, so no expiry yet.
        fireScheduled()
        assertEquals(0, expired)
        assertTrue(monitor.isRunning)
        assertEquals(now + 40_000, scheduled!!.first)
        fireScheduled()
        assertEquals(1, expired)
    }

    @Test
    fun `stop cancels the pending deadline and never fires`() {
        monitor.start(60_000)
        monitor.stop()
        assertFalse(monitor.isRunning)
        assertEquals(null, scheduled)
        assertEquals(0, expired)
    }

    @Test
    fun `checkNow after a long gap fires immediately`() {
        monitor.start(60_000)
        now += 3_600_000 // app was in the background for an hour
        monitor.checkNow()
        assertEquals(1, expired)
        assertEquals(null, scheduled)
    }

    @Test
    fun `checkNow before the deadline does nothing`() {
        monitor.start(60_000)
        now += 10_000
        monitor.checkNow()
        assertEquals(0, expired)
        assertTrue(monitor.isRunning)
    }

    @Test
    fun `zero or negative timeout disables the monitor`() {
        monitor.start(0)
        assertFalse(monitor.isRunning)
        assertEquals(null, scheduled)
        monitor.touch()
        monitor.checkNow()
        assertEquals(0, expired)
    }

    @Test
    fun `touch and checkNow are no-ops when stopped`() {
        monitor.touch()
        monitor.checkNow()
        assertEquals(0, expired)
        assertEquals(null, scheduled)
    }

    @Test
    fun `restart replaces the previous timeout`() {
        monitor.start(60_000)
        monitor.start(5_000)
        assertEquals(now + 5_000, scheduled!!.first)
        fireScheduled()
        assertEquals(1, expired)
    }
}
```

- [ ] **Step 2: Run to verify failure**

Run: `.\gradlew.bat :app:testDebugUnitTest --tests "com.mitas.ppnam.station1aa.InactivityMonitorTest"`
Expected: compilation FAILS with unresolved reference `InactivityMonitor`.

- [ ] **Step 3: Implement**

```kotlin
package com.mitas.ppnam.station1aa

/**
 * Inactivity auto-logout timer (spec §3). Pure Kotlin: the caller supplies a monotonic
 * clock and a scheduler, so production uses SystemClock.elapsedRealtime + a main-thread
 * Handler while tests drive time by hand.
 *
 * The deadline is wall-clock from the last activity, so time spent in the background
 * still counts; hosts call [checkNow] on resume to catch a deadline that passed while
 * no Handler was running. [onExpired] fires at most once per [start].
 */
class InactivityMonitor(
    private val now: () -> Long,
    private val schedule: (Long, Runnable) -> Unit,
    private val cancel: (Runnable) -> Unit,
    private val onExpired: () -> Unit,
) {
    private var timeoutMs = 0L
    private var lastActivity = 0L
    private var pending: Runnable? = null

    val isRunning: Boolean get() = timeoutMs > 0

    fun start(timeoutMs: Long) {
        stop()
        if (timeoutMs <= 0) return
        this.timeoutMs = timeoutMs
        lastActivity = now()
        scheduleCheck(timeoutMs)
    }

    fun touch() {
        if (!isRunning) return
        lastActivity = now()
    }

    fun checkNow() {
        if (!isRunning) return
        val remaining = timeoutMs - (now() - lastActivity)
        if (remaining <= 0) {
            stop()
            onExpired()
        } else {
            scheduleCheck(remaining)
        }
    }

    fun stop() {
        timeoutMs = 0
        pending?.let(cancel)
        pending = null
    }

    private fun scheduleCheck(delayMs: Long) {
        pending?.let(cancel)
        val r = Runnable { pending = null; checkNow() }
        pending = r
        schedule(delayMs, r)
    }
}
```

- [ ] **Step 4: Run tests**

Run: `.\gradlew.bat :app:testDebugUnitTest --tests "com.mitas.ppnam.station1aa.InactivityMonitorTest"`
Expected: 8 tests PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/mitas/ppnam/station1aa/InactivityMonitor.kt app/src/test/java/com/mitas/ppnam/station1aa/InactivityMonitorTest.kt
git commit -m "Add InactivityMonitor for the inactivity auto-logout"
```

---

### Task 3: Auto-logout setting (repository + Settings screen)

**Files:**
- Create: `app/src/main/java/com/mitas/ppnam/station1aa/AutoLogout.kt`
- Test: `app/src/test/java/com/mitas/ppnam/station1aa/AutoLogoutTest.kt`
- Modify: `app/src/main/java/com/mitas/ppnam/station1aa/SettingsRepository.kt`
- Modify: `app/src/main/res/layout/activity_settings.xml` (after `tilBrokerPassword`, before the closing `</LinearLayout>` of the broker card, ~line 400)
- Modify: `app/src/main/java/com/mitas/ppnam/station1aa/SettingsActivity.kt`
- Modify: `app/src/main/res/values/strings.xml`

**Interfaces:**
- Produces:
  ```kotlin
  object AutoLogout {
      const val DEFAULT_MINUTES = 15
      const val MAX_MINUTES = 1440
      fun parseMinutes(text: String): Int?      // null when invalid
      fun timeoutMs(minutes: Int): Long         // 0 when minutes <= 0
  }
  // SettingsRepository
  fun autoLogoutMinutes(): Int
  fun saveAutoLogoutMinutes(minutes: Int)
  ```

- [ ] **Step 1: Write the failing tests**

```kotlin
package com.mitas.ppnam.station1aa

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AutoLogoutTest {

    @Test
    fun `default is fifteen minutes`() {
        assertEquals(15, AutoLogout.DEFAULT_MINUTES)
    }

    @Test
    fun `parses whole minutes within range`() {
        assertEquals(0, AutoLogout.parseMinutes("0"))
        assertEquals(15, AutoLogout.parseMinutes(" 15 "))
        assertEquals(1440, AutoLogout.parseMinutes("1440"))
    }

    @Test
    fun `rejects blanks, negatives, decimals and out-of-range values`() {
        assertNull(AutoLogout.parseMinutes(""))
        assertNull(AutoLogout.parseMinutes("-1"))
        assertNull(AutoLogout.parseMinutes("1.5"))
        assertNull(AutoLogout.parseMinutes("1441"))
        assertNull(AutoLogout.parseMinutes("abc"))
    }

    @Test
    fun `timeout in milliseconds, zero means disabled`() {
        assertEquals(0L, AutoLogout.timeoutMs(0))
        assertEquals(0L, AutoLogout.timeoutMs(-3))
        assertEquals(60_000L, AutoLogout.timeoutMs(1))
        assertEquals(900_000L, AutoLogout.timeoutMs(15))
    }
}
```

- [ ] **Step 2: Run to verify failure**

Run: `.\gradlew.bat :app:testDebugUnitTest --tests "com.mitas.ppnam.station1aa.AutoLogoutTest"`
Expected: compilation FAILS (unresolved `AutoLogout`).

- [ ] **Step 3: Implement `AutoLogout.kt`**

```kotlin
package com.mitas.ppnam.station1aa

/** Inactivity auto-logout setting rules (spec §3): whole minutes, 0 = never, max one day. */
object AutoLogout {
    const val DEFAULT_MINUTES = 15
    const val MAX_MINUTES = 1440

    fun parseMinutes(text: String): Int? =
        text.trim().toIntOrNull()?.takeIf { it in 0..MAX_MINUTES }

    fun timeoutMs(minutes: Int): Long = if (minutes <= 0) 0L else minutes * 60_000L
}
```

- [ ] **Step 4: Run tests**

Run: `.\gradlew.bat :app:testDebugUnitTest --tests "com.mitas.ppnam.station1aa.AutoLogoutTest"`
Expected: 4 tests PASS.

- [ ] **Step 5: Add the repository accessors**

In `SettingsRepository.kt` add to `Keys`:

```kotlin
        const val AUTO_LOGOUT_MINUTES = "auto_logout_minutes"
```

and add after `isProvisioned()`:

```kotlin
    /** Inactivity auto-logout, in minutes; 0 = never (spec §3). */
    fun autoLogoutMinutes(): Int =
        prefs.getInt(Keys.AUTO_LOGOUT_MINUTES, AutoLogout.DEFAULT_MINUTES)

    fun saveAutoLogoutMinutes(minutes: Int) {
        prefs.edit().putInt(Keys.AUTO_LOGOUT_MINUTES, minutes.coerceIn(0, AutoLogout.MAX_MINUTES)).apply()
    }
```

- [ ] **Step 6: Add the strings**

In `strings.xml`, in the Settings block:

```xml
    <string name="section_session_policy">Session</string>
    <string name="hint_auto_logout_minutes">Auto sign-out after (minutes, 0 = never)</string>
    <string name="error_auto_logout_minutes">Enter 0–1440</string>
```

- [ ] **Step 7: Add the field to the settings layout**

Insert directly after the `tilBrokerPassword` `TextInputLayout` closes (still inside the broker card's LinearLayout):

```xml
                        <TextView
                            style="@style/SettingsSectionLabel"
                            android:layout_width="wrap_content"
                            android:layout_height="wrap_content"
                            android:layout_marginTop="20dp"
                            android:text="@string/section_session_policy"
                            android:textColor="@color/primary_action" />

                        <com.google.android.material.textfield.TextInputLayout
                            android:id="@+id/tilAutoLogout"
                            style="@style/Widget.MaterialComponents.TextInputLayout.OutlinedBox"
                            android:layout_width="match_parent"
                            android:layout_height="wrap_content"
                            android:layout_marginTop="12dp"
                            android:hint="@string/hint_auto_logout_minutes"
                            app:boxStrokeColor="@color/outline_dark"
                            app:hintTextColor="@color/text_secondary_dark">

                            <com.google.android.material.textfield.TextInputEditText
                                android:id="@+id/etAutoLogout"
                                android:layout_width="match_parent"
                                android:layout_height="wrap_content"
                                android:inputType="number"
                                android:maxLength="4"
                                android:textColor="@color/text_primary_dark" />
                        </com.google.android.material.textfield.TextInputLayout>
```

If `SettingsSectionLabel` is not a style in `res/values/styles.xml` (check with grep), copy the attributes used by the existing "Broker" section label in this layout instead.

- [ ] **Step 8: Wire the Settings screen**

In `SettingsActivity.onCreate`, after `binding.etBrokerUsername.setText(current.username)`:

```kotlin
        binding.etAutoLogout.setText(settingsRepository.autoLogoutMinutes().toString())
```

In the save click listener, after the port validation and before `val typedPassword`:

```kotlin
            val autoLogoutMinutes = AutoLogout.parseMinutes(binding.etAutoLogout.text.toString())
            if (autoLogoutMinutes == null) {
                binding.etAutoLogout.error = getString(R.string.error_auto_logout_minutes)
                return@setOnClickListener
            }
            settingsRepository.saveAutoLogoutMinutes(autoLogoutMinutes)
```

`SessionGuard` does not exist yet, so this task stops at persisting the value. Task 4 adds the `SessionGuard.applyTimeout()` call on the next line so a saved change takes effect without a relaunch.

- [ ] **Step 9: Build and run all unit tests**

Run: `.\gradlew.bat :app:testDebugUnitTest :app:assembleDebug`
Expected: BUILD SUCCESSFUL, all tests pass.

- [ ] **Step 10: Commit**

```bash
git add app/src/main/java/com/mitas/ppnam/station1aa/AutoLogout.kt app/src/test/java/com/mitas/ppnam/station1aa/AutoLogoutTest.kt app/src/main/java/com/mitas/ppnam/station1aa/SettingsRepository.kt app/src/main/java/com/mitas/ppnam/station1aa/SettingsActivity.kt app/src/main/res/layout/activity_settings.xml app/src/main/res/values/strings.xml
git commit -m "Settings: configurable inactivity auto sign-out (minutes, 0 = never)"
```

---

### Task 4: SessionGuard — forced sign-out on station offline and inactivity

**Files:**
- Create: `app/src/main/java/com/mitas/ppnam/station1aa/SessionGuard.kt`
- Create: `app/src/main/java/com/mitas/ppnam/station1aa/SessionActivity.kt`
- Modify: `app/src/main/java/com/mitas/ppnam/station1aa/ScannerApp.kt`
- Modify: `app/src/main/java/com/mitas/ppnam/station1aa/MainActivity.kt`
- Modify: `app/src/main/res/layout/activity_main.xml` (delete the `layoutStationOffline` block, lines ~204-257)
- Modify: `app/src/main/java/com/mitas/ppnam/station1aa/TagAssignmentActivity.kt`
- Modify: `app/src/main/java/com/mitas/ppnam/station1aa/OffloadActivity.kt`
- Modify: `app/src/main/java/com/mitas/ppnam/station1aa/SettingsActivity.kt`
- Modify: `app/src/main/java/com/mitas/ppnam/station1aa/LoginActivity.kt`
- Modify: `app/src/main/res/layout/activity_login.xml`
- Modify: `app/src/main/res/values/strings.xml`
- Modify: `tools/test_campaign/presence_section.py` (P1)

**Interfaces:**
- Consumes: `InactivityMonitor` (Task 2), `SettingsRepository.autoLogoutMinutes()` (Task 3), `AuthClient.logout`, `MqttManager.addStationStatusListener`, `OperatorSessionHolder.addListener`.
- Produces:
  ```kotlin
  object SessionGuard {
      fun install(app: Application)
      fun touch()
      fun checkNow()
      fun applyTimeout()
      fun signOut(reason: String)
  }
  const val EXTRA_SIGNED_OUT_REASON = "signed_out_reason"   // on LoginActivity's companion
  abstract class SessionActivity : AppCompatActivity()       // forwards onUserInteraction/onResume
  ```

- [ ] **Step 1: Add the strings**

```xml
    <!-- Forced sign-out (spec §2, §3) -->
    <string name="signed_out_station_offline">Station went offline — you were signed out. Sign in again when the station is back online.</string>
    <string name="signed_out_inactivity">Signed out after %1$d minutes of inactivity.</string>
    <string name="login_station_offline_banner">Station is offline. Sign-in is unavailable until the station app is running.</string>
```

Delete `station_offline_title` and `station_offline_message` (their only user is the overlay removed in Step 5).

- [ ] **Step 2: Create `SessionGuard.kt`**

```kotlin
package com.mitas.ppnam.station1aa

import android.app.Activity
import android.app.Application
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log

/**
 * Process-wide owner of the two forced sign-out triggers (spec §2-§3):
 *
 *  - station presence flips to offline while an operator is signed in;
 *  - the operator has been inactive for the configured number of minutes.
 *
 * Both end in [signOut]: best-effort `reader_logout_requested`, session cleared, and
 * LoginActivity brought up with the reason so the operator knows what happened. Installed
 * once from ScannerApp; activities only ever call [touch] (any interaction or scan).
 */
object SessionGuard {

    private const val TAG = "SessionGuard"

    private lateinit var app: Application
    private val mainHandler = Handler(Looper.getMainLooper())
    private var monitor: InactivityMonitor? = null

    fun install(app: Application) {
        this.app = app
        monitor = InactivityMonitor(
            now = { SystemClock.elapsedRealtime() },
            schedule = { delay, r -> mainHandler.postDelayed(r, delay) },
            cancel = { r -> mainHandler.removeCallbacks(r) },
            onExpired = {
                val minutes = SettingsRepository(app).autoLogoutMinutes()
                signOut(app.getString(R.string.signed_out_inactivity, minutes))
            },
        )

        // Start/stop the inactivity timer with the session itself.
        OperatorSessionHolder.addListener { session ->
            mainHandler.post { if (session == null) monitor?.stop() else applyTimeout() }
        }

        // A deadline that passed while the app was backgrounded is caught on the next resume.
        app.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityResumed(activity: Activity) { checkNow() }
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
            override fun onActivityStarted(activity: Activity) {}
            override fun onActivityPaused(activity: Activity) {}
            override fun onActivityStopped(activity: Activity) {}
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
            override fun onActivityDestroyed(activity: Activity) {}
        })

        // Station presence: offline while signed in => sign out. Broker reconnects leave the
        // presence value untouched, so a broker blip never trips this.
        MqttManager.getInstance(app).addStationStatusListener { online ->
            if (!online) mainHandler.post {
                if (OperatorSessionHolder.session != null) {
                    Log.i(TAG, "Station offline with an active session — signing out")
                    signOut(app.getString(R.string.signed_out_station_offline))
                }
            }
        }
    }

    /** Any operator interaction or scanner read. Safe from any thread. */
    fun touch() {
        mainHandler.post { monitor?.touch() }
    }

    fun checkNow() {
        monitor?.checkNow()
    }

    /** (Re)reads the configured timeout; called when a session starts and after Settings saves. */
    fun applyTimeout() {
        if (OperatorSessionHolder.session == null) return
        val minutes = SettingsRepository(app).autoLogoutMinutes()
        monitor?.start(AutoLogout.timeoutMs(minutes))
    }

    /** Idempotent: a second trigger racing the first finds no session and does nothing. */
    fun signOut(reason: String) {
        if (OperatorSessionHolder.session == null) return
        AuthClient(app).logout {
            app.startActivity(Intent(app, LoginActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                putExtra(LoginActivity.EXTRA_SIGNED_OUT_REASON, reason)
            })
        }
    }
}
```

- [ ] **Step 3: Create `SessionActivity.kt`**

```kotlin
package com.mitas.ppnam.station1aa

import androidx.appcompat.app.AppCompatActivity

/**
 * Base for every screen that requires a signed-in operator: each touch or key press
 * counts as activity for the inactivity auto-logout (spec §3). Scanner broadcasts don't
 * pass through onUserInteraction, so receivers call SessionGuard.touch() themselves.
 */
abstract class SessionActivity : AppCompatActivity() {
    override fun onUserInteraction() {
        super.onUserInteraction()
        SessionGuard.touch()
    }
}
```

- [ ] **Step 4: Rewrite `ScannerApp.kt`**

Replace the whole file:

```kotlin
package com.mitas.ppnam.station1aa

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build

class ScannerApp : Application() {

    private val SETTINGS_RFID = "E28011700000021B2F6E9827"

    private val rfidShortcutReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == "com.rscja.scanner.action.scanner.RFID") {
                val data = intent.getStringExtra("data")
                if (data == SETTINGS_RFID) {
                    val settingsIntent = Intent(context, SettingsActivity::class.java).apply {
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
                    }
                    startActivity(settingsIntent)
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()

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

- [ ] **Step 5: Strip the overlay from MainActivity**

In `activity_main.xml` delete the whole `<!-- Station Offline Overlay -->` `ConstraintLayout` (id `layoutStationOffline`) up to and including its closing tag, leaving the root's closing tag.

In `MainActivity.kt`: change `class MainActivity : AppCompatActivity()` to `class MainActivity : SessionActivity()`; delete the `stationStatusListener` property, the `addStationStatusListener(...)` and `removeStationStatusListener(...)` calls. Remove the now-unused `import androidx.appcompat.app.AppCompatActivity` if the IDE flags it (Kotlin tolerates unused imports; leave if unsure).

- [ ] **Step 6: Extend `SessionActivity` on the other signed-in screens and touch on scans**

`TagAssignmentActivity`: `class TagAssignmentActivity : SessionActivity()`; in `rfidReceiver.onReceive`, before `if (!data.isNullOrEmpty()) onTagScanned(data)` add `SessionGuard.touch()`.

`OffloadActivity`: `class OffloadActivity : SessionActivity()`; add `SessionGuard.touch()` as the first statement inside both `if (intent?.action == ...)` blocks of `rfidReceiver` and `barcodeReceiver`.

`SettingsActivity`: `class SettingsActivity : SessionActivity()`; in the save listener, right after `settingsRepository.saveAutoLogoutMinutes(autoLogoutMinutes)` add `SessionGuard.applyTimeout()`.

- [ ] **Step 7: LoginActivity — reason text and station-offline banner**

Add to `activity_login.xml`, as the first child of the card's inner `LinearLayout` (before `tilUsername`):

```xml
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
```

In `LoginActivity.kt`:

```kotlin
    companion object {
        /** Why the operator landed here without asking to (spec §2-§3); shown as the error text. */
        const val EXTRA_SIGNED_OUT_REASON = "signed_out_reason"
    }
```

Replace `connectionStatusListener` with:

```kotlin
    private val connectionStatusListener: (ConnectionStatus) -> Unit = { status ->
        runOnUiThread {
            binding.connectionPill.setStatus(status)
            binding.tvStationOfflineBanner.visibility =
                if (status == ConnectionStatus.STATION_OFFLINE) View.VISIBLE else View.GONE
        }
    }
```

In `onCreate`, after `binding.btnLogin.applyPressScaleFeedback()`:

```kotlin
        intent.getStringExtra(EXTRA_SIGNED_OUT_REASON)?.takeIf { it.isNotBlank() }?.let { showError(it) }
```

Add a helper and use it in both login paths so an offline station fails fast instead of after the 10-second timeout:

```kotlin
    /** Spec §2: with the station's presence offline no login can succeed — say so at once. */
    private fun stationIsOffline(): Boolean {
        val mqtt = MqttManager.getInstance(this)
        return mqtt.isConnected() && !mqtt.isStationOnline
    }
```

In `submitCredentials`, after the empty-fields check and before `if (loginInFlight || loggedIn) return`:

```kotlin
        if (stationIsOffline()) {
            showError(getString(R.string.login_station_offline_banner))
            return
        }
```

In `attemptBadgeLogin`, inside `runOnUiThread` before `setLoggingIn(true)`:

```kotlin
            if (stationIsOffline()) {
                showError(getString(R.string.login_station_offline_banner))
                return@runOnUiThread
            }
```

- [ ] **Step 8: Build and run unit tests**

Run: `.\gradlew.bat :app:testDebugUnitTest :app:assembleDebug`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 9: Rewrite campaign case P1**

In `tools/test_campaign/presence_section.py` replace the P1 block with:

```python
        with c.case("P1", "Station offline signs the operator out with a reason; login refuses while offline") as case:
            login_to_main(sim)
            sim.cmd("station", state="offline")
            expect(d.find(id="etUsername", retries=15) is not None,
                   "station offline did not return to the login screen")
            err = d.find(id="tvLoginError", retries=5)
            expect(err is not None and "signed out" in err.text.lower(), f"reason text {err}")
            case.note(f"reason: {err.text!r}")
            banner = d.find(id="tvStationOfflineBanner", retries=5)
            expect(banner is not None, "offline banner not shown on login")
            case.shot(d.screenshot("P1_station_offline_login"))
            # A login attempt while offline fails immediately, not after the 10s timeout.
            d.type_into("etUsername", "op.both")
            d.type_into("etPassword", "both123!")
            d.key("KEYCODE_BACK")
            t0 = time.time()
            d.tap(id="btnLogin")
            err = d.find(id="tvLoginError", retries=3)
            expect(err is not None and "offline" in err.text.lower() and time.time() - t0 < 6,
                   f"offline login did not fail fast: {err}")
            sim.cmd("station", state="online")
            expect(d.wait_gone("tvStationOfflineBanner", timeout=15), "banner did not clear")
            d.tap(id="btnLogin")
            expect(d.find(id="tileTagAssignment", retries=8) is not None, "login after recovery failed")
```

- [ ] **Step 10: Commit**

```bash
git add -A app/src/main tools/test_campaign/presence_section.py
git commit -m "SessionGuard: sign out on station offline or inactivity, with a reason on the login screen"
```

---

### Task 5: Contract v3.2.0 and simulator support for `operator_list_requested`

**Files:**
- Modify: `docs/Station1_MQTT_Contract_v3.md`
- Modify: `tools/simlib/world.py`
- Modify: `tools/tests/test_world.py`

**Interfaces:**
- Produces (wire): request `req/operator_list_requested` (schema 4.1 envelope, no extra fields, no session) → response `res/operator_list` with `accepted: true`, `nextAction: "login"`, `operators: [{ "username", "displayName" }]`.

- [ ] **Step 1: Write the failing simulator tests**

Append to `tools/tests/test_world.py` after `test_unknown_badge_rejected`:

```python
def test_operator_list_returns_password_operators(world, clock):
    suffix, result = auth_req(world, clock, "operator_list_requested", "oplist-1")
    assert suffix == "operator_list"
    assert result["accepted"]
    assert result["nextAction"] == "login"
    names = {o["username"]: o["displayName"] for o in result["operators"]}
    assert names == {
        "op.both": "Bongi Both", "op.tag": "Thandi Tag",
        "op.off": "Owen Offload", "op.none": "Nomsa None",
    }
    # display-only: no permissions or badge material leak into the directory
    assert all(set(o) == {"username", "displayName"} for o in result["operators"])


def test_operator_list_replay_is_idempotent(world, clock):
    _, first = auth_req(world, clock, "operator_list_requested", "oplist-2")
    _, again = auth_req(world, clock, "operator_list_requested", "oplist-2")
    assert again == first
```

- [ ] **Step 2: Run to verify failure**

Run: `cd tools && python -m pytest tests/test_world.py -q -k operator_list`
Expected: FAIL with `KeyError: 'operator_list_requested'`.

- [ ] **Step 3: Implement in `world.py`**

Add to `AUTH_RESPONSE_SUFFIX`:

```python
    "operator_list_requested": "operator_list",
```

Add to the handler map in `handle_auth`:

```python
            "operator_list_requested": self._auth_operator_list,
```

Add after `_auth_badge_login`:

```python
    def _auth_operator_list(self, req: ParsedRequest) -> dict:
        """§4.5 (3.2.0): the login-screen directory. Display-only — usernames and
        display names of active password operators, nothing about permissions."""
        operators = [
            {"username": op.username, "displayName": op.display_name}
            for op in OPERATORS
        ]
        return self._auth_response(
            req, accepted=True, reason="Operator list.",
            next_action="login", extra={"operators": operators},
        )
```

Update the module docstring's first line to say "contract v3.2.0".

- [ ] **Step 4: Run the whole simulator suite**

Run: `cd tools && python -m pytest tests -q`
Expected: all pass (76 = 74 + 2).

- [ ] **Step 5: Contract document**

In `docs/Station1_MQTT_Contract_v3.md`:

1. Header table: `Contract version | 3.2.0`, `Last updated | 2026-09-17`.
2. §4 request/response table: add a row after `login_requested`:
   `| \`operator_list_requested\` | \`operator_list\` | Login-screen operator directory (3.2.0). |`
3. Insert a new section before "### 4.5 Manager/Admin scoped authorization (reserved)" and renumber that to 4.6 and "Replay and idempotency" to 4.7 (grep the file for "4.5", "4.6", "§4.5", "§4.6" and fix any cross-references):

````markdown
### 4.5 Operator directory (added in 3.2.0)

The login screen offers a dropdown of operator usernames so an operator picks a name and
types only the password. The scanner asks for the directory whenever it (re)connects to
the broker while on the login screen, on
`PPNAM/station_1/{deviceId}/req/operator_list_requested` with the plain pre-login
envelope and no additional fields:

```json
{
  "messageId": "operator-list-001",
  "schemaVersion": "4.1",
  "deviceId": "scanner_5c64df8d86a8",
  "timestampUtc": "2026-09-17T06:00:00.000000Z"
}
```

The station answers on `res/operator_list`:

```json
{
  "messageId": "response-operator-list-001",
  "inResponseToMessageId": "operator-list-001",
  "schemaVersion": "4.1",
  "deviceId": "scanner_5c64df8d86a8",
  "timestampUtc": "2026-09-17T06:00:00.120000Z",
  "accepted": true,
  "reason": "Operator list.",
  "nextAction": "login",
  "operators": [
    { "username": "jsmith", "displayName": "J. Smith" }
  ]
}
```

Rules:

- `operators` lists **active password (SCRAM) operators** only, sorted by `displayName`.
  Each entry carries exactly `username` and `displayName`. Roles, permissions,
  `allowedTabs`, badge tags, and secrets MUST NOT appear — the directory is display-only
  and confers nothing; every login is still authenticated by §4.3 / §4.4.
- No `operatorSessionId` is required. Replay follows §4.7 (an identical replay returns the
  stored list).
- The scanner caches the last accepted list on the device so the dropdown is populated
  before the station answers (or when it never does). Typing a username that is not in the
  list remains valid.
- A station that has not implemented this request rejects it with
  `authentication_request_unsupported` on `res/request_rejected` (§4.1); the scanner then
  keeps its cached list, or an empty dropdown, and falls back to typed usernames.
````

4. §10 "Implementation deltas": add a bullet: "*(3.2.0, 2026-09-17)* `operator_list_requested` is implemented on the Android scanner and the simulator; **the Windows station handler is pending** — until it ships, scanners receive `authentication_request_unsupported` and fall back to typed usernames."
5. §12 acceptance tests: add "operator directory request (accepted; unsupported-station fallback)".
6. §13 revision history: add `| 3.2.0 | 2026-09-17 | §4.5 operator directory (`operator_list_requested` → `operator_list`) for the login dropdown. |` matching the existing row format.

Also update `LOGIN_MQTT_CONTRACT.md` (the superseded pointer) with a bullet: "*(3.2.0)* `operator_list_requested` → `operator_list` supplies the login-screen user dropdown (§4.5)."

- [ ] **Step 6: Commit**

```bash
git add docs/Station1_MQTT_Contract_v3.md LOGIN_MQTT_CONTRACT.md tools/simlib/world.py tools/tests/test_world.py
git commit -m "Contract 3.2.0: operator_list_requested for the login dropdown; simulator support"
```

---

### Task 6: OperatorDirectory (parser, cache, AuthClient request)

**Files:**
- Create: `app/src/main/java/com/mitas/ppnam/station1aa/OperatorDirectory.kt`
- Test: `app/src/test/java/com/mitas/ppnam/station1aa/OperatorListCodecTest.kt`
- Modify: `app/src/main/java/com/mitas/ppnam/station1aa/AuthClient.kt`

**Interfaces:**
- Produces:
  ```kotlin
  data class OperatorEntry(val username: String, val displayName: String)
  object OperatorListCodec {
      fun fromResponse(json: JSONObject): List<OperatorEntry>   // reads "operators"
      fun encode(list: List<OperatorEntry>): String             // JSON array text
      fun decode(text: String?): List<OperatorEntry>            // tolerant; empty on garbage
  }
  class OperatorDirectory(context: Context) {
      fun cached(): List<OperatorEntry>
      fun refresh(onUpdated: (List<OperatorEntry>) -> Unit)    // main thread callback, only on success
  }
  // AuthClient
  fun operatorList(onResult: (Result<List<OperatorEntry>>) -> Unit)
  ```

- [ ] **Step 1: Write the failing tests**

```kotlin
package com.mitas.ppnam.station1aa

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Contract v3.2.0 §4.5: display-only operator directory for the login dropdown. */
class OperatorListCodecTest {

    private val response = JSONObject(
        """{"accepted":true,"operators":[
             {"username":"op.tag","displayName":"Thandi Tag"},
             {"username":"op.both","displayName":"Bongi Both","role":"Admin"},
             {"username":"","displayName":"Nobody"},
             {"username":"op.nodisplay"}
           ]}"""
    )

    @Test
    fun `parses username and displayName, drops blank usernames, defaults displayName`() {
        val list = OperatorListCodec.fromResponse(response)
        assertEquals(
            listOf(
                OperatorEntry("op.tag", "Thandi Tag"),
                OperatorEntry("op.both", "Bongi Both"),
                OperatorEntry("op.nodisplay", "op.nodisplay"),
            ),
            list,
        )
    }

    @Test
    fun `a response without operators yields an empty list`() {
        assertTrue(OperatorListCodec.fromResponse(JSONObject("""{"accepted":true}""")).isEmpty())
    }

    @Test
    fun `encode and decode round-trip`() {
        val list = listOf(OperatorEntry("a", "A Person"), OperatorEntry("b", "B"))
        assertEquals(list, OperatorListCodec.decode(OperatorListCodec.encode(list)))
    }

    @Test
    fun `decode tolerates null and garbage`() {
        assertTrue(OperatorListCodec.decode(null).isEmpty())
        assertTrue(OperatorListCodec.decode("not json").isEmpty())
        assertTrue(OperatorListCodec.decode("{}").isEmpty())
    }
}
```

- [ ] **Step 2: Run to verify failure**

Run: `.\gradlew.bat :app:testDebugUnitTest --tests "com.mitas.ppnam.station1aa.OperatorListCodecTest"`
Expected: compilation FAILS.

- [ ] **Step 3: Implement `OperatorDirectory.kt`**

```kotlin
package com.mitas.ppnam.station1aa

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** One row of the login dropdown (contract v3.2.0 §4.5). Display-only. */
data class OperatorEntry(val username: String, val displayName: String)

object OperatorListCodec {

    /** Reads `operators` from an accepted `operator_list` response. Blank usernames are dropped. */
    fun fromResponse(json: JSONObject): List<OperatorEntry> = fromArray(json.optJSONArray("operators"))

    fun encode(list: List<OperatorEntry>): String = JSONArray().apply {
        list.forEach { put(JSONObject().put("username", it.username).put("displayName", it.displayName)) }
    }.toString()

    fun decode(text: String?): List<OperatorEntry> {
        if (text.isNullOrBlank()) return emptyList()
        return try { fromArray(JSONArray(text)) } catch (e: Exception) { emptyList() }
    }

    private fun fromArray(array: JSONArray?): List<OperatorEntry> {
        if (array == null) return emptyList()
        return (0 until array.length()).mapNotNull { i ->
            val o = array.optJSONObject(i) ?: return@mapNotNull null
            val username = o.optString("username", "").trim()
            if (username.isEmpty()) return@mapNotNull null
            OperatorEntry(username, o.optString("displayName", "").trim().ifEmpty { username })
        }
    }
}

/**
 * The login screen's operator directory: asks the station for the list and keeps the last
 * accepted one on the device so the dropdown is populated before (or without) an answer.
 */
class OperatorDirectory(context: Context) {

    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences("operator_directory", Context.MODE_PRIVATE)

    fun cached(): List<OperatorEntry> = OperatorListCodec.decode(prefs.getString(KEY_OPERATORS, null))

    /** Requests a fresh list; [onUpdated] runs on the main thread only when the station accepted. */
    fun refresh(onUpdated: (List<OperatorEntry>) -> Unit) {
        AuthClient(appContext).operatorList { result ->
            result.onSuccess { list ->
                prefs.edit().putString(KEY_OPERATORS, OperatorListCodec.encode(list)).apply()
                onUpdated(list)
            }
        }
    }

    private companion object {
        const val KEY_OPERATORS = "operators"
    }
}
```

- [ ] **Step 4: Add the request to `AuthClient`**

After `loginWithBadge`:

```kotlin
    /** Contract v3.2.0 §4.5: the display-only operator directory for the login dropdown. */
    fun operatorList(onResult: (Result<List<OperatorEntry>>) -> Unit) {
        val payload = Schema41.envelope(Schema41.newMessageId("operator-list"), deviceId())
        request("operator_list_requested", "operator_list", payload) { result ->
            onResult(result.map { OperatorListCodec.fromResponse(it) })
        }
    }
```

Update the class KDoc topic list with `req/operator_list_requested -> res/operator_list`.

- [ ] **Step 5: Run tests and build**

Run: `.\gradlew.bat :app:testDebugUnitTest :app:assembleDebug`
Expected: BUILD SUCCESSFUL, 4 new tests pass.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/mitas/ppnam/station1aa/OperatorDirectory.kt app/src/test/java/com/mitas/ppnam/station1aa/OperatorListCodecTest.kt app/src/main/java/com/mitas/ppnam/station1aa/AuthClient.kt
git commit -m "OperatorDirectory: operator_list request, parser and on-device cache"
```

---

### Task 7: Login screen user dropdown

**Files:**
- Modify: `app/src/main/res/layout/activity_login.xml` (the `tilUsername` block)
- Modify: `app/src/main/java/com/mitas/ppnam/station1aa/LoginActivity.kt`
- Modify: `tools/test_campaign/login_section.py` (new case L11)

**Interfaces:**
- Consumes: `OperatorDirectory`, `OperatorEntry` (Task 6); `MqttManager.addConnectionListener((Boolean) -> Unit)`.

- [ ] **Step 1: Layout — exposed dropdown that stays typeable**

Replace the `tilUsername` block with:

```xml
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
                            android:imeOptions="actionNext"
                            android:inputType="text"
                            android:maxLines="1"
                            android:completionThreshold="1"
                            android:textColor="@color/text_primary_dark" />
                    </com.google.android.material.textfield.TextInputLayout>
```

- [ ] **Step 2: Wire the dropdown in `LoginActivity`**

Add fields:

```kotlin
    private lateinit var directory: OperatorDirectory
    private var operators: List<OperatorEntry> = emptyList()

    /** Refresh the directory each time the broker link comes up (spec §4). */
    private val connectionListener: (Boolean) -> Unit = { connected ->
        if (connected) directory.refresh { list -> runOnUiThread { showOperators(list) } }
    }
```

In `onCreate` after `authClient = AuthClient(this)`:

```kotlin
        directory = OperatorDirectory(this)
        showOperators(directory.cached())
        MqttManager.getInstance(this).addConnectionListener(connectionListener)
```

Add:

```kotlin
    /**
     * Dropdown rows read "Display Name (username)"; picking one fills the field with the
     * username only, which is what SCRAM authenticates. Typing any other name still works.
     */
    private fun showOperators(list: List<OperatorEntry>) {
        operators = list.sortedBy { it.displayName.lowercase() }
        // Username first: the adapter filters on the label's prefix, so typing the start of
        // a username still narrows the list. Picking a row leaves only the username behind,
        // which is what SCRAM authenticates.
        val labels = operators.map { "${it.username} — ${it.displayName}" }
        binding.etUsername.setAdapter(
            android.widget.ArrayAdapter(this, android.R.layout.simple_list_item_1, labels)
        )
        binding.etUsername.setOnItemClickListener { _, _, position, _ ->
            val label = binding.etUsername.adapter.getItem(position) as String
            val picked = operators.firstOrNull { "${it.username} — ${it.displayName}" == label }
            binding.etUsername.setText(picked?.username ?: label, false)
            binding.etPassword.requestFocus()
        }
    }
```

In `onDestroy`, alongside the status-listener removal, add
`MqttManager.getInstance(this).removeConnectionListener(connectionListener)`.

- [ ] **Step 3: Build**

Run: `.\gradlew.bat :app:assembleDebug`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Campaign case L11**

Append to `tools/test_campaign/login_section.py` before the `c.finish()` (or final) line inside `main()`:

```python
        # ---------------------------------------------------------- L11
        with c.case("L11", "Login dropdown lists the station's operators; picking one fills the username") as case:
            to_login_screen(sim)
            asked = sim.wait_for(
                lambda e: e["dir"] == "in" and e["topic"].endswith("req/operator_list_requested"),
                timeout=15)
            expect(asked is not None, "app never requested the operator list")
            d.tap(id="etUsername")
            row = d.find(text="op.tag — Thandi Tag", retries=6)
            expect(row is not None, "dropdown did not show the simulator's operators")
            case.shot(d.screenshot("L11_dropdown"))
            d.tap(xy=row.center)
            field = d.find(id="etUsername", retries=3)
            expect(field is not None and field.text.strip() == "op.tag", f"username field {field}")
            d.type_into("etPassword", "tag123!")
            d.key("KEYCODE_BACK")
            d.tap(id="btnLogin")
            expect(on_main(), "login via dropdown pick failed")
```

If `d.find(text=...)` does not match because the em dash is transliterated by uiautomator, match on `"Thandi Tag"` with `text=` and a substring helper instead.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/res/layout/activity_login.xml app/src/main/java/com/mitas/ppnam/station1aa/LoginActivity.kt tools/test_campaign/login_section.py
git commit -m "Login: operator dropdown fed by operator_list, cached on device"
```

---

### Task 8: Scan-only RFID and barcode fields on Offload

**Files:**
- Modify: `app/src/main/res/layout/activity_offload.xml` (`etTag`, `etBarcode`)
- Modify: `app/src/main/res/values/strings.xml` (`hint_tag_id`, `hint_barcode`)
- Modify: `tools/test_campaign/offload_section.py` (new case O14)

- [ ] **Step 1: Layout**

Replace both edit texts' attribute sets:

```xml
                        <com.google.android.material.textfield.TextInputEditText
                            android:id="@+id/etTag"
                            android:layout_width="match_parent"
                            android:layout_height="wrap_content"
                            android:cursorVisible="false"
                            android:focusable="false"
                            android:focusableInTouchMode="false"
                            android:inputType="none"
                            android:longClickable="false"
                            android:maxLines="1"
                            android:textColor="@color/text_primary_dark" />
```

Same for `etBarcode` (id changed). Strings:

```xml
    <string name="hint_tag_id">Scan RFID tag</string>
    <string name="hint_barcode">Scan barcode</string>
```

- [ ] **Step 2: Build and sanity-check OffloadActivity**

`enterScanStep` / `matchPallet` still toggle `isEnabled` on both fields for the greyed look during MATCHING; no code change needed. Run: `.\gradlew.bat :app:assembleDebug` → BUILD SUCCESSFUL.

- [ ] **Step 3: Campaign case O14**

Append to `offload_section.py` inside `main()`:

```python
        # ------------------------------------------------------------ O14
        with c.case("O14", "RFID and barcode fields ignore typing; only scans fill them") as case:
            fresh_offload(sim)
            d.tap(id="etTag")
            d.text("TYPED")
            d.tap(id="etBarcode")
            d.text("TYPED")
            tag = d.find(id="etTag", retries=2)
            bc = d.find(id="etBarcode", retries=2)
            expect((tag.text or "").strip() in ("", "Scan RFID tag"), f"typing reached etTag: {tag.text!r}")
            expect((bc.text or "").strip() in ("", "Scan barcode"), f"typing reached etBarcode: {bc.text!r}")
            btn = d.find(id="btnMatchPallet")
            expect(btn is not None and not btn.enabled, "Match Pallet enabled without scans")
            d.scan_rfid("TAG-PAL-001")
            d.scan_barcode("BC-001")
            btn = d.find(id="btnMatchPallet", retries=3)
            expect(btn is not None and btn.enabled, "scans did not enable Match Pallet")
            case.shot(d.screenshot("O14_scan_only"))
```

- [ ] **Step 4: Commit**

```bash
git add app/src/main/res/layout/activity_offload.xml app/src/main/res/values/strings.xml tools/test_campaign/offload_section.py
git commit -m "Offload: RFID and barcode fields are scan-only"
```

---

### Task 9: Campaign cases for auto-logout, version bump, docs

**Files:**
- Modify: `app/build.gradle.kts` (lines 21-22)
- Modify: `tools/test_campaign/settings_section.py` (new S9)
- Modify: `tools/test_campaign/login_section.py` (new L12)
- Modify: `tools/test_campaign/build_matrix.py` (simulator version text + pytest count)
- Modify: `README.md` if it lists the version or features (grep `1.2.0`)

- [ ] **Step 1: Version bump**

```kotlin
        versionCode = 3
        versionName = "1.3.0"
```

- [ ] **Step 2: Settings case S9**

Append inside `settings_section.py` `main()`:

```python
        with c.case("S9", "Auto sign-out minutes are validated and saved") as case:
            open_settings()
            enter_pin(PIN)
            node = d.scroll_to("etAutoLogout")
            expect(node is not None, "auto sign-out field not found")
            expect(node.text.strip() == "15", f"default was {node.text!r}, expected 15")
            d.type_into("etAutoLogout", "5000")
            d.key("KEYCODE_BACK")
            d.tap(id="btnSaveSettings")
            expect(d.find(id="etAutoLogout", retries=2) is not None, "invalid value was accepted")
            d.type_into("etAutoLogout", "1")
            d.key("KEYCODE_BACK")
            d.tap(id="btnSaveSettings")
            time.sleep(3)
            open_settings()
            enter_pin(PIN)
            node = d.scroll_to("etAutoLogout")
            expect(node is not None and node.text.strip() == "1", f"saved value {node and node.text!r}")
            case.note("auto sign-out set to 1 minute (restored to 15 by L12)")
```

- [ ] **Step 3: Login case L12 (inactivity sign-out, restores the default)**

Append to `login_section.py` `main()` after L11:

```python
        # ---------------------------------------------------------- L12
        with c.case("L12", "Inactivity auto sign-out returns to login with a reason") as case:
            # S9 leaves the setting at 1 minute; set it here too so this case stands alone.
            d.relaunch(wait=4)
            d.tap(id="btnSettings")
            d.type_into("etPin", "079545")
            d.key("KEYCODE_BACK")
            d.tap(id="btnUnlock")
            time.sleep(0.8)
            d.scroll_to("etAutoLogout")
            d.type_into("etAutoLogout", "1")
            d.key("KEYCODE_BACK")
            d.tap(id="btnSaveSettings")
            time.sleep(3)
            to_login_screen(sim)
            scram_login("op.both", "both123!")
            expect(on_main(), "login failed")
            t0 = time.time()
            expect(d.find(id="etUsername", retries=1) is None, "already on login?")
            # No touches from here on; poll gently (uiautomator dumps are not user interaction).
            back_on_login = False
            while time.time() - t0 < 90:
                if d.find(id="etUsername", retries=1) is not None:
                    back_on_login = True
                    break
                time.sleep(5)
            expect(back_on_login, "inactivity sign-out never happened within 90s")
            err = d.find(id="tvLoginError", retries=5)
            expect(err is not None and "inactivity" in err.text.lower(), f"reason {err}")
            case.note(f"signed out after ~{int(time.time() - t0)}s: {err.text!r}")
            # restore the default
            d.tap(id="btnSettings")
            d.type_into("etPin", "079545")
            d.key("KEYCODE_BACK")
            d.tap(id="btnUnlock")
            time.sleep(0.8)
            d.scroll_to("etAutoLogout")
            d.type_into("etAutoLogout", "15")
            d.key("KEYCODE_BACK")
            d.tap(id="btnSaveSettings")
            time.sleep(3)
```

- [ ] **Step 4: build_matrix.py header**

Change `v3.1.0` to `v3.2.0` and `{74}` to `76` in the header lines.

- [ ] **Step 5: Build, unit tests, simulator tests**

Run: `.\gradlew.bat :app:testDebugUnitTest :app:assembleDebug` and `cd tools && python -m pytest tests -q`
Expected: all green.

- [ ] **Step 6: Commit**

```bash
git add app/build.gradle.kts tools/test_campaign README.md
git commit -m "Bump to 1.3.0; campaign cases for auto sign-out and the operator directory"
```

---

### Task 10: Device campaign run and TEST_MATRIX

**Files:**
- Modify: `docs/TEST_MATRIX.md` (generated)

- [ ] **Step 1: Start the simulator headless** (see memory `station1-test-campaign.md` for the exact invocation) and confirm a C72 is attached: `adb devices`.

- [ ] **Step 2: Install the debug APK**: `adb install -r app/build/outputs/apk/debug/app-debug.apk`.

- [ ] **Step 3: Run each section** in this order and fix any failure at its source before moving on: `login_section.py`, `tag_section.py`, `offload_section.py`, `settings_section.py`, `presence_section.py`.

- [ ] **Step 4: Regenerate the matrix**: `python tools/test_campaign/build_matrix.py`, read `docs/TEST_MATRIX.md`, confirm the overall count is all-pass.

- [ ] **Step 5: Commit**

```bash
git add docs/TEST_MATRIX.md tools/test_campaign/results
git commit -m "Test matrix: 1.3.0 campaign on the C72"
```

If no device is attached, skip Steps 1-5, say so explicitly in the final report, and leave the matrix untouched.
