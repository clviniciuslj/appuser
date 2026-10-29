package br.com.pingreal

import android.graphics.Color

/** Cores do app, em um só lugar (usadas pelas telas, pelo widget e pelo hub de ping). */
object Palette {
    val bg = Color.rgb(11, 11, 12)
    val panel = Color.rgb(18, 19, 21)
    val panelStroke = Color.rgb(36, 38, 41)
    val consoleBg = Color.rgb(8, 9, 10)
    val green = Color.rgb(111, 207, 151)
    val cyan = Color.rgb(108, 180, 238)
    val violet = Color.rgb(183, 156, 237)
    val orange = Color.rgb(232, 162, 99)
    val amber = Color.rgb(229, 180, 84)
    val red = Color.rgb(229, 115, 115)
    val white = Color.rgb(230, 232, 234)
    val text = Color.rgb(190, 194, 198)
    val muted = Color.rgb(138, 143, 148)
    val onGreen = Color.rgb(8, 20, 14)

    /** Cores das curvas quando há vários alvos. */
    val series = intArrayOf(green, cyan, violet, orange)
}
