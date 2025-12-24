package com.example.carelink.presentation

import android.app.*
import android.content.Intent
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.media.AudioAttributes
import android.media.SoundPool
import android.os.*
import android.util.Log
import androidx.core.app.NotificationCompat
import com.example.carelink.R
import kotlin.math.sqrt

class FallDetectService : Service(), SensorEventListener {

    // ===============================
    // Sensor
    // ===============================
    private lateinit var sensorManager: SensorManager
    private var accelerometer: Sensor? = null

    // ===============================
    // Sound
    // ===============================
    private lateinit var soundPool: SoundPool
    private var alertSoundId: Int = 0

    // ===============================
    // Fall detection state
    // ===============================
    private var freeFallDetected = false
    private var impactDetected = false
    private var impactTime: Long = 0

    // Thresholds (tunable)
    private val FREE_FALL_THRESHOLD = 6.5f      // < 0.5g
    private val IMPACT_THRESHOLD = 30f         // > 2.5g
    private val STILL_THRESHOLD = 12f          // ~1g
    private val STILL_TIME_MS = 2000L           // 1.5s

    // ===============================
    // Lifecycle
    // ===============================
    override fun onCreate() {
        super.onCreate()

        // 🔥 必须：立刻进入前台
        startForeground(1001, createNotification())

        // 🔊 初始化警报音（后台允许）
        val audioAttributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ALARM)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()

        soundPool = SoundPool.Builder()
            .setMaxStreams(1)
            .setAudioAttributes(audioAttributes)
            .build()

        // ⚠️ 你需要在 res/raw/ 放一个 fall_alert.wav
        alertSoundId = soundPool.load(this, R.raw.fall_alert, 1)

        // 🧭 初始化传感器
        sensorManager = getSystemService(SENSOR_SERVICE) as SensorManager
        accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

        accelerometer?.let {
            sensorManager.registerListener(
                this,
                it,
                SensorManager.SENSOR_DELAY_GAME
            )
        }

        Log.d("FallDetectService", "Service started")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        sensorManager.unregisterListener(this)
        soundPool.release()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ===============================
    // Sensor callback (3-stage fall detection)
    // ===============================
    override fun onSensorChanged(event: SensorEvent) {
        val x = event.values[0]
        val y = event.values[1]
        val z = event.values[2]

        val magnitude = sqrt(x * x + y * y + z * z)
        val now = System.currentTimeMillis()

        // ① Free fall
        if (!freeFallDetected && magnitude < FREE_FALL_THRESHOLD) {
            freeFallDetected = true
            Log.d("FALL", "Free fall detected")
            return
        }

        // ② Impact
        if (freeFallDetected && !impactDetected && magnitude > IMPACT_THRESHOLD) {
            impactDetected = true
            impactTime = now
            Log.d("FALL", "Impact detected")
            return
        }

        // ③ Stillness
        if (impactDetected) {
            val still = magnitude in 8f..STILL_THRESHOLD

            if (still && now - impactTime > STILL_TIME_MS) {
                Log.e("FALL", "FALL CONFIRMED")
                strongAlert()
                resetFallState()
            }

            // 超时清空，避免卡死
            if (now - impactTime > 3000) {
                resetFallState()
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    private fun resetFallState() {
        freeFallDetected = false
        impactDetected = false
        impactTime = 0
    }

    // ===============================
    // Strong vibration + alarm sound
    // ===============================
    private fun strongAlert() {
        // 📳 强震动
        val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val vm = getSystemService(VIBRATOR_MANAGER_SERVICE) as VibratorManager
            vm.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            getSystemService(VIBRATOR_SERVICE) as Vibrator
        }

        vibrator.vibrate(
            VibrationEffect.createWaveform(
                longArrayOf(0, 400, 200, 400, 200, 400),
                intArrayOf(
                    0,
                    VibrationEffect.DEFAULT_AMPLITUDE,
                    0,
                    VibrationEffect.DEFAULT_AMPLITUDE,
                    0,
                    VibrationEffect.DEFAULT_AMPLITUDE
                ),
                -1
            )
        )

        // 🔊 警报音
        soundPool.play(
            alertSoundId,
            1f,
            1f,
            1,
            0,
            1f
        )
    }

    // ===============================
    // Foreground notification
    // ===============================
    private fun createNotification(): Notification {
        val channelId = "fall_detect_channel"

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                "Fall Detection",
                NotificationManager.IMPORTANCE_LOW
            )
            getSystemService(NotificationManager::class.java)
                .createNotificationChannel(channel)
        }

        return NotificationCompat.Builder(this, channelId)
            .setContentTitle("CareLink 正在监测")
            .setContentText("跌倒检测运行中")
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setOngoing(true)
            .build()
    }
}
