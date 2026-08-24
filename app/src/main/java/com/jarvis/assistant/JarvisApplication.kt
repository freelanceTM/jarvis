package com.jarvis.assistant

import android.app.Application
import android.content.Intent
import com.jarvis.assistant.agent.automation.scheduler.AutomationScheduleReceiver
import dagger.hilt.android.HiltAndroidApp

@HiltAndroidApp
class JarvisApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        // Room remains the source of truth. Reconcile on every process start so
        // force-stop/update/restart cannot leave stale or missing alarms.
        sendBroadcast(
            Intent(this, AutomationScheduleReceiver::class.java)
                .setAction(AutomationScheduleReceiver.ACTION_RECONCILE)
        )
    }
}
