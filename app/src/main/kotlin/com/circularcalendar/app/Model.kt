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
    val counterClockwise: Boolean = false,
    val nowAtBottom: Boolean = false,
    val design: Design = Design(),
    val calendarFilter: CalendarFilter = CalendarFilter(),
    // Крупные события: false = «снизу», у кольца дней (укладка как в v0.4);
    // true = «сверху» — иерархия длительности: из двух пересекающихся
    // более длинное уходит дальше от кольца, короткие остаются у кольца.
    val largeEventsOnTop: Boolean = true,
    // Орбита: false = равномерно (дни равной ширины), true = по Кеплеру
    // (реальная угловая скорость Земли, расстояние до Солнца меняется).
    val keplerOrbit: Boolean = false,
    // Показывать орбиты планет (пасхалка, по умолчанию выключено).
    val showOrbits: Boolean = false,
    // Что движется со временем: false = круг (сейчас всегда внизу),
    // true = стрелка (круг неподвижен, стрелка и планеты идут сами).
    val arrowMoves: Boolean = false,
) {
    val look: Look by lazy { Look(design) }

    fun copy(
        counterClockwise: Boolean = this.counterClockwise,
        nowAtBottom: Boolean = this.nowAtBottom,
        design: Design = this.design,
        calendarFilter: CalendarFilter = this.calendarFilter,
        largeEventsOnTop: Boolean = this.largeEventsOnTop,
        keplerOrbit: Boolean = this.keplerOrbit,
        showOrbits: Boolean = this.showOrbits,
        arrowMoves: Boolean = this.arrowMoves,
    ) = WheelSettings(
        counterClockwise, nowAtBottom, design, calendarFilter, largeEventsOnTop, keplerOrbit,
        showOrbits, arrowMoves,
    )
}

/**
 * Параметры анимации — крутятся ползунками в настройках.
 * [friction] — затухание инерции (1/с, больше = быстрее останавливается);
 * [launch] — доля скорости броска, переходящая в инерцию;
 * [spring] — жёсткость пружин (отскок от пределов, анимации зума), рад/с;
 * [damping] — демпфирование пружин (1 = без перелёта, меньше = упруже);
 * [boost] — разгон: доля текущей инерции, добавляемая повторным броском;
 * [rotThreshold] — порог вращения двумя пальцами (градусы поворота пальцев);
 * [rotZoomK] — насколько порог растёт при одновременном зуме (° на каждое ×2),
 *   чтобы пинч не крутил круг случайно;
 * [rotCatchUp] — скорость, с которой круг «догоняет» пальцы после порога (1/с).
 */
class MotionSettings(
    val friction: Double = 7.0,
    val launch: Double = 1.0,
    val spring: Double = 22.0,
    val damping: Double = 0.8,
    val boost: Double = 0.8,
    val rotThreshold: Double = 8.0,
    val rotZoomK: Double = 25.0,
    val rotCatchUp: Double = 14.0,
) {
    fun copy(
        friction: Double = this.friction,
        launch: Double = this.launch,
        spring: Double = this.spring,
        damping: Double = this.damping,
        boost: Double = this.boost,
        rotThreshold: Double = this.rotThreshold,
        rotZoomK: Double = this.rotZoomK,
        rotCatchUp: Double = this.rotCatchUp,
    ) = MotionSettings(friction, launch, spring, damping, boost, rotThreshold, rotZoomK, rotCatchUp)
}

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
) {
    /** Кэш раскладки названия (пишет только поток записи сцены). */
    var title: TitleLayout? = null
}

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
// Мок-события: «выдуманный пользователь» на всё окно ±6 мес от сегодня —
// круг заполнен при любой дате (демо-режим, скриншоты, нет доступа к календарю).
// ─────────────────────────────────────────────────────────────────────────────

fun buildMockEvents(@Suppress("UNUSED_PARAMETER") year: Int): List<CalendarEvent> {
    val blue = 0xFF4A90D9.toInt()
    val green = 0xFF27AE60.toInt()
    val orange = 0xFFE67E22.toInt()
    val purple = 0xFF8E44AD.toInt()
    val teal = 0xFF16A085.toInt()
    val red = 0xFFE74C3C.toInt()

    val today = LocalDateTime.now().toLocalDate().atStartOfDay()
    val m0 = today.withDayOfMonth(1)
    // Месяц со сдвигом от текущего, день обрезается по длине месяца.
    fun d(month: Long, day: Int): LocalDateTime {
        val m = m0.plusMonths(month)
        return m.withDayOfMonth(day.coerceIn(1, m.toLocalDate().lengthOfMonth()))
    }
    fun t(day: Long) = today.plusDays(day)
    var n = 0
    fun ev(en: String, ru: String, a: LocalDateTime, b: LocalDateTime, c: Int, cal: String, allDay: Boolean = false) =
        CalendarEvent("m${n++}", tr(en, ru), a, b, c, cal, allDay)

    // Новый год в горах — на ближайшие 29.12–04.01 (всегда в окне ±6 мес вокруг осени/зимы).
    val nyYear = if (today.monthValue >= 7) today.year else today.year - 1
    val ny = LocalDateTime.of(nyYear, 12, 29, 0, 0)

    return listOf(
        ev("English course", "Курс английского", d(-6, 1), d(-5, 1), green, "edu"),
        ev("Birthday", "День рождения", d(-5, 17), d(-5, 18), purple, "personal", true),
        ev("Hackathon", "Хакатон", d(-5, 25), d(-5, 28), teal, "work"),
        ev("Running", "Пробежки", d(-4, 1), d(-1, 1), teal, "health"),
        ev("Apartment renovation", "Ремонт квартиры", d(-4, 15), d(-3, 10), red, "personal"),
        ev("Diet", "Диета", d(-3, 15), d(-2, 15), orange, "health"),
        ev("Team offsite", "Встреча команды", d(-3, 24), d(-3, 27), blue, "work"),
        ev("Vacation: seaside", "Отпуск: море", d(-2, 14), d(-2, 28), teal, "personal"),
        ev("Friend's birthday", "День рождения друга", d(-1, 3), d(-1, 4), green, "personal", true),
        ev("Course: module 2", "Учёба: модуль 2", t(-10), t(12), orange, "edu"),
        ev("Business trip", "Командировка", t(4), t(9), blue, "work"),
        ev("Project deadline", "Дедлайн проекта", t(14), t(15), red, "work", true),
        ev("Car inspection", "Техосмотр", d(1, 5), d(1, 7), orange, "personal"),
        ev("Project Alpha", "Проект Alpha", d(1, 10), d(1, 28), blue, "work"),
        ev("Conference", "Конференция", d(1, 18), d(1, 22), green, "work"),
        ev("New Year in the mountains", "Новый год в горах", ny, ny.plusDays(6), purple, "personal"),
        ev("Mom's birthday", "День рождения мамы", d(4, 14), d(4, 15), purple, "personal", true),
        ev("Vacation: hiking", "Отпуск: поход", d(5, 2), d(5, 12), teal, "personal"),
    ) + shortMock(blue, green, orange, teal)
}

