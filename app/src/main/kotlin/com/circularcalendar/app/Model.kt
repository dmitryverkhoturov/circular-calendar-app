package com.circularcalendar.app

import java.time.Duration
import java.time.LocalDateTime

// ─────────────────────────────────────────────────────────────────────────────
// Модель события / календаря / фильтра / настроек.
// Прямой порт из Flutter-версии (main.dart).
// ─────────────────────────────────────────────────────────────────────────────

class CalendarEvent(
    val id: String,
    val title: String,
    val start: LocalDateTime,
    val end: LocalDateTime,
    val color: Int,
    val calendarId: String = "",
    val allDay: Boolean = false,
)

class CalendarInfo(
    val id: String,
    val name: String,
    val color: Int,
    // Диагностические поля (только натив; во Flutter их не было)
    val visible: Boolean = true,
    val accountName: String = "",
    val accountType: String = "",
)

/** null = показывать все; иначе — белый список ID. */
class CalendarFilter(val allowedIds: Set<String>? = null) {

    fun allows(e: CalendarEvent): Boolean =
        allowedIds == null || allowedIds.contains(e.calendarId)

    /** Тоглит ID. Семантика идентична Flutter-версии. */
    fun withToggled(id: String, allIds: Set<String>): CalendarFilter {
        if (allowedIds == null) {
            val next = allIds.toMutableSet().apply { remove(id) }
            return CalendarFilter(next) // пусто → «показывать ничего» ({} ≠ null)
        }
        val next = allowedIds.toMutableSet()
        if (!next.remove(id)) next.add(id)
        if (next.size >= allIds.size && next.containsAll(allIds)) return CalendarFilter()
        return CalendarFilter(next)
    }
}

class WheelSettings(
    val counterClockwise: Boolean = true,
    val nowAtBottom: Boolean = true,
    val themeIndex: Int = 0,
    val calendarFilter: CalendarFilter = CalendarFilter(),
    // Крупные события: false = «снизу», у кольца дней (укладка как в v0.4);
    // true = «сверху» — иерархия длительности: из двух пересекающихся
    // более длинное уходит дальше от кольца, короткие остаются у кольца.
    val largeEventsOnTop: Boolean = false,
    // Орбита: false = равномерно (дни равной ширины), true = по Кеплеру
    // (реальная угловая скорость Земли, расстояние до Солнца меняется).
    val keplerOrbit: Boolean = false,
) {
    val theme: WheelTheme get() = THEMES[themeIndex.coerceIn(0, THEMES.size - 1)]

    fun copy(
        counterClockwise: Boolean = this.counterClockwise,
        nowAtBottom: Boolean = this.nowAtBottom,
        themeIndex: Int = this.themeIndex,
        calendarFilter: CalendarFilter = this.calendarFilter,
        largeEventsOnTop: Boolean = this.largeEventsOnTop,
        keplerOrbit: Boolean = this.keplerOrbit,
    ) = WheelSettings(
        counterClockwise, nowAtBottom, themeIndex, calendarFilter, largeEventsOnTop, keplerOrbit,
    )
}

/**
 * Параметры анимации — крутятся ползунками в настройках.
 * [friction] — затухание инерции (1/с, больше = быстрее останавливается);
 * [launch] — доля скорости броска, переходящая в инерцию;
 * [spring] — жёсткость пружин (отскок от пределов, анимации зума), рад/с;
 * [damping] — демпфирование пружин (1 = без перелёта, меньше = упруже);
 * [boost] — разгон: доля текущей инерции, добавляемая повторным броском.
 */
class MotionSettings(
    val friction: Double = 7.0,
    val launch: Double = 1.0,
    val spring: Double = 22.0,
    val damping: Double = 0.8,
    val boost: Double = 0.8,
)

// ─────────────────────────────────────────────────────────────────────────────
// Послойная укладка событий в frac-пространстве (0..1 текущий год, 1..2 —
// следующий, для переката года). Порт _layerEvents + _buildLayeredForBothYears.
// ─────────────────────────────────────────────────────────────────────────────

class EventBand(
    val event: CalendarEvent,
    val startFrac: Double,
    val endFrac: Double,
    var layer: Int = 0,
    // Короткие (< дня) пересекающиеся события живут в одном ряду: ряд режется
    // по радиусу на [lanes] дорожек, событие — в дорожке [lane] (0 = у кольца).
    var lane: Int = 0,
    var lanes: Int = 1,
)

