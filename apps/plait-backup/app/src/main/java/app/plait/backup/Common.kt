package app.plait.backup

import app.plait.design.PlaitIcon
import androidx.compose.ui.res.painterResource
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap

/**
 * A passphrase is typed exactly: a field that only draws dots is still ordinary text to the keyboard, which then autocorrects,
 * capitalises and adds spaces (and learns the word). Password type, no autocorrect, no capitals.
 */
val PassphraseKeyboard = androidx.compose.foundation.text.KeyboardOptions(
    capitalization = androidx.compose.ui.text.input.KeyboardCapitalization.None,
    autoCorrectEnabled = false,
    keyboardType = androidx.compose.ui.text.input.KeyboardType.Password,
    imeAction = androidx.compose.ui.text.input.ImeAction.Done,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BackTopBar(title: String, onBack: () -> Unit, actions: @Composable () -> Unit = {}) {
    TopAppBar(
        title = { Text(title) },
        navigationIcon = { IconButton(onClick = onBack) { Icon(painterResource(PlaitIcon.Back), "Back") } },
        actions = { actions() },
    )
}

/** The thin step bar under DataBackup's top bars: step [step] of [steps] filled. */
@Composable
fun StepBar(step: Int, steps: Int = 3) {
    LinearProgressIndicator(
        progress = { step / steps.toFloat() },
        modifier = Modifier.fillMaxWidth().padding(horizontal = 0.dp),
        strokeCap = StrokeCap.Round,
        gapSize = 4.dp,
        trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
        drawStopIndicator = {},
    )
}

@Composable
fun AppIcon(pkg: String, vm: BackupViewModel, size: Dp = 48.dp) {
    Personal.of(pkg)?.let {
        PersonalIcon(it, size)
        return
    }
    val context = LocalContext.current
    val info = vm.appInfo(pkg)
    val installed: ImageBitmap? = remember(pkg, info) {
        info?.let { runCatching { it.info.loadIcon(context.packageManager).toBitmap(128, 128).asImageBitmap() }.getOrNull() }
    }
    // Not installed (a fresh phone): the icon saved with the archive
    val saved = vm.archive(pkg)?.iconPng
    val archived: ImageBitmap? = remember(pkg, saved) { if (installed == null) AppMeta.decode(saved) else null }
    val bitmap = installed ?: archived
    if (bitmap != null) Image(bitmap, null, Modifier.size(size).clip(CircleShape))
    else Box(Modifier.size(size).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceContainerHighest), Alignment.Center) {
        Text(pkg.substringAfterLast('.').take(1).uppercase(), style = MaterialTheme.typography.titleMedium)
    }
}

fun formatSize(bytes: Long) = when {
    bytes >= 1L shl 30 -> "%.2f GB".format(bytes / (1L shl 30).toDouble())
    bytes >= 1L shl 20 -> "%.2f MB".format(bytes / (1L shl 20).toDouble())
    else -> "%.2f KB".format(bytes / 1024.0)
}
