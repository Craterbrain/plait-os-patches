package app.plait.design

import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/*
 * The building blocks of the unified look. Rules they keep, so a screen made of them follows the system without thought:
 *   - depth is tone (surface < surfaceContainer < ...High < ...Highest), not outlines; a hairline only separates rows in a group
 *   - the accent (primary) is solid on the one action and on what is switched on; a selection is a tint
 *   - every system icon sits on a tonal tile (primaryContainer / onPrimaryContainer); colour beyond that means a state
 *   - touch targets are at least 48 dp tall, text at least 12 sp
 */

/** An icon from [PlaitIcon] in the current content colour. */
@Composable
fun PlaitIcon(
    @DrawableRes icon: Int,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    tint: Color = LocalContentColor.current,
) = Icon(painterResource(icon), contentDescription, modifier, tint)

/** The tonal tile: Android's themed-icon look, behind every system icon. */
@Composable
fun TonalTile(
    @DrawableRes icon: Int,
    modifier: Modifier = Modifier,
    size: Dp = 40.dp,
    shape: Shape = RoundedCornerShape(12.dp),
) {
    Box(
        modifier.size(size).background(MaterialTheme.colorScheme.primaryContainer, shape),
        contentAlignment = Alignment.Center,
    ) {
        Icon(painterResource(icon), null, Modifier.size(size * 0.55f), MaterialTheme.colorScheme.onPrimaryContainer)
    }
}

