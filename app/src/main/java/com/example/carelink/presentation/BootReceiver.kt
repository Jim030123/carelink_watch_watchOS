package com.example.carelink.presentation

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {

            Log.d("BootReceiver", "BOOT_COMPLETED received")

            val serviceIntent = Intent(context, FallDetectService::class.java)
            context.startForegroundService(serviceIntent)
        }
    }
}
