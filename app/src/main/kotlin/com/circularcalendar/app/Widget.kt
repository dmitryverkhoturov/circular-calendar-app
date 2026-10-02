package com.circularcalendar.app

import android.app.AlarmManager
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.os.Bundle
import android.widget.RemoteViews
import java.time.LocalDateTime
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

// ─────────────────────────────────────────────────────────────────────────────
// Виджет на домашнем экране. Тот же drawWheel/drawBodies, что и в приложении,
// но в картинку (RemoteViews умеет только готовые вьюхи и битмапы — живого
// вращения пальцем в виджете Android не даёт). Перерисовка — по будильнику
// без пробуждения устройства (раз в 15 мин / час), при смене времени и при
// изменении настроек приложения.
// ─────────────────────────────────────────────────────────────────────────────

/** Масштабы виджета: название → зум. */
val WIDGET_ZOOMS = listOf("Год" to 1.0, "Квартал" to 1.8, "Месяц" to 3.6, "Неделя" to 14.0)
val WIDGET_POS = listOf("По центру", "Сверху", "Снизу")
val WIDGET_CUTS = listOf("Кадр", "Месяцы", "Недели", "Дни")

/**
 * Настройки одного виджета. [theme] −1 = как в приложении, иначе индекс в PRESETS.
 * [zoom] −1 = кадр выставлен пальцами: зум [scale], фокус кадра в полярных
 * координатах ОТНОСИТЕЛЬНО «сейчас» — радиус [fr] (доли maxR) и угол [fa] от
 * стрелки, чтобы кадр ехал вместе со временем.
 */
class WidgetConfig(
    val zoom: Int = 0,
    val pos: Int = 0,
    val arrowMoves: Boolean = false,
    val theme: Int = -1,
    val background: Boolean = false,
    val scale: Float = 1f,
    val fr: Float = 0f,
    val fa: Float = 0f,
    val cut: Int = 0,
) {
    fun copy(
        zoom: Int = this.zoom, pos: Int = this.pos, arrowMoves: Boolean = this.arrowMoves,
        theme: Int = this.theme, background: Boolean = this.background,
        scale: Float = this.scale, fr: Float = this.fr, fa: Float = this.fa, cut: Int = this.cut,
    ) = WidgetConfig(zoom, pos, arrowMoves, theme, background, scale, fr, fa, cut)

    fun save(ctx: Context, id: Int) {
        ctx.getSharedPreferences("widget", Context.MODE_PRIVATE).edit()
            .putInt("zoom$id", zoom).putInt("pos$id", pos).putBoolean("arrow$id", arrowMoves)
            .putInt("theme$id", theme).putBoolean("bg$id", background)
            .putFloat("scale$id", scale).putFloat("fr$id", fr).putFloat("fa$id", fa)
            .putInt("cut$id", cut).apply()
    }

    companion object {
        fun load(ctx: Context, id: Int): WidgetConfig {
            val p = ctx.getSharedPreferences("widget", Context.MODE_PRIVATE)
            val app = appSettings(ctx)
            return WidgetConfig(
                p.getInt("zoom$id", 0).coerceIn(-1, WIDGET_ZOOMS.size - 1),
                p.getInt("pos$id", 0).coerceIn(0, WIDGET_POS.size - 1),
                p.getBoolean("arrow$id", app.arrowMoves),
                p.getInt("theme$id", -1).coerceIn(-1, PRESETS.size - 1),
                p.getBoolean("bg$id", false),
                p.getFloat("scale$id", 1f), p.getFloat("fr$id", 0f), p.getFloat("fa$id", 0f),
                p.getInt("cut$id", 0).coerceIn(0, WIDGET_CUTS.size - 1),
            )
        }

        fun delete(ctx: Context, id: Int) {
            ctx.getSharedPreferences("widget", Context.MODE_PRIVATE).edit()
                .remove("zoom$id").remove("pos$id").remove("arrow$id").remove("theme$id").remove("bg$id")
                .remove("scale$id").remove("fr$id").remove("fa$id").remove("cut$id").apply()
        }
    }
}

