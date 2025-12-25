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
                Log.w("FALL", "Countdown finished - Notifying emergency contacts")
                // 这里可以添加发送紧急短信/API的逻辑
                sendResetOnce()
                finish()
            } else {
                handler.postDelayed(this, 1000)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 保持屏幕常亮并在锁屏上显示
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

        okButton.setOnClickListener {
            Log.d("FALL", "User clicked OK - Canceling alert")
            sendResetOnce()
            finish()
        }

        handler.postDelayed(countdownRunnable, 1000)
    }

    /**
     * 非常重要：如果用户通过手势滑动关闭了 Activity 而没点按钮，
     * 我们也要发送重置广播，否则 Service 会一直卡在 alertTriggered = true 状态。
     */
    override fun onDestroy() {
        Log.d("FALL", "CountdownActivity onDestroy")
        sendResetOnce()
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    private fun updateText() {
        countdownText.text = "检测到疑似跌倒\n\n$remaining 秒后将发出求救"
    }

    private fun sendResetOnce() {
        if (resetSent) return
        resetSent = true

        Log.e("FALL", ">>> SENDING RESET BROADCAST TO SERVICE <<<")
        val intent = Intent("ACTION_FALL_ALERT_RESET")
        // 明确指定包名，增加安全性，确保 Service 必能收到
        intent.setPackage(packageName) 
        sendBroadcast(intent)
    }
}
