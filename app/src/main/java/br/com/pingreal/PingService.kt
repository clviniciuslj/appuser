package br.com.pingreal

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ComponentName
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder

/**
 * Mantém o processo vivo enquanto há um ping em andamento (com o app fechado ou a tela bloqueada).
 * Não executa o ping: quem manda nele é o [PingHub]. Encerra sozinho quando a sessão termina.
 */
class PingService : Service() {

    companion object {
        const val ACTION_START = "br.com.pingreal.action.START"
        const val ACTION_STOP = "br.com.pingreal.action.STOP"
        const val ACTION_ENSURE = "br.com.pingreal.action.ENSURE"

        private const val CHANNEL = "ping"
        private const val NOTIFICATION_ID = 1
        private const val NOTIFICATION_MIN_INTERVAL_MS = 2000L

        @Volatile var instance: PingService? = null
    }

    private var lastNotificationAt = 0L
    private var lastNotificationText = ""

    override fun onCreate() {
        super.onCreate()
        instance = this
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL, "Ping em andamento", NotificationManager.IMPORTANCE_LOW).apply {
                setShowBadge(false)
            }
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Quem inicia um serviço em primeiro plano é obrigado a chamar startForeground logo: faz isso
        // sempre, antes de qualquer outra coisa, mesmo que depois não haja nada para manter.
        enterForeground()

        when (intent?.action) {
            ACTION_START -> startFromWidget()
            ACTION_STOP -> PingHub.stop()
            else -> Unit
        }

        if (!PingHub.running) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun startFromWidget() {
        val host = Prefs.of(this).getString(Prefs.WIDGET_HOST, "")?.let { parseHost(it.trim()) } ?: return
        PingHub.start(applicationContext, host, listOf(PingTarget(host, true, 4, 32)))
    }

    private fun enterForeground() {
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    /** Atualiza o texto da notificação, no máximo a cada poucos segundos e só se mudou. */
    fun refreshNotification() {
        val text = notificationText()
        val now = System.currentTimeMillis()
        if (text == lastNotificationText || now - lastNotificationAt < NOTIFICATION_MIN_INTERVAL_MS) return
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, buildNotification())
    }

    private fun notificationText(): String {
        val label = PingHub.hostLabel
        return if (label.isEmpty()) "Ping em andamento" else "$label · ${PingHub.latest.text}"
    }

    private fun buildNotification(): Notification {
        val text = notificationText()
        lastNotificationText = text
        lastNotificationAt = System.currentTimeMillis()

        val open = PendingIntent.getActivity(
            this, 0,
            Intent.makeMainActivity(ComponentName(this, MainActivity::class.java))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_ping)
            .setContentTitle("Real Ping")
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setCategory(Notification.CATEGORY_SERVICE)
            .build()
    }

    override fun onDestroy() {
        instance = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
