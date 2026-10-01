package com.circularcalendar.app

import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

// ─────────────────────────────────────────────────────────────────────────────
// drawWheel — вся геометрия, события и Солнечная система.
//
// ТОЧНОСТЬ (зум до 200 000×): координаты считаются в double и пишутся в холст
// ОТНОСИТЕЛЬНО локального начала (точка контента под центром экрана), так что
// float-числа, уходящие в Skia, всегда малы. Дуги — адаптивные ломаные только
// в видимом угловом окне, радиальные линии обрезаются видимым диапазоном
// радиусов. Иначе на масштабе минут/секунд всё дрожало бы на пиксели.
// ─────────────────────────────────────────────────────────────────────────────

private val MONTHS_RU = arrayOf(
    "Янв", "Фев", "Мар", "Апр", "Май", "Июн",
    "Июл", "Авг", "Сен", "Окт", "Ноя", "Дек",
)
private val MONTHS_FULL = arrayOf(
    "Январь", "Февраль", "Март", "Апрель", "Май", "Июнь",
    "Июль", "Август", "Сентябрь", "Октябрь", "Ноябрь", "Декабрь",
)
private val MONTHS_GEN = arrayOf(
    "янв", "фев", "мар", "апр", "мая", "июн",
    "июл", "авг", "сен", "окт", "ноя", "дек",
)

private fun deg(rad: Double): Float = (rad * 180.0 / PI).toFloat()

/** Геометрия круга в координатах контента (общая для рендера и жестов). */
class WheelGeom(width: Float, height: Float) {
    val cx = width / 2.0
    val cy = height / 2.0
    val maxR = min(width, height) / 2.0
    val outerR = maxR * 0.92
    // Внутреннее кольцо: внутри innerR пусто, к fadeEndR — плавный градиент.
    val innerR = maxR * 0.22
    val fadeEndR = maxR * 0.34
    val unit = maxR / 360.0
    // Кончик стрелки «сейчас» = Земля; это расстояние = 1 а.е.
    val tipR = outerR + 14.0 * unit
    // Граница «не потерять календарь»: круг + стрелка + орбита Луны.
    val keepR = tipR + 6.0 * unit
}

/**
 * Что видно при записи сцены.
 * [camScale] — зум; [originX]/[originY] — локальное начало (контент);
 * [rotDeg] — поворот камеры (для «прямых» подписей планет);
 * угловое окно [aLo, aHi] в углах контента (если ![full]);
 * [rMin]/[rMax] — видимый диапазон расстояний от центра круга;
 * [visR] — радиус видимой области вокруг локального начала.
 */
class Lod(
    val camScale: Double = 1.0,
    val originX: Double = 0.0,
    val originY: Double = 0.0,
    val rotDeg: Float = 0f,
    val full: Boolean = true,
    val aLo: Double = 0.0,
    val aHi: Double = 0.0,
    val rMin: Double = 0.0,
    val rMax: Double = 1e15,
    val visR: Double = 1e15,
    val density: Float = 2.75f,
)

/** Ломаная дуга с шагом под экранную точность (стрелка прогиба ≤ 0.3 px). */
private fun arcPoly(
    path: Path, cx: Double, cy: Double, r: Double, a0: Double, a1: Double,
    scale: Double, move: Boolean,
) {
    val rs = r * scale
    val step = if (rs > 2) sqrt(2.4 / rs) else 0.6
    val n = ceil(abs(a1 - a0) / step).toInt().coerceIn(1, 4096)
    for (i in 0..n) {
        val a = a0 + (a1 - a0) * i / n
        val x = (cx + cos(a) * r).toFloat()
        val y = (cy + sin(a) * r).toFloat()
        if (i == 0 && move) path.moveTo(x, y) else path.lineTo(x, y)
    }
}

