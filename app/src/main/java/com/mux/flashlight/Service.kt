package com.mux.flashlight

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.widget.RemoteViews
import androidx.compose.ui.graphics.toArgb
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import kotlin.math.sqrt

object Haptics {
    fun tick(ctx: Context) {
        if (!Store.haptics) return
        try {
            ctx.getSystemService(Vibrator::class.java)?.vibrate(VibrationEffect.createOneShot(25, VibrationEffect.DEFAULT_AMPLITUDE))
        } catch (e: Exception) { }
    }
}

/** Keeps the light alive while the app is closed and the screen is off. */
class TorchService : Service() {
    companion object {
        const val ACTION_ON = "com.mux.flashlight.ON"
        const val ACTION_OFF = "com.mux.flashlight.OFF"
        const val ACTION_TOGGLE = "com.mux.flashlight.TOGGLE"
        const val ACTION_MODE = "com.mux.flashlight.MODE"
        const val ACTION_BRIGHT = "com.mux.flashlight.BRIGHT"
        const val ACTION_SYNC = "com.mux.flashlight.SYNC"
        const val CH = "mux_flash"
        private const val ID = 1
        @Volatile var running = false

        fun send(ctx: Context, action: String, mode: String? = null) {
            val app = ctx.applicationContext
            Store.init(app)
            Torch.init(app)
            if (!running && (action == ACTION_OFF || action == ACTION_BRIGHT || (action == ACTION_SYNC && !Store.shake))) {
                if (action != ACTION_SYNC) Torch.direct(app, action, mode)
                return
            }
            val i = Intent(app, TorchService::class.java).setAction(action)
            if (mode != null) i.putExtra("mode", mode)
            try {
                ContextCompat.startForegroundService(app, i)
            } catch (e: Exception) {
                Torch.direct(app, action, mode)
            }
        }
    }

    private var sm: SensorManager? = null
    private var shakeOn = false
    private var lastShake = 0L
    private var shakeCount = 0
    private var noticeShown = ""

    private val shakeListener = object : SensorEventListener {
        override fun onSensorChanged(e: SensorEvent) {
            val g = sqrt(e.values[0] * e.values[0] + e.values[1] * e.values[1] + e.values[2] * e.values[2]) / SensorManager.GRAVITY_EARTH
            val limit = floatArrayOf(3.0f, 2.5f, 2.0f)[Store.shakeSens.coerceIn(0, 2)]
            if (g > limit) {
                val now = SystemClock.elapsedRealtime()
                if (now - lastShake > 250) {
                    if (now - lastShake > 1000) shakeCount = 0
                    shakeCount++
                    lastShake = now
                    if (shakeCount >= 2) {
                        shakeCount = 0
                        Haptics.tick(this@TorchService)
                        if (Torch.on) Torch.stopAll(this@TorchService) else Torch.startSteady(this@TorchService)
                    }
                }
            }
        }
        override fun onAccuracyChanged(s: Sensor?, a: Int) { }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Store.init(this)
        Torch.init(this)
        running = true
        Torch.onChange = { refresh() }
        startFg()
        when (intent?.action) {
            ACTION_ON -> Torch.startSteady(this)
            ACTION_OFF -> Torch.stopAll(this)
            ACTION_TOGGLE -> if (Torch.on) Torch.stopAll(this) else Torch.startSteady(this)
            ACTION_MODE -> Torch.startMode(this, intent?.getStringExtra("mode") ?: "strobe")
            ACTION_BRIGHT -> Torch.reapply(this)
        }
        syncShake()
        refresh()
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        if (shakeOn) { sm?.unregisterListener(shakeListener); shakeOn = false }
        Torch.onChange = null
        running = false
        if (Torch.on) Torch.stopAll(this)
        super.onDestroy()
    }

    private fun syncShake() {
        if (Store.shake && !shakeOn) {
            sm = getSystemService(SensorManager::class.java)
            val s = sm?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
            if (s != null) { sm?.registerListener(shakeListener, s, SensorManager.SENSOR_DELAY_UI); shakeOn = true }
        } else if (!Store.shake && shakeOn) {
            sm?.unregisterListener(shakeListener)
            shakeOn = false
        }
    }

