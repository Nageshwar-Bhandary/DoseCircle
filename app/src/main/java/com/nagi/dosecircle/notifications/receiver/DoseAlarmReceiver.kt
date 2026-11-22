package com.nagi.dosecircle.notifications.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

class DoseAlarmReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "DoseAlarmReceiver"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val scheduleTimeId = intent.getLongExtra("scheduleTimeId", -1L)
        val medicationId = intent.getLongExtra("medicationId", -1L)
        val personId = intent.getLongExtra("personId", -1L)

        Log.d(
            TAG,
            "Alarm fired: action=${intent.action}, scheduleTimeId=$scheduleTimeId, medicationId=$medicationId, personId=$personId"
        )
    }
}
