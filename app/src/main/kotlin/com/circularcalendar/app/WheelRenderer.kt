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
    bands: List<EventBand>,
    lod: Lod,
) {
    val th = settings.theme
    val g = WheelGeom(width, height)
    val s = lod.camScale
    val sF = s.toFloat()
    val unit = g.unit
    val outerR = g.outerR
    val dens = lod.density
    // Центр круга в локальных координатах.
    val lx = g.cx - lod.originX
    val ly = g.cy - lod.originY

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

    fun radial(a: Double, r0: Double, r1: Double, p: Paint) {
        val ra = max(r0, lod.rMin - 2.0 / s)
        val rb = min(r1, lod.rMax + 2.0 / s)
        if (ra >= rb || !angleVisible(a)) return
        canvas.drawLine(px(a, ra), py(a, ra), px(a, rb), py(a, rb), p)
    }

    // Экранная толщина линий почти постоянна: растёт с зумом лишь как
    // zoom^0.15 (максимум ×2), иначе на масштабе часов риски толще промежутков.
    fun lineW(base: Double, minPx: Double = 0.7): Float {
        val screen = if (s >= 1) base * unit * min(s.pow(0.15), 2.0) else base * unit * s
        return (max(screen, minPx) / s).toFloat()
    }

    fun fade(v: Double, lo: Double, hi: Double): Float = ((v - lo) / (hi - lo)).coerceIn(0.0, 1.0).toFloat()

    val textRotOffset = if (settings.nowAtBottom) -PI / 2 else PI / 2
    val textPaint = Paint(
        Paint.ANTI_ALIAS_FLAG or Paint.LINEAR_TEXT_FLAG or Paint.SUBPIXEL_TEXT_FLAG,
    ).apply { textAlign = Paint.Align.CENTER }
    val mediumFace = Typeface.create("sans-serif-medium", Typeface.NORMAL)

    /** Подпись вдоль окружности с ЭКРАННЫМ кеглем [sizePx]. */
    val digitH = DIGIT_H

    fun label(
        text: String, a: Double, r: Double, sizePx: Float, color: Int,
        medium: Boolean = false, digits: Boolean = false,
    ) {
        if (sizePx < 2.5f) return
        val reach = sizePx * 3 / s
        if (r < lod.rMin - reach || r > lod.rMax + reach) return
        textPaint.textSize = sizePx
        textPaint.color = color
        textPaint.typeface = if (medium) mediumFace else Typeface.DEFAULT
        canvas.save()
        canvas.translate(px(a, r), py(a, r))
        canvas.rotate(deg(a + textRotOffset))
        canvas.scale(1f / sF, 1f / sF)
        // Цифры центрируются по их реальной высоте (ряды встают точно).
        val y = if (digits) (digitH * sizePx / 2).toFloat()
        else -(textPaint.ascent() + textPaint.descent()) / 2f
        canvas.drawText(text, 0f, y, textPaint)
        canvas.restore()
    }

    val nowFrac = tm.nowFrac
    val totalSec = tm.totalSec
    val pxPerSec = 2.0 * PI * outerR * s / totalSec
    val pxPerDay = pxPerSec * 86400.0

    // ═════ Слой круга: заливка + радиальные риски, с маской внутреннего кольца ═
    val needMask = lod.rMin < g.fadeEndR
    if (needMask) {
        val b = (outerR + 4 * unit).toFloat()
        canvas.saveLayer(lx.toFloat() - b, ly.toFloat() - b, lx.toFloat() + b, ly.toFloat() + b, null)
    }

    // ── Заливка секторов по месяцам ──────────────────────────────────────────
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

    for (m in 1..12) {
        val f0 = monthFrac(m)
        val f1 = monthFrac(m + 1)
        val odd = m % 2 == 1
        val cPast = if (odd) th.bandPastOdd else th.bandPastEven
        val cFuture = if (odd) th.bandFutureOdd else th.bandFutureEven
        when {
            f1 <= nowFrac -> drawSlice(f0, f1, cPast)
            f0 >= nowFrac -> drawSlice(f0, f1, cFuture)
            else -> {
                drawSlice(f0, nowFrac, cPast)
                drawSlice(nowFrac, f1, cFuture)
            }
        }
    }

    // ── LOD-лестница рисок: недели → дни → часы → минуты → секунды ─────────
    // Порог появления — экранное расстояние между соседними рисками вида.
    val weekVis = fade(pxPerDay * 7, 30.0, 60.0)
    val dayVis = fade(pxPerDay, 25.0, 50.0)
    val hourVis = fade(pxPerSec * 3600, 12.0, 25.0)
    val minVis = fade(pxPerSec * 60, 10.0, 22.0)
    val secVis = fade(pxPerSec, 10.0, 22.0)

    fun tickPaint(color: Int, alpha: Float, w: Float) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = withAlpha(color, alpha)
        strokeWidth = w
    }

    /** Риски с шагом [step] сек; кратные [skip] пропускаются (их рисует крупный вид). */
    fun timeTicks(step: Long, skip: Long, p: Paint) {
        val k0 = ceil(fLo * totalSec / step).toLong()
        val k1 = floor(fHi * totalSec / step).toLong()
        if (k1 - k0 > 6000) return
        for (k in k0..k1) {
            val sec = k * step
            if (skip > 0 && Math.floorMod(sec, skip) == 0L) continue
            radial(tm.angleOf(sec / totalSec), 0.0, outerR, p)
        }
    }

    if (weekVis > 0f) {
        val p = tickPaint(th.weekLineColor, th.weekLineAlpha * weekVis, lineW(th.weekLineWidth.toDouble()))
        val k0 = ceil(fLo * totalSec / 86400).toLong()
        val k1 = floor(fHi * totalSec / 86400).toLong()
        val dow0 = tm.yearStart.dayOfWeek.value - 1 // 0 = понедельник
        for (k in k0..k1) {
            if (Math.floorMod(dow0 + k, 7L) != 0L) continue
            radial(tm.angleOf(k * 86400.0 / totalSec), 0.0, outerR, p)
        }
    }
    if (dayVis > 0f) timeTicks(86400, 0, tickPaint(th.dayTickColor, th.dayTickAlpha * dayVis, lineW(0.3)))
    if (hourVis > 0f) timeTicks(3600, 86400, tickPaint(th.dayTickColor, th.dayTickAlpha * 0.8f * hourVis, lineW(0.175)))
    if (minVis > 0f) timeTicks(60, 3600, tickPaint(th.dayTickColor, th.dayTickAlpha * 0.7f * minVis, lineW(0.12)))
    if (secVis > 0f) timeTicks(1, 60, tickPaint(th.dayTickColor, th.dayTickAlpha * 0.6f * secVis, lineW(0.08)))

    // ── Границы месяцев, астро-линии, сезоны ────────────────────────────────
    val monthLinePaint = tickPaint(th.monthLineColor, th.monthLineAlpha, lineW(th.monthLineWidth.toDouble(), 1.0))
    for (m in 1..12) radial(tm.angleOf(monthFrac(m)), 0.0, outerR, monthLinePaint)

    val astroPaint = tickPaint(th.astroLineColor, th.astroLineAlpha, lineW(th.astroLineWidth.toDouble()))
    for ((m, day) in listOf(3 to 20, 6 to 21, 9 to 22, 12 to 21)) {
        radial(tm.angleOf(tm.fracOf(java.time.LocalDateTime.of(year, m, day, 0, 0))), 0.0, outerR, astroPaint)
    }

    val seasonPaint = tickPaint(th.seasonLineColor, th.seasonLineAlpha, lineW(th.seasonLineWidth.toDouble(), 1.2))
    for (m in listOf(3, 6, 9, 12)) radial(tm.angleOf(monthFrac(m)), 0.0, outerR, seasonPaint)

    // ── Маска: внутри внутреннего кольца пусто, дальше — мягкий градиент ────
    if (needMask) {
        val inner = (g.innerR / g.fadeEndR).toFloat()
        val span = 1f - inner
        val maskPaint = Paint().apply {
            // smoothstep: 0 → 1 между innerR и fadeEndR.
            shader = RadialGradient(
                lx.toFloat(), ly.toFloat(), g.fadeEndR.toFloat(),
                intArrayOf(0, 0, 0x29000000, 0x80000000.toInt(), 0xD6000000.toInt(), 0xFF000000.toInt()),
                floatArrayOf(0f, inner, inner + span * 0.25f, inner + span * 0.5f, inner + span * 0.75f, 1f),
                Shader.TileMode.CLAMP,
            )
            xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN)
        }
        val b = (outerR + 4 * unit).toFloat()
        canvas.drawRect(lx.toFloat() - b, ly.toFloat() - b, lx.toFloat() + b, ly.toFloat() + b, maskPaint)
        canvas.restore()
    }

    // ── Контур — внешний круг (снаружи чёткий край) ─────────────────────────
    run {
        val c = clip(0.0, 2 * PI)
        if (c != null && outerR >= lod.rMin - 4 / s && outerR <= lod.rMax + 4 / s) {
            val path = Path()
            arcPoly(path, lx, ly, outerR, c[0], c[1], s, true)
            canvas.drawPath(path, Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                strokeWidth = lineW(th.ringWidth.toDouble())
                color = withAlpha(th.ringColor, th.ringAlpha)
            })
        }
    }

    // ── Подписи времени: фрактальная лестница день → час → минута → секунда ─
    // Размер привязан к кругу (масштабируется вместе с ним, без скачков).
    // День — крупное число на весь день; часы/минуты/секунды — каждое мельче
    // (пропорционально своему интервалу) и ближе к краю, «над» крупным.
    run {
        val steps = TIME_STEPS
        val fonts = timeLabelFonts(outerR, totalSec)
        val rows = timeLabelRows(outerR, totalSec)
        for (i in 0..3) {
            val f = fonts[i]
            val vis = if (i == 0) dayVis else fade(f * s, 6.0, 10.0)
            if (vis <= 0f) continue
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
                val c = if (fmid <= nowFrac) th.dayNumPast else th.dayNumFuture
                label(text, tm.angleOf(fmid), rc, (f * s).toFloat(), mulAlpha(c, vis), digits = true)
            }
        }
    }

    // ── Подписи месяцев ──────────────────────────────────────────────────────
    val midR = (outerR + g.innerR) / 2
    for (m in 1..12) {
        val f0 = monthFrac(m)
        val f1 = monthFrac(m + 1)
        if (!visible(f0, f1)) continue
        label(
            MONTHS_RU[m - 1], tm.angleOf((f0 + f1) / 2), midR,
            (th.monthLabelFontMul * unit * s).toFloat(),
            if (f1 <= nowFrac) th.monthLabelPast else th.monthLabelFuture,
            medium = true,
        )
    }

    // ── Орбиты планет (по настройке; бледные) ───────────────────────────────
    val au = g.tipR
    if (settings.showOrbits) {
        val orbitPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = (0.8 / s).toFloat()
            color = withAlpha(WHITE, 0.06f)
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
    val bandW = th.eventBandWidth * unit
    val bandGap = 1.5 * unit
    val laneGap = 0.6 * unit
    val eventBandBaseR = outerR - 6.5 * unit

    if (bands.isNotEmpty()) {
        val minBandR = g.innerR + bandW / 2 + bandGap
        val availableSpace = eventBandBaseR - bandW / 2 - minBandR
        val maxVisibleLayers =
            if (availableSpace > 0) (floor(availableSpace / (bandW + bandGap)) + 1).toInt() else 1

        val bandPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
        val bandPath = Path()
        val pxPerFrac = 2.0 * PI * eventBandBaseR * s

        val overflowBuckets = HashMap<Int, Int>()
        val overflowFrac = HashMap<Int, Double>()

        for (b in bands) {
            if (!visible(b.startFrac, b.endFrac)) continue

            if (b.layer >= maxVisibleLayers) {
                val key = (b.startFrac * 50).toInt()
                overflowBuckets[key] = (overflowBuckets[key] ?: 0) + 1
                overflowFrac.putIfAbsent(key, b.startFrac)
                continue
            }

            val sweepFrac = b.endFrac - b.startFrac
            if (sweepFrac * pxPerFrac < 0.5) continue

            val rRow = (eventBandBaseR - b.layer * (bandW + bandGap) - bandW / 2)
                .coerceIn(minBandR, eventBandBaseR)
            // Дорожка внутри ряда (короткие пересекающиеся делят ряд по радиусу).
            val laneW = (bandW - (b.lanes - 1) * laneGap) / b.lanes
            val r = rRow + bandW / 2 - laneW / 2 - b.lane * (laneW + laneGap)
            if (r + laneW / 2 < lod.rMin || r - laneW / 2 > lod.rMax) continue

            val a0 = tm.angleOf(b.startFrac)
            val a1 = tm.angleOf(b.endFrac)

            val isPast = b.endFrac <= nowFrac
            val alpha = if (isPast) th.eventPastAlpha else th.eventFutureAlpha
            bandPaint.color = withAlpha(b.event.color, alpha)
            if (!buildBandPath(bandPath, lx, ly, r, laneW / 2, a0, a1, s, ::clip)) continue
            canvas.drawPath(bandPath, bandPaint)

            drawBandTitle(
                canvas, textPaint, b.event.title, lx, ly, r, laneW, a0, a1,
                b.event.color, alpha, settings.nowAtBottom,
                isDayPlus = sweepFrac >= LARGE_BAND_FRAC,
                ccw = settings.counterClockwise,
                camScale = s,
            )
        }

        if (overflowBuckets.isNotEmpty()) {
            val overflowR = (minBandR + bandGap * 0.5).coerceIn(g.innerR + bandW * 0.5, eventBandBaseR)
            val overflowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                strokeWidth = (bandW * 0.6).toFloat()
                strokeCap = Paint.Cap.ROUND
                color = withAlpha(th.accent, 0.55f)
            }
            for ((key, count) in overflowBuckets) {
                val f = overflowFrac[key]!!
                val a0 = tm.angleOf(f)
                val a1 = tm.angleOf(f + 0.015)
                val path = Path()
                arcPoly(path, lx, ly, overflowR, a0, a1, s, true)
                canvas.drawPath(path, overflowPaint)
                label("+$count", (a0 + a1) / 2, overflowR, (bandW * 0.9 * s).toFloat().coerceIn(6f, 40f),
                    withAlpha(th.accent, 0.85f), medium = true)
            }
        }
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
    val accent = settings.theme.accent
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
            canvas.drawLine(
                (lx + cos(nowAngle) * ra).toFloat(), (ly + sin(nowAngle) * ra).toFloat(),
                (lx + cos(nowAngle) * rb).toFloat(), (ly + sin(nowAngle) * rb).toFloat(),
                Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = accent
                    strokeWidth = lineW(3.0, 1.5)
                    strokeCap = Paint.Cap.ROUND
                },
            )
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

