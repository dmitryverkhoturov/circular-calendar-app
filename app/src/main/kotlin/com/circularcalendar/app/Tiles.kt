package com.circularcalendar.app

import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Picture
import android.graphics.RenderNode
import java.util.concurrent.Executors
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

// ─────────────────────────────────────────────────────────────────────────────
// Плитки, как в картах.
//
// Сцена режется на квадраты TILE×TILE px на дискретных уровнях зума (шаг √2).
// Плитка уровня L — кусок круга, растеризованный на зуме sL = √2^L, в осях
// круга (без поворота камеры): поворот, панорама и зум внутри уровня — просто
// трансформ готовых текстур. Плитка пишется в фоне (Picture), растеризуется
// GPU один раз при первом показе и дальше не перерисовывается.
//
// Чего нет готового — подставляется плитка соседнего уровня (грубее, мягче),
// а не дыра. Новые плитки вводятся в кадр не больше NEW_PER_FRAME за кадр:
// растеризация размазана по кадрам, а не одним куском на весь экран.
// ─────────────────────────────────────────────────────────────────────────────

const val TILE = 512
/** Запас окна плитки, px: свечение и края соседних фигур заходят внутрь. */
const val TILE_MARGIN = 48.0
private const val NEW_PER_FRAME = 4
private const val CACHE_MAX = 90 // ×1 МБ видеопамяти на плитку
private const val BASE_LEVEL = 0 // подложка: весь круг ~4 плитки, всегда в кэше
private val WORKERS = (Runtime.getRuntime().availableProcessors() - 2).coerceIn(2, 6)
private val SQRT2 = sqrt(2.0)

fun levelScale(l: Int): Double = 2.0.pow(l / 2.0)

/** Уровень, чьи плитки показываются на зуме s без увеличения (s ≤ sL < s·√2). */
fun levelFor(s: Double): Int = ceil(2 * ln(s) / ln(2.0) - 1e-9).toInt()

private fun key(l: Int, i: Int, j: Int): Long =
    ((l + 128).toLong() shl 50) or ((i.toLong() and 0x1FFFFFF) shl 25) or (j.toLong() and 0x1FFFFFF)

/** Снимок данных для записи плиток: фоновые потоки не трогают поля View. */
class TileScene(
    val gen: Int,
    val w: Float, val h: Float,
    val settings: WheelSettings,
    val tm: TimeMap,
    val events: EventIndex,
    val flipDeg: Float,
    val density: Float,
)

class TileJobResult(
    val k: Long, val l: Int, val i: Int, val j: Int, val gen: Int, val tm: TimeMap,
    val main: Picture?, val tiers: Array<Picture?>?, val mask: Int, val ms: Double,
)

/**
 * ВАЖНО (грабли v0.13): HWUI выкидывает display list и растровый слой узла,
 * как только узел хотя бы на кадр выпал из дерева отрисовки. Java-объект
 * остаётся, drawRenderNode рисует ПУСТОТУ — «дыра», о которой движок не знает.
 * Поэтому плитка хранит свою Picture и перед показом проверяет hasDisplayList(),
 * а невидимые плитки кэша каждый кадр «держатся» в дереве под пустым клипом.
 */
private class Tile(val l: Int, val i: Int, val j: Int, val tm: TimeMap) {
    var gen = -1
    var empty = false
    var pic: Picture? = null
    var tierPics: Array<Picture?>? = null
    var node: RenderNode? = null
    var tierNodes: Array<RenderNode?>? = null
    var mask = 0
    var shown = false
    var used = 0L
    var drawnAt = 0L
    /** Прежняя версия (старое поколение): видна, пока новая ждёт очереди на показ. */
    var prev: Tile? = null

    /** Главный узел (с растровым слоем) жив — display list не выкинут системой? */
    fun live(): Boolean = empty || node?.hasDisplayList() == true

