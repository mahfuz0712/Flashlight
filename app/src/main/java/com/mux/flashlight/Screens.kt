package com.mux.flashlight

import android.app.Activity
import android.app.NotificationManager
import android.app.StatusBarManager
import android.content.ComponentName
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.IntentFilter
import android.graphics.drawable.Icon
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.BatteryManager
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Slider
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

val SCREEN_COLORS = listOf(
    "WHITE" to Color(0xFFFFFFFF), "WARM" to Color(0xFFFFD9A0), "RED" to Color(0xFFFF0000),
    "GREEN" to Color(0xFF00FF66), "BLUE" to Color(0xFF3D7BFF), "AMBER" to Color(0xFFFFB300)
)
val SCREEN_EFFECTS = listOf("SOLID", "STROBE", "POLICE", "RAINBOW")

private fun modeLabel(m: String) = when (m) {
    "torch" -> "TORCH ON"; "strobe" -> "STROBE"; "sos" -> "SOS"; "morse" -> "MORSE"; "beacon" -> "BEACON"; else -> ""
}

private fun fmtLeft(ms: Long): String {
    val s = (ms.coerceAtLeast(0L) + 999) / 1000
    return "%d:%02d".format(s / 60, s % 60)
}

@Composable
private fun Section(t: String) = T("// $t", 12f, cSub, true, Modifier.padding(top = 18.dp, bottom = 8.dp))

@Composable
private fun Options(labels: List<String>, selected: Int, onSel: (Int) -> Unit) =
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        labels.forEachIndexed { i, l -> Chip(l, selected == i, Modifier.weight(1f)) { onSel(i) } }
    }

