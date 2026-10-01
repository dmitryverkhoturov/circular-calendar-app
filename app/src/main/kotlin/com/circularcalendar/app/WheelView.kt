package com.circularcalendar.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RenderNode
import android.view.Choreographer
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import java.time.LocalDateTime
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

// ─────────────────────────────────────────────────────────────────────────────
// WheelView — сцена + камера + жесты + физика.
//
// Камера — подобие в DOUBLE: зум s, поворот th, экранное положение центра
// круга (ox, oy). Точка контента p → экран: o + s·R(th)·(p − C). Float-матрица
// Android на зуме 10⁵ дрожала бы на пиксели, поэтому сцена пишется
// относительно локального начала (контент под центром экрана), а на кадр
// дельта-трансформ считается в double и только потом кладётся в Matrix.
//
// Рендер: RenderNode-растровый слой с вкатанной камерой (см. [[performance]]),
// перезапись по зуму ×1.25, выходу экрана за квадрат слоя и в покое.
//
// Жесты (как в картах):
//  • один палец: осевой замок (вращение по касательной / панорама по радиусу),
//    центр = панорама;
//  • два пальца: точки под пальцами прикованы — пан + зум + вращение разом;
//  • двойной тап — приближение ×2; тройной — сброс вида;
//  • тап двумя пальцами — отдаление ×2;
//  • двойной тап с удержанием и движением вверх/вниз — зум одним пальцем.
// Физика: инерция с затуханием, разгон повторными бросками, пружинный отскок
// от пределов зума и от края (календарь нельзя потерять). Все константы —
// в MotionSettings, крутятся в настройках.
// ─────────────────────────────────────────────────────────────────────────────

class WheelView(context: Context) : View(context) {

    // ── Камера ────────────────────────────────────────────────────────────────
    private var camS = 1.0
    private var camTh = 0.0
    private var camOx = 0.0
    private var camOy = 0.0
    private var camInit = false

    var motion = MotionSettings()

    private val minS = 0.025   // видна вся Солнечная система (до Нептуна)
    private val maxS = 200_000.0 // секунды и Земля во весь экран
    private val lnMin = ln(minS)
    private val lnMax = ln(maxS)

    private val node = RenderNode("wheel").apply {
        setUseCompositingLayer(true, Paint(Paint.FILTER_BITMAP_FLAG))
    }

    private var settings = WheelSettings()
    private var bands: List<EventBand> = emptyList()
    private var now: LocalDateTime = LocalDateTime.now()
    private var tm = TimeMap(now, true, true, false)

    // Параметры последней записи сцены.
    private var sceneDirty = true
    private var recS = 1.0
    private var recTh = 0.0
    private var recOx = 0.0
    private var recOy = 0.0
    private var nodeLeft = 0
    private var nodeTop = 0
    private var nodeSize = 0
    private var crispPending = false

    private val deltaM = Matrix()
    private val tmpVals = FloatArray(9)
    private val density = resources.displayMetrics.density

    private fun geom() = WheelGeom(width.toFloat(), height.toFloat())

    // ── Операции камеры ──────────────────────────────────────────────────────

    private fun panBy(dx: Double, dy: Double) {
        camOx += dx; camOy += dy
        invalidate()
    }

    private fun rotateAbout(rad: Double, px: Double, py: Double) {
        val c = cos(rad); val s = sin(rad)
        val dx = camOx - px; val dy = camOy - py
        camOx = px + c * dx - s * dy
        camOy = py + s * dx + c * dy
        camTh += rad
        invalidate()
    }

    private fun zoomAbout(f: Double, px: Double, py: Double) {
        camOx = px + f * (camOx - px)
        camOy = py + f * (camOy - py)
        camS *= f
        invalidate()
    }

    /**
     * Насколько круг ушёл за пределы: вектор коррекции в [out] (куда сдвинуть
     * центр, чтобы край круга снова зашёл на экран с отступом), длина — return.
     */
    private val corr = DoubleArray(2)
    private fun boundsCorrection(out: DoubleArray): Double {
        out[0] = 0.0; out[1] = 0.0
        if (width <= 0) return 0.0
        val m = 0.15 * min(width, height)
        val qx = camOx.coerceIn(m, width - m)
        val qy = camOy.coerceIn(m, height - m)
        val dx = qx - camOx; val dy = qy - camOy
        val d = hypot(dx, dy)
        val r = geom().keepR * camS
        if (d <= r) return 0.0
        val k = (d - r) / d
        out[0] = dx * k; out[1] = dy * k
        return d - r
    }

