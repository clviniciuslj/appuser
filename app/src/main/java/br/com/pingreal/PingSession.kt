package br.com.pingreal

import java.util.concurrent.ExecutorService
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

/** Estatísticas acumuladas de um alvo. Uma "queda" é uma sequência de [OUTAGE_LOSSES] perdas seguidas. */
class PingStats {
    companion object {
        const val OUTAGE_LOSSES = 3
    }

    var received = 0
        private set
    var lost = 0
    var min = Double.MAX_VALUE
        private set
    var max = 0.0
        private set
    var last = -1.0
        private set
    var lastWasLoss = false
        private set
    var outages = 0
        private set
    var longestOutageMs = 0L
        private set

    private var sum = 0.0
    private var jitterSum = 0.0
    private var jitterN = 0
    private var lossStreak = 0
    private var streakStart = 0L
    private var inOutage = false

    val sent: Int get() = received + lost
    val avg: Double get() = if (received > 0) sum / received else 0.0
    val jitter: Double get() = if (jitterN > 0) jitterSum / jitterN else 0.0
    val jitterSamples: Int get() = jitterN
    val lossPct: Double get() = if (sent > 0) lost * 100.0 / sent else 0.0

    /** Registra uma resposta. Devolve true se ela encerrou uma queda. */
    fun addReply(ms: Double, at: Long): Boolean {
        if (last >= 0 && !lastWasLoss) {
            jitterSum += abs(ms - last)
            jitterN++
        }
        received++
        sum += ms
        if (ms < min) min = ms
        if (ms > max) max = ms
        last = ms
        lastWasLoss = false
        lossStreak = 0
        val ended = inOutage
        closeOutage(at)
        return ended
    }

    /** Registra uma perda. Devolve true se ela iniciou uma queda. */
    fun addLoss(at: Long): Boolean {
        lost++
        last = -1.0
        lastWasLoss = true
        if (lossStreak == 0) streakStart = at
        lossStreak++
        if (!inOutage && lossStreak >= OUTAGE_LOSSES) {
            inOutage = true
            outages++
            return true
        }
        return false
    }

    /** Fecha uma queda em aberto (ao voltar a responder ou ao parar o ping) para medir a duração. */
    fun closeOutage(at: Long) {
        if (!inOutage) return
        longestOutageMs = max(longestOutageMs, at - streakStart)
        inOutage = false
    }
}

class PingReply(val address: String, val ttl: String, val timeMs: Double, val seq: Int?)

/**
 * Um ping contra um único alvo. Roda o binário do sistema numa thread própria e entrega tudo
 * (respostas, perdas, quedas, erros, fim) ao [Listener] **na thread da UI**.
 *
 * Cada execução do processo é uma "época": o `ping` recomeça a numerar os pacotes do 1, então ao
 * reiniciar o processo (por [restart] ou porque o sistema o encerrou) a contagem de sequência é zerada,
 * mas as estatísticas ([stats]) continuam acumulando.
 */
