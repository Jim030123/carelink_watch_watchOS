package com.example.carelink.presentation

import android.content.Intent
import android.os.*
import android.util.Log
import android.view.WindowManager
import android.widget.Button
import android.widget.TextView
import androidx.activity.ComponentActivity
import com.example.carelink.R

// ⭐ 关键修复：彻底移除 AmbientModeSupport，回到一个干净的状态
class CountdownActivity : ComponentActivity() {

    private var remaining = 10
    private var resetSent = false
    private var isTimeout = false

    private lateinit var handler: Handler
    private lateinit var countdownText: TextView
    private lateinit var okButton: Button

    private val countdownRunnable = object : Runnable {
        override fun run() {
            remaining--
            updateText()

            if (remaining <= 0) {
                Log.w("FALL", "Countdown finished - Proceeding to Emergency Call")
                isTimeout = true
                finish()
            } else {
                handler.postDelayed(this, 1000)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        Log.e("FALL_ACTIVITY_LIFECYCLE", "!!! CountdownActivity onCreate CALLED !!!")

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        }

        super.onCreate(savedInstanceState)

        window.addFlags(
            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
            WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
            WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
            WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD
        )

        setContentView(R.layout.activity_countdown)

        countdownText = findViewById(R.id.countdownText)
        okButton = findViewById(R.id.okButton)
        handler = Handler(Looper.getMainLooper())

        updateText()

        okButton.setOnClickListener {
            Log.d("FALL", "User clicked \'I\'m OK\' - Canceling alert")
            sendResetOnce()
            finish()
        }

        handler.postDelayed(countdownRunnable, 1000)
    }

    override fun onDestroy() {
        Log.d("FALL_ACTIVITY_LIFECYCLE", "CountdownActivity onDestroy (isTimeout=$isTimeout)")
        if (!isTimeout) {
            sendResetOnce()
        }
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    private fun updateText() {
        countdownText.text = "Emergency call in $remaining seconds"
    }

    private fun sendResetOnce() {
        if (resetSent) return
        resetSent = true

        Log.e("FALL", ">>> SENDING RESET BROADCAST TO SERVICE <<<")
        val intent = Intent("ACTION_FALL_ALERT_RESET")
        intent.setPackage(packageName) 
        sendBroadcast(intent)
    }
}
