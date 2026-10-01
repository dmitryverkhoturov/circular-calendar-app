package com.circularcalendar.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.BlendMode
import android.graphics.Canvas
import android.graphics.RadialGradient
import android.graphics.Shader
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Picture
import android.graphics.RenderNode
import android.os.Handler
import android.os.HandlerThread
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
    private val maxS = 1_000_000.0 // крупные секунды, Земля во весь экран
    private val lnMin = ln(minS)
    private val lnMax = ln(maxS)

    // Плитки (см. Tiles.kt): сцена кэшируется кусками на уровнях зума.
    private val tiles = TileEngine({ invalidate() }, { r -> post(r) })
    private var sceneGen = 0
    private var flipTh = 0.0 // поворот, по которому решён переворот подписей

    // Скорость камеры (экранные px/с, рад/с, ln зума/с) — для записи с упреждением.
    private var cvX = 0.0; private var cvY = 0.0; private var cvTh = 0.0; private var cvLs = 0.0
    private var cvPrevNs = 0L
    private var cvPS = 1.0; private var cvPTh = 0.0; private var cvPOx = 0.0; private var cvPOy = 0.0

    private fun trackVelocity(nowNs: Long) {
        val dt = (nowNs - cvPrevNs) / 1e9
        if (cvPrevNs == 0L || dt > 0.12 || dt <= 0) {
            cvX = 0.0; cvY = 0.0; cvTh = 0.0; cvLs = 0.0
        } else {
            val k = 0.5
            cvX += k * ((camOx - cvPOx) / dt - cvX)
            cvY += k * ((camOy - cvPOy) / dt - cvY)
            cvTh += k * ((camTh - cvPTh) / dt - cvTh)
            cvLs += k * (ln(camS / cvPS) / dt - cvLs)
        }
        cvPrevNs = nowNs; cvPS = camS; cvPTh = camTh; cvPOx = camOx; cvPOy = camOy
    }

    private var settings = WheelSettings()
    private var events = EventIndex(emptyList())
    private var now: LocalDateTime = LocalDateTime.now()
    private var tm = TimeMap(now, true, true, false)
    // Живое «сейчас» — каждый кадр (стрелка, планеты, поворот круга во времени).
    private var tmDyn = tm

    /** Доп. поворот сцены за время с её записи (режим «движется круг»). */
    private var timeRot = 0.0

    /** Поворот, с которым рисуется статичная сцена. */
    private fun thEff() = camTh + timeRot

    private var frameNow: LocalDateTime = LocalDateTime.now()

    private fun refreshTime() {
        val n = LocalDateTime.now()
        frameNow = n
        tmDyn = TimeMap(n, settings.counterClockwise, settings.nowAtBottom, settings.keplerOrbit, settings.arrowMoves)
        timeRot = timeRotFor(tm)
    }

    /** Поворот сцены, записанной с [t], к живому «сейчас» (у плиток разных поколений свой). */
    private fun timeRotFor(t: TimeMap): Double = if (settings.arrowMoves) 0.0 else {
        val f = java.time.Duration.between(t.yearStart, frameNow).toNanos() / 1e9 / t.totalSec
        t.dir * t.offsetOf(f)
    }

    private var sceneDirty = true
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
        val g = geom()
        val m = 0.15 * min(width, height)
        var best = Double.MAX_VALUE
        // Цель удовлетворена, если её диск заходит в экран с отступом m.
        fun target(cx: Double, cy: Double, r: Double): Boolean {
            val qx = cx.coerceIn(m, width - m)
            val qy = cy.coerceIn(m, height - m)
            val dx = qx - cx; val dy = qy - cy
            val d = hypot(dx, dy)
            if (d <= r) return true
            if (d - r < best) {
                best = d - r
                val k = (d - r) / d
                out[0] = dx * k; out[1] = dy * k
            }
            return false
        }
        if (target(camOx, camOy, g.keepR * camS)) { out[0] = 0.0; out[1] = 0.0; return 0.0 }
        // Планеты тоже «магнитят»: можно улететь к Юпитеру и остаться там.
        val au = g.tipR * camS
        for (p in Astro.OTHER_PLANETS) {
            val o = p.at(tmDyn.jdNow)
            val a = tmDyn.spaceAngle(o.lambda) + camTh
            val r = o.r * au
            if (target(camOx + cos(a) * r, camOy + sin(a) * r, au * p.radiusKm / AU_KM)) {
                out[0] = 0.0; out[1] = 0.0; return 0.0
            }
        }
        return best
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
    private var touching = false

    // Вращение двумя пальцами: до порога круг не крутится, после — догоняет
    // пальцы (накопленный «долг» rotBacklog гасится за ~1/rotCatchUp с).
    private var rotBacklog = 0.0

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
        rotBacklog = 0.0
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
        if (rotBacklog != 0.0) {
            val part = rotBacklog * (1 - exp(-motion.rotCatchUp * dt))
            rotateAbout(part, rotPx, rotPy)
            rotBacklog -= part
            if (abs(rotBacklog) * rotR < 0.3) {
                rotateAbout(rotBacklog, rotPx, rotPy)
                rotBacklog = 0.0
            }
        }
        if (touching) return rotBacklog != 0.0
        val fr = motion.friction
        val z = motion.damping

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
        var active = rotBacklog != 0.0

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

    /**
     * Отладка (adb): навести камеру на точку круга — [secs] секунд от «сейчас»,
     * на доле радиуса [rf] (1 = внешнее кольцо), зум [zoom], без поворота.
     */
    fun debugLook(zoom: Double, secs: Double, rf: Double, th: Double = 0.0) {
        stopAll()
        refreshTime()
        val g = geom()
        val a = tmDyn.angleOf(tmDyn.nowFrac + secs / tmDyn.totalSec)
        // rf < 0: навестись на ряд подписей №(-rf): 1 день, 2 часы, 3 минуты, 4 секунды.
        val r = if (rf < 0) timeLabelRows(g.outerR, tmDyn.totalSec)[(-rf).toInt() - 1] else g.outerR * rf
        camS = zoom; camTh = th
        camOx = width / 2.0 - zoom * cos(a + th) * r
        camOy = height / 2.0 - zoom * sin(a + th) * r
        invalidate()
    }

    /**
     * Отладка (adb): синтетический жест с точными метками времени — проверка
     * логики тапов/пинча без зависимости от скорости adb input.
     * dbl / tri / qs (двойной тап + свайп вниз) / pinch (пинч с поворотом пальцев [deg]).
     */
    fun debugGesture(name: String, x: Float, y: Float, deg: Float) {
        val t0 = android.os.SystemClock.uptimeMillis()
        val evs = ArrayList<MotionEvent>()
        fun one(dt: Long, act: Int, px: Float, py: Float) {
            evs.add(MotionEvent.obtain(t0, t0 + dt, act, px, py, 0))
        }
        fun tap(at: Long) { one(at, MotionEvent.ACTION_DOWN, x, y); one(at + 60, MotionEvent.ACTION_UP, x, y) }
        when (name) {
            "dbl" -> { tap(0); tap(180) }
            "tri" -> { tap(0); tap(180); tap(360) }
            "qs" -> {
                tap(0)
                one(180, MotionEvent.ACTION_DOWN, x, y)
                for (i in 1..20) one(180 + i * 16L, MotionEvent.ACTION_MOVE, x, y + i * 12f)
                one(180 + 21 * 16L + 200, MotionEvent.ACTION_UP, x, y + 240f)
            }
            "pinch" -> {
                // Два пальца: разводятся ×2 и поворачиваются на deg градусов.
                val props = Array(2) { MotionEvent.PointerProperties().apply { id = it; toolType = MotionEvent.TOOL_TYPE_FINGER } }
                fun coords(k: Double): Array<MotionEvent.PointerCoords> {
                    val r = 150.0 * (1 + k)
                    val a = Math.toRadians(deg * k)
                    return Array(2) { i ->
                        val sg = if (i == 0) -1 else 1
                        MotionEvent.PointerCoords().apply {
                            this.x = (x + sg * r * cos(a)).toFloat(); this.y = (y + sg * r * sin(a)).toFloat()
                            pressure = 1f; size = 1f
                        }
                    }
                }
                evs.add(MotionEvent.obtain(t0, t0, MotionEvent.ACTION_DOWN, 1, props, coords(0.0), 0, 0, 1f, 1f, 0, 0, 0, 0))
                evs.add(MotionEvent.obtain(t0, t0 + 10, MotionEvent.ACTION_POINTER_DOWN or (1 shl 8), 2, props, coords(0.0), 0, 0, 1f, 1f, 0, 0, 0, 0))
                for (i in 1..30) evs.add(MotionEvent.obtain(t0, t0 + 10 + i * 16L, MotionEvent.ACTION_MOVE, 2, props, coords(i / 30.0), 0, 0, 1f, 1f, 0, 0, 0, 0))
                evs.add(MotionEvent.obtain(t0, t0 + 800, MotionEvent.ACTION_POINTER_UP or (1 shl 8), 2, props, coords(1.0), 0, 0, 1f, 1f, 0, 0, 0, 0))
                evs.add(MotionEvent.obtain(t0, t0 + 810, MotionEvent.ACTION_UP, 1, props, coords(1.0), 0, 0, 1f, 1f, 0, 0, 0, 0))
            }
        }
        // Отдаём с реальными паузами — физика и петля кадров живут в реальном времени.
        for (ev in evs) {
            val delay = ev.eventTime - t0
            postDelayed({ onTouchEvent(ev); ev.recycle() }, delay)
        }
    }

    fun debugState(): String = "s=%.4f th=%.2f° o=(%.1f, %.1f)".format(camS, Math.toDegrees(camTh), camOx, camOy)

    fun resetCameraAnimated() {
        stopAll()
        resetActive = true
        rsV = DoubleArray(4)
        refreshTime()
        val want = tmDyn.anchor - tmDyn.nowAngle
        resetThTarget = camTh + Math.IEEEremainder(want - camTh, 2 * PI)
        startLoop()
    }

    /** Движение кончилось: если поворот заметно сменился — переписать подписи под него. */
    private fun requestCrisp() {
        if (abs(TileEngine.wrap(thEff() - flipTh)) > 0.15) sceneDirty = true
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
    private var downOnRing = true
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

    // Порог вращения пары: сколько пальцы повернули и сколько зумнули с начала.
    private var pairRot = 0.0
    private var pairLn = 0.0
    private var rotUnlocked = false

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
                // «Поймали» заметное кручение/панораму — такое касание не тап,
                // но может разогнать следующий бросок. Остаточная инерция зума
                // тапы НЕ ломает (иначе серия «двойной тап + свайп» срывалась).
                val wasMoving = running && zoomTarget.isNaN() && !resetActive &&
                    (abs(vRot) * rotR > 150 || hypot(vPanX, vPanY) > 150)
                carriedRot = if (wasMoving) vRot else 0.0
                carriedPivotWheel = rotPivotWheel
                carriedPanX = if (wasMoving) vPanX else 0.0
                carriedPanY = if (wasMoving) vPanY else 0.0
                stopAll()
                touching = true
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
                // Крутить можно только за кольцо: в дырке и за внешним краем — панорама.
                val dWheel = hypot(x - camOx, y - camOy) / camS
                downOnRing = dWheel >= max(g.innerR, settings.look.holeR * g.outerR) && dWheel <= g.outerR
                mode = when {
                    seq == 2 -> QS_PENDING
                    seq >= 3 -> TRIPLE_PENDING
                    !downOnRing -> CENTER_PENDING
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
                if (e.pointerCount == 2) {
                    pairRot = 0.0; pairLn = 0.0; rotUnlocked = motion.rotThreshold <= 0.0
                }
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
                    var dLn = 0.0
                    if (spanP > 1 && spanQ > 1) {
                        pairLn += ln(spanQ / spanP)
                        dLn = zoomRubber(spanQ / spanP, mqx, mqy)
                    }
                    rotPivotWheel = false; rotPx = mqx; rotPy = mqy; rotR = max(spanQ / 2, 40.0)
                    pairRot += dTh
                    var applied = 0.0
                    if (rotUnlocked) {
                        rotateAbout(dTh, mqx, mqy)
                        applied = dTh
                    } else {
                        // Порог растёт с зумом: пинч с лёгким поворотом пальцев не крутит.
                        val thr = Math.toRadians(motion.rotThreshold + motion.rotZoomK * abs(pairLn) / ln(2.0))
                        if (abs(pairRot) > thr) {
                            rotUnlocked = true
                            rotBacklog += pairRot // круг догоняет пальцы
                            startLoop()
                        }
                    }

                    cum[0] += applied; cum[1] += dx; cum[2] += dy; cum[3] += dLn
                    pushSample(t)
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
                        mode = if (downOnRing) UNDECIDED else PAN
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
                touching = false
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
                touching = false
                stopAll()
                startLoop() // вернуться в пределы, если вышли
            }
        }
        return true
    }

    // Колесо мыши (эмулятор, ПК, мышь по Bluetooth): зум к курсору.
    override fun onGenericMotionEvent(e: MotionEvent): Boolean {
        if (e.actionMasked == MotionEvent.ACTION_SCROLL) {
            val v = e.getAxisValue(MotionEvent.AXIS_VSCROLL)
            if (v != 0f) {
                stopAll()
                zoomRubber(Math.pow(2.0, v * 0.35), e.x.toDouble(), e.y.toDouble())
                zoomFx = e.x.toDouble(); zoomFy = e.y.toDouble()
                startLoop() // вернуть в пределы, если перелетели
                return true
            }
        }
        return super.onGenericMotionEvent(e)
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
        val old = this.settings
        if (old.arrowMoves != settings.arrowMoves || old.counterClockwise != settings.counterClockwise ||
            old.nowAtBottom != settings.nowAtBottom || old.keplerOrbit != settings.keplerOrbit
        ) {
            // Стрелка остаётся там же на экране — камера доворачивается.
            val a0 = TimeMap(now, old.counterClockwise, old.nowAtBottom, old.keplerOrbit, old.arrowMoves).nowAngle
            val a1 = TimeMap(now, settings.counterClockwise, settings.nowAtBottom, settings.keplerOrbit, settings.arrowMoves).nowAngle
            if (old.nowAtBottom == settings.nowAtBottom) camTh += a0 - a1
        }
        this.settings = settings
        this.now = now
        tm = TimeMap(now, settings.counterClockwise, settings.nowAtBottom, settings.keplerOrbit, settings.arrowMoves)
        val filtered = events.filter { settings.calendarFilter.allows(it) }
        this.events = EventIndex(buildEventBands(filtered, now.year, tm.nowFrac, settings.largeEventsOnTop))
        bgKey = null
        sceneDirty = true
        dataChanged = true
        invalidate()
    }

    /** Скорость видимого движения времени, экранных px/с. */
    private fun timePxPerSec(): Double =
        if (width <= 0) 0.0 else 2 * PI * geom().tipR * camS / tm.totalSec

    /** Секундный тик: перерисовать, если движение времени уже заметно. */
    fun tickSecond() {
        if (timePxPerSec() > 0.003) invalidate()
    }

    /** Что видно в экранном прямоугольнике — для LOD записи сцены. */
    private fun computeLod(
        l: Double, t: Double, r: Double, b: Double, originX: Double, originY: Double, th: Double,
        s: Double = camS, camOx: Double = this.camOx, camOy: Double = this.camOy,
    ): Lod {
        val qx = camOx.coerceIn(l, r); val qy = camOy.coerceIn(t, b)
        val inside = qx == camOx && qy == camOy
        val rMin = if (inside) 0.0 else hypot(qx - camOx, qy - camOy) / s
        var rMax = 0.0
        val cxs = doubleArrayOf(l, r, r, l)
        val cys = doubleArrayOf(t, t, b, b)
        for (i in 0..3) rMax = max(rMax, hypot(cxs[i] - camOx, cys[i] - camOy))
        rMax /= s
        val visR = hypot(r - l, b - t) / 2 / s
        val rotDeg = (th * 180 / PI).toFloat()
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
            aLo = aMin - th - pad, aHi = aMax - th + pad,
            rMin = rMin, rMax = rMax, visR = visR, density = density,
        )
    }

    /** Точка контента под экранной точкой (double). */
    private fun contentX(qx: Double, qy: Double, th: Double, s: Double = camS, ox: Double = camOx, oy: Double = camOy): Double {
        val dx = qx - ox; val dy = qy - oy
        return width / 2.0 + (cos(th) * dx + sin(th) * dy) / s
    }

    private fun contentY(qx: Double, qy: Double, th: Double, s: Double = camS, ox: Double = camOx, oy: Double = camOy): Double {
        val dx = qx - ox; val dy = qy - oy
        return height / 2.0 + (-sin(th) * dx + cos(th) * dy) / s
    }

    /** LOD плитки (l,i,j): виртуальная камера уровня без поворота, «экран» = плитка. */
    private fun tileLod(sc: TileScene, l: Int, i: Int, j: Int): Lod {
        val sL = levelScale(l)
        val g = WheelGeom(sc.w, sc.h)
        // Пиксели уровня: q = (p − C)·sL; центр круга в пикселях плитки = (−iT, −jT).
        val cx = -i.toDouble() * TILE; val cy = -j.toDouble() * TILE
        val oxC = g.cx + i.toDouble() * TILE / sL
        val oyC = g.cy + j.toDouble() * TILE / sL
        // Окно с запасом: свечение и края подписей соседей заходят в плитку.
        val m = TILE_MARGIN
        val lod = computeLod(-m, -m, TILE + m, TILE + m, oxC, oyC, 0.0, sL, cx, cy)
        return Lod(
            lod.camScale, lod.originX, lod.originY, sc.flipDeg, lod.full, lod.aLo, lod.aHi,
            lod.rMin, lod.rMax, lod.visR, sc.density,
        )
    }

    private fun newScene(drop: Boolean) {
        sceneDirty = false
        sceneGen++
        flipTh = thEff()
        tiles.setScene(
            TileScene(sceneGen, width.toFloat(), height.toFloat(), settings, tm, events, (flipTh * 180 / PI).toFloat(), density),
            drop,
        )
    }

    /**
     * Отладка (dbg 512): сцена целиком, без плиток, синхронно — эталон для
     * сравнения со плиточным кадром (разница = стыки и прочие ошибки плиток).
     */
    private fun drawReference(c: Canvas) {
        val w = width.toDouble(); val h = height.toDouble()
        val th = thEff()
        val l0 = computeLod(0.0, 0.0, w, h, contentX(w / 2, h / 2, th), contentY(w / 2, h / 2, th), th)
        val lod = Lod(l0.camScale, l0.originX, l0.originY, (flipTh * 180 / PI).toFloat(), l0.full, l0.aLo, l0.aHi,
            l0.rMin, l0.rMax, l0.visR, density)
        c.save()
        c.translate((w / 2).toFloat(), (h / 2).toFloat())
        c.rotate((th * 180 / PI).toFloat())
        c.scale(camS.toFloat(), camS.toFloat())
        drawWheel(c, width.toFloat(), height.toFloat(), tm, settings, events, lod)
        c.restore()
    }

    /** Динамический слой (стрелка, Солнце, планеты) — каждый кадр, живое время. */
    private fun drawBodiesInto(c: Canvas) {
        val w = width.toDouble(); val h = height.toDouble()
        val lod = computeLod(0.0, 0.0, w, h, contentX(w / 2, h / 2, camTh), contentY(w / 2, h / 2, camTh), camTh)
        c.save()
        c.translate((w / 2).toFloat(), (h / 2).toFloat())
        c.rotate((camTh * 180 / PI).toFloat())
        c.scale(camS.toFloat(), camS.toFloat())
        drawBodies(c, width.toFloat(), height.toFloat(), tmDyn, settings, lod)
        c.restore()
    }

    private var lastW = 0
    private var lastH = 0

    // ── Фон и зерно — в ЭКРАННЫХ координатах (не зумятся вместе с кругом) ───
    private var bgKey: Any? = null
    private val bgPaint = Paint()
    private var starPts: Array<FloatArray> = emptyArray()
    private val starPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeCap = Paint.Cap.ROUND }
    private var grainPaint: Paint? = null

    private fun prepareBackdrop() {
        val lk = settings.look
        val key = listOf(width, height, lk.d.backdrop, lk.d.stars)
        if (key == bgKey) return
        bgKey = key
        val w = width.toFloat(); val h = height.toFloat()
        bgPaint.shader = RadialGradient(
            w / 2, h * 0.45f, max(w, h) * 0.75f,
            intArrayOf(lk.bd.bg0, lk.bd.bg1), null, Shader.TileMode.CLAMP,
        )
        if (lk.d.stars && lk.dark) {
            // Три яркости звёзд, по drawPoints на каждую — дёшево.
            val rnd = java.util.Random(7)
            starPts = Array(3) { b ->
                val n = intArrayOf(110, 45, 14)[b]
                FloatArray(n * 2) { i -> if (i % 2 == 0) rnd.nextFloat() * w else rnd.nextFloat() * h }
            }
        } else starPts = emptyArray()
    }

    private fun drawBackdrop(canvas: Canvas) {
        prepareBackdrop()
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), bgPaint)
        val sizes = floatArrayOf(1.2f, 1.9f, 2.8f)
        val alphas = floatArrayOf(0.35f, 0.55f, 0.8f)
        for (b in starPts.indices) {
            starPaint.strokeWidth = sizes[b] * density / 2.75f * 2f
            starPaint.color = withAlpha(WHITE, alphas[b])
            canvas.drawPoints(starPts[b], starPaint)
        }
    }

    private fun drawGrain(canvas: Canvas) {
        val lk = settings.look
        if (lk.d.grain <= 0f) return
        val gp = grainPaint ?: Paint().also { p ->
            val n = 256
            val px = IntArray(n * n)
            val rnd = java.util.Random(11)
            for (i in px.indices) { val v = rnd.nextInt(256); px[i] = (0xFF shl 24) or (v shl 16) or (v shl 8) or v }
            val bmp = Bitmap.createBitmap(px, n, n, Bitmap.Config.ARGB_8888)
            p.shader = BitmapShader(bmp, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT)
            grainPaint = p
        }
        gp.blendMode = if (lk.dark) BlendMode.OVERLAY else BlendMode.MULTIPLY
        gp.alpha = (lk.d.grain * (if (lk.dark) 0.55f else 0.45f) * 255).toInt().coerceIn(0, 255)
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), gp)
    }

    // ── Счётчик кадров (настройка «Счётчик кадров») ─────────────────────────
    var dbg = 0 // отладка: 1 без слоёв LOD, 2 без сцены, 4 без тел, 8 без фона
    var perfHud = false
        set(v) { field = v; invalidate() }
    private val frameDt = LongArray(240)
    private val frameAt = LongArray(240)
    private val frameFb = IntArray(240)   // плиток подложкой в кадре
    private val frameHole = IntArray(240) // дыр в кадре
    private var frameN = 0
    private var lastDrawNs = 0L
    private var drawCostNs = 0L
    private val hudPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = WHITE; textSize = 13f * resources.displayMetrics.density }
    private val hudBg = Paint().apply { color = 0x99000000.toInt() }

    private fun drawHud(canvas: Canvas, now: Long) {
        // Кадры за последние 2 с движения: средний fps и «рывки» (> 2 кадров 60 Гц).
        var n = 0; var sum = 0L; var jank = 0; var worst = 0L; var fbFrames = 0; var holeFrames = 0
        for (i in 0 until min(frameN, frameDt.size)) {
            val k = Math.floorMod(frameN - 1 - i, frameDt.size)
            if (now - frameAt[k] > 2_000_000_000L) break
            val d = frameDt[k]
            if (d > 250_000_000L) continue // простой, не кадр анимации
            n++; sum += d; worst = max(worst, d)
            if (frameFb[k] > 0) fbFrames++
            if (frameHole[k] > 0) holeFrames++
            if (d > 33_000_000L) jank++
        }
        val fps = if (n > 0) 1e9 * n / sum else 0.0
        val lines = arrayOf(
            "fps %.0f".format(fps),
            "рывков >33мс: $jank",
            "кадров с грубой подменой: $fbFrames",
            "кадров с дырой: $holeFrames, оживлено: ${tiles.revivedTotal}",
            "худший кадр: %.0f мс".format(worst / 1e6),
            "onDraw: %.1f мс".format(drawCostNs / 1e6),
            "плитка (фон): %.0f мс".format(tiles.lastJobMs),
            "плиток: %d, в работе %d".format(tiles.cachedCount, tiles.pendingCount),
        )
        val d = resources.displayMetrics.density
        val lh = hudPaint.textSize * 1.25f
        val w = 230f * d
        val x = width - w - 12f * d; val y = 40f * d
        canvas.drawRect(x - 8f * d, y - lh, x + w, y + lh * (lines.size - 0.5f), hudBg)
        for (i in lines.indices) canvas.drawText(lines[i], x, y + i * lh, hudPaint)
    }

    override fun onDraw(canvas: Canvas) {
        if (width <= 0 || height <= 0) return
        val t0 = System.nanoTime()
        if (lastDrawNs != 0L) {
            frameDt[frameN % frameDt.size] = t0 - lastDrawNs
            frameAt[frameN % frameAt.size] = t0
            frameFb[frameN % frameFb.size] = tiles.lastFallbacks
            frameHole[frameN % frameHole.size] = tiles.lastHoles
            frameN++
        }
        lastDrawNs = t0
        trackVelocity(t0)
        drawFrame(canvas)
        drawCostNs = System.nanoTime() - t0
        if (perfHud) drawHud(canvas, t0)
    }

    private fun drawFrame(canvas: Canvas) {
        refreshTime()
        if (dbg and 8 == 0) drawBackdrop(canvas)

        if (width != lastW || height != lastH) { lastW = width; lastH = height; newScene(true) }
        // Новые данные — сразу; переворот подписей — только в покое.
        // Старые плитки видны, пока их не заменят свежие.
        else if (sceneDirty && (dataChanged || tiles.scene == null || (!running && !touching))) {
            dataChanged = false
            newScene(false)
        }

        val outerR = geom().outerR
        val totalSec = tm.totalSec
        var more = false
        if (dbg and 512 != 0) drawReference(canvas)
        else if (dbg and 2 == 0) more = tiles.draw(
            canvas, camS, camTh, camOx, camOy, width.toDouble(), height.toDouble(),
            cvX, cvY, cvTh, cvLs,
            { i -> if (dbg and 1 != 0) 0f else tierVis(i, camS, outerR, totalSec) },
            ::tileLod,
            ::timeRotFor,
        )
        if (dbg and 4 == 0) drawBodiesInto(canvas)
        drawGrain(canvas)

        // Плитки догружаются — следующий кадр (вводятся порциями).
        if (more) postInvalidateOnAnimation()
        // На глубоком зуме время видно глазом — плавно, каждый кадр.
        if (timePxPerSec() > 0.05) postInvalidateOnAnimation()
    }

    private var dataChanged = false

    override fun onDetachedFromWindow() {
        running = false
        super.onDetachedFromWindow()
    }

    /** Вызывать из onDestroy активности: остановить поток записи сцены. */
    fun release() {
        tiles.shutdown()
    }
}
