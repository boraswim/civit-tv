package com.tvenhance.home

import android.content.ComponentName
import android.content.ContentResolver
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.provider.Settings

/**
 * Keeps the launcher's mouse mode (an accessibility service) switched on. Android can silently
 * drop it from the enabled list (e.g. after tools that take over accessibility), which leaves the
 * remote dead inside phone-only apps and stops the ezan notices. WRITE_SECURE_SETTINGS is granted once over adb by
 * install-launcher.ps1, so the launcher can restore it itself.
 */
object A11yGuard {
    private val main = Handler(Looper.getMainLooper())
    private var rebindPending = false

    fun ensure(context: Context) {
        val me = ComponentName(context, MouseService::class.java).flattenToString()
        val resolver = context.contentResolver
        val current = Settings.Secure.getString(resolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES).orEmpty()
        val enabled = Settings.Secure.getInt(resolver, Settings.Secure.ACCESSIBILITY_ENABLED, 0) == 1
        val others = current.split(':').filter { it.isNotBlank() && !it.equals(me, ignoreCase = true) }
        if (!enabled || others.size == current.split(':').count { it.isNotBlank() }) {
            write(resolver, others + me)
            return
        }
        // Listed as on, yet not running: Android leaves a service that crashed or was updated
        // switched "on" but unbound until the setting changes. Give it a moment to bind, then
        // switch it off and on again.
        if (MouseService.instance != null || rebindPending) return
        rebindPending = true
        main.postDelayed({
            if (MouseService.instance == null) {
                write(resolver, others)
                main.postDelayed({ write(resolver, others + me); rebindPending = false }, 500)
            } else {
                rebindPending = false
            }
        }, 3000)
    }

    private fun write(resolver: ContentResolver, list: List<String>) = runCatching {
        Settings.Secure.putString(resolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, list.joinToString(":"))
        Settings.Secure.putInt(resolver, Settings.Secure.ACCESSIBILITY_ENABLED, 1)
    }
}