fun drawWheel(
    canvas: Canvas,
    width: Float,
    height: Float,
    tm: TimeMap,
    settings: WheelSettings,
    events: EventIndex,
    lod: Lod,
    // Слои LOD (риски и подписи времени) — в отдельные холсты на полной
    // яркости; их прозрачность ставится КАЖДЫЙ кадр по текущему зуму (плавное
    // проявление без перезаписи). null — рисовать всё в [canvas] с текущей яркостью.
    tiers: Array<Canvas>? = null,
) {
    val lk = settings.look
    val ds = lk.d
    val g = WheelGeom(width, height)
    val s = lod.camScale
    val sF = s.toFloat()
    val unit = g.unit
    val outerR = g.outerR
    val holeR = lk.holeR * outerR
    // Центр круга в локальных координатах.
    val lx = g.cx - lod.originX
    val ly = g.cy - lod.originY
    val rotRad = lod.rotDeg * PI / 180.0
    // Свечение — в экранных px (в локальных единицах делим на зум).
    val glowLocal = (ds.glow * 16.0 * lod.density / 2.75 / s).toFloat()

    fun px(a: Double, r: Double): Float = (lx + cos(a) * r).toFloat()
    fun py(a: Double, r: Double): Float = (ly + sin(a) * r).toFloat()

    // ── Окна видимости ───────────────────────────────────────────────────────
    val winLen = lod.aHi - lod.aLo
    var fLo = 0.0
    var fHi = 1.0 - 1e-12
    if (!lod.full) {
        val f1 = tm.fracOfAngle(lod.aLo)
        val f2 = tm.fracOfAngle(lod.aHi)
        fLo = min(f1, f2); fHi = max(f1, f2)
        // Окно — в первом обороте после опорного момента (углы камеры могут
        // накрутить много оборотов, а событиям и датам нужен «этот» год).
        val sh = floor(fLo - tm.nowFrac + 0.5)
        fLo -= sh; fHi -= sh
    }

    fun visible(fs: Double, fe: Double): Boolean {
        if (lod.full) return true
        for (k in -2..2) if (max(fs, fLo + k) < min(fe, fHi + k)) return true
        return false
    }

    /** Пересечение дуги [a0→a1] с угловым окном (направление сохраняется). */
    fun clip(a0: Double, a1: Double): DoubleArray? {
        if (lod.full) return doubleArrayOf(a0, a1)
        val lo = min(a0, a1); val hi = max(a0, a1)
        if (hi - lo + winLen >= 2 * PI) return doubleArrayOf(a0, a1)
        val k0 = Math.round(((lo + hi) / 2 - (lod.aLo + lod.aHi) / 2) / (2 * PI))
        for (k in k0 - 1..k0 + 1) {
            val l = max(lo, lod.aLo + 2 * PI * k)
            val h = min(hi, lod.aHi + 2 * PI * k)
            if (l < h) return if (a0 <= a1) doubleArrayOf(l, h) else doubleArrayOf(h, l)
        }
        return null
    }

    fun angleVisible(a: Double): Boolean {
        if (lod.full) return true
        val eps = 1e-3 * winLen + 1e-9
        return clip(a - eps, a + eps) != null
    }

    // Тонкие линии рисуем в ЭКРАННЫХ пикселях (координаты × зум, холст
    // масштабирован обратно), как подписи. В локальных единицах на зуме 10⁵
    // ширина линии ~1e-5 — мобильные GPU считают с пониженной точностью,
    // и деления «плыли» и пропадали (на эмуляторе точности хватало).
    fun radial(a: Double, r0: Double, r1: Double, p: Paint, cv: Canvas = canvas) {
        val canvas = cv
        val ra = max(r0, lod.rMin - 2.0 / s)
        val rb = min(r1, lod.rMax + 2.0 / s)
        if (ra >= rb || !angleVisible(a)) return
        val w = p.strokeWidth
        p.strokeWidth = w * sF
        canvas.save()
        canvas.scale(1f / sF, 1f / sF)
        canvas.drawLine(
            ((lx + cos(a) * ra) * s).toFloat(), ((ly + sin(a) * ra) * s).toFloat(),
            ((lx + cos(a) * rb) * s).toFloat(), ((ly + sin(a) * rb) * s).toFloat(), p,
        )
        canvas.restore()
        p.strokeWidth = w
    }

    /** Обводка пути, построенного в экранных px (см. [radial]). */
    fun strokeScreen(path: Path, p: Paint) {
        val w = p.strokeWidth
        p.strokeWidth = w * sF
        canvas.save()
        canvas.scale(1f / sF, 1f / sF)
        canvas.drawPath(path, p)
        canvas.restore()
        p.strokeWidth = w
    }

    // Экранная толщина линий почти постоянна: растёт с зумом лишь как
    // zoom^0.15 (максимум ×2), иначе на масштабе часов риски толще промежутков.
    fun lineW(base: Double, minPx: Double = 0.7): Float {
        val screen = if (s >= 1) base * unit * min(s.pow(0.15), 2.0) else base * unit * s
        return (max(screen, minPx) / s).toFloat()
    }

    fun fade(v: Double, lo: Double, hi: Double): Float = ((v - lo) / (hi - lo)).coerceIn(0.0, 1.0).toFloat()

    /** Полная окружность радиуса [r] (только видимая часть). */
    fun ringPath(r: Double): Path? {
        if (r < lod.rMin - 4 / s || r > lod.rMax + 4 / s) return null
        val c = clip(0.0, 2 * PI) ?: return null
        val path = Path()
        arcPoly(path, lx * s, ly * s, r * s, c[0], c[1], 1.0, true) // экранные px
        return path
    }

    // Подписи всегда читаются: внизу экрана — «лицом» как есть, вверху —
    // переворачиваются (решается по экранному углу на момент записи сцены).
    fun onBottom(a: Double) = sin(a + rotRad) >= 0
    fun textRot(a: Double) = if (onBottom(a)) a - PI / 2 else a + PI / 2

    val textPaint = Paint(
        Paint.ANTI_ALIAS_FLAG or Paint.LINEAR_TEXT_FLAG or Paint.SUBPIXEL_TEXT_FLAG,
    ).apply { textAlign = Paint.Align.CENTER }
    val face = lk.typeface
    val digitH = digitHeight(face)

    /** Подпись (прямая) по касательной с ЭКРАННЫМ кеглем [sizePx]. */
    fun label(
        text: String, a: Double, r: Double, sizePx: Float, color: Int,
        digits: Boolean = false, cv: Canvas = canvas,
    ) {
        val canvas = cv
        if (sizePx < 2.5f) return
        val reach = sizePx * 3 / s
        if (r < lod.rMin - reach || r > lod.rMax + reach) return
        textPaint.textSize = sizePx
        textPaint.color = color
        textPaint.typeface = face
        textPaint.letterSpacing = 0f
        canvas.save()
        canvas.translate(px(a, r), py(a, r))
        canvas.rotate(deg(textRot(a)))
        canvas.scale(1f / sF, 1f / sF)
        // Цифры центрируются по их реальной высоте (ряды встают точно).
        val y = if (digits) (digitH * sizePx / 2).toFloat()
        else -(textPaint.ascent() + textPaint.descent()) / 2f
        canvas.drawText(text, 0f, y, textPaint)
        canvas.restore()
    }

    /** Подпись, изогнутая по дуге радиуса [r] (кегль в единицах круга). */
    fun arcLabel(text: String, a: Double, r: Double, size: Double, color: Int) {
        if (size * s < 2.5) return
        if (r < lod.rMin - size * 3 || r > lod.rMax + size * 3) return
        textPaint.textSize = size.toFloat()
        textPaint.color = color
        textPaint.typeface = face
        textPaint.letterSpacing = lk.font.spacing
        textPaint.textAlign = Paint.Align.LEFT
        val w = textPaint.measureText(text).toDouble()
        val span = w / r
        val path = Path()
        if (onBottom(a)) arcPoly(path, lx, ly, r, a + span / 2, a - span / 2, s, true)
        else arcPoly(path, lx, ly, r, a - span / 2, a + span / 2, s, true)
        canvas.drawTextOnPath(text, path, 0f, -(textPaint.ascent() + textPaint.descent()) / 2f, textPaint)
        textPaint.textAlign = Paint.Align.CENTER
        textPaint.letterSpacing = 0f
    }

    val nowFrac = tm.nowFrac
    val totalSec = tm.totalSec
    val pxPerSec = 2.0 * PI * outerR * s / totalSec
    val pxPerDay = pxPerSec * 86400.0

    // ── Объём: мягкая тень под кругом (видна только у края) ─────────────────
    if (ds.volume > 0f && lod.rMax > outerR * 0.8 && s < 40) {
        val off = (12.0 * unit * ds.volume).toFloat()
        val rr = (outerR * 1.12).toFloat()
        val a = (0.55f * ds.volume * (if (lk.dark) 1f else 0.45f))
        canvas.drawCircle(lx.toFloat(), ly.toFloat() + off, rr, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = RadialGradient(
                lx.toFloat(), ly.toFloat() + off, rr,
                intArrayOf(0, 0, withAlpha(if (lk.dark) BLACK else mix(lk.accent, BLACK, 0.5f), a), 0),
                floatArrayOf(0f, 0.74f, 0.82f, 1f), Shader.TileMode.CLAMP,
            )
        })
    }

    // ═════ Слой круга: заливка + радиальные риски, с маской дырки/градиента ═══
    val gradOn = ds.ring || ds.grad < 0.999f
    val maskEnd = holeR + (outerR - holeR) * 0.92
    val needMask = RDBG and 16 == 0 && gradOn && lod.rMin < maskEnd
    if (needMask) {
        val b = (outerR + 4 * unit).toFloat()
        canvas.saveLayer(lx.toFloat() - b, ly.toFloat() - b, lx.toFloat() + b, ly.toFloat() + b, null)
    }

    // ── Заливка: один цвет на весь круг (прошлое — по ручке «Прошлое темнее») ─
    val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    val slicePath = Path()
    fun drawSlice(f0: Double, f1: Double, color: Int) {
        if (abs(f1 - f0) < 1e-12) return
        val c = clip(tm.angleOf(f0), tm.angleOf(f1)) ?: return
        val rIn = max(0.0, lod.rMin - 4.0 / s)
        val rOut = min(outerR, lod.rMax + 4.0 / s)
        if (rIn >= rOut) return
        slicePath.reset()
        arcPoly(slicePath, lx, ly, rOut, c[0], c[1], s, true)
        if (rIn <= 1e-9) slicePath.lineTo(lx.toFloat(), ly.toFloat())
        else arcPoly(slicePath, lx, ly, rIn, c[1], c[0], s, false)
        slicePath.close()
        fillPaint.color = color
        canvas.drawPath(slicePath, fillPaint)
    }

    val year = tm.year
    fun monthFrac(m: Int): Double =
        if (m <= 12) tm.fracOf(java.time.LocalDateTime.of(year, m, 1, 0, 0)) else 1.0

    // Год целиком: прошлое (до «сейчас») и будущее; без чередования месяцев.
    drawSlice(0.0, nowFrac, lk.sectorPast)
    drawSlice(nowFrac, 1.0, lk.sectorFuture)

    // ── LOD-лестница рисок и подписей (см. [tierVis]) ──────────────────────
    // В режиме слоёв: слой пишется на полной яркости, если проявится в
    // пределах ×TIER_REACH зума (до следующей перезаписи), иначе пропускается.
    // Слой, который во всём диапазоне уже проявлен полностью, вкатывается в
    // основную сцену (кэш-растр, ноль работы на кадр); отдельно, с яркостью
    // на каждый кадр, живут только проявляющиеся/гаснущие.
    fun fading(i: Int): Boolean =
        tiers != null && tierVis(i, s / TIER_REACH, outerR, totalSec) < 1f
    fun tv(i: Int): Float =
        if (tiers != null) (if (tierVis(i, s * TIER_REACH, outerR, totalSec) > 0f) 1f else 0f)
        else tierVis(i, s, outerR, totalSec)
    fun tc(i: Int): Canvas = if (fading(i)) tiers!![i] else canvas

    fun tickPaint(color: Int, alpha: Float, w: Float) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = withAlpha(color, alpha)
        strokeWidth = w
    }

    val line = lk.line
    val ta = lk.tickAlpha

    // ── Границы месяцев, астро-линии, сезоны ────────────────────────────────
    if (lk.monthAlpha > 0f) {
        val monthLinePaint = tickPaint(line, lk.monthAlpha, lineW(1.6, 1.0))
        for (m in 1..12) radial(tm.angleOf(monthFrac(m)), 0.0, outerR, monthLinePaint)
    }

    val astroPaint = tickPaint(lk.astroColor, 0.5f, lineW(0.7))
    for ((m, day) in listOf(3 to 20, 6 to 21, 9 to 22, 12 to 21)) {
        radial(tm.angleOf(tm.fracOf(java.time.LocalDateTime.of(year, m, day, 0, 0))), 0.0, outerR, astroPaint)
    }

    val seasonPaint = tickPaint(line, lk.seasonAlpha, lineW(2.8, 1.2))
    for (m in listOf(3, 6, 9, 12)) radial(tm.angleOf(monthFrac(m)), 0.0, outerR, seasonPaint)

    // ── Объём: блик сверху-слева (под маской — в дырке его нет) ─────────────
    if (ds.volume > 0f && lod.rMax > holeR && s < 40) {
        val hx = (lx - outerR * 0.45).toFloat(); val hy = (ly - outerR * 0.55).toFloat()
        val hr = (outerR * 1.35).toFloat()
        canvas.drawCircle(lx.toFloat(), ly.toFloat(), outerR.toFloat(), Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = RadialGradient(
                hx, hy, hr, intArrayOf(withAlpha(WHITE, 0.15f * ds.volume), 0), null, Shader.TileMode.CLAMP,
            )
        })
    }

    // ── Маска: дырка в центре + градиент от неё к краю ──────────────────────
    if (needMask) {
        val hk = (holeR / maskEnd).toFloat()
        val gr = ds.grad.coerceIn(0f, 1f)
        fun al(v: Float) = ((v.coerceIn(0f, 1f) * 255f + 0.5f).toInt() shl 24)
        val colors: IntArray
        val pos: FloatArray
        if (ds.ring) {
            colors = intArrayOf(0, 0, al(gr), al(gr + (1 - gr) * 0.6f), al(1f))
            pos = floatArrayOf(0f, hk * 0.995f, hk, hk + (1 - hk) * 0.55f, 1f)
        } else {
            colors = intArrayOf(al(gr), al(gr + (1 - gr) * 0.6f), al(1f))
            pos = floatArrayOf(0f, 0.55f, 1f)
        }
        val maskPaint = Paint().apply {
            if (lod.rMax - lod.rMin < outerR * 0.02) {
                // Глубокий зум: градиент на экране неразличим, а float-шейдер
                // с центром за тысячи экранов даёт пятна — берём ровную альфу.
                val t = (((lod.rMin + lod.rMax) / 2) / maskEnd).toFloat().coerceIn(0f, 1f)
                var a = 1f
                for (i in 1 until pos.size) if (t <= pos[i]) {
                    val k = ((t - pos[i - 1]) / max(pos[i] - pos[i - 1], 1e-6f)).coerceIn(0f, 1f)
                    val a0 = (colors[i - 1] ushr 24) / 255f; val a1 = (colors[i] ushr 24) / 255f
                    a = a0 + (a1 - a0) * k; break
                }
                color = withAlpha(BLACK, a)
            } else {
                shader = RadialGradient(lx.toFloat(), ly.toFloat(), maskEnd.toFloat(), colors, pos, Shader.TileMode.CLAMP)
            }
            xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN)
        }
        val b = (outerR + 4 * unit).toFloat()
        canvas.drawRect(lx.toFloat() - b, ly.toFloat() - b, lx.toFloat() + b, ly.toFloat() + b, maskPaint)
        canvas.restore()
    }

    // ── Лучи: тонкая линия на каждый день, от дырки до края (настроение
    // обложки — частые тонкие штрихи; не веер и не занятость).
    if (ds.rays) {
        val rayPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            strokeWidth = lineW(0.55, 0.8)
            color = withAlpha(lk.accent, if (lk.dark) 0.30f else 0.42f)
        }
        val r0 = if (ds.ring) holeR else outerR * 0.06
        val nDays = (totalSec / 86400).toInt()
        for (d in 0..nDays) {
            val f = d * 86400 / totalSec
            if (!visible(f - 0.002, f + 0.002)) continue
            radial(tm.angleOf(f), r0, outerR, rayPaint)
        }
    }

    // ── Обод (снаружи чёткий край) и обвод дырки ────────────────────────────
    ringPath(outerR)?.let { path ->
        strokeScreen(path, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = lineW(lk.ringWidth)
            color = lk.ringColor
            if (glowLocal > 0f) setShadowLayer(glowLocal * 0.8f * sF, 0f, 0f, withAlpha(lk.accent, 0.9f))
        })
    }
    if (ds.ring && ds.holeLine) ringPath(holeR)?.let { path ->
        strokeScreen(path, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = lineW(if (ds.outline > 0f) 2.4 else 1.4)
            color = if (ds.outline > 0f) lk.ringColor else withAlpha(lk.accent, 0.7f)
        })
    }

    // ── Подписи времени: фрактальная лестница день → час → минута → секунда ─
    // Размер привязан к кругу (масштабируется вместе с ним, без скачков).
    // День — крупное число на весь день; часы/минуты/секунды — каждое мельче
    // (пропорционально своему интервалу) и ближе к краю, «над» крупным.
    run {
        val steps = TIME_STEPS
        val fonts = timeLabelFonts(outerR, totalSec)
        val rows = timeLabelRows(outerR, totalSec, digitH)
        for (i in 0..3) {
            val f = fonts[i]
            val vis = tv(TIER_LABEL0 + i)
            if (vis <= 0f) continue
            val cv = tc(TIER_LABEL0 + i)
            val rc = rows[i]
            if (rc + f < lod.rMin || rc - f > lod.rMax) continue
            val step = steps[i]
            val k0 = floor(fLo * totalSec / step).toLong()
            val k1 = ceil(fHi * totalSec / step).toLong()
            if (k1 - k0 > 3000) continue
            for (k in k0..k1) {
                val f0 = k * step / totalSec
                val f1 = (k + 1) * step / totalSec
                if (!visible(f0, f1)) continue
                val text = if (i == 0) tm.yearStart.plusDays(k).dayOfMonth.toString()
                else "%02d".format(Math.floorMod(k, if (i == 1) 24L else 60L))
                val fmid = (f0 + f1) / 2
                val c = if (fmid <= nowFrac) lk.dayNumPast else lk.dayNumFuture
                label(text, tm.angleOf(fmid), rc, (f * s).toFloat(), mulAlpha(c, vis), digits = true, cv = cv)
            }
        }
    }

    // ── Подписи месяцев — по дуге, всегда читаемые ──────────────────────────
    val midR = if (ds.ring) max((outerR + g.innerR) / 2, holeR + 26 * unit) else (outerR + g.innerR) / 2
    val monthNames = if (lk.font.caps) MONTHS_FULL.map { it.uppercase() } else MONTHS_FULL.toList()
    val monthSize = (if (lk.font.caps) 11.0 else 13.5) * unit
    for (m in 1..12) {
        val f0 = monthFrac(m)
        val f1 = monthFrac(m + 1)
        if (!visible(f0, f1)) continue
        arcLabel(
            monthNames[m - 1], tm.angleOf((f0 + f1) / 2), midR, monthSize,
            if (f1 <= nowFrac) lk.labelPast else lk.labelFuture,
        )
    }

    // ── Орбиты планет (по настройке; бледные) ───────────────────────────────
    val au = g.tipR
    if (settings.showOrbits) {
        val orbitPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = (0.8 / s).toFloat()
            color = if (lk.dark) withAlpha(WHITE, 0.06f) else withAlpha(BLACK, 0.10f)
        }
        val pt = DoubleArray(2)
        val path = Path()
        fun drawOrbit(p: Planet) {
            val o = p.elements(tm.jdNow)
            val rLo = o.a * (1 - o.e) * au * 0.999
            val rHi = o.a * (1 + o.e) * au * 1.001
            if (rHi < lod.rMin || rLo > lod.rMax) return
            // Окно углов → долготы → истинная аномалия (ν ≈ λ − ϖ, запас на наклон).
            var nu0 = 0.0
            var nu1 = 2 * PI
            if (!lod.full) {
                val l1 = tm.lamOfAngle(lod.aLo)
                val l2 = tm.lamOfAngle(lod.aHi)
                nu0 = min(l1, l2) - o.peri - 0.12
                nu1 = max(l1, l2) - o.peri + 0.12
            }
            val rs = o.a * au * s
            val step = if (rs > 2) sqrt(2.4 / rs) else 0.6
            val n = ceil((nu1 - nu0) / step).toInt().coerceIn(8, 4096)
            path.reset()
            for (i in 0..n) {
                p.pointAt(o, nu0 + (nu1 - nu0) * i / n, pt)
                val a = tm.spaceAngle(atan2(pt[1], pt[0]))
                val r = hypot(pt[0], pt[1]) * au
                val x = (lx + cos(a) * r).toFloat()
                val y = (ly + sin(a) * r).toFloat()
                if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            canvas.drawPath(path, orbitPaint)
        }
        for (p in Astro.OTHER_PLANETS) drawOrbit(p)
        if (tm.kepler) drawOrbit(Astro.EARTH)
    }

    // ── События — дуги-ленточки ──────────────────────────────────────────────
    val bandW = (if (ds.rays) 5.0 else 7.0) * unit
    val bandGap = 1.5 * unit
    val laneGap = 0.6 * unit
    val eventBandBaseR = eventBaseR(outerR, totalSec, digitH)

    if (events.all.isNotEmpty()) {
        val minBandR = max(g.innerR, holeR) + bandW / 2 + bandGap
        val availableSpace = eventBandBaseR - bandW / 2 - minBandR
        val maxVisibleLayers =
            if (availableSpace > 0) (floor(availableSpace / (bandW + bandGap)) + 1).toInt() else 1

        val bandPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
        val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
        }
        val bandPath = Path()
        val pxPerFrac = 2.0 * PI * eventBandBaseR * s

        val overflowBuckets = HashMap<Int, Int>()
        val overflowFrac = HashMap<Int, Double>()
        // Крошечные на экране события (короче ~3px) — не squircle-путь каждое,
        // а четырёхугольник в общий путь своего цвета: один drawPath на цвет.
        val tiny = HashMap<Int, Path>()
        val tinyPx = 3.0
        var glows = 0

        if (RDBG and 128 == 0) events.forVisible(lod.full, fLo, fHi) { b ->
            if (!visible(b.startFrac, b.endFrac)) return@forVisible

            if (b.layer >= maxVisibleLayers) {
                val key = (b.startFrac * 50).toInt()
                overflowBuckets[key] = (overflowBuckets[key] ?: 0) + 1
                overflowFrac.putIfAbsent(key, b.startFrac)
                return@forVisible
            }

            val sweepFrac = b.endFrac - b.startFrac
            if (sweepFrac * pxPerFrac < 0.5) return@forVisible

            val rRow = (eventBandBaseR - b.layer * (bandW + bandGap) - bandW / 2)
                .coerceIn(minBandR, eventBandBaseR)
            // Дорожка внутри ряда (короткие пересекающиеся делят ряд по радиусу).
            val laneW = (bandW - (b.lanes - 1) * laneGap) / b.lanes
            val r = rRow + bandW / 2 - laneW / 2 - b.lane * (laneW + laneGap)
            if (r + laneW / 2 < lod.rMin || r - laneW / 2 > lod.rMax) return@forVisible

            val a0 = tm.angleOf(b.startFrac)
            val a1 = tm.angleOf(b.endFrac)

            val isPast = b.endFrac <= nowFrac
            val alpha = if (isPast) lk.eventPastAlpha else lk.eventFutureAlpha
            val col = lk.eventColor(b.event.color, b.event.calendarId)
            var textBg = col

            val lenPx = abs(a1 - a0) * r * s
            if (lenPx < tinyPx || laneW * s < tinyPx) {
                val key = withAlpha(col, alpha)
                val path = tiny.getOrPut(key) { Path() }
                val am = (a0 + a1) / 2
                val ha = max(abs(a1 - a0), 0.8 / (r * s)) / 2
                val hw = max(laneW / 2, 0.4 / s)
                path.moveTo(px(am - ha, r - hw), py(am - ha, r - hw))
                path.lineTo(px(am - ha, r + hw), py(am - ha, r + hw))
                path.lineTo(px(am + ha, r + hw), py(am + ha, r + hw))
                path.lineTo(px(am + ha, r - hw), py(am + ha, r - hw))
                path.close()
                return@forVisible
            }

            // Форма: изогнутый squircle точной длины. «Пилюли» — скругление на
            // всю полуширину; короткое событие сужается с боков (не круг).
            val halfW = laneW / 2
            val roundK = if (ds.roundEvents) 1.0 else 0.7
            if (!buildBandPath(bandPath, lx, ly, r, halfW, a0, a1, s, ::clip, roundK)) return@forVisible
            val ow = if (ds.outline > 0f) max(laneW * 0.16 * ds.outline, 1.1 / s) else 0.0
            val fillCol = if (ds.outline > 0f) mix(lk.bd.bg1, col, 0.10f) else col
            textBg = fillCol
            bandPaint.color = withAlpha(fillCol, alpha)
            // Свечение (размытие) — дорогая растеризация слоя: только у заметных
            // на экране лент и не больше GLOW_MAX на сцену; у мелких его не видно.
            if (RDBG and 32 == 0 && glowLocal > 0f && !isPast && lenPx >= 12 && glows < GLOW_MAX) {
                bandPaint.setShadowLayer(glowLocal * 0.7f, 0f, 0f, withAlpha(col, 0.85f))
                glows++
            }
            canvas.drawPath(bandPath, bandPaint)
            bandPaint.clearShadowLayer()
            if (ow > 0) {
                strokePaint.strokeCap = Paint.Cap.BUTT
                strokePaint.strokeJoin = Paint.Join.ROUND
                strokePaint.strokeWidth = (ow * 1.6).toFloat()
                strokePaint.color = withAlpha(col, alpha)
                canvas.drawPath(bandPath, strokePaint)
            } else if (ds.volume > 0f && abs(a1 - a0) * r > laneW * 2) {
                // Мультяшный блик: светлая полоска по внешней кромке.
                val capA = halfW / r * (if (a1 >= a0) 1.0 else -1.0)
                clip(a0 + capA, a1 - capA)?.let { c ->
                    bandPath.reset()
                    arcPoly(bandPath, lx, ly, r + halfW * 0.5, c[0], c[1], s, true)
                    strokePaint.strokeCap = Paint.Cap.ROUND
                    strokePaint.strokeWidth = (laneW * 0.13).toFloat()
                    strokePaint.color = withAlpha(WHITE, 0.30f * ds.volume * alpha)
                    canvas.drawPath(bandPath, strokePaint)
                }
            }

            if (RDBG and 64 == 0) drawBandTitle(
                canvas, textPaint, b, lx, ly, r, laneW, a0, a1,
                textBg, alpha, face,
                bottom = onBottom((a0 + a1) / 2),
                isDayPlus = sweepFrac >= LARGE_BAND_FRAC,
                ccw = settings.counterClockwise,
                camScale = s,
            )
        }
        if (tiny.isNotEmpty()) {
            val tp = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
            for ((c, path) in tiny) { tp.color = c; canvas.drawPath(path, tp) }
        }

        if (overflowBuckets.isNotEmpty()) {
            val overflowR = (minBandR + bandGap * 0.5).coerceIn(g.innerR + bandW * 0.5, eventBandBaseR)
            val overflowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                strokeWidth = (bandW * 0.6).toFloat()
                strokeCap = Paint.Cap.ROUND
                color = withAlpha(lk.accent, 0.55f)
            }
            for ((key, count) in overflowBuckets) {
                val f = overflowFrac[key]!!
                val a0 = tm.angleOf(f)
                val a1 = tm.angleOf(f + 0.015)
                val path = Path()
                arcPoly(path, lx, ly, overflowR, a0, a1, s, true)
                canvas.drawPath(path, overflowPaint)
                label("+$count", (a0 + a1) / 2, overflowR, (bandW * 0.9 * s).toFloat().coerceIn(6f, 40f),
                    withAlpha(lk.ink, 0.85f))
            }
        }
    }

    // ── Риски времени — ПОВЕРХ событий: иначе на зуме дней/минут/секунд
    // их целиком закрывали ленты (на телефоне с реальным календарём лент
    // много — деления «пропадали»). В кольце начинаются от дырки.
    run {
        val r0 = if (ds.ring) holeR else 0.0
        fun timeTicksFrom(step: Long, skip: Long, p: Paint, cv: Canvas) {
            val k0 = ceil(fLo * totalSec / step).toLong()
            val k1 = floor(fHi * totalSec / step).toLong()
            if (k1 - k0 > 6000) return
            for (k in k0..k1) {
                val sec = k * step
                if (skip > 0 && Math.floorMod(sec, skip) == 0L) continue
                radial(tm.angleOf(sec / totalSec), r0, outerR, p, cv)
            }
        }
        val weekVis = tv(TIER_WEEK); val dayVis = tv(TIER_DAY)
        val hourVis = tv(TIER_HOUR); val minVis = tv(TIER_MIN); val secVis = tv(TIER_SEC)
        if (!ds.rays) {
            if (weekVis > 0f) {
                val p = tickPaint(line, ta * 2.6f * weekVis, lineW(0.55, 1.0))
                val cv = tc(TIER_WEEK)
                val k0 = ceil(fLo * totalSec / 86400).toLong()
                val k1 = floor(fHi * totalSec / 86400).toLong()
                val dow0 = tm.yearStart.dayOfWeek.value - 1 // 0 = понедельник
                for (k in k0..k1) {
                    if (Math.floorMod(dow0 + k, 7L) != 0L) continue
                    radial(tm.angleOf(k * 86400.0 / totalSec), r0, outerR, p, cv)
                }
            }
            if (dayVis > 0f) timeTicksFrom(86400, 0, tickPaint(line, ta * 2.0f * dayVis, lineW(0.42, 1.0)), tc(TIER_DAY))
        }
        // Иерархия: неделя > день > час > минута > секунда (и по яркости, и по толщине).
        if (hourVis > 0f) timeTicksFrom(3600, 86400, tickPaint(line, ta * 1.0f * hourVis, lineW(0.175)), tc(TIER_HOUR))
        if (minVis > 0f) timeTicksFrom(60, 3600, tickPaint(line, ta * 0.85f * minVis, lineW(0.12)), tc(TIER_MIN))
        if (secVis > 0f) timeTicksFrom(1, 60, tickPaint(line, ta * 0.75f * secVis, lineW(0.08)), tc(TIER_SEC))
    }

}

