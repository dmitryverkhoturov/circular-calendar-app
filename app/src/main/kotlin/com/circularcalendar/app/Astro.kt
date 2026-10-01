package com.circularcalendar.app

import java.time.Duration
import java.time.LocalDateTime
import java.time.ZoneId
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.math.sqrt

// ─────────────────────────────────────────────────────────────────────────────
// Астрономия: положения планет (кеплеровы элементы JPL «Approximate Positions
// of the Major Planets», 1800–2050), Луна (упрощённая теория), и TimeMap —
// отображение «время года → угол на круге» (равномерно или по Кеплеру).
// Всё в плоскости эклиптики, наклоны орбит проецируются (схематично).
// ─────────────────────────────────────────────────────────────────────────────

const val AU_KM = 149_597_870.7

private const val DEG = PI / 180.0

private fun wrapPi(a: Double): Double {
    var x = a % (2 * PI)
    if (x > PI) x -= 2 * PI
    if (x < -PI) x += 2 * PI
    return x
}

class Planet(
    val name: String,
    val radiusKm: Double,
    val color: Int,
    val nightColor: Int,
    // a, e, I, L, ϖ, Ω и их скорости за юлианское столетие (градусы/а.е.)
    private val el: DoubleArray,
    private val rate: DoubleArray,
) {
    /** Элементы орбиты на дату (радианы). */
    class Elements(
        val a: Double, val e: Double, val inc: Double,
        val node: Double, val argPeri: Double, val lMean: Double,
    ) {
        val peri get() = node + argPeri
    }

    class Orbit(val el: Elements, val lambda: Double, val r: Double)

    fun elements(jd: Double): Elements {
        val t = (jd - 2451545.0) / 36525.0
        val node = (el[5] + rate[5] * t) * DEG
        return Elements(
            a = el[0] + rate[0] * t,
            e = el[1] + rate[1] * t,
            inc = (el[2] + rate[2] * t) * DEG,
            node = node,
            argPeri = (el[4] + rate[4] * t) * DEG - node,
            lMean = (el[3] + rate[3] * t) * DEG,
        )
    }

    /**
     * Точка орбиты при истинной аномалии [nu], спроецированная на эклиптику:
     * [out] = (x, y) в а.е. Та же проекция, что у положения планеты, —
     * поэтому планета лежит РОВНО на нарисованной орбите.
     */
    fun pointAt(o: Elements, nu: Double, out: DoubleArray) {
        val r = o.a * (1 - o.e * o.e) / (1 + o.e * cos(nu))
        val u = o.argPeri + nu
        val cu = cos(u); val su = sin(u)
        val cn = cos(o.node); val sn = sin(o.node); val ci = cos(o.inc)
        out[0] = r * (cn * cu - sn * su * ci)
        out[1] = r * (sn * cu + cn * su * ci)
    }

    /** Гелиоцентрическое положение на дату (λ — НЕ обёрнутая долгота). */
    fun at(jd: Double): Orbit {
        val o = elements(jd)
        val m = wrapPi(o.lMean - o.peri)
        val ea = Astro.solveKepler(m, o.e)
        val nu = 2 * atan2(sqrt(1 + o.e) * sin(ea / 2), sqrt(1 - o.e) * cos(ea / 2))
        val p = DoubleArray(2)
        pointAt(o, nu, p)
        // Непрерывная долгота: средняя (линейна во времени) + уравнение центра.
        val lam = o.lMean + wrapPi(atan2(p[1], p[0]) - o.lMean)
        return Orbit(o, lam, hypot(p[0], p[1]))
    }
}

object Astro {
    fun jd(t: LocalDateTime): Double {
        val ms = t.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        return ms / 86_400_000.0 + 2440587.5
    }

    fun solveKepler(m: Double, e: Double): Double {
        var ea = m + e * sin(m)
        repeat(8) { ea -= (ea - e * sin(ea) - m) / (1 - e * cos(ea)) }
        return ea
    }

