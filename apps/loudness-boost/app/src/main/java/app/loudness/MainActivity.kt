package app.loudness

import android.app.Activity
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView

/**
 * One screen of cards: a master processing switch, Boost (switch, big readout, slider) and Equalizer (switch, preset
 * chips, a draggable 15-band curve). Every change is saved and applied live; a card whose switch is off is dimmed.
 */
open class MainActivity : Activity() {
    protected open val screenshotSettings: Boost.Settings? = null

    private lateinit var settings: Boost.Settings
    private lateinit var colors: Palette
    private lateinit var masterCard: LinearLayout
    private lateinit var masterSubtitle: TextView
    private lateinit var gainCard: LinearLayout
    private lateinit var gainValue: TextView
    private lateinit var gainSlider: SeekBar
    private lateinit var eqCard: LinearLayout
    private lateinit var eqSubtitle: TextView
    private lateinit var chips: LinearLayout
    private lateinit var eqView: EqView
    private val presetNames = Boost.PRESETS.keys.toList() + Boost.CUSTOM

    private val density get() = resources.displayMetrics.density
    private fun px(dp: Int) = (dp * density).toInt()
    private val wrap = ViewGroup.LayoutParams.WRAP_CONTENT
    private val match = ViewGroup.LayoutParams.MATCH_PARENT
    private val medium = Typeface.create("sans-serif-medium", Typeface.NORMAL)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        settings = screenshotSettings ?: Boost.load(this)
        colors = Palette(this)
        window.decorView.setBackgroundColor(colors.background)

        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val heading = LinearLayout(this).apply { gravity = android.view.Gravity.CENTER_VERTICAL }
        heading.addView(text("Boost & EQ", 32f, colors.text, medium))
        val version = runCatching { packageManager.getPackageInfo(packageName, 0).versionName }.getOrNull()
        if (!version.isNullOrBlank()) {
            heading.addView(text(version, 12f, colors.textSecondary), LinearLayout.LayoutParams(wrap, wrap).apply {
                marginStart = px(10)
            })
        }
        root.addView(heading, LinearLayout.LayoutParams(match, wrap).apply { topMargin = px(12) })
        root.addView(text("Louder, clearer sound for every app", 15f, colors.textSecondary), LinearLayout.LayoutParams(match, wrap).apply { topMargin = px(4); bottomMargin = px(20) })

        // ---- Master ----
        masterCard = card()
        masterSubtitle = text("", 14f, colors.textSecondary)
        masterCard.addView(headerRow(R.drawable.ps_power, "Audio processing", masterSubtitle, settings.processing) { update(processing = it) })
        root.addView(masterCard, cardParams())

