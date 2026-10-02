package com.circularcalendar.app

import android.app.Activity
import android.appwidget.AppWidgetManager
import android.content.Intent
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.time.LocalDateTime

// ─────────────────────────────────────────────────────────────────────────────
// Настройка виджета: открывается при добавлении на экран и потом по долгому
// нажатию → «Настроить». Сверху — живое превью в пропорциях виджета.
// ─────────────────────────────────────────────────────────────────────────────

class WidgetConfigActivity : Activity() {

    private var id = AppWidgetManager.INVALID_APPWIDGET_ID
    private var cfg = WidgetConfig()
    private var events: List<CalendarEvent>? = null
    private val now = LocalDateTime.now()
    private val main = Handler(Looper.getMainLooper())
    private var gen = 0
    // Размер последнего превью (px) — геометрия для пересчёта жеста в кадр.
    private var pw = 0
    private var ph = 0
    // Жест над превью: пока пальцы на экране, картинка только двигается
    // трансформом вьюхи, по отпусканию кадр пересчитывается и перерисовывается.
    private var lastX = 0f
    private var lastY = 0f
    private var lastN = 0

    private lateinit var root: LinearLayout
    private lateinit var preview: ImageView
    private lateinit var previewBox: FrameLayout
    private lateinit var options: LinearLayout

    private fun dp(v: Float): Int = (v * resources.displayMetrics.density + 0.5f).toInt()

