# plait-design

PlaitOS's shared look ("Graphite, refined") as an Android library, so every PlaitOS app follows the same system without
each one re-deciding it. Mocks and the reasoning are in `plait-os/mocks/graphite-refined`. Broker, Shield, Scan and
Backup are built with it; this document describes what they actually do, so a new app can copy it.

## Principles
- **Colours are roles.** The theme uses the system's wallpaper colours (Material You). Ask for
  `MaterialTheme.colorScheme.surfaceContainer`, never a hex value. A wallpaper with too little colour falls back to the
  Graphite blue (the ROM patches that; this library carries the same scheme for previews and for a system without dynamic colour).
- **Tone, not outlines.** Depth is `surface < surfaceContainer < ...High < ...Highest`: no borders, no shadows. A hairline
  only separates rows inside a group.
- **One accent.** `primary` is solid on the one action and on what is switched on; a selection is a tint of it, never a
  second solid colour.
- **Tonal icons.** Every system icon sits on a `TonalTile` (`primaryContainer` / `onPrimaryContainer`). Colour beyond that
  means a state.
- **Floors.** Text is at least 12 sp, touch targets at least 48 dp.
- **Layout is the app's.** This library styles; it does not decide what an app's screens contain. Restyling an app changes
  its components, not its layout.

## Theme
`PlaitTheme { … }` sets colours, type and shapes; dark or light follows the system. `PlaitTheme(dynamicColor = false)`
uses the Graphite-blue fallback scheme (previews).

**State colours.** Material 3 only has `error`. Success and warning come from `PlaitTheme.extras` (`success`,
`successContainer`, `warning`, `warningContainer`), fixed per mode and readable on any surface. Prefer the components
that use them (`StateChip`, `NoticeCard`) over reading them directly.

**Shapes** (`PlaitShapes`): modest corners. Nothing is a pill except chips and switches.

| Role | Radius | Used by |
|---|---|---|
| `extraSmall` / `small` | 6 / 8 dp | small elements |
| `medium` | 10 dp | buttons and controls |
| `large` | 14 dp | cards and groups |
| `extraLarge` | 28 dp | sheets |
| (fixed) | 12 dp | `TonalTile` |