        // ---- Boost ----
        gainCard = card()
        gainCard.addView(headerRow(R.drawable.ps_volume, "Boost", text("Extra volume, held below clipping by a limiter", 14f, colors.textSecondary), settings.gainOn) { update(gainOn = it) })
        gainValue = text("", 48f, colors.accent, medium).apply { gravity = Gravity.CENTER }
        gainCard.addView(gainValue, LinearLayout.LayoutParams(match, wrap).apply { topMargin = px(12) })
        gainSlider = SeekBar(this).apply {
            max = (Boost.MAX_GAIN_DB * 2).toInt() // 0.5 dB steps
            progressTintList = ColorStateList.valueOf(colors.accent)
            thumbTintList = ColorStateList.valueOf(colors.accent)
            progressBackgroundTintList = ColorStateList.valueOf(colors.outlineStrong)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(bar: SeekBar, progress: Int, fromUser: Boolean) { if (fromUser) update(gainDb = progress / 2f) }
                override fun onStartTrackingTouch(bar: SeekBar) {}
                override fun onStopTrackingTouch(bar: SeekBar) {}
            })
        }
        gainCard.addView(gainSlider, LinearLayout.LayoutParams(match, px(40)).apply { topMargin = px(4) })
        val scale = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        scale.addView(text("0 dB", 13f, colors.textSecondary), LinearLayout.LayoutParams(0, wrap, 1f))
        scale.addView(text("Reset", 14f, colors.accent, medium).apply {
            setPadding(px(16), px(8), px(16), px(8))
            setOnClickListener { update(gainDb = 0f); refresh() }
        })
        scale.addView(text("${Boost.MAX_GAIN_DB.toInt()} dB", 13f, colors.textSecondary).apply { gravity = Gravity.END }, LinearLayout.LayoutParams(0, wrap, 1f))
        gainCard.addView(scale, LinearLayout.LayoutParams(match, wrap).apply { leftMargin = px(16); rightMargin = px(16) })
        root.addView(gainCard, cardParams())

        // ---- Equalizer ----
        eqCard = card()
        eqSubtitle = text("", 14f, colors.textSecondary)
        eqCard.addView(headerRow(R.drawable.ps_equalizer, "Equalizer", eqSubtitle, settings.eqOn) { update(eqOn = it) })
        chips = LinearLayout(this)
        // The chip row runs to the card's edges, so chips scroll out under its rounded side instead of being cut off
        eqCard.addView(HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            clipToPadding = false
            setPadding(px(20), 0, px(12), 0)
            addView(chips)
        }, LinearLayout.LayoutParams(match, wrap).apply { topMargin = px(14); leftMargin = -px(20); rightMargin = -px(20) })
        eqView = EqView(this, colors).apply {
            onBandChanged = { i, db ->
                val wasCustom = settings.preset == Boost.CUSTOM
                update(bands = settings.bands.copyOf().also { it[i] = db }, preset = Boost.CUSTOM)
                if (!wasCustom) showChips()
            }
        }
        eqCard.addView(eqView, LinearLayout.LayoutParams(match, px(250)).apply { topMargin = px(12) })
        root.addView(eqCard, cardParams())

        val scroll = ScrollView(this).apply {
            addView(root)
            clipToPadding = false
            // Android 15+ draws edge to edge: keep content clear of the status and navigation bars
            setOnApplyWindowInsetsListener { v, insets ->
                val bars = insets.getInsets(WindowInsets.Type.systemBars())
                v.setPadding(px(16) + bars.left, px(16) + bars.top, px(16) + bars.right, px(16) + bars.bottom)
                insets
            }
        }
        setContentView(scroll)
        refresh()
    }

    // ---- building blocks ----

    private fun text(value: String, size: Float, color: Int, face: Typeface? = null) = TextView(this).apply {
        text = value
        textSize = size
        setTextColor(color)
        if (face != null) typeface = face
    }

    private fun rounded(color: Int, radiusDp: Int) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = px(radiusDp).toFloat()
    }

    private fun card() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = rounded(colors.card, 14)
        setPadding(px(20), px(18), px(20), px(18))
    }

    private fun cardParams() = LinearLayout.LayoutParams(match, wrap).apply { bottomMargin = px(12) }

    /** A card's title and subtitle beside its switch; tapping anywhere on the row flips the switch. */
    private fun headerRow(icon: Int, title: String, subtitle: TextView, checked: Boolean, onChange: (Boolean) -> Unit): LinearLayout {
        val row = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        row.addView(android.widget.ImageView(this).apply {
            setImageResource(icon)
            imageTintList = ColorStateList.valueOf(colors.accent)
            setPadding(px(9), px(9), px(9), px(9))
            background = rounded(colors.accentTint, 12)
        }, LinearLayout.LayoutParams(px(40), px(40)).apply { rightMargin = px(14) })
        val texts = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        texts.addView(text(title, 20f, colors.text, medium))
        texts.addView(subtitle, LinearLayout.LayoutParams(wrap, wrap).apply { topMargin = px(2) })
        row.addView(texts, LinearLayout.LayoutParams(0, wrap, 1f))
        val switch = Switch(this).apply {
            isChecked = checked
            val states = arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf())
            thumbTintList = ColorStateList(states, intArrayOf(colors.onAccent, colors.outlineStrong or 0xFF000000.toInt()))
            trackTintList = ColorStateList(states, intArrayOf(colors.accent, colors.chip))
            setOnCheckedChangeListener { _, on -> onChange(on) }
        }
        row.addView(switch, LinearLayout.LayoutParams(wrap, wrap).apply { leftMargin = px(12) })
        row.setOnClickListener { switch.toggle() }
        return row
    }

    private fun showChips() {
        chips.removeAllViews()
        for (name in presetNames) {
            val selected = name == settings.preset
            // "Custom" only appears once the curve has been changed by hand
            if (name == Boost.CUSTOM && !selected) continue
            chips.addView(text(name, 14f, if (selected) colors.accent else colors.textSecondary, medium).apply {
                background = rounded(if (selected) colors.accentTint else 0, 18)
                setPadding(px(16), px(9), px(16), px(9))
                isEnabled = settings.processing && settings.eqOn
                setOnClickListener {
                    val bands = Boost.PRESETS[name] ?: return@setOnClickListener
                    update(bands = bands.copyOf(), preset = name)
                    refresh()
                }
            }, LinearLayout.LayoutParams(wrap, wrap).apply { rightMargin = px(8) })
        }
        eqSubtitle.text = if (settings.preset == Boost.CUSTOM) "Custom curve · drag a point to tune it" else "${settings.preset} · drag a point to tune it"
    }

    // ---- state ----

    private fun update(
        processing: Boolean = settings.processing,
        eqOn: Boolean = settings.eqOn,
        gainOn: Boolean = settings.gainOn,
        gainDb: Float = settings.gainDb,
        bands: FloatArray = settings.bands,
        preset: String = settings.preset
    ) {
        settings = Boost.Settings(processing, eqOn, gainOn, gainDb, bands, preset)
        if (screenshotSettings == null) {
            Boost.save(this, settings)
            Boost.ensure(this)
        }
        gainValue.text = "+%.1f dB".format(settings.gainDb)
        showEnabled()
    }

    /** Pushes the current settings into every control. */
    private fun refresh() {
        gainValue.text = "+%.1f dB".format(settings.gainDb)
        gainSlider.progress = (settings.gainDb * 2).toInt()
        eqView.bands = settings.bands
        showChips()
        showEnabled()
    }

    private fun showEnabled() {
        val on = settings.processing
        masterSubtitle.text = if (on) "On · every app's sound goes through it" else "Off · sound plays untouched"
        masterCard.background = rounded(if (on) colors.accentSoft else colors.card, 14)
        setCardEnabled(gainCard, on && settings.gainOn, on)
        setCardEnabled(eqCard, on && settings.eqOn, on)
        for (i in 0 until chips.childCount) chips.getChildAt(i).isEnabled = on && settings.eqOn
    }

    /** Dims a card whose feature is off; its switch stays usable while processing is on. */
    private fun setCardEnabled(card: LinearLayout, enabled: Boolean, switchUsable: Boolean) {
        card.alpha = if (enabled) 1f else 0.5f
        for (i in 1 until card.childCount) setEnabledDeep(card.getChildAt(i), enabled)
        setEnabledDeep(card.getChildAt(0), switchUsable)
    }

    private fun setEnabledDeep(view: View, enabled: Boolean) {
        view.isEnabled = enabled
        if (view is ViewGroup) for (i in 0 until view.childCount) setEnabledDeep(view.getChildAt(i), enabled)
    }
}