class PingSession(
    val host: String,
    val continuous: Boolean,
    val count: Int,
    val size: Int,
    val color: Int,
    private val listener: Listener,
    private val pool: ExecutorService,
    private val postToUi: (() -> Unit) -> Unit
) {
    interface Listener {
        fun onReply(session: PingSession, reply: PingReply, sampleTime: Long)
        fun onLoss(session: PingSession, sendTime: Long)

        /** [down] = começou uma queda; false = voltou a responder depois de uma queda. */
        fun onOutage(session: PingSession, down: Boolean)
        fun onErrorLine(session: PingSession, line: String)
        fun onFinished(session: PingSession, error: String?)
    }

    companion object {
        private const val LOSS_TIMEOUT_MS = 2000L

        private val REPLY_STRICT = Regex(
            """(\d+)\s+bytes from\s+(.+?):?\s+icmp_\w+=(\d+)\s+ttl=(\d+)\s+time[=<]\s*([\d.]+)\s*ms""",
            RegexOption.IGNORE_CASE
        )
        private val REPLY_LOOSE = Regex(
            """(\d+)\s+bytes from\s+(.+?):.*?\bttl[= ](\d+).*?\btime[=<]\s*([\d.]+)\s*ms""",
            RegexOption.IGNORE_CASE
        )

        fun parseReply(line: String): PingReply? {
            REPLY_STRICT.find(line)?.let { m ->
                val time = m.groupValues[5].toDoubleOrNull() ?: return null
                return PingReply(m.groupValues[2].trim(), m.groupValues[4], time, m.groupValues[3].toIntOrNull())
            }
            REPLY_LOOSE.find(line)?.let { m ->
                val time = m.groupValues[4].toDoubleOrNull() ?: return null
                return PingReply(m.groupValues[2].trim().removeSuffix(":"), m.groupValues[3], time, null)
            }
            return null
        }

        fun isHardError(lower: String): Boolean =
            lower.contains("unknown host") ||
                lower.contains("cannot resolve") ||
                lower.contains("network is unreachable") ||
                lower.contains("operation not permitted") ||
                lower.contains("name or service not known") ||
                lower.contains("bad address")

        fun isErrorLine(line: String): Boolean {
            val lower = line.lowercase()
            return isHardError(lower) ||
                lower.contains("destination host unreachable") ||
                lower.contains("connect:") ||
                lower.contains("ping:")
        }
    }

    val stats = PingStats()

    var hardError = false
        private set
    var finished = false
        private set
    @Volatile var stopRequested = false
        private set

    @Volatile private var process: Process? = null
    @Volatile private var epoch = 0

    // Controle de sequência da execução atual do processo (somente thread da UI).
    private val answered = HashSet<Int>()
    private val settled = HashSet<Int>()
    private var nextSeq = 1
    private var lastSeq = 0
    private var slotStart = 0L
    private var slotAnchored = false
    private var lastTickAt = 0L
    private var epochReplies = 0

    fun start() {
        resetTracking()
        launch()
    }

    fun stop() {
        stopRequested = true
        process?.destroy()
    }

    /** Chamado periodicamente pela UI para detectar pacotes cujo prazo venceu. */
    fun tick() {
        if (finished || stopRequested) return
        val now = System.currentTimeMillis()
        // Se a UI ficou parada (tela bloqueada, app em segundo plano), o relógio dos pacotes não andou
        // junto: sem este ajuste, todos os pacotes "atrasados" seriam contados como perdidos de uma vez.
        if (lastTickAt != 0L && now - lastTickAt > 3000) slotStart += now - lastTickAt
        lastTickAt = now
        checkLosses(false)
    }

    private fun resetTracking() {
        answered.clear()
        settled.clear()
        nextSeq = 1
        lastSeq = 0
        slotStart = System.currentTimeMillis()
        slotAnchored = false
        lastTickAt = 0L
        epochReplies = 0
    }

    private fun launch() {
        val myEpoch = epoch
        pool.execute { runProcess(myEpoch) }
    }

    private fun runProcess(myEpoch: Int) {
        var error: String? = null
        try {
            // -n: sem resolução reversa de nomes (evita atrasos quando o DNS está ruim).
            val args = mutableListOf("/system/bin/ping", "-n", "-W", "1", "-s", size.toString())
            if (!continuous) {
                args.add("-c")
                args.add(count.toString())
            }
            args.add(host)

            val p = ProcessBuilder(args).redirectErrorStream(true).start()
            process = p
            if (stopRequested || myEpoch != epoch) p.destroy()
            try {
                p.inputStream.bufferedReader().use { reader ->
                    while (true) {
                        val line = reader.readLine() ?: break
                        if (line.isBlank()) continue
                        // O horário vale o da leitura, não o do processamento na UI (que pode atrasar).
                        val readAt = System.currentTimeMillis()
                        postToUi { if (myEpoch == epoch) handleLine(line, readAt) }
                    }
                }
                p.waitFor()
            } finally {
                if (process === p) process = null
                p.destroy()
            }
        } catch (e: Exception) {
            if (!stopRequested && myEpoch == epoch) error = e.message ?: "falha ao executar ping"
        }
        postToUi { onProcessEnded(myEpoch, error) }
    }

    private fun onProcessEnded(myEpoch: Int, error: String?) {
        if (myEpoch != epoch || finished) return // já substituído por um processo novo

        // Ping contínuo que morreu sozinho (ex.: o sistema encerrou o processo em segundo plano): reinicia.
        // Só se já tinha funcionado, para não ficar num laço infinito com destino inválido.
        val diedOnItsOwn = continuous && !stopRequested && error == null && !hardError && epochReplies > 0
        if (diedOnItsOwn) {
            epoch++
            resetTracking()
            launch()
            return
        }
        finish(error)
    }

    private fun handleLine(line: String, readAt: Long) {
        // Depois de parar, linhas que ainda estavam na fila chegam com horário errado: ignora.
        if (finished || stopRequested) return

        // "ping: sendmsg: Operation not permitted": o Android bloqueou a rede do app (segundo plano,
        // economia de bateria/dados). É temporário, não um erro de destino.
        val lowerLine = line.lowercase()
        if (lowerLine.contains("sendmsg") && lowerLine.contains("not permitted")) {
            onSendBlocked(readAt)
            return
        }

        val reply = parseReply(line)
        if (reply == null) {
            if (isErrorLine(line)) {
                if (isHardError(lowerLine)) hardError = true
                listener.onErrorLine(this, line)
            }
            return
        }

        val seq = reply.seq ?: (lastSeq + 1)
        lastSeq = max(lastSeq, seq)

        if (!slotAnchored) {
            slotStart = readAt - reply.timeMs.toLong() - (seq - 1) * 1000L
            slotAnchored = true
        }
        if (!answered.add(seq)) return // duplicado

        if (seq < nextSeq) stats.lost = max(0, stats.lost - 1) // chegou depois de marcado como perdido

        epochReplies++
        val outageEnded = stats.addReply(reply.timeMs, readAt)
        listener.onReply(this, reply, readAt - reply.timeMs.toLong())
        if (outageEnded) listener.onOutage(this, false)
        checkLosses(false)
    }

    private fun registerLoss(at: Long) {
        val outageBegan = stats.addLoss(at)
        listener.onLoss(this, at)
        if (outageBegan) listener.onOutage(this, true)
    }

    /** O envio do pacote atual falhou: registra a perda na hora, em vez de esperar o prazo vencer. */
    private fun onSendBlocked(at: Long) {
        val seq = ((at - slotStart) / 1000.0).roundToInt() + 1
        val limit = if (continuous) Int.MAX_VALUE else count
        if (seq < nextSeq || seq > limit || seq in answered || !settled.add(seq)) return
        registerLoss(at)
    }

    /** Marca como perdidos os pacotes cujo prazo venceu (ou todos os pendentes, se [force] e o ping for finito). */
    private fun checkLosses(force: Boolean) {
        val now = System.currentTimeMillis()
        val limit = if (continuous) Int.MAX_VALUE else count
        while (nextSeq <= limit) {
            if (force && continuous) break
            val sendTime = slotStart + (nextSeq - 1) * 1000L
            if (!force && sendTime + LOSS_TIMEOUT_MS > now) break
            if (nextSeq !in answered && nextSeq !in settled) registerLoss(sendTime)
            nextSeq++
        }
    }

    private fun finish(error: String?) {
        if (finished) return
        val clean = !stopRequested && error == null && !hardError
        checkLosses(force = clean)
        stats.closeOutage(System.currentTimeMillis())
        finished = true
        listener.onFinished(this, error)
    }
}
