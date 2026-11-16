package app.loudness

import android.app.Activity
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.Switch
import android.widget.TextView

/**
 * A master processing switch, a Gain section (switch, slider, reset) and an EQ section (switch,
 * preset picker, 15-band tuner). Every change is saved and applied live; controls whose switch is
 * off are dimmed.
 */
class MainActivity : Activity() {

    private lateinit var settings: Boost.Settings
    private lateinit var gainValue: TextView
    private lateinit var gainSlider: SeekBar
    private lateinit var presetPicker: Spinner
    private lateinit var gainControls: LinearLayout
    private lateinit var eqControls: LinearLayout
    private lateinit var sections: LinearLayout
    private val bandSliders = mutableListOf<SeekBar>()
    private val bandLabels = mutableListOf<TextView>()
    private val presetNames = Boost.PRESETS.keys.toList() + Boost.CUSTOM
    private var updatingUi = false

    private val density get() = resources.displayMetrics.density
    private fun px(dp: Int) = (dp * density).toInt()
    private val wrap = ViewGroup.LayoutParams.WRAP_CONTENT
    private val match = ViewGroup.LayoutParams.MATCH_PARENT

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        settings = Boost.load(this)

        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(TextView(this).apply {
            text = "Loudness"
            textSize = 30f
            typeface = Typeface.DEFAULT_BOLD
        })
        root.addView(switchRow("Audio processing", settings.processing, 20f) { update(processing = it) }, LinearLayout.LayoutParams(match, wrap).apply { topMargin = px(16) })

        sections = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(sections)

        // ---- Gain ----
        sections.addView(header("Gain", settings.gainOn) { update(gainOn = it) })
        gainControls = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        gainValue = TextView(this).apply { textSize = 26f; gravity = Gravity.CENTER }
        gainControls.addView(gainValue, LinearLayout.LayoutParams(match, wrap))
        gainSlider = SeekBar(this).apply {
            max = (Boost.MAX_GAIN_DB * 2).toInt()
            setOnSeekBarChangeListener(listener { update(gainDb = it / 2f) })
        }
        gainControls.addView(gainSlider, LinearLayout.LayoutParams(match, wrap).apply { topMargin = px(8) })
        gainControls.addView(Button(this).apply {
            text = "Reset to 0 dB"
            setOnClickListener { update(gainDb = 0f); refresh() }
        }, LinearLayout.LayoutParams(wrap, wrap).apply { gravity = Gravity.END; topMargin = px(4) })
        sections.addView(gainControls)

