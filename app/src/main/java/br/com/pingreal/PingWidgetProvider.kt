package br.com.pingreal

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews

/** Widget em pílula: IP de destino, botão executar/parar e o resultado mais recente da sessão compartilhada. */
class PingWidgetProvider : AppWidgetProvider() {

    companion object {
        /** Toque sem efeito (o campo de IP, enquanto há um ping rodando), para não cair no clique da pílula. */
        const val ACTION_NOOP = "br.com.pingreal.action.NOOP"

        private const val FLAGS = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT

        /** Redesenha todos os widgets com o estado atual do [PingHub]. */
        fun refresh(context: Context) {
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(ComponentName(context, PingWidgetProvider::class.java))
            if (ids.isEmpty()) return
            manager.updateAppWidget(ids, build(context))
        }

        private fun openApp(context: Context): PendingIntent =
            PendingIntent.getActivity(
                context, 10,
                Intent.makeMainActivity(ComponentName(context, MainActivity::class.java))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED),
                FLAGS
            )

        private fun editIp(context: Context): PendingIntent =
            PendingIntent.getActivity(
                context, 11,
                Intent(context, WidgetIpActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                FLAGS
            )

        private fun service(context: Context, action: String, code: Int): PendingIntent =
            PendingIntent.getForegroundService(
                context, code,
                Intent(context, PingService::class.java).setAction(action),
                FLAGS
            )

        private fun noop(context: Context): PendingIntent =
            PendingIntent.getBroadcast(
                context, 12,
                Intent(context, PingWidgetProvider::class.java).setAction(ACTION_NOOP),
                FLAGS
            )

        private fun build(context: Context): RemoteViews {
            val views = RemoteViews(context.packageName, R.layout.widget_ping)
            val running = PingHub.running
            val savedHost = Prefs.of(context).getString(Prefs.WIDGET_HOST, "").orEmpty()

            // Campo de IP
            val hostText = if (running) PingHub.hostLabel else savedHost
            views.setTextViewText(R.id.widget_host, hostText.ifEmpty { "IP de destino" })
            views.setTextColor(R.id.widget_host, if (hostText.isEmpty()) Palette.muted else Palette.white)
            // Enquanto pinga o IP não é editável, mas o toque não deve abrir o app por engano.
            views.setOnClickPendingIntent(R.id.widget_host, if (running) noop(context) else editIp(context))

            // Resultado mais recente: só uma linha, cada resposta substitui a anterior.
            val result = when {
                running -> PingHub.latest
                PingHub.sessions.isNotEmpty() -> PingHub.latest
                else -> PingHub.Latest("—", Palette.muted)
            }
            views.setTextViewText(R.id.widget_result, result.text)
            views.setTextColor(R.id.widget_result, result.color)

            // Executar / parar
            if (running) {
                views.setTextViewText(R.id.widget_toggle, "■")
                views.setTextColor(R.id.widget_toggle, Palette.red)
                views.setInt(R.id.widget_toggle, "setBackgroundResource", R.drawable.widget_btn_stop)
                views.setOnClickPendingIntent(R.id.widget_toggle, service(context, PingService.ACTION_STOP, 20))
            } else {
                views.setTextViewText(R.id.widget_toggle, "▶")
                views.setTextColor(R.id.widget_toggle, Palette.onGreen)
                views.setInt(R.id.widget_toggle, "setBackgroundResource", R.drawable.widget_btn_start)
                // Sem IP salvo, o botão pede o IP em vez de tentar iniciar.
                views.setOnClickPendingIntent(
                    R.id.widget_toggle,
                    if (savedHost.isBlank()) editIp(context) else service(context, PingService.ACTION_START, 21)
                )
            }

            // O resto da pílula abre o app.
            val open = openApp(context)
            views.setOnClickPendingIntent(R.id.widget_root, open)
            views.setOnClickPendingIntent(R.id.widget_header, open)
            views.setOnClickPendingIntent(R.id.widget_result, open)
            return views
        }
    }

    override fun onUpdate(context: Context, manager: AppWidgetManager, appWidgetIds: IntArray) {
        manager.updateAppWidget(appWidgetIds, build(context))
    }
}
