package com.circularcalendar.app

import android.Manifest
import android.app.Activity
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import org.json.JSONArray
import java.time.LocalDateTime

// ─────────────────────────────────────────────────────────────────────────────
// MainActivity — тонкая нативная оболочка: круг + кнопка настроек +
// панель настроек. Настоящие Android-вьюхи, без единой зависимости.
// ─────────────────────────────────────────────────────────────────────────────

class MainActivity : Activity() {

    private lateinit var root: FrameLayout
    private lateinit var overlay: FrameLayout
    private lateinit var wheel: WheelView
    private lateinit var settingsButton: TextView
    private var panel: View? = null

    private var settings = WheelSettings()
    private var events: List<CalendarEvent> = emptyList()
    private var calendars: List<CalendarInfo>? = null
    private var now: LocalDateTime = LocalDateTime.now()

    private val mainHandler = Handler(Looper.getMainLooper())
    private val density: Float get() = resources.displayMetrics.density
    private fun dp(v: Float): Int = (v * density + 0.5f).toInt()

    // Обновление «сейчас» раз в минуту — стрелка не зависает.
    private val nowTicker = object : Runnable {
        override fun run() {
            now = LocalDateTime.now()
            wheel.update(settings, events, now)
            mainHandler.postDelayed(this, 60_000)
        }
    }

    // ── Персистентность ──────────────────────────────────────────────────────

    private fun loadSettings() {
        val p = getSharedPreferences("settings", MODE_PRIVATE)
        val filterStr = p.getString("calFilter", null)
        var filter = CalendarFilter()
        if (!filterStr.isNullOrEmpty()) {
            try {
                val arr = JSONArray(filterStr)
                val ids = HashSet<String>()
                for (i in 0 until arr.length()) ids.add(arr.getString(i))
                filter = CalendarFilter(ids)
            } catch (_: Exception) {
            }
        }
        settings = WheelSettings(
            counterClockwise = p.getBoolean("ccw", true),
            nowAtBottom = p.getBoolean("nowBottom", true),
            themeIndex = p.getInt("themeIdx", 0).coerceIn(0, THEMES.size - 1),
            calendarFilter = filter,
            largeEventsOnTop = p.getBoolean("largeTop", false),
        )
    }

    private fun saveSettings() {
        val p = getSharedPreferences("settings", MODE_PRIVATE).edit()
        p.putBoolean("ccw", settings.counterClockwise)
        p.putBoolean("nowBottom", settings.nowAtBottom)
        p.putInt("themeIdx", settings.themeIndex)
        p.putBoolean("largeTop", settings.largeEventsOnTop)
        val ids = settings.calendarFilter.allowedIds
        if (ids == null) p.remove("calFilter")
        else p.putString("calFilter", JSONArray(ids.toList()).toString())
        p.apply()
    }

    // Камера (зум/поворот/пан) переживает рестарт: "w,h,9 значений матрицы".
    private fun saveCamera() {
        if (wheel.width <= 0 || wheel.height <= 0) return
        val v = wheel.cameraValues()
        getSharedPreferences("settings", MODE_PRIVATE).edit()
            .putString("camera", "${wheel.width},${wheel.height}," + v.joinToString(","))
            .apply()
    }

    private fun restoreCamera() {
        val s = getSharedPreferences("settings", MODE_PRIVATE)
            .getString("camera", null) ?: return
        try {
            val parts = s.split(',')
            if (parts.size != 11) return
            val vals = FloatArray(9) { parts[it + 2].toFloat() }
            wheel.setPendingCamera(vals, parts[0].toInt(), parts[1].toInt())
        } catch (_: Exception) {
        }
    }

    private fun applySettings(s: WheelSettings) {
        settings = s
        saveSettings()
        wheel.update(settings, events, now)
        rebuildPanelIfOpen()
    }

    // ── Жизненный цикл ───────────────────────────────────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        loadSettings()

        // Edge-to-edge: рисуем под полностью прозрачными системными барами.
        if (android.os.Build.VERSION.SDK_INT >= 30) {
            window.setDecorFitsSystemWindows(false)
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility =
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
        }
        window.statusBarColor = 0
        window.navigationBarColor = 0
        window.isNavigationBarContrastEnforced = false
        window.isStatusBarContrastEnforced = false

        root = FrameLayout(this)

