package app.plait.scan

import app.plait.design.PlaitFilterChip
import app.plait.design.PlaitCard
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp

@Composable
fun ToolField(label: String, value: String, onChange: (String) -> Unit, modifier: Modifier = Modifier.fillMaxWidth(), keyboard: KeyboardType = KeyboardType.Uri, placeholder: String? = null) {
    OutlinedTextField(
        value = value, onValueChange = onChange, label = { Text(label) }, singleLine = true, modifier = modifier,
        keyboardOptions = KeyboardOptions(keyboardType = keyboard), placeholder = placeholder?.let { { Text(it) } },
    )
}

@Composable
fun <T> ChipRow(options: List<T>, selected: T, label: (T) -> String, onSelect: (T) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEach { o -> PlaitFilterChip(label(o), o == selected, { onSelect(o) }) }
    }
}

@Composable
fun Mono(text: String, modifier: Modifier = Modifier) {
    Text(text, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace, modifier = modifier)
}

@Composable
fun InfoCard(title: String? = null, content: @Composable () -> Unit) {
    PlaitCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (title != null) Text(title, style = MaterialTheme.typography.titleSmall)
            content()
        }
    }
}

/** A small line chart of recent values (round-trip times), drawn without any chart library. */
@Composable
fun Sparkline(values: List<Double>, modifier: Modifier = Modifier.fillMaxWidth().height(64.dp)) {
    val color = MaterialTheme.colorScheme.primary
    val dim = MaterialTheme.colorScheme.outlineVariant
    Canvas(modifier) {
        drawLine(dim, Offset(0f, size.height), Offset(size.width, size.height), 2f)
        if (values.size < 2) return@Canvas
        val max = values.max().coerceAtLeast(1.0)
        val path = Path()
        values.forEachIndexed { i, v ->
            val x = size.width * i / (values.size - 1)
            val y = size.height - (size.height * (v / max)).toFloat().coerceIn(0f, size.height)
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        drawPath(path, color, style = Stroke(width = 4f))
    }
}

fun ms(v: Double?) = if (v == null) "–" else if (v >= 100) "%.0f ms".format(v) else "%.1f ms".format(v)

/** Plait Broker's state, kept current while the page is shown (it can start, stop, or be allowed at any moment). */
@Composable
fun rememberBrokerState(): androidx.compose.runtime.State<Broker.State> {
    val state = androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(Broker.state()) }
    androidx.compose.runtime.DisposableEffect(Unit) {
        val up = rikka.shizuku.Shizuku.OnBinderReceivedListener { state.value = Broker.state() }
        val down = rikka.shizuku.Shizuku.OnBinderDeadListener { state.value = Broker.state() }
        val answer = rikka.shizuku.Shizuku.OnRequestPermissionResultListener { _, _ -> state.value = Broker.state() }
        rikka.shizuku.Shizuku.addBinderReceivedListenerSticky(up)
        rikka.shizuku.Shizuku.addBinderDeadListener(down)
        rikka.shizuku.Shizuku.addRequestPermissionResultListener(answer)
        onDispose {
            rikka.shizuku.Shizuku.removeBinderReceivedListener(up)
            rikka.shizuku.Shizuku.removeBinderDeadListener(down)
            rikka.shizuku.Shizuku.removeRequestPermissionResultListener(answer)
        }
    }
    return state
}
