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
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs
import kotlin.math.sqrt

class FallDetectService : Service(), SensorEventListener {

    private val CHANNEL_ID_MONITOR = "fall_monitor_channel"
    private val CHANNEL_ID_ALERT = "fall_alert_channel"
    
    // 通知 ID 分离
    private val NOTIF_ID_MONITOR = 1001
    private val NOTIF_ID_ALERT = 1002

    private lateinit var rtcClient: RtcSignalClient
    
    // 使用 AtomicBoolean 确保线程安全，防止瞬间触发两次
    private val alertTriggered = AtomicBoolean(false)

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
            resetAll()
        }
    }

    override fun onCreate() {
        super.onCreate()
        Log.d("FALL", "FallDetectService Created")

        rtcClient = RtcSignalClient(this)
        rtcClient.connect()

        createNotificationChannels()

        // 1. 初始启动：使用监测通知（ID 1001），不带全屏意图
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIF_ID_MONITOR, 
                createMonitorNotification(), 
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } else {
            startForeground(NOTIF_ID_MONITOR, createMonitorNotification())
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
            val monitorChannel = NotificationChannel(CHANNEL_ID_MONITOR, "跌倒监测状态", NotificationManager.IMPORTANCE_LOW)
            val alertChannel = NotificationChannel(CHANNEL_ID_ALERT, "跌倒紧急报警", NotificationManager.IMPORTANCE_HIGH).apply {
                enableVibration(true)
                setBypassDnd(true)
            }
            nm.createNotificationChannel(monitorChannel)
            nm.createNotificationChannel(alertChannel)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onDestroy() {
        stopAlertSound()
        cancelRtcSending()
        try { unregisterReceiver(resetReceiver) } catch (e: Exception) {}
        sensorManager.unregisterListener(this)
        soundPool.release()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onSensorChanged(event: SensorEvent) {
        if (alertTriggered.get()) return
        if (System.currentTimeMillis() - lastResetTime < RESET_COOLDOWN_MS) return

        val magnitude = sqrt(event.values[0] * event.values[0] + event.values[1] * event.values[1] + event.values[2] * event.values[2])
        val now = System.currentTimeMillis()

        if (!inFreeFall && magnitude < FREE_FALL_THRESHOLD) {
            inFreeFall = true
            fallStartTime = now
            return
        }

        if (inFreeFall && !impactDetected && magnitude > IMPACT_THRESHOLD) {
            impactDetected = true
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
        // 原子操作检查，防止瞬间触发两次
        if (!alertTriggered.compareAndSet(false, true)) return

        Log.e("FALL", "!!! FALL ALERT TRIGGERED !!!")

        // 🚨 使用独立的通知 ID (1002) 发送报警，触发全屏 UI
        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(NOTIF_ID_ALERT, createAlertNotification())

        rtcRunnable = Runnable {
            Log.e("RTC", "10 seconds passed, starting RTC call...")
            stopAlertSound() 
            // 移除报警通知，因为它已经没用了
            nm.cancel(NOTIF_ID_ALERT)

            // 更新 Service 通知类型为 MICROPHONE，但不带全屏意图
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    NOTIF_ID_MONITOR, 
                    createMonitorNotification(), 
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE or ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
                )
            }

            rtcClient.sendFallAlert(userId = "CG-003")
            rtcClient.startWebRtcCall()

            val hangUpIntent = Intent(this, HangUpActivity::class.java)
            hangUpIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            startActivity(hangUpIntent)
        }
        mainHandler.postDelayed(rtcRunnable!!, 10000)

        strongVibrate()
        alertStreamId = soundPool.play(alertSoundId, 1f, 1f, 1, 0, 1f)
    }

    private fun resetAll() {
        Log.e("FALL", "Resetting all states...")
        rtcClient.endCall()
        
        // 取消报警通知 ID
        val nm = getSystemService(NotificationManager::class.java)
        nm.cancel(NOTIF_ID_ALERT)

        resetStateVariables()
        alertTriggered.set(false)
        lastResetTime = System.currentTimeMillis()
        
        // 恢复 Service 为普通监测状态
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIF_ID_MONITOR, createMonitorNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            nm.notify(NOTIF_ID_MONITOR, createMonitorNotification())
        }
    }

    private fun createMonitorNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID_MONITOR)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle("CareLink")
            .setContentText(if (alertTriggered.get()) "检测到疑似跌倒..." else "跌倒监测运行中")
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .build()
    }

    @SuppressLint("FullScreenIntentPolicy")
    private fun createAlertNotification(): Notification {
        val intent = Intent(this, CountdownActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 
            0, 
            intent, 
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID_ALERT)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle("🚨 紧急提醒")
            .setContentText("检测到跌倒，即将发出求救！")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setFullScreenIntent(pendingIntent, true)
            .setOngoing(true)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .build()
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

    private fun strongVibrate() {
        val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            (getSystemService(VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
        } else {
            @Suppress("DEPRECATION") getSystemService(VIBRATOR_SERVICE) as Vibrator
        }
        vibrator.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 400, 200, 400), intArrayOf(0, 255, 0, 255), -1))
    }
}