        wheel = WheelView(this)
        root.addView(wheel, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))

        // Оверлей (кнопка настроек, панель) — с отступами от системных баров.
        overlay = FrameLayout(this)
        root.addView(overlay, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        root.setOnApplyWindowInsetsListener { _, insets ->
            if (android.os.Build.VERSION.SDK_INT >= 30) {
                val si = insets.getInsets(android.view.WindowInsets.Type.systemBars())
                overlay.setPadding(si.left, si.top, si.right, si.bottom)
            } else {
                @Suppress("DEPRECATION")
                overlay.setPadding(
                    insets.systemWindowInsetLeft,
                    insets.systemWindowInsetTop,
                    insets.systemWindowInsetRight,
                    insets.systemWindowInsetBottom,
                )
            }
            insets
        }

        // Маленькая, почти незаметная кнопка настроек — внизу слева (§3b SPEC).
        settingsButton = TextView(this).apply {
            text = "⚙︎" // ⚙ в текстовом стиле (не эмодзи)
            textSize = 15f
            setTextColor(withAlpha(WHITE, 0.85f))
            gravity = Gravity.CENTER
            alpha = 0.28f
            background = roundedBg(withAlpha(WHITE, 0.06f), 8f)
            setOnClickListener { togglePanel() }
        }
        overlay.addView(settingsButton, FrameLayout.LayoutParams(dp(32f), dp(32f)).apply {
            gravity = Gravity.BOTTOM or Gravity.START
            leftMargin = dp(12f); bottomMargin = dp(12f)
        })

        setContentView(root)

        // Мок всегда — виден сразу; реальные календари загрузятся асинхронно.
        events = buildMockEvents(now.year)
        restoreCamera()
        wheel.update(settings, events, now)
        mainHandler.postDelayed(nowTicker, 60_000)

        requestCalendarOrLoad()
    }

    override fun onResume() {
        super.onResume()
        // Обновление событий при каждом возврате в приложение.
        if (CalendarRepo.hasPermission(this)) loadCalendarData()
    }

    override fun onPause() {
        saveCamera()
        super.onPause()
    }

    override fun onDestroy() {
        mainHandler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    // ── Календарь ────────────────────────────────────────────────────────────

    private fun requestCalendarOrLoad() {
        if (CalendarRepo.hasPermission(this)) {
            loadCalendarData()
        } else {
            requestPermissions(arrayOf(Manifest.permission.READ_CALENDAR), 1)
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray,
    ) {
        if (requestCode == 1) loadCalendarData()
    }

    private fun loadCalendarData() {
        Thread {
            val (d, evs) = CalendarRepo.loadAll(this, now.year)
            mainHandler.post {
                if (d.ok) {
                    calendars = d.calendars
                    events = evs
                    now = LocalDateTime.now()
                    migrateFilterIfStale(d.calendars)
                }
                // Нет календарей/разрешения — тихо остаёмся на моке.
                wheel.update(settings, events, now)
                rebuildPanelIfOpen()
            }
        }.start()
    }

    /**
     * Миграция фильтра: если сохранённые ID не совпадают ни с одним реальным
     * календарём (например, остались мок-ID) — сбрасываем на «показать все».
     */
    private fun migrateFilterIfStale(cals: List<CalendarInfo>) {
        val saved = settings.calendarFilter.allowedIds ?: return
        if (cals.isEmpty() || saved.isEmpty()) return
        val realIds = cals.map { it.id }.toSet()
        if (saved.none { realIds.contains(it) }) {
            settings = settings.copy(calendarFilter = CalendarFilter())
            saveSettings()
        }
    }

    // ── Панель настроек ──────────────────────────────────────────────────────

    private fun togglePanel() {
        if (panel != null) {
            overlay.removeView(panel)
            panel = null
            settingsButton.alpha = 0.28f
        } else {
            val v = buildPanel()
            panel = v
            overlay.addView(v, FrameLayout.LayoutParams(
                dp(240f), FrameLayout.LayoutParams.WRAP_CONTENT).apply {
                gravity = Gravity.BOTTOM or Gravity.START
                leftMargin = dp(8f); bottomMargin = dp(52f)
            })
            settingsButton.alpha = 0.70f
        }
    }

    private fun rebuildPanelIfOpen() {
        if (panel != null) {
            overlay.removeView(panel)
            panel = null
            togglePanel()
        }
    }

    private fun roundedBg(
        color: Int, radiusDp: Float, strokeColor: Int? = null,
    ): GradientDrawable = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(radiusDp).toFloat()
        if (strokeColor != null) setStroke(dp(1f), strokeColor)
    }

    private fun label(text: String): TextView = TextView(this).apply {
        this.text = text
        textSize = 10f
        letterSpacing = 0.08f
        setTextColor(withAlpha(WHITE, 0.35f))
    }

    private fun toggle(
        options: List<String>, selected: Int, onTap: (Int) -> Unit,
    ): LinearLayout {
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        options.forEachIndexed { i, opt ->
            val active = i == selected
            val tv = TextView(this).apply {
                text = opt
                textSize = 12f
                gravity = Gravity.CENTER
                setPadding(0, dp(6f), 0, dp(6f))
                setTextColor(withAlpha(WHITE, if (active) 0.88f else 0.38f))
                typeface = if (active) Typeface.create("sans-serif-medium", Typeface.NORMAL)
                           else Typeface.DEFAULT
                background = roundedBg(
                    withAlpha(WHITE, if (active) 0.12f else 0.04f), 6f,
                    strokeColor = withAlpha(WHITE, if (active) 0.20f else 0.06f),
                )
                setOnClickListener { onTap(i) }
            }
            val lp = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            if (i < options.size - 1) lp.rightMargin = dp(4f)
            row.addView(tv, lp)
        }
        return row
    }

    private fun dot(color: Int, sizeDp: Float): View = View(this).apply {
        background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(color)
        }
        layoutParams = LinearLayout.LayoutParams(dp(sizeDp), dp(sizeDp))
    }

    private fun buildPanel(): View {
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16f), dp(14f), dp(16f), dp(14f))
        }

        fun space(h: Float) = col.addView(View(this), LinearLayout.LayoutParams(1, dp(h)))

        col.addView(label("Ориентация круга"))
        space(6f)
        col.addView(toggle(listOf("Против часовой", "По часовой"),
            if (settings.counterClockwise) 0 else 1) { i ->
            applySettings(settings.copy(counterClockwise = i == 0))
        })
        space(14f)

        col.addView(label("«Сейчас» по умолчанию"))
        space(6f)
        col.addView(toggle(listOf("Внизу", "Вверху"),
            if (settings.nowAtBottom) 0 else 1) { i ->
            applySettings(settings.copy(nowAtBottom = i == 0))
        })
        space(14f)

        col.addView(label("Крупные события"))
        space(6f)
        // «Снизу» — крупные у кольца дней (как раньше); «Сверху» — иерархия
        // длительности: из пересекающихся длинное уходит дальше от кольца.
        col.addView(toggle(listOf("Снизу", "Сверху"),
            if (settings.largeEventsOnTop) 1 else 0) { i ->
            applySettings(settings.copy(largeEventsOnTop = i == 1))
        })
        space(14f)

        col.addView(label("Тема"))
        space(6f)
        THEMES.forEachIndexed { i, t ->
            val active = settings.themeIndex == i
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(10f), dp(7f), dp(10f), dp(7f))
                background = if (active)
                    roundedBg(withAlpha(WHITE, 0.10f), 6f, strokeColor = withAlpha(WHITE, 0.20f))
                else null
                setOnClickListener { applySettings(settings.copy(themeIndex = i)) }
            }
            row.addView(dot(t.accent, 10f))
            row.addView(TextView(this).apply {
                text = t.name
                textSize = 13f
                setTextColor(withAlpha(WHITE, if (active) 0.90f else 0.50f))
                typeface = if (active) Typeface.create("sans-serif-medium", Typeface.NORMAL)
                           else Typeface.DEFAULT
                setPadding(dp(8f), 0, 0, 0)
            })
            col.addView(row, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(4f) })
        }

        // Фильтр календарей: реальные, если есть; иначе — из мок-событий.
        val entries: List<Triple<String, String, Int>> =
            calendars?.takeIf { it.isNotEmpty() }
                ?.map { Triple(it.id, it.name, it.color) }
                ?: run {
                    val seen = LinkedHashMap<String, Int>()
                    for (e in events) if (e.calendarId.isNotEmpty() && !seen.containsKey(e.calendarId))
                        seen[e.calendarId] = e.color
                    seen.map { Triple(it.key, it.key, it.value) }
                }
        if (entries.isNotEmpty()) {
            space(14f)
            col.addView(label("Календари"))
            space(6f)
            val allIds = entries.map { it.first }.toSet()
            for ((id, name, color) in entries) {
                val allowed = settings.calendarFilter.allowedIds?.contains(id) ?: true
                val row = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(0, dp(4f), 0, dp(4f))
                    setOnClickListener {
                        applySettings(settings.copy(
                            calendarFilter = settings.calendarFilter.withToggled(id, allIds)))
                    }
                }
                row.addView(dot(if (allowed) color else withAlpha(color, 0.25f), 10f))
                row.addView(TextView(this).apply {
                    text = name
                    textSize = 12f
                    maxLines = 1
                    setTextColor(withAlpha(WHITE, if (allowed) 0.80f else 0.30f))
                    setPadding(dp(8f), 0, dp(6f), 0)
                }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
                row.addView(TextView(this).apply {
                    text = if (allowed) "✓" else "—"
                    textSize = 12f
                    setTextColor(withAlpha(WHITE, if (allowed) 0.50f else 0.20f))
                })
                col.addView(row, LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT))
            }
        }

        // Обновление событий вручную.
        space(14f)
        col.addView(TextView(this).apply {
            text = "Обновить события"
            textSize = 12f
            gravity = Gravity.CENTER
            setPadding(0, dp(7f), 0, dp(7f))
            setTextColor(withAlpha(WHITE, 0.75f))
            background = roundedBg(withAlpha(WHITE, 0.06f), 6f,
                strokeColor = withAlpha(WHITE, 0.10f))
            setOnClickListener { requestCalendarOrLoad() }
        }, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT))

        val scroll = ScrollView(this).apply {
            isVerticalScrollBarEnabled = false
            background = roundedBg(
                withAlpha(0xFF111520.toInt(), 0.95f), 12f,
                strokeColor = withAlpha(WHITE, 0.08f),
            )
            clipToOutline = true
            addView(col)
        }
        return scroll
    }
}
