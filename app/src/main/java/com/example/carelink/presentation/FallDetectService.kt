package com.example.carelink.presentation

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
    // 防重复触发
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

    private var lastResetTime = 0L
    private val RESET_COOLDOWN_MS = 2000L


    // ===============================
    // 状态机
    // ===============================
    private var inFreeFall = false
    private var impactDetected = false
    private var stillStartTime = 0L
    private var fallHandled = false

    // ===============================
    // 声音 & 震动
    // ===============================
    private lateinit var soundPool: SoundPool
    private var alertSoundId = 0

    // ===============================
    // 重置广播（来自 CountdownActivity）
    // ===============================
    private val resetReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            Log.e("FALL", "RESET RECEIVED")
            resetAll()
        }
    }







    // ===============================
    // 生命周期
    // ===============================
    override fun onCreate() {
        super.onCreate()

        startForeground(1001, createNotification())

        registerReceiver(
            resetReceiver,
            IntentFilter("ACTION_FALL_ALERT_RESET"),
            Context.RECEIVER_NOT_EXPORTED
        )


        val audioAttributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ALARM)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()

        soundPool = SoundPool.Builder()
            .setMaxStreams(1)
            .setAudioAttributes(audioAttributes)
            .build()

        alertSoundId = soundPool.load(this, R.raw.fall_alert, 1)

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
        unregisterReceiver(resetReceiver)
        sensorManager.unregisterListener(this)
        soundPool.release()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ===============================
    // 跌倒检测核心
    // ===============================
    override fun onSensorChanged(event: SensorEvent) {


            if (System.currentTimeMillis() - lastResetTime < RESET_COOLDOWN_MS) {
                return
            }

            // 原本的检测逻辑


        val x = event.values[0]
        val y = event.values[1]
        val z = event.values[2]

        val magnitude = sqrt(x * x + y * y + z * z)
        val now = System.currentTimeMillis()

        // 自由落体
        if (!inFreeFall && magnitude < FREE_FALL_THRESHOLD) {
            inFreeFall = true
            impactDetected = false
            stillStartTime = 0
            fallHandled = false
            return
        }

        // 撞击
        if (inFreeFall && !impactDetected && magnitude > IMPACT_THRESHOLD) {
            impactDetected = true
            return
        }

        // 静止
        if (impactDetected && !fallHandled) {
            if (abs(magnitude - STILL_THRESHOLD) < 2f) {
                if (stillStartTime == 0L) {
                    stillStartTime = now
                } else if (now - stillStartTime >= STILL_TIME_MS) {
                    fallHandled = true
                    triggerFallAlert()
                }
            } else {
                stillStartTime = 0
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    // ===============================
    // 跌倒确认
    // ===============================
    private fun triggerFallAlert() {
        Log.e("FALL", "triggerFallAlert() CALLED")

        if (alertTriggered) return
        alertTriggered = true

        strongVibrate()

        soundPool.play(alertSoundId, 1f, 1f, 1, 0, 1f)

        val powerManager = getSystemService(POWER_SERVICE) as PowerManager
        val wakeLock = powerManager.newWakeLock(
            PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP,
            "CareLink:FallWakeLock"
        )
        wakeLock.acquire(3000)

        val intent = Intent(this, CountdownActivity::class.java).apply {
            addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_CLEAR_TOP or
                        Intent.FLAG_ACTIVITY_SINGLE_TOP
            )
        }

        startActivity(intent)
    }

    private fun resetState() {
        inFreeFall = false
        impactDetected = false
        stillStartTime = 0
        fallHandled = false
        alertTriggered = false
    }

    private fun strongVibrate() {
        val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val manager = getSystemService(VIBRATOR_MANAGER_SERVICE) as VibratorManager
            manager.defaultVibrator
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
    }

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
    private fun resetAll() {
        Log.e("FALL", "FULL RESET")

        inFreeFall = false
        impactDetected = false
        stillStartTime = 0
        fallHandled = false
        alertTriggered = false

        // ⭐ 关键：给状态机一个“冷却期”
        lastResetTime = System.currentTimeMillis()
    }
}