/**
 * Динамический слой: стрелка «сейчас», Солнце, планеты, Земля, Луна.
 * Рисуется КАЖДЫЙ кадр поверх кэшированной сцены (дёшево: десяток фигур),
 * поэтому планеты и стрелка двигаются плавно в реальном времени.
 */
fun drawBodies(
    canvas: Canvas,
    width: Float,
    height: Float,
    tm: TimeMap,
    settings: WheelSettings,
    lod: Lod,
) {
    val g = WheelGeom(width, height)
    val s = lod.camScale
    val unit = g.unit
    val lx = g.cx - lod.originX
    val ly = g.cy - lod.originY
    val au = g.tipR

    fun lineW(base: Double, minPx: Double): Float {
        val screen = if (s >= 1) base * unit * min(s.pow(0.15), 2.0) else base * unit * s
        return (max(screen, minPx) / s).toFloat()
    }
    fun fade(v: Double, lo: Double, hi: Double): Float = ((v - lo) / (hi - lo)).coerceIn(0.0, 1.0).toFloat()
    fun onScreen(x: Double, y: Double, r: Double) = hypot(x, y) < lod.visR + r + 60 / s

    // ── Стрелка: от Солнца до Земли ──────────────────────────────────────────
    val lk = settings.look
    val accent = lk.arrowColor
    val glowLocal = (lk.d.glow * 16.0 * lod.density / 2.75 / s).toFloat()
    val nowAngle = tm.nowAngle
    val earthR = au * tm.earthDistAu
    val ex = lx + cos(nowAngle) * earthR
    val ey = ly + sin(nowAngle) * earthR
    val earthRc = au * Astro.EARTH.radiusKm / AU_KM
    val earthPx = earthRc * s

    run {
        val ra = max(0.0, lod.rMin - 2 / s)
        val rb = min(earthR, lod.rMax + 2 / s)
        if (ra < rb) {
            // В экранных px (см. radial в drawWheel): точность мобильных GPU.
            val sf = s.toFloat()
            canvas.save()
            canvas.scale(1f / sf, 1f / sf)
            canvas.drawLine(
                ((lx + cos(nowAngle) * ra) * s).toFloat(), ((ly + sin(nowAngle) * ra) * s).toFloat(),
                ((lx + cos(nowAngle) * rb) * s).toFloat(), ((ly + sin(nowAngle) * rb) * s).toFloat(),
                Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = accent
                    strokeWidth = lineW(3.0, 1.5) * sf
                    strokeCap = Paint.Cap.ROUND
                    if (glowLocal > 0f) setShadowLayer(glowLocal * sf, 0f, 0f, withAlpha(accent, 0.9f))
                },
            )
            canvas.restore()
        }
    }

    // Ромбик на кончике — пока Земля не видна диском; дальше его заменяет она.
    val diamondAlpha = 1f - fade(earthPx, 3.0, 9.0)
    if (diamondAlpha > 0f && onScreen(ex, ey, 0.0)) {
        val size = lineW(3.8, 3.0).toDouble()
        val ca = cos(nowAngle); val sa = sin(nowAngle)
        val path = Path()
        path.moveTo((ex + ca * size).toFloat(), (ey + sa * size).toFloat())
        path.lineTo((ex - sa * size * 0.55).toFloat(), (ey + ca * size * 0.55).toFloat())
        path.lineTo((ex - ca * size * 0.55).toFloat(), (ey - sa * size * 0.55).toFloat())
        path.lineTo((ex + sa * size * 0.55).toFloat(), (ey - ca * size * 0.55).toFloat())
        path.close()
        canvas.drawPath(path, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = withAlpha(accent, diamondAlpha) })
    }

    val bodyPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    /**
     * Диск с освещённой стороной к Солнцу (мягкий терминатор). Пока тело
     * меньше пары пикселей — еле заметная точка (пасхалка, не мешает);
     * по мере приближения проявляется в полную силу.
     */
    fun body(x: Double, y: Double, rc: Double, day: Int, night: Int) {
        if (!onScreen(x, y, rc)) return
        val px = rc * s
        val alpha = 0.45f + 0.55f * fade(px, 1.5, 6.0)
        val fx = x.toFloat(); val fy = y.toFloat()
        if (px < 1.5) {
            bodyPaint.shader = null
            bodyPaint.color = withAlpha(day, alpha)
            canvas.drawCircle(fx, fy, (1.5 / s).toFloat(), bodyPaint)
            return
        }
        val dx = lx - x; val dy = ly - y
        val d = max(hypot(dx, dy), 1e-12)
        val ux = (dx / d * rc).toFloat(); val uy = (dy / d * rc).toFloat()
        bodyPaint.shader = LinearGradient(
            fx - ux, fy - uy, fx + ux, fy + uy,
            intArrayOf(night, night, day, lighten(day)),
            floatArrayOf(0f, 0.44f, 0.56f, 1f),
            Shader.TileMode.CLAMP,
        )
        bodyPaint.alpha = (alpha * 255).toInt()
        canvas.drawCircle(fx, fy, rc.toFloat(), bodyPaint)
        bodyPaint.shader = null
        bodyPaint.alpha = 255
    }

    // Солнце: корона + диск с потемнением к краю.
    run {
        val sunR = au * Astro.SUN_RADIUS_KM / AU_KM
        if (!onScreen(lx, ly, sunR * 8)) return@run
        val diskR = max(sunR, 1.2 / s)
        val glowR = max(diskR * 5, 7.0 / s)
        val k = (diskR / glowR).toFloat()
        bodyPaint.shader = RadialGradient(
            lx.toFloat(), ly.toFloat(), glowR.toFloat(),
            intArrayOf(0xFFFFE9A8.toInt(), 0x55FFC24D, 0x1AFFA53A, 0x00FF9A30),
            floatArrayOf(k * 0.9f, k * 1.1f, k * 2.2f, 1f),
            Shader.TileMode.CLAMP,
        )
        canvas.drawCircle(lx.toFloat(), ly.toFloat(), glowR.toFloat(), bodyPaint)
        bodyPaint.shader = RadialGradient(
            lx.toFloat(), ly.toFloat(), diskR.toFloat(),
            intArrayOf(0xFFFFFCF0.toInt(), 0xFFFFE7A0.toInt(), 0xFFF7B548.toInt()),
            floatArrayOf(0f, 0.7f, 1f),
            Shader.TileMode.CLAMP,
        )
        canvas.drawCircle(lx.toFloat(), ly.toFloat(), diskR.toFloat(), bodyPaint)
        bodyPaint.shader = null
    }

    // Планеты.
    for (p in Astro.OTHER_PLANETS) {
        val o = p.at(tm.jdNow)
        val a = tm.spaceAngle(o.lambda)
        val x = lx + cos(a) * o.r * au
        val y = ly + sin(a) * o.r * au
        val rc = au * p.radiusKm / AU_KM
        if (p === Astro.SATURN && rc * s >= 1.2 && onScreen(x, y, rc * 2.3)) {
            canvas.drawCircle(x.toFloat(), y.toFloat(), (rc * 1.75).toFloat(), Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                strokeWidth = (rc * 0.95).toFloat()
                color = withAlpha(0xFFE6D3A0.toInt(), 0.33f * fade(rc * s, 1.0, 5.0))
            })
        }
        body(x, y, rc, p.color, p.nightColor)
    }

    // Земля и Луна.
    if (onScreen(ex, ey, au * 0.003)) {
        val moon = Astro.moon(tm.jdNow)
        val md = au * moon[1] / AU_KM
        val ma = tm.spaceAngle(moon[0])
        val mdPx = md * s
        if (settings.showOrbits && mdPx in 14.0..8000.0) {
            canvas.drawCircle(ex.toFloat(), ey.toFloat(), md.toFloat(), Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                strokeWidth = (0.8 / s).toFloat()
                color = withAlpha(WHITE, 0.06f)
            })
        }
        if (diamondAlpha < 0.5f) {
            body(ex, ey, earthRc, Astro.EARTH.color, Astro.EARTH.nightColor)
            if (earthPx > 5) {
                canvas.drawCircle(ex.toFloat(), ey.toFloat(), (earthRc * 1.035).toFloat(), Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    style = Paint.Style.STROKE
                    strokeWidth = (earthRc * 0.05).toFloat()
                    color = 0x5580C8FF
                })
            }
        }
        if (mdPx >= 14) {
            body(
                ex + cos(ma) * md, ey + sin(ma) * md, au * Astro.MOON_RADIUS_KM / AU_KM,
                0xFFCFCFCF.toInt(), 0xFF2A2A2E.toInt(),
            )
        }
    }
}

