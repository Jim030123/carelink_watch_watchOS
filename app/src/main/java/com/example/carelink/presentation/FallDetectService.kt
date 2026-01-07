package com.example.carelink.presentation

import android.annotation.SuppressLint
import android.app.*
import android.content.*
import android.content.pm.ServiceInfo
import android.hardware.*
import android.media.AudioAttributes
import android.media.SoundPool
import android.os.*
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.example.carelink.R
import com.example.carelink.presentation.theme.HangUpActivity
import kotlin.math.abs
import kotlin.math.sqrt

class FallDetectService : Service(), SensorEventListener {

    // 渠道 ID 常量
    private val CHANNEL_ID_MONITOR = "fall_monitor_channel" // 平时监测
    private val CHANNEL_ID_ALERT = "fall_alert_channel"     // 紧急报警

    private lateinit var rtcClient: RtcSignalClient
    private var alertTriggered = false

    private lateinit var sensorManager: SensorManager
    private var accelerometer: Sensor? = null

    private val FREE_FALL_THRESHOLD = 5f
    private val IMPACT_THRESHOLD = 25f
    private val STILL_THRESHOLD = 11f
    private val STILL_TIME_MS = 1500L

    private var lastResetTime = 0L
    private val RESET_COOLDOWN_MS = 2000L

    private var inFreeFall = false
    private var impactDetected = false
    private var stillStartTime = 0L
    private var fallHandled = false
    private var fallStartTime = 0L 

    private lateinit var soundPool: SoundPool
    private var alertSoundId = 0
    private var alertStreamId = 0

    private val mainHandler = Handler(Looper.getMainLooper())
    private var rtcRunnable: Runnable? = null

    private val resetReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            Log.e("FALL", ">>> RESET SIGNAL RECEIVED <<<")
            stopAlertSound()
            cancelRtcSending()
            
            // ⭐ 核心修复：通知 RTC 客户端执行挂断动作并发送 end_call 信令
            try {
                rtcClient.hangup()
            } catch (e: Exception) {
                Log.e("FALL", "RTC hangup call failed: ${e.message}")
            }
            
            resetAll()
        }
    }

    override fun onCreate() {
        super.onCreate()
        Log.d("FALL", "FallDetectService Created")

        rtcClient = RtcSignalClient(this)
        rtcClient.connect()

        // 1. 预先创建两个渠道
        createNotificationChannels()

        // 2. 初始使用监测渠道启动前台服务
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                1001, 
                createNotification(), 
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE or ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } else {
            startForeground(1001, createNotification())
        }

        val filter = IntentFilter("ACTION_FALL_ALERT_RESET")
        ContextCompat.registerReceiver(
            this,
            resetReceiver,
            filter,
            ContextCompat.RECEIVER_NOT_EXPORTED
        )

        val audioAttributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ALARM)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()
        soundPool = SoundPool.Builder().setMaxStreams(1).setAudioAttributes(audioAttributes).build()
        alertSoundId = soundPool.load(this, R.raw.fall_alert, 1)

        sensorManager = getSystemService(SENSOR_SERVICE) as SensorManager
        accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        accelerometer?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME)
        }
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NotificationManager::class.java)
            
            // 监测渠道：静默，低优先级
            val monitorChannel = NotificationChannel(
                CHANNEL_ID_MONITOR, 
                "跌倒监测状态", 
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "显示跌倒监测是否正在运行"
                setShowBadge(false)
            }
            
            // 报警渠道：高优先级，必须弹出
            val alertChannel = NotificationChannel(
                CHANNEL_ID_ALERT, 
                "跌倒紧急报警", 
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "检测到跌倒时的紧急提醒"
                enableVibration(true)
                setBypassDnd(true)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            }
            
            nm.createNotificationChannel(monitorChannel)
            nm.createNotificationChannel(alertChannel)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onDestroy() {
        stopAlertSound()
        cancelRtcSending()
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

        val magnitude = sqrt(event.values[0] * event.values[0] + event.values[1] * event.values[1] + event.values[2] * event.values[2])
        val now = System.currentTimeMillis()

        if (!inFreeFall && magnitude < FREE_FALL_THRESHOLD) {
            inFreeFall = true
            fallStartTime = now
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

        // 🚨 切换到高优先级通知，触发全屏 UI
        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(1001, createNotification())

        rtcRunnable = Runnable {
            Log.e("RTC", "Starting WebRTC...")
            stopAlertSound() 
            rtcClient.sendFallAlert(userId = "CG-003")
            rtcClient.startWebRtcCall()

            val hangUpIntent = Intent(this, HangUpActivity::class.java)
            hangUpIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            startActivity(hangUpIntent)
        }
        mainHandler.postDelayed(rtcRunnable!!, 10000)

        strongVibrate()
        alertStreamId = soundPool.play(alertSoundId, 1f, 1f, 1, 0, 1f)

        // 双重保险：显式启动 Activity
        val intent = Intent(this, CountdownActivity::class.java)
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        startActivity(intent)
    }

    private fun stopAlertSound() {
        if (alertStreamId != 0) {
            soundPool.stop(alertStreamId)
            alertStreamId = 0
        }
    }

    private fun cancelRtcSending() {
        rtcRunnable?.let { mainHandler.removeCallbacks(it) }
        rtcRunnable = null
    }

    private fun resetStateVariables() {
        inFreeFall = false
        impactDetected = false
        stillStartTime = 0
        fallHandled = false
    }

    private fun resetAll() {
        Log.e("FALL", "Resetting all states...")
        resetStateVariables()
        alertTriggered = false
        lastResetTime = System.currentTimeMillis()
        
        // ✅ 重置回低优先级监测渠道
        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(1001, createNotification())
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
        // 根据状态选择渠道
        val currentChannelId = if (alertTriggered) CHANNEL_ID_ALERT else CHANNEL_ID_MONITOR
        
        val builder = NotificationCompat.Builder(this, currentChannelId)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle("CareLink 跌倒提醒")
            .setContentText(if (alertTriggered) "检测到跌倒！" else "跌倒监测运行中")
            .setOngoing(true)
            .setOnlyAlertOnce(!alertTriggered) // 监测状态不响铃

        if (alertTriggered) {
            val intent = Intent(this, CountdownActivity::class.java)
            val pendingIntent = PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            builder.setPriority(NotificationCompat.PRIORITY_HIGH)
                .setCategory(NotificationCompat.CATEGORY_ALARM)
                .setFullScreenIntent(pendingIntent, true)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
        } else {
            builder.setPriority(NotificationCompat.PRIORITY_LOW)
        }

        return builder.build()
    }
}
