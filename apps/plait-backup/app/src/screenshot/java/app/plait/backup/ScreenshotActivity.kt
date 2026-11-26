package app.plait.backup

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.plait.design.PlaitTheme

class ScreenshotActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val jobs = listOf("Messages", "Contacts", "Phone").mapIndexed { index, label ->
            JobItem("example.backup.item$index", label).apply { state = ItemState.DONE }
        }
        setContent {
            PlaitTheme {
                var complete by rememberSaveable { mutableStateOf(intent.getBooleanExtra("completed", false)) }
                BackHandler(complete) { complete = false }
                if (!complete) {
                    DashboardContent(
                        supportsPersonal = true,
                        onBackup = { complete = true },
                        onPhoneData = { complete = true },
                        onRestore = {},
                        onDelete = {},
                        onHistory = {},
                        onSettings = {},
                        overview = {
                            DashboardOverview(
                                at = 1795525200000L,
                                used = 38L shl 30,
                                total = 128L shl 30,
                                backups = 2L shl 30,
                                until = null,
                                onStart = {},
                                onEnd = {},
                            )
                        },
                    )
                } else ProcessingContent(
                    flow = Flow.BACKUP,
                    running = false,
                    phase = "Backup complete",
                    phaseDetail = "3 of 3 succeeded",
                    jobItems = jobs,
                    log = listOf("Messages backed up", "Contacts backed up", "Phone backed up"),
                    onDone = { complete = false },
                ) { item ->
                    Box(
                        Modifier.size(40.dp).background(
                            MaterialTheme.colorScheme.secondaryContainer,
                            CircleShape,
                        ),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(item.label.take(1), color = MaterialTheme.colorScheme.onSecondaryContainer)
                    }
                }
            }
        }
    }
}