    /** Перезаписать узлы из сохранённых Picture (растеризуются заново при показе). */
    fun revive() {
        if (empty) return
        val p = pic ?: return
        val n = node ?: RenderNode("tile").apply {
            setUseCompositingLayer(true, Paint(Paint.FILTER_BITMAP_FLAG))
            setPosition(0, 0, TILE, TILE)
        }.also { node = it }
        if (!n.hasDisplayList()) {
            val c = n.beginRecording(TILE, TILE)
            try { c.drawPicture(p) } finally { n.endRecording() }
        }
        val tp = tierPics ?: return
        val tn = tierNodes ?: arrayOfNulls<RenderNode>(TIER_N).also { tierNodes = it }
        for (k in 0 until TIER_N) {
            val q = tp[k] ?: continue
            val node = tn[k] ?: RenderNode("tier").apply {
                setHasOverlappingRendering(false)
                setPosition(0, 0, TILE, TILE)
            }.also { tn[k] = it }
            if (!node.hasDisplayList()) {
                val c = node.beginRecording(TILE, TILE)
                try { c.drawPicture(q) } finally { node.endRecording() }
            }
        }
    }

    fun release() {
        node?.discardDisplayList(); node = null
        tierNodes?.forEach { it?.discardDisplayList() }; tierNodes = null
        pic = null; tierPics = null
        prev?.release(); prev = null
    }
}

class TileEngine(private val onReady: () -> Unit, private val post: (Runnable) -> Unit) {