// ── Слои LOD: риски недели/дня/часа/минуты/секунды и подписи дня/часа/мин/сек ─
const val TIER_WEEK = 0
const val TIER_DAY = 1
const val TIER_HOUR = 2
const val TIER_MIN = 3
const val TIER_SEC = 4
const val TIER_LABEL0 = 5 // 5..8 — подписи дня, часа, минуты, секунды
const val TIER_N = 9
/** Запас зума, на который слои пишутся заранее (перезапись — раньше, при ×1.12). */
const val TIER_REACH = 1.35

private fun fadeF(v: Double, lo: Double, hi: Double): Float = ((v - lo) / (hi - lo)).coerceIn(0.0, 1.0).toFloat()

/**
 * Яркость слоя LOD на зуме [s]. Порог появления рисок — экранное расстояние
 * между соседними рисками вида; подписей — их экранный кегль.
 */
fun tierVis(i: Int, s: Double, outerR: Double, totalSec: Double): Float {
    val pxPerSec = 2.0 * PI * outerR * s / totalSec
    return when (i) {
        TIER_WEEK -> fadeF(pxPerSec * 86400 * 7, 30.0, 60.0)
        TIER_DAY, TIER_LABEL0 -> fadeF(pxPerSec * 86400, 25.0, 50.0)
        TIER_HOUR -> fadeF(pxPerSec * 3600, 12.0, 25.0)
        TIER_MIN -> fadeF(pxPerSec * 60, 10.0, 22.0)
        TIER_SEC -> fadeF(pxPerSec, 10.0, 22.0)
        else -> fadeF(timeLabelFonts(outerR, totalSec)[i - TIER_LABEL0] * s, 6.0, 10.0)
    }
}

