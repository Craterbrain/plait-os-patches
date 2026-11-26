package app.plait.broker

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember

class ScreenshotActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            BrokerTheme { BrokerApp(remember { mutableIntStateOf(-1) }, sampleApps) }
        }
    }
}

private val sampleApps = Snapshot(
    entries = listOf(
        AppEntry(10001, "app.plait.scan", MODE_RESTRICTED, emptyList()),
        AppEntry(10002, "app.plait.shield", MODE_RESTRICTED, emptyList()),
        AppEntry(10003, "app.plait.backup", MODE_RESTRICTED, emptyList()),
        AppEntry(10004, "example.studio.toolbox", MODE_DENIED, emptyList()),
    ),
    audit = emptyList(),
)
