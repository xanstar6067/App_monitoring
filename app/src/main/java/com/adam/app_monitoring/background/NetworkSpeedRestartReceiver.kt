package com.adam.app_monitoring.background

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class NetworkSpeedRestartReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_RESTART) return
        NetworkSpeedDiagnostics.record(context, "restart_alarm_received")
        NetworkSpeedServiceController.start(context, "restart_alarm")
    }

    companion object {
        const val ACTION_RESTART = "com.adam.app_monitoring.action.RESTART_NETWORK_SPEED"
    }
}