// ── Лесенка подписей времени (общая для рендера и отладки) ─────────────────
val TIME_STEPS = longArrayOf(86400, 3600, 60, 1)

/** Высота цифр шрифта (доля кегля) — по реальным границам глифов. */
val DIGIT_H: Double by lazy { digitHeight(Typeface.DEFAULT) }

private val digitHCache = java.util.concurrent.ConcurrentHashMap<Typeface, Double>()

fun digitHeight(tf: Typeface): Double = digitHCache.getOrPut(tf) {
    val p = Paint(Paint.ANTI_ALIAS_FLAG)
    p.textSize = 100f
    p.typeface = tf
    val b = android.graphics.Rect()
    p.getTextBounds("0123456789", 0, 10, b)
    (b.bottom - b.top) / 100.0
}

/** Кегль ряда: доля своего интервала (день 0.6, мельче 0.5) — соседи не слипаются. */
fun timeLabelFonts(outerR: Double, totalSec: Double) = DoubleArray(4) { i ->
    (if (i == 0) 0.6 else 0.5) * 2 * PI * outerR * TIME_STEPS[i] / totalSec
}

/**
 * Радиусы центров рядов: число дня у края, над ним (к центру) часы, минуты,
 * секунды. Отступ от крупного ряда — 15% высоты его цифр + высота мелкой
 * цифры: ряды не касаются ни на каком зуме.
 */
