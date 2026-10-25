package com.tvenhance.home

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.widget.Toast

const val APPLE_MUSIC = "com.apple.android.music"

/**
 * TV-launcher entry for Apple Music. The app itself is the unmodified phone build, which only
 * declares a phone launcher entry, so the stock Google TV launcher can't list it. This gives it
 * a banner there; it just opens the real app.
 */
class AppleMusicActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        A11yGuard.ensure(applicationContext) // make sure the pointer will be there
        val launch = packageManager.getLaunchIntentForPackage(APPLE_MUSIC)
        if (launch != null) {
            startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            MouseService.appOpening()
        } else {
            Toast.makeText(this, getString(R.string.cant_open, "Apple Music"), Toast.LENGTH_SHORT).show()
        }
        finish()
    }
}
