package br.com.pingreal

import java.net.InetAddress
import kotlin.math.max

/** Ping bloqueante de poucos pacotes (usado pelo diagnóstico e pelo teste de velocidade). */
fun quickPing(host: String, count: Int, procs: MutableList<Process>): PingStats {
    val stats = PingStats()
    val process = ProcessBuilder("/system/bin/ping", "-n", "-W", "1", "-s", "32", "-c", count.toString(), host)
        .redirectErrorStream(true)
        .start()
    procs.add(process)
    try {
        process.inputStream.bufferedReader().use { reader ->
            while (true) {
                val line = reader.readLine() ?: break
                PingSession.parseReply(line)?.let { stats.addReply(it.timeMs, System.currentTimeMillis()) }
            }
        }
        process.waitFor()
    } finally {
        procs.remove(process)
        process.destroy()
    }
    stats.lost = max(0, count - stats.received)
    return stats
}

/** Sonda de traceroute: um ping com TTL limitado. Compartilhado entre o traceroute e o diagnóstico. */
object Tracer {

    /**
     * [raw] só vem preenchido quando nenhum endereço foi reconhecido; [line] é a linha que originou
     * o resultado (ou a primeira linha da resposta), útil para depurar o formato do `ping`.
     */
    class Probe(
        val address: String?,
        val ms: Double?,
        val reached: Boolean,
        val raw: String?,
        val line: String? = null
    )

    private val HEADER_IP = Regex("""^PING\s+\S+\s+\(([^)]+)\)""", RegexOption.IGNORE_CASE)
    private val TTL_FIELD = Regex("""\bttl[= ]\d+""", RegexOption.IGNORE_CASE)
    private val TIME_FIELD = Regex("""time[=<]\s*([\d.]+)\s*ms""", RegexOption.IGNORE_CASE)
    private val FROM_ADDR = Regex(
        """\bfrom\s+(?:\S+\s+\()?([0-9a-fA-F:.]+)\)?""",
        RegexOption.IGNORE_CASE
    )
    private val EXCEEDED = listOf(
        "time to live exceeded", "ttl exceeded", "time exceeded",
        "hop limit", "ttl expired"
    )

    /** Compara dois IPs literais (IPv6 pode vir escrito de formas diferentes). */
    private fun sameIp(a: String, b: String): Boolean = try {
        InetAddress.getByName(a) == InetAddress.getByName(b)
    } catch (_: Exception) {
        a == b
    }

    /** Custo fixo de abrir o processo do ping, descontado quando o roteador não informa `time=`. */
    fun calibrateOverhead(procs: MutableList<Process>): Double = try {
        val started = System.nanoTime()
        val process = ProcessBuilder("/system/bin/ping", "-n", "-c", "1", "-W", "1", "127.0.0.1")
            .redirectErrorStream(true)
            .start()
        procs.add(process)
        try {
            process.inputStream.bufferedReader().use { it.readText() }
            process.waitFor()
        } finally {
            procs.remove(process)
            process.destroy()
        }
        (System.nanoTime() - started) / 1_000_000.0
    } catch (_: Exception) {
        0.0
    }

    private fun isNoise(line: String): Boolean {
        val lower = line.lowercase()
        return lower.startsWith("ping ") ||
            lower.startsWith("---") ||
            lower.startsWith("rtt") ||
            lower.startsWith("round-trip") ||
            lower.contains("statistics") ||
            lower.contains("packets transmitted") ||
            (lower.contains("%") && lower.contains("loss"))
    }

    private fun extractAddress(line: String): String? =
        FROM_ADDR.find(line)?.groupValues?.get(1)
            ?.removeSuffix(":")
            ?.takeIf { it.contains('.') || it.contains(':') }
            ?.substringBefore('%')

    fun probe(host: String, ttl: Int, overheadMs: Double, procs: MutableList<Process>): Probe {
        val args = listOf("/system/bin/ping", "-n", "-c", "1", "-W", "2", "-t", ttl.toString(), host)
        val started = System.nanoTime()
        val process = ProcessBuilder(args).redirectErrorStream(true).start()
        procs.add(process)
        val lines = try {
            process.inputStream.bufferedReader().use { it.readLines() }
        } finally {
            procs.remove(process)
            process.destroy()
        }
        val elapsedMs = (System.nanoTime() - started) / 1_000_000.0

        val trimmed = lines.map { it.trim() }
        val targetIp = trimmed.firstNotNullOfOrNull { HEADER_IP.find(it)?.groupValues?.get(1)?.trim() }
        val reply = trimmed.filter { it.isNotEmpty() && !isNoise(it) }

        val destination = reply.firstOrNull {
            it.contains("bytes from", ignoreCase = true) && TTL_FIELD.containsMatchIn(it)
        }
        val exceeded = reply.firstOrNull { l -> EXCEEDED.any { l.contains(it, ignoreCase = true) } }
        val anyFrom = reply.firstOrNull { FROM_ADDR.containsMatchIn(it) && extractAddress(it) != null }

        val source = destination ?: exceeded ?: anyFrom
        val address = source?.let { extractAddress(it) }

        if (address == null) return Probe(null, null, false, reply.firstOrNull(), reply.firstOrNull())

        val reportedTime = source?.let { TIME_FIELD.find(it)?.groupValues?.get(1)?.toDoubleOrNull() }
        val ms = reportedTime ?: (elapsedMs - overheadMs).coerceAtLeast(0.5)

        // "Alcançado" só vale se a resposta veio do endereço de destino (e não de um roteador no meio).
        val reached = destination != null && (targetIp == null || sameIp(address, targetIp))
        return Probe(address, ms, reached, null, source)
    }
}