/** Граница «крупного» события: занимает на круге не меньше суток. */
const val LARGE_BAND_FRAC = 1.0 / 366.0

/**
 * Строит ленточки с правилом окна ±6 месяцев от «сейчас»: каждый угловой слот
 * показывает вхождение, ближайшее по времени. В середине года прошлое видно
 * приглушённым; при подходе к концу года дальнее прошлое замещается событиями
 * следующего года (перекат, SPEC §5) — БЕЗ наложения двух копий на одном угле.
 */
fun buildEventBands(
    events: List<CalendarEvent>,
    year: Int,
    nowFrac: Double,
    largeOnTop: Boolean = false,
): List<EventBand> {
    val yearStart = LocalDateTime.of(year, 1, 1, 0, 0)
    val nextYearStart = LocalDateTime.of(year + 1, 1, 1, 0, 0)
    val nextYearEnd = LocalDateTime.of(year + 2, 1, 1, 0, 0)
    val totalSec = Duration.between(yearStart, nextYearStart).seconds.toDouble()
    val nextTotalSec = Duration.between(nextYearStart, nextYearEnd).seconds.toDouble()

    fun frac(d: LocalDateTime): Double =
        Duration.between(yearStart, d).seconds.toDouble() / totalSec

    fun fracNext(d: LocalDateTime): Double =
        Duration.between(nextYearStart, d).seconds.toDouble() / nextTotalSec

    val bands = ArrayList<EventBand>()
    for (e in events) {
        // Копия текущего года — если событие закончилось не дальше 6 мес. назад.
        if (e.start.year == year || (e.start.year < year && e.end.isAfter(yearStart))) {
            val s = frac(e.start).coerceIn(0.0, 1.0)
            val en = frac(e.end).coerceIn(0.0, 1.0)
            if (en - s > 1e-9 && en > nowFrac - 0.5) bands.add(EventBand(e, s, en))
        }
        // Копия следующего года (перекат) — если начало не дальше 6 мес. вперёд.
        if (e.start.year == year + 1 || (e.start.year == year && e.end.isAfter(nextYearStart))) {
            val s = (1.0 + fracNext(e.start)).coerceIn(1.0, 2.0)
            val en = (1.0 + fracNext(e.end)).coerceIn(1.0, 2.0)
            if (en - s > 1e-9 && s < nowFrac + 0.5) bands.add(EventBand(e, s, en))
        }
    }

    // Короткие пересекающиеся — в один ряд: кластер укладывается как одно
    // целое (интервал-объединение), внутри кластера — дорожки по радиусу.
    val units = ArrayList<EventBand>()
    val members = HashMap<EventBand, List<EventBand>>()
    val small = bands.filter { it.endFrac - it.startFrac < LARGE_BAND_FRAC }
        .sortedBy { it.startFrac }
    bands.filterTo(units) { it.endFrac - it.startFrac >= LARGE_BAND_FRAC }
    var i = 0
    while (i < small.size) {
        var j = i + 1
        var end = small[i].endFrac
        while (j < small.size && small[j].startFrac < end - 1e-12) {
            if (small[j].endFrac > end) end = small[j].endFrac
            j++
        }
        val cluster = small.subList(i, j)
        if (cluster.size == 1) {
            units.add(cluster[0])
        } else {
            val laneEnds = ArrayList<Double>()
            for (b in cluster) {
                var l = laneEnds.indexOfFirst { it <= b.startFrac + 1e-12 }
                if (l < 0) { laneEnds.add(b.endFrac); l = laneEnds.size - 1 } else laneEnds[l] = b.endFrac
                b.lane = l
            }
            for (b in cluster) b.lanes = laneEnds.size
            val proxy = EventBand(cluster[0].event, cluster[0].startFrac, end)
            members[proxy] = cluster.toList()
            units.add(proxy)
        }
        i = j
    }

    if (largeOnTop) assignLayersByDuration(units) else assignLayers(units)
    for ((proxy, list) in members) for (b in list) b.layer = proxy.layer
    return bands
}

// Жадная укладка: сортируем по началу, длиннее — первее;
// слой = первый, где событие не пересекает конец предыдущего.
private fun assignLayers(bands: MutableList<EventBand>) {
    bands.sortWith(compareBy<EventBand> { it.startFrac }.thenByDescending { it.endFrac })
    val layerEnds = ArrayList<Double>()
    for (b in bands) {
        var layer = 0
        while (true) {
            if (layer >= layerEnds.size) {
                layerEnds.add(b.endFrac); break
            }
            if (b.startFrac >= layerEnds[layer] - 1e-12) {
                layerEnds[layer] = b.endFrac; break
            }
            layer++
        }
        b.layer = layer
    }
}

