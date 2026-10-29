package br.com.pingreal

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import java.util.Locale
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/** Paleta e construtores de view usados por todas as telas (montadas em código). */
class UiKit(private val context: Context) {

    val bg = Palette.bg
    val panel = Palette.panel
    val panelStroke = Palette.panelStroke
    val consoleBg = Palette.consoleBg
    val green = Palette.green
    val cyan = Palette.cyan
    val violet = Palette.violet
    val orange = Palette.orange
    val amber = Palette.amber
    val red = Palette.red
    val white = Palette.white
    val text = Palette.text
    val muted = Palette.muted
    val onGreen = Palette.onGreen

    /** Cores das curvas quando há vários alvos. */
    val series = Palette.series

    private val density = context.resources.displayMetrics.density

    fun dp(value: Int): Int = (value * density).toInt()
    fun dpf(value: Float): Float = value * density

    fun lp(w: Int, h: Int, weight: Float = 0f) = LinearLayout.LayoutParams(w, h, weight)

    fun roundRect(fill: Int, radiusDp: Float, stroke: Int = 0): GradientDrawable =
        GradientDrawable().apply {
            setColor(fill)
            cornerRadius = dpf(radiusDp)
            if (stroke != 0) setStroke(max(1, dp(1)), stroke)
        }

    fun rippled(fill: Int, radiusDp: Float, stroke: Int = 0): Drawable {
        val mask = GradientDrawable().apply {
            setColor(Color.WHITE)
            cornerRadius = dpf(radiusDp)
        }
        return RippleDrawable(
            ColorStateList.valueOf(Color.argb(50, 255, 255, 255)),
            roundRect(fill, radiusDp, stroke),
            mask
        )
    }

    fun tv(
        text: String,
        size: Float,
        color: Int,
        bold: Boolean = false,
        mono: Boolean = false,
        spacing: Float = 0f
    ): TextView = TextView(context).apply {
        this.text = text
        textSize = size
        setTextColor(color)
        typeface = when {
            mono && bold -> Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            mono -> Typeface.MONOSPACE
            bold -> Typeface.DEFAULT_BOLD
            else -> Typeface.DEFAULT
        }
        letterSpacing = spacing
    }

    /** Rótulo de seção (cinza, maiúsculas espaçadas). */
    fun label(text: String): TextView = tv(text, 11f, muted, bold = true, spacing = 0.1f)

    fun card(): LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        background = roundRect(panel, 10f, panelStroke)
        setPadding(dp(14), dp(12), dp(14), dp(12))
    }

    fun button(label: String, fill: Int, textColor: Int, stroke: Int = 0, onClick: () -> Unit): Button =
        Button(context).apply {
            text = label
            isAllCaps = false
            textSize = 12f
            typeface = Typeface.DEFAULT_BOLD
            stateListAnimator = null
            minWidth = 0
            minHeight = 0
            minimumWidth = 0
            minimumHeight = 0
            setPadding(dp(4), 0, dp(4), 0)
            setOnClickListener { onClick() }
            style(this, fill, textColor, stroke)
        }

    /** Troca as cores de um botão já criado (ex.: INICIAR ↔ PARAR). */
    fun style(button: Button, fill: Int, textColor: Int, stroke: Int = 0) {
        button.setTextColor(textColor)
        button.background = rippled(fill, 8f, stroke)
    }

    /** Botão principal (preenchido em verde). */
    fun primary(button: Button) = style(button, green, onGreen)

    /** Botão de parar (contorno, texto vermelho). */
    fun danger(button: Button) = style(button, Color.TRANSPARENT, red, panelStroke)

    /** Botão secundário (contorno, texto claro). */
    fun secondary(button: Button) = style(button, Color.TRANSPARENT, white, panelStroke)

    fun setEnabled(view: View, enabled: Boolean) {
        view.isEnabled = enabled
        view.alpha = if (enabled) 1f else 0.4f
    }

    /** Ação de texto pequena (ex.: ENVIAR, LIMPAR). */
    fun textAction(label: String, onClick: () -> Unit): TextView =
        tv(label, 11f, muted, bold = true, spacing = 0.08f).apply {
            gravity = Gravity.CENTER
            setPadding(dp(10), dp(6), dp(10), dp(6))
            background = rippled(Color.TRANSPARENT, 6f)
            isClickable = true
            setOnClickListener { onClick() }
        }

    fun formatMs(ms: Double): String = br.com.pingreal.formatMs(ms)

    fun formatMbps(v: Double): String = br.com.pingreal.formatMbps(v)
}

