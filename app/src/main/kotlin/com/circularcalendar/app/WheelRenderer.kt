package com.circularcalendar.app

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import java.time.DayOfWeek
import java.time.Duration
import java.time.LocalDateTime
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

// ─────────────────────────────────────────────────────────────────────────────
// drawWheel — вся геометрия + события. Порт drawWheel из Flutter-версии
// + LOD: угловое окно видимости и минимальный пиксельный размер события.
// Сцена пишется в RenderNode ОДИН РАЗ; на кадр — только concat + drawRenderNode.
// ─────────────────────────────────────────────────────────────────────────────

private val MONTHS_RU = arrayOf(
    "Янв", "Фев", "Мар", "Апр", "Май", "Июн",
    "Июл", "Авг", "Сен", "Окт", "Ноя", "Дек",
)

private const val PI = Math.PI

private fun deg(rad: Double): Float = (rad * 180.0 / PI).toFloat()

/**
 * LOD-параметры записи сцены.
 * [camScale] — текущий зум камеры (для пиксельных порогов).
 * Окно видимости в frac-пространстве: [winStart, winStart+winLen] (mod 1);
 * [full] = виден весь круг.
 */
class Lod(
    val camScale: Float = 1f,
    val winStart: Double = 0.0,
    val winLen: Double = 1.0,
    val full: Boolean = true,
) {
    /** Пересекается ли [s,e] (frac, 0..2) с окном видимости (mod 1). */
    fun visible(s: Double, e: Double): Boolean {
        if (full) return true
        for (k in -2..2) {
            val lo = winStart + k
            if (max(s, lo) < min(e, lo + winLen)) return true
        }
        return false
    }

    fun visiblePoint(f: Double): Boolean = visible(f - 0.003, f + 0.003)
}