/** Короткие (< дня) события вокруг «сегодня» — чтобы мок показывал и их. */
private fun shortMock(vararg c: Int): List<CalendarEvent> {
    val t = LocalDateTime.now().toLocalDate().atStartOfDay()
    fun at(day: Long, h: Int, m: Int) = t.plusDays(day).withHour(h).withMinute(m)
    return listOf(
        CalendarEvent("s1", tr("Team call", "Созвон с командой"), at(0, 10, 0), at(0, 11, 0), c[0], "work"),
        CalendarEvent("s2", tr("Lunch", "Обед"), at(0, 13, 0), at(0, 14, 0), c[1], "personal"),
        CalendarEvent("s3", tr("Dentist", "Стоматолог"), at(1, 9, 30), at(1, 10, 15), c[2], "personal"),
        CalendarEvent("s4", tr("Workout", "Тренировка"), at(1, 18, 0), at(1, 19, 30), c[3], "health"),
        CalendarEvent("s5", tr("Review", "Ревью"), at(1, 18, 30), at(1, 19, 0), c[0], "work"),
        CalendarEvent("s6", tr("Call mom", "Звонок маме"), at(2, 20, 0), at(2, 20, 15), c[1], "personal"),
        CalendarEvent("s7", tr("Yoga", "Йога"), at(-1, 8, 0), at(-1, 9, 0), c[3], "health"),
    )
}

/**
 * Отладка: тяжёлый календарь как на реальном телефоне — тысячи экземпляров
 * повторяющихся событий за ±1 год (adb: --ez stress true).
 */
fun buildStressEvents(year: Int): List<CalendarEvent> {
    val cols = intArrayOf(0xFF4A90D9.toInt(), 0xFF27AE60.toInt(), 0xFFE67E22.toInt(), 0xFF8E44AD.toInt(), 0xFF16A085.toInt(), 0xFFE74C3C.toInt())
    val rnd = java.util.Random(5)
    val out = ArrayList<CalendarEvent>()
    var day = LocalDateTime.of(year - 1, 7, 1, 0, 0)
    val end = LocalDateTime.of(year + 1, 7, 1, 0, 0)
    var n = 0
    val names = arrayOf(tr("Standup", "Стендап"), tr("Team call", "Созвон с командой"), tr("Lunch", "Обед"), tr("Workout", "Тренировка"), tr("Sprint planning", "Планирование спринта"), tr("1:1 with manager", "1:1 с руководителем"), tr("English", "Английский"), tr("Commute", "Дорога"), tr("Design review", "Ревью дизайна"), tr("Yoga", "Йога"))
    while (day < end) {
        val dow = day.dayOfWeek.value
        if (dow <= 5) {
            out.add(CalendarEvent("w${n++}", tr("Standup", "Стендап"), day.withHour(10), day.withHour(10).withMinute(15), cols[0], "work"))
            out.add(CalendarEvent("w${n++}", tr("Lunch", "Обед"), day.withHour(13), day.withHour(14), cols[1], "personal"))
            repeat(rnd.nextInt(4)) {
                val h = 11 + rnd.nextInt(7)
                val len = 30L * (1 + rnd.nextInt(3))
                out.add(CalendarEvent("w${n++}", names[rnd.nextInt(names.size)], day.withHour(h), day.withHour(h).plusMinutes(len), cols[rnd.nextInt(6)], "work"))
            }
        }
        if (dow == 2 || dow == 4) out.add(CalendarEvent("w${n++}", tr("Workout", "Тренировка"), day.withHour(19), day.withHour(20).withMinute(30), cols[4], "health"))
        if (rnd.nextInt(12) == 0) out.add(CalendarEvent("w${n++}", tr("Birthday", "День рождения"), day, day.plusDays(1), cols[3], "personal", true))
        if (rnd.nextInt(25) == 0) out.add(CalendarEvent("w${n++}", tr("Trip", "Поездка"), day, day.plusDays(2L + rnd.nextInt(6)), cols[5], "personal"))
        day = day.plusDays(1)
    }
    return out + buildMockEvents(year)
}

/** Строка на языке системы для текста вне Activity (мок-события и т.п.). UI — через res/values. */
fun tr(en: String, ru: String): String =
    if (java.util.Locale.getDefault().language == "ru") ru else en
