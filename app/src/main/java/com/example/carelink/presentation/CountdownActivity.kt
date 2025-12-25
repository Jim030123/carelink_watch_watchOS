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
                // 🛑 核心修改：倒计时结束不再发送 Reset 广播，让 Service 继续执行通话逻辑
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
            Log.d("FALL", "User clicked 'I'm OK' - Canceling alert")
            sendResetOnce()
            finish()
        }

        handler.postDelayed(countdownRunnable, 1000)
    }

    /**
     * 修改 onDestroy 逻辑：
     * 只有当用户主动关闭（如点击按钮或手势返回）时才重置。
     * 如果是倒计时超时自动关闭，则不触发重置，以允许通话继续。
     */
    override fun onDestroy() {
        Log.d("FALL", "CountdownActivity onDestroy (isTimeout=$isTimeout)")
        if (!isTimeout) {
            // 如果不是因为超时结束的，说明用户可能手动取消了（点按钮或返回键）
            sendResetOnce()
        }
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
        intent.setPackage(packageName) 
        sendBroadcast(intent)
    }
}