    val MERCURY = Planet(
        "Меркурий", 2439.7, 0xFFA8A49C.toInt(), 0xFF2A2826.toInt(),
        doubleArrayOf(0.38709927, 0.20563593, 7.00497902, 252.25032350, 77.45779628, 48.33076593),
        doubleArrayOf(0.00000037, 0.00001906, -0.00594749, 149472.67411175, 0.16047689, -0.12534081),
    )
    val VENUS = Planet(
        "Венера", 6051.8, 0xFFEBD9A8.toInt(), 0xFF3A3326.toInt(),
        doubleArrayOf(0.72333566, 0.00677672, 3.39467605, 181.97909950, 131.60246718, 76.67984255),
        doubleArrayOf(0.00000390, -0.00004107, -0.00078890, 58517.81538729, 0.00268329, -0.27769418),
    )
    val EARTH = Planet(
        "Земля", 6371.0, 0xFF3D7FE0.toInt(), 0xFF0D1A33.toInt(),
        doubleArrayOf(1.00000261, 0.01671123, -0.00001531, 100.46457166, 102.93768193, 0.0),
        doubleArrayOf(0.00000562, -0.00004392, -0.01294668, 35999.37244981, 0.32327364, 0.0),
    )
    val MARS = Planet(
        "Марс", 3389.5, 0xFFD0603A.toInt(), 0xFF2E1610.toInt(),
        doubleArrayOf(1.52371034, 0.09339410, 1.84969142, -4.55343205, -23.94362959, 49.55953891),
        doubleArrayOf(0.00001847, 0.00007882, -0.00813131, 19140.30268499, 0.44441088, -0.29257343),
    )
    val JUPITER = Planet(
        "Юпитер", 69911.0, 0xFFD9B98E.toInt(), 0xFF332A20.toInt(),
        doubleArrayOf(5.20288700, 0.04838624, 1.30439695, 34.39644051, 14.72847983, 100.47390909),
        doubleArrayOf(-0.00011607, -0.00013253, -0.00183714, 3034.74612775, 0.21252668, 0.20469106),
    )
    val SATURN = Planet(
        "Сатурн", 58232.0, 0xFFE6D3A0.toInt(), 0xFF332E22.toInt(),
        doubleArrayOf(9.53667594, 0.05386179, 2.48599187, 49.95424423, 92.59887831, 113.66242448),
        doubleArrayOf(-0.00125060, -0.00050991, 0.00193609, 1222.49362201, -0.41897216, -0.28867794),
    )
    val URANUS = Planet(
        "Уран", 25362.0, 0xFFA6DCE3.toInt(), 0xFF1E3033.toInt(),
        doubleArrayOf(19.18916464, 0.04725744, 0.77263783, 313.23810451, 170.95427630, 74.01692503),
        doubleArrayOf(-0.00196176, -0.00004397, -0.00242939, 428.48202785, 0.40805281, 0.04240589),
    )
    val NEPTUNE = Planet(
        "Нептун", 24622.0, 0xFF5B7FE6.toInt(), 0xFF121A33.toInt(),
        doubleArrayOf(30.06992276, 0.00859048, 1.77004347, -55.12002969, 44.96476227, 131.78422574),
        doubleArrayOf(0.00026291, 0.00005105, 0.00035372, 218.45945325, -0.32241464, -0.05010030),
    )

    val OTHER_PLANETS = listOf(MERCURY, VENUS, MARS, JUPITER, SATURN, URANUS, NEPTUNE)

    const val SUN_RADIUS_KM = 695_700.0
    const val MOON_RADIUS_KM = 1737.4

    /** Луна: геоцентрическая эклиптическая долгота (рад) и расстояние (км). */
    fun moon(jd: Double): DoubleArray {
        val d = jd - 2451545.0
        val l = (218.316 + 13.176396 * d) * DEG
        val m = (134.963 + 13.064993 * d) * DEG
        val ms = (357.529 + 0.98560028 * d) * DEG
        val dd = (297.850 + 12.190749 * d) * DEG // элонгация
        val lam = l + (6.289 * sin(m) + 1.274 * sin(2 * dd - m) + 0.658 * sin(2 * dd) -
            0.186 * sin(ms) + 0.214 * sin(2 * m)) * DEG
        val dist = 385001.0 - 20905.0 * cos(m) - 3699.0 * cos(2 * dd - m) - 2956.0 * cos(2 * dd)
        return doubleArrayOf(lam, dist)
    }
}

