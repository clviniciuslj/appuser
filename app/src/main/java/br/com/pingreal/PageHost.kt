package br.com.pingreal

import android.content.SharedPreferences

/** O que as abas pedem à activity: ocupar o app (um teste por vez), rede atual, compartilhar etc. */
interface PageHost {
    /**
     * Marca o app como ocupado por um teste da aba (traceroute, diagnóstico, velocidade).
     * Devolve false se outra coisa já está rodando, inclusive um ping iniciado pelo widget.
     */
    fun acquire(label: String, color: Int): Boolean
    fun release()

    fun netInfo(): NetInfo
    fun prefs(): SharedPreferences
    fun post(block: () -> Unit)
    fun setKeepScreenOn(on: Boolean)

    /** Android 13+: pede (uma vez) a permissão da notificação do ping em segundo plano. */
    fun requestNotificationPermission()

    fun shareText(text: String)
    fun copyText(text: String)

    /** Gera e compartilha uma imagem com título, data, o gráfico (se houver) e o texto. */
    fun shareImage(title: String, body: String, chart: LatencyChartView?)
}
