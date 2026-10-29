package br.com.pingreal

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.core.content.ContextCompat
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.roundToInt

class PingTarget(val host: String, val continuous: Boolean, val count: Int, val size: Int)

/**
 * Dono único da sessão de ping. O app, o widget e a notificação só *observam* e pedem iniciar/parar:
 * existe no máximo uma sessão por vez, e ela não depende de nenhuma tela estar aberta.
 *
 * Tudo roda na thread principal (as sessões entregam os eventos por lá).
 */
object PingHub : PingSession.Listener {

    /** [kind] é uma cor semântica (TEXT, MUTED… ou SERIES + índice do alvo); cada tela traduz para sua cor. */
    class Line(val seq: Long, val text: String, val kind: Int)

    private class ChartEvent(val index: Int, val time: Long, val ms: Float)

    /** Último resultado, no formato curto que o widget e a notificação mostram. */
    class Latest(val text: String, val color: Int)

    interface Observer {
        /** Uma sessão começou (ou já existia ao se conectar): reiniciar gráfico e painel. */
        fun onStarted() {}
        fun onLine(line: Line) {}

        /** [ms] < 0 indica pacote perdido. */
        fun onSample(index: Int, time: Long, ms: Float) {}
        fun onStats() {}

        /** Todos os alvos terminaram. */
        fun onFinished() {}
    }

    const val TEXT = 0
    const val MUTED = 1
    const val WHITE = 2
    const val AMBER = 3
    const val RED = 4
    const val GREEN = 5
    const val SERIES = 10

    private const val RULE = "────────────────────────────"
    private const val MAX_LINES = 600
    private const val MAX_EVENTS = 1200
    private const val WIDGET_MIN_INTERVAL_MS = 400L

    private val handler = Handler(Looper.getMainLooper())
    private val observers = ArrayList<Observer>()
    private val lines = ArrayDeque<Line>()
    private val events = ArrayDeque<ChartEvent>()
    private var seq = 0L
    private var appContext: Context? = null
    private var pool: ExecutorService? = null
    private var stopRequested = false

    var sessions: List<PingSession> = emptyList()
        private set
    var running = false
        private set
    var raw = ""
        private set
    var latest = Latest("—", Palette.muted)
        private set

    /** O app está ocupado com traceroute, diagnóstico ou velocidade: não inicia outro teste por cima. */
    var externalBusy = false

    val hostLabel: String
        get() {
            val first = sessions.firstOrNull()?.host ?: return ""
            return if (sessions.size > 1) "$first +${sessions.size - 1}" else first
        }

    // =====================================================================
    // Observadores
    // =====================================================================

    /** Conecta uma tela. Reenvia o que ela perdeu: pontos do gráfico e as linhas com [Line.seq] > [lastSeq]. */
    fun attach(observer: Observer, lastSeq: Long) {
        if (observer in observers) return
        observers.add(observer)
        val hasSession = sessions.isNotEmpty()
        if (hasSession) {
            observer.onStarted()
            for (e in events) observer.onSample(e.index, e.time, e.ms)
        }
        for (l in lines) if (l.seq > lastSeq) observer.onLine(l)
        if (hasSession) {
            observer.onStats()
            if (!running) observer.onFinished()
        }
    }

    fun detach(observer: Observer) {
        observers.remove(observer)
    }

    private inline fun notifyAll(block: (Observer) -> Unit) {
        for (o in observers.toList()) block(o)
    }

    // =====================================================================
    // Iniciar / parar
    // =====================================================================

