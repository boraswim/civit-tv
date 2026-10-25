package com.tvenhance.home

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.lifecycleScope
import dadb.AdbKeyPair
import dadb.Dadb
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File

const val STOCK_LAUNCHER = "com.google.android.tvlauncher"

/**
 * Flips the home screen between ÇivitTV and the stock Google TV launcher.
 *
 * Apps can't enable or disable other apps, so this talks to the box's own adbd over
 * 127.0.0.1:5555 (network debugging must stay on). The first time, the TV asks to
 * authorise this app's key, just like it did for the PC.
 */
class SwitchActivity : ComponentActivity() {
    private var hint by mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val toSevim = isStockEnabled(this)
        val title = getString(if (toSevim) R.string.switching_to_sevim else R.string.switching_to_stock)
        setContent { SwitchScreen(title, hint) }

        lifecycleScope.launch {
            val approveHint = launch {
                delay(2500)
                hint = getString(R.string.approve_hint)
            }
            val command = switchCommand(toSevim, packageName)
            // The adb socket read can't be cancelled while it waits for the user to approve the
            // key, so run it detached and only await it with a timeout.
            val shell = CoroutineScope(Dispatchers.IO).async {
                runCatching { localShell(applicationContext, command) }.getOrDefault(false)
            }
            val ok = withTimeoutOrNull(120_000) { shell.await() } ?: false
            approveHint.cancel()
            if (ok) {
                startActivity(homeIntent())
            } else {
                hint = getString(R.string.switch_failed)
                delay(6000)
            }
            finish()
        }
    }

}

fun isStockEnabled(context: Context): Boolean = try {
    val pm = context.packageManager
    pm.getApplicationEnabledSetting(STOCK_LAUNCHER) != PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER &&
        pm.getApplicationInfo(STOCK_LAUNCHER, 0).enabled
} catch (e: Exception) {
    false
}

fun switchCommand(toSevim: Boolean, selfPackage: String) = if (toSevim) {
    "pm disable-user --user 0 $STOCK_LAUNCHER && cmd package set-home-activity $selfPackage/.MainActivity"
} else {
    "pm enable $STOCK_LAUNCHER && cmd package set-home-activity $STOCK_LAUNCHER/.MainActivity"
}

fun homeIntent(): Intent =
    Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

fun localShell(context: Context, command: String): Boolean {
    val priv = File(context.filesDir, "adbkey")
    val pub = File(context.filesDir, "adbkey.pub")
    if (!priv.exists() || !pub.exists()) AdbKeyPair.generate(priv, pub)
    Dadb.create("127.0.0.1", 5555, AdbKeyPair.read(priv, pub)).use { adb ->
        return adb.shell(command).exitCode == 0
    }
}

@androidx.compose.runtime.Composable
private fun SwitchScreen(title: String, hint: String?) {
    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.75f)), contentAlignment = Alignment.Center) {
        Column(
            Modifier
                .width(520.dp)
                .clip(RoundedCornerShape(24.dp))
                .background(Color(0xF2242429))
                .padding(horizontal = 32.dp, vertical = 28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            BasicText(
                title,
                style = TextStyle(color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center),
            )
            if (hint != null) {
                BasicText(
                    hint,
                    style = TextStyle(color = Color.White.copy(alpha = 0.7f), fontSize = 17.sp, textAlign = TextAlign.Center),
                )
            }
        }
    }
}