        // ---- EQ ----
        sections.addView(header("EQ", settings.eqOn) { update(eqOn = it) })
        eqControls = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        presetPicker = Spinner(this).apply {
            adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, presetNames)
            onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(parent: AdapterView<*>?, view: View?, pos: Int, id: Long) {
                    if (updatingUi) return
                    val bands = Boost.PRESETS[presetNames[pos]] ?: return // "Custom" keeps the bands
                    update(bands = bands.copyOf(), preset = presetNames[pos])
                    refresh()
                }
                override fun onNothingSelected(parent: AdapterView<*>?) {}
            }
        }
        eqControls.addView(presetPicker, LinearLayout.LayoutParams(match, wrap).apply { bottomMargin = px(8) })
        for (i in Boost.CENTERS.indices) eqControls.addView(bandRow(i), LinearLayout.LayoutParams(match, px(44)))
        sections.addView(eqControls)

        val scroll = ScrollView(this).apply {
            addView(root)
            clipToPadding = false
            // Android 15+ draws edge to edge: keep content clear of the status and navigation bars
            setOnApplyWindowInsetsListener { v, insets ->
                val bars = insets.getInsets(WindowInsets.Type.systemBars())
                v.setPadding(px(20) + bars.left, px(16) + bars.top, px(20) + bars.right, px(16) + bars.bottom)
                insets
            }
        }
        setContentView(scroll)
        refresh()
    }

    private fun switchRow(label: String, checked: Boolean, size: Float, onChange: (Boolean) -> Unit) =
        Switch(this).apply {
            text = label
            textSize = size
            isChecked = checked
            setOnCheckedChangeListener { _, on -> onChange(on) }
        }

    private fun header(label: String, checked: Boolean, onChange: (Boolean) -> Unit) =
        switchRow(label, checked, 20f, onChange).apply {
            typeface = Typeface.DEFAULT_BOLD
            layoutParams = LinearLayout.LayoutParams(match, wrap).apply { topMargin = px(28); bottomMargin = px(8) }
        }

    private fun bandRow(i: Int) = LinearLayout(this).apply {
        gravity = Gravity.CENTER_VERTICAL
        addView(TextView(this@MainActivity).apply { text = hz(Boost.CENTERS[i]) }, LinearLayout.LayoutParams(px(44), wrap))
        val slider = SeekBar(this@MainActivity).apply {
            max = (Boost.BAND_RANGE_DB * 4).toInt() // -12..+12 dB in 0.5 dB steps
            setOnSeekBarChangeListener(listener { progress ->
                val bands = settings.bands.copyOf().also { it[i] = progress / 2f - Boost.BAND_RANGE_DB }
                update(bands = bands, preset = Boost.CUSTOM)
                showBand(i)
                selectPreset(Boost.CUSTOM)
            })
        }
        addView(slider, LinearLayout.LayoutParams(0, wrap, 1f))
        val value = TextView(this@MainActivity).apply { gravity = Gravity.END }
        addView(value, LinearLayout.LayoutParams(px(44), wrap))
        bandSliders += slider
        bandLabels += value
    }

    private fun listener(onUser: (Int) -> Unit) = object : SeekBar.OnSeekBarChangeListener {
        override fun onProgressChanged(bar: SeekBar, progress: Int, fromUser: Boolean) { if (fromUser) onUser(progress) }
        override fun onStartTrackingTouch(bar: SeekBar) {}
        override fun onStopTrackingTouch(bar: SeekBar) {}
    }

    private fun update(
        processing: Boolean = settings.processing,
        eqOn: Boolean = settings.eqOn,
        gainOn: Boolean = settings.gainOn,
        gainDb: Float = settings.gainDb,
        bands: FloatArray = settings.bands,
        preset: String = settings.preset
    ) {
        settings = Boost.Settings(processing, eqOn, gainOn, gainDb, bands, preset)
        Boost.save(this, settings)
        Boost.ensure(this)
        gainValue.text = "+%.1f dB".format(settings.gainDb)
        showEnabled()
    }

    /** Pushes the current settings into every control without triggering their listeners. */
    private fun refresh() {
        updatingUi = true
        gainValue.text = "+%.1f dB".format(settings.gainDb)
        gainSlider.progress = (settings.gainDb * 2).toInt()
        for (i in bandSliders.indices) {
            bandSliders[i].progress = ((settings.bands[i] + Boost.BAND_RANGE_DB) * 2).toInt()
            showBand(i)
        }
        selectPreset(settings.preset)
        updatingUi = false
        showEnabled()
    }

    private fun showEnabled() {
        sections.alpha = if (settings.processing) 1f else 0.4f
        setEnabledDeep(gainControls, settings.processing && settings.gainOn)
        setEnabledDeep(eqControls, settings.processing && settings.eqOn)
    }

    private fun setEnabledDeep(group: ViewGroup, enabled: Boolean) {
        group.alpha = if (enabled) 1f else 0.4f
        for (i in 0 until group.childCount) {
            val child = group.getChildAt(i)
            child.isEnabled = enabled
            if (child is ViewGroup && child !is Spinner) setEnabledDeep(child, enabled).also { child.alpha = 1f }
        }
    }

    private fun selectPreset(name: String) {
        val wasUpdating = updatingUi
        updatingUi = true
        presetPicker.setSelection(presetNames.indexOf(name).coerceAtLeast(0), false)
        updatingUi = wasUpdating
    }

    private fun showBand(i: Int) {
        bandLabels[i].text = "%+.1f".format(settings.bands[i])
    }

    private fun hz(f: Float) = if (f >= 1000f) "%.1fk".format(f / 1000f).replace(".0k", "k") else "${f.toInt()}"
}
