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
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
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

    // LINEAR_TEXT + SUBPIXEL: интервалы между глифами считаются без хинтинг-
    // квантования — текст остаётся ровным при любом зуме (без «кривых букв»).
    val textPaint = Paint(
        Paint.ANTI_ALIAS_FLAG or Paint.LINEAR_TEXT_FLAG or Paint.SUBPIXEL_TEXT_FLAG,
    ).apply {
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

    // ── LOD-лестница рисок: месяцы всегда, недели → дни → часы по зуму ──────
    // Все риски — полные линии от центра до края. Иерархия толщин и альф:
    // месяц > неделя > день > час. Порог появления — экранное расстояние
    // между соседними рисками ЭТОГО вида: каждый вид проявляется фейдом,
    // когда ему хватает места, и чем мельче вид — тем глубже нужен зум.
    val pxPerDay = (2.0 * PI * outerR * lod.camScale / totalDays).toFloat()
    fun fade(v: Float, lo: Float, hi: Float): Float = ((v - lo) / (hi - lo)).coerceIn(0f, 1f)
    val weekVis = fade(pxPerDay * 7f, 30f, 60f)   // недели: полные к дефолту
    val dayVis = fade(pxPerDay, 25f, 50f)         // дни: с ~3× зума
    val hourVis = fade(pxPerDay / 24f, 12f, 25f)  // часы: с ~35× зума

    // ── Недельные линии (понедельники) — тусклее и тоньше месячных ──────────
    if (weekVis > 0f) {
        val weekPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = withAlpha(th.weekLineColor, th.weekLineAlpha * weekVis)
            strokeWidth = th.weekLineWidth * unit
        }
        for (d in 1 until totalDays) {
            val date = yearStart.plusDays(d.toLong())
            if (date.dayOfWeek != DayOfWeek.MONDAY) continue
            val f = frac(date)
            if (!lod.visiblePoint(f)) continue
            canvas.drawLine(cx, cy, pointX(f, outerR), pointY(f, outerR), weekPaint)
        }
    }

    // ── Риски дней — до центра, тоньше недельных ─────────────────────────────
    val tickLen = 5.0f * unit // константа раскладки: от неё считается радиус чисел
    if (dayVis > 0f) {
        val dayLinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = withAlpha(th.dayTickColor, th.dayTickAlpha * dayVis)
            strokeWidth = th.weekLineWidth * unit * 0.6f
        }
        for (d in 1 until totalDays) {
            val f = frac(yearStart.plusDays(d.toLong()))
            if (!lod.visiblePoint(f)) continue
            canvas.drawLine(cx, cy, pointX(f, outerR), pointY(f, outerR), dayLinePaint)
        }
    }

    // ── Часовые риски — до центра, тоньше дневных, при близком зуме ─────────
    if (hourVis > 0f) {
        val hourPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = withAlpha(th.dayTickColor, th.dayTickAlpha * 0.75f * hourVis)
            strokeWidth = th.weekLineWidth * unit * 0.35f
        }
        for (d in 0 until totalDays) {
            val f0 = frac(yearStart.plusDays(d.toLong()))
            val f1 = frac(yearStart.plusDays(d + 1L))
            if (!lod.visible(f0, f1)) continue
            for (h in 1..23) {
                val f = f0 + (f1 - f0) * h / 24.0
                if (!lod.visiblePoint(f)) continue
                canvas.drawLine(cx, cy, pointX(f, outerR), pointY(f, outerR), hourPaint)
            }
        }
    }

    // ── Числа дней (между рисками, чуть внутрь от конца рисок, §13) ─────────
    // Проявляются вместе с дневными рисками (та же LOD-ступень).
    val dayNumFs = 4.5f * unit
    val dayNumR = outerR - tickLen - 1.5f * unit - dayNumFs * 0.5f
    if (dayVis > 0f) {
        for (d in 0 until totalDays) {
            val date = yearStart.plusDays(d.toLong())
            val fmid = frac(date.plusHours(12))
            if (!lod.visiblePoint(fmid)) continue
            val past = fmid <= nowFrac
            radialText(
                date.dayOfMonth.toString(), fmid, dayNumR,
                fsize = dayNumFs,
                color = mulAlpha(if (past) th.dayNumPast else th.dayNumFuture, dayVis),
            )
        }
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

        // Ленточка = «искривлённый squircle»: кольцевой сектор со скруглёнными
        // углами. Длина строго равна длительности события.
        val bandPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
        val bandPath = Path()

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

            val isPast = b.endFrac <= nowFrac
            val alpha = if (isPast) th.eventPastAlpha else th.eventFutureAlpha
            bandPaint.color = withAlpha(b.event.color, alpha)
            buildBandPath(bandPath, cx, cy, r, bandW / 2f, a0, a1)
            canvas.drawPath(bandPath, bandPaint)

            drawBandTitle(
                canvas, textPaint, b.event.title, cx, cy, r, bandW, a0, a1,
                b.event.color, alpha, settings.nowAtBottom,
                isDayPlus = sweepFrac >= LARGE_BAND_FRAC,
                ccw = settings.counterClockwise,
                camScale = lod.camScale,
            )
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
            val overflowTextPaint = Paint(
                Paint.ANTI_ALIAS_FLAG or Paint.LINEAR_TEXT_FLAG or Paint.SUBPIXEL_TEXT_FLAG,
            ).apply {
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
    // Скейлится по зуму: при приближении утоньшается (экранная толщина
    // постоянна), чтобы не заслонять маленькие события (§2 SPEC).
    val accent = th.accent
    val nowAngle = angleOf(nowFrac)
    // Мягкое утоньшение: экранная толщина растёт как zoom^0.3 (с потолком ×4),
    // т.е. относительно контента стрелка тоньше, но остаётся хорошо видимой.
    val zoomDiv = max(1f, lod.camScale)
    val zk = min(zoomDiv.pow(0.3f), 4f) / zoomDiv
    val tipR = outerR + 14f * unit
    val tipX = cx + (cos(nowAngle) * tipR).toFloat()
    val tipY = cy + (sin(nowAngle) * tipR).toFloat()

    canvas.drawLine(cx, cy, tipX, tipY, Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = accent
        strokeWidth = 3.0f * unit * zk
        strokeCap = Paint.Cap.ROUND
    })

    run {
        val size = 3.8f * unit * zk
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

    canvas.drawCircle(cx, cy, 2.0f * unit * zk, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = accent })
    canvas.drawCircle(cx, cy, 5.0f * unit * zk, Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = withAlpha(accent, 0.12f)
    })
}