    /** Inicia a sessão. Devolve false se já há um teste em andamento (evita sessões duplicadas). */
    fun start(context: Context, raw: String, targets: List<PingTarget>): Boolean {
        if (running || externalBusy || targets.isEmpty()) return false
        val ctx = context.applicationContext
        appContext = ctx
        stopRequested = false
        events.clear()
        this.raw = raw

        pool?.shutdownNow()
        val workers = Executors.newCachedThreadPool()
        pool = workers
        sessions = targets.mapIndexed { i, t ->
            PingSession(
                t.host, t.continuous, t.count, t.size,
                Palette.series[i % Palette.series.size], this, workers
            ) { block -> handler.post { block() } }
        }
        running = true
        latest = Latest("Aguardando…", Palette.muted)

        addLine("", MUTED)
        addLine(RULE, MUTED)
        addLine("> $raw", WHITE)
        addLine(describe(targets), MUTED)
        addLine("", MUTED)

        notifyAll { it.onStarted() }
        handler.removeCallbacks(tick)
        handler.postDelayed(tick, 500)

        // O serviço em primeiro plano mantém o processo vivo com o app fechado. Se o sistema não permitir
        // (restrições de execução em segundo plano), a sessão segue normalmente, só sem essa proteção.
        try {
            ContextCompat.startForegroundService(
                ctx, Intent(ctx, PingService::class.java).setAction(PingService.ACTION_ENSURE)
            )
        } catch (_: Exception) {
        }

        sessions.forEach { it.start() }
        refreshOutside(force = true)
        return true
    }

    /** Pede para parar. A sessão termina assim que os processos de ping encerram. */
    fun stop() {
        if (!running || stopRequested) return
        stopRequested = true
        addLine("Interrompendo...", MUTED)
        sessions.forEach { it.stop() }
    }

    private fun describe(targets: List<PingTarget>): String {
        if (targets.size > 1) return "Ping simultâneo para ${targets.size} alvos"
        val t = targets[0]
        val sizeNote = if (t.size != 32) " com ${t.size} bytes" else ""
        return if (t.continuous) "Ping contínuo para ${t.host}$sizeNote"
        else "Ping para ${t.host} (${t.count} tentativas)$sizeNote"
    }

    private val tick = object : Runnable {
        override fun run() {
            if (!running) return
            sessions.forEach { it.tick() }
            notifyAll { it.onStats() }
            handler.postDelayed(this, 500)
        }
    }

    private fun complete() {
        handler.removeCallbacks(tick)
        addLine(if (stopRequested) "Interrompido pelo usuário." else "Concluído.", MUTED)
        addLine(RULE, MUTED)

        running = false
        val failed = sessions.firstOrNull()?.let { it.hardError && it.stats.received == 0 } == true
        latest = if (failed) Latest("Destino inválido", Palette.red) else Latest("Parado", Palette.muted)

        pool?.shutdown()
        pool = null

        notifyAll { it.onFinished() }
        refreshOutside(force = true)

        // Nada mais a manter ativo: encerra o serviço em primeiro plano.
        appContext?.let { it.stopService(Intent(it, PingService::class.java)) }
    }

    // =====================================================================
    // Eventos das sessões
    // =====================================================================

    private fun prefix(session: PingSession): String =
        if (sessions.size > 1) "[${session.host}] " else ""

    private fun kindFor(index: Int): Int = if (sessions.size > 1) SERIES + index else TEXT

    override fun onReply(session: PingSession, reply: PingReply, sampleTime: Long) {
        val idx = sessions.indexOf(session)
        pushEvent(idx, sampleTime, reply.timeMs.toFloat())
        val shown = if (reply.timeMs < 1) "<1" else reply.timeMs.roundToInt().toString()
        addLine(
            "${prefix(session)}Resposta de ${reply.address}: bytes=${session.size} tempo=${shown}ms TTL=${reply.ttl}",
            kindFor(idx)
        )
        if (idx == 0) latest = Latest("${formatMs(reply.timeMs)} ms", LatencyChartView.colorFor(reply.timeMs))
        notifyAll {
            it.onSample(idx, sampleTime, reply.timeMs.toFloat())
            it.onStats()
        }
        refreshOutside(force = false)
    }

    override fun onLoss(session: PingSession, sendTime: Long) {
        val idx = sessions.indexOf(session)
        pushEvent(idx, sendTime, -1f)
        addLine("${prefix(session)}Esgotado o tempo limite da solicitação.", AMBER)
        if (idx == 0) latest = Latest("Esgotado", Palette.amber)
        notifyAll {
            it.onSample(idx, sendTime, -1f)
            it.onStats()
        }
        refreshOutside(force = false)
    }