// ---------------------------------------------------------------- power button
@Composable
fun PowerButton(on: Boolean, level: Float, modifier: Modifier, onClick: () -> Unit) {
    val main = cMain
    val second = cSecond
    val tr = rememberInfiniteTransition(label = "pwr")
    val rot by tr.animateFloat(0f, 360f, infiniteRepeatable(tween(16000, easing = LinearEasing)), label = "rot")
    val pulse by tr.animateFloat(0.85f, 1f, infiniteRepeatable(tween(900, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "pulse")
    Canvas(modifier.clickable(onClick = onClick)) {
        val c = center
        val r = size.minDimension / 2f
        if (on) {
            drawCircle(
                Brush.radialGradient(listOf(second.copy(alpha = (0.2f + 0.6f * level) * pulse), Color.Transparent), center = c, radius = r),
                r, c
            )
        }
        drawCircle(main.copy(alpha = 0.55f), r * 0.93f, c, style = Stroke(2.dp.toPx()))
        rotate(rot, c) {
            drawCircle(
                main.copy(alpha = 0.7f), r * 0.78f, c,
                style = Stroke(3.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(18f, 16f)))
            )
        }
        val col = if (on) second else main
        drawCircle(if (on) second.copy(alpha = 0.18f) else Color(0xFF071A22), r * 0.62f, c)
        drawCircle(col, r * 0.62f, c, style = Stroke(2.dp.toPx()))
        val s = r * 0.26f
        drawArc(col, -55f, 290f, false, Offset(c.x - s, c.y - s + r * 0.04f), Size(s * 2, s * 2), style = Stroke(6.dp.toPx(), cap = StrokeCap.Round))
        drawLine(col, Offset(c.x, c.y - s - r * 0.1f), Offset(c.x, c.y - r * 0.02f), 6.dp.toPx(), StrokeCap.Round)
    }
}

// ---------------------------------------------------------------- torch
@Composable
fun TorchScreen() {
    val ctx = LocalContext.current
    var bat by remember { mutableIntStateOf(-1) }
    var temp by remember { mutableFloatStateOf(0f) }
    var lux by remember { mutableFloatStateOf(-1f) }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            now = System.currentTimeMillis()
            val bi = ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            if (bi != null) {
                val l = bi.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
                val sc = bi.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
                bat = if (l >= 0 && sc > 0) l * 100 / sc else -1
                temp = bi.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) / 10f
            }
            delay(1000)
        }
    }
    DisposableEffect(Unit) {
        val sm = ctx.getSystemService(SensorManager::class.java)
        val sensor = sm.getDefaultSensor(Sensor.TYPE_LIGHT)
        val l = object : SensorEventListener {
            override fun onSensorChanged(e: SensorEvent) { lux = e.values[0] }
            override fun onAccuracyChanged(s: Sensor?, a: Int) { }
        }
        if (sensor != null) sm.registerListener(l, sensor, SensorManager.SENSOR_DELAY_NORMAL)
        onDispose { sm.unregisterListener(l) }
    }

    fun toggle() {
        if (!Torch.supported) { Toast.makeText(ctx, "No flash on this phone. Try the SCREEN tab.", Toast.LENGTH_SHORT).show(); return }
        Haptics.tick(ctx)
        TorchService.send(ctx, TorchService.ACTION_TOGGLE)
    }
    fun setBrightness(v: Int) { Store.updateBrightness(v); Torch.reapply(ctx) }

    val sensors = "BATTERY ${if (bat >= 0) "$bat%" else "-"}  ${"%.1f".format(temp)}C" + if (lux >= 0) "  LIGHT ${lux.roundToInt()} LX" else ""
    val left = if (Torch.on && Torch.endAt > 0) "  OFF IN ${fmtLeft(Torch.endAt - now)}" else ""
    val title = when {
        !Torch.supported -> "NO FLASH FOUND"
        Torch.busy && !Torch.on -> "FLASH IN USE"
        Torch.on -> modeLabel(Torch.mode) + if (Torch.mode == "torch") "  ${Store.brightness}%" else ""
        else -> "TORCH OFF"
    }
    val info = if (!Torch.on && Torch.notice.isNotEmpty()) "SWITCHED OFF: ${Torch.notice}" else sensors + left
    val cap = when {
        !Torch.supported -> "THIS PHONE HAS NO FLASH. USE THE SCREEN TAB FOR A SCREEN LIGHT."
        Torch.maxLevel > 1 && Build.VERSION.SDK_INT >= 33 ->
            "HARDWARE BRIGHTNESS: ${Torch.maxLevel} LEVELS. NOW LEVEL ${Torch.levelFor(Store.brightness)}. 100% = LEVEL ${Torch.maxLevel}, THE BRIGHTEST."
        else -> "THIS FLASH HAS ONE BRIGHTNESS LEVEL (100%). BELOW 100% THE SOFT DIM SWITCHES THE LED VERY FAST."
    }

    Column(Modifier.fillMaxSize()) {
        HudCard(Modifier.weight(1f).fillMaxWidth(), tag = "TORCH", glow = true) {
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), horizontalAlignment = Alignment.CenterHorizontally) {
                PowerButton(Torch.on, Store.brightness / 100f, Modifier.size(190.dp)) { toggle() }
                T(if (Torch.on) "TAP TO TURN OFF" else "TAP TO TURN ON", 11f, cSub, mod = Modifier.padding(top = 4.dp))
                Row(Modifier.fillMaxWidth().padding(top = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                    T("BRIGHTNESS", 11f, cSub, true, Modifier.weight(1f))
                    T("${Store.brightness}%", 40f, if (Torch.on) cSecond else cMain, true)
                }
                Slider(
                    value = Store.brightness.toFloat(), onValueChange = { setBrightness(it.roundToInt()) },
                    valueRange = 1f..100f, colors = sliderColors()
                )
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    listOf(10, 25, 50, 75, 100).forEach { p ->
                        Chip(if (p == 100) "MAX" else "$p%", Store.brightness == p, Modifier.weight(1f)) { setBrightness(p) }
                    }
                }
                T(cap, 10f, cSub, mod = Modifier.padding(top = 12.dp), lines = 4)
                if (Torch.supported && !(Torch.maxLevel > 1 && Build.VERSION.SDK_INT >= 33)) {
                    T("SOFT DIM (PWM)", 10f, cSub, true, Modifier.padding(top = 12.dp, bottom = 6.dp))
                    Options(listOf("ON", "OFF"), if (Store.softDim) 0 else 1) { Store.updateSoftDim(it == 0); Torch.reapply(ctx) }
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        StatusCard(title, info)
        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            HudButton(if (Torch.on) "TURN OFF" else "TURN ON", true, Modifier.weight(1f), textSize = 15f) { toggle() }
            HudButton("MAX 100%", false, Modifier.weight(1f), textSize = 15f) {
                Store.updateBrightness(100)
                if (Torch.on && Torch.mode == "torch") Torch.reapply(ctx) else toggleOnly(ctx)
            }
        }
        Spacer(Modifier.height(8.dp))
    }
}

