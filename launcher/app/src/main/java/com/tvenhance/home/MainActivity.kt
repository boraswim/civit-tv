package com.tvenhance.home

import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.LauncherApps
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.UserHandle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    private lateinit var repo: AppRepository
    private var apps by mutableStateOf<List<AppEntry>>(emptyList())
    private var homeSignal by mutableIntStateOf(0)

    private val packageCallback = object : LauncherApps.Callback() {
        override fun onPackageAdded(packageName: String, user: UserHandle) = reload()
        override fun onPackageRemoved(packageName: String, user: UserHandle) = reload()
        override fun onPackageChanged(packageName: String, user: UserHandle) = reload()
        override fun onPackagesAvailable(packageNames: Array<out String>, user: UserHandle, replacing: Boolean) = reload()
        override fun onPackagesUnavailable(packageNames: Array<out String>, user: UserHandle, replacing: Boolean) = reload()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        repo = AppRepository(applicationContext)
        UiSounds.init(applicationContext)
        setContent {
            HomeScreen(
                apps = apps,
                homeSignal = homeSignal,
                repo = repo,
                onLaunch = ::launch,
                onAppInfo = ::openAppInfo,
                onReload = ::reload,
            )
        }
        getSystemService(LauncherApps::class.java).registerCallback(packageCallback, Handler(mainLooper))
    }

    override fun onDestroy() {
        getSystemService(LauncherApps::class.java).unregisterCallback(packageCallback)
        super.onDestroy()
    }

    // True once we've been fully hidden (another app in front) since we were last resumed.
    private var leftSinceResume = true

    override fun onResume() {
        super.onResume()
        leftSinceResume = false
        A11yGuard.ensure(applicationContext)
        reload()
    }

    override fun onStop() {
        super.onStop()
        leftSinceResume = true
        // Whatever opened over us (app tile, shelf item, now playing), let the pointer check whether it needs to appear.
        MouseService.appOpening()
    }

    // Pressing Home while already on the home screen jumps back to the top row. Android pauses
    // us before delivering that Home press, so "never stopped" (not "resumed") is the signal.
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (!leftSinceResume) homeSignal++
    }

    private fun reload() {
        lifecycleScope.launch {
            apps = withContext(Dispatchers.Default) { repo.loadApps() }
        }
    }

    private fun launch(app: AppEntry) {
        UiSounds.select()
        try {
            startActivity(Intent(app.launch).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED))
            repo.markLaunched(app.pkg)
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(this, getString(R.string.cant_open, app.label), Toast.LENGTH_SHORT).show()
        }
    }

    private fun openAppInfo(app: AppEntry) {
        try {
            startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", app.pkg, null))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(this, getString(R.string.info_unavailable), Toast.LENGTH_SHORT).show()
        }
    }
}