    override fun onOutage(session: PingSession, down: Boolean) {
        val ctx = appContext ?: return
        if (!Prefs.of(ctx).getBoolean(Prefs.VIBRATE, true)) return
        vibrate(ctx, if (down) longArrayOf(0, 400, 150, 400) else longArrayOf(0, 70, 90, 70))
    }

    override fun onErrorLine(session: PingSession, line: String) {
        addLine("${prefix(session)}$line", RED)
    }

    override fun onFinished(session: PingSession, error: String?) {
        printSummary(session, error)
        if (sessions.all { it.finished }) complete()
    }

    private fun printSummary(session: PingSession, error: String?) {
        val s = session.stats
        val tag = prefix(session)
        if (s.sent > 0) {
            addLine("", MUTED)
            addLine("${tag}Estatísticas do Ping para ${session.host}:", WHITE)
            addLine(
                "    Pacotes: Enviados = ${s.sent}, Recebidos = ${s.received}, " +
                    "Perdidos = ${s.lost} (${s.lossPct.roundToInt()}% de perda),",
                WHITE
            )
            if (s.received > 0) {
                addLine(
                    "    Mínimo = ${formatMs(s.min)}ms, Máximo = ${formatMs(s.max)}ms, " +
                        "Média = ${formatMs(s.avg)}ms, Jitter = ${formatMs(s.jitter)}ms",
                    WHITE
                )
            } else {
                addLine("    Nenhuma resposta válida recebida.", AMBER)
            }
            if (s.outages > 0) {
                addLine("    Quedas = ${s.outages} (maior: ${formatDuration(s.longestOutageMs)})", AMBER)
            }
        }
        when {
            error != null -> addLine("${tag}Erro: $error", RED)
            session.hardError -> addLine("${tag}Falha ao resolver ou alcançar o destino.", RED)
        }
    }

    // =====================================================================
    // Histórico
    // =====================================================================

    private fun addLine(text: String, kind: Int) {
        val line = Line(++seq, text, kind)
        lines.addLast(line)
        if (lines.size > MAX_LINES) lines.removeFirst()
        notifyAll { it.onLine(line) }
    }

    private fun pushEvent(index: Int, time: Long, ms: Float) {
        events.addLast(ChartEvent(index, time, ms))
        if (events.size > MAX_EVENTS) events.removeFirst()
    }

    // =====================================================================
    // Widget e notificação (fora do app)
    // =====================================================================

    private var lastSignature = ""
    private var lastOutsideAt = 0L
    private var pendingOutside = false
    private val outsideRunnable = Runnable {
        pendingOutside = false
        pushOutside()
    }

    /** Atualiza widget e notificação só quando o texto mudou, e no máximo algumas vezes por segundo. */
    private fun refreshOutside(force: Boolean) {
        if (appContext == null) return
        if (!force && signature() == lastSignature) return
        val wait = WIDGET_MIN_INTERVAL_MS - (System.currentTimeMillis() - lastOutsideAt)
        if (!force && wait > 0) {
            if (!pendingOutside) {
                pendingOutside = true
                handler.postDelayed(outsideRunnable, wait)
            }
            return
        }
        handler.removeCallbacks(outsideRunnable)
        pendingOutside = false
        pushOutside()
    }

    private fun signature() = "$running|$hostLabel|${latest.text}|${latest.color}"

    private fun pushOutside() {
        val ctx = appContext ?: return
        lastSignature = signature()
        lastOutsideAt = System.currentTimeMillis()
        PingWidgetProvider.refresh(ctx)
        if (running) PingService.instance?.refreshNotification()
    }

    @Suppress("DEPRECATION")
    private fun vibrate(ctx: Context, pattern: LongArray) {
        try {
            val vibrator = if (Build.VERSION.SDK_INT >= 31) {
                ctx.getSystemService(VibratorManager::class.java)?.defaultVibrator
            } else {
                ctx.getSystemService(Vibrator::class.java)
            }
            vibrator?.vibrate(VibrationEffect.createWaveform(pattern, -1))
        } catch (_: Exception) {
        }
    }
}
