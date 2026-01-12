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

    private var wakeLock: PowerManager.WakeLock? = null

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

        createNotificationChannels()

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
        ContextCompat.registerReceiver(this, resetReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)

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
            
            val monitorChannel = NotificationChannel(CHANNEL_ID_MONITOR, "跌倒监测状态", NotificationManager.IMPORTANCE_LOW).apply {
                description = "显示跌倒监测是否正在运行"
                setShowBadge(false)
            }
            
            val alertChannel = NotificationChannel(CHANNEL_ID_ALERT, "跌倒紧急报警", NotificationManager.IMPORTANCE_HIGH).apply {
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
        releaseWakeLock()
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

    @SuppressLint("WakelockTimeout")
    private fun triggerFallAlert() {
        if (alertTriggered) return
        alertTriggered = true

        Log.e("FALL", "!!! FALL ALERT TRIGGERED - ATTEMPTING TO SHOW UI FIRST !!!")

        // 黄金法则：第一步，且只做这一步：唤醒屏幕并拉起 UI
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP, "CareLink::FallAlertWakeLock").apply { acquire() }
        
        val intent = Intent(this, CountdownActivity::class.java)
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        startActivity(intent)
        
        // 发送高优先级通知作为备用方案
        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(1001, createNotification())

        // 第二步：将所有其他操作延迟执行，避免干扰 UI 线程
        mainHandler.post {
            Log.d("FALL", "Executing secondary actions (sound, vibrate, etc.)")
            strongVibrate()
            alertStreamId = soundPool.play(alertSoundId, 1f, 1f, 1, 0, 1f)

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
        }
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
        releaseWakeLock()
        resetStateVariables()
        alertTriggered = false
        lastResetTime = System.currentTimeMillis()
        
        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(1001, createNotification())
    }

    private fun releaseWakeLock() {
        if (wakeLock?.isHeld == true) {
            wakeLock?.release()
            wakeLock = null
            Log.d("FALL", "WakeLock released.")
        }
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
        val currentChannelId = if (alertTriggered) CHANNEL_ID_ALERT else CHANNEL_ID_MONITOR
        
        val builder = NotificationCompat.Builder(this, currentChannelId)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle("CareLink 跌倒提醒")
            .setContentText(if (alertTriggered) "检测到跌倒！" else "跌倒监测运行中")
            .setOngoing(true)
            .setOnlyAlertOnce(!alertTriggered)

        if (alertTriggered) {
            val intent = Intent(this, CountdownActivity::class.java)
            // ⭐ 简化 PendingIntent，只保留最核心的 Flag
            val pendingIntent = PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE)
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