    /** Пан с резиновым сопротивлением за краем. */
    private fun panRubber(dx: Double, dy: Double) {
        val over = boundsCorrection(corr)
        var ddx = dx; var ddy = dy
        if (over > 0) {
            val nx = -corr[0] / over; val ny = -corr[1] / over // наружу
            val comp = dx * nx + dy * ny
            if (comp > 0) {
                val l = 0.3 * min(width, height)
                val f = 1.0 / ((1 + over / l) * (1 + over / l))
                ddx -= nx * comp * (1 - f); ddy -= ny * comp * (1 - f)
            }
        }
        panBy(ddx, ddy)
    }

    /** Зум с резиновым сопротивлением за пределами [minS, maxS]. Возвращает ln применённого. */
    private fun zoomRubber(f: Double, px: Double, py: Double): Double {
        var d = ln(f)
        val cur = ln(camS)
        val l = 0.3
        if (d > 0 && cur + d > lnMax) {
            val inside = max(0.0, lnMax - cur)
            val over0 = max(0.0, cur - lnMax)
            d = inside + (d - inside) / ((1 + over0 / l) * (1 + over0 / l))
        } else if (d < 0 && cur + d < lnMin) {
            val inside = min(0.0, lnMin - cur)
            val over0 = max(0.0, lnMin - cur)
            d = inside + (d - inside) / ((1 + over0 / l) * (1 + over0 / l))
        }
        zoomAbout(exp(d), px, py)
        return d
    }

    // ── Сохранение камеры ────────────────────────────────────────────────────

    private var pendingCamera: DoubleArray? = null
    private var pendingCamW = 0
    private var pendingCamH = 0

    fun cameraState(): String = "v2,$width,$height,$camS,$camTh,$camOx,$camOy"

    fun restoreCameraState(str: String) {
        try {
            val p = str.split(',')
            if (p.size != 7 || p[0] != "v2") return
            pendingCamW = p[1].toInt(); pendingCamH = p[2].toInt()
            pendingCamera = doubleArrayOf(p[3].toDouble(), p[4].toDouble(), p[5].toDouble(), p[6].toDouble())
        } catch (_: Exception) {
        }
    }

    /** Мгновенно вернуть камеру в допустимые пределы (после восстановления). */
    private fun snapIntoBounds() {
        if (!camS.isFinite() || camS <= 0) { camS = 1.0; camTh = 0.0; camOx = width / 2.0; camOy = height / 2.0 }
        val cs = camS.coerceIn(minS, maxS)
        if (cs != camS) zoomAbout(cs / camS, width / 2.0, height / 2.0)
        if (boundsCorrection(corr) > 0) panBy(corr[0], corr[1])
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        val pc = pendingCamera
        if (pc != null && w == pendingCamW && h == pendingCamH) {
            camS = pc[0]; camTh = pc[1]; camOx = pc[2]; camOy = pc[3]
            camInit = true
        } else if (!camInit) {
            camOx = w / 2.0; camOy = h / 2.0
            camInit = true
        } else {
            camOx += (w - oldw) / 2.0; camOy += (h - oldh) / 2.0
        }
        pendingCamera = null
        snapIntoBounds()
        sceneDirty = true
    }

    // ── Физика ───────────────────────────────────────────────────────────────

    private var vRot = 0.0
    private var rotPivotWheel = true
    private var rotPx = 0.0
    private var rotPy = 0.0
    private var rotR = 300.0 // радиус хвата: пороги вращения — в экранных px/s
    private var vPanX = 0.0
    private var vPanY = 0.0
    private var vZoom = 0.0 // ln(s)/с
    private var zoomFx = 0.0
    private var zoomFy = 0.0
    private var zoomTarget = Double.NaN

    private var resetActive = false
    private var rsV = DoubleArray(4) // скорости пружин сброса: lnS, th, ox, oy
    private var resetThTarget = 0.0

    private var running = false
    private var lastFrameNanos = 0L

