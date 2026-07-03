package com.circularcalendar.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Picture
import android.view.Choreographer
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import java.time.LocalDateTime
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

// ─────────────────────────────────────────────────────────────────────────────
// WheelView — сцена + камера + жесты.
//
// Паттерн рендера (тот же инвариант, что во Flutter-версии):
// статичная сцена рисуется ОДИН РАЗ в Picture; на кадр —
// только canvas.concat(camera) + drawPicture. Никакой тяжёлой работы в onDraw.
//
// Жесты — порт из main.dart:
//  * один палец: осевой замок (тангенциальный свайп → вращение на месте,
//    радиальный → панорама; гистерезис 1.4, порог 10dp);
//  * один палец в «пустом центре» → чистая панорама;
//  * два пальца: панорама + пинч-зум к фокусу, без вращения;
//  * бросок → инерция вращения с трением exp(-2.2·dt);
//  * двойной тап → сброс камеры.
// ─────────────────────────────────────────────────────────────────────────────

class WheelView(context: Context) : View(context) {

    private var picture: Picture? = null
    private val camera = Matrix()

    private var settings = WheelSettings()
    private var events: List<CalendarEvent> = emptyList()
    private var now: LocalDateTime = LocalDateTime.now()

    // ── Камера ────────────────────────────────────────────────────────────────

    private val tmpPts = FloatArray(2)

    private fun wheelCenterX(): Float {
        tmpPts[0] = width / 2f; tmpPts[1] = height / 2f
        camera.mapPoints(tmpPts)
        return tmpPts[0]
    }

    private fun wheelCenterY(): Float {
        tmpPts[0] = width / 2f; tmpPts[1] = height / 2f
        camera.mapPoints(tmpPts)
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

    // 0 = не решено, 1 = вращение, 2 = панорама/мультитач
    private var gestureMode = 0
    private var accumTan = 0f
    private var accumRad = 0f

    private var lastFocalX = 0f
    private var lastFocalY = 0f
    private var lastSpan = 0f

    private var angVel = 0.0        // рад/с
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

    // Средняя точка всех активных пальцев (кроме исключаемого index).
    private fun focalOf(e: MotionEvent, excludeIndex: Int = -1): Pair<Float, Float> {
        var sx = 0f; var sy = 0f; var n = 0
        for (i in 0 until e.pointerCount) {
            if (i == excludeIndex) continue
            sx += e.getX(i); sy += e.getY(i); n++
        }
        return if (n > 0) Pair(sx / n, sy / n) else Pair(0f, 0f)
    }

    // Расстояние между первыми двумя пальцами (для пинч-зума).
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
                    // Два пальца: панорама + пинч-зум, без вращения.
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
                // Раскладка вектора движения на радиальную и тангенциальную
                // составляющие относительно ПРЕДЫДУЩЕЙ точки (как во Flutter).
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

                // Вращение на месте: угол из тангенциального смещения.
                val rot = (dTan / r).toDouble()
                val t = e.eventTime
                val dt = ((t - velTimeMs) / 1000.0).coerceIn(0.008, 0.1)
                angVel = angVel * 0.5 + (rot / dt) * 0.5
                velTimeMs = t
                rotateBy(rot, cxW, cyW)
            }

            MotionEvent.ACTION_POINTER_UP -> {
                // Перезаякорить фокус без поднимающегося пальца.
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

    // ── Сцена ─────────────────────────────────────────────────────────────────

    fun update(settings: WheelSettings, events: List<CalendarEvent>, now: LocalDateTime) {
        this.settings = settings
        this.events = events
        this.now = now
        setBackgroundColor(settings.theme.background)
        rebuildPicture()
        invalidate()
    }

    private fun rebuildPicture() {
        if (width <= 0 || height <= 0) return
        val pic = Picture()
        val c = pic.beginRecording(width, height)
        val filtered = events.filter { settings.calendarFilter.allows(it) }
        drawWheel(c, width.toFloat(), height.toFloat(), now, settings, filtered)
        pic.endRecording()
        picture = pic
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        rebuildPicture()
    }

    override fun onDraw(canvas: Canvas) {
        val pic = picture ?: return
        canvas.save()
        canvas.concat(camera)
        canvas.drawPicture(pic)
        canvas.restore()
    }
}