    private fun startFg() {
        val n = build()
        if (Build.VERSION.SDK_INT >= 34) startForeground(ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        else startForeground(ID, n)
    }

    private fun refresh() {
        if (!Torch.on && !Store.shake) {
            if (Torch.notice.isNotEmpty() && Torch.notice != noticeShown) { noticeShown = Torch.notice; notifyOff() }
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return
        }
        try { getSystemService(NotificationManager::class.java).notify(ID, build()) } catch (e: SecurityException) { }
    }

    private fun modeText(): String = when (Torch.mode) {
        "torch" -> "Steady light ${Store.brightness}%"
        "strobe" -> "Strobe ${Store.strobeHz} Hz"
        "sos" -> "SOS signal"
        "morse" -> "Morse: ${Store.morseText.take(30)}"
        "beacon" -> "Beacon every ${Store.beaconSec}s"
        else -> ""
    }

    private fun build(): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CH, "Flashlight", NotificationManager.IMPORTANCE_LOW))
        val flags = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        fun act(code: Int, a: String, m: String? = null): PendingIntent {
            val i = Intent(this, TorchService::class.java).setAction(a)
            if (m != null) i.putExtra("mode", m)
            return PendingIntent.getService(this, code, i, flags)
        }
        val open = PendingIntent.getActivity(this, 9, Intent(this, MainActivity::class.java), flags)
        val b = NotificationCompat.Builder(this, CH)
            .setSmallIcon(R.drawable.ic_stat_flash)
            .setContentTitle(if (Torch.on) "Flashlight ON" else "Flashlight ready")
            .setContentText(if (Torch.on) modeText() else "Shake twice to toggle")
            .setOngoing(true).setOnlyAlertOnce(true).setContentIntent(open)
            .setPriority(NotificationCompat.PRIORITY_LOW)
        if (Torch.on) b.addAction(0, "OFF", act(1, ACTION_OFF)) else b.addAction(0, "ON", act(2, ACTION_ON))
        b.addAction(0, "SOS", act(3, ACTION_MODE, "sos"))
        return b.build()
    }

    private fun notifyOff() {
        val open = PendingIntent.getActivity(this, 10, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val n = NotificationCompat.Builder(this, CH)
            .setSmallIcon(R.drawable.ic_stat_flash)
            .setContentTitle("Flashlight turned off")
            .setContentText(Torch.notice)
            .setAutoCancel(true).setContentIntent(open).build()
        try { getSystemService(NotificationManager::class.java).notify(ID + 1, n) } catch (e: SecurityException) { }
    }
}

/** Quick settings tile. */
class TorchTile : TileService() {
    override fun onStartListening() {
        Store.init(this)
        Torch.init(this)
        update()
    }

    override fun onClick() {
        TorchService.send(this, TorchService.ACTION_TOGGLE)
        update()
    }

    private fun update() {
        val t = qsTile ?: return
        t.state = if (Torch.on) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        t.label = "Flashlight"
        t.updateTile()
    }

    companion object {
        fun requestUpdate(ctx: Context) {
            try { TileService.requestListeningState(ctx, ComponentName(ctx, TorchTile::class.java)) } catch (e: Exception) { }
        }
    }
}

/** Buttons of the home screen widget. */
class ActionReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        Store.init(ctx)
        when (intent.action) {
            FlashWidget.W_TOGGLE -> TorchService.send(ctx, TorchService.ACTION_TOGGLE)
            FlashWidget.W_SOS ->
                if (Torch.on && Torch.mode == "sos") TorchService.send(ctx, TorchService.ACTION_OFF)
                else TorchService.send(ctx, TorchService.ACTION_MODE, "sos")
        }
    }
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        Store.init(ctx)
        if (Store.shake) TorchService.send(ctx, TorchService.ACTION_SYNC)
    }
}

class FlashWidget : AppWidgetProvider() {
    companion object {
        const val W_TOGGLE = "com.mux.flashlight.W_TOGGLE"
        const val W_SOS = "com.mux.flashlight.W_SOS"

        fun updateAll(ctx: Context) {
            val mgr = AppWidgetManager.getInstance(ctx)
            val ids = mgr.getAppWidgetIds(ComponentName(ctx, FlashWidget::class.java))
            if (ids.isNotEmpty()) FlashWidget().onUpdate(ctx, mgr, ids)
        }

        fun pin(ctx: Context): Boolean {
            val mgr = AppWidgetManager.getInstance(ctx)
            return if (mgr.isRequestPinAppWidgetSupported) mgr.requestPinAppWidget(ComponentName(ctx, FlashWidget::class.java), null, null) else false
        }

        private fun broadcast(ctx: Context, code: Int, action: String): PendingIntent =
            PendingIntent.getBroadcast(
                ctx, code, Intent(ctx, ActionReceiver::class.java).setAction(action),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
    }

    override fun onUpdate(ctx: Context, mgr: AppWidgetManager, ids: IntArray) {
        Store.init(ctx)
        val on = Torch.on
        val v = RemoteViews(ctx.packageName, R.layout.widget_flash)
        v.setTextViewText(R.id.w_power, if (on) "ON" else "OFF")
        v.setInt(R.id.w_power, "setBackgroundResource", if (on) R.drawable.widget_btn_on else R.drawable.widget_btn)
        v.setTextColor(R.id.w_power, if (on) 0xFF031014.toInt() else cMain.toArgb())
        v.setTextColor(R.id.w_title, cMain.toArgb())
        v.setOnClickPendingIntent(R.id.w_power, broadcast(ctx, 1, W_TOGGLE))
        v.setOnClickPendingIntent(R.id.w_sos, broadcast(ctx, 2, W_SOS))
        v.setOnClickPendingIntent(
            R.id.w_title,
            PendingIntent.getActivity(ctx, 3, Intent(ctx, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        )
        for (id in ids) mgr.updateAppWidget(id, v)
    }
}
