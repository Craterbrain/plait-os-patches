package app.plait.design

import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.FloatingActionButtonDefaults
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/*
 * The shared patterns of a Plait app screen, so every app has the same search, lists, people, floating button, bottom
 * bar and sheets. Layout stays the app's; these are the parts it is made of.
 */

/** The search field under a header: 48 dp on the raised surface, 14 dp corners, a clear button once there is text. */
@Composable
fun PlaitSearchField(query: String, onQueryChange: (String) -> Unit, placeholder: String, modifier: Modifier = Modifier) {
    val c = MaterialTheme.colorScheme
    BasicTextField(
        value = query,
        onValueChange = onQueryChange,
        modifier = modifier.fillMaxWidth(),
        singleLine = true,
        textStyle = MaterialTheme.typography.bodyLarge.copy(color = c.onSurface),
        cursorBrush = SolidColor(c.primary),
        decorationBox = { field ->
            Row(
                Modifier.fillMaxWidth().heightIn(min = 48.dp).background(c.surfaceContainerHigh, MaterialTheme.shapes.large)
                    .padding(start = 14.dp, end = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                PlaitIcon(PlaitIcon.Search, null, Modifier.size(20.dp), c.onSurfaceVariant)
                Box(Modifier.weight(1f).padding(vertical = 12.dp)) {
                    if (query.isEmpty()) {
                        Text(placeholder, style = MaterialTheme.typography.bodyLarge, color = c.onSurfaceVariant, maxLines = 1,
                            overflow = TextOverflow.Ellipsis)
                    }
                    field()
                }
                if (query.isNotEmpty()) {
                    IconButton(onClick = { onQueryChange("") }) { PlaitIcon(PlaitIcon.Close, "Clear", Modifier.size(20.dp), c.onSurfaceVariant) }
                }
            }
        },
    )
}

/**
 * A row of any list: something on the left ([lead]: a [TonalTile], a [PlaitAvatar] or an app icon, 40 dp), a title, an
 * optional second line, an optional [meta] (a time, a size) and whatever belongs on the right. Put rows in a
 * [SettingsGroup] so they share one card with hairlines between them; the hairline lines up with the title.
 */
@Composable
fun ListRow(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    lead: (@Composable () -> Unit)? = null,
    meta: String? = null,
    emphasized: Boolean = false,
    onClick: (() -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    val c = MaterialTheme.colorScheme
    Row(
        modifier.fillMaxWidth().heightIn(min = 60.dp)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        lead?.invoke()
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, color = c.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis,
                fontWeight = if (emphasized) androidx.compose.ui.text.font.FontWeight.SemiBold else null)
            if (subtitle != null) {
                Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = if (emphasized) c.onSurface else c.onSurfaceVariant,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        if (meta != null) {
            Text(meta, style = MaterialTheme.typography.labelMedium, color = if (emphasized) c.primary else c.onSurfaceVariant)
        }
        trailing?.invoke()
    }
}

/** Which of the three avatar tones [key] gets: the same person always the same tone. */
fun avatarTone(key: String): Int = Math.floorMod(key.trim().lowercase().hashCode(), 3)

/**
 * A person: their initial on one of three tones (primary container, secondary container, a tertiary tint), chosen by
 * [key] so the same person always looks the same in Phone, Contacts and Messages. No name: a person icon.
 */
@Composable
fun PlaitAvatar(name: String, modifier: Modifier = Modifier, key: String = name, size: Dp = 40.dp) {
    val c = MaterialTheme.colorScheme
    val (bg, fg) = when (avatarTone(key)) {
        0 -> c.primaryContainer to c.onPrimaryContainer
        1 -> c.secondaryContainer to c.onSecondaryContainer
        else -> c.tertiary.copy(alpha = 0.18f) to c.tertiary
    }
    val initial = name.firstOrNull { it.isLetterOrDigit() }?.uppercaseChar()
    Box(modifier.size(size).background(bg, CircleShape), contentAlignment = Alignment.Center) {
        if (initial != null) {
            Text(initial.toString(), color = fg, style = if (size >= 56.dp) MaterialTheme.typography.titleLarge else MaterialTheme.typography.titleMedium)
        } else {
            PlaitIcon(PlaitIcon.Person, null, Modifier.size(size * 0.55f), fg)
        }
    }
}

/** The floating button: 56 dp, 16 dp corners, the primary container. With [text] it is extended (icon plus label). */
@Composable
fun PlaitFab(@DrawableRes icon: Int, onClick: () -> Unit, modifier: Modifier = Modifier, text: String? = null, contentDescription: String? = text) {
    val c = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(16.dp)
    val elevation = FloatingActionButtonDefaults.loweredElevation()
    if (text != null) {
        ExtendedFloatingActionButton(
            onClick = onClick, modifier = modifier, shape = shape, containerColor = c.primaryContainer, contentColor = c.onPrimaryContainer,
            elevation = elevation, icon = { PlaitIcon(icon, null) }, text = { Text(text, style = MaterialTheme.typography.labelLarge) },
        )
    } else {
        FloatingActionButton(onClick, modifier, shape, c.primaryContainer, c.onPrimaryContainer, elevation) { PlaitIcon(icon, contentDescription) }
    }
}

/** The bottom bar, for an app's top-level sections only (never Settings: that is the gear in the header). */
@Composable
fun PlaitNavigationBar(modifier: Modifier = Modifier, content: @Composable RowScope.() -> Unit) =
    NavigationBar(modifier, containerColor = MaterialTheme.colorScheme.surfaceContainerLow, tonalElevation = 0.dp, content = content)

/** One section of [PlaitNavigationBar]: a tint of the accent behind the selected one. */
@Composable
fun RowScope.PlaitNavItem(selected: Boolean, onClick: () -> Unit, @DrawableRes icon: Int, label: String) {
    val c = MaterialTheme.colorScheme
    NavigationBarItem(
        selected = selected, onClick = onClick,
        icon = { PlaitIcon(icon, null) },
        label = { Text(label, style = MaterialTheme.typography.labelMedium) },
        colors = NavigationBarItemDefaults.colors(
            selectedIconColor = c.primary, selectedTextColor = c.onSurface, indicatorColor = c.primary.copy(alpha = 0.16f),
            unselectedIconColor = c.onSurfaceVariant, unselectedTextColor = c.onSurfaceVariant,
        ),
    )
}

/**
 * A sheet for pickers and quick choices: 28 dp top corners and a handle, on the raised surface, with an optional
 * [title]. Content gets 16 dp sides and 12 dp between items.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlaitSheet(
    onDismiss: () -> Unit,
    title: String? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    // Opens fully: these sheets are short, and a half-open state would only hide their last row
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        scrimColor = Color.Black.copy(alpha = 0.5f),
    ) {
        Column(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (title != null) Text(title, Modifier.padding(horizontal = 4.dp), style = MaterialTheme.typography.titleLarge)
            content()
        }
    }
}