/** Настройки круга из приложения (те же ключи, что в MainActivity). */
fun appSettings(ctx: Context): WheelSettings {
    val p = ctx.getSharedPreferences("settings", Context.MODE_PRIVATE)
    var filter = CalendarFilter()
    p.getString("calFilter", null)?.let { s ->
        try {
            val arr = org.json.JSONArray(s)
            filter = CalendarFilter(HashSet<String>().apply { for (i in 0 until arr.length()) add(arr.getString(i)) })
        } catch (_: Exception) {}
    }
    return WheelSettings(
        counterClockwise = p.getBoolean("ccw", true),
        nowAtBottom = p.getBoolean("nowBottom", true),
        design = Design.fromJson(p.getString("design", null)),
        calendarFilter = filter,
        largeEventsOnTop = p.getBoolean("largeTop", false),
        keplerOrbit = p.getBoolean("kepler", false),
        showOrbits = false,
        arrowMoves = p.getBoolean("arrowMoves", false),
    )
}

/** Рисует виджет в битмап [w]×[h] px. Вызывать с фонового потока. */
fun renderWidget(
    ctx: Context, w: Int, h: Int, cfg: WidgetConfig, events: List<CalendarEvent>, now: LocalDateTime,
): Bitmap {
    Fonts.ctx = ctx.applicationContext
    val density = ctx.resources.displayMetrics.density
    val app = appSettings(ctx)
    val design = if (cfg.theme >= 0) PRESETS[cfg.theme].second else app.design
    val settings = app.copy(design = design, arrowMoves = cfg.arrowMoves)
    val tm = TimeMap(now, settings.counterClockwise, settings.nowAtBottom, settings.keplerOrbit, settings.arrowMoves)
    val bands = EventIndex(buildEventBands(
        events.filter { settings.calendarFilter.allows(it) }, now.year, tm.nowFrac, settings.largeEventsOnTop))

    val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    val c = Canvas(bmp)
    val wf = w.toFloat(); val hf = h.toFloat()

    if (cfg.background) {
        val lk = settings.look
        val r = widgetCornerPx(ctx)
        c.drawRoundRect(RectF(0f, 0f, wf, hf), r, r, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = RadialGradient(wf / 2, hf * 0.45f, max(wf, hf) * 0.75f,
                intArrayOf(lk.bd.bg0, lk.bd.bg1), null, Shader.TileMode.CLAMP)
        })
    }

    val g = WheelGeom(wf, hf)
    var f = widgetFrame(g, cfg, tm.nowAngle, density)
    // Клин: целые единицы, задетые кадром, и кадр, подогнанный под них.
    val wedge = if (cfg.cut == 0) null else wedgeRange(
        wf.toDouble(), hf.toDouble(), g, tm, f[1], f[2],
        wf / 2.0 + (g.cx - f[1]) * f[0], hf / 2.0 + (g.cy - f[2]) * f[0], f[0], cfg.cut)
    if (wedge != null) f = wedgeFit(wf.toDouble(), hf.toDouble(), g, tm, settings.look.holeR * g.outerR, wedge[0], wedge[1])
    val s = f[0]; val fx = f[1]; val fy = f[2]
    // Центр круга на экране.
    val camOx = wf / 2.0 + (g.cx - fx) * s
    val camOy = hf / 2.0 + (g.cy - fy) * s

    val lod = widgetLod(wf.toDouble(), hf.toDouble(), fx, fy, camOx, camOy, s, density)
    c.save()
    c.translate(wf / 2, hf / 2)
    c.scale(s.toFloat(), s.toFloat())
    drawWheel(c, wf, hf, tm, settings, bands, lod)
    // Клин режется по ободу, стрелка — поверх маски (кончик выходит за обод).
    if (wedge != null) {
        c.restore(); c.save()
        cutWedge(c, g, tm, camOx, camOy, s, wedge[0], wedge[1], density)
        c.translate(wf / 2, hf / 2)
        c.scale(s.toFloat(), s.toFloat())
    }
    drawBodies(c, wf, hf, tm, settings, lod)
    c.restore()
    return bmp
}

/**
 * Кадр виджета: [зум, фокус x, фокус y] (фокус — точка контента в центре кадра).
 * Пресет: на «Годе» — центр круга; с зумом фокус уезжает к «сейчас», чтобы
 * кончик стрелки стоял на 85 % пути от центра кадра к краю, а над ним были
 * дни и события. Свой кадр — из [WidgetConfig.scale]/[fr]/[fa].
 */
