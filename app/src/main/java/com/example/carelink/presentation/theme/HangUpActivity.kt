package com.example.carelink.presentation.theme

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.WindowManager
import android.widget.ImageButton
import android.widget.TextView
import com.example.carelink.R

class HangUpActivity : Activity() {

    private lateinit var tvMin: TextView
    private lateinit var tvSec: TextView
    private lateinit var btnHangUp: ImageButton

    private val handler = Handler(Looper.getMainLooper())
    private var elapsedSeconds = 0

    private val timerRunnable = object : Runnable {
        override fun run() {
            elapsedSeconds++
            val min = elapsedSeconds / 60
            val sec = elapsedSeconds % 60
            tvMin.text = String.format("%02d", min)
            tvSec.text = String.format("%02d", sec)
            handler.postDelayed(this, 1000)
        }
    }

    // ⭐ 监听重置广播，实现同步关闭界面
    private val resetReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            Log.d("RTC", "HangUpActivity received remote hangup, finishing...")
            finish()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        window.addFlags(
            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
                    WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                    WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
        )
        
        setContentView(R.layout.activity_hangup)

        tvMin = findViewById(R.id.tvMin)
        tvSec = findViewById(R.id.tvSec)
        btnHangUp = findViewById(R.id.btnHangUp)

        btnHangUp.setOnClickListener {
            Log.d("RTC", "User clicked Hang Up button")
            sendResetBroadcast()
            finish()
        }

        // 注册接收器
        val filter = IntentFilter("ACTION_FALL_ALERT_RESET")
        registerReceiver(resetReceiver, filter, Context.RECEIVER_NOT_EXPORTED)

        handler.post(timerRunnable)
    }

    private fun sendResetBroadcast() {
        Log.e("FALL", ">>> SENDING RESET BROADCAST FROM HANGUP <<<")
        val intent = Intent("ACTION_FALL_ALERT_RESET")
        intent.setPackage(packageName)
        sendBroadcast(intent)
    }

    override fun onDestroy() {
        handler.removeCallbacks(timerRunnable)
        try {
            unregisterReceiver(resetReceiver)
        } catch (e: Exception) {}
        super.onDestroy()
    }
}
