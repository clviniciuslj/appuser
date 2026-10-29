package br.com.pingreal

import android.content.Context
import android.content.SharedPreferences
import java.util.Locale

fun formatMs(ms: Double): String =
    if (ms < 10) String.format(Locale.US, "%.1f", ms) else ms.toInt().toString()

fun formatMbps(v: Double): String =
    if (v >= 100) v.toInt().toString() else String.format(Locale.US, "%.1f", v)

fun formatDuration(ms: Long): String {
    val seconds = (ms + 500) / 1000
    return if (seconds < 60) "$seconds s" else String.format(Locale.US, "%d min %02d s", seconds / 60, seconds % 60)
}

/** Chaves das preferências salvas no aparelho. */
object Prefs {
    const val NAME = "pingreal"
    const val HOST = "host"
    const val KEEP_SCREEN = "keep_screen"
    const val VIBRATE = "vibrate_outage"
    const val WIDGET_HOST = "widget_host"
    const val FAVORITES = "favorites"
    const val HISTORY = "history"

    fun of(context: Context): SharedPreferences = context.getSharedPreferences(NAME, Context.MODE_PRIVATE)
}
