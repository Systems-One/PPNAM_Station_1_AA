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
