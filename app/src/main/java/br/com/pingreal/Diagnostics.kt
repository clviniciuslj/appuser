package br.com.pingreal

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.provider.Settings
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.net.ConnectException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.HttpURLConnection
import java.net.Inet6Address
import java.net.InetAddress
import java.net.SocketTimeoutException
import java.net.URL
import java.net.UnknownHostException
import java.util.Locale
import java.util.concurrent.Callable
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLException
import kotlin.random.Random

enum class Status { PENDING, RUNNING, OK, WARN, FAIL, SKIP }

class StepOutcome(val id: String, val title: String, var status: Status, var detail: String)

class DiagnosticReport(val steps: List<StepOutcome>, val cancelled: Boolean)

/**
 * Diagnóstico em camadas: da conexão do celular até a navegação. Roda bloqueando a thread
 * que o chamou e avisa cada etapa pelo `update` de [run].
 */
class DiagnosticEngine(private val context: Context) {

    companion object {
        val STEP_TITLES = listOf(
            "link" to "Conexão com a rede",
            "ip" to "Endereço IP (DHCP)",
            "gw" to "Roteador (gateway)",
            "inet" to "Internet por IP",
            "dns" to "DNS",
            "web" to "Navegação (HTTP/HTTPS)",
            "pub" to "IP público e provedor",
            "route" to "Rota até a internet"
        )

        private val INTERNET_TARGETS = listOf("1.1.1.1", "8.8.8.8", "9.9.9.9")
        private const val DNS_NAME = "www.google.com"
        private const val PING_COUNT = 6
    }

    @Volatile var cancelled = false
        private set

    private val procs = CopyOnWriteArrayList<Process>()
    private val pool = Executors.newFixedThreadPool(4)

    fun cancel() {
        cancelled = true
        procs.forEach { it.destroy() }
        pool.shutdownNow()
    }

    fun run(net: NetInfo, update: (StepOutcome) -> Unit): DiagnosticReport {
        val steps = STEP_TITLES.map { StepOutcome(it.first, it.second, Status.PENDING, "") }
        val byId = steps.associateBy { it.id }

        fun set(id: String, status: Status, detail: String) {
            val s = byId.getValue(id)
            s.status = status
            s.detail = detail
            update(s)
        }

        fun partial() = DiagnosticReport(steps, true)

        try {
            set("link", Status.RUNNING, "")
            linkStep(net).let { set("link", it.first, it.second) }
            if (cancelled) return partial()

            set("ip", Status.RUNNING, "")
            ipStep(net).let { set("ip", it.first, it.second) }
            if (cancelled) return partial()

            val noLink = byId.getValue("link").status == Status.FAIL
            val noIp = byId.getValue("ip").status == Status.FAIL
            if (noLink || noIp) {
                for (id in listOf("gw", "inet", "dns", "web", "pub", "route")) {
                    set(id, Status.SKIP, "Não testado: sem conexão local")
                }
                return DiagnosticReport(steps, false)
            }

            set("gw", Status.RUNNING, "")
            gatewayStep(net).let { set("gw", it.first, it.second) }
            if (cancelled) return partial()

            set("inet", Status.RUNNING, "")
            internetStep().let { set("inet", it.first, it.second) }
            if (cancelled) return partial()

            val internetDown = byId.getValue("inet").status == Status.FAIL

            set("dns", Status.RUNNING, "")
            dnsStep(net).let { set("dns", it.first, it.second) }
            if (cancelled) return partial()

            set("web", Status.RUNNING, "")
            val web = webStep()
            set("web", web.status, web.detail)
            if (cancelled) return partial()

            if (web.status == Status.FAIL) {
                set("pub", Status.SKIP, "Sem navegação para consultar")
            } else {
                set("pub", Status.RUNNING, "")
                pubStep(web.traceIp).let { set("pub", it.first, it.second) }
            }
            if (cancelled) return partial()

            if (internetDown && byId.getValue("gw").status != Status.FAIL) {
                set("route", Status.RUNNING, "")
                routeStep().let { set("route", it.first, it.second) }
            } else {
                set("route", Status.SKIP, "Não necessário")
            }

            return DiagnosticReport(steps, cancelled)
        } catch (e: Exception) {
            // Cancelar interrompe as threads no meio de uma etapa; isso não é erro.
            if (cancelled) return partial()
            throw e
        } finally {
            pool.shutdownNow()
        }
    }

