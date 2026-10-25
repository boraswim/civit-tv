package com.tvenhance.home

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Classic mode is temporary: if the TV boots with the stock launcher switched on, switch back
 * to ÇivitTV so a restart always lands on ÇivitTV.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        A11yGuard.ensure(context)
        if (!isStockEnabled(context)) return
        val pending = goAsync()
        Thread {
            try {
                // adbd's network listener can come up a few seconds after boot completes.
                for (attempt in 1..10) {
                    if (runCatching { localShell(context, switchCommand(true, context.packageName)) }.getOrDefault(false)) {
                        context.startActivity(homeIntent())
                        break
                    }
                    Thread.sleep(2000)
                }
            } finally {
                pending.finish()
            }
        }.start()
    }
}
