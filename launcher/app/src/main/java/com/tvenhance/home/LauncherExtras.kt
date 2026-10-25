package com.tvenhance.home

import android.content.Context

/**
 * Hooks for app-specific extras kept outside this repository. A build compiles
 * `src/personal/` in when that folder exists (see app/build.gradle.kts); otherwise
 * `src/stock/` provides an [Extras] that adds nothing.
 */
interface LauncherExtras {
    /** Phone-only apps (no TV interface) that get the remote pointer while they're in front. */
    val pointerApps: Set<String> get() = emptySet()

    /** TV banners (drawable ids) for apps that ship without one. */
    val banners: Map<String, Int> get() = emptyMap()

    /**
     * A custom top shelf for [app], or null to use the standard one. [played] is what recently
     * played in that app, already turned into shelf cards.
     */
    fun shelf(context: Context, app: AppEntry, played: List<ShelfItem>, network: Boolean): Shelf? = null
}
