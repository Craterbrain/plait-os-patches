package app.loudness

class ScreenshotActivity : MainActivity() {
    override val screenshotSettings = Boost.Settings(
        processing = true,
        eqOn = true,
        gainOn = true,
        gainDb = 4.5f,
        bands = Boost.PRESETS.getValue("Vocal / podcast").copyOf(),
        preset = "Vocal / podcast",
    )
}