fun widgetFrame(g: WheelGeom, cfg: WidgetConfig, nowAngle: Double, density: Float): DoubleArray {
    if (cfg.zoom < 0) {
        val a = nowAngle + cfg.fa
        val r = cfg.fr * g.maxR
        return doubleArrayOf(cfg.scale.toDouble(), g.cx + cos(a) * r, g.cy + sin(a) * r)
    }
    val s = WIDGET_ZOOMS[cfg.zoom].second * 0.97
    val half = g.maxR / s
    val fr = if (s < 1.2) 0.0 else max(0.0, g.tipR - 0.85 * half)
    val fx = g.cx + cos(nowAngle) * fr
    var fy = g.cy + sin(nowAngle) * fr
    // Положение: если круг не занимает виджет целиком — прижать вверх/вниз.
    val camOy = g.cy + (g.cy - fy) * s
    val pad = 4.0 * density
    val top = camOy - g.keepR * s; val bottom = camOy + g.keepR * s
    val dy = when (cfg.pos) {
        1 -> if (top > pad) pad - top else 0.0
        2 -> if (bottom < g.cy * 2 - pad) g.cy * 2 - pad - bottom else 0.0
        else -> 0.0
    }
    fy -= dy / s
    return doubleArrayOf(s, fx, fy)
}

/** Свой кадр из фокуса (контент) и зума — для жестов в настройке виджета. */
fun widgetCustom(cfg: WidgetConfig, g: WheelGeom, nowAngle: Double, s: Double, fx: Double, fy: Double): WidgetConfig {
    val dx = fx - g.cx; val dy = fy - g.cy
    val r = hypot(dx, dy)
    return cfg.copy(
        zoom = -1, pos = 0, scale = s.toFloat(),
        fr = (r / g.maxR).toFloat(),
        fa = if (r < 1e-9) 0f else (atan2(dy, dx) - nowAngle).toFloat(),
    )
}

/**
 * Форма «клин», как на иконке: месяцы/недели/дни, которых кадр касается
 * ободом (самое широкое место) хоть одним днём, — ЦЕЛИКОМ. Возвращает
 * диапазон [f0, f1] или null, если в кадре виден весь обод (год).
 */
private fun wedgeRange(
    w: Double, h: Double, g: WheelGeom, tm: TimeMap,
    fx: Double, fy: Double, camOx: Double, camOy: Double, s: Double, cut: Int,
): DoubleArray? {
    fun inside(f: Double): Boolean {
        val a = tm.angleOf(f)
        val x = camOx + cos(a) * g.outerR * s; val y = camOy + sin(a) * g.outerR * s
        return x in 0.0..w && y in 0.0..h
    }
    // Опорная точка — «сейчас», если обод там в кадре, иначе направление на центр кадра.
    val seed = if (inside(tm.nowFrac) || hypot(fx - g.cx, fy - g.cy) < 1e-6) tm.nowFrac
        else tm.fracOfAngle(atan2(fy - g.cy, fx - g.cx))
    val step = 1.0 / 20000
    var lo = seed; var hi = seed
    if (inside(seed)) {
        while (inside(lo - step) && seed - lo < 1.0) lo -= step
        while (inside(hi + step) && hi - seed < 1.0) hi += step
        if (hi - lo >= 0.98) return null
    }
    // Расширяем наружу до границ единиц.
    var d = tm.yearStart.plusSeconds((lo * tm.totalSec).toLong()).toLocalDate()
    d = when (cut) {
        1 -> d.withDayOfMonth(1)
        2 -> d.minusDays((d.dayOfWeek.value - 1).toLong())
        else -> d
    }
    val f0 = tm.fracOf(d.atStartOfDay())
    while (tm.fracOf(d.atStartOfDay()) < hi) {
        d = when (cut) { 1 -> d.plusMonths(1); 2 -> d.plusWeeks(1); else -> d.plusDays(1) }
    }
    val f1 = tm.fracOf(d.atStartOfDay())
    return if (f1 - f0 >= 0.98) null else doubleArrayOf(f0, f1)
}

/** Кадр, в который клин [f0, f1] (от дырки до кончика стрелки) влезает целиком: [зум, фокус x, фокус y]. */
private fun wedgeFit(w: Double, h: Double, g: WheelGeom, tm: TimeMap, holeR: Double, f0: Double, f1: Double): DoubleArray {
    val rOut = g.tipR + 3 * g.unit
    var x0 = 1e18; var x1 = -1e18; var y0 = 1e18; var y1 = -1e18
    val n = 128
    for (i in 0..n) {
        val a = tm.angleOf(f0 + (f1 - f0) * i / n)
        for (r in doubleArrayOf(holeR, rOut)) {
            val x = g.cx + cos(a) * r; val y = g.cy + sin(a) * r
            x0 = min(x0, x); x1 = max(x1, x); y0 = min(y0, y); y1 = max(y1, y)
        }
    }
    val sFit = min(w / (x1 - x0), h / (y1 - y0)) * 0.94
    // Узкий клин (неделя, дни) целиком был бы щепкой: подгоняем по ширине
    // обода (самое широкое место), а сужающийся верх уходит за край кадра.
    val s = min(w / (x1 - x0), h / (y1 - y0) * 3) * 0.94
    if (s <= sFit * 1.01) return doubleArrayOf(sFit, (x0 + x1) / 2, (y0 + y1) / 2)
    val am = tm.angleOf((f0 + f1) / 2)
    val ux = cos(am); val uy = sin(am)
    val halfExt = min(if (abs(ux) > 1e-6) w / 2 / abs(ux) else 1e18, if (abs(uy) > 1e-6) h / 2 / abs(uy) else 1e18)
    val back = (halfExt * 0.97) / s
    return doubleArrayOf(s, g.cx + ux * (rOut - back), g.cy + uy * (rOut - back))
}