/**
 * Внешний край ленточек событий: сразу за лесенкой подписей (её последний
 * ряд) + зазор 0.35 высоты цифры дня. Раньше был фиксированный отступ
 * 6.5u — между числами и событиями зияла пустота.
 */
fun eventBaseR(outerR: Double, totalSec: Double, h: Double): Double {
    val f = timeLabelFonts(outerR, totalSec)
    val rows = timeLabelRows(outerR, totalSec, h)
    return rows[3] - h * f[3] / 2 - 0.35 * h * f[0]
}

fun timeLabelRows(outerR: Double, totalSec: Double, h: Double = DIGIT_H): DoubleArray {
    val f = timeLabelFonts(outerR, totalSec)
    val rows = DoubleArray(4)
    // От обода до числа дня — 0.6 высоты цифры (было 0.35: цифры липли к ободу).
    var edge = 0.6 * h * f[0]
    for (i in 0..3) {
        if (i > 0) edge += 0.15 * h * f[i - 1] + 1.0 * h * f[i]
        rows[i] = outerR - edge - h * f[i] / 2
        edge += h * f[i]
    }
    return rows
}

private fun lighten(c: Int): Int {
    val r = (((c shr 16) and 0xFF) + 255) / 2
    val g = (((c shr 8) and 0xFF) + 255) / 2
    val b = ((c and 0xFF) + 255) / 2
    return (c and 0xFF000000.toInt()) or (r shl 16) or (g shl 8) or b
}