/** A screen's title (and a line under it), with room for actions on the right. */
@Composable
fun PlaitTopBar(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    actions: @Composable RowScope.() -> Unit = {},
) {
    Row(modifier.fillMaxWidth().padding(start = 20.dp, end = 12.dp, top = 12.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.onSurface)
            if (subtitle != null) {
                Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        actions()
    }
}

/** A small heading over a group. */
@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        modifier.padding(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 6.dp),
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/** Collects the rows of a [SettingsGroup] so a hairline can go between them. */
class SettingsGroupScope {
    internal val rows = mutableListOf<@Composable () -> Unit>()
    fun row(content: @Composable () -> Unit) { rows += content }
}

/** One rounded card of rows, separated by hairlines inset past the icon. */
@Composable
fun SettingsGroup(modifier: Modifier = Modifier, content: SettingsGroupScope.() -> Unit) {
    val rows = SettingsGroupScope().apply(content).rows
    Surface(
        modifier.fillMaxWidth().padding(horizontal = 12.dp),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        Column {
            rows.forEachIndexed { i, row ->
                if (i > 0) HorizontalDivider(Modifier.padding(start = 68.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                row()
            }
        }
    }
}

/** A row: tonal tile, title, an optional second line and value, and whatever belongs on the right. */
@Composable
fun SettingsRow(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    @DrawableRes icon: Int? = null,
    value: String? = null,
    onClick: (() -> Unit)? = null,
    chevron: Boolean = onClick != null,
    trailing: @Composable (() -> Unit)? = null,
) {
    Row(
        modifier.fillMaxWidth().heightIn(min = 56.dp)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        if (icon != null) TonalTile(icon)
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
            if (subtitle != null) {
                Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, overflow = TextOverflow.Ellipsis)
            }
        }
        if (value != null) Text(value, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        trailing?.invoke()
        if (chevron) PlaitIcon(PlaitIcon.ChevronRight, null, Modifier.size(20.dp), MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** A row with a switch; the whole row toggles it. */
@Composable
fun SwitchRow(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    @DrawableRes icon: Int? = null,
) = SettingsRow(
    title, modifier.clickable { onCheckedChange(!checked) }, subtitle, icon, chevron = false,
    trailing = { PlaitSwitch(checked, onCheckedChange) },
)

/** The switch: primary when on, an outlined thumb when off (so the off state reads without colour). */
@Composable
fun PlaitSwitch(checked: Boolean, onCheckedChange: ((Boolean) -> Unit)?, modifier: Modifier = Modifier) {
    val c = MaterialTheme.colorScheme
    Switch(
        checked, onCheckedChange, modifier,
        colors = SwitchDefaults.colors(
            checkedThumbColor = c.onPrimary, checkedTrackColor = c.primary, checkedBorderColor = c.primary,
            uncheckedThumbColor = c.outline, uncheckedTrackColor = c.surfaceContainerHighest, uncheckedBorderColor = c.outline,
        ),
    )
}

/** A card: one tonal surface, no outline and no shadow. [onClick] makes the whole card tappable. */
@Composable
fun PlaitCard(
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.surfaceContainer,
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val body: @Composable () -> Unit = { Column(content = content) }
    if (onClick != null) Surface(onClick, modifier, shape = MaterialTheme.shapes.large, color = color, content = body)
    else Surface(modifier, shape = MaterialTheme.shapes.large, color = color, content = body)
}

/** A card that says something about a state (a problem, a warning, good news): a tint of the state colour over the surface, text stays readable. */
@Composable
fun NoticeCard(tone: Tone, modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    val c = MaterialTheme.colorScheme
    val x = PlaitTheme.extras
    val tint = when (tone) {
        Tone.Neutral -> c.surfaceContainerHighest
        Tone.Success -> x.successContainer
        Tone.Warning -> x.warningContainer
        Tone.Danger -> c.error.copy(alpha = 0.16f)
        Tone.Accent -> c.primary.copy(alpha = 0.14f)
    }
    Surface(modifier, shape = MaterialTheme.shapes.large, color = c.surfaceContainer) {
        Column(Modifier.background(tint).fillMaxWidth(), content = content)
    }
}

enum class Tone { Neutral, Success, Warning, Danger, Accent }

/** A small status label: Permissive, Restricted, Denied, Protected. Tinted, never a solid colour. */
@Composable
fun StateChip(text: String, tone: Tone, modifier: Modifier = Modifier) {
    val c = MaterialTheme.colorScheme
    val x = PlaitTheme.extras
    val (bg, fg) = when (tone) {
        Tone.Neutral -> c.surfaceContainerHighest to c.onSurfaceVariant
        Tone.Success -> x.successContainer to x.success
        Tone.Warning -> x.warningContainer to x.warning
        Tone.Danger -> c.error.copy(alpha = 0.15f) to c.error
        Tone.Accent -> c.primary.copy(alpha = 0.16f) to c.primary
    }
    Text(
        text,
        modifier.background(bg, CircleShape).padding(horizontal = 10.dp, vertical = 4.dp),
        style = MaterialTheme.typography.labelMedium, color = fg,
    )
}

/** A filter chip: selected is a tint of the accent with an accent label, not a second solid colour. */
@Composable
fun PlaitFilterChip(label: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val c = MaterialTheme.colorScheme
    Text(
        label,
        modifier.heightIn(min = 36.dp)
            .background(if (selected) c.primary.copy(alpha = 0.16f) else Color.Transparent, CircleShape)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        style = MaterialTheme.typography.labelLarge,
        color = if (selected) c.primary else c.onSurfaceVariant,
    )
}

/** A small tappable pill that does something (not a filter): tonal, with a 48 dp touch area around the smaller pill. */
@Composable
fun PlaitActionChip(label: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(modifier.minimumInteractiveComponentSize().clickable(onClick = onClick), contentAlignment = Alignment.Center) {
        Text(
            label,
            Modifier.background(MaterialTheme.colorScheme.surfaceContainerHighest, CircleShape).padding(horizontal = 14.dp, vertical = 7.dp),
            style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

enum class ButtonKind { Primary, Tonal, Text }

/** Filled for the one action on a screen, tonal for the rest, text for the quietest. 48 dp tall. */
@Composable
fun PlaitButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    kind: ButtonKind = ButtonKind.Tonal,
    enabled: Boolean = true,
) {
    val m = modifier.heightIn(min = 48.dp)
    val pad = PaddingValues(horizontal = 20.dp)
    when (kind) {
        ButtonKind.Primary -> Button(onClick, m, enabled, MaterialTheme.shapes.medium, contentPadding = pad) { Text(text, style = MaterialTheme.typography.labelLarge) }
        ButtonKind.Tonal -> FilledTonalButton(onClick, m, enabled, MaterialTheme.shapes.medium, contentPadding = pad,
            colors = ButtonDefaults.filledTonalButtonColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHighest, contentColor = MaterialTheme.colorScheme.onSurface)) {
            Text(text, style = MaterialTheme.typography.labelLarge)
        }
        ButtonKind.Text -> TextButton(onClick, m, enabled, MaterialTheme.shapes.medium, contentPadding = pad) { Text(text, style = MaterialTheme.typography.labelLarge) }
    }
}

/** [PlaitButton] with a content slot, for a button that holds more than its label. Filled: the one action on a screen. */
@Composable
fun PlaitFilledButton(onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, content: @Composable RowScope.() -> Unit) =
    Button(onClick, modifier.heightIn(min = 48.dp), enabled, MaterialTheme.shapes.medium, contentPadding = PaddingValues(horizontal = 20.dp), content = content)

/** Tonal: the other actions. */
@Composable
fun PlaitTonalButton(onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, content: @Composable RowScope.() -> Unit) =
    FilledTonalButton(
        onClick, modifier.heightIn(min = 48.dp), enabled, MaterialTheme.shapes.medium, contentPadding = PaddingValues(horizontal = 20.dp),
        colors = ButtonDefaults.filledTonalButtonColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHighest, contentColor = MaterialTheme.colorScheme.onSurface),
        content = content,
    )