fun drawWheel(
    canvas: Canvas,
    width: Float,
    height: Float,
    now: LocalDateTime,
    settings: WheelSettings,
    bands: List<EventBand>,
    lod: Lod = Lod(),
) {
    val th = settings.theme
    val cx = width / 2f
    val cy = height / 2f
    val maxR = min(width, height) / 2f
    val outerR = maxR * 0.92f
    // innerR — логическая граница «центральной зоны» (визуально НЕ рисуется).
    val innerR = maxR * 0.22f
    val midR = (outerR + innerR) / 2f
    val unit = maxR / 360f

    val year = now.year
    val yearStart = LocalDateTime.of(year, 1, 1, 0, 0)
    val yearEnd = LocalDateTime.of(year + 1, 1, 1, 0, 0)
    val totalSec = Duration.between(yearStart, yearEnd).seconds.toDouble()
    val totalDays = Duration.between(yearStart, yearEnd).toDays().toInt()

    fun frac(d: LocalDateTime): Double =
        Duration.between(yearStart, d).seconds.toDouble() / totalSec

    val nowFrac = frac(now)

    val dir = if (settings.counterClockwise) 1.0 else -1.0
    val anchor = if (settings.nowAtBottom) PI / 2 else -PI / 2

    fun angleOf(f: Double): Double = anchor - dir * 2.0 * PI * (f - nowFrac)

    fun pointX(f: Double, r: Float): Float = cx + (cos(angleOf(f)) * r).toFloat()
    fun pointY(f: Double, r: Float): Float = cy + (sin(angleOf(f)) * r).toFloat()

    val textRotOffset = if (settings.nowAtBottom) -PI / 2 else PI / 2

    val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
    }

    fun radialText(s: String, f: Double, r: Float, fsize: Float, color: Int, medium: Boolean = false) {
        textPaint.textSize = fsize
        textPaint.color = color
        textPaint.typeface = if (medium)
            Typeface.create("sans-serif-medium", Typeface.NORMAL)
        else
            Typeface.DEFAULT
        canvas.save()
        canvas.translate(pointX(f, r), pointY(f, r))
        canvas.rotate(deg(angleOf(f) + textRotOffset))
        canvas.drawText(s, 0f, -(textPaint.ascent() + textPaint.descent()) / 2f, textPaint)
        canvas.restore()
    }

    // ── Заливка секторов по месяцам (pie slices до центра) ───────────────────
    val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    val outerRect = RectF(cx - outerR, cy - outerR, cx + outerR, cy + outerR)

    fun drawSlice(fStart: Double, fEnd: Double, color: Int) {
        if (abs(fEnd - fStart) < 1e-9) return
        val a0 = angleOf(fStart)
        val a1 = angleOf(fEnd)
        val path = Path()
        path.moveTo(cx, cy)
        path.arcTo(outerRect, deg(a0), deg(a1 - a0), false)
        path.close()
        fillPaint.color = color
        canvas.drawPath(path, fillPaint)
    }

    for (m in 1..12) {
        val f0 = frac(LocalDateTime.of(year, m, 1, 0, 0))
        val f1 = if (m < 12) frac(LocalDateTime.of(year, m + 1, 1, 0, 0)) else 1.0
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

    // ── Недельные линии (понедельники) — тусклее месячных ────────────────────
    val weekPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = withAlpha(th.weekLineColor, th.weekLineAlpha)
        strokeWidth = th.weekLineWidth * unit
    }
    for (d in 1 until totalDays) {
        val date = yearStart.plusDays(d.toLong())
        if (date.dayOfWeek != DayOfWeek.MONDAY) continue
        val f = frac(date)
        if (!lod.visiblePoint(f)) continue
        canvas.drawLine(cx, cy, pointX(f, outerR), pointY(f, outerR), weekPaint)
    }

    // ── Риски дней ───────────────────────────────────────────────────────────
    val tickLen = 5.0f * unit
    val dayTickPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = withAlpha(th.dayTickColor, th.dayTickAlpha)
        strokeWidth = th.dayTickWidth * unit
    }
    for (d in 1 until totalDays) {
        val f = frac(yearStart.plusDays(d.toLong()))
        if (!lod.visiblePoint(f)) continue
        canvas.drawLine(
            pointX(f, outerR), pointY(f, outerR),
            pointX(f, outerR - tickLen), pointY(f, outerR - tickLen),
            dayTickPaint,
        )
    }

    // ── Числа дней (между рисками, чуть внутрь от конца рисок, §13) ─────────
    val dayNumFs = 4.5f * unit
    val dayNumR = outerR - tickLen - 1.5f * unit - dayNumFs * 0.5f
    for (d in 0 until totalDays) {
        val date = yearStart.plusDays(d.toLong())
        val fmid = frac(date.plusHours(12))
        if (!lod.visiblePoint(fmid)) continue
        val past = fmid <= nowFrac
        radialText(
            date.dayOfMonth.toString(), fmid, dayNumR,
            fsize = dayNumFs,
            color = if (past) th.dayNumPast else th.dayNumFuture,
        )
    }

    // ── Границы месяцев ──────────────────────────────────────────────────────
    val monthLinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = withAlpha(th.monthLineColor, th.monthLineAlpha)
        strokeWidth = th.monthLineWidth * unit
    }
    for (m in 1..12) {
        val f = frac(LocalDateTime.of(year, m, 1, 0, 0))
        canvas.drawLine(cx, cy, pointX(f, outerR), pointY(f, outerR), monthLinePaint)
    }

    // ── Астрономические линии ────────────────────────────────────────────────
    val astroPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = withAlpha(th.astroLineColor, th.astroLineAlpha)
        strokeWidth = th.astroLineWidth * unit
    }
    for ((m, day) in listOf(3 to 20, 6 to 21, 9 to 22, 12 to 21)) {
        val f = frac(LocalDateTime.of(year, m, day, 0, 0))
        canvas.drawLine(cx, cy, pointX(f, outerR), pointY(f, outerR), astroPaint)
    }

    // ── Сезонные границы ─────────────────────────────────────────────────────
    val seasonPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = withAlpha(th.seasonLineColor, th.seasonLineAlpha)
        strokeWidth = th.seasonLineWidth * unit
        strokeCap = Paint.Cap.ROUND
    }
    for (m in listOf(3, 6, 9, 12)) {
        val f = frac(LocalDateTime.of(year, m, 1, 0, 0))
        canvas.drawLine(cx, cy, pointX(f, outerR), pointY(f, outerR), seasonPaint)
    }

    // ── Контур — один внешний круг ───────────────────────────────────────────
    canvas.drawCircle(cx, cy, outerR, Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = th.ringWidth * unit
        color = withAlpha(th.ringColor, th.ringAlpha)
    })

    // ── Подписи месяцев ──────────────────────────────────────────────────────
    for (m in 1..12) {
        val f0 = frac(LocalDateTime.of(year, m, 1, 0, 0))
        val f1 = if (m < 12) frac(LocalDateTime.of(year, m + 1, 1, 0, 0)) else 1.0
        if (!lod.visible(f0, f1)) continue
        val past = f1 <= nowFrac
        radialText(
            MONTHS_RU[m - 1], (f0 + f1) / 2, midR,
            fsize = th.monthLabelFontMul * unit,
            color = if (past) th.monthLabelPast else th.monthLabelFuture,
            medium = true,
        )
    }

    // ── События — дуги-ленточки ──────────────────────────────────────────────
    val bandW = th.eventBandWidth * unit
    val bandGap = 1.5f * unit
    val eventBandBaseR = outerR - tickLen - 1.5f * unit - dayNumFs - 2.5f * unit

    if (bands.isNotEmpty()) {
        val minBandR = innerR + bandW / 2f + bandGap
        val availableSpace = eventBandBaseR - bandW / 2f - minBandR
        val maxVisibleLayers =
            if (availableSpace > 0) (floor(availableSpace / (bandW + bandGap)) + 1).toInt() else 1

        val bandPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.BUTT // точная длина = длительность события (без «хвостов»)
            strokeWidth = bandW
        }

        // Пиксель на единицу frac по окружности ленточной зоны (для LOD-отсева).
        val pxPerFrac = 2.0 * PI * eventBandBaseR * lod.camScale

        val overflowBuckets = HashMap<Int, Int>()
        val overflowFrac = HashMap<Int, Double>()

        for (b in bands) {
            if (!lod.visible(b.startFrac, b.endFrac)) continue

            if (b.layer >= maxVisibleLayers) {
                val key = (b.startFrac * 50).toInt()
                overflowBuckets[key] = (overflowBuckets[key] ?: 0) + 1
                overflowFrac.putIfAbsent(key, b.startFrac)
                continue
            }

            // LOD: события мельче ~половины пикселя на текущем зуме не рисуем —
            // они появятся при приближении (сцена перезаписывается по зуму).
            val sweepFrac = b.endFrac - b.startFrac
            if (sweepFrac * pxPerFrac < 0.5) continue

            val rRaw = eventBandBaseR - b.layer * (bandW + bandGap) - bandW / 2f
            val r = rRaw.coerceIn(minBandR, eventBandBaseR)
            val a0 = angleOf(b.startFrac)
            val a1 = angleOf(b.endFrac)
            val sweep = a1 - a0

            val isPast = b.endFrac <= nowFrac
            val alpha = if (isPast) th.eventPastAlpha else th.eventFutureAlpha
            bandPaint.color = withAlpha(b.event.color, alpha)
            val rect = RectF(cx - r, cy - r, cx + r, cy + r)
            canvas.drawArc(rect, deg(a0), deg(sweep), false, bandPaint)

            drawBandTitle(canvas, textPaint, b.event.title, cx, cy, r, bandW, a0, a1, b.event.color, alpha, lod.camScale)
        }

        // Overflow-маркеры «+N».
        if (overflowBuckets.isNotEmpty()) {
            val overflowR = (minBandR + bandGap * 0.5f)
                .coerceIn(innerR + bandW * 0.5f, eventBandBaseR)
            val overflowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                strokeWidth = bandW * 0.6f
                strokeCap = Paint.Cap.ROUND
                color = withAlpha(th.accent, 0.55f)
            }
            val overflowTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                textAlign = Paint.Align.CENTER
                typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                color = withAlpha(th.accent, 0.85f)
                textSize = (bandW * 0.9f).coerceIn(6f, 14f)
            }
            val rect = RectF(cx - overflowR, cy - overflowR, cx + overflowR, cy + overflowR)
            for ((key, count) in overflowBuckets) {
                val f = overflowFrac[key]!!
                val a0 = angleOf(f)
                val sweepFrac = 0.015
                val sweep = angleOf(f + sweepFrac) - a0
                canvas.drawArc(rect, deg(a0), deg(sweep), false, overflowPaint)

                val midAngle = a0 + sweep / 2
                val tx = cx + (cos(midAngle) * overflowR).toFloat()
                val ty = cy + (sin(midAngle) * overflowR).toFloat()
                canvas.drawText(
                    "+$count", tx,
                    ty - (overflowTextPaint.ascent() + overflowTextPaint.descent()) / 2f,
                    overflowTextPaint,
                )
            }
        }
    }

    // ── Стрелка «сейчас» ─────────────────────────────────────────────────────
    val accent = th.accent
    val nowAngle = angleOf(nowFrac)
    val tipR = outerR + 14f * unit
    val tipX = cx + (cos(nowAngle) * tipR).toFloat()
    val tipY = cy + (sin(nowAngle) * tipR).toFloat()

    canvas.drawLine(cx, cy, tipX, tipY, Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = accent
        strokeWidth = 1.2f * unit
        strokeCap = Paint.Cap.ROUND
    })

    run {
        val size = 3.8f * unit
        val ca = cos(nowAngle).toFloat()
        val sa = sin(nowAngle).toFloat()
        val path = Path()
        path.moveTo(tipX + ca * size, tipY + sa * size)
        path.lineTo(tipX - sa * size * 0.55f, tipY + ca * size * 0.55f)
        path.lineTo(tipX - ca * size * 0.55f, tipY - sa * size * 0.55f)
        path.lineTo(tipX + sa * size * 0.55f, tipY - ca * size * 0.55f)
        path.close()
        canvas.drawPath(path, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = accent })
    }

    canvas.drawCircle(cx, cy, 2.0f * unit, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = accent })
    canvas.drawCircle(cx, cy, 5.0f * unit, Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = withAlpha(accent, 0.12f)
    })
}