private fun toggleOnly(ctx: Context) {
    if (!Torch.supported) return
    Haptics.tick(ctx)
    TorchService.send(ctx, TorchService.ACTION_ON)
}

// ---------------------------------------------------------------- modes
@Composable
fun ModesScreen() {
    val ctx = LocalContext.current
    var sel by remember { mutableStateOf(if (Torch.mode in listOf("strobe", "sos", "morse", "beacon")) Torch.mode else "strobe") }
    val names = listOf("strobe", "sos", "morse", "beacon")
    val running = Torch.on && Torch.mode == sel
    Column(Modifier.fillMaxSize()) {
        HudCard(Modifier.weight(1f).fillMaxWidth(), tag = "SIGNAL MODES") {
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                Options(listOf("STROBE", "SOS", "MORSE", "BEACON"), names.indexOf(sel)) { sel = names[it] }
                when (sel) {
                    "strobe" -> {
                        Section("STROBE SPEED")
                        T("${Store.strobeHz} FLASHES PER SECOND", 18f, cSecond, true)
                        Slider(
                            value = Store.strobeHz.toFloat(), onValueChange = { Store.updateStrobeHz(it.roundToInt()) },
                            valueRange = 1f..20f, steps = 18, colors = sliderColors()
                        )
                        T("FAST ENOUGH TO GET NOTICED, USEFUL FOR EMERGENCIES, CYCLING AND SIGNALING.", 10f, cSub, lines = 3)
                    }
                    "sos" -> {
                        Section("SOS SPEED")
                        Options(listOf("SLOW", "NORMAL", "FAST"), Store.sosSpeed) { Store.updateSosSpeed(it) }
                        T("... --- ...", 28f, cSecond, true, Modifier.padding(top = 16.dp))
                        T("THE INTERNATIONAL DISTRESS SIGNAL, REPEATED UNTIL YOU STOP IT.", 10f, cSub, mod = Modifier.padding(top = 4.dp), lines = 2)
                    }
                    "morse" -> {
                        Section("MESSAGE")
                        Box(Modifier.fillMaxWidth().hudPanel(Color(0xFF06131A), cMain.copy(alpha = 0.4f), 8.dp).padding(10.dp)) {
                            if (Store.morseText.isEmpty()) T("TYPE A MESSAGE...", 13f, cSub)
                            BasicTextField(
                                Store.morseText, { Store.updateMorseText(it) }, singleLine = true,
                                textStyle = TextStyle(color = cWhite, fontFamily = Mono, fontSize = 15.sp),
                                cursorBrush = SolidColor(cMain), modifier = Modifier.fillMaxWidth()
                            )
                        }
                        T(morsePreview(Store.morseText).ifEmpty { "-" }, 13f, cSecond, mod = Modifier.padding(top = 8.dp), lines = 4)
                        Section("SPEED  ${Store.morseWpm} WPM")
                        Slider(
                            value = Store.morseWpm.toFloat(), onValueChange = { Store.updateMorseWpm(it.roundToInt()) },
                            valueRange = 5f..30f, steps = 24, colors = sliderColors()
                        )
                        Section("REPEAT")
                        Options(listOf("ON", "OFF"), if (Store.morseRepeat) 0 else 1) { Store.updateMorseRepeat(it == 0) }
                    }
                    else -> {
                        Section("BEACON INTERVAL")
                        T("ONE SHORT FLASH EVERY ${Store.beaconSec} S", 18f, cSecond, true)
                        Slider(
                            value = Store.beaconSec.toFloat(), onValueChange = { Store.updateBeaconSec(it.roundToInt()) },
                            valueRange = 1f..10f, steps = 8, colors = sliderColors()
                        )
                        T("A SLOW BLINK THAT SAVES BATTERY. GOOD FOR BEING SEEN AT NIGHT.", 10f, cSub, lines = 2)
                    }
                }
                T(
                    "WARNING: FLASHING LIGHTS CAN TRIGGER SEIZURES IN PEOPLE WITH PHOTOSENSITIVE EPILEPSY.",
                    10f, cRed, mod = Modifier.padding(top = 18.dp), lines = 3
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        StatusCard(
            title = if (running) "RUNNING  ${modeLabel(sel)}" else "READY  ${modeLabel(sel)}",
            info = "LIGHT USES THE TORCH BRIGHTNESS: ${Store.brightness}%"
        )
        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            HudButton(if (running) "RESTART" else "START ${modeLabel(sel)}", true, Modifier.weight(1f), textSize = 14f) {
                if (!Torch.supported) Toast.makeText(ctx, "No flash on this phone. Try the SCREEN tab.", Toast.LENGTH_SHORT).show()
                else { Haptics.tick(ctx); TorchService.send(ctx, TorchService.ACTION_MODE, sel) }
            }
            HudButton("ALL OFF", false, Modifier.weight(1f), textSize = 14f) { TorchService.send(ctx, TorchService.ACTION_OFF) }
        }
        Spacer(Modifier.height(8.dp))
    }
}

// ---------------------------------------------------------------- screen light
@Composable
fun ScreenScreen(onStart: () -> Unit) {
    Column(Modifier.fillMaxSize()) {
        HudCard(Modifier.weight(1f).fillMaxWidth(), tag = "SCREEN LIGHT") {
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                Section("COLOR")
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    SCREEN_COLORS.forEachIndexed { i, (_, col) ->
                        Box(
                            Modifier.weight(1f).height(48.dp).background(col)
                                .then(if (Store.screenColor == i) Modifier.border(3.dp, cSecond) else Modifier.border(1.dp, cDim))
                                .clickable { Store.updateScreenColor(i) }
                        )
                    }
                }
                T(SCREEN_COLORS[Store.screenColor].first, 11f, cSub, mod = Modifier.padding(top = 6.dp))
                Section("EFFECT")
                Options(SCREEN_EFFECTS, Store.screenEffect) { Store.updateScreenEffect(it) }
                Section("SCREEN BRIGHTNESS")
                Row(verticalAlignment = Alignment.CenterVertically) {
                    T("${Store.screenBright}%", 30f, cSecond, true, Modifier.weight(1f))
                    T("100% = BRIGHTEST", 10f, cSub)
                }
                Slider(
                    value = Store.screenBright.toFloat(), onValueChange = { Store.updateScreenBright(it.roundToInt()) },
                    valueRange = 1f..100f, colors = sliderColors()
                )
                T("NIGHT TIP: RED KEEPS YOUR EYES ADAPTED TO THE DARK. WARM IS GENTLE FOR READING.", 10f, cSub, mod = Modifier.padding(top = 10.dp), lines = 3)
                T("WARNING: STROBE AND POLICE EFFECTS FLASH FAST AND CAN TRIGGER SEIZURES.", 10f, cRed, mod = Modifier.padding(top = 10.dp), lines = 3)
            }
        }
        Spacer(Modifier.height(8.dp))
        StatusCard(
            title = "${SCREEN_COLORS[Store.screenColor].first}  ${SCREEN_EFFECTS[Store.screenEffect]}",
            info = "THE WHOLE SCREEN BECOMES THE LIGHT. TAP IT TO SHOW OR HIDE THE CONTROLS."
        )
        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            HudButton("START SCREEN", true, Modifier.weight(1f), textSize = 14f) { onStart() }
            HudButton("WHITE 100%", false, Modifier.weight(1f), textSize = 14f) {
                Store.updateScreenColor(0); Store.updateScreenEffect(0); Store.updateScreenBright(100); onStart()
            }
        }
        Spacer(Modifier.height(8.dp))
    }
}

