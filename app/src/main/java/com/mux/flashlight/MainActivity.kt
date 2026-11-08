package com.mux.flashlight

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Color as AColor
import android.os.Build
import android.os.Bundle
import android.view.KeyEvent
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(AColor.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(AColor.TRANSPARENT)
        )
        Store.init(this)
        Torch.init(this)
        if (Store.autoOn && Torch.supported && !Torch.on) TorchService.send(this, TorchService.ACTION_ON)
        setContent { App() }
    }

    // volume keys change the brightness while the app is open
    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (Store.volumeKeys && (keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN)) {
            Store.updateBrightness(Store.brightness + if (keyCode == KeyEvent.KEYCODE_VOLUME_UP) 10 else -10)
            Torch.reapply(this)
            return true
        }
        return super.onKeyDown(keyCode, event)
    }
}

enum class Tab(val label: String, val sub: String) {
    TORCH("TORCH", "FLASH CONTROL"), MODES("MODES", "SIGNAL MODES"), SCREEN("SCREEN", "SCREEN LIGHT"),
    AUTO("AUTO", "AUTOMATION"), SETTINGS("SYS", "SYSTEM")
}

@Composable
fun App() {
    val ctx = LocalContext.current
    var tab by remember { mutableStateOf(Tab.TORCH) }
    var screenLight by remember { mutableStateOf(false) }
    var update by remember { mutableStateOf<UpdateInfo?>(null) }

    val notifLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    fun askNotif() {
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
    LaunchedEffect(Unit) { askNotif() }
    LaunchedEffect(Unit) { if (isOnline(ctx)) update = checkUpdate(ctx).second }

    // keep the screen awake while the app is open (setting)
    val act = remember { ctx.findActivity() }
    DisposableEffect(Store.keepAwake) {
        if (Store.keepAwake) act?.window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose { act?.window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
    }

    MaterialTheme(colorScheme = darkColorScheme()) {
        update?.let { u ->
            UpdateDialog(u, appVersion(ctx), onUpdate = { openUrl(ctx, u.apkUrl); update = null }, onCancel = { update = null })
        }
        if (screenLight) {
            ScreenLightOverlay(onClose = { screenLight = false })
        } else {
            HudBackground {
                Column(Modifier.fillMaxSize().systemBarsPadding()) {
                    HudHeader("FLASHLIGHT", tab.sub)
                    Box(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 14.dp)) {
                        when (tab) {
                            Tab.TORCH -> TorchScreen()
                            Tab.MODES -> ModesScreen()
                            Tab.SCREEN -> ScreenScreen(onStart = { screenLight = true })
                            Tab.AUTO -> AutoScreen()
                            Tab.SETTINGS -> SettingsScreen(onNotifPermission = { askNotif() }, onUpdateFound = { update = it })
                        }
                    }
                    Row(Modifier.fillMaxWidth().padding(14.dp, 8.dp, 14.dp, 12.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        for (t in Tab.entries) HudButton(t.label, tab == t, Modifier.weight(1f), 50.dp, 11f) { tab = t }
                    }
                }
            }
        }
    }
}