// ─────────────────────────────────────────────────────────────────────────────
// «Искривлённый squircle» — кольцевой сектор со скруглёнными углами.
// Обрезается угловым окном: срезанный край (вне экрана) — прямой.
// ─────────────────────────────────────────────────────────────────────────────

private fun buildBandPath(
    path: Path,
    cx: Double,
    cy: Double,
    r: Double,
    halfW: Double,
    a0: Double,
    a1: Double,
    scale: Double,
    clip: (Double, Double) -> DoubleArray?,
    roundK: Double = 0.7,
): Boolean {
    val c = clip(a0, a1) ?: return false
    val ro = r + halfW
    val ri = r - halfW
    val sweep = a1 - a0
    val sgn = if (sweep >= 0) 1.0 else -1.0
    // Радиус скругления: доля полуширины, но не больше половины длины —
    // короткое событие становится узким скруглённым столбиком, а не кругом.
    val cr = min(halfW * roundK, abs(sweep) * r / 2)
    val ca = (cr / r) * sgn
    val startCut = c[0] != a0
    val endCut = c[1] != a1

    fun x(a: Double, rad: Double): Float = (cx + cos(a) * rad).toFloat()
    fun y(a: Double, rad: Double): Float = (cy + sin(a) * rad).toFloat()

    // Угол — кубика с k = 0.5523 (четверть окружности), не квадратика:
    // концы пилюли круглые, а не «гранёные».
    fun corner(sa: Double, sr: Double, ca_: Double, cr_: Double, ea: Double, er: Double) {
        val k = 0.5523
        val sx = x(sa, sr); val sy = y(sa, sr)
        val qx = x(ca_, cr_); val qy = y(ca_, cr_)
        val ex = x(ea, er); val ey = y(ea, er)
        path.cubicTo(
            (sx + (qx - sx) * k).toFloat(), (sy + (qy - sy) * k).toFloat(),
            (ex + (qx - ex) * k).toFloat(), (ey + (qy - ey) * k).toFloat(),
            ex, ey,
        )
    }

    path.reset()
    arcPoly(path, cx, cy, ro, if (startCut) c[0] else a0 + ca, if (endCut) c[1] else a1 - ca, scale, true)
    if (!endCut) {
        corner(a1 - ca, ro, a1, ro, a1, ro - cr)
        path.lineTo(x(a1, ri + cr), y(a1, ri + cr))
        corner(a1, ri + cr, a1, ri, a1 - ca, ri)
    } else {
        path.lineTo(x(c[1], ri), y(c[1], ri))
    }
    arcPoly(path, cx, cy, ri, if (endCut) c[1] else a1 - ca, if (startCut) c[0] else a0 + ca, scale, false)
    if (!startCut) {
        corner(a0 + ca, ri, a0, ri, a0, ri + cr)
        path.lineTo(x(a0, ro - cr), y(a0, ro - cr))
        corner(a0, ro - cr, a0, ro, a0 + ca, ro)
    }
    path.close()
    return true
}

// ─────────────────────────────────────────────────────────────────────────────
// Название события.
//  * ≥ 1 дня — текст по дуге, низом к стрелке «сейчас».
//  * < 1 дня — текст вдоль радиуса, низ глифов назад по времени.
// Кегль дефолтный, ужимается до влезания; проявляется по зуму (≥ 5px).
// ─────────────────────────────────────────────────────────────────────────────