/** Full-screen colored light. Window brightness follows the slider, 100% is the brightest the display can be. */
@Composable
fun ScreenLightOverlay(onClose: () -> Unit) {
    val ctx = LocalContext.current
    val act = remember { ctx.findActivity() }
    val view = LocalView.current
    var showCtl by remember { mutableStateOf(true) }
    var t by remember { mutableLongStateOf(0L) }
    LaunchedEffect(Unit) { while (true) { withFrameNanos { t = it / 1_000_000L } } }
    LaunchedEffect(Store.screenBright) {
        act?.window?.let { w -> val lp = w.attributes; lp.screenBrightness = Store.screenBright / 100f; w.attributes = lp }
    }
    DisposableEffect(Unit) {
        val w = act?.window
        if (w != null) {
            val c = WindowCompat.getInsetsController(w, view)
            c.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            c.hide(WindowInsetsCompat.Type.systemBars())
        }
        onDispose {
            act?.window?.let { win ->
                val lp = win.attributes
                lp.screenBrightness = -1f
                win.attributes = lp
                WindowCompat.getInsetsController(win, view).show(WindowInsetsCompat.Type.systemBars())
            }
        }
    }
    BackHandler(true) { onClose() }

    val base = SCREEN_COLORS[Store.screenColor].second
    val color = when (Store.screenEffect) {
        1 -> { val period = 1000L / Store.strobeHz.coerceIn(1, 20); if (t % period < period / 2) base else Color.Black }
        2 -> when ((t / 110L) % 8L) { 0L, 2L -> Color(0xFFFF0000); 4L, 6L -> Color(0xFF0033FF); else -> Color.Black }
        3 -> Color.hsv(((t / 15L) % 360L).toFloat(), 1f, 1f)
        else -> base
    }
    Box(Modifier.fillMaxSize().background(color).pointerInput(Unit) { detectTapGestures(onTap = { showCtl = !showCtl }) }) {
        if (showCtl) {
            Column(
                Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(16.dp)
                    .hudPanel(Color(0xE6050E14), cLine, 14.dp).padding(horizontal = 16.dp, vertical = 10.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    T("SCREEN BRIGHTNESS  ${Store.screenBright}%", 11f, cSecond, true, Modifier.weight(1f))
                    T("[CLOSE]", 12f, cMain, true, Modifier.clickable { onClose() }.padding(8.dp))
                }
                Slider(
                    value = Store.screenBright.toFloat(), onValueChange = { Store.updateScreenBright(it.roundToInt()) },
                    valueRange = 1f..100f, colors = sliderColors()
                )
            }
        }
    }
}

