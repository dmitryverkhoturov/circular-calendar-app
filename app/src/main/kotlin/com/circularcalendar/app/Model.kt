package com.circularcalendar.app

import java.time.Duration
import java.time.LocalDateTime
import kotlin.math.abs

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
    // Дата в том году, где она ближе всего к сегодня (окно ±6 мес) — сезонные
    // события остаются в своих месяцах при любой дате запуска.
    fun on(m: Int, day: Int): LocalDateTime = (-1..1)
        .map { LocalDateTime.of(today.year + it, m, day, 0, 0) }
        .minBy { abs(Duration.between(today, it).toDays()) }
    var n = 0
    // Событие с [m1/d1] по [m2/d2] включительно; конец в следующем году, если месяц меньше.
    fun ev(en: String, ru: String, m1: Int, d1: Int, m2: Int, d2: Int, c: Int, cal: String): CalendarEvent {
        val s = on(m1, d1)
        var e = LocalDateTime.of(s.year, m2, d2, 0, 0).plusDays(1)
        if (e.isBefore(s)) e = e.plusYears(1)
        return CalendarEvent("m${n++}", tr(en, ru), s, e, c, cal)
    }
    fun day(en: String, ru: String, m: Int, d: Int, c: Int, cal: String) =
        on(m, d).let { CalendarEvent("m${n++}", tr(en, ru), it, it.plusDays(1), c, cal, true) }

    // Цвета по смыслу: поездки — оранжевый, семья и друзья — фиолетовый, спорт — зелёный,
    // хобби — бирюзовый, дом — красный, работа — синий.
    return listOf(
        ev("Trip to Rome", "Поездка в Рим", 4, 24, 4, 29, orange, "travel"),
        ev("Guitar lessons", "Уроки гитары", 5, 4, 6, 29, teal, "hobby"),
        day("Mom's birthday", "День рождения мамы", 5, 17, purple, "family"),
        ev("Moving to a new flat", "Переезд", 6, 5, 6, 8, red, "home"),
        day("Anna & Tom's wedding", "Свадьба Ани и Тома", 6, 20, purple, "family"),
        ev("Seaside vacation", "Отпуск на море", 7, 10, 7, 24, orange, "travel"),
        ev("Camping", "Поход", 8, 14, 8, 17, green, "sport"),
        ev("Music festival", "Музыкальный фестиваль", 8, 28, 8, 31, purple, "family"),
        ev("Marathon training", "Подготовка к марафону", 9, 1, 10, 25, green, "sport"),
        ev("Conference in Berlin", "Конференция в Берлине", 9, 15, 9, 18, blue, "work"),
        ev("Grandparents visiting", "Бабушка и дедушка в гостях", 10, 20, 10, 24, purple, "family"),
        day("City marathon", "Городской марафон", 10, 26, green, "sport"),
        day("Friend's birthday", "День рождения друга", 11, 8, purple, "family"),
        ev("Trip to Lisbon", "Поездка в Лиссабон", 11, 12, 11, 16, orange, "travel"),
        day("Project presentation", "Презентация проекта", 11, 25, blue, "work"),
        ev("Christmas with family", "Рождество с семьёй", 12, 24, 12, 26, red, "home"),
        ev("New Year in the mountains", "Новый год в горах", 12, 29, 1, 4, orange, "travel"),
        ev("Yoga retreat", "Йога-ретрит", 1, 16, 1, 19, green, "sport"),
        ev("Pottery class", "Курс керамики", 2, 2, 3, 30, teal, "hobby"),
        day("My birthday", "Мой день рождения", 2, 21, purple, "family"),
        ev("Trip to Japan", "Поездка в Японию", 3, 20, 4, 2, orange, "travel"),
        ev("Weekend in Prague", "Выходные в Праге", 10, 2, 10, 4, orange, "travel"),
        ev("Book fair", "Книжная ярмарка", 10, 9, 10, 11, teal, "hobby"),
        day("Halloween party", "Вечеринка на Хэллоуин", 10, 31, purple, "family"),
        ev("Painting the kitchen", "Красим кухню", 11, 20, 11, 22, red, "home"),
        day("Parents' anniversary", "Годовщина родителей", 12, 5, purple, "family"),
        ev("Ice skating season", "Сезон катка", 12, 1, 2, 28, green, "sport"),
        ev("Ski trip", "Поездка на лыжах", 2, 12, 2, 16, orange, "travel"),
        ev("Spring cleaning", "Весенняя уборка", 3, 7, 3, 8, red, "home"),
        ev("Garden season", "Сезон на даче", 5, 1, 9, 30, green, "home"),
        ev("Kids' summer camp", "Летний лагерь детей", 7, 27, 8, 7, purple, "family"),
        day("Dad's birthday", "День рождения папы", 9, 6, purple, "family"),
        ev("Language course", "Курс испанского", 9, 7, 12, 18, teal, "hobby"),
    ) + shortMock(purple, red, green, teal, orange, blue)
}