/**
 * Время года ↔ угол на круге. frac — доля календарного года (0..1 текущий,
 * 1..2 следующий; можно и за пределами). Угол — в пространстве контента
 * (до поворота камеры).
 *
 * Что движется ([arrowMoves]): false — круг, «сейчас» всегда на [anchor];
 * true — круг неподвижен (1 января на [anchor]), движется стрелка.
 *
 * Равномерно: угол линеен по времени. Кеплер: угол = реальная гелиоцентрическая
 * долгота Земли (быстрее в январе у перигелия), нормированная так, чтобы
 * календарный год был ровно 2π — без шва на 1 января.
 */
class TimeMap(
    now: LocalDateTime,
    val counterClockwise: Boolean,
    val nowAtBottom: Boolean,
    val kepler: Boolean,
    val arrowMoves: Boolean = false,
) {
    val year = now.year
    val yearStart: LocalDateTime = LocalDateTime.of(year, 1, 1, 0, 0)
    val totalSec = Duration.between(yearStart, LocalDateTime.of(year + 1, 1, 1, 0, 0)).seconds.toDouble()
    val totalDays = (totalSec / 86400.0 + 0.5).toInt()
    val nowFrac = Duration.between(yearStart, now).toNanos() / 1e9 / totalSec
    val refFrac = if (arrowMoves) 0.0 else nowFrac
    val dir = if (counterClockwise) 1.0 else -1.0
    val anchor = if (nowAtBottom) PI / 2 else -PI / 2

    private val jd0 = Astro.jd(yearStart)
    private val daysInYear = totalSec / 86400.0
    private val lam0 = Astro.EARTH.at(jd0).lambda
    private val keplerScale = 2 * PI / (Astro.EARTH.at(jd0 + daysInYear).lambda - lam0)
    private val refK = k(refFrac)

    val jdNow = jd0 + nowFrac * daysInYear
    val earthNow = Astro.EARTH.at(jdNow)
    /** Расстояние Земля–Солнце в а.е. для отрисовки (в равномерном режиме — 1). */
    val earthDistAu = if (kepler) earthNow.r else 1.0

    private fun k(f: Double): Double =
        if (kepler) (Astro.EARTH.at(jd0 + f * daysInYear).lambda - lam0) * keplerScale
        else 2 * PI * f

    fun fracOf(t: LocalDateTime): Double =
        Duration.between(yearStart, t).seconds.toDouble() / totalSec

    /** Угловое смещение от опорного момента (рад, по ходу времени). */
    fun offsetOf(f: Double): Double = k(f) - refK

    fun angleOf(f: Double): Double = anchor - dir * offsetOf(f)

    /** Угол стрелки «сейчас» (в режиме «движется круг» = [anchor]). */
    val nowAngle = angleOf(nowFrac)

    /** Обратное к [angleOf] (ветвь, ближайшая к линейной оценке). */
    fun fracOfAngle(a: Double): Double {
        val off = dir * (anchor - a)
        var f = refFrac + off / (2 * PI)
        if (!kepler) return f
        repeat(4) {
            val g = offsetOf(f) - off
            val d = (offsetOf(f + 1e-4) - offsetOf(f)) / 1e-4
            f -= g / d
        }
        return f
    }

    /** Угол контента для направления с эклиптической долготой [lam] (Земля — на стрелке). */
    fun spaceAngle(lam: Double): Double = nowAngle - dir * (lam - earthNow.lambda)

    /** Обратное к [spaceAngle]. */
    fun lamOfAngle(a: Double): Double = earthNow.lambda + dir * (nowAngle - a)
}