// ─────────────────────────────────────────────────────────────────────────────
// «Искривлённый squircle» — кольцевой сектор со скруглёнными углами.
// Квадратичные кривые на углах дают мягкую, сквиркловую форму.
// ─────────────────────────────────────────────────────────────────────────────

private fun buildBandPath(
    path: Path,
    cx: Float,
    cy: Float,
    r: Float,
    halfW: Float,
    a0: Double,
    a1: Double,
) {
    val ro = r + halfW
    val ri = r - halfW
    val sweep = a1 - a0
    val sgn = if (sweep >= 0) 1.0 else -1.0
    // Радиус скругления углов: до 70% полутолщины, но не больше половины дуги.
    val cr = min(halfW * 0.7f, (abs(sweep) * r / 2f).toFloat())
    val ca = (cr / r) * sgn // угловой отступ скругления (со знаком направления)

    fun px(a: Double, rad: Float): Float = cx + (cos(a) * rad).toFloat()
    fun py(a: Double, rad: Float): Float = cy + (sin(a) * rad).toFloat()

    val ovalO = RectF(cx - ro, cy - ro, cx + ro, cy + ro)
    val ovalI = RectF(cx - ri, cy - ri, cx + ri, cy + ri)

    path.reset()
    // Внешняя дуга (с отступами под углы).
    path.arcTo(ovalO, deg(a0 + ca), deg((a1 - ca) - (a0 + ca)), true)
    // Угол у конца события: наружу → радиальная кромка.
    path.quadTo(px(a1, ro), py(a1, ro), px(a1, ro - cr), py(a1, ro - cr))
    path.lineTo(px(a1, ri + cr), py(a1, ri + cr))
    path.quadTo(px(a1, ri), py(a1, ri), px(a1 - ca, ri), py(a1 - ca, ri))
    // Внутренняя дуга назад.
    path.arcTo(ovalI, deg(a1 - ca), deg((a0 + ca) - (a1 - ca)), false)
    // Угол у начала события.
    path.quadTo(px(a0, ri), py(a0, ri), px(a0, ri + cr), py(a0, ri + cr))
    path.lineTo(px(a0, ro - cr), py(a0, ro - cr))
    path.quadTo(px(a0, ro), py(a0, ro), px(a0 + ca, ro), py(a0 + ca, ro))
    path.close()
}

// ─────────────────────────────────────────────────────────────────────────────
// Название события.
//  * ≥ 1 дня — «нормальная ориентация»: текст по дуге вдоль ленточки
//    (drawTextOnPath); направление пути выбирается так, чтобы низ текста
//    смотрел на стрелку «сейчас» — при «сейчас вверху» переворачивается.
//  * < 1 дня — поворот на 90°: текст вдоль радиуса, узкий и длинный,
//    низом (перпендикуляром) в сторону стрелки «сейчас».
// Кегль: дефолтный для больших событий, уменьшается только чтобы влезть.
// Без многоточий (кроме названий длиннее 200 символов и безвыходных случаев).
// Мелкий текст проявляется зумом (сцена перезаписывается по LOD).
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
    nowAtBottom: Boolean,
    isDayPlus: Boolean,
    ccw: Boolean,
    camScale: Float,
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

// Порог появления текста: пишем, только когда кегль на экране уже читаем.
// Мелким событиям кегль ужимается сильнее → они проявляются позже (по зуму).
private const val TEXT_MIN_SCREEN_FS = 5f