// ─────────────────────────────────────────────────────────────────────────────
// Название события по дуге ленточки. Не вылезает за пределы события:
// перенос до 2 строк, дальше многоточие. Появляется, когда дуга события
// достаточно длинна на экране (сцена перезаписывается по зуму → названия
// «проявляются» при приближении).
// ─────────────────────────────────────────────────────────────────────────────

private fun drawBandTitle(
    canvas: Canvas,
    paint: Paint,
    title: String,
    cx: Float,
    cy: Float,
    r: Float,
    bandW: Float,
    a0: Double,
    a1: Double,
    bandColor: Int,
    bandAlpha: Float,
    camScale: Float,
) {
    if (title.isBlank()) return
    val sweepAbs = abs(a1 - a0)
    val arcLen = (sweepAbs * r).toFloat()          // content px
    val arcLenScreen = arcLen * camScale
    // Порог читаемости: меньше ~30px на экране текст не имеет смысла.
    if (arcLenScreen < 30f || bandW * camScale < 5f) return

    // Текст читается «головой к центру» (как тангенциальные подписи круга):
    // путь должен идти в сторону УМЕНЬШЕНИЯ экранного угла.
    val startAngle = max(a0, a1)
    val sweepNeg = -sweepAbs

    val rect = RectF(cx - r, cy - r, cx + r, cy + r)
    val path = Path()
    path.addArc(rect, deg(startAngle), deg(sweepNeg))

    val pad = bandW * 0.45f
    val avail = arcLen - 2f * pad
    if (avail <= 0f) return

    // Цвет текста по яркости ленточки (ленточка полупрозрачна на тёмном фоне —
    // почти всегда белый, но на ярких цветах нужен тёмный).
    val lum = 0.299f * ((bandColor shr 16) and 0xFF) +
        0.587f * ((bandColor shr 8) and 0xFF) +
        0.114f * (bandColor and 0xFF)
    val textColor = if (lum * bandAlpha > 165f) 0xD9000000.toInt() else 0xE8FFFFFF.toInt()

    paint.textAlign = Paint.Align.LEFT
    paint.typeface = Typeface.DEFAULT
    paint.color = textColor

    fun baselineOffset(): Float = -(paint.ascent() + paint.descent()) / 2f

    // 1) Пытаемся уложить в одну строку.
    val fs1 = bandW * 0.52f
    paint.textSize = fs1
    val w1 = paint.measureText(title)
    if (w1 <= avail) {
        canvas.drawTextOnPath(title, path, pad + (avail - w1) / 2f, baselineOffset(), paint)
        paint.textAlign = Paint.Align.CENTER
        return
    }

    // 2) Две строки с переносом по словам; хвост — многоточие.
    val fs2 = bandW * 0.40f
    paint.textSize = fs2
    val lines = wrapToTwoLines(title, paint, avail)
    if (lines.isEmpty()) {
        paint.textAlign = Paint.Align.CENTER
        return
    }
    val lineShift = bandW * 0.23f
    lines.forEachIndexed { i, line ->
        val lw = paint.measureText(line)
        val v = (if (lines.size == 1) 0f else if (i == 0) -lineShift else lineShift) + baselineOffset()
        canvas.drawTextOnPath(line, path, pad + (avail - lw) / 2f, v, paint)
    }
    paint.textAlign = Paint.Align.CENTER
}

