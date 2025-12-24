package com.example.carelink.presentation

import android.app.Activity
import android.content.Intent
import android.os.*
import android.util.Log
import android.view.WindowManager
import android.widget.Button
import android.widget.TextView
import com.example.carelink.R

class CountdownActivity : Activity() {

    private var remaining = 10
    private var resetSent = false

    private lateinit var handler: Handler
    private lateinit var countdownText: TextView
    private lateinit var okButton: Button

    private val countdownRunnable = object : Runnable {
        override fun run() {
            remaining--
            updateText()

            if (remaining <= 0) {
                Log.w("FALL", "Countdown finished")
                sendResetOnce()
                finish()
            } else {
                handler.postDelayed(this, 1000)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 🔥 非常重要：保证在锁屏 / 息屏 / 手表抬腕都能显示
        window.addFlags(
            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
                    WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                    WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
        )

        setContentView(R.layout.activity_countdown)

        countdownText = findViewById(R.id.countdownText)
        okButton = findViewById(R.id.okButton)

        handler = Handler(Looper.getMainLooper())

        updateText()

        Log.d("FALL", "CountdownActivity started")

        okButton.setOnClickListener {
            Log.d("FALL", "User clicked I'M OK")
            sendResetOnce()
            finish()
        }

        // ⏱ 延迟 1 秒启动，避免 Activity 刚创建就被系统 pause
        handler.postDelayed(countdownRunnable, 1000)
    }

    override fun onDestroy() {
        Log.d("FALL", "CountdownActivity destroyed")
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    // ===============================
    // 工具方法
    // ===============================

    private fun updateText() {
        countdownText.text = "检测到跌倒\n$remaining 秒后将通知照护者"
    }

    private fun sendResetOnce() {
        if (resetSent) return
        resetSent = true

        Log.e("FALL", "SEND RESET BROADCAST")

        sendBroadcast(
            Intent("ACTION_FALL_ALERT_RESET")
        )
    }
}
