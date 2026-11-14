package com.cpsfloat

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.media.AudioManager
import android.net.ConnectivityManager
import android.net.Network
import android.os.BatteryManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.view.WindowManager
import android.widget.Toast
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * 桌宠服务。桌宠与 CPS 悬浮窗完全独立：这里只收集手机自身的状态（键盘、前台应用、通知、屏幕、电量、
 * 网络、声音、摇晃、安装应用），交给 PetBrain 决定播放哪个动画。
 */
class PetService : Service() {

    companion object {
        const val ACTION_SYNC = "com.cpsfloat.PET_SYNC"
        const val ACTION_STOP = "com.cpsfloat.PET_STOP"
        private const val CHANNEL = "pet_overlay"

        fun sync(c: Context) {
            val i = Intent(c, PetService::class.java).setAction(ACTION_SYNC)
            if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(i) else c.startService(i)
        }
    }

    private lateinit var wm: WindowManager
    private lateinit var ctl: PetController
    private val h = Handler(Looper.getMainLooper())
    private var cfg = PetCfg()
    private var lastRot = -1
    private var sensorsOn = false
    private var screenOn = true

    private val refreshRun = Runnable { refresh() }
    private val prefListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == null || key.startsWith("pet_")) {
            h.removeCallbacks(refreshRun)
            h.postDelayed(refreshRun, 30)
        }
    }

    // ---------------- 系统广播 ----------------
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, i: Intent?) {
            val now = SystemClock.uptimeMillis()
            when (i?.action) {
                Intent.ACTION_SCREEN_OFF -> {
                    screenOn = false
                    ctl.brain.screen(false, now)
                    updateSensors()
                }
                Intent.ACTION_SCREEN_ON -> {
                    screenOn = true
                    ctl.brain.screen(true, now)
                    updateSensors()
                }
                Intent.ACTION_USER_PRESENT -> ctl.brain.unlocked(now)
                Intent.ACTION_POWER_CONNECTED -> ctl.brain.charger(true, now)
                Intent.ACTION_BATTERY_CHANGED -> readBattery(i)
                Intent.ACTION_PACKAGE_ADDED, Intent.ACTION_PACKAGE_REPLACED -> ctl.brain.install(now)
            }
        }
    }

    private fun readBattery(i: Intent) {
        val level = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = i.getIntExtra(BatteryManager.EXTRA_SCALE, 100).coerceAtLeast(1)
        val plugged = i.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0
        val temp = i.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0)
        val pct = if (level < 0) 100 else level * 100 / scale
        ctl.brain.battery(pct, plugged, temp)
    }

    // ---------------- 网络 ----------------
    private var netCb: ConnectivityManager.NetworkCallback? = null

    private fun registerNet() {
        try {
            val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val cb = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    h.post { ctl.brain.net(true, SystemClock.uptimeMillis()) }
                }

                override fun onLost(network: Network) {
                    h.post { ctl.brain.net(false, SystemClock.uptimeMillis()) }
                }
            }
            cm.registerDefaultNetworkCallback(cb)
            netCb = cb
        } catch (_: Throwable) {
        }
    }

    private fun unregisterNet() {
        val cb = netCb ?: return
        try {
            (getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager).unregisterNetworkCallback(cb)
        } catch (_: Throwable) {
        }
        netCb = null
    }

    // ---------------- 重力传感器：摇晃 + 眼睛倾斜 ----------------
    private var gx = 0f
    private var gy = 0f
    private var shakeCount = 0
    private var shakeWindowStart = 0L
    private var lastShake = 0L

    private val sensorListener = object : SensorEventListener {
        override fun onSensorChanged(e: SensorEvent) {
            val ax = e.values[0]
            val ay = e.values[1]
            val az = e.values[2]
            gx = gx * 0.9f + ax * 0.1f
            gy = gy * 0.9f + ay * 0.1f
            ctl.setTilt((-gx / 9.8f).coerceIn(-1f, 1f), (1f - gy / 9.8f).coerceIn(-1f, 1f) * 0.6f)
            val mag = sqrt(ax * ax + ay * ay + az * az)
            if (abs(mag - 9.81f) > 13f) {
                val now = SystemClock.uptimeMillis()
                if (now - shakeWindowStart > 1500L) {
                    shakeWindowStart = now
                    shakeCount = 0
                }
                shakeCount++
                if (shakeCount >= 4 && now - lastShake > 6000L) {
                    lastShake = now
                    shakeCount = 0
                    ctl.brain.shake(now)
                }
            }
        }

        override fun onAccuracyChanged(s: Sensor?, accuracy: Int) {}
    }

    private fun updateSensors() {
        val want = screenOn && cfg.enabled && (cfg.follow || "shake" !in cfg.off)
        if (want == sensorsOn) return
        try {
            val sm = getSystemService(Context.SENSOR_SERVICE) as SensorManager
            if (want) {
                val s = sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
                if (s != null) sm.registerListener(sensorListener, s, SensorManager.SENSOR_DELAY_UI)
            } else {
                sm.unregisterListener(sensorListener)
                ctl.setTilt(0f, 0f)
            }
            sensorsOn = want
        } catch (_: Throwable) {
        }
    }

    // ---------------- 音乐 ----------------
    private val musicPoll = object : Runnable {
        override fun run() {
            try {
                if (screenOn) {
                    val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
                    ctl.brain.music(am.isMusicActive)
                }
            } catch (_: Throwable) {
            }
            h.postDelayed(this, 3000L)
        }
    }

    // ---------------- 屏幕方向 ----------------
    @Suppress("DEPRECATION")
    private fun rotation(): Int = wm.defaultDisplay.rotation

    private val rotPoll = object : Runnable {
        override fun run() {
            val r = rotation()
            if (r != lastRot) {
                lastRot = r
                ctl.onScreenChanged()
            }
            h.postDelayed(this, 500L)
        }
    }

    // ---------------- 无障碍环境感知 ----------------
    private val senseListener = object : Sense.Listener {
        override fun onKeyboard(shown: Boolean) = ctl.brain.keyboard(shown, SystemClock.uptimeMillis())
        override fun onForeground(pkg: String) = ctl.brain.foreground(pkg, SystemClock.uptimeMillis())
        override fun onNotification() = ctl.brain.notification(SystemClock.uptimeMillis())
        override fun onTyping() = ctl.brain.typing(SystemClock.uptimeMillis())
        override fun onActivity() = ctl.brain.activity(SystemClock.uptimeMillis())
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        ctl = PetController(this, wm)
        startFg()
        lastRot = rotation()
        Cfg.prefs(this).registerOnSharedPreferenceChangeListener(prefListener)

        val f = IntentFilter()
        f.addAction(Intent.ACTION_SCREEN_OFF)
        f.addAction(Intent.ACTION_SCREEN_ON)
        f.addAction(Intent.ACTION_USER_PRESENT)
        f.addAction(Intent.ACTION_POWER_CONNECTED)
        val sticky = registerReceiver(receiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        if (sticky != null) readBattery(sticky)
        registerReceiver(receiver, f)
        val pf = IntentFilter()
        pf.addAction(Intent.ACTION_PACKAGE_ADDED)
        pf.addAction(Intent.ACTION_PACKAGE_REPLACED)
        pf.addDataScheme("package")
        registerReceiver(receiver, pf)
        registerNet()
        Sense.listener = senseListener
        h.post(musicPoll)
        h.post(rotPoll)
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        val r = rotation()
        if (r != lastRot) {
            lastRot = r
            ctl.onScreenChanged()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> Cfg.putPetBool(this, "enabled", false)
        }
        refresh()
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        h.removeCallbacksAndMessages(null)
        Cfg.prefs(this).unregisterOnSharedPreferenceChangeListener(prefListener)
        try { unregisterReceiver(receiver) } catch (_: Throwable) { }
        unregisterNet()
        if (Sense.listener === senseListener) Sense.listener = null
        if (sensorsOn) {
            try {
                (getSystemService(Context.SENSOR_SERVICE) as SensorManager).unregisterListener(sensorListener)
            } catch (_: Throwable) {
            }
        }
        ctl.remove()
        super.onDestroy()
    }

    private fun refresh() {
        cfg = Cfg.loadPet(this)
        if (cfg.enabled && !Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "请先授予悬浮窗权限", Toast.LENGTH_SHORT).show()
            stopSelf()
            return
        }
        ctl.apply(cfg)
        updateSensors()
        if (!cfg.enabled) stopSelf()
    }

    private fun startFg() {
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "桌宠", NotificationManager.IMPORTANCE_LOW))
        }
        val stop = PendingIntent.getService(
            this, 2, Intent(this, PetService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val open = PendingIntent.getActivity(
            this, 3, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val n = Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle("桌宠运行中")
            .setContentIntent(open)
            .addAction(Notification.Action.Builder(null, "关闭", stop).build())
            .build()
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(2, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(2, n)
        }
    }
}