// ── Лесенка подписей времени (общая для рендера и отладки) ─────────────────
val TIME_STEPS = longArrayOf(86400, 3600, 60, 1)

/** Высота цифр шрифта (доля кегля) — по реальным границам глифов. */
val DIGIT_H: Double by lazy {
    val p = Paint(Paint.ANTI_ALIAS_FLAG)
    p.textSize = 100f
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
fun timeLabelRows(outerR: Double, totalSec: Double): DoubleArray {
    val f = timeLabelFonts(outerR, totalSec)
    val h = DIGIT_H
    val rows = DoubleArray(4)
    var edge = 0.35 * h * f[0]
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
): Boolean {
    val c = clip(a0, a1) ?: return false
    val ro = r + halfW
    val ri = r - halfW
    val sweep = a1 - a0
    val sgn = if (sweep >= 0) 1.0 else -1.0
    val cr = min(halfW * 0.7, abs(sweep) * r / 2)
    val ca = (cr / r) * sgn
    val startCut = c[0] != a0
    val endCut = c[1] != a1

    fun x(a: Double, rad: Double): Float = (cx + cos(a) * rad).toFloat()
    fun y(a: Double, rad: Double): Float = (cy + sin(a) * rad).toFloat()

    path.reset()
    arcPoly(path, cx, cy, ro, if (startCut) c[0] else a0 + ca, if (endCut) c[1] else a1 - ca, scale, true)
    if (!endCut) {
        path.quadTo(x(a1, ro), y(a1, ro), x(a1, ro - cr), y(a1, ro - cr))
        path.lineTo(x(a1, ri + cr), y(a1, ri + cr))
        path.quadTo(x(a1, ri), y(a1, ri), x(a1 - ca, ri), y(a1 - ca, ri))
    } else {
        path.lineTo(x(c[1], ri), y(c[1], ri))
    }
    arcPoly(path, cx, cy, ri, if (endCut) c[1] else a1 - ca, if (startCut) c[0] else a0 + ca, scale, false)
    if (!startCut) {
        path.quadTo(x(a0, ri), y(a0, ri), x(a0, ri + cr), y(a0, ri + cr))
        path.lineTo(x(a0, ro - cr), y(a0, ro - cr))
        path.quadTo(x(a0, ro), y(a0, ro), x(a0 + ca, ro), y(a0 + ca, ro))
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
    title: String,
    cx: Double,
    cy: Double,
    r: Double,
    bandW: Double,
    a0: Double,
    a1: Double,
    bandColor: Int,
    bandAlpha: Float,
    nowAtBottom: Boolean,
    isDayPlus: Boolean,
    ccw: Boolean,
    camScale: Double,
) {
    if (title.isBlank()) return
    val text = if (title.length > 200) title.take(200).trimEnd() + "…" else title

    val lum = 0.299f * ((bandColor shr 16) and 0xFF) +
        0.587f * ((bandColor shr 8) and 0xFF) +
        0.114f * (bandColor and 0xFF)
    paint.color = if (lum * bandAlpha > 165f) 0xD9000000.toInt() else 0xE8FFFFFF.toInt()
    paint.typeface = Typeface.DEFAULT

    if (isDayPlus) {
        drawArcTitle(canvas, paint, text, cx, cy, r, bandW, a0, a1, nowAtBottom, camScale)
    } else {
        drawRadialTitle(canvas, paint, text, cx, cy, r, bandW, a0, a1, ccw, camScale)
    }
    paint.textAlign = Paint.Align.CENTER
}

private const val TEXT_MIN_SCREEN_FS = 5.0
private const val TEXT_MAX_SCREEN_FS = 1500.0

private fun drawArcTitle(
    canvas: Canvas,
    paint: Paint,
    text: String,
    cx: Double,
    cy: Double,
    r: Double,
    bandW: Double,
    a0: Double,
    a1: Double,
    nowAtBottom: Boolean,
    camScale: Double,
) {
    val sweepAbs = abs(a1 - a0)
    val arcLen = (sweepAbs * r).toFloat()
    val avail = arcLen * 0.90f
    if (avail <= 0f) return

    paint.textAlign = Paint.Align.LEFT

    var fs = (bandW * 0.52).toFloat()
    var lines: List<String>? = null
    while (fs >= bandW * 0.02) {
        paint.textSize = fs
        val maxLines = min(8, (bandW * 0.92 / (fs * 1.15)).toInt().coerceAtLeast(1))
        val wrapped = wrapText(text, paint, avail, maxLines)
        if (wrapped != null) {
            lines = wrapped; break
        }
        fs *= 0.84f
    }
    val ls = lines ?: return
    if (fs * camScale < TEXT_MIN_SCREEN_FS || fs * camScale > TEXT_MAX_SCREEN_FS) return

    // Путь по дуге: при «сейчас внизу» — по убыванию угла (низ текста наружу).
    val lo = min(a0, a1); val hi = max(a0, a1)
    val path = Path()
    if (nowAtBottom) arcPoly(path, cx, cy, r, hi, lo, camScale, true)
    else arcPoly(path, cx, cy, r, lo, hi, camScale, true)

    paint.textSize = fs
    val base = -(paint.ascent() + paint.descent()) / 2f
    val n = ls.size
    ls.forEachIndexed { i, line ->
        val lw = paint.measureText(line)
        val v = (i - (n - 1) / 2f) * fs * 1.15f + base
        canvas.drawTextOnPath(line, path, (arcLen - lw) / 2f, v, paint)
    }
}

private fun drawRadialTitle(
    canvas: Canvas,
    paint: Paint,
    text: String,
    cx: Double,
    cy: Double,
    r: Double,
    bandW: Double,
    a0: Double,
    a1: Double,
    ccw: Boolean,
    camScale: Double,
) {
    val amid = (a0 + a1) / 2
    val angW = (abs(a1 - a0) * r).toFloat()
    val maxLen = (bandW * 0.9).toFloat()

    paint.textAlign = Paint.Align.CENTER

    var fs = min((bandW * 0.30).toFloat(), angW / 1.25f)
    var lines: List<String>? = null
    while (fs >= bandW * 0.01) {
        paint.textSize = fs
        val maxLines = min(8, (angW / (fs * 1.25f)).toInt().coerceAtLeast(1))
        val wrapped = wrapText(text, paint, maxLen, maxLines)
        if (wrapped != null) {
            lines = wrapped; break
        }
        fs *= 0.84f
    }
    val ls = lines ?: return
    if (fs * camScale < TEXT_MIN_SCREEN_FS || fs * camScale > TEXT_MAX_SCREEN_FS) return

    paint.textSize = fs

    val dirSign = if (ccw) 1.0 else -1.0
    val dx = -sin(amid) * dirSign
    val dy = cos(amid) * dirSign
    val ux = dy
    val uy = -dx
    val rotDeg = (atan2(uy, ux) * 180.0 / PI).toFloat()

    canvas.save()
    canvas.translate((cx + cos(amid) * r).toFloat(), (cy + sin(amid) * r).toFloat())
    canvas.rotate(rotDeg)
    val base = -(paint.ascent() + paint.descent()) / 2f
    val n = ls.size
    ls.forEachIndexed { i, line ->
        val y = (i - (n - 1) / 2f) * fs * 1.25f + base
        canvas.drawText(line, 0f, y, paint)
    }
    canvas.restore()
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
