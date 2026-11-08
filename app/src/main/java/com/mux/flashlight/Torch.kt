package com.mux.flashlight

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.os.BatteryManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private val MORSE = mapOf(
    'A' to ".-", 'B' to "-...", 'C' to "-.-.", 'D' to "-..", 'E' to ".", 'F' to "..-.", 'G' to "--.", 'H' to "....",
    'I' to "..", 'J' to ".---", 'K' to "-.-", 'L' to ".-..", 'M' to "--", 'N' to "-.", 'O' to "---", 'P' to ".--.",
    'Q' to "--.-", 'R' to ".-.", 'S' to "...", 'T' to "-", 'U' to "..-", 'V' to "...-", 'W' to ".--", 'X' to "-..-",
    'Y' to "-.--", 'Z' to "--..",
    '0' to "-----", '1' to ".----", '2' to "..---", '3' to "...--", '4' to "....-", '5' to ".....",
    '6' to "-....", '7' to "--...", '8' to "---..", '9' to "----.",
    '.' to ".-.-.-", ',' to "--..--", '?' to "..--..", '!' to "-.-.--", '/' to "-..-.", '-' to "-....-"
)

/** Human readable morse, e.g. "... --- ... / .-". */
fun morsePreview(text: String): String =
    text.uppercase().trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        .joinToString("  /  ") { w -> w.mapNotNull { MORSE[it] }.joinToString(" ") }

/** Pairs of (light on?, duration in ms) for the given text. */
fun morseSteps(text: String, unit: Long): List<Pair<Boolean, Long>> {
    val out = ArrayList<Pair<Boolean, Long>>()
    val words = text.uppercase().trim().split(Regex("\\s+")).map { w -> w.filter { MORSE.containsKey(it) } }.filter { it.isNotEmpty() }
    words.forEachIndexed { wi, w ->
        w.forEachIndexed { li, ch ->
            val code = MORSE[ch] ?: return@forEachIndexed
            code.forEachIndexed { si, s ->
                out += true to (if (s == '.') unit else unit * 3)
                if (si < code.length - 1) out += false to unit
            }
            if (li < w.length - 1) out += false to unit * 3
        }
        if (wi < words.size - 1) out += false to unit * 7
    }
    return out
}

/** The flashlight engine. Both the foreground service and the UI use it. */
object Torch {
    var supported by mutableStateOf(true)      // the phone has a flash
    var maxLevel by mutableIntStateOf(1)       // hardware brightness levels (1 = none)
    var busy by mutableStateOf(false)          // another app is using the camera
    var on by mutableStateOf(false)            // something is running
    var mode by mutableStateOf("")             // "", torch, strobe, sos, morse, beacon
    var lit by mutableStateOf(false)           // the flash is lit right now
    var endAt by mutableLongStateOf(0L)        // auto-off time (ms), 0 = none
    var notice by mutableStateOf("")           // why it was switched off automatically
    var onChange: (() -> Unit)? = null

    private var camId: String? = null
    private var inited = false
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var job: Job? = null
    private var monitor: Job? = null
    private var pwm: Job? = null
    private var wake: PowerManager.WakeLock? = null
    private const val PERIOD = 50L

    fun init(ctx: Context) {
        if (inited) return
        val app = ctx.applicationContext
        val cm = app.getSystemService(CameraManager::class.java)
        try {
            for (id in cm.cameraIdList) {
                val ch = cm.getCameraCharacteristics(id)
                val flash = ch.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
                val back = ch.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
                if (flash && (back || camId == null)) {
                    camId = id
                    if (Build.VERSION.SDK_INT >= 33) maxLevel = ch.get(CameraCharacteristics.FLASH_INFO_STRENGTH_MAXIMUM_LEVEL) ?: 1
                    if (back) break
                }
            }
        } catch (e: Exception) { }
        supported = camId != null
        try {
            cm.registerTorchCallback(object : CameraManager.TorchCallback() {
                override fun onTorchModeUnavailable(cameraId: String) {
                    if (cameraId == camId) { busy = true; if (on) stopAll(app, "FLASH IN USE BY ANOTHER APP") }
                }
                override fun onTorchModeChanged(cameraId: String, enabled: Boolean) {
                    if (cameraId == camId) busy = false
                }
            }, Handler(Looper.getMainLooper()))
        } catch (e: Exception) { }
        inited = true
    }

    /** Hardware level for a brightness percent. 100% is always the highest level the flash supports. */
    fun levelFor(pct: Int): Int =
        Math.ceil(pct.coerceIn(1, 100) / 100.0 * maxLevel).toInt().coerceIn(1, maxLevel)

    // ------------------------------------------------------------ low level
    private fun setLit(app: Context, v: Boolean) {
        val id = camId ?: return
        val cm = app.getSystemService(CameraManager::class.java)
        try {
            if (!v) {
                pwm?.cancel(); pwm = null
                cm.setTorchMode(id, false)
                lit = false
                return
            }
            if (Build.VERSION.SDK_INT >= 33 && maxLevel > 1) {
                cm.turnOnTorchWithStrengthLevel(id, levelFor(Store.brightness))
                lit = true
                return
            }
            if (Store.softDim && Store.brightness < 100) {
                if (pwm == null) pwm = scope.launch { pwmLoop(cm, id) }
                lit = true
                return
            }
            cm.setTorchMode(id, true)
            lit = true
        } catch (e: Exception) {
            // camera busy or not allowed right now
        }
    }

