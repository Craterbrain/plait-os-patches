# plait-design

PlaitOS's shared look ("Graphite, refined") as an Android library, so every PlaitOS app follows the same system without
each one re-deciding it. Mocks and the reasoning are in `plait-os/mocks/graphite-refined`.

## Rules the components keep
- **Colours are roles.** The theme uses the system's wallpaper colours (Material You). Ask for
  `MaterialTheme.colorScheme.surfaceContainer`, never a hex value. A wallpaper with too little colour falls back to the
  Graphite blue (the ROM patches that; this library carries the same scheme for previews and for a system without dynamic colour).
- **Tone, not outlines.** Depth is `surface < surfaceContainer < ...High < ...Highest`; a hairline only separates rows in a group.
- **One accent.** `primary` is solid on the one action and on what is switched on; a selection is a tint of it.
- **Tonal icons.** Every system icon sits on a `TonalTile` (`primaryContainer` / `onPrimaryContainer`). Colour beyond that is state.
- **Floors.** Text is at least 12 sp, touch targets at least 48 dp.
- **Layout is the app's.** This library styles; it does not decide what an app's screens contain.

## Use
```kotlin
PlaitTheme {                        // system colours, dark or light by the system
    SettingsGroup {
        row { SwitchRow("Show seconds", on, { on = it }, icon = PlaitIcon.Timer) }
        row { SettingsRow("Clock position", icon = PlaitIcon.Clock, value = "Left", onClick = {}) }
    }
}
```
Components: `PlaitTopBar`, `SectionLabel`, `SettingsGroup`/`SettingsRow`/`SwitchRow`, `TonalTile`, `PlaitSwitch`,
`StateChip(text, Tone)`, `PlaitFilterChip`, `PlaitButton(kind)`. `./gradlew :sample:installDebug` puts every one on a phone.

In an app's `settings.gradle.kts` (the repo sits next to the apps):
```kotlin
includeBuild("../plait-design") { dependencySubstitution { substitute(module("app.plait:design")).using(project(":design")) } }
```
and `implementation("app.plait:design")`.

## Icons
Material Symbols Rounded (weight 400, Apache License 2.0), never hand-drawn. Add a line to `design/icons.txt`
(`timer = timer`, or `home = home, fill` for a filled variant too), run `python3 tools/icons.py`, use `PlaitIcon.Timer`.
It fetches the SVG, writes `ps_timer.xml` (an Android vector drawable) and updates `PlaitIcon.kt`; a wrong name fails
loudly. The SVGs are cached in `tools/icon-cache/`, so a rebuild needs no network. Names: https://fonts.google.com/icons

## Regenerating the fallback scheme
`tools/fallback_scheme.py` (needs `materialyoucolor`) rewrites `GraphiteScheme.kt` from the Graphite-blue seed, using the same
Tonal Spot algorithm Android runs for a wallpaper.