/** Маска клина: всё вне сектора [f0, f1] до кончика стрелки стирается (с антиалиасингом). */
private fun cutWedge(
    c: Canvas, g: WheelGeom, tm: TimeMap, camOx: Double, camOy: Double, s: Double, f0: Double, f1: Double, density: Float,
) {
    val far = g.outerR * s + 3.0 * density
    val path = android.graphics.Path()
    path.moveTo(camOx.toFloat(), camOy.toFloat())
    val n = 256
    for (i in 0..n) {
        val a = tm.angleOf(f0 + (f1 - f0) * i / n)
        path.lineTo((camOx + cos(a) * far).toFloat(), (camOy + sin(a) * far).toFloat())
    }
    path.close()
    path.fillType = android.graphics.Path.FillType.INVERSE_WINDING
    c.drawPath(path, Paint(Paint.ANTI_ALIAS_FLAG).apply {
        xfermode = android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.CLEAR)
    })
}

private fun widgetCornerPx(ctx: Context): Float = try {
    ctx.resources.getDimension(android.R.dimen.system_app_widget_background_radius)
} catch (_: Exception) { 16f * ctx.resources.displayMetrics.density }

/** LOD кадра виджета: экран [0,w]×[0,h], центр круга на экране — (camOx, camOy). */
private fun widgetLod(
    w: Double, h: Double, originX: Double, originY: Double, camOx: Double, camOy: Double, s: Double, density: Float,
): Lod {
    val qx = camOx.coerceIn(0.0, w); val qy = camOy.coerceIn(0.0, h)
    val inside = qx == camOx && qy == camOy
    val rMin = if (inside) 0.0 else hypot(qx - camOx, qy - camOy) / s
    val xs = doubleArrayOf(0.0, w, w, 0.0); val ys = doubleArrayOf(0.0, 0.0, h, h)
    var rMax = 0.0
    for (i in 0..3) rMax = max(rMax, hypot(xs[i] - camOx, ys[i] - camOy))
    rMax /= s
    val visR = hypot(w, h) / 2 / s
    if (inside) return Lod(s, originX, originY, 0f, full = true, rMin = 0.0, rMax = rMax, visR = visR, density = density)
    val a0 = atan2(ys[0] - camOy, xs[0] - camOx)
    var aMin = a0; var aMax = a0
    for (i in 1..3) {
        var a = atan2(ys[i] - camOy, xs[i] - camOx)
        while (a - a0 > PI) a -= 2 * PI
        while (a - a0 < -PI) a += 2 * PI
        aMin = min(aMin, a); aMax = max(aMax, a)
    }
    val p = (aMax - aMin) * 0.02 + 1e-9
    return Lod(s, originX, originY, 0f, full = false, aLo = aMin - p, aHi = aMax + p,
        rMin = rMin, rMax = rMax, visR = visR, density = density)
}

/** События для виджета: реальный календарь или пусто (мок на домашнем экране вводил бы в заблуждение). */
fun widgetEvents(ctx: Context, now: LocalDateTime): List<CalendarEvent> {
    val (d, evs) = CalendarRepo.loadAll(ctx, now.year)
    return if (d.ok) evs else emptyList()
}

/** Размер виджета в px (портретный: ширина — минимальная, высота — максимальная). */
fun widgetSizePx(ctx: Context, opts: Bundle?): Pair<Int, Int> {
    val dm = ctx.resources.displayMetrics
    var wDp = opts?.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH) ?: 0
    var hDp = opts?.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT) ?: 0
    if (wDp <= 0) wDp = 160
    if (hDp <= 0) hDp = 160
    // Битмап виджета ограничен по памяти — держим сторону не больше ~1000 px.
    val k = min(dm.density.toDouble(), 1000.0 / max(wDp, hDp))
    return Pair((wDp * k).toInt().coerceAtLeast(64), (hDp * k).toInt().coerceAtLeast(64))
}

