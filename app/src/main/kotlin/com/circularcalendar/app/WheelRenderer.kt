package com.circularcalendar.app

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import java.time.Duration
import java.time.LocalDateTime
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.min

// ─────────────────────────────────────────────────────────────────────────────
// drawWheel — вся геометрия + события. Прямой порт drawWheel из main.dart.
// Вызывается ОДИН РАЗ при смене данных/настроек — рисуется в Picture.
// На кадр — только canvas.concat + drawPicture (в WheelView).
// ─────────────────────────────────────────────────────────────────────────────

private val MONTHS_RU = arrayOf(
    "Янв", "Фев", "Мар", "Апр", "Май", "Июн",
    "Июл", "Авг", "Сен", "Окт", "Ноя", "Дек",
)

private const val PI = Math.PI

private fun deg(rad: Double): Float = (rad * 180.0 / PI).toFloat()

fun drawWheel(
    canvas: Canvas,
    width: Float,
    height: Float,
    now: LocalDateTime,
    settings: WheelSettings,
    events: List<CalendarEvent>,
) {
    val th = settings.theme
    val cx = width / 2f
    val cy = height / 2f
    val maxR = min(width, height) / 2f
    val outerR = maxR * 0.92f
    // innerR — логическая граница «центральной зоны» (для ленточек).
    // Визуально НЕ рисуется (§3a, §13 SPEC).
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

    // Угол в радианах: 0 = +x, y вниз (та же система, что во Flutter).
    fun angleOf(f: Double): Double = anchor - dir * 2.0 * PI * (f - nowFrac)

    fun pointX(f: Double, r: Float): Float = cx + (cos(angleOf(f)) * r).toFloat()
    fun pointY(f: Double, r: Float): Float = cy + (sin(angleOf(f)) * r).toFloat()

    // При nowAtBottom текст у «сейчас» (внизу) читается прямо; при nowAtTop —
    // прямо читается верх. Логика идентична Flutter.
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
        // Вертикальное центрирование строки.
        canvas.drawText(s, 0f, -(textPaint.ascent() + textPaint.descent()) / 2f, textPaint)
        canvas.restore()
    }

    // ── Заливка секторов по месяцам (pie slices до центра, без innerR) ───────
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
                // Текущий месяц: делим по nowFrac (§2 SPEC).
                drawSlice(f0, nowFrac, cPast)
                drawSlice(nowFrac, f1, cFuture)
            }
        }
    }

    // ── Риски дней ───────────────────────────────────────────────────────────
    val tickLen = 5.0f * unit
    val dayTickPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = withAlpha(th.dayTickColor, th.dayTickAlpha)
        strokeWidth = th.dayTickWidth * unit
    }
    for (d in 1 until totalDays) {
        val f = frac(yearStart.plusDays(d.toLong()))
        canvas.drawLine(
            pointX(f, outerR), pointY(f, outerR),
            pointX(f, outerR - tickLen), pointY(f, outerR - tickLen),
            dayTickPaint,
        )
    }

    // ── Числа дней (§13 SPEC: между рисками, чуть внутрь от конца рисок) ────
    val dayNumFs = 4.5f * unit
    val dayNumR = outerR - tickLen - 1.5f * unit - dayNumFs * 0.5f
    for (d in 0 until totalDays) {
        val date = yearStart.plusDays(d.toLong())
        val fmid = frac(date.plusHours(12))
        val past = fmid <= nowFrac
        radialText(
            date.dayOfMonth.toString(), fmid, dayNumR,
            fsize = dayNumFs,
            color = if (past) th.dayNumPast else th.dayNumFuture,
        )
    }

    // ── Границы месяцев (от центра до края, без внутренней окружности) ──────
    val monthLinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = withAlpha(th.monthLineColor, th.monthLineAlpha)
        strokeWidth = th.monthLineWidth * unit
    }
    for (m in 1..12) {
        val f = frac(LocalDateTime.of(year, m, 1, 0, 0))
        canvas.drawLine(cx, cy, pointX(f, outerR), pointY(f, outerR), monthLinePaint)
    }

    // ── Астрономические линии (солнцестояния/равноденствия) ─────────────────
    val astroPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = withAlpha(th.astroLineColor, th.astroLineAlpha)
        strokeWidth = th.astroLineWidth * unit
    }
    for ((m, day) in listOf(3 to 20, 6 to 21, 9 to 22, 12 to 21)) {
        val f = frac(LocalDateTime.of(year, m, day, 0, 0))
        canvas.drawLine(cx, cy, pointX(f, outerR), pointY(f, outerR), astroPaint)
    }

    // ── Сезонные границы (календарные: 1 мар/июн/сен/дек) ───────────────────
    val seasonPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = withAlpha(th.seasonLineColor, th.seasonLineAlpha)
        strokeWidth = th.seasonLineWidth * unit
        strokeCap = Paint.Cap.ROUND
    }
    for (m in listOf(3, 6, 9, 12)) {
        val f = frac(LocalDateTime.of(year, m, 1, 0, 0))
        canvas.drawLine(cx, cy, pointX(f, outerR), pointY(f, outerR), seasonPaint)
    }

    // ── Контур — один внешний круг (внутренней окружности НЕТ) ──────────────
    canvas.drawCircle(cx, cy, outerR, Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = th.ringWidth * unit
        color = withAlpha(th.ringColor, th.ringAlpha)
    })

    // ── Подписи месяцев ──────────────────────────────────────────────────────
    for (m in 1..12) {
        val f0 = frac(LocalDateTime.of(year, m, 1, 0, 0))
        val f1 = if (m < 12) frac(LocalDateTime.of(year, m + 1, 1, 0, 0)) else 1.0
        val past = f1 <= nowFrac
        radialText(
            MONTHS_RU[m - 1], (f0 + f1) / 2, midR,
            fsize = th.monthLabelFontMul * unit,
            color = if (past) th.monthLabelPast else th.monthLabelFuture,
            medium = true,
        )
    }

    // ── События — дуги-ленточки с послойной укладкой ────────────────────────
    val bandW = th.eventBandWidth * unit
    val bandGap = 1.5f * unit
    // Ленточки СТРОГО внутрь от полосы чисел дней (§13 SPEC).
    val eventBandBaseR = outerR - tickLen - 1.5f * unit - dayNumFs - 2.5f * unit

    if (events.isNotEmpty()) {
        val bands = buildEventBands(events, year)

        val minBandR = innerR + bandW / 2f + bandGap
        val availableSpace = eventBandBaseR - bandW / 2f - minBandR
        val maxVisibleLayers =
            if (availableSpace > 0) (floor(availableSpace / (bandW + bandGap)) + 1).toInt() else 1

        val bandPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            strokeWidth = bandW
        }

        // Overflow: бакеты по startFrac с шагом ~2% года.
        val overflowBuckets = HashMap<Int, Int>()
        val overflowFrac = HashMap<Int, Double>()

        for (b in bands) {
            if (b.layer < maxVisibleLayers) {
                val rRaw = eventBandBaseR - b.layer * (bandW + bandGap) - bandW / 2f
                val r = rRaw.coerceIn(minBandR, eventBandBaseR)
                val a0 = angleOf(b.startFrac)
                val a1 = angleOf(b.endFrac)
                var sweep = a1 - a0
                val minSweep = 0.015 // ~0.86°, видимость очень коротких событий
                if (abs(sweep) < minSweep) sweep = if (sweep < 0) -minSweep else minSweep

                val isPast = b.endFrac <= nowFrac
                val alpha = if (isPast) th.eventPastAlpha else th.eventFutureAlpha
                bandPaint.color = withAlpha(b.event.color, alpha)
                val rect = RectF(cx - r, cy - r, cx + r, cy + r)
                canvas.drawArc(rect, deg(a0), deg(sweep), false, bandPaint)
            } else {
                val key = (b.startFrac * 50).toInt()
                overflowBuckets[key] = (overflowBuckets[key] ?: 0) + 1
                overflowFrac.putIfAbsent(key, b.startFrac)
            }
        }

        // Overflow-маркеры: маленькая дуга + «+N».
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

    // Ромб на конце стрелки.
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