/** Ícones da barra inferior, desenhados em código. */
class NavIconView(context: Context, private val kind: Int) : View(context) {

    companion object {
        const val TERMINAL = 0
        const val DIAGNOSTIC = 1
    }

    var activeColor: Int = Color.WHITE
        set(value) { field = value; invalidate() }

    private val density = resources.displayMetrics.density
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.6f * density
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val path = Path()

    override fun onDraw(canvas: Canvas) {
        paint.color = activeColor
        val w = width.toFloat()
        val h = height.toFloat()
        val inset = paint.strokeWidth

        when (kind) {
            TERMINAL -> {
                // moldura + prompt ">_"
                canvas.drawRoundRect(RectF(inset, inset + h * 0.08f, w - inset, h - inset - h * 0.08f), 5f * density, 5f * density, paint)
                path.reset()
                path.moveTo(w * 0.27f, h * 0.36f)
                path.lineTo(w * 0.43f, h * 0.50f)
                path.lineTo(w * 0.27f, h * 0.64f)
                canvas.drawPath(path, paint)
                canvas.drawLine(w * 0.52f, h * 0.65f, w * 0.72f, h * 0.65f, paint)
            }
            else -> {
                // anel + linha de batimento
                canvas.drawCircle(w / 2f, h / 2f, (w / 2f) - inset, paint)
                path.reset()
                path.moveTo(w * 0.16f, h * 0.52f)
                path.lineTo(w * 0.34f, h * 0.52f)
                path.lineTo(w * 0.43f, h * 0.30f)
                path.lineTo(w * 0.55f, h * 0.74f)
                path.lineTo(w * 0.64f, h * 0.52f)
                path.lineTo(w * 0.84f, h * 0.52f)
                canvas.drawPath(path, paint)
            }
        }
    }
}

/**
 * Velocímetro do teste de velocidade: arco de 240°, escala não linear (como a do Speedtest),
 * ponteiro animado e o valor no centro.
 */
class GaugeView(context: Context) : View(context) {

    companion object {
        private val MARKS = floatArrayOf(0f, 5f, 10f, 50f, 100f, 250f, 500f, 750f, 1000f)
        private const val START_ANGLE = 150f
        private const val SWEEP = 240f
    }

    private val density = resources.displayMetrics.density
    private fun dp(v: Float) = v * density

    private var phase = ""
    private var valueText = "—"
    private var unitText = "Mbps"
    private var accent = Color.rgb(108, 180, 238)
    private var target = 0f
    private var shown = 0f
    private var lastFrame = 0L