// ---------------------------------------------------------------- automation
@Composable
fun AutoScreen() {
    val ctx = LocalContext.current
    HudCard(Modifier.fillMaxSize(), tag = "AUTOMATION") {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            Section("AUTO-OFF TIMER")
            val ao = listOf(0, 5, 15, 30, 60)
            Options(listOf("OFF", "5 MIN", "15 MIN", "30 MIN", "1 H"), ao.indexOf(Store.autoOffMin).coerceAtLeast(0)) { Store.updateAutoOff(ao[it]) }
            T("TURNS THE LIGHT OFF BY ITSELF. STARTS WHEN YOU TURN THE LIGHT ON.", 10f, cSub, mod = Modifier.padding(top = 6.dp), lines = 2)

            Section("BATTERY GUARD")
            val bg = listOf(0, 5, 10, 15, 20)
            Options(listOf("OFF", "5%", "10%", "15%", "20%"), bg.indexOf(Store.batteryGuard).coerceAtLeast(0)) { Store.updateBatteryGuard(bg[it]) }
            T("SWITCHES OFF BELOW THIS BATTERY LEVEL (NOT WHILE CHARGING).", 10f, cSub, mod = Modifier.padding(top = 6.dp), lines = 2)

            Section("HEAT GUARD")
            val tg = listOf(0, 40, 45, 50)
            Options(listOf("OFF", "40 C", "45 C", "50 C"), tg.indexOf(Store.thermalGuard).coerceAtLeast(0)) { Store.updateThermalGuard(tg[it]) }
            T("THE LED GETS WARM AT HIGH BRIGHTNESS. THIS PROTECTS THE PHONE.", 10f, cSub, mod = Modifier.padding(top = 6.dp), lines = 2)

            Section("SHAKE TWICE TO TOGGLE")
            Options(listOf("ON", "OFF"), if (Store.shake) 0 else 1) {
                Store.updateShake(it == 0)
                TorchService.send(ctx, TorchService.ACTION_SYNC)
            }
            T("KEEPS A SMALL BACKGROUND SERVICE RUNNING. WORKS BEST WITH THE SCREEN ON, SOME PHONES ALSO WITH THE SCREEN OFF.", 10f, cSub, mod = Modifier.padding(top = 6.dp), lines = 3)
            Options(listOf("LOW", "MEDIUM", "HIGH"), Store.shakeSens) { Store.updateShakeSens(it) }

            Section("TURN ON WHEN THE APP OPENS")
            Options(listOf("ON", "OFF"), if (Store.autoOn) 0 else 1) { Store.updateAutoOn(it == 0) }

            Section("KEEP SCREEN AWAKE IN THE APP")
            Options(listOf("ON", "OFF"), if (Store.keepAwake) 0 else 1) { Store.updateKeepAwake(it == 0) }

            Section("VOLUME KEYS CHANGE BRIGHTNESS")
            Options(listOf("ON", "OFF"), if (Store.volumeKeys) 0 else 1) { Store.updateVolumeKeys(it == 0) }

            Section("VIBRATE ON TAP")
            Options(listOf("ON", "OFF"), if (Store.haptics) 0 else 1) { Store.updateHaptics(it == 0) }
            Spacer(Modifier.height(8.dp))
        }
    }
}

