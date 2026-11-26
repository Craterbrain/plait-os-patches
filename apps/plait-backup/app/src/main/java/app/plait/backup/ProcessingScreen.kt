package app.plait.backup

import app.plait.design.PlaitIcon
import androidx.compose.ui.res.painterResource
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.unit.dp

@Composable
fun ProcessingScreen(vm: BackupViewModel, onDone: () -> Unit) {
    ProcessingContent(vm.jobFlow, vm.running, vm.phase, vm.phaseDetail, vm.jobItems, vm.log, onDone) {
        AppIcon(it.packageName, vm, 40.dp)
    }
}

@Composable
internal fun ProcessingContent(
    flow: Flow,
    running: Boolean,
    phase: String,
    phaseDetail: String,
    jobItems: List<JobItem>,
    log: List<String>,
    onDone: () -> Unit,
    icon: @Composable (JobItem) -> Unit,
) {
    val total = jobItems.size
    val finished = jobItems.count { it.state == ItemState.DONE || it.state == ItemState.FAILED }
    val failed = jobItems.count { it.state == ItemState.FAILED }
    var showLog by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            Column {
                BackTopBar(flow.title, onBack = { if (!running) onDone() })
                LinearProgressIndicator(progress = { if (total == 0) 0f else finished / total.toFloat() }, modifier = Modifier.fillMaxWidth(),
                    strokeCap = StrokeCap.Round)
            }
        },
        bottomBar = {
            Box(Modifier.fillMaxWidth().navigationBarsPadding().padding(16.dp)) {
                Button(onClick = onDone, enabled = !running, modifier = Modifier.fillMaxWidth().height(56.dp)) {
                    Text("Continue", style = MaterialTheme.typography.titleMedium)
                }
            }
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            // Big status ring with the phase underneath, like DataBackup's processing header
            Column(Modifier.fillMaxWidth().padding(vertical = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Box(Modifier.size(150.dp), Alignment.Center) {
                    CircularProgressIndicator(
                        progress = { if (total == 0) 0f else finished / total.toFloat() },
                        modifier = Modifier.size(150.dp), strokeWidth = 8.dp, strokeCap = StrokeCap.Round,
                        trackColor = MaterialTheme.colorScheme.secondaryContainer,
                    )
                    Box(Modifier.size(126.dp).clip(CircleShape).background(MaterialTheme.colorScheme.secondaryContainer), Alignment.Center) {
                        val icon = when {
                            running -> PlaitIcon.Hourglass
                            failed > 0 -> PlaitIcon.Error
                            else -> PlaitIcon.CheckCircle
                        }
                        Icon(painterResource(icon), null, Modifier.size(72.dp), tint = MaterialTheme.colorScheme.onSecondaryContainer)
                    }
                }
                Spacer(Modifier.height(20.dp))
                Text(phase, style = MaterialTheme.typography.headlineSmall)
                Text(phaseDetail, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (log.isNotEmpty()) TextButton(onClick = { showLog = !showLog }) { Text(if (showLog) "Hide log" else "Show log") }
            }
            Surface(shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp), color = MaterialTheme.colorScheme.surfaceContainer,
                modifier = Modifier.fillMaxSize()) {
                LazyColumn(Modifier.padding(horizontal = 24.dp, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (showLog) items(log) { Text(it, style = MaterialTheme.typography.bodySmall) }
                    else items(jobItems, key = { it.packageName }) { item ->
                        Row(Modifier.fillMaxWidth().height(56.dp), verticalAlignment = Alignment.CenterVertically) {
                            icon(item)
                            Spacer(Modifier.width(20.dp))
                            Text(item.label, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                            when (item.state) {
                                ItemState.WAITING -> Icon(painterResource(PlaitIcon.Clock), "Waiting", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                                ItemState.RUNNING -> CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 3.dp)
                                ItemState.DONE -> Icon(painterResource(PlaitIcon.CheckCircle), "Done", tint = MaterialTheme.colorScheme.primary)
                                ItemState.FAILED -> Icon(painterResource(PlaitIcon.Error), "Failed", tint = MaterialTheme.colorScheme.error)
                            }
                        }
                    }
                }
            }
        }
    }
}