**Type** (`PlaitTypography`): the system font, nothing bundled. Titles are semibold, body regular, and nothing is
smaller than 12 sp (Material's own scale goes down to 11).

| Style | Size | Use |
|---|---|---|
| `headlineSmall` | 24 sp semibold | screen title (`PlaitTopBar`, `PlaitAppTitle`) |
| `titleLarge` / `titleMedium` / `titleSmall` | 22 / 16 / 14 sp semibold | a card's title (`titleMedium` is the usual one) |
| `bodyLarge` / `bodyMedium` | 16 / 14 sp | row titles / second lines and values |
| `bodySmall` | 13 sp | explanations under a section label or a card title |
| `labelLarge` / `labelMedium` | 14 / 12 sp medium | buttons, chips, section labels / version, state chips |
| `PlaitDisplay` | 56 sp light | big numerals only (launcher clock, Boost & EQ's gain) |

## Screens
How the apps lay out a screen. Follow it unless the app's layout already says otherwise.

**Frame.** A `Scaffold` with a top bar, and a bottom `NavigationBar` only when the app has top-level sections (Broker:
Apps / Activity; Scan). The content is a `LazyColumn` with `contentPadding = PaddingValues(12.dp)` and
`verticalArrangement = Arrangement.spacedBy(8.dp)` between items.

**App header.**
- On the root screen of every Plait app, show the installed version name inline after the app name in muted label text.
  Use `PlaitAppTitle("App name")` inside a Material top bar, or set `showAppVersion = true` on `PlaitTopBar`.
- Put the Settings gear at the top right of the app bar. Use `PlaitIcon.Settings` with the accessible label "Settings".
  The gear opens the app's settings. Keep Settings out of the bottom navigation.
- A screen below the root shows its own name (no version) and a back arrow on the left (`PlaitIcon.Back`, label "Back").

**Sections.** A `SectionLabel("Blocklists")` over each group. When a section needs explaining, one or two short sentences
follow in `bodySmall`, `onSurfaceVariant`, inset `8.dp` horizontally to line up with the label.

**Cards.** Content goes in `PlaitCard`, with `16.dp` padding inside (`20.dp` for a status card) and `4.dp`–`8.dp`
between the lines in it. A card that is a choice or a toggle is tappable as a whole (`onClick`), with its `PlaitSwitch`
or selection state on the right. Where an app has one overall state, a status card leads the root screen (Shield:
Broker connected, DNS provider, blocklist size).

**States and notices.** A short state is a `StateChip(text, Tone)`: Permissive, Restricted, Denied, Protected. Something
the person must read (a problem, a warning, good news) is a `NoticeCard(tone)`: `Danger` for failures, `Warning` for
risks, `Success` for done, `Accent` for information. Both tint the surface; neither uses a solid state colour.

**Actions.** One `PlaitFilledButton` per screen, for its main action. Every other action is a `PlaitTonalButton`. A
row of buttons uses `spacedBy(12.dp)`. Small inline actions are `PlaitActionChip`s, and choices among a few options are
a row of `PlaitFilterChip`s (selected = an accent tint).

**Dialogs.** A Material `AlertDialog` for confirmations, with `TextButton`s for its confirm and dismiss actions. Say
what will happen in the confirm button's label ("Remove", not "OK").

**Lists.** Every list is one `SettingsGroup`: `SettingsRow` / `SwitchRow` for settings, `ListRow` for everything else
(apps, devices, people, conversations), with a tonal tile, a `PlaitAvatar` or an app icon on the left and hairlines
between rows. A searchable list opens with a `PlaitSearchField` under the header. People are always `PlaitAvatar`s.

**Words.** Sentence case for titles, labels and buttons ("Apply blocklists"). Explanations are short and plain, and
say what happens rather than how it works.

**Bottom bars.** Every bar at the bottom of a screen looks the same, whether it holds sections or actions. One
bar per screen; a screen never shows two bars with the same choices.

| | Spec |
|---|---|
| Container | `surfaceContainerLow`, 80 dp tall, no divider line, no shadow. The system navigation bar takes the same colour. |
| Section item (`PlaitNavItem`) | 24 dp icon centred in a 64 x 32 dp pill (16 dp corners); 12 sp `labelMedium` under it. |
| Selected section | pill `primary` at 16 %, icon `primary`, label `onSurface`. Unselected: no pill, icon and label `onSurfaceVariant`. |
| Action item | a 40 dp `TonalTile` (12 dp corners, `primaryContainer` / `onPrimaryContainer`) in a 48 dp touch target, no label (the content description names it). |
| Main action | the one action that finishes the screen (apply, send): the same tile in solid `primary` / `onPrimary`. At most one per bar. |
| Order | sections spread evenly; actions: dismiss (close) at the start, the main action at the end, the rest centred. |

A section bar, in Compose:

```kotlin
Scaffold(
    bottomBar = {
        PlaitNavigationBar {
            PlaitNavItem(tab == 0, { tab = 0 }, PlaitIcon.Image, "Photos")
            PlaitNavItem(tab == 1, { tab = 1 }, PlaitIcon.Folder, "Albums")
            PlaitNavItem(tab == 2, { tab = 2 }, PlaitIcon.Search, "Explore")
        }
    },
) { padding -> /* … */ }
```

An action bar, in Compose:

```kotlin
@Composable
fun ActionTile(icon: Int, label: String, main: Boolean = false, onClick: () -> Unit) {
    val c = MaterialTheme.colorScheme
    IconButton(onClick, Modifier.size(48.dp)) {          // 48 dp touch target
        Box(
            Modifier.size(40.dp).background(if (main) c.primary else c.primaryContainer, RoundedCornerShape(12.dp)),
            contentAlignment = Alignment.Center,
        ) { PlaitIcon(icon, label, tint = if (main) c.onPrimary else c.onPrimaryContainer) }
    }
}

Row(
    Modifier.fillMaxWidth().height(80.dp).background(MaterialTheme.colorScheme.surfaceContainerLow)
        .padding(horizontal = 12.dp),
    verticalAlignment = Alignment.CenterVertically,
) {
    ActionTile(PlaitIcon.Close, "Cancel") { cancel() }
    Spacer(Modifier.weight(1f))
    ActionTile(PlaitIcon.Edit, "Edit") { edit() }
    ActionTile(PlaitIcon.Share, "Share") { share() }
    Spacer(Modifier.weight(1f))
    ActionTile(PlaitIcon.Check, "Apply", main = true) { apply() }
}
```

Apps built on Android views (the AOSP forks) use the same spec; the Gallery's `com.android.gallery3d.util.PlaitBars`
is the reference to copy:

```java
LinearLayout bar = new LinearLayout(context);
PlaitBars.styleBar(bar);                                   // surfaceContainerLow, no divider
bar.addView(PlaitBars.navItem(context, R.drawable.ps_image, "Photos", selected, v -> show(0)),
        new LinearLayout.LayoutParams(0, dp(PlaitBars.BAR_HEIGHT_DP), 1));
ImageButton apply = PlaitBars.actionTile(context, R.drawable.ps_check, "Apply", /* main */ true, v -> apply());
bar.addView(apply, PlaitBars.tileParams(context));          // 48 dp touch target around a 40 dp tile
getWindow().setNavigationBarColor(PlaitBars.barColor(context));
```

**Sheets in view-based apps.** Pickers, details and quick settings are a `PlaitSheet` there too; the Gallery's
`com.android.gallery3d.util.PlaitSheet` is the view version (28 dp top corners, handle, `surfaceContainerHigh`, an
optional title) with the guide's rows: `row(icon, title, subtitle, onClick)` (a tonal tile), `switchRow(...)`,
`detail(label, value)` (a muted label over the value), `section(label)` and `buttons(...)` (one filled, the rest
tonal). Confirmations stay `AlertDialog`s whose buttons say what happens ("Discard" / "Save copy").

## Components

| Component | Use it for |
|---|---|
| `PlaitTopBar(title, subtitle?, showAppVersion, actions)` | a screen title without a Material top bar |
| `PlaitAppTitle(name)` | app name + installed version, inside a Material `TopAppBar` on the root screen |
| `SectionLabel(text)` | the heading over a group |
| `PlaitSearchField(query, onQueryChange, placeholder)` | the 48 dp search field under the header of every searchable list |
| `ListRow(title, subtitle?, lead?, meta?, emphasized, onClick?, trailing?)` | a row of any list (apps, devices, people, conversations) inside a `SettingsGroup` |
| `PlaitAvatar(name, key = name)` | a person: their initial on one of three tones, the same person always the same tone |
| `PlaitCard(onClick?, color?) { … }` | any card: one tonal surface, no outline, no shadow |
| `NoticeCard(tone) { … }` | a message about a state: a tint of the state colour over the surface |
| `SettingsGroup { row { … } }` | a card of settings rows with hairlines between them |
| `SettingsRow(title, subtitle?, icon?, value?, onClick?, trailing?)` | one row: tonal tile, title, second line, value, chevron |
| `SwitchRow(title, checked, onCheckedChange, …)` | a row with a switch; the whole row toggles it |
| `TonalTile(icon, size = 40.dp)` | the tinted square behind every system icon |
| `PlaitIcon(icon, contentDescription)` | an icon from `PlaitIcon.*` in the current content colour |
| `PlaitSwitch(checked, onCheckedChange)` | the switch: primary when on, an outlined thumb when off |
| `StateChip(text, Tone)` | a short status label (`Tone.Neutral`, `Success`, `Warning`, `Danger`, `Accent`) |
| `PlaitFilterChip(label, selected, onClick)` | one of a few options; selected is an accent tint |
| `PlaitActionChip(label, onClick)` | a small inline action with a 48 dp touch area |
| `PlaitFilledButton { … }` / `PlaitTonalButton { … }` | the screen's main action / every other action, 48 dp tall |
| `PlaitButton(text, onClick, kind)` | the same buttons with a plain text label (`Primary`, `Tonal`, `Text`) |
| `PlaitFab(icon, onClick, text?)` | the floating button: 56 dp, 16 dp corners, primary container; extended with a label |
| `PlaitNavigationBar { PlaitNavItem(...) }` | the bottom bar for an app's top-level sections, with a tinted indicator |
| `PlaitSheet(onDismiss, title?) { … }` | pickers and quick choices: 28 dp top corners, handle, raised surface |

`./gradlew :sample:installDebug` puts every component on a phone.

## PlaitSheet in Gallery

Gallery implements `PlaitSheet` in Java with Android Views. It follows the same visual pattern as the [Compose PlaitSheet](design/src/main/java/app/plait/design/Patterns.kt#L186). The dialog opens at the bottom at full width, with 28 dp top corners, a handle, an optional title, and a 50% scrim. Its surface and navigation bar use the system's raised surface color for the current light or dark theme. Short sheets open at their content height. Long sheets are capped at 85% of the screen height, with a scrolling body below the title. The handle is visual, and the dialog has no drag gesture. [Gallery sheet implementation](../../grapheneos-2026100600/packages/apps/Gallery2/src/com/android/gallery3d/util/PlaitSheet.java#L43)

Gallery uses the sheet for three kinds of interaction.

- **Settings** opens from the library gear. Tapping anywhere on the group-by-date row toggles it, saves the preference, and refreshes the grid. The sheet stays open. [Settings call site](../../grapheneos-2026100600/packages/apps/Gallery2/src/com/android/gallery3d/app/PlaitGalleryActivity.java#L917)
- **Add to album** puts **New album** first, followed by destination rows with tonal folder icons, album names, and paths. A row dismisses the sheet before running its action. Choosing an existing album copies the photo there. Creating an album opens an alert dialog for the name. [Album picker and name dialog](../../grapheneos-2026100600/packages/apps/Gallery2/src/com/android/gallery3d/app/PlaitGalleryActivity.java#L1189)
- **Photo details** uses the filename as its title and presents each field as a muted label above a selectable value. When EXIF data is available, it appears in its own section. [Details call site](../../grapheneos-2026100600/packages/apps/Gallery2/src/com/android/gallery3d/app/PlaitGalleryActivity.java#L1341)

The Settings call site shows how the activity supplies the current value and owns the update, while the sheet supplies the row and handles the tap.

```java
boolean enabled = getPreferences(MODE_PRIVATE).getBoolean(GROUP_DATES, true);
com.android.gallery3d.util.PlaitSheet sheet = new com.android.gallery3d.util.PlaitSheet(
        this, getText(R.string.plait_gallery_settings));
sheet.add(sheet.switchRow(getText(R.string.plait_gallery_group_dates), null, enabled, checked -> {
    getPreferences(MODE_PRIVATE).edit().putBoolean(GROUP_DATES, checked).apply();
    updateTiles();
}));
sheet.show();
```

## Use
In an app's `settings.gradle.kts` (the repo sits next to the apps):
```kotlin
includeBuild("../plait-design") { dependencySubstitution { substitute(module("app.plait:design")).using(project(":design")) } }
```
and `implementation("app.plait:design")`. A root screen then looks like this:
```kotlin
PlaitTheme {
    Scaffold(topBar = {
        TopAppBar(
            title = { PlaitAppTitle("Shield") },
            actions = { IconButton(onClick = onSettings) { PlaitIcon(PlaitIcon.Settings, "Settings") } },
        )
    }) { padding ->
        LazyColumn(Modifier.padding(padding), contentPadding = PaddingValues(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item { SectionLabel("Blocklists") }
            item {
                PlaitCard(Modifier.fillMaxWidth(), onClick = { on = !on }) {
                    Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("HaGeZi Light", style = MaterialTheme.typography.titleMedium)
                            Text("Ads, tracking, metrics", style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        PlaitSwitch(on, { on = it })
                    }
                }
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    PlaitFilledButton(onClick = apply) { Text("Apply blocklists") }
                    PlaitTonalButton(onClick = turnOff) { Text("Turn off") }
                }
            }
        }
    }
}
```

## App examples

These mockups use illustrative data and Graphite fallback colors. Version labels and layouts reflect the design previews. The snippets come from the app implementations and ROM patches.

### Broker

The app list puts search above the access groups. Each group uses the same row layout and state chips.

![Broker app list showing search, access groups, and the Apps and Activity tabs](docs/app-mockups/broker.webp)

Implementation in [plait-broker/MainActivity.kt](../plait-broker/broker/src/main/java/app/plait/broker/MainActivity.kt#L477).

```kotlin
val accessModes = listOf(MODE_PERMISSIVE, MODE_RESTRICTED, MODE_DENIED, MODE_BLOCKED)
val groups = shown.groupBy { if (it.mode in accessModes) it.mode else MODE_DENIED }
Column(Modifier.fillMaxSize()) {
    PlaitSearchField(query, onQuery, "Search apps", Modifier.padding(horizontal = 12.dp, vertical = 6.dp))
    // Access groups follow.
}
```

### Shield

Blocklists share one card with inset dividers. Each row toggles its own entry in the saved selection.

![Shield protection status, DNS provider, and grouped blocklist switches](docs/app-mockups/shield.webp)

Implementation in [plait-broker/MainActivity.kt](../plait-broker/shield/src/main/java/app/plait/shield/MainActivity.kt#L218).

```kotlin
SettingsGroup(inset = 14.dp, sidePadding = 0.dp) {
    BLOCKLISTS.forEach { l ->
        row {
            ListToggleRow(l, l.id in selected) { on ->
                selected = if (on) selected + l.id else selected - l.id
                prefs?.edit()?.putStringSet("lists", selected)?.apply()
            }
        }
    }
}
```

### Scan

Search appears once the first scan has results. The visible rows determine where the rounded ends of the device group appear.

![Scan network overview, device search, and grouped device results](docs/app-mockups/scan.webp)

Implementation in [plait-broker/MainActivity.kt](../plait-broker/scanner/src/main/java/app/plait/scan/MainActivity.kt#L226).

```kotlin
if (hosts.isNotEmpty()) item { PlaitSearchField(query, { query = it }, "Search devices", Modifier.padding(bottom = 8.dp)) }
if (hosts.isNotEmpty() && shown.isEmpty()) item {
    Text("No devices match", Modifier.padding(8.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
}
itemsIndexed(shown, key = { _, h -> h.ip }) { i, h -> HostRow(h, first = i == 0, last = i == shown.lastIndex) { selected = h.ip } }
```

### Backup

The dashboard leads with a tonal overview card. This excerpt shows how its first row pairs a quiet label with a more prominent value.

![Backup dashboard concept showing a backup status card and grouped actions](docs/app-mockups/backup.webp)

Implementation in [plait-backup/DashboardScreen.kt](../plait-backup/app/src/main/java/app/plait/backup/DashboardScreen.kt#L158).

```kotlin
Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainer, modifier = Modifier.fillMaxWidth()) {
    Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(painterResource(PlaitIcon.Archive), null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(10.dp))
            Text("Last backup", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.weight(1f))
            Text(if (at == 0L) "Never" else DateFormat.format("MMM d, h:mm a", at).toString(), style = MaterialTheme.typography.titleSmall)
        }
        // Storage and broker session rows follow.
    }
}
```

### Phone

The call log rounds the first and last rows in each day group. Interior corners use a smaller radius.

![Phone favorites above the call log with a floating Dial button](docs/app-mockups/phone.webp)

Implementation in [plait-os/0009-PlaitOS-group-call-log-card-corners-by-day.patch](../plait-os/rom-overlay/patches/packages_apps_Dialer/0009-PlaitOS-group-call-log-card-corners-by-day.patch#L29).

```java
private void styleCallLogGroup(View row, boolean first, boolean last) {
  float density = row.getResources().getDisplayMetrics().density;
  float top = (first ? 14f : 6f) * density;
  float bottom = (last ? 14f : 6f) * density;
  GradientDrawable background = (GradientDrawable) row.getBackground().mutate();
  background.setCornerRadii(new float[] {top, top, top, top, bottom, bottom, bottom, bottom});
}
```

### Contacts

The main list adds the version to its title. The mockup also shows the tonal avatars and New contact action.

![Contacts search and grouped initials with a New contact button](docs/app-mockups/contacts.webp)

Implementation in [plait-os/0003-PlaitOS-Contacts-title-with-its-version-soft-avatar-.patch](../plait-os/rom-overlay/patches/packages_apps_Contacts/0003-PlaitOS-Contacts-title-with-its-version-soft-avatar-.patch#L212).

```java
@Override
public void setTitle(CharSequence title) {
    if (title != null && TextUtils.equals(title.toString(), getString(R.string.contactsList))) {
        title = com.android.contacts.util.PlaitTitle.withVersion(this, title);
    }
    super.setTitle(title);
}
```

### Messages

The attachment popup uses a raised surface with 28 dp top corners and four equally sized tiles per row.

![Messages conversation list with pinned and recent groups](docs/app-mockups/messages.webp)
![Messages conversation with the attachment sheet open](docs/app-mockups/messages-attach.webp)

Implementation in [plait-messaging/ConversationAttachSheet.kt](../plait-messaging/src/com/android/messaging/ui/conversation/composer/ui/ConversationAttachSheet.kt#L235).

```kotlin
Surface(
    modifier = Modifier.fillMaxWidth(),
    shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
    color = MaterialTheme.colorScheme.surfaceContainerHigh,
    shadowElevation = 6.dp,
) {
    Column(
        Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 10.dp, bottom = 16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Box(
            Modifier.align(Alignment.CenterHorizontally).size(width = 36.dp, height = 4.dp)
                .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f), RoundedCornerShape(2.dp)),
        )
        tiles.chunked(4).forEach { row ->
            Row(Modifier.fillMaxWidth()) {
                row.forEach { tile ->
                    AttachTileView(
                        tile = tile,
                        modifier = Modifier.weight(1f),
                        onClick = {
                            onDismiss()
                            onAction(tile.action)
                        },
                    )
                }
                repeat(4 - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}
```

### Clock

Bottom tabs use a custom icon-and-label view. Each tab also gets an accessible description from its label resource.

![Clock mockup with bottom tabs](docs/app-mockups/clock.webp)

Implementation in [plait-os/0001-PlaitOS-Give-Clock-a-Monet-theme-and-bottom-tabs.patch](../plait-os/rom-overlay/patches/packages_apps_DeskClock/0001-PlaitOS-Give-Clock-a-Monet-theme-and-bottom-tabs.patch#L831).

```java
final UiDataModel.Tab model = UiDataModel.getUiDataModel().getTab(i);
final TabLayout.Tab tab = mTabLayout.newTab()
        .setTag(model)
        .setText(model.getLabelResId())
        .setContentDescription(model.getLabelResId())
        .setCustomView(R.layout.tab_item);
final View item = tab.getCustomView();
((ImageView) item.findViewById(R.id.clock_tab_icon)).setImageResource(model.getIconResId());
mTabLayout.addTab(tab);
```

## Icons
Material Symbols Rounded (weight 400, Apache License 2.0), never hand-drawn. Add a line to `design/icons.txt`
(`timer = timer`, or `home = home, fill` for a filled variant too), run `python3 tools/icons.py`, use `PlaitIcon.Timer`.
It fetches the SVG, writes `ps_timer.xml` (an Android vector drawable) and updates `PlaitIcon.kt`; a wrong name fails
loudly. The SVGs are cached in `tools/icon-cache/`, so a rebuild needs no network. Names: https://fonts.google.com/icons

Every icon a PlaitOS app shows comes from here, including the AOSP forks that don't use this library: they copy the
`ps_*.xml` files they need (the Gallery's `plait-os/scripts/gallery-icons.py` copies a fixed list and fails when one is
missing from `icons.txt`). No app fetches icons itself or keeps bitmap icons; the drawables are black, so tint them
with the content colour.

## Regenerating the fallback scheme
`tools/fallback_scheme.py` (needs `materialyoucolor`) rewrites `GraphiteScheme.kt` from the Graphite-blue seed, using the same
Tonal Spot algorithm Android runs for a wallpaper.