    // ------------------------------------------------------------------ etapas

    @Suppress("DEPRECATION")
    private fun linkStep(net: NetInfo): Pair<Status, String> {
        val cm = context.getSystemService(ConnectivityManager::class.java)
        val airplane = try {
            Settings.Global.getInt(context.contentResolver, Settings.Global.AIRPLANE_MODE_ON, 0) == 1
        } catch (_: Exception) {
            false
        }
        val active = cm.activeNetwork
            ?: return Status.FAIL to (if (airplane) "Modo avião ligado" else "Sem conexão: nem Wi-Fi nem dados móveis")
        val caps = cm.getNetworkCapabilities(active)

        var status = Status.OK
        val parts = ArrayList<String>()

        if (net.type.startsWith("Wi-Fi")) {
            val info = try {
                (context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager).connectionInfo
            } catch (_: Exception) {
                null
            }
            val freq = info?.frequency ?: 0
            val band = when {
                freq >= 5925 -> "6 GHz"
                freq >= 4900 -> "5 GHz"
                freq >= 2400 -> "2,4 GHz"
                else -> null
            }
            parts.add(if (band != null) "Wi-Fi $band" else "Wi-Fi")

            val rssi = info?.rssi ?: -127
            if (rssi in -126..0) {
                val quality = when {
                    rssi >= -67 -> "bom"
                    rssi >= -75 -> "regular"
                    rssi >= -85 -> "fraco"
                    else -> "muito fraco"
                }
                if (rssi < -75) status = Status.WARN
                parts.add("$rssi dBm ($quality)")
            }
            val speed = info?.linkSpeed ?: -1
            if (speed > 0) parts.add("link $speed Mbps")
        } else if (net.type.startsWith("Móvel")) {
            status = Status.WARN
            parts.add("Dados móveis")
        } else {
            parts.add(net.type)
        }

        if (caps != null && !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) {
            if (status == Status.OK) status = Status.WARN
            parts.add("o Android marcou esta rede como sem internet")
        }
        if (caps != null && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_CAPTIVE_PORTAL)) {
            if (status == Status.OK) status = Status.WARN
            parts.add("portal cativo detectado")
        }
        if (net.type.contains("VPN")) parts.add("VPN ativa")

