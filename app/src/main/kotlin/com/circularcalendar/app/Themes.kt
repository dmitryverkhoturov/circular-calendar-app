package com.circularcalendar.app

// Темы — единственный источник цветов и толщин.
// Прямой порт WheelTheme из Flutter-версии (main.dart).
// Цвета — ARGB Int. Толщины — множители от unit (maxR/360).

fun withAlpha(color: Int, alpha: Float): Int {
    val a = (alpha.coerceIn(0f, 1f) * 255f + 0.5f).toInt()
    return (color and 0x00FFFFFF) or (a shl 24)
}

const val WHITE = 0xFFFFFFFF.toInt()

class WheelTheme(
    val name: String,
    val background: Int,
    // Заливка секторов (нечётный/чётный месяц × прошлое/будущее)
    val bandPastOdd: Int,
    val bandPastEven: Int,
    val bandFutureOdd: Int,
    val bandFutureEven: Int,
    // Риски и контуры
    val dayTickColor: Int,
    val dayTickAlpha: Float,
    val monthLineColor: Int,
    val monthLineAlpha: Float,
    val seasonLineColor: Int,
    val seasonLineAlpha: Float,
    val astroLineColor: Int,
    val astroLineAlpha: Float,
    val ringColor: Int,
    val ringAlpha: Float,
    // Толщины (множители от unit)
    val dayTickWidth: Float,
    val monthLineWidth: Float,
    val seasonLineWidth: Float,
    val astroLineWidth: Float,
    val ringWidth: Float,
    // Текст месяцев
    val monthLabelPast: Int,
    val monthLabelFuture: Int,
    val monthLabelFontMul: Float,
    // Числа дней
    val dayNumPast: Int,
    val dayNumFuture: Int,
    // Акцент (стрелка «сейчас»)
    val accent: Int,
    // События
    val eventBandWidth: Float = 7.0f,
    val eventPastAlpha: Float = 0.45f,
    val eventFutureAlpha: Float = 0.85f,
)

val THEME_DARK = WheelTheme(
    name = "Тёмная классика",
    background = 0xFF090B10.toInt(),
    bandPastOdd = 0xFF0D1018.toInt(),
    bandPastEven = 0xFF0B0E15.toInt(),
    bandFutureOdd = 0xFF141B2E.toInt(),
    bandFutureEven = 0xFF111727.toInt(),
    dayTickColor = WHITE,
    dayTickAlpha = 0.08f,
    monthLineColor = WHITE,
    monthLineAlpha = 0.22f,
    seasonLineColor = WHITE,
    seasonLineAlpha = 0.80f,
    astroLineColor = 0xFF64C8C0.toInt(),
    astroLineAlpha = 0.50f,
    ringColor = WHITE,
    ringAlpha = 0.18f,
    dayTickWidth = 0.9f,
    monthLineWidth = 1.6f,
    seasonLineWidth = 2.8f,
    astroLineWidth = 0.7f,
    ringWidth = 1.0f,
    monthLabelPast = 0x5AFFFFFF,
    monthLabelFuture = 0xE6FFFFFF.toInt(),
    monthLabelFontMul = 13.0f,
    dayNumPast = 0x28FFFFFF,
    dayNumFuture = 0x52FFFFFF,
    accent = 0xFFD4A853.toInt(),
    eventBandWidth = 7.0f,
    eventPastAlpha = 0.42f,
    eventFutureAlpha = 0.82f,
)

val THEME_POLAR = WheelTheme(
    name = "Полярная ночь",
    background = 0xFF07090F.toInt(),
    bandPastOdd = 0xFF0A0C12.toInt(),
    bandPastEven = 0xFF090B11.toInt(),
    bandFutureOdd = 0xFF111828.toInt(),
    bandFutureEven = 0xFF0F1522.toInt(),
    dayTickColor = WHITE,
    dayTickAlpha = 0.07f,
    monthLineColor = WHITE,
    monthLineAlpha = 0.18f,
    seasonLineColor = 0xFFB0BEE0.toInt(),
    seasonLineAlpha = 0.75f,
    astroLineColor = 0xFF80D4E8.toInt(),
    astroLineAlpha = 0.45f,
    ringColor = 0xFF8899CC.toInt(),
    ringAlpha = 0.22f,
    dayTickWidth = 0.8f,
    monthLineWidth = 1.5f,
    seasonLineWidth = 2.5f,
    astroLineWidth = 0.65f,
    ringWidth = 0.9f,
    monthLabelPast = 0x47AABBDD,
    monthLabelFuture = 0xCCCCDDFF.toInt(),
    monthLabelFontMul = 13.0f,
    dayNumPast = 0x228899CC,
    dayNumFuture = 0x4499AADD,
    accent = 0xFF7ECFE0.toInt(),
    eventBandWidth = 7.0f,
    eventPastAlpha = 0.40f,
    eventFutureAlpha = 0.80f,
)

val THEMES = listOf(THEME_DARK, THEME_POLAR)