// Укладка «по приоритету длительности» (режим «крупные сверху»): короткие
// события первыми занимают полосу у кольца дней; из двух пересекающихся
// более длинное уходит дальше (глубже). Слой = первый без пересечений.
private fun assignLayersByDuration(bands: List<EventBand>) {
    val sorted = bands.sortedWith(
        compareBy<EventBand>({ it.endFrac - it.startFrac }, { it.startFrac }),
    )
    // На слой — интервалы, отсортированные по началу (вставка бинпоиском).
    val starts = ArrayList<ArrayList<Double>>()
    val ends = ArrayList<ArrayList<Double>>()
    for (b in sorted) {
        var layer = 0
        while (true) {
            if (layer >= starts.size) {
                starts.add(arrayListOf(b.startFrac))
                ends.add(arrayListOf(b.endFrac))
                break
            }
            val ss = starts[layer]
            val ee = ends[layer]
            var idx = ss.binarySearch(b.startFrac)
            if (idx < 0) idx = -idx - 1
            val freeLeft = idx == 0 || ee[idx - 1] <= b.startFrac + 1e-12
            val freeRight = idx == ss.size || ss[idx] >= b.endFrac - 1e-12
            if (freeLeft && freeRight) {
                ss.add(idx, b.startFrac)
                ee.add(idx, b.endFrac)
                break
            }
            layer++
        }
        b.layer = layer
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Мок-события — тот же набор, что во Flutter-версии.
// ─────────────────────────────────────────────────────────────────────────────

fun buildMockEvents(year: Int): List<CalendarEvent> {
    val blue = 0xFF4A90D9.toInt()
    val green = 0xFF27AE60.toInt()
    val orange = 0xFFE67E22.toInt()
    val purple = 0xFF8E44AD.toInt()
    val teal = 0xFF16A085.toInt()
    val red = 0xFFE74C3C.toInt()

    fun d(y: Int, m: Int, day: Int) = LocalDateTime.of(y, m, day, 0, 0)

    return listOf(
        CalendarEvent("m1", "Отпуск: море", d(year, 7, 14), d(year, 7, 28), teal, "personal"),
        CalendarEvent("m2", "Командировка", d(year, 3, 10), d(year, 3, 17), blue, "work"),
        CalendarEvent("m3", "Новый год в горах", d(year, 12, 29), d(year + 1, 1, 4), purple, "personal"),
        CalendarEvent("m4", "Учёба: модуль 2", d(year, 9, 1), d(year, 9, 30), orange, "edu"),
        CalendarEvent("m5", "Ремонт квартиры", d(year, 5, 15), d(year, 6, 10), red, "personal"),
        CalendarEvent("m6", "Проект Alpha", d(year, 2, 1), d(year, 2, 20), blue, "work"),
        CalendarEvent("m7", "Конференция", d(year, 2, 10), d(year, 2, 15), green, "work"),
        CalendarEvent("m8", "Воркшоп", d(year, 2, 12), d(year, 2, 13), orange, "work"),
        CalendarEvent("m9", "День рождения", d(year, 4, 17), d(year, 4, 18), purple, "personal", true),
        CalendarEvent("m10", "День рождения друга", d(year, 8, 3), d(year, 8, 4), green, "personal", true),
        CalendarEvent("m11", "Дедлайн проекта", d(year, 10, 31), d(year, 11, 1), red, "work", true),
        CalendarEvent("m12", "Встреча команды", d(year, 6, 24), d(year, 6, 26), blue, "work"),
        CalendarEvent("m13", "Техосмотр", d(year, 11, 5), d(year, 11, 7), orange, "personal"),
        CalendarEvent("m14", "Хакатон", d(year, 4, 25), d(year, 4, 28), teal, "work"),
        CalendarEvent("m15", "Курс английского", d(year, 6, 1), d(year, 6, 30), green, "edu"),
        CalendarEvent("m16", "Пробежки", d(year, 6, 1), d(year, 8, 31), teal, "health"),
        CalendarEvent("m17", "Диета", d(year, 6, 15), d(year, 7, 15), orange, "health"),
    )
}
