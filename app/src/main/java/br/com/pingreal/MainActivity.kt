package br.com.pingreal

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.StaticLayout
import android.text.TextPaint
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.max

/**
 * Moldura do app: cabeçalho, abas, barra inferior, rede, compartilhamento e "um teste por vez".
 * O ping em si não pertence a esta tela: vive no [PingHub] e sobrevive a ela.
 */
class MainActivity : Activity(), PageHost {

    private companion object {
        const val STATE_RESUME_PING = "resume_ping"
        const val REQUEST_NOTIFICATIONS = 1
    }

    private lateinit var ui: UiKit
    private lateinit var terminal: TerminalPage
    private lateinit var diagnostic: DiagnosticPage
    private lateinit var root: LinearLayout
    private lateinit var nav: LinearLayout
    private lateinit var statusText: TextView
    private lateinit var statusDot: GradientDrawable
    private val pages = ArrayList<View>()
    private val tabIcons = ArrayList<NavIconView>()
    private val tabLabels = ArrayList<TextView>()

    /** Teste desta tela em andamento (traceroute, diagnóstico, velocidade): rótulo e cor do status. */
    private var appTest: Pair<String, Int>? = null
    private var cachedNet = NetInfo.EMPTY

    private val handler = Handler(Looper.getMainLooper())
    private val networkRefresh = Runnable { refreshNetwork() }

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) = scheduleNetworkRefresh()
        override fun onLost(network: Network) = scheduleNetworkRefresh()
        override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) = scheduleNetworkRefresh()
        override fun onLinkPropertiesChanged(network: Network, props: LinkProperties) = scheduleNetworkRefresh()
    }
    private var callbackRegistered = false

    /** Só acompanha se há ping em andamento (iniciado aqui ou pelo widget) para o status e o bloqueio de outros testes. */
    private val pingState = object : PingHub.Observer {
        override fun onStarted() = syncBusy()
        override fun onFinished() = syncBusy()
    }

    // =====================================================================
    // Ciclo de vida
    // =====================================================================

    @Suppress("DEPRECATION")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ui = UiKit(this)
        window.statusBarColor = ui.bg
        window.navigationBarColor = ui.bg
        if (Build.VERSION.SDK_INT >= 30) window.setDecorFitsSystemWindows(false)

        buildUi()
        setContentView(root)
        selectTab(0)
        refreshNetwork()

        // Sem argumento de linhas: este observador só quer saber do estado, não do histórico.
        PingHub.attach(pingState, Long.MAX_VALUE)
        syncBusy()

        // O processo foi encerrado pelo sistema com um ping ativo: aqui o hub está vazio, então retoma.
        // (Se a tela só foi recriada no mesmo processo, o hub continua rodando e a tela se reconecta sozinha.)
        if (savedInstanceState?.getBoolean(STATE_RESUME_PING) == true && !PingHub.running) {
            handler.post { terminal.resumePing() }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        if (PingHub.running) outState.putBoolean(STATE_RESUME_PING, true)
    }

    override fun onStart() {
        super.onStart()
        try {
            getSystemService(ConnectivityManager::class.java).registerDefaultNetworkCallback(networkCallback)
            callbackRegistered = true
        } catch (_: Exception) {
            callbackRegistered = false
        }
        refreshNetwork()
    }

    override fun onStop() {
        if (callbackRegistered) {
            try {
                getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(networkCallback)
            } catch (_: Exception) {
            }
            callbackRegistered = false
        }
        super.onStop()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        PingHub.detach(pingState)
        // Testes desta tela morrem com ela; o ping do hub NÃO (segue no serviço).
        PingHub.externalBusy = false
        if (::terminal.isInitialized) terminal.shutdown()
        if (::diagnostic.isInitialized) diagnostic.shutdown()
        super.onDestroy()
    }

    // =====================================================================
    // PageHost
    // =====================================================================

    override fun acquire(label: String, color: Int): Boolean {
        if (PingHub.running || appTest != null) return false
        appTest = label to color
        PingHub.externalBusy = true
        syncBusy()
        return true
    }

    override fun release() {
        appTest = null
        PingHub.externalBusy = false
        syncBusy()
    }

    override fun netInfo(): NetInfo = try {
        readNetworkInfo(this).also { cachedNet = it }
    } catch (_: Exception) {
        cachedNet
    }

    override fun prefs(): SharedPreferences = Prefs.of(this)

    override fun post(block: () -> Unit) {
        runOnUiThread { if (!isFinishing && !isDestroyed) block() }
    }

    override fun setKeepScreenOn(on: Boolean) {
        if (on) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    override fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQUEST_NOTIFICATIONS)
        }
    }

    override fun shareText(text: String) {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, text)
        }
        startActivity(Intent.createChooser(intent, "Enviar"))
    }

    override fun copyText(text: String) {
        getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("Real Ping", text))
        Toast.makeText(this, "Copiado", Toast.LENGTH_SHORT).show()
    }

    override fun shareImage(title: String, body: String, chart: LatencyChartView?) {
        try {
            val dir = File(cacheDir, "shared").apply { mkdirs() }
            val file = File(dir, "pingreal_relatorio.png")
            FileOutputStream(file).use { out ->
                buildReportBitmap(title, body, chart).compress(Bitmap.CompressFormat.PNG, 100, out)
            }
            val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "image/png"
                putExtra(Intent.EXTRA_STREAM, uri)
                clipData = ClipData.newRawUri("", uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(intent, "Enviar"))
        } catch (e: Exception) {
            Toast.makeText(this, "Não foi possível gerar a imagem: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    /** Ocupado = há ping (daqui ou do widget) ou um teste desta tela. Atualiza o status e as abas. */
    private fun syncBusy() {
        val test = appTest
        when {
            PingHub.running -> setStatus("PING ATIVO", ui.green)
            test != null -> setStatus(test.first, test.second)
            else -> setStatus("PRONTO", ui.muted)
        }
        val idle = !PingHub.running && test == null
        if (::terminal.isInitialized) terminal.onModeChanged(idle)
        if (::diagnostic.isInitialized) diagnostic.onModeChanged(idle)
    }

    private fun setStatus(label: String, color: Int) {
        statusText.text = label
        statusText.setTextColor(color)
        statusDot.setColor(color)
    }

    private fun scheduleNetworkRefresh() {
        handler.removeCallbacks(networkRefresh)
        handler.postDelayed(networkRefresh, 300)
    }

    private fun refreshNetwork() {
        if (::terminal.isInitialized) terminal.onNetworkChanged()
    }

    // =====================================================================
    // Interface
    // =====================================================================

    @Suppress("DEPRECATION")
    private fun versionName(): String = try {
        packageManager.getPackageInfo(packageName, 0).versionName ?: ""
    } catch (_: Exception) {
        ""
    }

    private fun buildUi() {
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(ui.bg)
            setPadding(ui.dp(16), ui.dp(8), ui.dp(16), ui.dp(8))
        }

        root.setOnApplyWindowInsetsListener { v, insets ->
            val pad = IntArray(4) // esquerda, topo, direita, base
            var imeHeight = 0
            if (Build.VERSION.SDK_INT >= 30) {
                val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
                val ime = insets.getInsets(WindowInsets.Type.ime())
                pad[0] = bars.left
                pad[1] = bars.top
                pad[2] = bars.right
                pad[3] = max(bars.bottom, ime.bottom)
                imeHeight = ime.bottom
            } else {
                @Suppress("DEPRECATION")
                pad[0] = insets.systemWindowInsetLeft
                @Suppress("DEPRECATION")
                pad[1] = insets.systemWindowInsetTop
                @Suppress("DEPRECATION")
                pad[2] = insets.systemWindowInsetRight
                @Suppress("DEPRECATION")
                pad[3] = insets.systemWindowInsetBottom
            }
            v.setPadding(ui.dp(16) + pad[0], ui.dp(8) + pad[1], ui.dp(16) + pad[2], ui.dp(8) + pad[3])
            // Com o teclado aberto, a barra de abas só atrapalha.
            if (::nav.isInitialized) nav.visibility = if (imeHeight > ui.dp(120)) View.GONE else View.VISIBLE
            insets
        }

        // ---- cabeçalho: nome + versão, status e opções ----
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(ui.dp(2), 0, 0, ui.dp(8))
        }
        header.addView(ui.tv("REAL PING", 13f, ui.white, bold = true, spacing = 0.18f))
        header.addView(ui.tv(versionName(), 10f, ui.muted, mono = true).apply { setPadding(ui.dp(8), 0, 0, 0) })
        header.addView(View(this), ui.lp(0, 1, 1f))
        statusText = ui.tv("PRONTO", 10f, ui.muted, bold = true, spacing = 0.1f)
        statusDot = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(ui.muted)
        }
        header.addView(statusText)
        header.addView(View(this).apply { background = statusDot }, ui.lp(ui.dp(7), ui.dp(7)).apply { marginStart = ui.dp(7) })
        header.addView(
            ui.tv("⋮", 18f, ui.muted, bold = true).apply {
                gravity = Gravity.CENTER
                background = ui.rippled(Color.TRANSPARENT, 8f)
                isClickable = true
                contentDescription = "Opções"
                setOnClickListener { showSettings() }
            },
            ui.lp(ui.dp(36), ui.dp(36)).apply { marginStart = ui.dp(6) }
        )
        root.addView(header, ui.lp(-1, -2))

        // ---- abas ----
        terminal = TerminalPage(this, ui, this)
        diagnostic = DiagnosticPage(this, ui, this)
        val frame = FrameLayout(this)
        for (page in listOf(terminal.view, diagnostic.view)) {
            frame.addView(page, FrameLayout.LayoutParams(-1, -1))
            pages.add(page)
        }
        root.addView(frame, ui.lp(-1, 0, 1f))

        // ---- barra de abas ----
        nav = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(ui.dp(4), ui.dp(2), ui.dp(4), ui.dp(2))
        }
        nav.addView(navItem(0, NavIconView.TERMINAL, "TERMINAL"), ui.lp(0, -2, 1f))
        nav.addView(navItem(1, NavIconView.DIAGNOSTIC, "DIAGNÓSTICO"), ui.lp(0, -2, 1f))
        root.addView(View(this).apply { setBackgroundColor(ui.panelStroke) }, ui.lp(-1, ui.dp(1)).apply { topMargin = ui.dp(8) })
        root.addView(nav, ui.lp(-1, -2).apply { topMargin = ui.dp(2) })
    }

    private fun navItem(index: Int, kind: Int, label: String): LinearLayout {
        val icon = NavIconView(this, kind)
        val text = ui.tv(label, 9f, ui.muted, bold = true, spacing = 0.08f).apply { gravity = Gravity.CENTER }
        tabIcons.add(icon)
        tabLabels.add(text)
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(0, ui.dp(6), 0, ui.dp(6))
            background = ui.rippled(Color.TRANSPARENT, 8f)
            isClickable = true
            addView(icon, ui.lp(ui.dp(24), ui.dp(24)))
            addView(text, ui.lp(-2, -2).apply { topMargin = ui.dp(2) })
            setOnClickListener { selectTab(index) }
        }
    }

    private fun selectTab(index: Int) {
        for ((i, page) in pages.withIndex()) page.visibility = if (i == index) View.VISIBLE else View.GONE
        for (i in tabIcons.indices) {
            val color = if (i == index) ui.white else ui.muted
            tabIcons[i].activeColor = color
            tabLabels[i].setTextColor(color)
        }
        if (index != 0) terminal.hideKeyboard()
    }

    private fun showSettings() {
        val p = prefs()
        val items = arrayOf("Manter a tela ligada durante o ping", "Vibrar quando a conexão cair e voltar")
        val checked = booleanArrayOf(
            p.getBoolean(Prefs.KEEP_SCREEN, true),
            p.getBoolean(Prefs.VIBRATE, true)
        )
        AlertDialog.Builder(this)
            .setTitle("Opções")
            .setMultiChoiceItems(items, checked) { _, which, isChecked ->
                val key = if (which == 0) Prefs.KEEP_SCREEN else Prefs.VIBRATE
                p.edit().putBoolean(key, isChecked).apply()
                if (which == 0) terminal.applyKeepScreen()
            }
            .setPositiveButton("OK", null)
            .show()
    }

    // =====================================================================
    // Imagem do relatório
    // =====================================================================

    private fun buildReportBitmap(title: String, body: String, chart: LatencyChartView?): Bitmap {
        val width = 1080
        val pad = 48
        val inner = width - 2 * pad
        val withChart = chart != null && chart.width > 0 && chart.height > 0

        val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = ui.white
            textSize = 52f
            typeface = Typeface.DEFAULT_BOLD
        }
        val datePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = ui.muted
            textSize = 30f
        }
        val bodyPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = ui.white
            textSize = 30f
            typeface = Typeface.MONOSPACE
        }
        val layout = StaticLayout.Builder.obtain(body, 0, body.length, bodyPaint, inner)
            .setLineSpacing(8f, 1f)
            .build()

        val scale = if (withChart) inner.toFloat() / chart!!.width else 1f
        val scaledChartH = if (withChart) (chart!!.height * scale).toInt() else 0

        var y = pad + 52
        val titleBaseline = y.toFloat()
        y += 44
        val dateBaseline = y.toFloat()
        y += 36
        val chartTop = y
        if (withChart) y += scaledChartH + 36
        val bodyTop = y
        val totalH = bodyTop + layout.height + pad

        val bitmap = Bitmap.createBitmap(width, totalH, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(ui.bg)
        canvas.drawText(title, pad.toFloat(), titleBaseline, titlePaint)
        val date = SimpleDateFormat("dd/MM/yyyy HH:mm:ss", Locale("pt", "BR")).format(Date())
        canvas.drawText(date, pad.toFloat(), dateBaseline, datePaint)

        if (withChart) {
            val panelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ui.panel }
            canvas.drawRoundRect(
                RectF(pad.toFloat(), chartTop.toFloat(), (pad + inner).toFloat(), (chartTop + scaledChartH).toFloat()),
                28f, 28f, panelPaint
            )
            canvas.save()
            canvas.translate(pad.toFloat(), chartTop.toFloat())
            canvas.scale(scale, scale)
            chart!!.draw(canvas)
            canvas.restore()
        }

        canvas.save()
        canvas.translate(pad.toFloat(), bodyTop.toFloat())
        layout.draw(canvas)
        canvas.restore()

        return bitmap
    }
}