    private fun look(): Look =
        if (cfg.theme >= 0) Look(PRESETS[cfg.theme].second) else appSettings(this).look
    private fun fg(): Int = if (look().dark) WHITE else 0xFF1C1020.toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setResult(RESULT_CANCELED)
        id = intent.extras?.getInt(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
            ?: AppWidgetManager.INVALID_APPWIDGET_ID
        if (id == AppWidgetManager.INVALID_APPWIDGET_ID) { finish(); return }
        Fonts.ctx = applicationContext
        cfg = WidgetConfig.load(this, id)

        window.statusBarColor = 0
        window.navigationBarColor = 0

        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            fitsSystemWindows = true
        }
        previewBox = FrameLayout(this)
        preview = ImageView(this).apply { scaleType = ImageView.ScaleType.FIT_CENTER }
        previewBox.addView(preview, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        previewBox.clipChildren = true
        setupGestures()
        root.addView(previewBox, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f).apply {
            setMargins(dp(24f), dp(20f), dp(24f), dp(8f))
        })
        val scroll = ScrollView(this)
        options = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16f), dp(8f), dp(16f), dp(16f))
        }
        scroll.addView(options)
        root.addView(scroll, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        setContentView(root)

        rebuild()
        Thread {
            val evs = widgetEvents(this, now)
            main.post { events = evs; renderPreview() }
        }.start()
    }

    private fun set(c: WidgetConfig) {
        val themeChanged = c.theme != cfg.theme
        cfg = c
        if (themeChanged) rebuild() else rebuildOptions()
        renderPreview()
    }

    private fun rebuild() {
        val lk = look()
        root.background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(lk.bd.bg0, lk.bd.bg1))
        window.decorView.systemUiVisibility = if (lk.dark) 0 else
            View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
        rebuildOptions()
    }

    private fun rebuildOptions() {
        options.removeAllViews()
        fun space(h: Float) = options.addView(View(this), LinearLayout.LayoutParams(1, dp(h)))

        options.addView(TextView(this).apply {
            text = if (cfg.zoom < 0) "Свой кадр · двигай и зумь превью пальцами" else "Превью можно двигать и зумить пальцами"
            textSize = 12f
            gravity = Gravity.CENTER
            setTextColor(withAlpha(fg(), 0.5f))
        }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        space(10f)
        options.addView(label("Масштаб")); space(6f)
        toggleGrid(WIDGET_ZOOMS.map { it.first }, cfg.zoom, 4) { set(cfg.copy(zoom = it)) }
        space(10f)
        options.addView(label("Форма — клин по целым")); space(6f)
        toggleGrid(WIDGET_CUTS, cfg.cut, 4) { set(cfg.copy(cut = it)) }
        space(10f)
        options.addView(label("Положение")); space(6f)
        toggleGrid(WIDGET_POS, cfg.pos, 3) { set(cfg.copy(pos = it)) }
        space(10f)
        options.addView(label("Что движется со временем")); space(6f)
        toggleGrid(listOf("Круг (сейчас внизу)", "Стрелка"), if (cfg.arrowMoves) 1 else 0, 2) {
            set(cfg.copy(arrowMoves = it == 1))
        }
        space(10f)
        options.addView(label("Тема")); space(6f)
        toggleGrid(listOf("Как в приложении") + PRESETS.map { it.first }, cfg.theme + 1, 3) {
            set(cfg.copy(theme = it - 1))
        }
        space(10f)
        options.addView(label("Фон")); space(6f)
        toggleGrid(listOf("Без фона", "Фон темы"), if (cfg.background) 1 else 0, 2) {
            set(cfg.copy(background = it == 1))
        }
        space(16f)

        val acc = look().accent
        options.addView(TextView(this).apply {
            text = "Готово"
            textSize = 15f
            gravity = Gravity.CENTER
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setTextColor(if (luminance(acc) > 150) 0xFF1C1020.toInt() else WHITE)
            setPadding(0, dp(12f), 0, dp(12f))
            background = rounded(acc, 99f)
            setOnClickListener { done() }
        }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
    }

    private fun done() {
        cfg.save(this, id)
        val mgr = AppWidgetManager.getInstance(this)
        val (w, h) = widgetSizePx(this, mgr.getAppWidgetOptions(id))
        val c = cfg; val evs = events
        val app = applicationContext
        Thread {
            val bmp = renderWidget(app, w, h, c, evs ?: widgetEvents(app, now), now)
            mgr.updateAppWidget(id, WheelWidget.views(app, bmp))
            WheelWidget.schedule(app)
        }.start()
        setResult(RESULT_OK, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id))
        finish()
    }

    /** Превью в пропорциях виджета (если размер ещё неизвестен — квадрат). */
    private fun renderPreview() {
        val bw = previewBox.width; val bh = previewBox.height
        if (bw <= 0 || bh <= 0) { previewBox.post { renderPreview() }; return }
        val (ww, wh) = widgetSizePx(this, AppWidgetManager.getInstance(this).getAppWidgetOptions(id))
        val k = minOf(bw.toDouble() / ww, bh.toDouble() / wh)
        val w = (ww * k).toInt().coerceAtLeast(64); val h = (wh * k).toInt().coerceAtLeast(64)
        val my = ++gen
        val c = cfg; val evs = events ?: emptyList()
        Thread {
            val bmp = renderWidget(this, w, h, c, evs, now)
            main.post {
                if (my != gen) return@post
                pw = w; ph = h
                preview.setImageBitmap(bmp)
                preview.scaleX = 1f; preview.scaleY = 1f
                preview.translationX = 0f; preview.translationY = 0f
            }
        }.start()
    }

    private fun nowAngle(): Double {
        val app = appSettings(this)
        return TimeMap(now, app.counterClockwise, app.nowAtBottom, app.keplerOrbit, cfg.arrowMoves).nowAngle
    }

    private fun setupGestures() {
        val scaler = ScaleGestureDetector(this, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(d: ScaleGestureDetector): Boolean {
                // Масштаб вокруг точки между пальцами: p' = f + k·(p − f).
                val k = d.scaleFactor
                val cx = previewBox.width / 2f; val cy = previewBox.height / 2f
                val z = (preview.scaleX * k).coerceIn(0.2f, 40f)
                val kk = z / preview.scaleX
                preview.translationX = d.focusX - cx + kk * (cx + preview.translationX - d.focusX)
                preview.translationY = d.focusY - cy + kk * (cy + preview.translationY - d.focusY)
                preview.scaleX = z; preview.scaleY = z
                return true
            }
        })
        previewBox.setOnTouchListener { _, e ->
            scaler.onTouchEvent(e)
            // Сдвиг — по среднему положению пальцев.
            val up = e.actionMasked == MotionEvent.ACTION_POINTER_UP
            var sx = 0f; var sy = 0f; var n = 0
            for (i in 0 until e.pointerCount) {
                if (up && i == e.actionIndex) continue
                sx += e.getX(i); sy += e.getY(i); n++
            }
            if (n > 0) { sx /= n; sy /= n }
            when (e.actionMasked) {
                MotionEvent.ACTION_MOVE -> if (n == lastN) {
                    preview.translationX += sx - lastX
                    preview.translationY += sy - lastY
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> { commitGesture(); n = 0 }
            }
            lastX = sx; lastY = sy; lastN = n
            true
        }
    }

    /** Трансформ превью → свой кадр виджета. */
    private fun commitGesture() {
        val z = preview.scaleX.toDouble()
        val tx = preview.translationX.toDouble(); val ty = preview.translationY.toDouble()
        if (pw <= 0 || (z == 1.0 && tx == 0.0 && ty == 0.0)) return
        val g = WheelGeom(pw.toFloat(), ph.toFloat())
        val na = nowAngle()
        val f = widgetFrame(g, cfg, na, resources.displayMetrics.density)
        val s = f[0]
        // Центр кадра теперь показывает точку картинки c − t/z, а она — точка
        // контента F + (q − c)/s.
        val s2 = (s * z).coerceIn(0.5, 80.0)
        val fx = f[1] - tx / z / s
        val fy = f[2] - ty / z / s
        cfg = widgetCustom(cfg, g, na, s2, fx, fy)
        rebuildOptions()
        renderPreview()
    }

    // ── Мелкие вьюхи (как в панели приложения) ───────────────────────────────

    private fun rounded(color: Int, r: Float, stroke: Int? = null) = GradientDrawable().apply {
        setColor(color); cornerRadius = dp(r).toFloat()
        if (stroke != null) setStroke(dp(1f), stroke)
    }

    private fun label(text: String) = TextView(this).apply {
        this.text = text.uppercase()
        textSize = 10f
        letterSpacing = 0.12f
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        setTextColor(withAlpha(fg(), 0.45f))
    }

    private fun toggleGrid(opts: List<String>, selected: Int, perRow: Int, onTap: (Int) -> Unit) {
        val fg = fg(); val acc = look().accent
        opts.chunked(perRow).forEachIndexed { r, chunk ->
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            for (k in 0 until perRow) {
                val i = r * perRow + k
                val active = i == selected
                val tv = TextView(this).apply {
                    text = chunk.getOrNull(k) ?: ""
                    textSize = 12f
                    gravity = Gravity.CENTER
                    maxLines = 1
                    setPadding(dp(2f), dp(8f), dp(2f), dp(8f))
                    setTextColor(if (active) (if (luminance(acc) > 150) 0xFF1C1020.toInt() else WHITE) else withAlpha(fg, 0.7f))
                    typeface = if (active) Typeface.create("sans-serif-medium", Typeface.NORMAL) else Typeface.DEFAULT
                    background = rounded(if (active) acc else withAlpha(fg, 0.05f), 99f,
                        if (active) null else withAlpha(fg, 0.10f))
                    if (k < chunk.size) setOnClickListener { onTap(i) } else visibility = View.INVISIBLE
                }
                row.addView(tv, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                    if (k < perRow - 1) rightMargin = dp(4f)
                })
            }
            options.addView(row, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(4f) })
        }
    }
}
