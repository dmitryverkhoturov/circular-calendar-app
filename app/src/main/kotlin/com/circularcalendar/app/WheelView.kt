package com.circularcalendar.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RenderNode
import android.view.Choreographer
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import java.time.Duration
import java.time.LocalDateTime
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

// ─────────────────────────────────────────────────────────────────────────────
// WheelView — сцена + камера + жесты.
//
// Рендер: сцена пишется в RenderNode с ВКАТАННОЙ камерой и кэшируется как
// растровый слой (setUseCompositingLayer) — квадрат размером с диагональ
// экрана, чтобы вращение никогда не открывало пустых углов. На кадр —
// только дельта-трансформ (camera·recCamera⁻¹) + drawRenderNode: одна
// текстура, никакой растеризации сцены. Перезапись слоя — по порогам зума
// (×1.25), выходу окна видимости за записанный запас, выходу экрана за
// квадрат слоя, и в покое (для идеальной чёткости).
//
// Жесты — порт из Flutter-версии: осевой замок (порог 10dp, гистерезис 1.4),
// центр = панорама, два пальца = пан+пинч, даблтап = сброс.
// Инерция — у вращения, панорамы и зума, затухание exp(-4.4·dt).
// Пороги инерции вращения — в ЭКРАННЫХ px/s (|angVel|·r точки хвата):
// на сильном зуме угловая скорость микроскопическая, и пороги в рад/с
// убивали инерцию (баг v0.5 «на масштабе часов не крутится с инерцией»).
// ─────────────────────────────────────────────────────────────────────────────

class WheelView(context: Context) : View(context) {

    private val camera = Matrix()
    private val node = RenderNode("wheel").apply {
        setUseCompositingLayer(true, Paint(Paint.FILTER_BITMAP_FLAG))
    }

    private var settings = WheelSettings()
    private var events: List<CalendarEvent> = emptyList()
    private var bands: List<EventBand> = emptyList()
    private var now: LocalDateTime = LocalDateTime.now()

    // Параметры последней записи сцены.
    private var sceneDirty = true
    private var recScale = 1f
    private var recFull = true
    private var recWinStart = 0.0
    private var recWinLen = 1.0
    private val recCam = Matrix()
    private val recCamInv = Matrix()
    private var nodeLeft = 0
    private var nodeTop = 0
    private var nodeSize = 0
    private var crispPending = false

    private val deltaM = Matrix()
    private val tmpM = Matrix()
    private val tmpPts = FloatArray(8)
    private val tmpVals = FloatArray(9)

    // ── Камера ────────────────────────────────────────────────────────────────

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
        val es = (cur * s).coerceIn(0.4f, 400.0f) / cur
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
    private var panVx = 0.0
    private var panVy = 0.0
    private var zoomVel = 0.0 // ln(scale)/s — инерция пинча
    private var lastRotR = 300f // радиус точки хвата при вращении (для px-порогов)
    private var lastPinchMs = 0L
    private var zoomFx = 0f // фокус зума для инерции
    private var zoomFy = 0f
    private var velTimeMs = 0L
    private var inertiaRunning = false
    private var lastFrameNanos = 0L

    // Затухание всех инерций и стартовый импульс (доля скорости броска).
    private val friction = 4.4
    private val launch = 0.52

    // Камера, сохранённая перед прошлым закрытием: применяется в onSizeChanged,
    // только если размер вью совпал (иначе матрица не имеет смысла).
    private var pendingCamera: FloatArray? = null
    private var pendingCamW = 0
    private var pendingCamH = 0

    fun cameraValues(): FloatArray {
        val v = FloatArray(9)
        camera.getValues(v)
        return v
    }

