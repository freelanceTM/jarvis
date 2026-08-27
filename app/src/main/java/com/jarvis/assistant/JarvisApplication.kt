package com.jarvis.assistant

import android.app.Application
import dagger.hilt.android.HiltAndroidApp

@HiltAndroidApp
class JarvisApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        // H-06: автоматический reconcile на холодный старт убран.
        //
        // Раньше здесь дёргался AutomationScheduleReceiver напрямую — это
        // было дорого и провоцировало двойную работу на screen-on/password-prompt.
        // Теперь reconcile запускается:
        //
        //   1. По BOOT_COMPLETED → WorkManager unique work "automation-reconcile"
        //      (KEEP policy, 5s initial delay, переживёт doze/process-death);
        //   2. При MY_PACKAGE_REPLACED / TIME_CHANGED / TIMEZONE_CHANGED напрямую
        //      в AutomationScheduleReceiver;
        //   3. По расписанию (ACTION_TRIGGER от AlarmManager).
    }
}