private fun drawBandTitle(
    canvas: Canvas,
    paint: Paint,
    band: EventBand,
    cx: Double,
    cy: Double,
    r: Double,
    bandW: Double,
    a0: Double,
    a1: Double,
    bandColor: Int,
    bandAlpha: Float,
    face: Typeface,
    bottom: Boolean,
    isDayPlus: Boolean,
    ccw: Boolean,
    camScale: Double,
) {
    val title = band.event.title
    if (title.isBlank()) return
    val arcLen = abs(a1 - a0) * r
    // Кегль не больше стартового — если даже он мельче порога, раскладку не считаем.
    val maxFs = if (isDayPlus) bandW * 0.52 else min(bandW * 0.30, arcLen / 1.25)
    if (maxFs * camScale < TEXT_MIN_SCREEN_FS) return

    // Раскладка (кегль + строки) не зависит от зума — считается один раз на
    // событие и геометрию, а не на каждую перезапись сцены.
    var tl = band.title
    if (tl == null || tl.bandW != bandW || tl.arcLen != arcLen || tl.face !== face || tl.dayPlus != isDayPlus) {
        val text = if (title.length > 200) title.take(200).trimEnd() + "…" else title
        paint.typeface = face
        paint.letterSpacing = 0f
        tl = if (isDayPlus) layoutArcTitle(paint, text, arcLen, bandW, face)
        else layoutRadialTitle(paint, text, arcLen, bandW, face)
        band.title = tl
    }
    val ls = tl.lines ?: return
    val fs = tl.fs
    if (fs * camScale < TEXT_MIN_SCREEN_FS || fs * camScale > TEXT_MAX_SCREEN_FS) return

    val lum = 0.299f * ((bandColor shr 16) and 0xFF) +
        0.587f * ((bandColor shr 8) and 0xFF) +
        0.114f * (bandColor and 0xFF)
    paint.color = if (lum * bandAlpha > 150f) 0xD9140A1E.toInt() else 0xF0FFFFFF.toInt()
    paint.typeface = face
    paint.letterSpacing = 0f
    paint.textSize = fs

    if (isDayPlus) {
        // Путь по дуге: внизу экрана — по убыванию угла (текст читается прямо),
        // вверху — по возрастанию (переворачивается, чтобы тоже читался).
        paint.textAlign = Paint.Align.LEFT
        val lo = min(a0, a1); val hi = max(a0, a1)
        val path = Path()
        if (bottom) arcPoly(path, cx, cy, r, hi, lo, camScale, true)
        else arcPoly(path, cx, cy, r, lo, hi, camScale, true)
        val base = -(paint.ascent() + paint.descent()) / 2f
        val n = ls.size
        for (i in 0 until n) {
            val v = (i - (n - 1) / 2f) * fs * 1.15f + base
            canvas.drawTextOnPath(ls[i], path, (arcLen.toFloat() - tl.widths[i]) / 2f, v, paint)
        }
    } else {
        paint.textAlign = Paint.Align.CENTER
        val amid = (a0 + a1) / 2
        val dirSign = if (ccw) 1.0 else -1.0
        val dx = -sin(amid) * dirSign
        val dy = cos(amid) * dirSign
        val rotDeg = (atan2(-dx, dy) * 180.0 / PI).toFloat()
        canvas.save()
        canvas.translate((cx + cos(amid) * r).toFloat(), (cy + sin(amid) * r).toFloat())
        canvas.rotate(rotDeg)
        val base = -(paint.ascent() + paint.descent()) / 2f
        val n = ls.size
        for (i in 0 until n) {
            val y = (i - (n - 1) / 2f) * fs * 1.25f + base
            canvas.drawText(ls[i], 0f, y, paint)
        }
        canvas.restore()
    }
    paint.textAlign = Paint.Align.CENTER
}

private const val TEXT_MIN_SCREEN_FS = 5.0
private const val GLOW_MAX = 48
private const val TEXT_MAX_SCREEN_FS = 1500.0

/** Раскладка названия события (кэшируется в [EventBand.title]). */
class TitleLayout(
    val bandW: Double, val arcLen: Double, val face: Typeface, val dayPlus: Boolean,
    val fs: Float, val lines: List<String>?, val widths: FloatArray,
)

private fun layoutArcTitle(paint: Paint, text: String, arcLen: Double, bandW: Double, face: Typeface): TitleLayout {
    val avail = (arcLen * 0.90).toFloat()
    var fs = (bandW * 0.52).toFloat()
    var lines: List<String>? = null
    if (avail > 0f) while (fs >= bandW * 0.02) {
        paint.textSize = fs
        val maxLines = min(8, (bandW * 0.92 / (fs * 1.15)).toInt().coerceAtLeast(1))
        val wrapped = wrapText(text, paint, avail, maxLines)
        if (wrapped != null) { lines = wrapped; break }
        fs *= 0.84f
    }
    paint.textSize = fs
    val widths = FloatArray(lines?.size ?: 0) { paint.measureText(lines!![it]) }
    return TitleLayout(bandW, arcLen, face, true, fs, lines, widths)
}

private fun layoutRadialTitle(paint: Paint, text: String, arcLen: Double, bandW: Double, face: Typeface): TitleLayout {
    val angW = arcLen.toFloat()
    val maxLen = (bandW * 0.9).toFloat()
    var fs = min((bandW * 0.30).toFloat(), angW / 1.25f)
    var lines: List<String>? = null
    while (fs >= bandW * 0.01) {
        paint.textSize = fs
        val maxLines = min(8, (angW / (fs * 1.25f)).toInt().coerceAtLeast(1))
        val wrapped = wrapText(text, paint, maxLen, maxLines)
        if (wrapped != null) { lines = wrapped; break }
        fs *= 0.84f
    }
    return TitleLayout(bandW, arcLen, face, false, fs, lines, FloatArray(0))
}

private fun wrapText(text: String, paint: Paint, maxW: Float, maxLines: Int): List<String>? {
    val words = text.split(' ').filter { it.isNotEmpty() }
    if (words.isEmpty()) return null
    val lines = ArrayList<String>(maxLines)
    var cur = StringBuilder()
    for (w in words) {
        val cand = if (cur.isEmpty()) w else "$cur $w"
        if (paint.measureText(cand) <= maxW) {
            cur.setLength(0); cur.append(cand)
        } else {
            if (cur.isEmpty()) return null
            lines.add(cur.toString())
            if (lines.size >= maxLines) return null
            cur = StringBuilder(w)
            if (paint.measureText(w) > maxW) return null
        }
    }
    if (cur.isNotEmpty()) lines.add(cur.toString())
    return if (lines.size <= maxLines) lines else null
}

/**
 * Индекс событий по времени: короткие отсортированы по началу — в видимое
 * окно попадаем двоичным поиском, а не перебором тысяч экземпляров
 * повторяющихся событий; длинные (их мало) проверяются все.
 */
class EventIndex(bands: List<EventBand>) {
    val all = bands
    private val short: Array<EventBand>
    private val shortStart: DoubleArray
    private val long: List<EventBand>

    init {
        val (sh, lg) = bands.partition { it.endFrac - it.startFrac <= SHORT_MAX }
        short = sh.sortedBy { it.startFrac }.toTypedArray()
        shortStart = DoubleArray(short.size) { short[it].startFrac }
        long = lg
    }

    /** Все события, которые могут пересечь окно [fLo, fHi] (+ сдвиги на годы). */
    inline fun forVisible(full: Boolean, fLo: Double, fHi: Double, f: (EventBand) -> Unit) {
        if (full || fHi - fLo > 0.9) { for (b in all) f(b); return }
        forLong(f)
        for (k in -2..2) forShort(fLo + k - SHORT_MAX, fHi + k, f)
    }

    inline fun forLong(f: (EventBand) -> Unit) { for (b in longList()) f(b) }

    inline fun forShort(from: Double, to: Double, f: (EventBand) -> Unit) {
        val arr = shortArr()
        var i = lowerBound(from)
        while (i < arr.size && arr[i].startFrac < to) { f(arr[i]); i++ }
    }

    fun longList(): List<EventBand> = long
    fun shortArr(): Array<EventBand> = short

    fun lowerBound(x: Double): Int {
        var lo = 0; var hi = shortStart.size
        while (lo < hi) { val m = (lo + hi) ushr 1; if (shortStart[m] < x) lo = m + 1 else hi = m }
        return lo
    }

    companion object { const val SHORT_MAX = 0.01 } // ~3.6 суток
}

@Volatile var RDBG = 0 // отладка рендера сцены (см. WheelView.dbg)