    fun setPendingCamera(values: FloatArray, w: Int, h: Int) {
        pendingCamera = values
        pendingCamW = w
        pendingCamH = h
    }

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
                val k = exp(-friction * dt)
                if (angVel != 0.0) {
                    rotateBy(angVel * dt, wheelCenterX(), wheelCenterY())
                    angVel *= k
                    if (abs(angVel) * lastRotR < 12.0) angVel = 0.0
                }
                if (panVx != 0.0 || panVy != 0.0) {
                    panBy((panVx * dt).toFloat(), (panVy * dt).toFloat())
                    panVx *= k; panVy *= k
                    if (hypot(panVx, panVy) < 20.0) { panVx = 0.0; panVy = 0.0 }
                }
                if (zoomVel != 0.0) {
                    val before = currentScale()
                    zoomBy(zoomFx, zoomFy, exp(zoomVel * dt).toFloat())
                    zoomVel *= k
                    // Упёрлись в предел зума или почти затухли — стоп.
                    if (currentScale() == before || abs(zoomVel) < 0.04) zoomVel = 0.0
                }
                if (angVel == 0.0 && panVx == 0.0 && panVy == 0.0 && zoomVel == 0.0) {
                    inertiaRunning = false
                    requestCrisp()
                    return
                }
            }
            Choreographer.getInstance().postFrameCallback(this)
        }
    }

    private fun stopInertia() {
        inertiaRunning = false
        angVel = 0.0
        panVx = 0.0; panVy = 0.0
        zoomVel = 0.0
    }

    private fun startInertia(upTimeMs: Long) {
        // Скорости протухают: палец подержали неподвижно — импульса нет.
        val stale = exp(-friction * ((upTimeMs - velTimeMs).coerceAtLeast(0) / 1000.0))
        angVel = (angVel * stale * launch).coerceIn(-9.0, 9.0)
        if (abs(angVel) * lastRotR <= 70.0) angVel = 0.0 // порог — экранные px/s
        panVx *= stale * launch; panVy *= stale * launch
        val panSpeed = hypot(panVx, panVy)
        if (panSpeed > 6000.0) { panVx *= 6000.0 / panSpeed; panVy *= 6000.0 / panSpeed }
        if (panSpeed <= 100.0) { panVx = 0.0; panVy = 0.0 }
        val zoomStale = exp(-friction * ((upTimeMs - lastPinchMs).coerceAtLeast(0) / 1000.0))
        zoomVel = (zoomVel * zoomStale * launch).coerceIn(-4.0, 4.0)
        if (abs(zoomVel) <= 0.25) zoomVel = 0.0
        if (angVel != 0.0 || panVx != 0.0 || panVy != 0.0 || zoomVel != 0.0) {
            inertiaRunning = true
            lastFrameNanos = System.nanoTime()
            Choreographer.getInstance().postFrameCallback(inertiaCallback)
        } else {
            requestCrisp()
        }
    }

    fun resetCamera() {
        stopInertia()
        camera.reset()
        sceneDirty = true
        invalidate()
    }

    /** В покое перезаписать слой точно под текущую камеру — идеальная чёткость. */
    private fun requestCrisp() {
        crispPending = true
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
                panVx = 0.0; panVy = 0.0
                zoomVel = 0.0
                velTimeMs = e.eventTime
            }

            MotionEvent.ACTION_MOVE -> {
                val t = e.eventTime
                val dt = ((t - velTimeMs) / 1000.0).coerceIn(0.008, 0.1)

                if (e.pointerCount >= 2) {
                    val (fx, fy) = focalOf(e)
                    val dx = fx - lastFocalX
                    val dy = fy - lastFocalY
                    panBy(dx, dy)
                    val span = spanOf(e)
                    if (lastSpan > 1f && span > 1f) {
                        zoomBy(fx, fy, span / lastSpan)
                        zoomVel = zoomVel * 0.5 + (Math.log((span / lastSpan).toDouble()) / dt) * 0.5
                        zoomFx = fx; zoomFy = fy
                        lastPinchMs = t
                    }
                    lastFocalX = fx; lastFocalY = fy
                    lastSpan = span
                    angVel = 0.0
                    panVx = panVx * 0.5 + (dx / dt) * 0.5
                    panVy = panVy * 0.5 + (dy / dt) * 0.5
                    velTimeMs = t
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
                    panVx = panVx * 0.5 + (dxAll / dt) * 0.5
                    panVy = panVy * 0.5 + (dyAll / dt) * 0.5
                    velTimeMs = t
                    return true
                }

                // Вращение точным угловым сдвигом (atan2), а не dTan/r —
                // при отдалении палец ходит по крутым дугам вокруг центра,
                // и линейное приближение заметно врёт.
                var rot = atan2((e.y - cyW).toDouble(), (e.x - cxW).toDouble()) -
                    atan2((prevY - cyW).toDouble(), (prevX - cxW).toDouble())
                if (rot > Math.PI) rot -= 2 * Math.PI
                if (rot < -Math.PI) rot += 2 * Math.PI
                angVel = angVel * 0.5 + (rot / dt) * 0.5
                lastRotR = r // радиус хвата — для px-порогов инерции
                panVx = 0.0; panVy = 0.0
                velTimeMs = t
                rotateBy(rot, cxW, cyW)
            }

            MotionEvent.ACTION_POINTER_UP -> {
                val (fx, fy) = focalOf(e, e.actionIndex)
                lastFocalX = fx; lastFocalY = fy
                lastSpan = spanOf(e, e.actionIndex)
            }

            MotionEvent.ACTION_UP -> {
                startInertia(e.eventTime)
            }

            MotionEvent.ACTION_CANCEL -> {
                stopInertia()
                requestCrisp()
            }
        }
        return true
    }

    // ── Сцена и LOD ───────────────────────────────────────────────────────────

    fun update(settings: WheelSettings, events: List<CalendarEvent>, now: LocalDateTime) {
        this.settings = settings
        this.events = events
        this.now = now
        val filtered = events.filter { settings.calendarFilter.allows(it) }
        this.bands = buildEventBands(filtered, now.year, nowFrac(), settings.largeEventsOnTop)
        setBackgroundColor(settings.theme.background)
        sceneDirty = true
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        pendingCamera?.let {
            if (w == pendingCamW && h == pendingCamH) camera.setValues(it)
            pendingCamera = null
        }
        sceneDirty = true
    }

    private fun nowFrac(): Double {
        val year = now.year
        val yearStart = LocalDateTime.of(year, 1, 1, 0, 0)
        val totalSec = Duration.between(yearStart, LocalDateTime.of(year + 1, 1, 1, 0, 0))
            .seconds.toDouble()
        return Duration.between(yearStart, now).seconds.toDouble() / totalSec
    }

    /**
     * Видимое окно в frac-пространстве: [start, len] или null = весь круг.
     * Экстремумы atan2 по выпуклому четырёхугольнику (экран в контент-
     * пространстве) достигаются в вершинах.
     */
    private fun computeWindow(): DoubleArray? {
        if (currentScale() <= 1.05f) return null
        if (!camera.invert(tmpM)) return null

        val w = width.toFloat(); val h = height.toFloat()
        tmpPts[0] = 0f; tmpPts[1] = 0f
        tmpPts[2] = w; tmpPts[3] = 0f
        tmpPts[4] = w; tmpPts[5] = h
        tmpPts[6] = 0f; tmpPts[7] = h
        tmpM.mapPoints(tmpPts)

        val cx = w / 2f; val cy = h / 2f

        var pos = false; var neg = false
        for (i in 0 until 4) {
            val x1 = tmpPts[i * 2]; val y1 = tmpPts[i * 2 + 1]
            val x2 = tmpPts[((i + 1) % 4) * 2]; val y2 = tmpPts[((i + 1) % 4) * 2 + 1]
            val cross = (x2 - x1) * (cy - y1) - (y2 - y1) * (cx - x1)
            if (cross > 0) pos = true else if (cross < 0) neg = true
        }
        if (!(pos && neg)) return null // центр круга на экране → видно всё

        val a0 = atan2((tmpPts[1] - cy).toDouble(), (tmpPts[0] - cx).toDouble())
        var aMin = a0; var aMax = a0
        for (i in 1 until 4) {
            var a = atan2((tmpPts[i * 2 + 1] - cy).toDouble(), (tmpPts[i * 2] - cx).toDouble())
            while (a - a0 > Math.PI) a -= 2 * Math.PI
            while (a - a0 < -Math.PI) a += 2 * Math.PI
            aMin = min(aMin, a); aMax = max(aMax, a)
        }

        val nf = nowFrac()
        val dir = if (settings.counterClockwise) 1.0 else -1.0
        val anchorA = if (settings.nowAtBottom) Math.PI / 2 else -Math.PI / 2
        val f1 = nf + dir * (anchorA - aMin) / (2 * Math.PI)
        val f2 = nf + dir * (anchorA - aMax) / (2 * Math.PI)
        var start = min(f1, f2)
        val len = abs(f1 - f2)
        if (len >= 0.999) return null
        start -= Math.floor(start)
        return doubleArrayOf(start, len)
    }

    /** Экран (через дельту) не выходит за квадрат слоя? */
    private fun screenInsideNode(): Boolean {
        if (nodeSize <= 0) return false
        if (!deltaM.invert(tmpM)) return false
        val w = width.toFloat(); val h = height.toFloat()
        tmpPts[0] = 0f; tmpPts[1] = 0f
        tmpPts[2] = w; tmpPts[3] = 0f
        tmpPts[4] = w; tmpPts[5] = h
        tmpPts[6] = 0f; tmpPts[7] = h
        tmpM.mapPoints(tmpPts)
        val m = 4f // допуск, px
        for (i in 0 until 4) {
            val x = tmpPts[i * 2]; val y = tmpPts[i * 2 + 1]
            if (x < nodeLeft + m || y < nodeTop + m ||
                x > nodeLeft + nodeSize - m || y > nodeTop + nodeSize - m
            ) return false
        }
        return true
    }

    private fun deltaNontrivial(): Boolean {
        deltaM.getValues(tmpVals)
        val sc = hypot(tmpVals[0], tmpVals[3])
        if (abs(sc - 1f) > 0.005f) return true
        if (abs(atan2(tmpVals[3].toDouble(), tmpVals[0].toDouble())) > 0.003) return true
        if (abs(tmpVals[2]) > 0.5f || abs(tmpVals[5]) > 0.5f) return true
        return false
    }

    private fun rerecordNeeded(win: DoubleArray?): Boolean {
        if (sceneDirty) return true
        val ratio = currentScale() / recScale
        if (ratio > 1.25f || ratio < 0.8f) return true
        if (!screenInsideNode()) return true
        if (!recFull) {
            if (win == null) return true
            var inside = false
            for (k in -1..1) {
                val s = win[0] + k
                if (s >= recWinStart - 1e-9 && s + win[1] <= recWinStart + recWinLen + 1e-9) {
                    inside = true; break
                }
            }
            if (!inside) return true
        }
        return false
    }

    private fun recordScene(win: DoubleArray?) {
        if (width <= 0 || height <= 0) return
        val scale = currentScale()

        val lod: Lod
        if (win == null) {
            lod = Lod(camScale = scale)
            recFull = true
        } else {
            val pad = win[1] * 0.35
            val start = win[0] - pad
            val len = win[1] + 2 * pad
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
        recCam.set(camera)
        recCam.invert(recCamInv)

        // Квадрат слоя с диагональ экрана (+12%) — вращение не открывает углов.
        nodeSize = ceil(hypot(width.toFloat(), height.toFloat()) * 1.12f).toInt()
        nodeLeft = (width - nodeSize) / 2
        nodeTop = (height - nodeSize) / 2
        node.setPosition(nodeLeft, nodeTop, nodeLeft + nodeSize, nodeTop + nodeSize)

        val c = node.beginRecording(nodeSize, nodeSize)
        try {
            c.translate(-nodeLeft.toFloat(), -nodeTop.toFloat())
            c.concat(camera) // камера вкатана в слой → на кадр остаётся дельта
            drawWheel(c, width.toFloat(), height.toFloat(), now, settings, bands, lod)
        } finally {
            node.endRecording()
        }
        sceneDirty = false
    }

    override fun onDraw(canvas: Canvas) {
        if (width <= 0 || height <= 0) return

        // Дельта: camera · recCamera⁻¹ (что накрутили после записи слоя).
        deltaM.setConcat(camera, recCamInv)

        val win = computeWindow()
        var rerecord = rerecordNeeded(win)
        if (crispPending && !rerecord && deltaNontrivial()) rerecord = true
        crispPending = false
        if (rerecord) {
            recordScene(win)
            deltaM.setConcat(camera, recCamInv) // после записи дельта = identity
        }

        if (canvas.isHardwareAccelerated && node.hasDisplayList()) {
            canvas.save()
            canvas.concat(deltaM)
            canvas.drawRenderNode(node)
            canvas.restore()
        } else {
            // Софт-фолбэк (скриншоты и т.п.).
            canvas.save()
            canvas.concat(camera)
            drawWheel(canvas, width.toFloat(), height.toFloat(), now, settings, bands)
            canvas.restore()
        }
    }
}
