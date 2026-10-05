package com.circularcalendar.app

import android.graphics.Typeface
import org.json.JSONObject
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

// ─────────────────────────────────────────────────────────────────────────────
// Дизайн — единственный источник цветов, шрифтов и эффектов.
// [Design] — то, что крутит пользователь (ручки в панели, сохраняется);
// [Look] — всё, что из него выведено для рендера (цвета, шрифт).
// Порт дизайн-стенда (artifact) — см. branches/design.md.
// ─────────────────────────────────────────────────────────────────────────────

fun withAlpha(color: Int, alpha: Float): Int {
    val a = (alpha.coerceIn(0f, 1f) * 255f + 0.5f).toInt()
    return (color and 0x00FFFFFF) or (a shl 24)
}

/** Умножает существующий альфа-канал цвета на [k] (для LOD-проявления). */
fun mulAlpha(color: Int, k: Float): Int {
    val a = ((color ushr 24) * k.coerceIn(0f, 1f) + 0.5f).toInt().coerceIn(0, 255)
    return (color and 0x00FFFFFF) or (a shl 24)
}

const val WHITE = 0xFFFFFFFF.toInt()
const val BLACK = 0xFF000000.toInt()

/** Смешение цветов (непрозрачный результат). */
fun mix(a: Int, b: Int, t: Float): Int {
    val k = t.coerceIn(0f, 1f)
    fun ch(sh: Int) = (((a shr sh) and 0xFF) + (((b shr sh) and 0xFF) - ((a shr sh) and 0xFF)) * k).roundToInt()
    return (0xFF shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
}

fun luminance(c: Int): Float =
    0.299f * ((c shr 16) and 0xFF) + 0.587f * ((c shr 8) and 0xFF) + 0.114f * (c and 0xFF)

fun hslToColor(h: Float, s: Float, l: Float): Int {
    val hh = ((h % 360f) + 360f) % 360f
    val ss = s / 100f; val ll = l / 100f
    val a = ss * min(ll, 1 - ll)
    fun f(n: Int): Int {
        val k = (n + hh / 30f) % 12f
        val v = ll - a * max(-1f, min(min(k - 3f, 9f - k), 1f))
        return (v * 255f).roundToInt().coerceIn(0, 255)
    }
    return (0xFF shl 24) or (f(0) shl 16) or (f(8) shl 8) or f(4)
}

/** [h 0..360, s 0..100, l 0..100] */
fun colorToHsl(c: Int): FloatArray {
    val r = ((c shr 16) and 0xFF) / 255f
    val g = ((c shr 8) and 0xFF) / 255f
    val b = (c and 0xFF) / 255f
    val mx = max(r, max(g, b)); val mn = min(r, min(g, b))
    val l = (mx + mn) / 2
    var h = 0f; var s = 0f
    if (mx != mn) {
        val d = mx - mn
        s = if (l > 0.5f) d / (2 - mx - mn) else d / (mx + mn)
        h = when (mx) {
            r -> (g - b) / d + (if (g < b) 6 else 0)
            g -> (b - r) / d + 2
            else -> (r - g) / d + 4
        } * 60f
    }
    return floatArrayOf(h, s * 100, l * 100)
}

// ── Фоны ─────────────────────────────────────────────────────────────────────

class Backdrop(
    val name: Int, // R.string
    val bg0: Int,      // центр фонового градиента
    val bg1: Int,      // края
    val surface: Int,  // основа заливки круга
    val ink: Int?,     // текст (null — из главного цвета)
    val line: Int?,    // линии (null — из главного цвета)
    val dark: Boolean,
)

val BACKDROPS = listOf(
    Backdrop(R.string.bd_space, 0xFF211857.toInt(), 0xFF0A0722.toInt(), 0xFF2C2163.toInt(), 0xFFF6F0FF.toInt(), WHITE, true),
    Backdrop(R.string.bd_plum, 0xFF2B0F2C.toInt(), 0xFF10050F.toInt(), 0xFF3A1638.toInt(), 0xFFFFEFF7.toInt(), WHITE, true),
    Backdrop(R.string.bd_night, 0xFF0D1017.toInt(), 0xFF090B10.toInt(), 0xFF141B2E.toInt(), WHITE, WHITE, true),
    Backdrop(R.string.bd_coal, 0xFF151515.toInt(), 0xFF070707.toInt(), 0xFF1B1B1B.toInt(), WHITE, WHITE, true),
    Backdrop(R.string.bd_paper, 0xFFF3EEE3.toInt(), 0xFFE5DECE.toInt(), 0xFFEAE2D3.toInt(), null, null, false),
    Backdrop(R.string.bd_chalk, 0xFFF5F5F2.toInt(), 0xFFE4E4E0.toInt(), 0xFFEEEEEA.toInt(), 0xFF141414.toInt(), 0xFF141414.toInt(), false),
)

// ── Шрифты (системные: ноль байт к APK) ─────────────────────────────────────

class FontChoice(
    val name: Int, val family: String, val style: Int, val caps: Boolean, val spacing: Float,
    val res: Int = 0, // встроенный TTF (res/font, урезан: один вес, латиница+Latin Ext-A+кириллица)
    val asset: String? = null, // оригинальный вариативный TTF из assets (не модифицируем — RFN в лицензии)
    val weight: Int = 400,
)

private const val COMFORTAA = "fonts/Comfortaa[wght].ttf"

/** Встроенные шрифты грузятся один раз из ресурсов (нужен Context — см. MainActivity). */
object Fonts {
    private val cache = HashMap<Any, Typeface>()
    var ctx: android.content.Context? = null

    fun of(f: FontChoice): Typeface {
        if (f.asset != null) {
            val key = f.asset + f.weight
            cache[key]?.let { return it }
            val c = ctx
            if (c != null) {
                try {
                    return Typeface.Builder(c.assets, f.asset)
                        .setFontVariationSettings("'wght' ${f.weight}").build()
                        .also { cache[key] = it }
                } catch (_: Exception) {}
            }
        }
        if (f.res != 0) {
            cache[f.res]?.let { return it }
            val c = ctx
            if (c != null) {
                try { return c.resources.getFont(f.res).also { cache[f.res] = it } } catch (_: Exception) {}
            }
        }
        return Typeface.create(f.family, f.style)
    }
}

val FONTS = listOf(
    FontChoice(R.string.f_regular, "sans-serif-medium", Typeface.NORMAL, false, 0f),
    FontChoice(R.string.f_bold, "sans-serif-black", Typeface.NORMAL, false, 0f),
    FontChoice(R.string.f_narrow, "sans-serif-condensed", Typeface.BOLD, false, 0f),
    FontChoice(R.string.f_caps, "sans-serif-light", Typeface.NORMAL, true, 0.22f),
    FontChoice(R.string.f_serif, "serif", Typeface.BOLD, false, 0f),
    FontChoice(R.string.f_mono, "monospace", Typeface.BOLD, false, 0f),
    FontChoice(R.string.f_hand, "casual", Typeface.NORMAL, false, 0f),
    // Встроенные (≈100–140 КБ; Comfortaa — оригинал ≈200 КБ): мягкие, с кириллицей.
    FontChoice(R.string.f_nunito, "sans-serif-medium", Typeface.NORMAL, false, 0.01f, R.font.nunito),
    FontChoice(R.string.f_comfortaa, "sans-serif-medium", Typeface.NORMAL, false, 0f, asset = COMFORTAA, weight = 700),
    FontChoice(R.string.f_rubik, "sans-serif-medium", Typeface.NORMAL, false, 0f, R.font.rubik),
    FontChoice(R.string.f_unbounded, "sans-serif-black", Typeface.NORMAL, false, 0f, R.font.unbounded),
    FontChoice(R.string.f_comfortaa_caps, "sans-serif-light", Typeface.NORMAL, true, 0.18f, asset = COMFORTAA, weight = 700),
)

val PALETTE_NAMES = listOf(R.string.pal_calendar, R.string.pal_soft, R.string.pal_shades, R.string.pal_mono)
val ARROW_NAMES = listOf(R.string.arrow_warm, R.string.arrow_accent, R.string.arrow_white)

/** Мультяшная палитра: цвет календаря заменяется ближайшим по оттенку. */
private val SOFT_PALETTE = intArrayOf(
    0xFFFF9F43.toInt(), 0xFFFFD166.toInt(), 0xFF9BE564.toInt(), 0xFF2EC4B6.toInt(),
    0xFF5FA8FF.toInt(), 0xFF9C7BFF.toInt(), 0xFFFF6B9D.toInt(), 0xFFFF6B5E.toInt(),
)

val SWATCHES = listOf(
    0xFFC2357F.toInt() to "Orchid",
    0xFFB8295F.toInt() to "Raspberry plum",
    0xFFA8287A.toInt() to "Fuchsia",
    0xFF962A8C.toInt() to "Plum",
    0xFF7E2E9C.toInt() to "Purple",
    0xFFD4A853.toInt() to "Gold",
    0xFFFFFFFF.toInt() to "White",
)

// ── Ручки ────────────────────────────────────────────────────────────────────

data class Design(
    val preset: String = "dusk",
    val accent: Int = 0xFFA8287A.toInt(),
    val ring: Boolean = true,      // кольцо с дыркой / залит до центра
    val hole: Float = 0.30f,       // радиус дырки, доля внешнего радиуса
    val grad: Float = 0.10f,       // непрозрачность у дырки (1 = ровная заливка)
    val holeLine: Boolean = true,  // обвод дырки
    val tint: Float = 0.75f,       // сколько главного цвета в заливке
    val pastDim: Float = 0f,       // насколько прошлое темнее будущего
    val backdrop: Int = 0,
    val glow: Float = 0.30f,
    val grain: Float = 0f,
    val volume: Float = 0.35f,     // тень под кругом + блик + блик на лентах
    val stars: Boolean = false,
    val rays: Boolean = false,     // лучи по дням, длина = занятость дня
    val font: Int = 0,
    val palette: Int = 1,
    val arrow: Int = 0,
    val roundEvents: Boolean = true, // события-«пилюли» с круглыми концами
    val outline: Float = 0f,       // толщина мультяшной обводки событий
) {
    fun toJson(): String = JSONObject().apply {
        put("preset", preset); put("accent", accent); put("ring", ring); put("hole", hole.toDouble())
        put("grad", grad.toDouble()); put("holeLine", holeLine); put("tint", tint.toDouble())
        put("pastDim", pastDim.toDouble()); put("backdrop", backdrop); put("glow", glow.toDouble())
        put("grain", grain.toDouble()); put("volume", volume.toDouble()); put("stars", stars)
        put("rays", rays); put("font", font); put("palette", palette); put("arrow", arrow)
        put("roundEvents", roundEvents); put("outline", outline.toDouble())
    }.toString()

    companion object {
        fun fromJson(s: String?): Design {
            if (s.isNullOrEmpty()) return Design()
            return try {
                val o = JSONObject(s); val d = Design()
                fun f(k: String, v: Float) = o.optDouble(k, v.toDouble()).toFloat()
                Design(
                    o.optString("preset", d.preset), o.optInt("accent", d.accent), o.optBoolean("ring", d.ring),
                    f("hole", d.hole), f("grad", d.grad), o.optBoolean("holeLine", d.holeLine), f("tint", d.tint),
                    f("pastDim", d.pastDim), o.optInt("backdrop", d.backdrop).coerceIn(0, BACKDROPS.size - 1),
                    f("glow", d.glow), f("grain", d.grain), f("volume", d.volume), o.optBoolean("stars", d.stars),
                    o.optBoolean("rays", d.rays), o.optInt("font", d.font).coerceIn(0, FONTS.size - 1),
                    o.optInt("palette", d.palette).coerceIn(0, PALETTE_NAMES.size - 1),
                    o.optInt("arrow", d.arrow).coerceIn(0, ARROW_NAMES.size - 1),
                    o.optBoolean("roundEvents", d.roundEvents), f("outline", d.outline),
                )
            } catch (_: Exception) { Design() }
        }
    }
}

/** Пресеты: id → (название, дизайн). Первый — по умолчанию. */
val PRESETS: List<Pair<Int, Design>> = listOf(
    R.string.p_dusk to Design(),
    R.string.p_cartoon to Design(
        preset = "cartoon", glow = 0.55f, grain = 0.22f, volume = 0.6f, stars = true, font = 7,
    ),
    R.string.bd_paper to Design(
        preset = "paper", hole = 0.22f, grad = 0f, holeLine = false, tint = 0.10f,
        backdrop = 4, glow = 0f, grain = 0.25f, volume = 0f, rays = true, font = 11, palette = 2, arrow = 1,
    ),
    R.string.bd_plum to Design(
        preset = "plum", accent = 0xFFB8295F.toInt(), hole = 0.42f, grad = 0f, tint = 0.9f,
        backdrop = 1, glow = 0.8f, grain = 0.3f, volume = 0.3f, palette = 2, arrow = 2,
    ),
    R.string.p_ink to Design(
        preset = "mono", accent = WHITE, hole = 0.3f, grad = 0.15f, tint = 0.07f, backdrop = 3,
        glow = 0f, grain = 0.12f, volume = 0f, font = 10, palette = 3, arrow = 2, outline = 1f,
    ),
    R.string.bd_chalk to Design(
        preset = "monoLight", accent = 0xFF141414.toInt(), hole = 0.3f, grad = 0.15f, tint = 0.05f,
        backdrop = 5, glow = 0f, grain = 0.12f, volume = 0f, font = 10, palette = 3, arrow = 1, outline = 1f,
    ),
    R.string.p_classic to Design(
        preset = "classic", accent = 0xFFD4A853.toInt(), ring = false, hole = 0f, grad = 1f, holeLine = false,
        tint = 0.06f, pastDim = 0.5f, backdrop = 2, glow = 0f, volume = 0f, palette = 0, arrow = 1,
        roundEvents = false,
    ),
)

/** Всё, что рендер выводит из [Design]. */
class Look(val d: Design) {
    val bd = BACKDROPS[d.backdrop.coerceIn(0, BACKDROPS.size - 1)]
    val dark = bd.dark
    val accent = d.accent
    val background = bd.bg1
    val ink: Int = bd.ink ?: mix(accent, 0xFF1A0610.toInt(), 0.45f)
    val line: Int = bd.line ?: mix(accent, 0xFF1A0610.toInt(), 0.25f)

    val sectorFuture: Int = mix(bd.surface, accent, d.tint * 0.62f)
    val sectorPast: Int = mix(sectorFuture, bd.bg1, d.pastDim * 0.6f)

    val holeR: Float get() = if (d.ring) max(d.hole, 0.05f) else 0f

    val ringColor: Int = if (dark) withAlpha(mix(accent, WHITE, 0.35f), 0.95f) else withAlpha(accent, 0.9f)
    val ringWidth: Double = when {
        d.outline > 0f -> 2.6 + 1.6 * d.outline
        d.volume > 0f || d.glow > 0.3f -> 2.6
        else -> 1.0
    }

    val arrowColor: Int = when (d.arrow) {
        0 -> 0xFFFFB84D.toInt()
        2 -> if (dark) WHITE else 0xFF2A0D1C.toInt()
        else -> if (dark) mix(accent, WHITE, 0.25f) else accent
    }

    val font = FONTS[d.font.coerceIn(0, FONTS.size - 1)]
    val typeface: Typeface = Fonts.of(font)

    // Риски (альфы — порт старой иерархии сезон > месяц > неделя > день > час).
    // Границы дней должны читаться и на светлой фуксии (было 0.08 — «пропали»).
    val tickAlpha = if (dark) 0.15f else 0.17f
    val monthAlpha = if (d.rays) 0f else if (dark) 0.24f else 0.30f
    val seasonAlpha = if (dark) 0.70f else 0.55f
    val astroColor = if (dark) 0xFF64C8C0.toInt() else 0xFF2A8F88.toInt()

    val labelFuture = withAlpha(ink, 0.92f)
    val labelPast = withAlpha(ink, if (d.pastDim > 0f) 0.38f else 0.62f)
    val dayNumFuture = withAlpha(ink, 0.55f)
    val dayNumPast = withAlpha(ink, if (d.pastDim > 0f) 0.25f else 0.40f)

    val eventPastAlpha = 1f - 0.55f * (0.35f + 0.65f * d.pastDim)
    val eventFutureAlpha = 1f

    /** Цвет события по выбранной палитре. */
    fun eventColor(raw: Int, calendarId: String): Int = when (d.palette) {
        1 -> nearestSoft(raw)
        2 -> {
            val t = floatArrayOf(-0.25f, 0f, 0.2f, -0.12f, 0.32f, 0.1f)[Math.floorMod(calendarId.hashCode(), 6)]
            if (dark) { if (t < 0) mix(accent, BLACK, -t * 0.6f) else mix(accent, WHITE, t + 0.12f) }
            else { if (t < 0) mix(accent, BLACK, -t) else mix(accent, WHITE, t) }
        }
        3 -> if (dark) mix(WHITE, BLACK, floatArrayOf(0f, 0.12f, 0.24f)[Math.floorMod(calendarId.hashCode(), 3)])
             else mix(0xFF141414.toInt(), WHITE, floatArrayOf(0f, 0.25f, 0.45f)[Math.floorMod(calendarId.hashCode(), 3)])
        else -> raw
    }

    val outlineColor: Int = if (dark) bd.bg1 else WHITE

    private fun nearestSoft(c: Int): Int {
        val h = colorToHsl(c)
        if (h[1] < 12f) return mix(0xFFB8B2D6.toInt(), c, 0.2f) // серые календари — нейтральный сиреневый
        var best = SOFT_PALETTE[0]; var bd = 1e9f
        for (p in SOFT_PALETTE) {
            val ph = colorToHsl(p)[0]
            var dh = abs(ph - h[0]); if (dh > 180) dh = 360 - dh
            if (dh < bd) { bd = dh; best = p }
        }
        return best
    }
}
