package br.com.pingreal

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Color
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/** Aba "Diagnóstico": diagnóstico completo em camadas e teste de velocidade. */
class DiagnosticPage(
    private val activity: Activity,
    private val ui: UiKit,
    private val host: PageHost
) {
    private class Row(val glyph: TextView, val detail: TextView)

    val view: View

    private val executor = Executors.newSingleThreadExecutor()
    private val rows = HashMap<String, Row>()

    private var engine: DiagnosticEngine? = null
    private var speed: SpeedTest? = null
    private var diagRunning = false
    private var speedRunning = false
    private var idleGlobal = true

    private var lastReport: DiagnosticReport? = null
    private var lastNet: NetInfo? = null
    private var lastSpeed: SpeedResult? = null
    private var lastAt: Date? = null

    private lateinit var diagButton: Button
    private lateinit var speedButton: Button
    private lateinit var shareButton: Button
    private lateinit var gauge: GaugeView
    private lateinit var speedDown: TextView
    private lateinit var speedUp: TextView
    private lateinit var speedPing: TextView
    private lateinit var speedJitter: TextView

    init {
        val content = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        fun add(v: View) = content.addView(v, ui.lp(-1, -2).apply { bottomMargin = ui.dp(12) })

        add(buildDiagnosticCard())
        add(buildSpeedCard())

        shareButton = ui.button("ENVIAR RELATÓRIO", Color.TRANSPARENT, ui.white, ui.panelStroke) { showShareDialog() }
        shareButton.visibility = View.GONE
        content.addView(shareButton, ui.lp(-1, ui.dp(46)).apply { bottomMargin = ui.dp(12) })

        view = ScrollView(activity).apply {
            isVerticalScrollBarEnabled = false
            addView(content)
        }
        refreshButtons()
    }

    // ------------------------------------------------------------------ construção

    private fun buildDiagnosticCard(): View {
        val card = ui.card()
        card.addView(ui.label("DIAGNÓSTICO"))
        diagButton = ui.button("▶ INICIAR DIAGNÓSTICO", ui.green, ui.onGreen) {
            if (diagRunning) engine?.cancel() else startDiagnostic()
        }
        card.addView(diagButton, ui.lp(-1, ui.dp(46)).apply { topMargin = ui.dp(10); bottomMargin = ui.dp(6) })

        for ((id, title) in DiagnosticEngine.STEP_TITLES) {
            val glyph = ui.tv("○", 16f, ui.muted, bold = true).apply { gravity = Gravity.CENTER_HORIZONTAL }
            val detail = ui.tv("", 11f, ui.muted).apply { visibility = View.GONE }
            val text = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                addView(ui.tv(title, 13f, ui.white, bold = true))
                addView(detail, ui.lp(-1, -2).apply { topMargin = ui.dp(1) })
            }
            val row = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, ui.dp(6), 0, ui.dp(6))
            }
            row.addView(glyph, ui.lp(ui.dp(26), -2))
            row.addView(text, ui.lp(0, -2, 1f))
            card.addView(row, ui.lp(-1, -2))
            rows[id] = Row(glyph, detail)
        }
        return card
    }

    private fun buildSpeedCard(): View {
        val card = ui.card()
        card.addView(ui.label("TESTE DE VELOCIDADE"))

        gauge = GaugeView(activity)
        card.addView(gauge, ui.lp(-1, -2).apply { topMargin = ui.dp(8) })

        val results = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL }
        fun cell(label: String): TextView {
            val value = ui.tv("—", 14f, ui.white, bold = true, mono = true)
            val box = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                addView(ui.tv(label, 9f, ui.muted, bold = true, spacing = 0.08f))
                addView(value, ui.lp(-2, -2).apply { topMargin = ui.dp(2) })
            }
            results.addView(box, ui.lp(0, -2, 1f))
            return value
        }
        speedDown = cell("↓ DOWNLOAD")
        speedUp = cell("↑ UPLOAD")
        speedPing = cell("PING")
        speedJitter = cell("JITTER")
        card.addView(results, ui.lp(-1, -2).apply { topMargin = ui.dp(10) })

        speedButton = ui.button("▶ TESTAR VELOCIDADE", Color.TRANSPARENT, ui.white, ui.panelStroke) {
            if (speedRunning) speed?.cancel() else confirmSpeed()
        }
        card.addView(speedButton, ui.lp(-1, ui.dp(46)).apply { topMargin = ui.dp(14) })
        return card
    }

    // ------------------------------------------------------------------ estado

    fun onModeChanged(idle: Boolean) {
        idleGlobal = idle
        refreshButtons()
    }

    private fun refreshButtons() {
        if (diagRunning) {
            diagButton.text = "■ PARAR"
            ui.danger(diagButton)
        } else {
            diagButton.text = "▶ INICIAR DIAGNÓSTICO"
            ui.primary(diagButton)
        }
        ui.setEnabled(diagButton, diagRunning || idleGlobal)

        if (speedRunning) {
            speedButton.text = "■ PARAR"
            ui.danger(speedButton)
        } else {
            speedButton.text = "▶ TESTAR VELOCIDADE"
            ui.secondary(speedButton)
        }
        ui.setEnabled(speedButton, speedRunning || idleGlobal)
    }

    fun shutdown() {
        engine?.cancel()
        speed?.cancel()
        executor.shutdownNow()
    }

    // ------------------------------------------------------------------ diagnóstico

    private fun glyph(s: Status) = when (s) {
        Status.PENDING -> "○"
        Status.RUNNING -> "◔"
        Status.OK -> "✔"
        Status.WARN -> "▲"
        Status.FAIL -> "✘"
        Status.SKIP -> "–"
    }

    private fun colorOf(s: Status) = when (s) {
        Status.PENDING, Status.SKIP -> ui.muted
        Status.RUNNING -> ui.white
        Status.OK -> ui.green
        Status.WARN -> ui.amber
        Status.FAIL -> ui.red
    }

    private fun updateRow(step: StepOutcome) {
        val row = rows[step.id] ?: return
        row.glyph.text = glyph(step.status)
        row.glyph.setTextColor(colorOf(step.status))
        val text = if (step.status == Status.RUNNING) "verificando…" else step.detail
        row.detail.text = text
        row.detail.visibility = if (text.isBlank()) View.GONE else View.VISIBLE
        row.detail.setTextColor(
            when (step.status) {
                Status.WARN -> ui.amber
                Status.FAIL -> ui.red
                else -> ui.muted
            }
        )
    }

    private fun resetRows() {
        for ((id, title) in DiagnosticEngine.STEP_TITLES) updateRow(StepOutcome(id, title, Status.PENDING, ""))
    }

    private fun startDiagnostic() {
        if (diagRunning || speedRunning) return
        if (!host.acquire("DIAGNÓSTICO", ui.white)) return

        diagRunning = true
        refreshButtons()
        resetRows()
        shareButton.visibility = View.GONE

        val net = host.netInfo()
        lastNet = net
        lastAt = Date()
        lastReport = null

        val eng = DiagnosticEngine(activity)
        engine = eng
        executor.execute {
            val report = try {
                eng.run(net) { step -> host.post { updateRow(step) } }
            } catch (_: Exception) {
                null
            }
            host.post { onDiagnosticDone(report, eng.cancelled) }
        }
    }

    private fun onDiagnosticDone(report: DiagnosticReport?, cancelled: Boolean) {
        diagRunning = false
        host.release()
        refreshButtons()

        if (cancelled || report == null) {
            for ((id, title) in DiagnosticEngine.STEP_TITLES) {
                val row = rows[id] ?: continue
                if (row.glyph.text.toString().let { it == glyph(Status.RUNNING) || it == glyph(Status.PENDING) }) {
                    updateRow(StepOutcome(id, title, Status.SKIP, if (cancelled) "Interrompido" else "Falhou"))
                }
            }
            if (!cancelled) Toast.makeText(activity, "O diagnóstico falhou", Toast.LENGTH_SHORT).show()
            return
        }

        lastReport = report
        shareButton.visibility = View.VISIBLE
    }

    // ------------------------------------------------------------------ velocidade

    private fun confirmSpeed() {
        if (!idleGlobal) return
        if (host.netInfo().type.startsWith("Móvel")) {
            AlertDialog.Builder(activity)
                .setTitle("Dados móveis")
                .setMessage("O teste de velocidade baixa e envia centenas de MB. Continuar usando dados móveis?")
                .setPositiveButton("Continuar") { _, _ -> startSpeed() }
                .setNegativeButton("Cancelar", null)
                .show()
        } else {
            startSpeed()
        }
    }

    private fun startSpeed() {
        if (diagRunning || speedRunning) return
        if (!host.acquire("VELOCIDADE", ui.white)) return

        speedRunning = true
        refreshButtons()
        shareButton.visibility = View.GONE
        for (t in listOf(speedDown, speedUp, speedPing, speedJitter)) t.text = "—"
        lastNet = host.netInfo()
        lastAt = Date()

        val test = SpeedTest()
        speed = test
        executor.execute {
            val result = try {
                test.run(object : SpeedTest.Listener {
                    override fun onPhase(phase: Int) {
                        host.post {
                            when (phase) {
                                SpeedTest.PHASE_LATENCY -> {
                                    gauge.setPhase("PING", ui.muted)
                                    gauge.setText("…", "ms")
                                }
                                SpeedTest.PHASE_DOWN -> gauge.setPhase("↓ DOWNLOAD", ui.cyan)
                                else -> gauge.setPhase("↑ UPLOAD", ui.violet)
                            }
                        }
                    }

                    override fun onLatency(pingMs: Double, jitterMs: Double) {
                        host.post {
                            if (pingMs > 0) {
                                speedPing.text = "${ui.formatMs(pingMs)} ms"
                                speedJitter.text = "${ui.formatMs(jitterMs)} ms"
                                gauge.setText(ui.formatMs(pingMs), "ms")
                            }
                        }
                    }

                    override fun onProgress(phase: Int, mbps: Double, fraction: Float) {
                        if (phase == SpeedTest.PHASE_LATENCY) return
                        host.post {
                            gauge.setSpeed(mbps)
                            (if (phase == SpeedTest.PHASE_DOWN) speedDown else speedUp).text = ui.formatMbps(mbps)
                        }
                    }
                })
            } catch (_: Exception) {
                null
            }
            host.post { onSpeedDone(result, test.cancelled) }
        }
    }

    private fun onSpeedDone(result: SpeedResult?, cancelled: Boolean) {
        speedRunning = false
        host.release()
        refreshButtons()

        if (result == null) {
            gauge.settle("FALHOU", "—", "Mbps")
            Toast.makeText(activity, "O teste de velocidade falhou", Toast.LENGTH_SHORT).show()
            return
        }
        speedDown.text = if (result.downMbps > 0) ui.formatMbps(result.downMbps) else "falhou"
        speedUp.text = if (result.upMbps > 0) ui.formatMbps(result.upMbps) else if (cancelled) "—" else "falhou"

        if (cancelled) {
            gauge.settle("INTERROMPIDO", "—", "Mbps")
            return
        }
        gauge.settle("CONCLUÍDO", if (result.downMbps > 0) ui.formatMbps(result.downMbps) else "—", "Mbps ↓")

        if (result.downMbps > 0 || result.upMbps > 0) {
            lastSpeed = result
            shareButton.visibility = View.VISIBLE
        }
    }

    // ------------------------------------------------------------------ relatório

    private fun title() = "Real Ping — diagnóstico completo"

    private fun body(): String {
        val sb = StringBuilder()
        val net = lastNet
        if (net != null) {
            sb.append("Rede: ${net.type} · IP ${net.ip}\n")
            sb.append("Gateway ${net.gateway} · DNS ${net.dns}\n")
        }

        val report = lastReport
        if (report != null) {
            sb.append("\nETAPAS\n")
            for (s in report.steps) {
                if (s.status == Status.PENDING) continue
                sb.append("${glyph(s.status)} ${s.title}")
                if (s.detail.isNotBlank()) sb.append(": ${s.detail}")
                sb.append('\n')
            }
        }

        val speedResult = lastSpeed
        if (speedResult != null) {
            sb.append("\nVELOCIDADE\n")
            sb.append("Download ${ui.formatMbps(speedResult.downMbps)} Mbps · Upload ${ui.formatMbps(speedResult.upMbps)} Mbps\n")
            sb.append("Ping ${ui.formatMs(speedResult.pingMs)} ms · Jitter ${ui.formatMs(speedResult.jitterMs)} ms\n")
        }
        return sb.toString().trimEnd()
    }

    private fun fullText(): String {
        val date = SimpleDateFormat("dd/MM/yyyy HH:mm:ss", Locale("pt", "BR")).format(lastAt ?: Date())
        return "${title()}\n$date\n\n${body()}"
    }

    private fun showShareDialog() {
        if (lastReport == null && lastSpeed == null) {
            Toast.makeText(activity, "Rode o diagnóstico ou o teste de velocidade primeiro", Toast.LENGTH_SHORT).show()
            return
        }
        AlertDialog.Builder(activity)
            .setTitle("Enviar relatório")
            .setItems(arrayOf("Enviar texto", "Enviar imagem", "Copiar texto")) { _, which ->
                when (which) {
                    0 -> host.shareText(fullText())
                    1 -> host.shareImage(title(), body(), null)
                    else -> host.copyText(fullText())
                }
            }
            .show()
    }
}
