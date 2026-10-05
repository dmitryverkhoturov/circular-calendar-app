package com.circularcalendar.app

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.CalendarContract
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset

// ─────────────────────────────────────────────────────────────────────────────
// Чтение системного календаря НАПРЯМУЮ через CalendarContract —
// без плагинов. Это же — инструмент расследования бага «0 календарей»
// (device_calendar на устройстве пользователя возвращал пусто):
// здесь виден сырой ответ провайдера + аккаунты + видимость.
// ─────────────────────────────────────────────────────────────────────────────

object CalendarRepo {

    class Diagnostics(
        val permissionGranted: Boolean,
        val providerPresent: Boolean,
        val calendars: List<CalendarInfo>,
        val eventCount: Int,
        val error: String?,
    ) {
        val visibleCount: Int get() = calendars.count { it.visible }

        /** Короткая строка для бейджа. */
        fun badgeText(): String = when {
            error != null -> "error: $error"
            !permissionGranted -> "no READ_CALENDAR permission"
            !providerPresent -> "no calendar provider!"
            calendars.isEmpty() -> "permission granted, 0 calendars in provider"
            else -> "${calendars.size} cal. · $eventCount events"
        }

        val ok: Boolean get() = error == null && permissionGranted && calendars.isNotEmpty()
    }

    fun hasPermission(ctx: Context): Boolean =
        ctx.checkSelfPermission(Manifest.permission.READ_CALENDAR) ==
            PackageManager.PERMISSION_GRANTED

    private fun providerPresent(ctx: Context): Boolean =
        ctx.packageManager.resolveContentProvider(CalendarContract.AUTHORITY, 0) != null

    /**
     * Все календари провайдера, ВКЛЮЧАЯ невидимые (visible=0) —
     * чтобы диагностика показывала полную картину.
     */
    fun loadCalendars(ctx: Context): List<CalendarInfo> {
        val projection = arrayOf(
            CalendarContract.Calendars._ID,
            CalendarContract.Calendars.CALENDAR_DISPLAY_NAME,
            CalendarContract.Calendars.CALENDAR_COLOR,
            CalendarContract.Calendars.VISIBLE,
            CalendarContract.Calendars.ACCOUNT_NAME,
            CalendarContract.Calendars.ACCOUNT_TYPE,
        )
        val result = ArrayList<CalendarInfo>()
        ctx.contentResolver.query(
            CalendarContract.Calendars.CONTENT_URI, projection, null, null, null,
        )?.use { cur ->
            while (cur.moveToNext()) {
                val id = cur.getLong(0).toString()
                val name = cur.getString(1) ?: cur.getString(4) ?: id
                val color = if (!cur.isNull(2)) cur.getInt(2) or 0xFF000000.toInt() else WHITE
                result.add(
                    CalendarInfo(
                        id = id,
                        name = name,
                        color = color,
                        visible = !cur.isNull(3) && cur.getInt(3) == 1,
                        accountName = cur.getString(4) ?: "",
                        accountType = cur.getString(5) ?: "",
                    ),
                )
            }
        }
        return result
    }

    /**
     * События (экземпляры, с развёрнутыми повторами) за текущий + следующий год
     * — для переката года, как во Flutter-версии.
     */
    fun loadEvents(ctx: Context, year: Int, calendars: List<CalendarInfo>): List<CalendarEvent> {
        val zone = ZoneId.systemDefault()
        val beginMs = LocalDate.of(year, 1, 1).atStartOfDay(zone).toInstant().toEpochMilli()
        val endMs = LocalDate.of(year + 2, 1, 1).atStartOfDay(zone).toInstant().toEpochMilli()

        val calColor = calendars.associate { it.id to it.color }

        val projection = arrayOf(
            CalendarContract.Instances.EVENT_ID,
            CalendarContract.Instances.TITLE,
            CalendarContract.Instances.BEGIN,
            CalendarContract.Instances.END,
            CalendarContract.Instances.ALL_DAY,
            CalendarContract.Instances.CALENDAR_ID,
            CalendarContract.Instances.DISPLAY_COLOR,
        )

        val result = ArrayList<CalendarEvent>()
        CalendarContract.Instances.query(ctx.contentResolver, projection, beginMs, endMs)
            ?.use { cur ->
                var seq = 0
                while (cur.moveToNext()) {
                    val begin = if (!cur.isNull(2)) cur.getLong(2) else continue
                    val end = if (!cur.isNull(3)) cur.getLong(3) else continue
                    val allDay = !cur.isNull(4) && cur.getInt(4) == 1
                    val calId = if (!cur.isNull(5)) cur.getLong(5).toString() else ""
                    // Цвет: DISPLAY_COLOR события → цвет календаря → белый.
                    val color = when {
                        !cur.isNull(6) && cur.getInt(6) != 0 -> cur.getInt(6) or 0xFF000000.toInt()
                        else -> calColor[calId] ?: WHITE
                    }
                    // allDay хранятся как UTC-полночь: читаем дату в UTC,
                    // иначе день «съезжает» на восточных таймзонах.
                    fun toLocal(ms: Long): LocalDateTime =
                        if (allDay)
                            Instant.ofEpochMilli(ms).atZone(ZoneOffset.UTC).toLocalDateTime()
                        else
                            Instant.ofEpochMilli(ms).atZone(zone).toLocalDateTime()

                    result.add(
                        CalendarEvent(
                            // EVENT_ID не уникален между экземплярами повтора —
                            // добавляем порядковый суффикс.
                            id = "${cur.getLong(0)}#${seq++}",
                            title = cur.getString(1) ?: tr("(untitled)", "(без названия)"),
                            start = toLocal(begin),
                            end = toLocal(end),
                            color = color,
                            calendarId = calId,
                            allDay = allDay,
                        ),
                    )
                }
            }
        return result
    }

    /** Полная загрузка с диагностикой. Вызывать с фонового потока. */
    fun loadAll(ctx: Context, year: Int): Pair<Diagnostics, List<CalendarEvent>> {
        if (!hasPermission(ctx)) {
            return Pair(
                Diagnostics(false, providerPresent(ctx), emptyList(), 0, null),
                emptyList(),
            )
        }
        return try {
            val cals = loadCalendars(ctx)
            val events = if (cals.isEmpty()) emptyList() else loadEvents(ctx, year, cals)
            Pair(Diagnostics(true, providerPresent(ctx), cals, events.size, null), events)
        } catch (e: Exception) {
            Pair(
                Diagnostics(true, providerPresent(ctx), emptyList(), 0,
                    e.message ?: e.javaClass.simpleName),
                emptyList(),
            )
        }
    }
}
