package com.nagi.dosecircle.notifications.receiver

import android.app.AlarmManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.nagi.dosecircle.DoseCircleApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class ExactAlarmPermissionReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != AlarmManager.ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED) {
            return
        }
        Log.d(TAG, "Exact alarm permission state change broadcast received")

        val pendingResult = goAsync()
        val app = context.applicationContext as DoseCircleApp

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val count = app.alarmScheduler.rescheduleAll()
                Log.d(TAG, "Rescheduled $count alarms after exact alarm permission state change")
            } catch (e: Exception) {
                Log.e(TAG, "Error rescheduling alarms in ExactAlarmPermissionReceiver", e)
            } finally {
                pendingResult.finish()
            }
        }
    }

    companion object {
        private const val TAG = "ExactAlarmPermissionReceiver"
    }
}

