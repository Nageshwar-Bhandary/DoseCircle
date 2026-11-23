package com.nagi.dosecircle.notifications.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.nagi.dosecircle.DoseCircleApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class BootReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "BootReceiver"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        Log.d(TAG, "Received broadcast action: $action")

        val validActions = setOf(
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            Intent.ACTION_TIME_CHANGED,
            Intent.ACTION_TIMEZONE_CHANGED
        )

        if (action !in validActions) {
            Log.d(TAG, "Ignoring unrecognized action: $action")
            return
        }

        val pendingResult = goAsync()
        val app = context.applicationContext as DoseCircleApp

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val count = app.alarmScheduler.rescheduleAll()
                Log.d(TAG, "Rescheduled $count alarms after receiving $action")
            } catch (e: Exception) {
                Log.e(TAG, "Error rescheduling alarms in BootReceiver for action $action", e)
            } finally {
                pendingResult.finish()
            }
        }
    }
}