    // Обычный приоритет: плитки нужны экрану сейчас. Фоновый (nice 10) загонял
    // потоки на слабые ядра — плитка писалась в десятки раз дольше.
    private val pool = Executors.newFixedThreadPool(WORKERS) { r ->
        Thread({
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_DEFAULT)
            r.run()
        }, "tiles")
    }
    private val cache = HashMap<Long, Tile>()
    private val inflight = HashSet<Long>()
    private var frame = 0L
    private var level = Int.MIN_VALUE

    var scene: TileScene? = null
        private set
    var lastJobMs = 0.0
        private set
    val cachedCount get() = cache.size
    /** За последний кадр: плиток, показанных подложкой другого уровня, и дыр. */
    var lastFallbacks = 0
        private set
    var lastHoles = 0
        private set
    val pendingCount get() = inflight.size
    /** Сколько раз плитку пришлось оживить (система выкинула узел) — счётчик для отладки. */
    var revivedTotal = 0L
        private set

    /** Новые данные/настройки/ориентация: всё устаревает, но старое видно, пока не заменено. */
    fun setScene(sc: TileScene, dropAll: Boolean) {
        scene = sc
        if (dropAll) { cache.values.forEach { it.release() }; cache.clear() }
    }

    fun shutdown() = pool.shutdownNow()

    // ── Камера → плитки ─────────────────────────────────────────────────────

    private val m = Matrix()
    private val inv = Matrix()
    private val vals = FloatArray(9)

    /**
     * Матрица плитки (l,i,j): её пиксели → экран. Экран = o + k·R(th)·q,
     * q = пиксели уровня (центр круга = 0), k = s/sL. Считается в double.
     */
    private fun tileMatrix(out: Matrix, l: Int, i: Int, j: Int, s: Double, th: Double, ox: Double, oy: Double) {
        val k = s / levelScale(l)
        val a = k * cos(th); val b = k * sin(th)
        val qx = i.toDouble() * TILE; val qy = j.toDouble() * TILE
        vals[0] = a.toFloat(); vals[1] = (-b).toFloat(); vals[2] = (ox + a * qx - b * qy).toFloat()
        vals[3] = b.toFloat(); vals[4] = a.toFloat(); vals[5] = (oy + b * qx + a * qy).toFloat()
        vals[6] = 0f; vals[7] = 0f; vals[8] = 1f
        out.setValues(vals)
    }

    /** Диапазон индексов плиток уровня l, покрывающий экран (с запасом [pad] px). */
    private fun tileRange(
        l: Int, s: Double, th: Double, ox: Double, oy: Double, w: Double, h: Double, pad: Double,
    ): IntArray {
        val f = levelScale(l) / s
        val c = cos(-th); val sn = sin(-th)
        var x0 = Double.MAX_VALUE; var x1 = -Double.MAX_VALUE
        var y0 = Double.MAX_VALUE; var y1 = -Double.MAX_VALUE
        val xs = doubleArrayOf(-pad, w + pad, w + pad, -pad)
        val ys = doubleArrayOf(-pad, -pad, h + pad, h + pad)
        for (n in 0..3) {
            val dx = xs[n] - ox; val dy = ys[n] - oy
            val qx = (c * dx - sn * dy) * f
            val qy = (sn * dx + c * dy) * f
            x0 = min(x0, qx); x1 = max(x1, qx); y0 = min(y0, qy); y1 = max(y1, qy)
        }
        val lim = 1 shl 24
        return intArrayOf(
            floor(x0 / TILE).coerceIn(-lim.toDouble(), lim.toDouble()).toInt(),
            floor(x1 / TILE).coerceIn(-lim.toDouble(), lim.toDouble()).toInt(),
            floor(y0 / TILE).coerceIn(-lim.toDouble(), lim.toDouble()).toInt(),
            floor(y1 / TILE).coerceIn(-lim.toDouble(), lim.toDouble()).toInt(),
        )
    }

    /** Экранный bbox плитки пересекает экран (с запасом)? */
    private fun tileOnScreen(l: Int, i: Int, j: Int, s: Double, th: Double, ox: Double, oy: Double, w: Double, h: Double, pad: Double): Boolean {
        val k = s / levelScale(l)
        val a = k * cos(th); val b = k * sin(th)
        var x0 = Double.MAX_VALUE; var x1 = -Double.MAX_VALUE
        var y0 = Double.MAX_VALUE; var y1 = -Double.MAX_VALUE
        for (n in 0..3) {
            val qx = (i + (if (n == 1 || n == 2) 1 else 0)).toDouble() * TILE
            val qy = (j + (if (n >= 2) 1 else 0)).toDouble() * TILE
            val x = ox + a * qx - b * qy; val y = oy + b * qx + a * qy
            x0 = min(x0, x); x1 = max(x1, x); y0 = min(y0, y); y1 = max(y1, y)
        }
        return x1 >= -pad && x0 <= w + pad && y1 >= -pad && y0 <= h + pad
    }

    /** Плитка заведомо пуста (вне круга, орбиты скрыты)? */
    private fun emptyTile(sc: TileScene, l: Int, i: Int, j: Int): Boolean {
        if (sc.settings.showOrbits) return false
        val g = WheelGeom(sc.w, sc.h)
        val sL = levelScale(l)
        val x0 = i.toDouble() * TILE; val y0 = j.toDouble() * TILE
        val qx = 0.0.coerceIn(x0, x0 + TILE); val qy = 0.0.coerceIn(y0, y0 + TILE)
        return hypot(qx, qy) > g.keepR * 1.15 * sL
    }

    // ── Запись плитки (фоновый поток) ───────────────────────────────────────

    private fun record(sc: TileScene, l: Int, i: Int, j: Int, lodOf: (TileScene, Int, Int, Int) -> Lod): TileJobResult {
        val t0 = System.nanoTime()
        val k = key(l, i, j)
        if (emptyTile(sc, l, i, j)) return TileJobResult(k, l, i, j, sc.gen, sc.tm, null, null, 0, 0.0)
        val sL = levelScale(l)
        val lod = lodOf(sc, l, i, j)
        val main = Picture()
        val tiers = arrayOfNulls<Picture>(TIER_N)
        val tcs = arrayOfNulls<Canvas>(TIER_N)
        fun begin(p: Picture): Canvas = p.beginRecording(TILE, TILE).apply {
            clipRect(0, 0, TILE, TILE)
            scale(sL.toFloat(), sL.toFloat())
        }
        val mc = begin(main)
        // Слои LOD: проявляющиеся в диапазоне зума этого уровня — отдельно, с alpha на кадр.
        val mask = drawWheel(mc, sc.w, sc.h, sc.tm, sc.settings, sc.events, lod, { n ->
            tcs[n] ?: begin(Picture().also { tiers[n] = it }).also { tcs[n] = it }
        }, sL / SQRT2 / 1.1, sL * 1.1)
        if (RDBG and 256 != 0) {
            mc.save(); mc.scale((1 / sL).toFloat(), (1 / sL).toFloat())
            val dp = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 40f; color = 0xFFFFFF00.toInt(); strokeWidth = 3f }
            mc.drawText("$i,$j", 200f, 256f, dp)
            if (!lod.full) for ((a, c) in listOf(lod.aLo to 0xFF00FFFF.toInt(), lod.aHi to 0xFFFF00FF.toInt())) {
                dp.color = c
                val cx = -i.toDouble() * TILE; val cy = -j.toDouble() * TILE
                val r0 = lod.rMin * sL; val r1 = lod.rMax * sL
                mc.drawLine((cx + cos(a) * r0).toFloat(), (cy + sin(a) * r0).toFloat(), (cx + cos(a) * r1).toFloat(), (cy + sin(a) * r1).toFloat(), dp)
            }
            mc.restore()
        }
        main.endRecording()
        for (p in tiers) p?.endRecording()
        return TileJobResult(k, l, i, j, sc.gen, sc.tm, main, tiers, mask, (System.nanoTime() - t0) / 1e6)
    }

    private fun submit(l: Int, i: Int, j: Int, lodOf: (TileScene, Int, Int, Int) -> Lod) {
        val sc = scene ?: return
        val k = key(l, i, j)
        inflight.add(k)
        pool.execute {
            val r = try { record(sc, l, i, j, lodOf) } catch (e: Throwable) {
                android.util.Log.e("CCPERF", "tile failed", e); null
            }
            post(Runnable { accept(k, r) })
        }
    }

    /** UI-поток: готовую Picture — в узел с растровым слоем (растеризуется при первом показе). */
    private fun accept(k: Long, r: TileJobResult?) {
        inflight.remove(k)
        val sc = scene
        if (r == null || sc == null) { onReady(); return }
        lastJobMs = if (r.ms > 0) lastJobMs * 0.7 + r.ms * 0.3 else lastJobMs
        val old = cache[k]
        // Ответ на устаревшие данные годится только как замена «ничего».
        if (r.gen != sc.gen && old != null) { onReady(); return }
        val t = Tile(r.l, r.i, r.j, r.tm)
        t.gen = r.gen
        t.used = frame
        if (r.main == null) t.empty = true
        else {
            t.pic = r.main
            t.mask = r.mask
            if (r.mask != 0) t.tierPics = Array(TIER_N) { n -> if (r.mask and (1 shl n) == 0) null else r.tiers!![n] }
            t.revive()
        }
        // Старую версию не выкидываем, пока новая не показана: иначе — кадры с дырой/подложкой.
        if (old != null) {
            if (old.shown && !old.empty && old.live()) {
                old.prev?.let { deferRelease(it) }; old.prev = null
                t.prev = old
            } else deferRelease(old)
        }
        cache[k] = t
        onReady()
    }

    // ── Кадр ─────────────────────────────────────────────────────────────────

    private val dbgPaint = Paint().apply { style = Paint.Style.STROKE; color = 0xFFFF0000.toInt(); strokeWidth = 2f }
    private val wantList = ArrayList<LongArray>() // (dist2, l, i, j)

    /**
     * Нарисовать круг плитками. [tierAlpha] — яркость слоя LOD на текущем зуме.
     * [vx],[vy],[vls] — скорость камеры (для подкачки по ходу движения).
     * Возвращает true, если ещё что-то догружается (нужен следующий кадр).
     */
    fun draw(
        canvas: Canvas, s: Double, camTh: Double, ox: Double, oy: Double, w: Double, h: Double,
        vx: Double, vy: Double, vth: Double, vls: Double,
        tierAlpha: (Int) -> Float,
        lodOf: (TileScene, Int, Int, Int) -> Lod,
        rotOf: (TimeMap) -> Double,
    ): Boolean {
        val sc = scene ?: return false
        frame++
        this.camTh = camTh; this.rotOf = rotOf; rotMemo.clear()
        // Поворот круга во времени считается от «сейчас» КАЖДОЙ плитки (её TimeMap):
        // плитки разных поколений стыкуются без сдвига.
        val th = camTh + rotFor(sc.tm)
        // Уровень с гистерезисом: не скакать туда-сюда на границе.
        val want = levelFor(s)
        if (level == Int.MIN_VALUE) level = want
        else {
            val sL = levelScale(level)
            if (s > sL * 1.06 || s < sL / SQRT2 / 1.06) level = want
        }
        val L = level

        wantList.clear()
        lastFallbacks = 0; lastHoles = 0
        var fresh = 0
        var pending = false
        val r = tileRange(L, s, th, ox, oy, w, h, 0.0)
        val alphas = FloatArray(TIER_N) { tierAlpha(it) }

        for (j in r[2]..r[3]) for (i in r[0]..r[1]) {
            if (!tileOnScreen(L, i, j, s, th, ox, oy, w, h, 0.0)) continue
            val k = key(L, i, j)
            val t = cache[k]
            var drawn = false
            if (t != null) {
                t.used = frame
                if (t.gen != sc.gen) addWant(L, i, j, s, th, ox, oy, w, h, 0)
                if (t.empty) continue
                val live = t.live()
                if ((t.shown && live) || fresh < NEW_PER_FRAME) {
                    if (!live) { t.revive(); revivedTotal++ }
                    if (!t.shown || !live) { fresh++; t.shown = true }
                    t.prev?.let { p -> t.prev = null; deferRelease(p) }
                    drawTile(canvas, t, s, ox, oy, alphas)
                    drawn = true
                } else {
                    pending = true // готова, но ждёт очереди на растеризацию
                    val p = t.prev
                    if (p != null && p.live()) { p.used = frame; drawTile(canvas, p, s, ox, oy, alphas); drawn = true }
                }
            } else {
                if (emptyTile(sc, L, i, j)) continue
                addWant(L, i, j, s, th, ox, oy, w, h, 0)
            }
            if (!drawn) {
                if (fallback(canvas, L, i, j, s, th, ox, oy, alphas)) lastFallbacks++ else lastHoles++
            }
        }

        // Подкачка: кольцо вокруг экрана по ходу движения и соседний уровень при зуме.
        // Камера через lead секунд: сдвиг и поворот (поворот вокруг центра круга
        // двигает экранные точки, а не o) — просим плитки там, куда едем.
        val lead = 0.35
        val thP = th + (vth * lead).coerceIn(-1.0, 1.0)
        val pad = TILE * 0.5
        val oxP = ox + vx * lead; val oyP = oy + vy * lead
        val r2 = tileRange(L, s, thP, oxP, oyP, w, h, pad)
        if ((r2[1] - r2[0] + 1).toLong() * (r2[3] - r2[2] + 1) < 400) {
            for (j in r2[2]..r2[3]) for (i in r2[0]..r2[1]) {
                if (!tileOnScreen(L, i, j, s, thP, oxP, oyP, w, h, pad)) continue
                val t = cache[key(L, i, j)]
                if (t != null) { t.used = frame; if (t.gen == sc.gen) continue }
                if (!emptyTile(sc, L, i, j)) addWant(L, i, j, s, th, ox, oy, w, h, 1)
            }
        }
        if (abs(vls) > 0.3) {
            val L2 = if (vls > 0) L + 1 else L - 1
            val r3 = tileRange(L2, s, th, ox, oy, w, h, 0.0)
            if ((r3[1] - r3[0] + 1).toLong() * (r3[3] - r3[2] + 1) < 200) {
                for (j in r3[2]..r3[3]) for (i in r3[0]..r3[1]) {
                    if (!tileOnScreen(L2, i, j, s, th, ox, oy, w, h, 0.0)) continue
                    val t = cache[key(L2, i, j)]
                    if (t != null) { t.used = frame; if (t.gen == sc.gen) continue }
                    if (!emptyTile(sc, L2, i, j)) addWant(L2, i, j, s, th, ox, oy, w, h, 2)
                }
            }
        }

        // Подложка: весь круг на уровне BASE_LEVEL — нужна раньше всего (последний рубеж от дыр).
        if (L != BASE_LEVEL) for (j in -1..0) for (i in -1..0) {
            val t = cache[key(BASE_LEVEL, i, j)]
            if (t != null) t.used = frame
            // Подложка обновляется со сменой поколения (тема, данные) — иначе остаётся старой навсегда.
            if (t == null || t.gen != sc.gen) addWant(BASE_LEVEL, i, j, s, th, ox, oy, w, h, -1)
        }

        // Страховка от дыр: грубый уровень (в 4 раза мельче) всегда под рукой.
        run {
            val L4 = L - 4
            val r4 = tileRange(L4, s, th, ox, oy, w, h, TILE * 0.5)
            if ((r4[1] - r4[0] + 1).toLong() * (r4[3] - r4[2] + 1) < 50) {
                for (j in r4[2]..r4[3]) for (i in r4[0]..r4[1]) {
                    val t = cache[key(L4, i, j)]
                    if (t != null) { t.used = frame; if (t.gen == sc.gen) continue }
                    if (!emptyTile(sc, L4, i, j)) addWant(L4, i, j, s, th, ox, oy, w, h, 1)
                }
            }
        }

        // Отправить в работу самые нужные (ближе к центру экрана, видимые первыми).
        if (inflight.size < WORKERS * 3) {
            wantList.sortBy { it[0] }
            for (wnt in wantList) {
                if (inflight.size >= WORKERS * 3) break
                val l = wnt[1].toInt(); val i = wnt[2].toInt(); val j = wnt[3].toInt()
                if (key(l, i, j) in inflight) continue
                submit(l, i, j, lodOf)
            }
        }
        evict()
        keepAlive(canvas)
        // Недостающие плитки сами позовут кадр, когда придут (onReady).
        return pending
    }

    private var camTh = 0.0
    private var rotOf: (TimeMap) -> Double = { 0.0 }
    private val rotMemo = java.util.IdentityHashMap<TimeMap, Double>()
    private fun rotFor(tm: TimeMap): Double = rotMemo.getOrPut(tm) { rotOf(tm) }

    private val releaseQueue = ArrayList<Tile>()
    /** Выкинуть узлы через кадр: текущий display list ещё может на них ссылаться. */
    private fun deferRelease(t: Tile) { releaseQueue.add(t) }

    /**
     * Держать показанные плитки кэша в дереве HWUI: под пустым клипом они не
     * рисуются, но система не выкидывает их display list и растровый слой.
     */
    private fun keepAlive(canvas: Canvas) {
        canvas.save()
        canvas.clipRect(0f, 0f, 0f, 0f)
        fun hold(t: Tile) {
            if (t.drawnAt == frame || !t.shown || t.empty) return
            val n = t.node ?: return
            if (!n.hasDisplayList()) return
            canvas.drawRenderNode(n)
            t.tierNodes?.forEach { tn -> if (tn != null && tn.hasDisplayList()) canvas.drawRenderNode(tn) }
        }
        for (t in cache.values) { hold(t); t.prev?.let { hold(it) } }
        canvas.restore()
    }

    private fun addWant(l: Int, i: Int, j: Int, s: Double, th: Double, ox: Double, oy: Double, w: Double, h: Double, prio: Int) {
        if (key(l, i, j) in inflight) return
        // Расстояние центра плитки до центра экрана + штраф за приоритет.
        val k = s / levelScale(l)
        val qx = (i + 0.5) * TILE; val qy = (j + 0.5) * TILE
        val x = ox + k * (cos(th) * qx - sin(th) * qy) - w / 2
        val y = oy + k * (sin(th) * qx + cos(th) * qy) - h / 2
        val d = (x * x + y * y).toLong() + prio * 100_000_000L
        wantList.add(longArrayOf(d, l.toLong(), i.toLong(), j.toLong()))
    }

    private fun drawTile(canvas: Canvas, t: Tile, s: Double, ox: Double, oy: Double, alphas: FloatArray) {
        t.drawnAt = frame
        tileMatrix(m, t.l, t.i, t.j, s, camTh + rotFor(t.tm), ox, oy)
        canvas.save()
        canvas.concat(m)
        t.node?.let { canvas.drawRenderNode(it) }
        if (RDBG and 256 != 0) canvas.drawRect(1f, 1f, TILE - 1f, TILE - 1f, dbgPaint)
        t.tierNodes?.let { tn ->
            if (tn.any { it != null && !it.hasDisplayList() }) t.revive() // слои LOD без растра — дёшево
            var hidden = false
            for (n in 0 until TIER_N) {
                val node = tn[n] ?: continue
                val a = alphas[n]
                if (a <= 0f) { hidden = true; continue }
                node.alpha = a
                canvas.drawRenderNode(node)
            }
            // Погасшие слои тоже держим в дереве, иначе система выкинет их display list.
            if (hidden) {
                canvas.save()
                canvas.clipRect(0f, 0f, 0f, 0f)
                for (n in 0 until TIER_N) if (alphas[n] <= 0f) tn[n]?.let { canvas.drawRenderNode(it) }
                canvas.restore()
            }
        }
        canvas.restore()
    }

    /** Ничего готового для (L,i,j): плитки грубее (или мельче) уровня в её границах. */
    private fun fallback(canvas: Canvas, L: Int, i: Int, j: Int, s: Double, th: Double, ox: Double, oy: Double, alphas: FloatArray): Boolean {
        tileMatrix(m, L, i, j, s, th, ox, oy)
        if (!m.invert(inv)) return false
        val order = intArrayOf(-1, -2, 1, -3, -4, 2, -5, -6, -7, -8, -10, -12, BASE_LEVEL - L)
        for (d in order) {
            val l2 = L + d
            if (d == 0 || (l2 == BASE_LEVEL && d != BASE_LEVEL - L)) continue
            val f = levelScale(l2) / levelScale(L)
            val i0 = floor(i * TILE * f / TILE).toInt(); val i1 = floor(((i + 1) * TILE * f - 1e-6) / TILE).toInt()
            val j0 = floor(j * TILE * f / TILE).toInt(); val j1 = floor(((j + 1) * TILE * f - 1e-6) / TILE).toInt()
            if ((i1 - i0 + 1) * (j1 - j0 + 1) > 9) continue
            var ok = true
            for (jj in j0..j1) for (ii in i0..i1) {
                val t = cache[key(l2, ii, jj)]
                if (t == null) ok = false
            }
            if (!ok) continue
            canvas.save()
            canvas.concat(m)
            canvas.clipRect(0, 0, TILE, TILE)
            canvas.concat(inv)
            for (jj in j0..j1) for (ii in i0..i1) {
                val t = cache[key(l2, ii, jj)]!!
                t.used = frame
                if (!t.empty) {
                    if (!t.live()) { t.revive(); revivedTotal++ }
                    t.shown = true
                    drawTile(canvas, t, s, ox, oy, alphas)
                }
            }
            canvas.restore()
            return true
        }
        return false
    }

    /**
     * Лишнее — вон. Подложка (BASE_LEVEL) не выкидывается никогда; сначала
     * уходят плитки далёких уровней, потом — давно не нужные. Соседние уровни
     * держим: именно они закрывают дыры во время зума.
     */
    private fun evict() {
        for (t in releaseQueue) t.release()
        releaseQueue.clear()
        if (cache.size <= CACHE_MAX) return
        val L = level
        val cand = cache.entries.filter { it.value.drawnAt < frame && it.value.used < frame && it.value.l != BASE_LEVEL }
            .sortedWith(compareBy({ -levelDist(it.value.l, L) }, { it.value.used }))
        var n = cache.size - CACHE_MAX
        for (e in cand) {
            if (n <= 0) break
            e.value.release(); cache.remove(e.key); n--
        }
    }

    private fun levelDist(l: Int, L: Int): Int = if (l in L - 4..L + 1) 0 else abs(l - L)

    /** Все видимые плитки текущего уровня готовы и свежие? */
    fun settled(): Boolean = inflight.isEmpty()

    /** Плитки, что пишутся в покое, — для оценки. */
    @Suppress("unused")
    fun debugInfo() = "lvl=$level cache=${cache.size} inflight=${inflight.size}"

    companion object {
        /** Угол в [-π, π]. */
        fun wrap(a: Double): Double = Math.IEEEremainder(a, 2 * PI)
    }
}
