package br.com.pingreal

import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong

class SpeedResult(val downMbps: Double, val upMbps: Double, val pingMs: Double, val jitterMs: Double)

/** Teste de velocidade contra os servidores da Cloudflare: latência, download e upload. */
class SpeedTest {

    interface Listener {
        fun onPhase(phase: Int)
        fun onLatency(pingMs: Double, jitterMs: Double)
        fun onProgress(phase: Int, mbps: Double, fraction: Float)
    }

    companion object {
        const val PHASE_LATENCY = 0
        const val PHASE_DOWN = 1
        const val PHASE_UP = 2

        private const val PING_HOST = "1.1.1.1"
        private const val PING_COUNT = 6
        private const val DOWN_URL = "https://speed.cloudflare.com/__down?bytes=25000000"
        private const val UP_URL = "https://speed.cloudflare.com/__up"
        private const val DOWN_SECONDS = 8
        private const val UP_SECONDS = 6
        private const val WARMUP_NS = 1_500_000_000L
    }

    @Volatile var cancelled = false
        private set

    private val procs = CopyOnWriteArrayList<Process>()
    private val connections = CopyOnWriteArrayList<HttpURLConnection>()

    fun cancel() {
        cancelled = true
        procs.forEach { it.destroy() }
        connections.forEach { try { it.disconnect() } catch (_: Exception) {} }
    }

    fun run(listener: Listener): SpeedResult {
        listener.onPhase(PHASE_LATENCY)
        val ping = measureLatency(listener)
        listener.onLatency(ping.avg, ping.jitter)
        if (cancelled) return SpeedResult(0.0, 0.0, ping.avg, ping.jitter)

        listener.onPhase(PHASE_DOWN)
        val down = transfer(download = true, seconds = DOWN_SECONDS, threads = 4, phase = PHASE_DOWN, listener = listener)
        if (cancelled) return SpeedResult(down, 0.0, ping.avg, ping.jitter)

        listener.onPhase(PHASE_UP)
        val up = transfer(download = false, seconds = UP_SECONDS, threads = 3, phase = PHASE_UP, listener = listener)

        return SpeedResult(down, up, ping.avg, ping.jitter)
    }

    /** Um ping por vez, para a barra avançar a cada pacote em vez de ficar parada até o fim. */
    private fun measureLatency(listener: Listener): PingStats {
        val stats = PingStats()
        for (i in 1..PING_COUNT) {
            if (cancelled) break
            val one = try {
                quickPing(PING_HOST, 1, procs)
            } catch (_: Exception) {
                null
            }
            val now = System.currentTimeMillis()
            if (one != null && one.received > 0) stats.addReply(one.avg, now) else stats.addLoss(now)
            listener.onProgress(PHASE_LATENCY, 0.0, i / PING_COUNT.toFloat())
        }
        return stats
    }

    private fun transfer(download: Boolean, seconds: Int, threads: Int, phase: Int, listener: Listener): Double {
        val total = AtomicLong(0)
        val startNs = System.nanoTime()
        val endNs = startNs + seconds * 1_000_000_000L
        val pool = Executors.newFixedThreadPool(threads)

        repeat(threads) {
            pool.execute {
                val buffer = ByteArray(64 * 1024)
                while (System.nanoTime() < endNs && !cancelled) {
                    var conn: HttpURLConnection? = null
                    try {
                        conn = (URL(if (download) DOWN_URL else UP_URL).openConnection() as HttpURLConnection).apply {
                            connectTimeout = 5000
                            readTimeout = 6000
                            useCaches = false
                            if (!download) {
                                requestMethod = "POST"
                                doOutput = true
                                setChunkedStreamingMode(64 * 1024)
                                setRequestProperty("Content-Type", "application/octet-stream")
                            }
                        }
                        connections.add(conn)
                        if (download) {
                            conn.inputStream.use { input ->
                                while (System.nanoTime() < endNs && !cancelled) {
                                    val n = input.read(buffer)
                                    if (n < 0) break
                                    total.addAndGet(n.toLong())
                                }
                            }
                        } else {
                            conn.outputStream.use { out ->
                                while (System.nanoTime() < endNs && !cancelled) {
                                    out.write(buffer)
                                    total.addAndGet(buffer.size.toLong())
                                }
                            }
                        }
                    } catch (_: Exception) {
                        try { Thread.sleep(200) } catch (_: InterruptedException) { return@execute }
                    } finally {
                        conn?.let {
                            connections.remove(it)
                            try { it.disconnect() } catch (_: Exception) {}
                        }
                    }
                }
            }
        }

        // Amostrador: calcula a velocidade a cada 200 ms (média das últimas 5 amostras).
        val recent = ArrayDeque<Double>()
        var lastNs = startNs
        var lastBytes = 0L
        var warmBytes = -1L
        var warmNs = 0L
        while (System.nanoTime() < endNs && !cancelled) {
            try { Thread.sleep(200) } catch (_: InterruptedException) { break }
            val now = System.nanoTime()
            val bytes = total.get()
            val dt = (now - lastNs) / 1e9
            if (dt > 0) {
                recent.addLast((bytes - lastBytes) * 8 / dt / 1e6)
                if (recent.size > 5) recent.removeFirst()
            }
            if (warmBytes < 0 && now - startNs >= WARMUP_NS) {
                warmBytes = bytes
                warmNs = now
            }
            lastNs = now
            lastBytes = bytes
            listener.onProgress(phase, recent.average(), ((now - startNs) / (seconds * 1e9)).toFloat().coerceIn(0f, 1f))
        }

        val finishNs = System.nanoTime()
        val finishBytes = total.get()
        connections.forEach { try { it.disconnect() } catch (_: Exception) {} }
        pool.shutdownNow()

        val result = if (warmBytes >= 0 && finishNs > warmNs) {
            (finishBytes - warmBytes) * 8 / ((finishNs - warmNs) / 1e9) / 1e6
        } else if (finishNs > startNs) {
            finishBytes * 8 / ((finishNs - startNs) / 1e9) / 1e6
        } else {
            0.0
        }
        listener.onProgress(phase, result, 1f)
        return result
    }
}
