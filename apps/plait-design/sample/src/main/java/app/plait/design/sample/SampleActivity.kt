package app.plait.design.sample

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.plait.design.ButtonKind
import app.plait.design.ListRow
import app.plait.design.PlaitAvatar
import app.plait.design.PlaitFab
import app.plait.design.PlaitNavItem
import app.plait.design.PlaitNavigationBar
import app.plait.design.PlaitSearchField
import app.plait.design.PlaitSheet
import app.plait.design.TonalTile
import app.plait.design.PlaitButton
import app.plait.design.PlaitFilterChip
import app.plait.design.PlaitIcon
import app.plait.design.PlaitTheme
import app.plait.design.PlaitTopBar
import app.plait.design.SectionLabel
import app.plait.design.SettingsGroup
import app.plait.design.SettingsRow
import app.plait.design.StateChip
import app.plait.design.SwitchRow
import app.plait.design.Tone

/** Every component once, on the system's colours: install it, change the wallpaper, switch dark and light. */
class SampleActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            PlaitTheme {
                var a by remember { mutableStateOf(true) }
                var b by remember { mutableStateOf(false) }
                var filter by remember { mutableStateOf(0) }
                var query by remember { mutableStateOf("") }
                var tab by remember { mutableStateOf(0) }
                var sheet by remember { mutableStateOf(false) }
                Column(Modifier.background(MaterialTheme.colorScheme.surface).statusBarsPadding().verticalScroll(rememberScrollState())) {
                    PlaitTopBar("Plait Design", subtitle = "Graphite, refined")
                    SectionLabel("Settings")
                    SettingsGroup {
                        row { SettingsRow("Clock position", icon = PlaitIcon.Clock, subtitle = "Left", onClick = {}) }
                        row { SwitchRow("Show seconds", a, { a = it }, icon = PlaitIcon.Timer) }
                        row { SwitchRow("Also on the lock screen", b, { b = it }, icon = PlaitIcon.LockOpen, subtitle = "Off") }
                        row { SettingsRow("Reset", icon = PlaitIcon.Restart, value = "Stock", onClick = {}) }
                    }
                    SectionLabel("Filters")
                    Row(Modifier.padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf("Permissive", "Restricted", "Denied").forEachIndexed { i, t -> PlaitFilterChip(t, filter == i, { filter = i }) }
                    }
                    SectionLabel("States")
                    Row(Modifier.padding(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        StateChip("Permissive", Tone.Accent); StateChip("Protected", Tone.Success)
                        StateChip("Restricted", Tone.Warning); StateChip("Denied", Tone.Danger); StateChip("Off", Tone.Neutral)
                    }
                    SectionLabel("Buttons")
                    Row(Modifier.padding(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        PlaitButton("Apply", {}, kind = ButtonKind.Primary); PlaitButton("Turn off", {}); PlaitButton("Cancel", {}, kind = ButtonKind.Text)
                    }
                    SectionLabel("Search and lists")
                    PlaitSearchField(query, { query = it }, "Search people", Modifier.padding(horizontal = 12.dp))
                    Spacer(Modifier.height(8.dp))
                    SettingsGroup {
                        listOf("Alex Rivera" to "Mobile", "Jordan Lee" to "Work", "Sam" to "Mobile").forEach { (n, s) ->
                            row { ListRow(n, subtitle = s, lead = { PlaitAvatar(n) }, meta = "9:02", onClick = {}) }
                        }
                        row { ListRow("Router", subtitle = "192.168.1.1", lead = { TonalTile(PlaitIcon.Wifi) }, meta = "gateway") }
                    }
                    SectionLabel("Floating button and sheet")
                    Row(Modifier.padding(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        PlaitFab(PlaitIcon.Add, { sheet = true }, text = "Open sheet"); PlaitFab(PlaitIcon.Add, { sheet = true }, contentDescription = "Add")
                    }
                    if (sheet) PlaitSheet({ sheet = false }, title = "A sheet") {
                        SettingsGroup { row { SettingsRow("Later today", icon = PlaitIcon.Clock, subtitle = "6:00 PM", onClick = { sheet = false }) } }
                    }
                    SectionLabel("Bottom bar")
                    PlaitNavigationBar {
                        PlaitNavItem(tab == 0, { tab = 0 }, PlaitIcon.Wifi, "Network")
                        PlaitNavItem(tab == 1, { tab = 1 }, PlaitIcon.Clock, "History")
                    }
                    Spacer(Modifier.height(32.dp))
                }
            }
        }
    }
}
