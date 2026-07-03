package com.circularcalendar.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.RenderNode
import android.view.Choreographer
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import java.time.Duration
import java.time.LocalDateTime
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

// ─────────────────────────────────────────────────────────────────────────────
// WheelView — сцена + камера + жесты.
//
// Рендер: сцена пишется в RenderNode (display list кэшируется на GPU);
// на кадр — только canvas.concat(camera) + drawRenderNode. Пере-запись сцены —
// ТОЛЬКО когда LOD-окно устарело: зум ушёл за порог ×1.35 или видимый сектор
// вышел за записанное (с запасом) окно. Внутри записи рендерер отсекает
// события мельче полпикселя и всё вне видимого сектора — поэтому даже тысячи
// экземпляров событий не гробят кадр.
//
// Жесты — порт из Flutter-версии: осевой замок (порог 10dp, гистерезис 1.4),
// центр = панорама, два пальца = пан+пинч, инерция exp(-2.2·dt), даблтап = сброс.
// ─────────────────────────────────────────────────────────────────────────────

class WheelView(context: Context) : View(context) {

    private val camera = Matrix()
    private val node = RenderNode("wheel")

    private var settings = WheelSettings()
    private var bands: List<EventBand> = emptyList()
    private var now: LocalDateTime = LocalDateTime.now()

    // Параметры последней записи сцены.
    private var sceneDirty = true
    private var recScale = 1f
    private var recFull = true
    private var recWinStart = 0.0
    private var recWinLen = 1.0

    // ── Камера ────────────────────────────────────────────────────────────────

    private val tmpPts = FloatArray(8)
    private val invCamera = Matrix()

    private fun wheelCenterX(): Float {
        tmpPts[0] = width / 2f; tmpPts[1] = height / 2f
        camera.mapPoints(tmpPts, 0, tmpPts, 0, 1)
        return tmpPts[0]
    }

    private fun wheelCenterY(): Float {
        tmpPts[0] = width / 2f; tmpPts[1] = height / 2f
        camera.mapPoints(tmpPts, 0, tmpPts, 0, 1)
        return tmpPts[1]
    }

    private fun currentScale(): Float = camera.mapRadius(1f)

    private fun innerScreenR(): Float =
        min(width, height) / 2f * 0.20f * currentScale()

    private fun panBy(dx: Float, dy: Float) {
        camera.postTranslate(dx, dy)
        invalidate()
    }

    private fun rotateBy(rad: Double, px: Float, py: Float) {
        camera.postRotate((rad * 180.0 / Math.PI).toFloat(), px, py)
        invalidate()
    }

    private fun zoomBy(fx: Float, fy: Float, s: Float) {
        val cur = currentScale()
        val es = (cur * s).coerceIn(0.4f, 40.0f) / cur
        camera.postScale(es, es, fx, fy)
        invalidate()
    }

    // ── Жесты ─────────────────────────────────────────────────────────────────

    private val density = resources.displayMetrics.density
    private val lockThreshold = 10.0f * density
    private val lockHysteresis = 1.4f

    private var gestureMode = 0 // 0 = не решено, 1 = вращение, 2 = панорама
    private var accumTan = 0f
    private var accumRad = 0f

    private var lastFocalX = 0f
    private var lastFocalY = 0f
    private var lastSpan = 0f

    private var angVel = 0.0
    private var velTimeMs = 0L
    private var inertiaRunning = false
    private var lastFrameNanos = 0L

    private val doubleTapDetector = GestureDetector(
        context,
        object : GestureDetector.SimpleOnGestureListener() {
            override fun onDoubleTap(e: MotionEvent): Boolean {
                resetCamera()
                return true
            }
        },
    )