/**
 * Короткие (< дня) события: плотно вокруг «сегодня» (±10 дней — крупные кадры
 * по дням) плюс еженедельные дела на всё окно ±6 мес. Длительности 3–8 ч:
 * на крупном зуме подписи читаются.
 */
private fun shortMock(fam: Int, home: Int, sport: Int, hobby: Int, travel: Int, work: Int): List<CalendarEvent> {
    val t = LocalDateTime.now().toLocalDate().atStartOfDay()
    var n = 0
    fun e(day: Long, h: Int, m: Int, mins: Long, en: String, ru: String, col: Int, cal: String): CalendarEvent {
        val s = t.plusDays(day).withHour(h).withMinute(m)
        return CalendarEvent("s${n++}", tr(en, ru), s, s.plusMinutes(mins), col, cal)
    }
    val near = listOf(
        e(-9, 10, 0, 240, "Lunch with Max", "Обед с Максом", fam, "family"),
        e(-8, 17, 0, 240, "Swimming", "Бассейн", sport, "sport"),
        e(-7, 9, 0, 240, "Groceries", "Продукты", home, "home"),
        e(-6, 18, 0, 270, "Theatre", "Театр", fam, "family"),
        e(-5, 10, 0, 300, "Museum", "Музей", hobby, "hobby"),
        e(-4, 17, 0, 240, "Gym", "Спортзал", sport, "sport"),
        e(-3, 11, 0, 240, "Flea market", "Блошиный рынок", home, "home"),
        e(-2, 7, 0, 150, "Yoga", "Йога", sport, "sport"),
        e(-2, 11, 0, 210, "Book club", "Книжный клуб", hobby, "hobby"),
        e(-1, 9, 0, 180, "Coffee with Kate", "Кофе с Катей", fam, "family"),
        e(-1, 14, 0, 240, "Lunch with Ann", "Обед с Аней", fam, "family"),
        e(0, 9, 0, 210, "Piano lesson", "Урок фортепиано", hobby, "hobby"),
        e(0, 14, 0, 210, "Lunch with Max", "Обед с Максом", fam, "family"),
        e(0, 18, 30, 270, "Dinner with friends", "Ужин с друзьями", fam, "family"),
        e(1, 8, 30, 210, "Dentist", "Стоматолог", home, "home"),
        e(1, 13, 0, 240, "Bike ride", "Велопрогулка", sport, "sport"),
        e(1, 18, 30, 270, "Movie night", "Кино вечером", fam, "family"),
        e(2, 9, 0, 210, "Farmers market", "Фермерский рынок", home, "home"),
        e(2, 13, 30, 240, "Brunch with Sara", "Бранч с Сашей", fam, "family"),
        e(2, 18, 30, 270, "Board games", "Настолки", hobby, "hobby"),
        e(3, 9, 0, 180, "Car service", "Сервис машины", home, "home"),
        e(3, 15, 0, 240, "Kids' football", "Футбол у детей", fam, "family"),
        e(4, 9, 0, 240, "Doctor", "Врач", home, "home"),
        e(4, 18, 0, 270, "Concert", "Концерт", fam, "family"),
        e(5, 10, 0, 300, "Pottery", "Керамика", hobby, "hobby"),
        e(6, 11, 0, 300, "Picnic", "Пикник", fam, "family"),
        e(7, 17, 0, 240, "Swimming", "Бассейн", sport, "sport"),
        e(8, 9, 0, 240, "Job interview", "Собеседование", work, "work"),
        e(9, 15, 0, 240, "Parents' evening", "Родительское собрание", fam, "family"),
        e(10, 9, 0, 480, "Hike", "Поход в горы", travel, "travel"),
    )
    // Еженедельное на всё окно, кроме дней, расписанных выше.
    val weekly = ArrayList<CalendarEvent>()
    for (d in -183L..183L) {
        if (d in -10L..10L) continue
        when (t.plusDays(d).dayOfWeek.value) {
            2, 4 -> weekly.add(e(d, 17, 0, 240, "Gym", "Спортзал", sport, "sport"))
            3 -> weekly.add(e(d, 18, 0, 240, "Spanish", "Испанский", hobby, "hobby"))
            6 -> weekly.add(e(d, 10, 0, 300, "Guitar", "Гитара", hobby, "hobby"))
            7 -> weekly.add(e(d, 12, 0, 360, "Family lunch", "Обед с семьёй", fam, "family"))
        }
    }
    return near + weekly
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
