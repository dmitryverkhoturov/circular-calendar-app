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
import android.widget.SeekBar
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

    private var motion = MotionSettings()

    // Тик «сейчас»: раз в минуту полная пересборка; на масштабе минут/секунд
    // стрелка двигается каждую секунду (без пересборки событий).
    private val nowTicker = object : Runnable {
        override fun run() {
            val n = LocalDateTime.now()
            if (n.minute != now.minute || n.hour != now.hour || n.dayOfYear != now.dayOfYear) {
                now = n
                wheel.update(settings, events, now)
            } else {
                wheel.tickSecond()
            }
            mainHandler.postDelayed(this, 1000 - (System.currentTimeMillis() % 1000))
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
            design = Design.fromJson(p.getString("design", null)),
            calendarFilter = filter,
            largeEventsOnTop = p.getBoolean("largeTop", false),
            keplerOrbit = p.getBoolean("kepler", false),
            showOrbits = p.getBoolean("orbits", false),
            arrowMoves = p.getBoolean("arrowMoves", false),
        )
        val d = MotionSettings()
        motion = MotionSettings(
            friction = p.getFloat("mFriction", d.friction.toFloat()).toDouble(),
            launch = p.getFloat("mLaunch", d.launch.toFloat()).toDouble(),
            spring = p.getFloat("mSpring", d.spring.toFloat()).toDouble(),
            damping = p.getFloat("mDamping", d.damping.toFloat()).toDouble(),
            boost = p.getFloat("mBoost", d.boost.toFloat()).toDouble(),
            rotThreshold = p.getFloat("mRotThr", d.rotThreshold.toFloat()).toDouble(),
            rotZoomK = p.getFloat("mRotZoomK", d.rotZoomK.toFloat()).toDouble(),
            rotCatchUp = p.getFloat("mRotCatch", d.rotCatchUp.toFloat()).toDouble(),
        )
    }

    private fun saveMotion() {
        getSharedPreferences("settings", MODE_PRIVATE).edit()
            .putFloat("mFriction", motion.friction.toFloat())
            .putFloat("mLaunch", motion.launch.toFloat())
            .putFloat("mSpring", motion.spring.toFloat())
            .putFloat("mDamping", motion.damping.toFloat())
            .putFloat("mBoost", motion.boost.toFloat())
            .putFloat("mRotThr", motion.rotThreshold.toFloat())
            .putFloat("mRotZoomK", motion.rotZoomK.toFloat())
            .putFloat("mRotCatch", motion.rotCatchUp.toFloat())
            .apply()
    }

    private fun saveSettings() {
        val p = getSharedPreferences("settings", MODE_PRIVATE).edit()
        p.putBoolean("ccw", settings.counterClockwise)
        p.putBoolean("nowBottom", settings.nowAtBottom)
        p.putString("design", settings.design.toJson())
        p.putBoolean("largeTop", settings.largeEventsOnTop)
        p.putBoolean("kepler", settings.keplerOrbit)
        p.putBoolean("orbits", settings.showOrbits)
        p.putBoolean("arrowMoves", settings.arrowMoves)
        val ids = settings.calendarFilter.allowedIds
        if (ids == null) p.remove("calFilter")
        else p.putString("calFilter", JSONArray(ids.toList()).toString())
        p.apply()
    }

    // Камера (зум/поворот/пан) переживает рестарт; при восстановлении
    // возвращается в пределы — календарь не потеряется за краем.
    private fun saveCamera() {
        if (wheel.width <= 0 || wheel.height <= 0) return
        getSharedPreferences("settings", MODE_PRIVATE).edit()
            .putString("camera2", wheel.cameraState())
            .apply()
    }

    private fun restoreCamera() {
        val s = getSharedPreferences("settings", MODE_PRIVATE)
            .getString("camera2", null) ?: return
        wheel.restoreCameraState(s)
    }

    private fun applySettings(s: WheelSettings) {
        settings = s
        saveSettings()
        wheel.update(settings, events, now)
        styleSettingsButton()
        rebuildPanelIfOpen()
    }

    /** Ползунок дизайна: без пересборки панели (иначе рвётся касание). */
    private fun applyDesignLive(d: Design) {
        settings = settings.copy(design = d)
        saveSettings()
        wheel.update(settings, events, now)
    }

    private fun applyDesign(d: Design) = applySettings(settings.copy(design = d))

    private fun styleSettingsButton() {
        if (!::settingsButton.isInitialized) return
        val fg = panelFg()
        settingsButton.setTextColor(withAlpha(fg, 0.9f))
        settingsButton.background = roundedBg(withAlpha(fg, 0.08f), 8f)
    }

    private fun panelFg(): Int = if (settings.look.dark) WHITE else 0xFF1C1020.toInt()

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
        wheel.motion = motion
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

        // Маленькая, почти незаметная кнопка настроек — вверху слева.
        settingsButton = TextView(this).apply {
            text = "⚙︎" // ⚙ в текстовом стиле (не эмодзи)
            textSize = 15f
            gravity = Gravity.CENTER
            alpha = 0.32f
            isSoundEffectsEnabled = false
            setOnClickListener { togglePanel() }
        }
        styleSettingsButton()
        overlay.addView(settingsButton, FrameLayout.LayoutParams(dp(32f), dp(32f)).apply {
            gravity = Gravity.TOP or Gravity.START
            leftMargin = dp(12f); topMargin = dp(10f)
        })
        root.isSoundEffectsEnabled = false
        overlay.isSoundEffectsEnabled = false
        wheel.isSoundEffectsEnabled = false

        setContentView(root)

        // Мок всегда — виден сразу; реальные календари загрузятся асинхронно.
        events = buildMockEvents(now.year)
        restoreCamera()
        wheel.update(settings, events, now)
        mainHandler.postDelayed(nowTicker, 1000)

        requestCalendarOrLoad()
    }

    // Отладочный вход: adb shell am start ... --ef zoom 500 --ef secs 0 --ef rf 1
    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        if (intent.hasExtra("gest")) {
            wheel.debugGesture(
                intent.getStringExtra("gest") ?: "",
                intent.getFloatExtra("x", 540f), intent.getFloatExtra("y", 1200f),
                intent.getFloatExtra("deg", 0f),
            )
            wheel.postDelayed({ android.util.Log.i("CCDBG", wheel.debugState()) }, 2500)
        }
        if (intent.hasExtra("state")) android.util.Log.i("CCDBG", wheel.debugState())
        if (intent.hasExtra("zoom")) {
            wheel.post {
                wheel.debugLook(
                    intent.getFloatExtra("zoom", 1f).toDouble(),
                    intent.getFloatExtra("secs", 0f).toDouble(),
                    intent.getFloatExtra("rf", 1f).toDouble(),
                    intent.getFloatExtra("th", 0f).toDouble(),
                )
            }
        }
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

    private var scrim: View? = null
    private var panelScroll = 0

    private fun togglePanel() {
        if (panel != null) {
            panelScroll = (panel as? ScrollView)?.scrollY ?: 0
            overlay.removeView(panel)
            overlay.removeView(scrim)
            panel = null; scrim = null
            settingsButton.alpha = 0.32f
        } else {
            // Тап мимо панели закрывает её (и не трогает круг).
            val sc = View(this).apply {
                isSoundEffectsEnabled = false
                setOnTouchListener { _, ev ->
                    if (ev.actionMasked == android.view.MotionEvent.ACTION_DOWN) togglePanel()
                    true
                }
            }
            scrim = sc
            overlay.addView(sc, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
            val v = buildPanel()
            quiet(v)
            panel = v
            overlay.addView(v, FrameLayout.LayoutParams(
                dp(288f), FrameLayout.LayoutParams.WRAP_CONTENT).apply {
                gravity = Gravity.TOP or Gravity.START
                leftMargin = dp(8f); topMargin = dp(50f); bottomMargin = dp(12f)
            })
            val y = panelScroll
            v.post { (v as? ScrollView)?.scrollTo(0, y) }
            settingsButton.alpha = 0.75f
            settingsButton.bringToFront()
        }
    }

    private fun rebuildPanelIfOpen() {
        if (panel != null) {
            togglePanel()
            togglePanel()
        }
    }

    /** Никаких звуков нажатий нигде в панели. */
    private fun quiet(v: View) {
        v.isSoundEffectsEnabled = false
        if (v is android.view.ViewGroup) for (i in 0 until v.childCount) quiet(v.getChildAt(i))
    }

    private fun roundedBg(
        color: Int, radiusDp: Float, strokeColor: Int? = null,
    ): GradientDrawable = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(radiusDp).toFloat()
        if (strokeColor != null) setStroke(dp(1f), strokeColor)
    }

    private fun label(text: String): TextView = TextView(this).apply {
        this.text = text.uppercase()
        textSize = 10f
        letterSpacing = 0.12f
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        setTextColor(withAlpha(panelFg(), 0.45f))
    }

    private fun toggle(
        options: List<String>, selected: Int, onTap: (Int) -> Unit,
    ): LinearLayout {
        val fg = panelFg()
        val acc = settings.look.accent
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        options.forEachIndexed { i, opt ->
            val active = i == selected
            val tv = TextView(this).apply {
                text = opt
                textSize = 12f
                gravity = Gravity.CENTER
                maxLines = 1
                setPadding(dp(2f), dp(7f), dp(2f), dp(7f))
                setTextColor(if (active) (if (luminance(acc) > 150) 0xFF1C1020.toInt() else WHITE) else withAlpha(fg, 0.6f))
                typeface = if (active) Typeface.create("sans-serif-medium", Typeface.NORMAL)
                           else Typeface.DEFAULT
                background = roundedBg(
                    if (active) acc else withAlpha(fg, 0.05f), 99f,
                    strokeColor = if (active) null else withAlpha(fg, 0.10f),
                )
                setOnClickListener { onTap(i) }
            }
            val lp = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            if (i < options.size - 1) lp.rightMargin = dp(4f)
            row.addView(tv, lp)
        }
        return row
    }

    /** Несколько рядов toggle по [perRow] штук. */
    private fun toggleGrid(col: LinearLayout, options: List<String>, selected: Int, perRow: Int, onTap: (Int) -> Unit) {
        options.chunked(perRow).forEachIndexed { r, chunk ->
            val base = r * perRow
            val padded = chunk + List(perRow - chunk.size) { "" }
            val row = toggle(padded, selected - base) { i -> if (base + i < options.size) onTap(base + i) }
            for (i in chunk.size until perRow) row.getChildAt(i).visibility = View.INVISIBLE
            col.addView(row, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(4f) })
        }
    }

    private fun dot(color: Int, sizeDp: Float, ring: Int? = null): View = View(this).apply {
        background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(color)
            if (ring != null) setStroke(dp(2f), ring)
        }
        layoutParams = LinearLayout.LayoutParams(dp(sizeDp), dp(sizeDp))
    }

    private fun applyMotion(m: MotionSettings) {
        motion = m
        wheel.motion = m
        saveMotion()
    }

    /** Ползунок: подпись + значение, линейная шкала [lo, hi]. */
    private fun slider(
        title: String, value: Double, lo: Double, hi: Double, fmt: String,
        onChange: (Double) -> Unit,
    ): View {
        val fg = panelFg()
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(4f), 0, 0)
        }
        val head = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        head.addView(TextView(this).apply {
            text = title
            textSize = 12f
            setTextColor(withAlpha(fg, 0.75f))
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        val valueView = TextView(this).apply {
            text = fmt.format(value)
            textSize = 11f
            typeface = Typeface.MONOSPACE
            setTextColor(withAlpha(fg, 0.55f))
        }
        head.addView(valueView)
        box.addView(head)
        val steps = 1000
        box.addView(SeekBar(this).apply {
            max = steps
            progress = (((value - lo) / (hi - lo)) * steps).toInt().coerceIn(0, steps)
            val tint = android.content.res.ColorStateList.valueOf(settings.look.accent)
            progressTintList = tint
            thumbTintList = tint
            progressBackgroundTintList = android.content.res.ColorStateList.valueOf(withAlpha(fg, 0.25f))
            setPadding(dp(6f), dp(6f), dp(6f), dp(6f))
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar, p: Int, fromUser: Boolean) {
                    if (!fromUser) return
                    val v = lo + (hi - lo) * p / steps
                    valueView.text = fmt.format(v)
                    onChange(v)
                }
                override fun onStartTrackingTouch(sb: SeekBar) {}
                override fun onStopTrackingTouch(sb: SeekBar) {}
            })
        }, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT))
        return box
    }

    private fun buildPanel(): View {
        val fg = panelFg()
        val lk = settings.look
        val d = settings.design
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16f), dp(14f), dp(16f), dp(16f))
        }

        fun space(h: Float) = col.addView(View(this), LinearLayout.LayoutParams(1, dp(h)))
        fun full(v: View) = col.addView(v, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))

        // ═════ Дизайн ═════
        col.addView(label("Стиль"))
        space(6f)
        val presetIdx = PRESETS.indexOfFirst { it.second.preset == d.preset }
        toggleGrid(col, PRESETS.map { it.first }, presetIdx, 3) { i -> applyDesign(PRESETS[i].second) }
        space(12f)

        col.addView(label("Главный цвет"))
        space(8f)
        run {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            for ((c, _) in SWATCHES) {
                val v = dot(c, 26f, if (c == d.accent) fg else withAlpha(fg, 0.15f))
                v.setOnClickListener { applyDesign(d.copy(accent = c, preset = "custom")) }
                row.addView(v, LinearLayout.LayoutParams(dp(26f), dp(26f)).apply { rightMargin = dp(9f) })
            }
            col.addView(row)
        }
        space(4f)
        run {
            val hsl = colorToHsl(d.accent)
            var h = hsl[0].toDouble(); if (h < 200) h += 360
            var sat = hsl[1].toDouble(); var lit = hsl[2].toDouble()
            fun push() = applyDesignLive(settings.design.copy(accent = hslToColor(h.toFloat(), sat.toFloat(), lit.toFloat()), preset = "custom"))
            full(slider("Оттенок (фиолет ← → малина)", h, 260.0, 380.0, "%.0f°") { h = it; push() })
            full(slider("Сочность", sat, 0.0, 100.0, "%.0f") { sat = it; push() })
            full(slider("Светлота", lit, 10.0, 90.0, "%.0f") { lit = it; push() })
        }
        space(12f)

        col.addView(label("Центр"))
        space(6f)
        col.addView(toggle(listOf("Кольцо с дыркой", "Залит"), if (d.ring) 0 else 1) { i ->
            applyDesign(d.copy(ring = i == 0, hole = if (i == 0 && d.hole < 0.05f) 0.3f else d.hole, preset = "custom"))
        })
        space(2f)
        fun dsl(title: String, v: Float, lo: Double, hi: Double, set: (Design, Float) -> Design) =
            full(slider(title, v.toDouble(), lo, hi, "%.2f") { x -> applyDesignLive(set(settings.design, x.toFloat()).copy(preset = "custom")) })
        if (d.ring) dsl("Размер дырки", d.hole, 0.05, 0.7) { o, x -> o.copy(hole = x) }
        dsl("Градиент (0 = из прозрачного)", d.grad, 0.0, 1.0) { o, x -> o.copy(grad = x) }
        dsl("Цвет в заливке", d.tint, 0.0, 1.0) { o, x -> o.copy(tint = x) }
        dsl("Прошлое темнее", d.pastDim, 0.0, 1.0) { o, x -> o.copy(pastDim = x) }
        if (d.ring) {
            space(4f)
            col.addView(toggle(listOf("Обвод дырки", "Без обвода"), if (d.holeLine) 0 else 1) { i ->
                applyDesign(d.copy(holeLine = i == 0, preset = "custom"))
            })
        }
        space(12f)

        col.addView(label("Фон"))
        space(6f)
        toggleGrid(col, BACKDROPS.map { it.name }, d.backdrop, 3) { i -> applyDesign(d.copy(backdrop = i, preset = "custom")) }
        space(12f)

        col.addView(label("Эффекты"))
        dsl("Свечение", d.glow, 0.0, 1.0) { o, x -> o.copy(glow = x) }
        dsl("Зерно", d.grain, 0.0, 1.0) { o, x -> o.copy(grain = x) }
        dsl("Объём", d.volume, 0.0, 1.0) { o, x -> o.copy(volume = x) }
        dsl("Обводка событий", d.outline, 0.0, 1.0) { o, x -> o.copy(outline = x) }
        space(4f)
        col.addView(toggle(listOf("Звёзды", "Без звёзд"), if (d.stars) 0 else 1) { i ->
            applyDesign(d.copy(stars = i == 0, preset = "custom"))
        })
        space(4f)
        col.addView(toggle(listOf("Лучи по дням", "Без лучей"), if (d.rays) 0 else 1) { i ->
            applyDesign(d.copy(rays = i == 0, preset = "custom"))
        })
        space(12f)

        col.addView(label("События"))
        space(6f)
        col.addView(toggle(listOf("Пилюли", "Ленты"), if (d.roundEvents) 0 else 1) { i ->
            applyDesign(d.copy(roundEvents = i == 0, preset = "custom"))
        })
        space(4f)
        toggleGrid(col, PALETTE_NAMES, d.palette, 2) { i -> applyDesign(d.copy(palette = i, preset = "custom")) }
        space(12f)

        col.addView(label("Шрифт"))
        space(6f)
        toggleGrid(col, FONTS.map { it.name }, d.font, 3) { i -> applyDesign(d.copy(font = i, preset = "custom")) }
        space(12f)

        col.addView(label("Стрелка «сейчас»"))
        space(6f)
        col.addView(toggle(ARROW_NAMES, d.arrow) { i -> applyDesign(d.copy(arrow = i, preset = "custom")) })
        space(10f)
        full(TextView(this).apply {
            text = "Скопировать дизайн (для чата)"
            textSize = 12f
            gravity = Gravity.CENTER
            setPadding(0, dp(8f), 0, dp(8f))
            setTextColor(withAlpha(fg, 0.8f))
            background = roundedBg(withAlpha(fg, 0.06f), 99f, strokeColor = withAlpha(fg, 0.12f))
            setOnClickListener {
                val cm = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
                cm.setPrimaryClip(android.content.ClipData.newPlainText("design", settings.design.toJson()))
                text = "Скопировано"
            }
        })
        space(18f)

        // ═════ Поведение ═════
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

        col.addView(label("Орбита Земли"))
        space(6f)
        // Равномерно — все дни одной ширины; Кеплер — реальная скорость Земли:
        // у перигелия (январь) дни шире, Земля и стрелка ближе к Солнцу.
        col.addView(toggle(listOf("Равномерно", "Кеплер"),
            if (settings.keplerOrbit) 1 else 0) { i ->
            applySettings(settings.copy(keplerOrbit = i == 1))
        })
        space(14f)

        col.addView(label("Что движется со временем"))
        space(6f)
        // Круг — «сейчас» всегда внизу, год прокручивается; стрелка — круг
        // стоит, стрелка с Землёй и планеты идут сами (как часы на стене).
        col.addView(toggle(listOf("Круг", "Стрелка"),
            if (settings.arrowMoves) 1 else 0) { i ->
            applySettings(settings.copy(arrowMoves = i == 1))
        })
        space(14f)

        col.addView(label("Орбиты планет"))
        space(6f)
        col.addView(toggle(listOf("Скрыты", "Показаны"),
            if (settings.showOrbits) 1 else 0) { i ->
            applySettings(settings.copy(showOrbits = i == 1))
        })
        space(14f)


        col.addView(label("Анимация"))
        space(4f)
        full(slider("Затухание инерции", motion.friction, 1.5, 20.0, "%.1f") {
            applyMotion(motion.copy(friction = it))
        })
        full(slider("Сила броска", motion.launch, 0.2, 2.5, "%.2f") {
            applyMotion(motion.copy(launch = it))
        })
        full(slider("Жёсткость пружины", motion.spring, 6.0, 45.0, "%.0f") {
            applyMotion(motion.copy(spring = it))
        })
        full(slider("Упругость (меньше = пружинит)", motion.damping, 0.35, 1.0, "%.2f") {
            applyMotion(motion.copy(damping = it))
        })
        full(slider("Разгон повторным броском", motion.boost, 0.0, 1.5, "%.2f") {
            applyMotion(motion.copy(boost = it))
        })
        full(slider("Порог вращения двумя пальцами, °", motion.rotThreshold, 0.0, 30.0, "%.0f") {
            applyMotion(motion.copy(rotThreshold = it))
        })
        full(slider("Рост порога при зуме, ° на ×2", motion.rotZoomK, 0.0, 80.0, "%.0f") {
            applyMotion(motion.copy(rotZoomK = it))
        })
        full(slider("Догон пальцев после порога", motion.rotCatchUp, 3.0, 40.0, "%.0f") {
            applyMotion(motion.copy(rotCatchUp = it))
        })
        space(4f)
        col.addView(TextView(this).apply {
            text = "Анимация по умолчанию"
            textSize = 11f
            gravity = Gravity.CENTER
            setPadding(0, dp(5f), 0, dp(5f))
            setTextColor(withAlpha(fg, 0.55f))
            background = roundedBg(withAlpha(fg, 0.04f), 6f, strokeColor = withAlpha(fg, 0.08f))
            setOnClickListener {
                applyMotion(MotionSettings())
                rebuildPanelIfOpen()
            }
        }, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT))
        space(14f)


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
                    setTextColor(withAlpha(fg, if (allowed) 0.80f else 0.30f))
                    setPadding(dp(8f), 0, dp(6f), 0)
                }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
                row.addView(TextView(this).apply {
                    text = if (allowed) "✓" else "—"
                    textSize = 12f
                    setTextColor(withAlpha(fg, if (allowed) 0.50f else 0.20f))
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
            setTextColor(withAlpha(fg, 0.75f))
            background = roundedBg(withAlpha(fg, 0.06f), 6f,
                strokeColor = withAlpha(fg, 0.10f))
            setOnClickListener { requestCalendarOrLoad() }
        }, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT))


        val scroll = ScrollView(this).apply {
            isVerticalScrollBarEnabled = false
            background = roundedBg(
                withAlpha(mix(lk.bd.bg1, lk.bd.surface, 0.5f), 0.96f), 16f,
                strokeColor = withAlpha(fg, 0.10f),
            )
            elevation = dp(6f).toFloat()
            clipToOutline = true
            addView(col)
        }
        return scroll
    }
}