    private val inertiaCallback = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            if (!inertiaRunning) return
            val dt = (frameTimeNanos - lastFrameNanos) / 1e9
            lastFrameNanos = frameTimeNanos
            if (dt > 0) {
                rotateBy(angVel * dt, wheelCenterX(), wheelCenterY())
                angVel *= exp(-2.2 * dt)
                if (abs(angVel) < 0.05) {
                    angVel = 0.0
                    inertiaRunning = false
                    return
                }
            }
            Choreographer.getInstance().postFrameCallback(this)
        }
    }

    private fun stopInertia() {
        inertiaRunning = false
        angVel = 0.0
    }

    private fun startInertia() {
        angVel = (angVel * 0.4).coerceIn(-9.0, 9.0)
        if (abs(angVel) > 0.25) {
            inertiaRunning = true
            lastFrameNanos = System.nanoTime()
            Choreographer.getInstance().postFrameCallback(inertiaCallback)
        } else {
            angVel = 0.0
        }
    }

    fun resetCamera() {
        stopInertia()
        camera.reset()
        invalidate()
    }

    private fun focalOf(e: MotionEvent, excludeIndex: Int = -1): Pair<Float, Float> {
        var sx = 0f; var sy = 0f; var n = 0
        for (i in 0 until e.pointerCount) {
            if (i == excludeIndex) continue
            sx += e.getX(i); sy += e.getY(i); n++
        }
        return if (n > 0) Pair(sx / n, sy / n) else Pair(0f, 0f)
    }

    private fun spanOf(e: MotionEvent, excludeIndex: Int = -1): Float {
        val idx = ArrayList<Int>(e.pointerCount)
        for (i in 0 until e.pointerCount) if (i != excludeIndex) idx.add(i)
        if (idx.size < 2) return 0f
        return hypot(
            e.getX(idx[0]) - e.getX(idx[1]),
            e.getY(idx[0]) - e.getY(idx[1]),
        )
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        doubleTapDetector.onTouchEvent(e)

        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                stopInertia()
                val dx = e.x - wheelCenterX()
                val dy = e.y - wheelCenterY()
                val inCenter = hypot(dx, dy) < innerScreenR()
                gestureMode = if (inCenter) 2 else 0
                accumTan = 0f; accumRad = 0f
                lastFocalX = e.x; lastFocalY = e.y
                lastSpan = 0f
                velTimeMs = e.eventTime
            }

            MotionEvent.ACTION_POINTER_DOWN -> {
                val (fx, fy) = focalOf(e)
                lastFocalX = fx; lastFocalY = fy
                lastSpan = spanOf(e)
                gestureMode = 2
                angVel = 0.0
            }

            MotionEvent.ACTION_MOVE -> {
                if (e.pointerCount >= 2) {
                    val (fx, fy) = focalOf(e)
                    panBy(fx - lastFocalX, fy - lastFocalY)
                    val span = spanOf(e)
                    if (lastSpan > 1f && span > 1f) zoomBy(fx, fy, span / lastSpan)
                    lastFocalX = fx; lastFocalY = fy
                    lastSpan = span
                    angVel = 0.0
                    gestureMode = 2
                    return true
                }

                val dxAll = e.x - lastFocalX
                val dyAll = e.y - lastFocalY
                if (dxAll == 0f && dyAll == 0f) return true

                val cxW = wheelCenterX()
                val cyW = wheelCenterY()
                val prevX = lastFocalX
                val prevY = lastFocalY
                val rvx = prevX - cxW
                val rvy = prevY - cyW
                val r = max(hypot(rvx, rvy), 1f)
                val radUx = rvx / r
                val radUy = rvy / r
                val tanUx = -radUy
                val tanUy = radUx
                val dRad = dxAll * radUx + dyAll * radUy
                val dTan = dxAll * tanUx + dyAll * tanUy

                lastFocalX = e.x; lastFocalY = e.y

                if (gestureMode == 0) {
                    accumTan += dTan
                    accumRad += dRad
                    val at = abs(accumTan)
                    val ar = abs(accumRad)
                    gestureMode = when {
                        at >= lockThreshold && at >= ar * lockHysteresis -> 1
                        ar >= lockThreshold && ar >= at * lockHysteresis -> 2
                        else -> return true
                    }
                }

                if (gestureMode == 2) {
                    panBy(dxAll, dyAll)
                    angVel = 0.0
                    return true
                }

                val rot = (dTan / r).toDouble()
                val t = e.eventTime
                val dt = ((t - velTimeMs) / 1000.0).coerceIn(0.008, 0.1)
                angVel = angVel * 0.5 + (rot / dt) * 0.5
                velTimeMs = t
                rotateBy(rot, cxW, cyW)
            }

            MotionEvent.ACTION_POINTER_UP -> {
                val (fx, fy) = focalOf(e, e.actionIndex)
                lastFocalX = fx; lastFocalY = fy
                lastSpan = spanOf(e, e.actionIndex)
            }

            MotionEvent.ACTION_UP -> {
                if (gestureMode == 1) startInertia() else angVel = 0.0
            }

            MotionEvent.ACTION_CANCEL -> {
                angVel = 0.0
            }
        }
        return true
    }

    // ── Сцена и LOD ───────────────────────────────────────────────────────────

    fun update(settings: WheelSettings, events: List<CalendarEvent>, now: LocalDateTime) {
        this.settings = settings
        this.now = now
        val filtered = events.filter { settings.calendarFilter.allows(it) }
        // Укладка по слоям — одна на смену данных (стабильные кольца при зуме).
        this.bands = buildEventBands(filtered, now.year)
        setBackgroundColor(settings.theme.background)
        sceneDirty = true
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        sceneDirty = true
    }

    /**
     * Видимое окно в frac-пространстве: [start, len] или null = весь круг.
     * Углы экрана переводятся в контент-пространство; если центр круга на
     * экране — видно всё; иначе угловой диапазон = углы 4 углов экрана
     * (экстремумы atan2 по выпуклому четырёхугольнику — в вершинах).
     */
    private fun computeWindow(): DoubleArray? {
        if (currentScale() <= 1.05f) return null
        if (!camera.invert(invCamera)) return null

        val w = width.toFloat(); val h = height.toFloat()
        tmpPts[0] = 0f; tmpPts[1] = 0f
        tmpPts[2] = w; tmpPts[3] = 0f
        tmpPts[4] = w; tmpPts[5] = h
        tmpPts[6] = 0f; tmpPts[7] = h
        invCamera.mapPoints(tmpPts)

        val cx = w / 2f; val cy = h / 2f

        // Центр внутри выпуклого четырёхугольника? (знаки векторных произведений)
        var pos = false; var neg = false
        for (i in 0 until 4) {
            val x1 = tmpPts[i * 2]; val y1 = tmpPts[i * 2 + 1]
            val x2 = tmpPts[((i + 1) % 4) * 2]; val y2 = tmpPts[((i + 1) % 4) * 2 + 1]
            val cross = (x2 - x1) * (cy - y1) - (y2 - y1) * (cx - x1)
            if (cross > 0) pos = true else if (cross < 0) neg = true
        }
        if (!(pos && neg)) return null // центр виден → весь круг

        // Углы вершин, развёрнутые вокруг первого.
        val a0 = atan2((tmpPts[1] - cy).toDouble(), (tmpPts[0] - cx).toDouble())
        var aMin = a0; var aMax = a0
        for (i in 1 until 4) {
            var a = atan2((tmpPts[i * 2 + 1] - cy).toDouble(), (tmpPts[i * 2] - cx).toDouble())
            while (a - a0 > Math.PI) a -= 2 * Math.PI
            while (a - a0 < -Math.PI) a += 2 * Math.PI
            aMin = min(aMin, a); aMax = max(aMax, a)
        }

        // Угол → frac (инверсия angleOf из рендерера).
        val year = now.year
        val yearStart = LocalDateTime.of(year, 1, 1, 0, 0)
        val totalSec = Duration.between(yearStart, LocalDateTime.of(year + 1, 1, 1, 0, 0))
            .seconds.toDouble()
        val nowFrac = Duration.between(yearStart, now).seconds.toDouble() / totalSec
        val dir = if (settings.counterClockwise) 1.0 else -1.0
        val anchorA = if (settings.nowAtBottom) Math.PI / 2 else -Math.PI / 2
        val f1 = nowFrac + dir * (anchorA - aMin) / (2 * Math.PI)
        val f2 = nowFrac + dir * (anchorA - aMax) / (2 * Math.PI)
        var start = min(f1, f2)
        val len = abs(f1 - f2)
        if (len >= 0.999) return null
        start -= Math.floor(start) // нормализация в [0,1)
        return doubleArrayOf(start, len)
    }

    private fun rerecordNeeded(win: DoubleArray?): Boolean {
        if (sceneDirty) return true
        val ratio = currentScale() / recScale
        if (ratio > 1.35f || ratio < 0.74f) return true
        if (recFull) return false
        if (win == null) return true
        // Текущее окно должно лежать внутри записанного (mod 1).
        for (k in -1..1) {
            val s = win[0] + k
            if (s >= recWinStart - 1e-9 && s + win[1] <= recWinStart + recWinLen + 1e-9) return false
        }
        return true
    }

    private fun recordScene(win: DoubleArray?) {
        if (width <= 0 || height <= 0) return
        val scale = currentScale()
        // Запас 35% длины окна с каждой стороны — панорама не требует
        // перезаписи каждый кадр.
        val lod: Lod
        if (win == null) {
            lod = Lod(camScale = scale)
            recFull = true
        } else {
            val padded = win[1] * 0.35
            val start = win[0] - padded
            val len = win[1] + 2 * padded
            if (len >= 0.999) {
                lod = Lod(camScale = scale)
                recFull = true
            } else {
                val startN = start - Math.floor(start)
                lod = Lod(camScale = scale, winStart = startN, winLen = len, full = false)
                recFull = false
                recWinStart = startN
                recWinLen = len
            }
        }
        recScale = scale

        node.setPosition(0, 0, width, height)
        val c = node.beginRecording(width, height)
        try {
            drawWheel(c, width.toFloat(), height.toFloat(), now, settings, bands, lod)
        } finally {
            node.endRecording()
        }
        sceneDirty = false
    }

    override fun onDraw(canvas: Canvas) {
        if (width <= 0 || height <= 0) return
        val win = computeWindow()
        if (rerecordNeeded(win)) recordScene(win)

        canvas.save()
        canvas.concat(camera)
        if (canvas.isHardwareAccelerated && node.hasDisplayList()) {
            canvas.drawRenderNode(node)
        } else {
            // Софт-фолбэк (скриншоты и т.п.) — рисуем напрямую, без LOD.
            drawWheel(canvas, width.toFloat(), height.toFloat(), now, settings, bands)
        }
        canvas.restore()
    }
}