class WheelWidget : AppWidgetProvider() {

    override fun onUpdate(ctx: Context, mgr: AppWidgetManager, ids: IntArray) = redraw(ctx, ids)

    override fun onAppWidgetOptionsChanged(ctx: Context, mgr: AppWidgetManager, id: Int, opts: Bundle) =
        redraw(ctx, intArrayOf(id))

    override fun onDeleted(ctx: Context, ids: IntArray) {
        for (id in ids) WidgetConfig.delete(ctx, id)
    }

    override fun onEnabled(ctx: Context) = schedule(ctx)

    override fun onDisabled(ctx: Context) {
        val pi = tickIntent(ctx)
        ctx.getSystemService(AlarmManager::class.java).cancel(pi)
        pi.cancel()
        ctx.getSharedPreferences("widget", Context.MODE_PRIVATE).edit().remove("period").apply()
    }

    override fun onReceive(ctx: Context, intent: Intent) {
        when (intent.action) {
            ACTION_TICK, Intent.ACTION_TIME_CHANGED, Intent.ACTION_TIMEZONE_CHANGED, Intent.ACTION_DATE_CHANGED ->
                redraw(ctx, allIds(ctx))
            else -> super.onReceive(ctx, intent)
        }
    }

    private fun redraw(ctx: Context, ids: IntArray) {
        if (ids.isEmpty()) return
        val pending = goAsync()
        Thread {
            try {
                schedule(ctx)
                val now = LocalDateTime.now()
                val events = widgetEvents(ctx, now)
                val mgr = AppWidgetManager.getInstance(ctx)
                for (id in ids) {
                    val (w, h) = widgetSizePx(ctx, mgr.getAppWidgetOptions(id))
                    val bmp = renderWidget(ctx, w, h, WidgetConfig.load(ctx, id), events, now)
                    mgr.updateAppWidget(id, views(ctx, bmp))
                }
            } finally {
                pending.finish()
            }
        }.start()
    }

    companion object {
        const val ACTION_TICK = "com.circularcalendar.app.WIDGET_TICK"

        fun allIds(ctx: Context): IntArray =
            AppWidgetManager.getInstance(ctx).getAppWidgetIds(ComponentName(ctx, WheelWidget::class.java))

        /** Перерисовать все виджеты (после смены настроек в приложении). */
        fun refreshAll(ctx: Context) {
            val ids = allIds(ctx)
            if (ids.isEmpty()) return
            ctx.sendBroadcast(Intent(ctx, WheelWidget::class.java).setAction(ACTION_TICK))
        }

        fun views(ctx: Context, bmp: Bitmap): RemoteViews =
            RemoteViews(ctx.packageName, R.layout.widget_wheel).apply {
                setImageViewBitmap(R.id.widget_image, bmp)
                setOnClickPendingIntent(R.id.widget_root, PendingIntent.getActivity(
                    ctx, 0, Intent(ctx, MainActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                ))
            }

        private fun tickIntent(ctx: Context): PendingIntent = PendingIntent.getBroadcast(
            ctx, 1, Intent(ctx, WheelWidget::class.java).setAction(ACTION_TICK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        /**
         * Будильник без пробуждения (RTC): на «Годе» сутки — пара пикселей,
         * хватает раза в час; крупнее — раз в 15 минут. Спящий телефон не будим:
         * перерисуется, когда проснётся.
         */
        fun schedule(ctx: Context) {
            val ids = allIds(ctx)
            if (ids.isEmpty()) return
            val zoomed = ids.any { WidgetConfig.load(ctx, it).let { c -> c.zoom > 0 || (c.zoom < 0 && c.scale > 1.2f) } }
            val period = if (zoomed) AlarmManager.INTERVAL_FIFTEEN_MINUTES else AlarmManager.INTERVAL_HOUR
            val am = ctx.getSystemService(AlarmManager::class.java)
            val p = ctx.getSharedPreferences("widget", Context.MODE_PRIVATE)
            val alive = PendingIntent.getBroadcast(
                ctx, 1, Intent(ctx, WheelWidget::class.java).setAction(ACTION_TICK),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_NO_CREATE,
            ) != null
            if (alive && p.getLong("period", 0) == period) return
            p.edit().putLong("period", period).apply()
            am.setInexactRepeating(AlarmManager.RTC, System.currentTimeMillis() + period, period, tickIntent(ctx))
        }
    }
}