    /** Soft dimming for flashes with a single brightness level: switches the flash very fast. */
    private suspend fun pwmLoop(cm: CameraManager, id: String) {
        try {
            while (true) {
                val duty = Store.brightness.coerceIn(3, 97)
                val onMs = (PERIOD * duty / 100).coerceAtLeast(2L)
                cm.setTorchMode(id, true)
                delay(onMs)
                cm.setTorchMode(id, false)
                delay((PERIOD - onMs).coerceAtLeast(2L))
            }
        } finally {
            try { cm.setTorchMode(id, false) } catch (e: Exception) { }
        }
    }

    // ------------------------------------------------------------ start / stop
    fun startSteady(ctx: Context) {
        val app = ctx.applicationContext
        begin(app, "torch")
        setLit(app, true)
        publish(app)
    }

    fun startMode(ctx: Context, name: String) {
        val app = ctx.applicationContext
        begin(app, name)
        job = scope.launch { runMode(app, name) }
        publish(app)
    }

    /** Brightness changed while the steady light is on. */
    fun reapply(ctx: Context) {
        if (on && mode == "torch") setLit(ctx.applicationContext, true)
    }

    fun stopAll(ctx: Context, why: String = "") {
        val app = ctx.applicationContext
        job?.cancel(); job = null
        monitor?.cancel(); monitor = null
        setLit(app, false)
        on = false
        mode = ""
        endAt = 0L
        if (why.isNotEmpty()) notice = why
        try { wake?.let { if (it.isHeld) it.release() } } catch (e: Exception) { }
        wake = null
        publish(app)
    }

    /** Used when the foreground service could not be started. */
    fun direct(ctx: Context, action: String, modeName: String?) {
        val app = ctx.applicationContext
        init(app)
        when (action) {
            TorchService.ACTION_ON -> startSteady(app)
            TorchService.ACTION_OFF -> stopAll(app)
            TorchService.ACTION_TOGGLE -> if (on) stopAll(app) else startSteady(app)
            TorchService.ACTION_MODE -> startMode(app, modeName ?: "strobe")
            TorchService.ACTION_BRIGHT -> reapply(app)
        }
    }

    private fun begin(app: Context, m: String) {
        job?.cancel(); job = null
        setLit(app, false)
        mode = m
        on = true
        notice = ""
        endAt = if (Store.autoOffMin > 0) System.currentTimeMillis() + Store.autoOffMin * 60_000L else 0L
        try {
            if (wake?.isHeld != true) {
                wake = app.getSystemService(PowerManager::class.java)
                    .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "mux:flashlight").apply { acquire(6 * 60 * 60 * 1000L) }
            }
        } catch (e: Exception) { }
        monitor?.cancel()
        monitor = scope.launch { guardLoop(app) }
    }

    private fun publish(app: Context) {
        onChange?.invoke()
        FlashWidget.updateAll(app)
        TorchTile.requestUpdate(app)
    }

    // ------------------------------------------------------------ guards (timer, battery, heat)
    private suspend fun guardLoop(app: Context) {
        while (true) {
            delay(1000)
            if (endAt > 0 && System.currentTimeMillis() >= endAt) { stopAll(app, "AUTO-OFF TIMER"); return }
            val bi = app.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) ?: continue
            val lvl = bi.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val scale = bi.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
            val pct = if (lvl >= 0 && scale > 0) lvl * 100 / scale else 100
            val temp = bi.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) / 10f
            val plugged = bi.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0
            if (Store.batteryGuard > 0 && !plugged && pct <= Store.batteryGuard) { stopAll(app, "BATTERY LOW ($pct%)"); return }
            if (Store.thermalGuard > 0 && temp >= Store.thermalGuard) { stopAll(app, "PHONE TOO WARM (${"%.1f".format(temp)}C)"); return }
        }
    }

    // ------------------------------------------------------------ patterns
    private suspend fun runMode(app: Context, name: String) {
        when (name) {
            "strobe" -> {
                while (true) {
                    val period = 1000L / Store.strobeHz.coerceIn(1, 20)
                    val onMs = maxOf(20L, period * 4 / 10)
                    setLit(app, true)
                    delay(onMs)
                    setLit(app, false)
                    delay(maxOf(10L, period - onMs))
                }
            }
            "sos" -> playMorse(app, "SOS", longArrayOf(300L, 200L, 120L)[Store.sosSpeed.coerceIn(0, 2)], true)
            "morse" -> playMorse(app, Store.morseText, 1200L / Store.morseWpm.coerceIn(5, 30), Store.morseRepeat)
            "beacon" -> {
                while (true) {
                    setLit(app, true)
                    delay(150)
                    setLit(app, false)
                    delay(Store.beaconSec.coerceIn(1, 10) * 1000L)
                }
            }
        }
    }

    private suspend fun playMorse(app: Context, text: String, unit: Long, repeat: Boolean) {
        val steps = morseSteps(text, unit)
        if (steps.isEmpty()) { stopAll(app, "NOTHING TO SEND"); return }
        do {
            for ((flag, ms) in steps) {
                setLit(app, flag)
                delay(ms)
            }
            setLit(app, false)
            if (repeat) delay(unit * 14)
        } while (repeat)
        stopAll(app, "")
    }
}
