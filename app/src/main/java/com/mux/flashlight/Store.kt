package com.mux.flashlight

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** All settings. They are remembered between launches. */
object Store {
    private lateinit var sp: SharedPreferences
    private var ready = false

    var accent by mutableIntStateOf(0)
    var brightness by mutableIntStateOf(100)       // percent, 100 = brightest the flash can do
    var softDim by mutableStateOf(true)            // PWM dimming on flashes without hardware brightness levels
    var strobeHz by mutableIntStateOf(8)
    var sosSpeed by mutableIntStateOf(1)           // 0 slow, 1 normal, 2 fast
    var morseText by mutableStateOf("SOS")
    var morseWpm by mutableIntStateOf(12)
    var morseRepeat by mutableStateOf(true)
    var beaconSec by mutableIntStateOf(3)
    var autoOffMin by mutableIntStateOf(0)         // 0 = never
    var batteryGuard by mutableIntStateOf(10)      // 0 = off, else % level
    var thermalGuard by mutableIntStateOf(45)      // 0 = off, else degrees C
    var shake by mutableStateOf(false)
    var shakeSens by mutableIntStateOf(1)          // 0 low, 1 medium, 2 high
    var autoOn by mutableStateOf(false)
    var keepAwake by mutableStateOf(true)
    var volumeKeys by mutableStateOf(true)
    var haptics by mutableStateOf(true)
    var screenColor by mutableIntStateOf(0)
    var screenEffect by mutableIntStateOf(0)
    var screenBright by mutableIntStateOf(100)

    fun init(ctx: Context) {
        if (ready) return
        sp = ctx.applicationContext.getSharedPreferences("mux_flashlight", Context.MODE_PRIVATE)
        accent = sp.getInt("accent", 0)
        brightness = sp.getInt("bright", 100)
        softDim = sp.getBoolean("softDim", true)
        strobeHz = sp.getInt("strobeHz", 8)
        sosSpeed = sp.getInt("sosSpeed", 1)
        morseText = sp.getString("morseText", "SOS") ?: "SOS"
        morseWpm = sp.getInt("morseWpm", 12)
        morseRepeat = sp.getBoolean("morseRepeat", true)
        beaconSec = sp.getInt("beaconSec", 3)
        autoOffMin = sp.getInt("autoOff", 0)
        batteryGuard = sp.getInt("batGuard", 10)
        thermalGuard = sp.getInt("thermGuard", 45)
        shake = sp.getBoolean("shake", false)
        shakeSens = sp.getInt("shakeSens", 1)
        autoOn = sp.getBoolean("autoOn", false)
        keepAwake = sp.getBoolean("keepAwake", true)
        volumeKeys = sp.getBoolean("volKeys", true)
        haptics = sp.getBoolean("haptics", true)
        screenColor = sp.getInt("scrColor", 0)
        screenEffect = sp.getInt("scrEffect", 0)
        screenBright = sp.getInt("scrBright", 100)
        ready = true
    }

    private fun i(k: String, v: Int) { sp.edit().putInt(k, v).apply() }
    private fun b(k: String, v: Boolean) { sp.edit().putBoolean(k, v).apply() }

    fun updateAccent(v: Int) { accent = v; i("accent", v) }
    fun updateBrightness(v: Int) { brightness = v.coerceIn(1, 100); i("bright", brightness) }
    fun updateSoftDim(v: Boolean) { softDim = v; b("softDim", v) }
    fun updateStrobeHz(v: Int) { strobeHz = v.coerceIn(1, 20); i("strobeHz", strobeHz) }
    fun updateSosSpeed(v: Int) { sosSpeed = v; i("sosSpeed", v) }
    fun updateMorseText(v: String) { morseText = v.take(120); sp.edit().putString("morseText", morseText).apply() }
    fun updateMorseWpm(v: Int) { morseWpm = v.coerceIn(5, 30); i("morseWpm", morseWpm) }
    fun updateMorseRepeat(v: Boolean) { morseRepeat = v; b("morseRepeat", v) }
    fun updateBeaconSec(v: Int) { beaconSec = v.coerceIn(1, 10); i("beaconSec", beaconSec) }
    fun updateAutoOff(v: Int) { autoOffMin = v; i("autoOff", v) }
    fun updateBatteryGuard(v: Int) { batteryGuard = v; i("batGuard", v) }
    fun updateThermalGuard(v: Int) { thermalGuard = v; i("thermGuard", v) }
    fun updateShake(v: Boolean) { shake = v; b("shake", v) }
    fun updateShakeSens(v: Int) { shakeSens = v; i("shakeSens", v) }
    fun updateAutoOn(v: Boolean) { autoOn = v; b("autoOn", v) }
    fun updateKeepAwake(v: Boolean) { keepAwake = v; b("keepAwake", v) }
    fun updateVolumeKeys(v: Boolean) { volumeKeys = v; b("volKeys", v) }
    fun updateHaptics(v: Boolean) { haptics = v; b("haptics", v) }
    fun updateScreenColor(v: Int) { screenColor = v; i("scrColor", v) }
    fun updateScreenEffect(v: Int) { screenEffect = v; i("scrEffect", v) }
    fun updateScreenBright(v: Int) { screenBright = v.coerceIn(1, 100); i("scrBright", screenBright) }
}
