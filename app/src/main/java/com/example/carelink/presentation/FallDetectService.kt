package com.example.carelink.presentation

import android.annotation.SuppressLint
import android.app.*
import android.content.*
import android.hardware.*
import android.media.AudioAttributes
import android.media.SoundPool
import android.os.*
import android.util.Log
import androidx.core.app.NotificationCompat
import com.example.carelink.R
import kotlin.math.abs
import kotlin.math.sqrt

class FallDetectService : Service(), SensorEventListener {

    // ===============================
    // RTC 信令客户端
    // ===============================
    private lateinit var rtcClient: RtcSignalClient

    // ===============================
    // 防重复触发标志位
    // ===============================
    private var alertTriggered = false

    // ===============================
    // 传感器
    // ===============================
    private lateinit var sensorManager: SensorManager
    private var accelerometer: Sensor? = null

    // ===============================
    // 跌倒参数（Galaxy Watch 稳定）
    // ===============================
    private val FREE_FALL_THRESHOLD = 5f
    private val IMPACT_THRESHOLD = 25f
    private val STILL_THRESHOLD = 11f
    private val STILL_TIME_MS = 1500L
    private val FALL_TIMEOUT_MS = 5000L 

    private var lastResetTime = 0L
    private val RESET_COOLDOWN_MS = 2000L

    // ===============================
    // 状态机
    // ===============================
    private var inFreeFall = false
    private var impactDetected = false
    private var stillStartTime = 0L
    private var fallHandled = false
    private var fallStartTime = 0L 

    // ===============================
    // 声音 & 震动
    // ===============================
    private lateinit var soundPool: SoundPool
    private var alertSoundId = 0

    // ===============================
    // 重置广播监听器
    // ===============================
    private val resetReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            Log.e("FALL", ">>> RESET SIGNAL RECEIVED <<<")
            resetAll()
        }
    }

    override fun onCreate() {
        super.onCreate()
        Log.d("FALL", "FallDetectService Created")

        // 1. 初始化 RTC 客户端并连接
        rtcClient = RtcSignalClient()
        rtcClient.connect()

        startForeground(1001, createNotification())

        // 兼容性注册广播
        val filter = IntentFilter("ACTION_FALL_ALERT_RESET")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(resetReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(resetReceiver, filter)
        }

        // 初始化声音
        val audioAttributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ALARM)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()
        soundPool = SoundPool.Builder().setMaxStreams(1).setAudioAttributes(audioAttributes).build()
        alertSoundId = soundPool.load(this, R.raw.fall_alert, 1)

        // 初始化传感器
        sensorManager = getSystemService(SENSOR_SERVICE) as SensorManager
        accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        accelerometer?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    override fun onDestroy() {
        try {
            unregisterReceiver(resetReceiver)
        } catch (e: Exception) {
            Log.e("FALL", "Unregister failed: ${e.message}")
        }
        sensorManager.unregisterListener(this)
        soundPool.release()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onSensorChanged(event: SensorEvent) {
        if (alertTriggered) return
        if (System.currentTimeMillis() - lastResetTime < RESET_COOLDOWN_MS) return

        val x = event.values[0]
        val y = event.values[1]
        val z = event.values[2]
        val magnitude = sqrt(x * x + y * y + z * z)
        val now = System.currentTimeMillis()

        if (inFreeFall && (now - fallStartTime > FALL_TIMEOUT_MS)) {
            Log.d("FALL", "Timeout reset")
            resetStateVariables()
        }

        if (!inFreeFall && magnitude < FREE_FALL_THRESHOLD) {
            inFreeFall = true
            fallStartTime = now
            impactDetected = false
            stillStartTime = 0
            fallHandled = false
            Log.d("FALL", "Stage 1: Fall detected")
            return
        }

        if (inFreeFall && !impactDetected && magnitude > IMPACT_THRESHOLD) {
            impactDetected = true
            Log.d("FALL", "Stage 2: Impact detected")
            return
        }

        if (impactDetected && !fallHandled) {
            if (abs(magnitude - STILL_THRESHOLD) < 2f) {
                if (stillStartTime == 0L) {
                    stillStartTime = now
                } else if (now - stillStartTime >= STILL_TIME_MS) {
                    fallHandled = true
                    triggerFallAlert()
                }
            } else if (magnitude > 15f) {
                stillStartTime = 0
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    private fun triggerFallAlert() {
        if (alertTriggered) return
        alertTriggered = true

        Log.e("FALL", "!!! FALL ALERT TRIGGERED !!!")

        // 🚀 发送 RTC 跌倒信号
        rtcClient.sendFallAlert(userId = "elder_001")

        strongVibrate()
        soundPool.play(alertSoundId, 1f, 1f, 1, 0, 1f)

        val intent = Intent(this, CountdownActivity::class.java)
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        startActivity(intent)

        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(1001, createNotification())
    }

    private fun resetStateVariables() {
        inFreeFall = false
        impactDetected = false
        stillStartTime = 0
        fallHandled = false
    }

    private fun resetAll() {
        Log.e("FALL", "Resetting all states for next detection...")
        resetStateVariables()
        alertTriggered = false
        lastResetTime = System.currentTimeMillis()
    }

    private fun strongVibrate() {
        val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            (getSystemService(VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
        } else {
            @Suppress("DEPRECATION") getSystemService(VIBRATOR_SERVICE) as Vibrator
        }
        vibrator.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 400, 200, 400), intArrayOf(0, 255, 0, 255), -1))
    }

    @SuppressLint("FullScreenIntentPolicy")
    private fun createNotification(): Notification {
        val channelId = "fall_detect_channel"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(channelId, "Fall Detection", NotificationManager.IMPORTANCE_HIGH)
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }

        val intent = Intent(this, CountdownActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

        return NotificationCompat.Builder(this, channelId)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle("CareLink 跌倒提醒")
            .setContentText(if (alertTriggered) "检测到跌倒！" else "跌倒监测运行中")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setFullScreenIntent(pendingIntent, true)
            .build()
    }
}