    private val arcRect = RectF()
    private val needle = Path()

    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        color = Color.argb(26, 255, 255, 255)
    }
    private val arcPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val scalePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(138, 143, 148)
        textSize = dp(9.5f)
        textAlign = Paint.Align.CENTER
    }
    private val needlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(230, 232, 234) }
    private val hubPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(18, 19, 21) }
    private val valuePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(230, 232, 234)
        textSize = dp(34f)
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
    }
    private val unitPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(138, 143, 148)
        textSize = dp(11f)
        textAlign = Paint.Align.CENTER
    }
    private val phasePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = dp(11f)
        textAlign = Paint.Align.CENTER
        typeface = Typeface.DEFAULT_BOLD
        letterSpacing = 0.1f
    }

    /** Fase atual (ex.: "↓ DOWNLOAD") e a cor do arco. O ponteiro volta ao zero. */
    fun setPhase(label: String, color: Int) {
        phase = label
        accent = color
        target = 0f
        valueText = "—"
        unitText = "Mbps"
        animateNow()
    }

    fun setSpeed(mbps: Double) {
        target = mbps.toFloat().coerceIn(0f, MARKS.last())
        valueText = if (mbps >= 100) mbps.toInt().toString() else String.format(Locale.US, "%.1f", mbps)
        unitText = "Mbps"
        animateNow()
    }

    /** Texto no centro sem mexer no ponteiro (ex.: latência em ms). */
    fun setText(value: String, unit: String) {
        valueText = value
        unitText = unit
        invalidate()
    }

    /** Estado final: ponteiro no zero e um texto no centro. */
    fun settle(label: String, value: String, unit: String) {
        phase = label
        target = 0f
        valueText = value
        unitText = unit
        animateNow()
    }

    private fun animateNow() {
        lastFrame = 0L
        postInvalidateOnAnimation()
    }

    /** Posição de 0 a 1 no arco: cada intervalo entre marcas ocupa o mesmo ângulo. */
    private fun fractionOf(v: Float): Float {
        val segments = MARKS.size - 1
        for (i in 0 until segments) {
            val a = MARKS[i]
            val b = MARKS[i + 1]
            if (v <= b) return (i + (v - a) / (b - a)) / segments
        }
        return 1f
    }

    override fun onDraw(canvas: Canvas) {
        val now = System.currentTimeMillis()
        val dt = if (lastFrame == 0L) 16L else (now - lastFrame).coerceIn(1L, 100L)
        lastFrame = now
        val diff = target - shown
        val animating = abs(diff) > 0.05f
        shown = if (animating) shown + diff * (1f - exp(-dt / 160f)) else target

        val w = width.toFloat()
        val h = height.toFloat()
        val stroke = dp(10f)
        val bottomRoom = dp(26f)
        val r = min((w - 2 * stroke) / 2f, (h - stroke - bottomRoom) / 1.5f)
        val cx = w / 2f
        val cy = stroke + r

        trackPaint.strokeWidth = stroke
        arcPaint.strokeWidth = stroke
        arcPaint.color = accent
        arcRect.set(cx - r, cy - r, cx + r, cy + r)

        canvas.drawArc(arcRect, START_ANGLE, SWEEP, false, trackPaint)
        val frac = fractionOf(shown)
        if (frac > 0.002f) canvas.drawArc(arcRect, START_ANGLE, SWEEP * frac, false, arcPaint)

        // Escala
        val labelR = r - stroke - dp(11f)
        for ((i, mark) in MARKS.withIndex()) {
            val angle = Math.toRadians((START_ANGLE + SWEEP * i / (MARKS.size - 1)).toDouble())
            val x = cx + cos(angle).toFloat() * labelR
            val y = cy + sin(angle).toFloat() * labelR + scalePaint.textSize / 3f
            canvas.drawText(mark.toInt().toString(), x, y, scalePaint)
        }

        // Ponteiro afinando até a ponta
        val angle = Math.toRadians((START_ANGLE + SWEEP * frac).toDouble())
        val dx = cos(angle).toFloat()
        val dy = sin(angle).toFloat()
        val length = r - stroke * 2.6f
        val base = dp(4f)
        needle.reset()
        needle.moveTo(cx - dy * base, cy + dx * base)
        needle.lineTo(cx + dx * length, cy + dy * length)
        needle.lineTo(cx + dy * base, cy - dx * base)
        needle.close()
        canvas.drawPath(needle, needlePaint)
        canvas.drawCircle(cx, cy, dp(7f), needlePaint)
        canvas.drawCircle(cx, cy, dp(3.5f), hubPaint)

        // Textos
        phasePaint.color = accent
        canvas.drawText(phase, cx, cy + r * 0.28f, phasePaint)
        val valueBaseline = cy + r * 0.28f + dp(36f)
        canvas.drawText(valueText, cx, valueBaseline, valuePaint)
        canvas.drawText(unitText, cx, valueBaseline + dp(16f), unitPaint)

        if (animating) postInvalidateOnAnimation()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = getDefaultSize(suggestedMinimumWidth, widthMeasureSpec)
        val h = (220 * density).toInt()
        setMeasuredDimension(w, resolveSize(h, heightMeasureSpec))
    }
}