        return status to parts.joinToString(" · ")
    }

    private fun hasGlobalIpv6(): Boolean = try {
        val cm = context.getSystemService(ConnectivityManager::class.java)
        val props = cm.activeNetwork?.let { cm.getLinkProperties(it) }
        props?.linkAddresses?.any { la ->
            val a = la.address
            a is Inet6Address && !a.isLinkLocalAddress && !a.isLoopbackAddress &&
                (a.address[0].toInt() and 0xfe) != 0xfc
        } == true
    } catch (_: Exception) {
        false
    }

    private fun ipStep(net: NetInfo): Pair<Status, String> {
        val ip = net.ip
        val v6 = if (hasGlobalIpv6()) "IPv6: sim" else "IPv6: não"
        return when {
            !isUsableAddress(ip) -> Status.FAIL to "O celular não tem endereço IP"
            ip.startsWith("169.254.") ->
                Status.FAIL to "IP $ip (autoatribuído): o roteador não entregou IP via DHCP"
            else -> Status.OK to "IP $ip · $v6"
        }
    }

    private fun gatewayStep(net: NetInfo): Pair<Status, String> {
        if (!isUsableAddress(net.gateway)) {
            return if (net.type.startsWith("Móvel")) {
                Status.SKIP to "Sem gateway nos dados móveis"
            } else {
                Status.WARN to "Gateway não identificado"
            }
        }
        val st = quickPing(net.gateway, PING_COUNT, procs)
        return when {
            st.received == 0 -> Status.FAIL to "${net.gateway} não responde ($PING_COUNT pacotes sem resposta)"
            st.lossPct >= 10 || st.avg > 60 ->
                Status.WARN to "${net.gateway} · ${st.received}/$PING_COUNT · média ${ms(st.avg)} · perda ${st.lossPct.toInt()}%"
            else -> Status.OK to "${net.gateway} · média ${ms(st.avg)} · perda ${st.lossPct.toInt()}%"
        }
    }

    private fun ms(v: Double) = if (v < 10) String.format(Locale.US, "%.1f ms", v) else "${v.toInt()} ms"

    private fun internetStep(): Pair<Status, String> {
        val futures = INTERNET_TARGETS.map { target ->
            pool.submit(Callable { target to quickPing(target, PING_COUNT, procs) })
        }
        val results = futures.map { it.get() }
        val responding = results.filter { it.second.received > 0 }

        val line = results.joinToString(" · ") { (target, st) ->
            if (st.received > 0) "$target ${ms(st.avg)}" else "$target sem resposta"
        }
        if (responding.isEmpty()) return Status.FAIL to "Nenhum destino respondeu · $line"

        val avgLoss = responding.map { it.second.lossPct }.average()
        val avgMs = responding.map { it.second.avg }.average()
        return if (responding.size < results.size || avgLoss >= 10 || avgMs > 150) {
            Status.WARN to "$line · perda ${avgLoss.toInt()}%"
        } else {
            Status.OK to "$line · perda ${avgLoss.toInt()}%"
        }
    }

    // DNS ------------------------------------------------------------------

    private fun dnsQuery(server: String, name: String, timeoutMs: Int = 2500): Long? = try {
        val id = Random.nextInt(0xFFFF)
        val out = ByteArrayOutputStream()
        val d = DataOutputStream(out)
        d.writeShort(id)
        d.writeShort(0x0100) // consulta recursiva
        d.writeShort(1)
        d.writeShort(0)
        d.writeShort(0)
        d.writeShort(0)
        name.split('.').forEach { label ->
            d.writeByte(label.length)
            d.write(label.toByteArray())
        }
        d.writeByte(0)
        d.writeShort(1) // tipo A
        d.writeShort(1) // classe IN
        val data = out.toByteArray()

        DatagramSocket().use { socket ->
            socket.soTimeout = timeoutMs
            val address = InetAddress.getByName(server)
            val started = System.nanoTime()
            socket.send(DatagramPacket(data, data.size, address, 53))
            val buf = ByteArray(512)
            socket.receive(DatagramPacket(buf, buf.size))
            val elapsed = (System.nanoTime() - started) / 1_000_000
            val rid = ((buf[0].toInt() and 0xff) shl 8) or (buf[1].toInt() and 0xff)
            val rcode = buf[3].toInt() and 0x0f
            if (rid == id && rcode == 0) elapsed else null
        }
    } catch (_: Exception) {
        null
    }

    private fun systemResolve(name: String): Long? {
        val future = pool.submit(Callable {
            val started = System.nanoTime()
            InetAddress.getAllByName(name)
            (System.nanoTime() - started) / 1_000_000
        })
        return try {
            future.get(5, TimeUnit.SECONDS)
        } catch (_: Exception) {
            future.cancel(true)
            null
        }
    }

    private fun dnsStep(net: NetInfo): Pair<Status, String> {
        val clientIp = net.dns.takeIf { isUsableAddress(it) }
        val clientMs = clientIp?.let { dnsQuery(it, DNS_NAME) }
        val publicMs = dnsQuery("1.1.1.1", DNS_NAME)
        val systemMs = systemResolve(DNS_NAME)

        val details = ArrayList<String>()
        if (clientIp != null) details.add("DNS do cliente ($clientIp): ${clientMs?.let { "$it ms" } ?: "sem resposta"}")
        details.add("DNS público (1.1.1.1): ${publicMs?.let { "$it ms" } ?: "sem resposta"}")
        details.add("resolução do sistema: ${systemMs?.let { "$it ms" } ?: "falhou"}")
        val text = details.joinToString(" · ")

        return when {
            systemMs == null && publicMs != null && clientIp != null ->
                Status.FAIL to "O DNS do cliente não resolve, mas o público sim · $text"
            systemMs == null && publicMs != null ->
                Status.FAIL to "O celular não consegue resolver nomes · $text"
            systemMs == null -> Status.FAIL to "Nenhum DNS responde · $text"
            systemMs > 400 || (clientIp != null && clientMs != null && clientMs > 400) ->
                Status.WARN to "DNS lento · $text"
            else -> Status.OK to text
        }
    }

    // Navegação -------------------------------------------------------------

    private class HttpResult(val code: Int, val ms: Long, val body: String)

    private fun http(url: String): HttpResult {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 6000
        conn.readTimeout = 6000
        conn.instanceFollowRedirects = false
        conn.useCaches = false
        conn.setRequestProperty("User-Agent", "RealPing")
        val started = System.nanoTime()
        try {
            val code = conn.responseCode
            val stream = if (code >= 400) conn.errorStream else conn.inputStream
            val body = stream?.use { s ->
                val buf = ByteArray(4096)
                val n = s.read(buf)
                if (n > 0) String(buf, 0, n) else ""
            } ?: ""
            return HttpResult(code, (System.nanoTime() - started) / 1_000_000, body)
        } finally {
            conn.disconnect()
        }
    }

    private fun describe(e: Exception): String = when (e) {
        is UnknownHostException -> "falha de DNS"
        is SSLException -> "certificado/TLS inválido (confira data e hora do celular)"
        is SocketTimeoutException -> "tempo esgotado"
        is ConnectException -> "sem rota ou conexão recusada"
        else -> e.javaClass.simpleName
    }

    private class WebResult(val status: Status, val detail: String, val traceIp: String?)

    private fun webStep(): WebResult {
        var httpOk = false
        var portal = false
        val httpInfo: String = try {
            val r = http("http://connectivitycheck.gstatic.com/generate_204")
            if (r.code == 204) {
                httpOk = true
                "HTTP ok (${r.ms} ms)"
            } else {
                portal = true
                "HTTP devolveu ${r.code}"
            }
        } catch (e: Exception) {
            "HTTP falhou: ${describe(e)}"
        }

        var httpsOk = false
        var traceIp: String? = null
        val httpsInfo: String = try {
            val r = http("https://www.cloudflare.com/cdn-cgi/trace")
            if (r.code == 200) {
                httpsOk = true
                traceIp = r.body.lineSequence().firstOrNull { it.startsWith("ip=") }?.removePrefix("ip=")?.trim()
                "HTTPS ok (${r.ms} ms)"
            } else {
                "HTTPS devolveu ${r.code}"
            }
        } catch (e: Exception) {
            "HTTPS falhou: ${describe(e)}"
        }

        val text = "$httpInfo · $httpsInfo"
        return when {
            portal -> WebResult(Status.FAIL, "Portal cativo ou redirecionamento: a rede pode exigir login · $text", traceIp)
            httpOk && httpsOk -> WebResult(Status.OK, text, traceIp)
            httpOk || httpsOk -> WebResult(Status.WARN, text, traceIp)
            else -> WebResult(Status.FAIL, text, traceIp)
        }
    }

    private fun pubStep(traceIp: String?): Pair<Status, String> {
        var ip = traceIp
        var provider: String? = null
        var place: String? = null
        try {
            val r = http("https://ipinfo.io/json")
            if (r.code == 200) {
                val json = JSONObject(r.body)
                ip = json.optString("ip", ip ?: "").ifBlank { ip }
                provider = json.optString("org", "").ifBlank { null }
                val city = json.optString("city", "")
                val country = json.optString("country", "")
                place = listOf(city, country).filter { it.isNotBlank() }.joinToString(", ").ifBlank { null }
            }
        } catch (_: Exception) {
            // sem o provedor: mostra só o IP, se houver
        }
        if (ip == null) return Status.WARN to "Não foi possível descobrir o IP público"
        val parts = listOfNotNull("IP público $ip", provider, place)
        return Status.OK to parts.joinToString(" · ")
    }

    private fun routeStep(): Pair<Status, String> {
        val overhead = Tracer.calibrateOverhead(procs)
        val hops = ArrayList<String>()
        var lastAnswered = 0
        var lastAddress: String? = null
        var reached = false
        for (ttl in 1..8) {
            if (cancelled) break
            val p = Tracer.probe("1.1.1.1", ttl, overhead, procs)
            if (p.address != null) {
                lastAnswered = ttl
                lastAddress = p.address
            }
            hops.add("$ttl ${p.address ?: "*"}")
            if (p.reached) {
                reached = true
                break
            }
        }
        val path = hops.joinToString("  ›  ")
        return when {
            reached -> Status.OK to path
            lastAddress != null ->
                Status.FAIL to "A rota para no salto $lastAnswered ($lastAddress) · $path"
            else -> Status.FAIL to "Nenhum salto respondeu · $path"
        }
    }
}
