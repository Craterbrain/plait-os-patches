package app.loudness

import android.content.Context
import android.media.audiofx.DynamicsProcessing
import android.util.Log
import kotlin.math.sqrt

/**
 * The loudness chain: a 15-band EQ, a gain, then a limiter so the gain cannot clip. With the EQ
 * on, the gain is folded into the band gains (one stage); with it off, it moves to the input gain.
 * Runs in Android's own DynamicsProcessing effect inside the audio HAL; this app only configures it.
 */
object Boost {
    const val MAX_GAIN_DB = 11f
    const val BAND_RANGE_DB = 12f

    val CENTERS = floatArrayOf(
        25f, 40f, 63f, 100f, 160f, 250f, 400f, 630f, 1000f, 1600f, 2500f, 4000f, 6300f, 10000f, 16000f
    )

    const val CUSTOM = "Custom"

    /** Preset name to band gains in dB (one per entry in [CENTERS]). */
    val PRESETS: Map<String, FloatArray> = linkedMapOf(
        "Flat" to FloatArray(CENTERS.size),
        "Bone conduction" to floatArrayOf(4f, 4f, 4f, 3.5f, 3f, 2f, 0.5f, 0f, 0f, 0f, 0.5f, 1f, 1f, 0.5f, 0f),
        "Bass" to floatArrayOf(6f, 6f, 5f, 4f, 3f, 1.5f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f),
        "Treble" to floatArrayOf(0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 1f, 2f, 3f, 4f, 4.5f, 5f),
        "Vocal / podcast" to floatArrayOf(-3f, -3f, -2f, -1f, 0f, 1f, 2f, 3f, 3.5f, 3f, 2f, 1f, 0f, -1f, -2f),
        "Loudness" to floatArrayOf(5f, 4.5f, 4f, 3f, 1.5f, 0f, -1f, -1.5f, -1f, 0f, 1.5f, 3f, 4f, 4.5f, 4f),
        // Curve carried over from the old JamesDSP setup
        "JamesDSP (old)" to floatArrayOf(-1.91f, -2.04f, -3f, -3f, -0.5f, 1.5f, 3.5f, 3.5f, 3.5f, 3f, 2f, 1.5f, 0f, 0f, -1.5f),
    )

    private const val LIMITER_ATTACK_MS = 1f
    private const val LIMITER_RELEASE_MS = 89.5f
    private const val LIMITER_RATIO = 10f
    private const val LIMITER_THRESHOLD_DB = -0.1f

    private const val TAG = "LoudnessBoost"

    // ---- settings ----

    class Settings(
        val processing: Boolean,
        val eqOn: Boolean,
        val gainOn: Boolean,
        val gainDb: Float,
        val bands: FloatArray,
        val preset: String
    )

    private const val PREFS = "boost"

    fun load(context: Context): Settings {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val bands = p.getString("bands", null)?.split(';')?.mapNotNull { it.toFloatOrNull() }
            ?.takeIf { it.size == CENTERS.size }?.toFloatArray() ?: FloatArray(CENTERS.size)
        return Settings(
            processing = p.getBoolean("processing", true),
            eqOn = p.getBoolean("eq_on", true),
            gainOn = p.getBoolean("gain_on", true),
            gainDb = p.getFloat("gain_db", 0f).coerceIn(0f, MAX_GAIN_DB),
            bands = bands,
            preset = p.getString("preset", "Flat") ?: "Flat"
        )
    }

    fun save(context: Context, s: Settings) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean("processing", s.processing)
            .putBoolean("eq_on", s.eqOn)
            .putBoolean("gain_on", s.gainOn)
            .putFloat("gain_db", s.gainDb.coerceIn(0f, MAX_GAIN_DB))
            .putString("bands", s.bands.joinToString(";"))
            .putString("preset", s.preset)
            .apply()
    }

    // ---- effect ----

    private var effect: DynamicsProcessing? = null

    /**
     * Applies the saved settings: attaches the effect (or updates it), or releases it when
     * processing is off so it costs nothing. The effect lives as long as this process; the Magisk
     * module installs the app as a persistent system app so Android never kills it.
     */
    @Synchronized
    fun ensure(context: Context) {
        val s = load(context)
        try {
            if (!s.processing) {
                effect?.release()
                effect = null
                Log.i(TAG, "Processing off")
                return
            }
            val dp = effect?.takeIf { it.hasControl() } ?: run {
                effect?.release()
                create().also { effect = it }
            }
            apply(dp, s)
            Log.i(TAG, "Processing on, gain ${if (s.gainOn) "+${s.gainDb} dB" else "off"}, EQ ${if (s.eqOn) s.preset else "off"}")
        } catch (e: Exception) {
            Log.e(TAG, "Could not attach DynamicsProcessing to the output mix", e)
        }
    }

    /** DynamicsProcessing bands are defined by their upper cutoff; use the midpoint to the next centre. */
    private fun cutoffHz(i: Int): Float =
        if (i == CENTERS.lastIndex) 20000f else sqrt(CENTERS[i] * CENTERS[i + 1])

    private fun create(): DynamicsProcessing {
        val config = DynamicsProcessing.Config.Builder(
            DynamicsProcessing.VARIANT_FAVOR_FREQUENCY_RESOLUTION,
            2,                   // stereo
            true, CENTERS.size,  // pre-EQ (in use so it can be switched on and off later)
            false, 0,            // no multiband compressor
            false, 0,            // no post-EQ
            true                 // limiter
        ).build()
        // Session 0 = the global output mix, so every app's playback goes through it
        return DynamicsProcessing(0, 0, config)
    }

    private fun apply(dp: DynamicsProcessing, s: Settings) {
        val gain = if (s.gainOn) s.gainDb else 0f
        val eq = DynamicsProcessing.Eq(true, s.eqOn, CENTERS.size)
        for (i in CENTERS.indices) {
            eq.setBand(i, DynamicsProcessing.EqBand(s.eqOn, cutoffHz(i), s.bands[i] + gain))
        }
        dp.setPreEqAllChannelsTo(eq)
        dp.setInputGainAllChannelsTo(if (s.eqOn) 0f else gain)
        dp.setLimiterAllChannelsTo(
            DynamicsProcessing.Limiter(
                true, true, 0,
                LIMITER_ATTACK_MS, LIMITER_RELEASE_MS, LIMITER_RATIO, LIMITER_THRESHOLD_DB, 0f
            )
        )
        dp.enabled = true
    }
}
