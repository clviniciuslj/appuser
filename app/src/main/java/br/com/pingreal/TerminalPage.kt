package br.com.pingreal

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.Editable
import android.text.InputType
import android.text.SpannableString
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextUtils
import android.text.TextWatcher
import android.text.style.ForegroundColorSpan
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Callable
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import kotlin.math.roundToInt

/**
 * Aba "Terminal": mostra a sessão de ping (que pertence ao [PingHub] e pode ter sido iniciada pelo widget),
 * mais o traceroute, o gráfico, o console e a rede atual. Fechar o app não interrompe o ping.
 */
class TerminalPage(
    private val activity: Activity,
    private val ui: UiKit,
    private val host: PageHost
) : PingHub.Observer {

    private class RowViews(val cur: TextView, val avg: TextView, val loss: TextView)

    companion object {
        private const val MAX_TARGETS = 4
        private const val MAX_HOPS = 30
        private const val PROBES_PER_HOP = 3
        private const val MAX_CONSOLE_LINES = 600
        private const val TRIM_LINES = 100
        private const val MAX_HISTORY = 12
        private const val DEFAULT_SIZE = 32
        private const val DASH = NetInfo.NONE
        private const val RULE = "────────────────────────────"
        private val WHITESPACE = Regex("\\s+")
    }

    val view: LinearLayout

    private lateinit var hostInput: EditText
    private lateinit var favButton: TextView
    private lateinit var chipsScroll: HorizontalScrollView
    private lateinit var chipsRow: LinearLayout
    private lateinit var runButton: Button
    private lateinit var traceButton: Button
    private lateinit var chart: LatencyChartView
    private lateinit var chartSubtitle: TextView
    private lateinit var latencyNow: TextView
    private lateinit var singleStats: LinearLayout
    private lateinit var multiBox: LinearLayout
    private lateinit var statMin: TextView
    private lateinit var statAvg: TextView
    private lateinit var statMax: TextView
    private lateinit var statJitter: TextView
    private lateinit var statLoss: TextView
    private lateinit var statOutages: TextView
    private lateinit var scroll: ScrollView
    private lateinit var output: TextView
    private lateinit var netLine: TextView

    private val multiRows = ArrayList<RowViews>()

    private var tracing = false
    private var lastRun = LastRun.NONE
    private var idleGlobal = true
    @Volatile private var stopRequested = false
    private var traceDebug = false

    // Só o traceroute roda aqui; o ping fica no PingHub.
    private val executor = Executors.newSingleThreadExecutor()
    private val probePool = Executors.newFixedThreadPool(PROBES_PER_HOP)
    private val activeProcesses = CopyOnWriteArrayList<Process>()

    private val buffer = SpannableStringBuilder()
    private var consoleLines = 0

    /** Última linha da sessão do hub já mostrada: ao reconectar, só o que faltar é reenviado. */
    private var lastSeq = 0L
    private val emptyStats = PingStats()
    private var netInfo = NetInfo.EMPTY

    private enum class LastRun { NONE, PING, TRACE }

    init {
        view = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        build()
        hostInput.setText(host.prefs().getString(Prefs.HOST, "") ?: "")
        refreshButtons()
        updateStats()
        // Conecta por último: se já existe uma sessão (iniciada pelo widget, ou antes de a tela ser
        // recriada), o histórico dela é reenviado e a tela mostra o mesmo teste, sem reiniciar nada.
        PingHub.attach(this, 0L)
    }

    // =====================================================================
    // Sessão do hub (PingHub.Observer)
    // =====================================================================

    override fun onStarted() {
        val sessions = PingHub.sessions
        val colors = IntArray(sessions.size) { ui.series[it % ui.series.size] }
        lastRun = LastRun.PING
        chart.placeholder = if (PingHub.running) "Aguardando respostas…" else LatencyChartView.IDLE_TEXT
        chart.setSeries(colors)
        chart.setLive(PingHub.running)
        buildStatsPanel()
        updateStats()
        refreshButtons()
        applyKeepScreen()
        // O IP em execução fica visível no campo, mesmo quando a sessão veio do widget.
        if (PingHub.running && hostInput.text.toString().trim() != PingHub.raw) {
            hostInput.setText(PingHub.raw)
        }
    }

    override fun onLine(line: PingHub.Line) {
        lastSeq = line.seq
        append(line.text, colorOf(line.kind))
    }

    override fun onSample(index: Int, time: Long, ms: Float) {
        if (ms < 0f) chart.addLoss(index, time) else chart.addSample(index, time, ms)
    }

    override fun onStats() = updateStats()

    override fun onFinished() {
        chart.setLive(false)
        chart.placeholder = LatencyChartView.IDLE_TEXT
        host.setKeepScreenOn(false)
        refreshButtons()
        updateStats()
    }

    private fun colorOf(kind: Int): Int = when {
        kind >= PingHub.SERIES -> ui.series[(kind - PingHub.SERIES) % ui.series.size]
        kind == PingHub.MUTED -> ui.muted
        kind == PingHub.WHITE -> ui.white
        kind == PingHub.AMBER -> ui.amber
        kind == PingHub.RED -> ui.red
        kind == PingHub.GREEN -> ui.green
        else -> ui.text
    }

    // =====================================================================
    // API usada pela activity
    // =====================================================================

    fun onModeChanged(idle: Boolean) {
        idleGlobal = idle
        refreshButtons()
    }

    fun onNetworkChanged() {
        netInfo = host.netInfo()
        updateNetLine()
        refreshChips()
    }

    /** Retoma o último ping depois que o sistema encerrou o app em segundo plano. */
    fun resumePing() = startPing()

    fun applyKeepScreen() {
        host.setKeepScreenOn(PingHub.running && host.prefs().getBoolean(Prefs.KEEP_SCREEN, true))
    }

    fun hideKeyboard() {
        val imm = activity.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(hostInput.windowToken, 0)
        hostInput.clearFocus()
    }

    /** Fecha a tela. O ping NÃO é interrompido: a sessão continua no hub (e no serviço). Só o traceroute para. */
    fun shutdown() {
        PingHub.detach(this)
        stopRequested = true
        activeProcesses.forEach { it.destroy() }
        executor.shutdownNow()
        probePool.shutdownNow()
    }

    // =====================================================================
    // Interface
    // =====================================================================

    private fun build() {
        // ---- entrada + favorito ----
        val inputRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        hostInput = EditText(activity).apply {
            setSingleLine(true)
            textSize = 15f
            setTextColor(ui.white)
            setHintTextColor(ui.muted)
            hint = "IP ou host  (vários: 8.8.8.8, 1.1.1.1)"
            typeface = Typeface.MONOSPACE
            background = ui.roundRect(ui.panel, 8f, ui.panelStroke)
            setPadding(ui.dp(14), 0, ui.dp(14), 0)
            inputType = InputType.TYPE_CLASS_TEXT or
                InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS or
                InputType.TYPE_TEXT_VARIATION_URI
            imeOptions = EditorInfo.IME_ACTION_GO
            setOnEditorActionListener { _, actionId, _ ->
                if (actionId == EditorInfo.IME_ACTION_GO) {
                    if (!PingHub.running && !tracing) startPing()
                    true
                } else {
                    false
                }
            }
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                override fun afterTextChanged(s: Editable?) = updateStar()
            })
        }
        favButton = ui.tv("☆", 22f, ui.muted).apply {
            gravity = Gravity.CENTER
            background = ui.rippled(ui.panel, 8f, ui.panelStroke)
            isClickable = true
            contentDescription = "Favoritar"
            setOnClickListener { toggleFavorite() }
        }
        inputRow.addView(hostInput, ui.lp(0, ui.dp(50), 1f))
        inputRow.addView(favButton, ui.lp(ui.dp(50), ui.dp(50)).apply { marginStart = ui.dp(8) })
        view.addView(inputRow, ui.lp(-1, -2))

        // ---- atalhos (gateway, DNS, favoritos, recentes) ----
        chipsRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        chipsScroll = HorizontalScrollView(activity).apply {
            isHorizontalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            addView(chipsRow, ViewGroup.LayoutParams(-2, -2))
        }
        view.addView(chipsScroll, ui.lp(-1, -2).apply { topMargin = ui.dp(8) })

        // ---- botões: INICIAR vira PARAR enquanto pinga; TRACEROUTE vira PARAR enquanto rastreia ----
        val buttons = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, ui.dp(10), 0, ui.dp(10))
        }
        runButton = ui.button("▶ INICIAR", ui.green, ui.onGreen) {
            if (PingHub.running) PingHub.stop() else startPing()
        }
        traceButton = ui.button("TRACEROUTE", Color.TRANSPARENT, ui.white, ui.panelStroke) {
            if (tracing) stopTrace() else startTraceRoute()
        }
        // Toque longo: mostra a linha crua que o ping devolveu em cada salto (para depurar o formato).
        traceButton.setOnLongClickListener {
            if (!tracing && !PingHub.running) {
                traceDebug = !traceDebug
                Toast.makeText(
                    activity,
                    if (traceDebug) "Traceroute: modo depuração ligado" else "Traceroute: modo depuração desligado",
                    Toast.LENGTH_SHORT
                ).show()
            }
            true
        }
        buttons.addView(runButton, ui.lp(0, ui.dp(46), 1.6f))
        buttons.addView(traceButton, ui.lp(0, ui.dp(46), 1f).apply { marginStart = ui.dp(8) })
        view.addView(buttons, ui.lp(-1, -2))

        // ---- gráfico ----
        val chartCard = ui.card()
        val chartHeader = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        chartSubtitle = ui.tv("últimos 60 s", 10f, ui.muted).apply { setPadding(0, ui.dp(1), 0, 0) }
        val chartTitle = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            addView(ui.label("LATÊNCIA"))
            addView(chartSubtitle)
        }
        latencyNow = ui.tv("— ms", 24f, ui.white, bold = true, mono = true)
        chartHeader.addView(chartTitle, ui.lp(0, -2, 1f))
        chartHeader.addView(latencyNow)
        chartCard.addView(chartHeader, ui.lp(-1, -2))

        chart = LatencyChartView(activity)
        chartCard.addView(chart, ui.lp(-1, ui.dp(115)).apply { topMargin = ui.dp(6); bottomMargin = ui.dp(8) })

        singleStats = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL }
        fun statCell(label: String): TextView {
            val value = ui.tv(DASH, 12f, ui.white, bold = true, mono = true).apply { maxLines = 1 }
            val cell = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                addView(ui.tv(label, 9f, ui.muted, bold = true, spacing = 0.06f))
                addView(value, ui.lp(-2, -2).apply { topMargin = ui.dp(2) })
            }
            singleStats.addView(cell, ui.lp(0, -2, 1f))
            return value
        }
        statMin = statCell("MÍN")
        statAvg = statCell("MÉD")
        statMax = statCell("MÁX")
        statJitter = statCell("JITTER")
        statLoss = statCell("PERDA")
        statOutages = statCell("QUEDAS")
        chartCard.addView(singleStats, ui.lp(-1, -2))

        multiBox = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
        }
        chartCard.addView(multiBox, ui.lp(-1, -2))
        view.addView(chartCard, ui.lp(-1, -2))

        // ---- console ----
        val consoleCard = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            background = ui.roundRect(ui.consoleBg, 10f, ui.panelStroke)
            setPadding(ui.dp(4), ui.dp(4), ui.dp(4), ui.dp(8))
        }
        val consoleHeader = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(ui.dp(10), 0, 0, 0)
        }
        consoleHeader.addView(ui.label("CONSOLE"), ui.lp(0, -2, 1f))
        consoleHeader.addView(ui.textAction("ENVIAR") { showShareDialog() })
        consoleHeader.addView(ui.textAction("LIMPAR") { clearConsole() })
        consoleCard.addView(consoleHeader, ui.lp(-1, -2))

        scroll = ScrollView(activity).apply { isFillViewport = true }
        output = ui.tv("", 12f, ui.text, mono = true).apply {
            setPadding(ui.dp(10), ui.dp(4), ui.dp(10), ui.dp(4))
            setTextIsSelectable(true)
            setLineSpacing(ui.dpf(2f), 1f)
        }
        scroll.addView(output, ViewGroup.LayoutParams(-1, -2))
        consoleCard.addView(scroll, ui.lp(-1, 0, 1f))
        view.addView(consoleCard, ui.lp(-1, 0, 1f).apply { topMargin = ui.dp(10) })

        // ---- rede atual (uma linha) ----
        netLine = ui.tv("", 11f, ui.text, mono = true).apply {
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
            setPadding(ui.dp(2), 0, ui.dp(2), 0)
        }
        view.addView(netLine, ui.lp(-1, -2).apply { topMargin = ui.dp(8) })
    }

    private fun refreshButtons() {
        when {
            PingHub.running -> {
                runButton.text = "■ PARAR"
                ui.danger(runButton)
                ui.setEnabled(runButton, true)
                traceButton.text = "TRACEROUTE"
                ui.secondary(traceButton)
                ui.setEnabled(traceButton, false)
            }
            tracing -> {
                runButton.text = "▶ INICIAR"
                ui.primary(runButton)
                ui.setEnabled(runButton, false)
                traceButton.text = "■ PARAR"
                ui.danger(traceButton)
                ui.setEnabled(traceButton, true)
            }
            else -> {
                runButton.text = "▶ INICIAR"
                ui.primary(runButton)
                ui.setEnabled(runButton, idleGlobal)
                traceButton.text = "TRACEROUTE"
                ui.secondary(traceButton)
                ui.setEnabled(traceButton, idleGlobal)
            }
        }
    }

    /** "Wi-Fi 192.168.0.23 · GW 192.168.0.1 · DNS 8.8.8.8", com rótulos em cinza (GW/DNS juntos se iguais). */
    private fun updateNetLine() {
        val sb = SpannableStringBuilder()
        fun part(label: String?, value: String) {
            if (sb.isNotEmpty()) sb.append("  ·  ")
            if (label != null) {
                val start = sb.length
                sb.append(label).append(' ')
                sb.setSpan(ForegroundColorSpan(ui.muted), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
            sb.append(value)
        }
        val n = netInfo
        part(null, "${n.type} ${n.ip}".trim())
        if (n.gateway == n.dns) {
            part("GW/DNS", n.gateway)
        } else {
            part("GW", n.gateway)
            part("DNS", n.dns)
        }
        netLine.text = sb
    }

    // =====================================================================
    // Estatísticas (uma linha de resumo ou uma linha por alvo)
    // =====================================================================

    private fun buildStatsPanel() {
        val sessions = PingHub.sessions
        val multi = sessions.size > 1
        singleStats.visibility = if (multi) View.GONE else View.VISIBLE
        multiBox.visibility = if (multi) View.VISIBLE else View.GONE
        latencyNow.visibility = if (multi) View.GONE else View.VISIBLE
        chartSubtitle.text = if (multi) "${sessions.size} alvos · últimos 60 s" else "últimos 60 s"

        multiBox.removeAllViews()
        multiRows.clear()
        if (!multi) return

        val header = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL }
        header.addView(View(activity), ui.lp(0, 1, 1f))
        for ((text, width) in listOf("ATUAL" to 56, "MÉD" to 56, "PERDA" to 44)) {
            header.addView(
                ui.tv(text, 9f, ui.muted, bold = true, spacing = 0.08f).apply { gravity = Gravity.END },
                ui.lp(ui.dp(width), -2)
            )
        }
        multiBox.addView(header, ui.lp(-1, -2))

        for (s in sessions) {
            val row = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, ui.dp(3), 0, ui.dp(3))
            }
            val dot = View(activity).apply {
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(s.color)
                }
            }
            val name = ui.tv(s.host, 11f, ui.white, mono = true).apply {
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
            }
            val cur = ui.tv(DASH, 11f, ui.white, bold = true, mono = true).apply { gravity = Gravity.END }
            val avg = ui.tv(DASH, 11f, ui.white, mono = true).apply { gravity = Gravity.END }
            val loss = ui.tv(DASH, 11f, ui.white, mono = true).apply { gravity = Gravity.END }
            row.addView(dot, ui.lp(ui.dp(8), ui.dp(8)))
            row.addView(name, ui.lp(0, -2, 1f).apply { marginStart = ui.dp(8) })
            row.addView(cur, ui.lp(ui.dp(56), -2))
            row.addView(avg, ui.lp(ui.dp(56), -2))
            row.addView(loss, ui.lp(ui.dp(44), -2))
            multiBox.addView(row, ui.lp(-1, -2))
            multiRows.add(RowViews(cur, avg, loss))
        }
    }

    private fun updateStats() {
        val sessions = PingHub.sessions
        if (sessions.size > 1) updateMultiStats(sessions) else updateSingleStats(sessions.firstOrNull()?.stats ?: emptyStats)
    }

    private fun updateSingleStats(s: PingStats) {
        when {
            s.lastWasLoss -> {
                latencyNow.text = "perdido"
                latencyNow.setTextColor(ui.red)
            }
            s.last >= 0 -> {
                latencyNow.text = "${ui.formatMs(s.last)} ms"
                latencyNow.setTextColor(LatencyChartView.colorFor(s.last))
            }
            else -> {
                latencyNow.text = "— ms"
                latencyNow.setTextColor(ui.white)
            }
        }
        statMin.text = if (s.received > 0) "${ui.formatMs(s.min)}ms" else DASH
        statAvg.text = if (s.received > 0) "${ui.formatMs(s.avg)}ms" else DASH
        statMax.text = if (s.received > 0) "${ui.formatMs(s.max)}ms" else DASH
        statJitter.text = if (s.jitterSamples > 0) "${ui.formatMs(s.jitter)}ms" else DASH
        statLoss.text = if (s.sent > 0) "${s.lossPct.roundToInt()}%" else DASH
        statLoss.setTextColor(if (s.lost > 0) ui.red else ui.white)
        statOutages.text = if (s.sent > 0) s.outages.toString() else DASH
        statOutages.setTextColor(if (s.outages > 0) ui.red else ui.white)
    }

    private fun updateMultiStats(sessions: List<PingSession>) {
        for ((i, s) in sessions.withIndex()) {
            val row = multiRows.getOrNull(i) ?: continue
            val st = s.stats
            when {
                st.lastWasLoss -> {
                    row.cur.text = "perdido"
                    row.cur.setTextColor(ui.red)
                }
                st.last >= 0 -> {
                    row.cur.text = "${ui.formatMs(st.last)}ms"
                    row.cur.setTextColor(LatencyChartView.colorFor(st.last))
                }
                else -> {
                    row.cur.text = DASH
                    row.cur.setTextColor(ui.white)
                }
            }
            row.avg.text = if (st.received > 0) "${ui.formatMs(st.avg)}ms" else DASH
            row.loss.text = if (st.sent > 0) "${st.lossPct.roundToInt()}%" else DASH
            row.loss.setTextColor(if (st.lost > 0) ui.red else ui.white)
        }
    }

    // =====================================================================
    // Favoritos e histórico
    // =====================================================================

    private fun loadList(key: String): MutableList<String> =
        (host.prefs().getString(key, "") ?: "").split('\n').filter { it.isNotBlank() }.toMutableList()

    private fun saveList(key: String, list: List<String>) {
        host.prefs().edit().putString(key, list.joinToString("\n")).apply()
    }

    private fun addHistory(entry: String) {
        val list = loadList(Prefs.HISTORY)
        list.remove(entry)
        list.add(0, entry)
        saveList(Prefs.HISTORY, list.take(MAX_HISTORY))
        refreshChips()
    }

    private fun toggleFavorite() {
        val entry = hostInput.text.toString().trim()
        if (entry.isEmpty()) {
            Toast.makeText(activity, "Digite um alvo para favoritar", Toast.LENGTH_SHORT).show()
            return
        }
        val favs = loadList(Prefs.FAVORITES)
        if (!favs.remove(entry)) favs.add(0, entry)
        saveList(Prefs.FAVORITES, favs)
        refreshChips()
    }

    private fun updateStar() {
        if (!::favButton.isInitialized) return
        val fav = hostInput.text.toString().trim().let { it.isNotEmpty() && it in loadList(Prefs.FAVORITES) }
        favButton.text = if (fav) "★" else "☆"
        favButton.setTextColor(if (fav) ui.amber else ui.muted)
    }

    private fun chip(label: String, color: Int, onClick: () -> Unit, onLongClick: (() -> Unit)? = null): TextView =
        ui.tv(label, 11f, color, mono = true).apply {
            maxLines = 1
            setPadding(ui.dp(11), ui.dp(6), ui.dp(11), ui.dp(6))
            background = ui.rippled(ui.panel, 8f, ui.panelStroke)
            isClickable = true
            setOnClickListener { onClick() }
            if (onLongClick != null) setOnLongClickListener { onLongClick(); true }
        }

    private fun refreshChips() {
        chipsRow.removeAllViews()
        fun add(view: View) = chipsRow.addView(view, ui.lp(-2, -2).apply { marginEnd = ui.dp(6) })
        fun short(s: String) = if (s.length > 24) s.take(23) + "…" else s
        fun fill(entry: String) {
            hostInput.setText(entry)
            hostInput.setSelection(entry.length)
        }

        if (isUsableAddress(netInfo.gateway)) add(chip("⌂ Gateway", ui.white, { addTarget(netInfo.gateway) }))
        if (isUsableAddress(netInfo.dns)) add(chip("DNS", ui.white, { addTarget(netInfo.dns) }))

        val favs = loadList(Prefs.FAVORITES)
        for (f in favs) {
            add(chip("★ ${short(f)}", ui.amber, { fill(f) }, {
                favs.remove(f)
                saveList(Prefs.FAVORITES, favs)
                Toast.makeText(activity, "Favorito removido", Toast.LENGTH_SHORT).show()
                refreshChips()
            }))
        }
        for (h in loadList(Prefs.HISTORY).filter { it !in favs }) {
            add(chip(short(h), ui.muted, { fill(h) }, {
                saveList(Prefs.HISTORY, loadList(Prefs.HISTORY).filter { it != h })
                Toast.makeText(activity, "Removido do histórico", Toast.LENGTH_SHORT).show()
                refreshChips()
            }))
        }

        chipsScroll.visibility = if (chipsRow.childCount == 0) View.GONE else View.VISIBLE
        updateStar()
    }

    /** Atalho de gateway/DNS: preenche o campo, ou acrescenta como mais um alvo se já houver texto. */
    private fun addTarget(ip: String) {
        val current = hostInput.text.toString().trim()
        val text = when {
            current.isEmpty() -> ip
            current.split(',').any { it.trim().split(WHITESPACE).firstOrNull() == ip } -> current
            else -> "$current, $ip"
        }
        hostInput.setText(text)
        hostInput.setSelection(text.length)
    }

    // =====================================================================
    // Console
    // =====================================================================

    private fun append(line: String, color: Int = ui.text) {
        val atBottom = scroll.getChildAt(0).let { child ->
            child == null || child.bottom - (scroll.height + scroll.scrollY) < ui.dp(48)
        }
        val span = SpannableString("$line\n")
        span.setSpan(ForegroundColorSpan(color), 0, span.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        buffer.append(span)
        output.append(span)
        consoleLines++
        if (consoleLines > MAX_CONSOLE_LINES) trimConsole()
        if (atBottom) scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
    }

    private fun trimConsole() {
        var cut = 0
        repeat(TRIM_LINES) {
            val idx = buffer.indexOf('\n', cut)
            if (idx >= 0) cut = idx + 1
        }
        if (cut > 0) {
            buffer.delete(0, cut)
            consoleLines -= TRIM_LINES
            output.text = buffer
        }
    }

    private fun clearConsole() {
        buffer.clear()
        consoleLines = 0
        output.text = ""
    }

    private fun copyConsole() {
        if (buffer.isEmpty()) {
            Toast.makeText(activity, "Console vazio", Toast.LENGTH_SHORT).show()
            return
        }
        host.copyText(buffer.toString())
    }

    // =====================================================================
    // Ping (a sessão pertence ao PingHub)
    // =====================================================================

    /**
     * Interpreta um alvo no formato `host [-t] [-n N] [-l TAMANHO]` (também aceita `-c` e `-s`).
     * Mostra o erro no console e devolve null se inválido.
     */
    private fun parseSegment(segment: String): PingTarget? {
        val parts = segment.split(WHITESPACE).filter { it.isNotBlank() }
        val target = parseHost(parts.firstOrNull().orEmpty())
        if (target == null) {
            append("Endereço inválido: ${parts.firstOrNull().orEmpty()}", ui.red)
            return null
        }

        var continuous = true
        var count = 4
        var size = DEFAULT_SIZE
        var index = 1

        fun number(range: IntRange, error: String): Int? {
            val value = parts.getOrNull(index + 1)?.toIntOrNull()
            if (value == null || value !in range) {
                append(error, ui.red)
                return null
            }
            return value
        }

        while (index < parts.size) {
            when (parts[index].lowercase()) {
                "-t" -> {
                    continuous = true
                    index++
                }
                "-n", "-c" -> {
                    count = number(1..10000, "Quantidade deve estar entre 1 e 10000.") ?: return null
                    continuous = false
                    index += 2
                }
                "-l", "-s" -> {
                    size = number(1..65500, "Tamanho deve estar entre 1 e 65500 bytes.") ?: return null
                    index += 2
                }
                else -> {
                    append("Parâmetro desconhecido: ${parts[index]}", ui.red)
                    return null
                }
            }
        }
        return PingTarget(target, continuous, count, size)
    }

    private fun startPing() {
        if (PingHub.running || tracing) return

        val raw = hostInput.text.toString().trim()
        val segments = raw.split(',').map { it.trim() }.filter { it.isNotEmpty() }

        if (segments.isEmpty()) {
            append("Informe um IP ou host.", ui.amber)
            return
        }
        if (segments.size > MAX_TARGETS) {
            append("No máximo $MAX_TARGETS alvos ao mesmo tempo.", ui.red)
            return
        }

        val targets = ArrayList<PingTarget>()
        for (segment in segments) targets.add(parseSegment(segment) ?: return)

        host.requestNotificationPermission()
        hideKeyboard()
        host.prefs().edit().putString(Prefs.HOST, raw).apply()
        addHistory(raw)

        // O hub imprime o cabeçalho, cria as sessões e avisa esta tela (onStarted) e o serviço.
        if (!PingHub.start(activity, raw, targets)) {
            append("Já existe um teste em andamento.", ui.amber)
        }
    }

    // =====================================================================
    // Compartilhar
    // =====================================================================

    private fun reportTitle(): String = "Real Ping — relatório de ping"

    private fun reportBody(): String {
        val sb = StringBuilder()
        sb.append("Rede: ${netInfo.type} · IP ${netInfo.ip}\n")
        sb.append("Gateway ${netInfo.gateway} · DNS ${netInfo.dns}\n")
        for (s in PingHub.sessions) {
            val st = s.stats
            sb.append('\n')
            sb.append("▸ ").append(s.host).append('\n')
            sb.append("  Enviados ${st.sent} · Recebidos ${st.received} · Perda ${st.lossPct.roundToInt()}%\n")
            if (st.received > 0) {
                sb.append(
                    "  Mín ${ui.formatMs(st.min)}ms · Méd ${ui.formatMs(st.avg)}ms · " +
                        "Máx ${ui.formatMs(st.max)}ms · Jitter ${ui.formatMs(st.jitter)}ms\n"
                )
            } else {
                sb.append("  Nenhuma resposta\n")
            }
            if (st.outages > 0) {
                sb.append("  Quedas ${st.outages} · maior ${formatDuration(st.longestOutageMs)}\n")
            }
        }
        return sb.toString().trimEnd()
    }

    private fun reportText(): String {
        val date = SimpleDateFormat("dd/MM/yyyy HH:mm:ss", Locale("pt", "BR")).format(Date())
        return "${reportTitle()}\n$date\n\n${reportBody()}"
    }

    private fun showShareDialog() {
        if (buffer.isEmpty() && PingHub.sessions.isEmpty()) {
            Toast.makeText(activity, "Nada para enviar ainda", Toast.LENGTH_SHORT).show()
            return
        }
        // Depois de um ping há um resumo; depois de um traceroute (ou nada) só o texto do console.
        val pingReport = PingHub.sessions.isNotEmpty() && lastRun == LastRun.PING
        val options = if (pingReport) {
            arrayOf("Enviar resumo (texto)", "Enviar imagem (gráfico + estatísticas)", "Copiar console")
        } else {
            arrayOf("Enviar console", "Copiar console")
        }
        AlertDialog.Builder(activity)
            .setTitle("Enviar")
            .setItems(options) { _, which ->
                when {
                    which == options.size - 1 -> copyConsole()
                    pingReport && which == 0 -> host.shareText(reportText())
                    pingReport && which == 1 -> host.shareImage(reportTitle(), reportBody(), chart)
                    else -> host.shareText(buffer.toString())
                }
            }
            .show()
    }

    // =====================================================================
    // Traceroute
    // =====================================================================

    private fun stopTrace() {
        if (!tracing) return
        stopRequested = true
        append("Interrompendo...", ui.muted)
        activeProcesses.forEach { it.destroy() }
    }

    private fun startTraceRoute() {
        if (tracing || PingHub.running) return

        val firstSegment = hostInput.text.toString().split(',').firstOrNull().orEmpty().trim()
        val target = parseHost(firstSegment.split(WHITESPACE).firstOrNull().orEmpty())
        if (target == null) {
            append("Informe um IP ou hostname válido para o traceroute.", ui.amber)
            return
        }
        if (!host.acquire("TRACEROUTE", ui.amber)) {
            append("Já existe um teste em andamento.", ui.amber)
            return
        }

        host.prefs().edit().putString(Prefs.HOST, target).apply()
        hideKeyboard()

        append("")
        append(RULE, ui.muted)
        append("> traceroute $target", ui.white)
        append("Rastreando rota para $target (máx. $MAX_HOPS saltos, $PROBES_PER_HOP sondas por salto)", ui.muted)
        append("")

        stopRequested = false
        tracing = true
        refreshButtons()
        executor.execute { runTrace(target) }
    }

    private fun runTrace(target: String) {
        var failure: String? = null
        var reached = false
        val debug = traceDebug

        try {
            val overhead = Tracer.calibrateOverhead(activeProcesses)

            for (ttl in 1..MAX_HOPS) {
                if (stopRequested) break

                val futures = (1..PROBES_PER_HOP).map {
                    probePool.submit(Callable<Tracer.Probe> { Tracer.probe(target, ttl, overhead, activeProcesses) })
                }
                val probes = futures.map { it.get() }
                if (stopRequested) break

                val hardRaw = probes.firstNotNullOfOrNull { p ->
                    p.raw?.takeIf { PingSession.isHardError(it.lowercase()) }
                }
                if (hardRaw != null) {
                    failure = hardRaw
                    break
                }

                val addresses = probes.mapNotNull { it.address }.distinct()
                val cells = probes.joinToString("") { p ->
                    val text = p.ms?.let { if (it < 1.0) "<1 ms" else "${it.roundToInt()} ms" } ?: "*"
                    text.padStart(8)
                }
                val where = if (addresses.isEmpty()) "Esgotado o tempo limite" else addresses.joinToString(", ")
                val hopReached = probes.any { it.reached }
                val color = if (addresses.isEmpty()) ui.amber else ui.text
                val rawNotes: List<String> = when {
                    debug -> probes.mapNotNull { it.line }.distinct()
                    addresses.isEmpty() -> listOfNotNull(probes.firstNotNullOfOrNull { it.raw })
                    else -> emptyList()
                }

                host.post {
                    append("${ttl.toString().padStart(2)}$cells  $where", color)
                    rawNotes.forEach { append("      ↳ $it", ui.muted) }
                }

                if (hopReached) {
                    reached = true
                    break
                }
            }
        } catch (e: Exception) {
            if (!stopRequested) {
                failure = (e.cause?.message ?: e.message) ?: "falha ao executar as sondagens do traceroute"
            }
        }

        host.post {
            when {
                stopRequested -> append("Traceroute interrompido pelo usuário.", ui.muted)
                failure != null -> append("Falha no traceroute: $failure", ui.red)
                reached -> append("Destino alcançado.", ui.green)
                else -> append("Rastreamento encerrado ($MAX_HOPS saltos sem alcançar o destino).", ui.amber)
            }
            append(RULE, ui.muted)
            tracing = false
            lastRun = LastRun.TRACE
            host.release()
            refreshButtons()
        }
    }
}