/** Текст по дуге для событий от одного дня. Рисуется ВСЕГДА (кегль ужимается). */
private fun drawArcTitle(
    canvas: Canvas,
    paint: Paint,
    text: String,
    cx: Float,
    cy: Float,
    r: Float,
    bandW: Float,
    a0: Double,
    a1: Double,
    nowAtBottom: Boolean,
    camScale: Float,
) {
    val sweepAbs = abs(a1 - a0)
    val arcLen = (sweepAbs * r).toFloat()
    // Отступ пропорционален дуге (НЕ толщине ленты) — иначе на ~однодневных
    // событиях доступная ширина уходила в минус и текст пропадал.
    val avail = arcLen * 0.90f
    if (avail <= 0f) return

    // Направление пути: при «сейчас внизу» — по убыванию экранного угла
    // (низ текста наружу, к стрелке); при «сейчас вверху» — наоборот.
    val rect = RectF(cx - r, cy - r, cx + r, cy + r)
    val path = Path()
    if (nowAtBottom) {
        path.addArc(rect, deg(max(a0, a1)), deg(-sweepAbs))
    } else {
        path.addArc(rect, deg(min(a0, a1)), deg(sweepAbs))
    }

    paint.textAlign = Paint.Align.LEFT

    // Дефолтный кегль; уменьшаем, пока не влезет целиком (без многоточий).
    var fs = bandW * 0.52f
    var lines: List<String>? = null
    while (fs >= bandW * 0.02f) {
        paint.textSize = fs
        val maxLines = min(8, (bandW * 0.92f / (fs * 1.15f)).toInt().coerceAtLeast(1))
        val wrapped = wrapText(text, paint, avail, maxLines)
        if (wrapped != null) {
            lines = wrapped; break
        }
        fs *= 0.84f
    }
    val ls = lines ?: return
    if (fs * camScale < TEXT_MIN_SCREEN_FS) return // проявится при приближении

    paint.textSize = fs
    val base = -(paint.ascent() + paint.descent()) / 2f
    val n = ls.size
    ls.forEachIndexed { i, line ->
        val lw = paint.measureText(line)
        val v = (i - (n - 1) / 2f) * fs * 1.15f + base
        canvas.drawTextOnPath(line, path, (arcLen - lw) / 2f, v, paint)
    }
}

/**
 * Текст вдоль радиуса (90°) для событий короче дня. Рисуется всегда.
 * Ориентация ЕДИНАЯ для прошлого и будущего: низ глифов — назад по времени
 * (у будущих событий это в сторону стрелки «сейчас»); зависит от CCW/CW.
 */
private fun drawRadialTitle(
    canvas: Canvas,
    paint: Paint,
    text: String,
    cx: Float,
    cy: Float,
    r: Float,
    bandW: Float,
    a0: Double,
    a1: Double,
    ccw: Boolean,
    camScale: Float,
) {
    val amid = (a0 + a1) / 2
    val angW = (abs(a1 - a0) * r).toFloat() // угловая (узкая) ширина чанка
    val maxLen = bandW * 0.9f               // радиальная (длинная) сторона

    paint.textAlign = Paint.Align.CENTER

    // Кегль: дефолт для узких, ограничен угловой шириной чанка.
    var fs = min(bandW * 0.30f, angW / 1.25f)
    var lines: List<String>? = null
    while (fs >= bandW * 0.01f) {
        paint.textSize = fs
        val maxLines = min(8, (angW / (fs * 1.25f)).toInt().coerceAtLeast(1))
        val wrapped = wrapText(text, paint, maxLen, maxLines)
        if (wrapped != null) {
            lines = wrapped; break
        }
        fs *= 0.84f
    }
    val ls = lines ?: return
    if (fs * camScale < TEXT_MIN_SCREEN_FS) return // проявится при приближении

    paint.textSize = fs

    // Низ глифов — назад по времени (единая ориентация для всех событий).
    val dirSign = if (ccw) 1.0 else -1.0
    val dx = -sin(amid) * dirSign
    val dy = cos(amid) * dirSign
    // Базовая линия = поворот d на -90° (тогда «низ» глифов совпадает с d).
    val ux = dy
    val uy = -dx
    val rotDeg = (atan2(uy, ux) * 180.0 / PI).toFloat()

    val mx = cx + (cos(amid) * r).toFloat()
    val my = cy + (sin(amid) * r).toFloat()

    canvas.save()
    canvas.translate(mx, my)
    canvas.rotate(rotDeg)
    val base = -(paint.ascent() + paint.descent()) / 2f
    val n = ls.size
    ls.forEachIndexed { i, line ->
        val y = (i - (n - 1) / 2f) * fs * 1.25f + base
        canvas.drawText(line, 0f, y, paint)
    }
    canvas.restore()
}

/**
 * Перенос по словам в НЕ БОЛЕЕ [maxLines] строк ширины [maxW].
 * null = не влезает (нужен кегль меньше). Слишком длинное слово тоже = null.
 */
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
            if (cur.isEmpty()) return null // слово шире строки
            lines.add(cur.toString())
            if (lines.size >= maxLines) return null
            cur = StringBuilder(w)
            if (paint.measureText(w) > maxW) return null
        }
    }
    if (cur.isNotEmpty()) lines.add(cur.toString())
    return if (lines.size <= maxLines) lines else null
}