// ---------------------------------------------------------------- settings
@Composable
fun SettingsScreen(onNotifPermission: () -> Unit, onUpdateFound: (UpdateInfo) -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var status by remember { mutableStateOf("") }
    var note by remember { mutableStateOf("") }
    val notifOk = ctx.getSystemService(NotificationManager::class.java).areNotificationsEnabled()
    HudCard(Modifier.fillMaxSize(), tag = "SYSTEM") {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            Section("INTERFACE COLOR")
            Options(ACCENTS.map { it.name }, Store.accent) { Store.updateAccent(it); FlashWidget.updateAll(ctx) }

            Section("FLASH")
            T(
                if (!Torch.supported) "NO FLASH FOUND"
                else if (Torch.maxLevel > 1 && Build.VERSION.SDK_INT >= 33) "FLASH OK, ${Torch.maxLevel} HARDWARE BRIGHTNESS LEVELS"
                else "FLASH OK, ONE HARDWARE LEVEL (SOFT DIM FOR LOWER BRIGHTNESS)",
                11f, if (Torch.supported) cMain else cRed, lines = 3
            )

            Section("PERMISSIONS")
            T("NOTIFICATIONS: " + if (notifOk) "ALLOWED" else "BLOCKED", 11f, if (notifOk) cMain else cRed)
            T("THE FLASHLIGHT NEEDS NO CAMERA PERMISSION.", 10f, cSub, mod = Modifier.padding(top = 4.dp))
            if (!notifOk) Chip("ALLOW NOTIFICATIONS", true, Modifier.padding(top = 8.dp).fillMaxWidth()) { onNotifPermission() }

            Section("QUICK ACCESS")
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Chip("QUICK TILE", false, Modifier.weight(1f)) {
                    note = if (Build.VERSION.SDK_INT >= 33) {
                        try {
                            ctx.getSystemService(StatusBarManager::class.java).requestAddTileService(
                                ComponentName(ctx, TorchTile::class.java), "Flashlight",
                                Icon.createWithResource(ctx, R.drawable.ic_stat_flash), ctx.mainExecutor
                            ) { }
                            "CONFIRM IN THE POP-UP"
                        } catch (e: Exception) { "EDIT QUICK SETTINGS AND ADD THE FLASHLIGHT TILE" }
                    } else "EDIT QUICK SETTINGS AND ADD THE FLASHLIGHT TILE"
                }
                Chip("WIDGET", false, Modifier.weight(1f)) {
                    note = if (FlashWidget.pin(ctx)) "CONFIRM IN THE POP-UP" else "ADD IT FROM YOUR LAUNCHER'S WIDGET MENU"
                }
            }
            if (note.isNotEmpty()) T(note, 10f, cSub, mod = Modifier.padding(top = 6.dp), lines = 2)

            Section("UPDATES")
            Chip("CHECK FOR UPDATES", false, Modifier.fillMaxWidth()) {
                scope.launch {
                    status = "CHECKING..."
                    if (!isOnline(ctx)) { status = "NO INTERNET CONNECTION"; return@launch }
                    val (ok, info) = checkUpdate(ctx)
                    status = when {
                        !ok -> "CHECK FAILED"
                        info == null -> "UP TO DATE"
                        else -> "NEW VERSION AVAILABLE: v${info.version}"
                    }
                    if (info != null) onUpdateFound(info)
                }
            }
            if (status.isNotEmpty()) T(status, 12f, cSub, mod = Modifier.padding(top = 8.dp), lines = 2)

            Section("ABOUT")
            T("FLASHLIGHT v${appVersion(ctx)}", 13f, cMain, true)
            T("FLASHLIGHT CORE: MUX", 11f, cSub, mod = Modifier.padding(top = 4.dp))
            T("NOTHING IS UPLOADED. EVERYTHING STAYS ON THIS DEVICE.", 11f, cSub, mod = Modifier.padding(top = 2.dp, bottom = 10.dp), lines = 2)
            T("DEV: ${AppInfo.DEV_NAME}", 12f, cWhite)
            T("MAIL: ${AppInfo.DEV_EMAIL}", 12f, cMain, mod = Modifier.clickable { openMail(ctx, AppInfo.DEV_EMAIL) }.padding(vertical = 6.dp))
            T("GITHUB: github.com/${AppInfo.GITHUB_USER}", 12f, cMain, mod = Modifier.clickable { openUrl(ctx, "https://github.com/${AppInfo.GITHUB_USER}") }.padding(vertical = 6.dp), lines = 2)

            Section("MORE APPS")
            T("zApps Store >>", 13f, cMain, true, Modifier.clickable { openUrl(ctx, AppInfo.MORE_APPS) }.padding(vertical = 6.dp))
            T("zappsstore-frontend.onrender.com", 11f, cSub, lines = 2)
            Spacer(Modifier.height(8.dp))
        }
    }
}