/** Перенос по словам максимум в 2 строки; не влезло — многоточие. */
private fun wrapToTwoLines(text: String, paint: Paint, maxW: Float): List<String> {
    if (maxW < paint.textSize) return emptyList()

    fun truncate(s: String): String {
        if (paint.measureText(s) <= maxW) return s
        val ell = "…"
        var n = paint.breakText(s, true, maxW - paint.measureText(ell), null)
        if (n <= 0) return ""
        return s.substring(0, n).trimEnd() + ell
    }

    val words = text.split(' ').filter { it.isNotEmpty() }
    if (words.isEmpty()) return emptyList()

    val line1 = StringBuilder()
    var idx = 0
    while (idx < words.size) {
        val cand = if (line1.isEmpty()) words[idx] else "${line1} ${words[idx]}"
        if (paint.measureText(cand) <= maxW) {
            line1.setLength(0); line1.append(cand); idx++
        } else break
    }
    if (line1.isEmpty()) {
        // Первое слово длиннее строки — жёсткое усечение.
        return listOf(truncate(words[0])).filter { it.isNotEmpty() }
    }
    if (idx >= words.size) return listOf(line1.toString())

    val rest = words.subList(idx, words.size).joinToString(" ")
    val line2 = truncate(rest)
    return if (line2.isEmpty()) listOf(line1.toString()) else listOf(line1.toString(), line2)
}