    private val frameCallback = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            if (!running) return
            val dt = ((frameTimeNanos - lastFrameNanos) / 1e9).coerceIn(0.0, 0.032)
            lastFrameNanos = frameTimeNanos
            val active = step(dt)
            if (active) {
                Choreographer.getInstance().postFrameCallback(this)
            } else {
                running = false
                requestCrisp()
            }
        }
    }

    private fun startLoop() {
        if (running) return
        running = true
        lastFrameNanos = System.nanoTime()
        Choreographer.getInstance().postFrameCallback(frameCallback)
    }

    private fun stopAll() {
        running = false
        vRot = 0.0; vPanX = 0.0; vPanY = 0.0; vZoom = 0.0
        zoomTarget = Double.NaN
        resetActive = false
    }

    private fun spring(x: Double, target: Double, v: Double, dt: Double, zeta: Double): Double {
        val w = motion.spring
        return v + (-w * w * (x - target) - 2 * zeta * w * v) * dt
    }

    /** Один шаг физики. true = ещё есть движение. */
    private fun step(dt: Double): Boolean {
        if (dt <= 0) return true
        val fr = motion.friction
        val z = motion.damping
        var active = false

        if (resetActive) {
            val g = geom()
            val x = doubleArrayOf(ln(camS), camTh, camOx, camOy)
            val t = doubleArrayOf(0.0, resetThTarget, g.cx, g.cy)
            var done = true
            for (i in 0..3) {
                rsV[i] = spring(x[i], t[i], rsV[i], dt, max(z, 0.9))
                x[i] += rsV[i] * dt
                val tol = if (i >= 2) 0.3 else 1e-3
                if (abs(x[i] - t[i]) > tol || abs(rsV[i]) > tol * 4) done = false
            }
            if (done) {
                camS = 1.0; camTh = t[1]; camOx = t[2]; camOy = t[3]
                resetActive = false
            } else {
                camS = exp(x[0]); camTh = x[1]; camOx = x[2]; camOy = x[3]
            }
            invalidate()
            return resetActive
        }

        // Вращение.
        if (vRot != 0.0) {
            val px = if (rotPivotWheel) camOx else rotPx
            val py = if (rotPivotWheel) camOy else rotPy
            rotateAbout(vRot * dt, px, py)
            vRot *= exp(-fr * dt)
            if (abs(vRot) * rotR < 6.0) vRot = 0.0 else active = true
        }

        // Зум: цель (анимация/возврат к пределу) или свободная инерция.
        val lnS = ln(camS)
        if (zoomTarget.isNaN() && (lnS > lnMax + 1e-6 || lnS < lnMin - 1e-6)) {
            zoomTarget = lnS.coerceIn(lnMin, lnMax)
        }
        if (!zoomTarget.isNaN()) {
            vZoom = spring(lnS, zoomTarget, vZoom, dt, z)
            zoomAbout(exp(vZoom * dt), zoomFx, zoomFy)
            val after = ln(camS)
            if (abs(after - zoomTarget) < 2e-4 && abs(vZoom) < 2e-3) {
                zoomAbout(exp(zoomTarget - after), zoomFx, zoomFy)
                vZoom = 0.0
                zoomTarget = Double.NaN
            } else active = true
        } else if (vZoom != 0.0) {
            zoomAbout(exp(vZoom * dt), zoomFx, zoomFy)
            vZoom *= exp(-fr * dt)
            if (abs(vZoom) < 0.02) vZoom = 0.0 else active = true
        }

        // Панорама: за краем — пружина к границе (как отскок в iOS).
        val over = boundsCorrection(corr)
        if (over > 0.3) {
            val w = motion.spring
            vPanX += (w * w * corr[0] - 2 * w * vPanX) * dt
            vPanY += (w * w * corr[1] - 2 * w * vPanY) * dt
            panBy(vPanX * dt, vPanY * dt)
            active = true
        } else if (vPanX != 0.0 || vPanY != 0.0) {
            panBy(vPanX * dt, vPanY * dt)
            val k = exp(-fr * dt)
            vPanX *= k; vPanY *= k
            if (hypot(vPanX, vPanY) < 12.0) { vPanX = 0.0; vPanY = 0.0 } else active = true
        }
        return active
    }

    /** Анимированный зум ×exp(dLn) вокруг точки (двойной тап, тап двумя пальцами). */
    private fun animateZoom(dLn: Double, fx: Double, fy: Double) {
        stopAll()
        zoomTarget = (ln(camS) + dLn).coerceIn(lnMin, lnMax)
        zoomFx = fx; zoomFy = fy
        vZoom = 0.0
        startLoop()
    }

    fun resetCameraAnimated() {
        stopAll()
        resetActive = true
        rsV = DoubleArray(4)
        resetThTarget = Math.round(camTh / (2 * PI)) * 2 * PI
        startLoop()
    }

    private fun requestCrisp() {
        crispPending = true
        invalidate()
    }

    // ── Скорость броска: кольцевой буфер накопленных величин ────────────────

    private val nSamples = 24
    private val sT = LongArray(nSamples)
    private val sV = Array(nSamples) { DoubleArray(4) } // rot, x, y, lnZoom
    private var sCount = 0
    private var sHead = 0
    private val cum = DoubleArray(4)

    private fun resetSamples(t: Long) {
        sCount = 0
        pushSample(t)
    }

    private fun pushSample(t: Long) {
        sT[sHead] = t
        System.arraycopy(cum, 0, sV[sHead], 0, 4)
        sHead = (sHead + 1) % nSamples
        if (sCount < nSamples) sCount++
    }

    /** Скорости на момент [upT] по окну ~90 мс (палец замер — скорость гаснет). */
    private fun velocity(upT: Long): DoubleArray {
        val out = DoubleArray(4)
        if (sCount < 2) return out
        var oldest = -1
        for (i in 0 until sCount) {
            val idx = Math.floorMod(sHead - 1 - i, nSamples)
            if (upT - sT[idx] > 90) break
            oldest = idx
        }
        if (oldest < 0) return out
        val newest = Math.floorMod(sHead - 1, nSamples)
        if (oldest == newest) return out
        val dt = max((upT - sT[oldest]) / 1000.0, 0.016)
        for (k in 0..3) out[k] = (sV[newest][k] - sV[oldest][k]) / dt
        return out
    }

    // ── Жесты ─────────────────────────────────────────────────────────────────

    private val vc = ViewConfiguration.get(context)
    private val touchSlop = vc.scaledTouchSlop.toDouble()
    private val dtSlop = vc.scaledDoubleTapSlop.toDouble()
    private val dtTimeout = ViewConfiguration.getDoubleTapTimeout().toLong()
    private val tapMaxMs = 280L
    private val lockThreshold = 10.0 * density
    private val lockHysteresis = 1.4
    // Зум одним пальцем: 180dp вниз = ×2.
    private val qsK = ln(2.0) / (180.0 * density)

    private companion object {
        const val UNDECIDED = 0
        const val ROTATE = 1
        const val PAN = 2
        const val CENTER_PENDING = 3
        const val QS_PENDING = 4
        const val QS = 5
        const val TRIPLE_PENDING = 6
        const val MULTI = 7
    }

    private var mode = UNDECIDED
    private var accumTan = 0.0
    private var accumRad = 0.0
    private var lastX = 0.0
    private var lastY = 0.0

    private var downTime = 0L
    private var downX = 0.0
    private var downY = 0.0
    private var maxMove = 0.0
    private var multiSeen = false
    private var caught = false
    private var tapCount = 0
    private var lastTapUpTime = 0L
    private var lastTapX = 0.0
    private var lastTapY = 0.0

    // Перенос инерции для разгона повторными бросками.
    private var carriedRot = 0.0
    private var carriedPivotWheel = true
    private var carriedPanX = 0.0
    private var carriedPanY = 0.0

    // Тап двумя пальцами.
    private var twoTap = false
    private var twoDownTime = 0L
    private var twoMidX = 0.0
    private var twoMidY = 0.0
    private var twoSpan = 0.0

    // Пара пальцев.
    private var pid1 = -1
    private var pid2 = -1
    private var p1x = 0.0; private var p1y = 0.0
    private var p2x = 0.0; private var p2y = 0.0

    // Скорость пинча на момент отпускания первого пальца.
    private var pinchVel: DoubleArray? = null
    private var pinchUpTime = 0L

    private fun initPair(e: MotionEvent, exclude: Int) {
        pid1 = -1; pid2 = -1
        for (i in 0 until e.pointerCount) {
            if (i == exclude) continue
            if (pid1 < 0) { pid1 = e.getPointerId(i); p1x = e.getX(i).toDouble(); p1y = e.getY(i).toDouble() }
            else if (pid2 < 0) { pid2 = e.getPointerId(i); p2x = e.getX(i).toDouble(); p2y = e.getY(i).toDouble() }
        }
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        val t = e.eventTime
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                // «Поймали» свободную инерцию (не анимацию зума/сброса) — такое
                // касание не тап, но может разогнать следующий бросок.
                val wasMoving = running && zoomTarget.isNaN() && !resetActive &&
                    (vRot != 0.0 || vPanX != 0.0 || vPanY != 0.0 || vZoom != 0.0)
                carriedRot = if (wasMoving) vRot else 0.0
                carriedPivotWheel = rotPivotWheel
                carriedPanX = if (wasMoving) vPanX else 0.0
                carriedPanY = if (wasMoving) vPanY else 0.0
                stopAll()
                caught = wasMoving
                val x = e.x.toDouble(); val y = e.y.toDouble()
                downTime = t; downX = x; downY = y
                maxMove = 0.0; multiSeen = false; twoTap = false; pinchVel = null
                lastX = x; lastY = y
                accumTan = 0.0; accumRad = 0.0
                val seq = if (!caught && t - lastTapUpTime <= dtTimeout &&
                    hypot(x - lastTapX, y - lastTapY) < dtSlop) tapCount + 1 else 1
                if (seq == 1) tapCount = 0
                val g = geom()
                val inCenter = hypot(x - camOx, y - camOy) < g.innerR * camS
                mode = when {
                    seq == 2 -> QS_PENDING
                    seq >= 3 -> TRIPLE_PENDING
                    inCenter -> CENTER_PENDING
                    else -> UNDECIDED
                }
                cum.fill(0.0)
                resetSamples(t)
            }

            MotionEvent.ACTION_POINTER_DOWN -> {
                multiSeen = true
                if (e.pointerCount == 2 && t - downTime < 250 && maxMove < touchSlop && !caught) {
                    twoTap = true
                    twoDownTime = t
                    twoMidX = (e.getX(0) + e.getX(1)) / 2.0
                    twoMidY = (e.getY(0) + e.getY(1)) / 2.0
                    twoSpan = hypot((e.getX(0) - e.getX(1)).toDouble(), (e.getY(0) - e.getY(1)).toDouble())
                } else {
                    twoTap = false
                }
                mode = MULTI
                initPair(e, -1)
                pinchVel = null
                resetSamples(t)
            }

            MotionEvent.ACTION_MOVE -> {
                if (mode == MULTI && e.pointerCount >= 2) {
                    val i1 = e.findPointerIndex(pid1)
                    val i2 = e.findPointerIndex(pid2)
                    if (i1 < 0 || i2 < 0) { initPair(e, -1); return true }
                    val q1x = e.getX(i1).toDouble(); val q1y = e.getY(i1).toDouble()
                    val q2x = e.getX(i2).toDouble(); val q2y = e.getY(i2).toDouble()
                    val mpx = (p1x + p2x) / 2; val mpy = (p1y + p2y) / 2
                    val mqx = (q1x + q2x) / 2; val mqy = (q1y + q2y) / 2
                    val spanP = hypot(p2x - p1x, p2y - p1y)
                    val spanQ = hypot(q2x - q1x, q2y - q1y)
                    if (twoTap && (hypot(mqx - twoMidX, mqy - twoMidY) > touchSlop ||
                            abs(spanQ - twoSpan) > touchSlop)) twoTap = false

                    // Точки под пальцами прикованы: пан средней точки, затем
                    // поворот и зум вокруг неё.
                    val dx = mqx - mpx; val dy = mqy - mpy
                    panRubber(dx, dy)
                    var dTh = atan2(q2y - q1y, q2x - q1x) - atan2(p2y - p1y, p2x - p1x)
                    if (dTh > PI) dTh -= 2 * PI
                    if (dTh < -PI) dTh += 2 * PI
                    rotateAbout(dTh, mqx, mqy)
                    var dLn = 0.0
                    if (spanP > 1 && spanQ > 1) dLn = zoomRubber(spanQ / spanP, mqx, mqy)

                    cum[0] += dTh; cum[1] += dx; cum[2] += dy; cum[3] += dLn
                    pushSample(t)
                    rotPivotWheel = false; rotPx = mqx; rotPy = mqy; rotR = max(spanQ / 2, 40.0)
                    zoomFx = mqx; zoomFy = mqy
                    p1x = q1x; p1y = q1y; p2x = q2x; p2y = q2y
                    return true
                }

                val x = e.x.toDouble(); val y = e.y.toDouble()
                maxMove = max(maxMove, hypot(x - downX, y - downY))

                when (mode) {
                    QS_PENDING -> {
                        if (maxMove < touchSlop) return true
                        mode = QS
                        lastX = x; lastY = y
                        return true
                    }
                    QS -> {
                        val dLn = zoomRubber(exp((y - lastY) * qsK), downX, downY)
                        cum[3] += dLn
                        pushSample(t)
                        zoomFx = downX; zoomFy = downY
                        lastX = x; lastY = y
                        return true
                    }
                    TRIPLE_PENDING -> {
                        if (maxMove < touchSlop) return true
                        mode = UNDECIDED
                    }
                    CENTER_PENDING -> {
                        if (maxMove < touchSlop) return true
                        mode = PAN
                    }
                }

                val dx = x - lastX; val dy = y - lastY
                if (dx == 0.0 && dy == 0.0) return true
                val prevX = lastX; val prevY = lastY
                lastX = x; lastY = y

                if (mode == UNDECIDED) {
                    val rvx = prevX - camOx; val rvy = prevY - camOy
                    val r = max(hypot(rvx, rvy), 1.0)
                    val radUx = rvx / r; val radUy = rvy / r
                    accumRad += dx * radUx + dy * radUy
                    accumTan += -dx * radUy + dy * radUx
                    val at = abs(accumTan); val ar = abs(accumRad)
                    mode = when {
                        at >= lockThreshold && at >= ar * lockHysteresis -> ROTATE
                        ar >= lockThreshold && ar >= at * lockHysteresis -> PAN
                        else -> return true
                    }
                }

                if (mode == PAN) {
                    panRubber(dx, dy)
                    cum[1] += dx; cum[2] += dy
                    pushSample(t)
                    return true
                }

                // ROTATE: точный угловой сдвиг вокруг центра круга.
                var rot = atan2(y - camOy, x - camOx) - atan2(prevY - camOy, prevX - camOx)
                if (rot > PI) rot -= 2 * PI
                if (rot < -PI) rot += 2 * PI
                rotateAbout(rot, camOx, camOy)
                cum[0] += rot
                pushSample(t)
                rotPivotWheel = true
                rotR = max(hypot(prevX - camOx, prevY - camOy), 1.0)
            }

            MotionEvent.ACTION_POINTER_UP -> {
                if (e.pointerCount - 1 >= 2) {
                    initPair(e, e.actionIndex)
                } else {
                    pinchVel = velocity(t)
                    pinchUpTime = t
                    val rest = if (e.actionIndex == 0) 1 else 0
                    lastX = e.getX(rest).toDouble(); lastY = e.getY(rest).toDouble()
                    mode = PAN
                    cum.fill(0.0)
                    resetSamples(t)
                }
            }

            MotionEvent.ACTION_UP -> {
                val x = e.x.toDouble(); val y = e.y.toDouble()
                val isTap = !multiSeen && !caught && maxMove < touchSlop && t - downTime < tapMaxMs

                if (twoTap && t - twoDownTime < 350) {
                    tapCount = 0; lastTapUpTime = 0
                    animateZoom(-ln(2.0), twoMidX, twoMidY)
                    return true
                }
                when {
                    mode == QS_PENDING && isTap -> {
                        tapCount = 2; lastTapUpTime = t; lastTapX = x; lastTapY = y
                        animateZoom(ln(2.0), downX, downY)
                        return true
                    }
                    mode == TRIPLE_PENDING && isTap -> {
                        tapCount = 0; lastTapUpTime = 0
                        resetCameraAnimated()
                        return true
                    }
                    isTap -> {
                        tapCount = 1; lastTapUpTime = t; lastTapX = x; lastTapY = y
                    }
                    else -> tapCount = 0
                }
                fling(t)
            }

            MotionEvent.ACTION_CANCEL -> {
                stopAll()
                startLoop() // вернуться в пределы, если вышли
            }
        }
        return true
    }

    private fun fling(t: Long) {
        // Отпустили оба пальца пинча почти разом — берём скорость пинча.
        val pv = pinchVel
        val v = if (pv != null && t - pinchUpTime < 60) pv else velocity(t)
        val launch = motion.launch
        vRot = v[0] * launch
        vPanX = v[1] * launch
        vPanY = v[2] * launch
        vZoom = v[3] * launch

        // Разгон: быстрый повторный бросок в ту же сторону добавляет инерцию.
        if (caught && t - downTime < 300) {
            val b = motion.boost
            if (carriedRot * vRot > 0 && carriedPivotWheel == rotPivotWheel) vRot += carriedRot * b
            if (carriedPanX * vPanX + carriedPanY * vPanY > 0) {
                vPanX += carriedPanX * b; vPanY += carriedPanY * b
            }
        }

        val rotSpeed = abs(vRot) * rotR
        if (rotSpeed > 30000) vRot *= 30000 / rotSpeed
        if (abs(vRot) * rotR < 40) vRot = 0.0
        val ps = hypot(vPanX, vPanY)
        if (ps > 30000) { vPanX *= 30000 / ps; vPanY *= 30000 / ps }
        if (ps < 60) { vPanX = 0.0; vPanY = 0.0 }
        vZoom = vZoom.coerceIn(-8.0, 8.0)
        if (abs(vZoom) < 0.2) vZoom = 0.0
        startLoop() // в т.ч. возврат в пределы, если вышли
    }

    // ── Сцена ─────────────────────────────────────────────────────────────────

    fun update(settings: WheelSettings, events: List<CalendarEvent>, now: LocalDateTime) {
        this.settings = settings
        this.now = now
        tm = TimeMap(now, settings.counterClockwise, settings.nowAtBottom, settings.keplerOrbit)
        val filtered = events.filter { settings.calendarFilter.allows(it) }
        this.bands = buildEventBands(filtered, now.year, tm.nowFrac, settings.largeEventsOnTop)
        setBackgroundColor(settings.theme.background)
        sceneDirty = true
        invalidate()
    }

    /** Сдвинуть «сейчас» без пересборки событий (частый тик на глубоком зуме). */
    fun setNow(now: LocalDateTime) {
        this.now = now
        tm = TimeMap(now, settings.counterClockwise, settings.nowAtBottom, settings.keplerOrbit)
        sceneDirty = true
        invalidate()
    }

    /** На масштабе минут/секунд «сейчас» нужно двигать каждую секунду. */
    fun needsFastTick(): Boolean {
        if (width <= 0) return false
        val pxPerSec = 2 * PI * geom().outerR * camS / tm.totalSec
        return pxPerSec * 60 > 3
    }

    /** Что видно в экранном прямоугольнике — для LOD записи сцены. */
    private fun computeLod(l: Double, t: Double, r: Double, b: Double, originX: Double, originY: Double): Lod {
        val s = camS
        val qx = camOx.coerceIn(l, r); val qy = camOy.coerceIn(t, b)
        val inside = qx == camOx && qy == camOy
        val rMin = if (inside) 0.0 else hypot(qx - camOx, qy - camOy) / s
        var rMax = 0.0
        val cxs = doubleArrayOf(l, r, r, l)
        val cys = doubleArrayOf(t, t, b, b)
        for (i in 0..3) rMax = max(rMax, hypot(cxs[i] - camOx, cys[i] - camOy))
        rMax /= s
        val visR = hypot(r - l, b - t) / 2 / s
        val rotDeg = (camTh * 180 / PI).toFloat()
        if (inside) {
            return Lod(s, originX, originY, rotDeg, full = true, rMin = 0.0, rMax = rMax, visR = visR, density = density)
        }
        val a0 = atan2(cys[0] - camOy, cxs[0] - camOx)
        var aMin = a0; var aMax = a0
        for (i in 1..3) {
            var a = atan2(cys[i] - camOy, cxs[i] - camOx)
            while (a - a0 > PI) a -= 2 * PI
            while (a - a0 < -PI) a += 2 * PI
            aMin = min(aMin, a); aMax = max(aMax, a)
        }
        val pad = (aMax - aMin) * 0.02 + 1e-9
        return Lod(
            s, originX, originY, rotDeg, full = false,
            aLo = aMin - camTh - pad, aHi = aMax - camTh + pad,
            rMin = rMin, rMax = rMax, visR = visR, density = density,
        )
    }

    /** Точка контента под экранной точкой (double). */
    private fun contentX(qx: Double, qy: Double): Double {
        val dx = qx - camOx; val dy = qy - camOy
        return width / 2.0 + (cos(camTh) * dx + sin(camTh) * dy) / camS
    }

    private fun contentY(qx: Double, qy: Double): Double {
        val dx = qx - camOx; val dy = qy - camOy
        return height / 2.0 + (-sin(camTh) * dx + cos(camTh) * dy) / camS
    }

    /** Дельта: экран на момент записи → экран сейчас. */
    private fun computeDelta() {
        val k = camS / recS
        val d = camTh - recTh
        val a = k * cos(d); val bb = k * sin(d)
        val tx = camOx - (a * recOx - bb * recOy)
        val ty = camOy - (bb * recOx + a * recOy)
        tmpVals[0] = a.toFloat(); tmpVals[1] = (-bb).toFloat(); tmpVals[2] = tx.toFloat()
        tmpVals[3] = bb.toFloat(); tmpVals[4] = a.toFloat(); tmpVals[5] = ty.toFloat()
        tmpVals[6] = 0f; tmpVals[7] = 0f; tmpVals[8] = 1f
        deltaM.setValues(tmpVals)
    }

    /** Экран (через обратную дельту) не выходит за квадрат слоя? */
    private fun screenInsideNode(): Boolean {
        if (nodeSize <= 0) return false
        val k = camS / recS
        val d = camTh - recTh
        val c = cos(-d) / k; val sn = sin(-d) / k
        val m = 4.0
        val xs = doubleArrayOf(0.0, width.toDouble(), width.toDouble(), 0.0)
        val ys = doubleArrayOf(0.0, 0.0, height.toDouble(), height.toDouble())
        for (i in 0..3) {
            val dx = xs[i] - camOx; val dy = ys[i] - camOy
            val x = recOx + c * dx - sn * dy
            val y = recOy + sn * dx + c * dy
            if (x < nodeLeft + m || y < nodeTop + m ||
                x > nodeLeft + nodeSize - m || y > nodeTop + nodeSize - m
            ) return false
        }
        return true
    }

    private fun deltaNontrivial(): Boolean {
        if (abs(camS / recS - 1) > 0.005) return true
        if (abs(camTh - recTh) > 0.003) return true
        computeDelta()
        deltaM.getValues(tmpVals)
        return abs(tmpVals[2]) > 0.5f || abs(tmpVals[5]) > 0.5f
    }

    private fun rerecordNeeded(): Boolean {
        if (sceneDirty) return true
        val ratio = camS / recS
        if (ratio > 1.25 || ratio < 0.8) return true
        return !screenInsideNode()
    }

    /** Пишет сцену в [c]: локальное начало = контент под центром экрана. */
    private fun drawSceneInto(c: Canvas, l: Double, t: Double, r: Double, b: Double) {
        val w = width.toDouble(); val h = height.toDouble()
        val ox = contentX(w / 2, h / 2)
        val oy = contentY(w / 2, h / 2)
        val lod = computeLod(l, t, r, b, ox, oy)
        c.translate((w / 2).toFloat(), (h / 2).toFloat())
        c.rotate((camTh * 180 / PI).toFloat())
        c.scale(camS.toFloat(), camS.toFloat())
        drawWheel(c, width.toFloat(), height.toFloat(), tm, settings, bands, lod)
    }

    private fun recordScene() {
        if (width <= 0 || height <= 0) return
        // Квадрат слоя с диагональ экрана (+12%) — вращение не открывает углов.
        nodeSize = ceil(hypot(width.toDouble(), height.toDouble()) * 1.12).toInt()
        nodeLeft = (width - nodeSize) / 2
        nodeTop = (height - nodeSize) / 2
        node.setPosition(nodeLeft, nodeTop, nodeLeft + nodeSize, nodeTop + nodeSize)
        val c = node.beginRecording(nodeSize, nodeSize)
        try {
            c.translate(-nodeLeft.toFloat(), -nodeTop.toFloat())
            drawSceneInto(
                c, nodeLeft.toDouble(), nodeTop.toDouble(),
                (nodeLeft + nodeSize).toDouble(), (nodeTop + nodeSize).toDouble(),
            )
        } finally {
            node.endRecording()
        }
        recS = camS; recTh = camTh; recOx = camOx; recOy = camOy
        sceneDirty = false
    }

    override fun onDraw(canvas: Canvas) {
        if (width <= 0 || height <= 0) return

        if (!canvas.isHardwareAccelerated) {
            canvas.save()
            drawSceneInto(canvas, 0.0, 0.0, width.toDouble(), height.toDouble())
            canvas.restore()
            return
        }

        var rerecord = rerecordNeeded()
        if (crispPending && !rerecord && deltaNontrivial()) rerecord = true
        crispPending = false
        if (rerecord) recordScene()

        computeDelta()
        if (node.hasDisplayList()) {
            canvas.save()
            canvas.concat(deltaM)
            canvas.drawRenderNode(node)
            canvas.restore()
        }
    }

    override fun onDetachedFromWindow() {
        running = false
        super.onDetachedFromWindow()
    }
}
