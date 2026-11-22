package app.plait.scan

import app.plait.design.PlaitCard
import app.plait.design.PlaitIcon
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

enum class Tool(val title: String, val blurb: String, val icon: Int) {
    SPEED("Speed test", "Opens fast.com", PlaitIcon.Speed),
    PING("Ping", "Round-trip time and loss", PlaitIcon.Ping),
    TRACE("Traceroute", "Every hop to a host", PlaitIcon.Route),
    WIFI("Wi-Fi scanner", "Nearby networks and channels", PlaitIcon.Wifi),
    WOL("Wake on LAN", "Turn a PC or NAS on", PlaitIcon.Power),
    PMTU("Path MTU", "Largest packet that fits", PlaitIcon.Ruler),
    SUBNET("Subnet calculator", "IPv4 and IPv6 CIDR", PlaitIcon.Calculate),
    DNS("DNS lookup", "Any record, any server", PlaitIcon.Server),
    TLS("Certificate inspector", "TLS chain and findings", PlaitIcon.Lock),
    WHOIS("WHOIS and location", "Who owns it, where it is", PlaitIcon.Browser),
    IPERF("iPerf", "Throughput to an iperf3 server", PlaitIcon.Bolt),
    CONNECTIONS("Connections", "Which app talks to what", PlaitIcon.Hub),
}

/** An address handed from one tool to the next (a connection opened in WHOIS, the certificate inspector, ping...). */
object ToolArgs {
    @Volatile var host: String? = null
    fun take(): String? = host.also { host = null }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ToolsPage() {
    val ctx = LocalContext.current
    var tool by remember { mutableStateOf<Tool?>(null) }
    BackHandler(enabled = tool != null) { tool = null }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(tool?.title ?: "Network tools") },
                navigationIcon = { if (tool != null) IconButton(onClick = { tool = null }) { PlaitIcon(PlaitIcon.Back, "Back") } },
            )
        },
        contentWindowInsets = androidx.compose.foundation.layout.WindowInsets(0),
    ) { pad ->
        when (tool) {
            null -> androidx.compose.foundation.layout.Column(Modifier.padding(pad)) {
                androidx.compose.foundation.layout.Box(Modifier.padding(start = 12.dp, end = 12.dp, top = 8.dp)) { BrokerReviewBanner() }
                LazyVerticalGrid(
                GridCells.Fixed(2), Modifier.fillMaxWidth(), contentPadding = PaddingValues(12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(Tool.entries.toList()) { t ->
                    PlaitCard(Modifier.fillMaxWidth().height(136.dp).clickable {
                        if (t == Tool.SPEED) {
                            try { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://fast.com"))) }
                            catch (e: Exception) { Toast.makeText(ctx, "No browser to open fast.com", Toast.LENGTH_SHORT).show() }
                        } else tool = t
                    }) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            PlaitIcon(t.icon, t.title, Modifier.size(28.dp), tint = MaterialTheme.colorScheme.primary)
                            Text(t.title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(t.blurb, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
            }
            Tool.PING -> PingTool(pad)
            Tool.TRACE -> TraceTool(pad)
            Tool.WIFI -> WifiTool(pad)
            Tool.WOL -> WolTool(pad)
            Tool.PMTU -> MtuTool(pad)
            Tool.SUBNET -> SubnetTool(pad)
            Tool.DNS -> DnsTool(pad)
            Tool.TLS -> TlsTool(pad)
            Tool.WHOIS -> WhoisTool(pad)
            Tool.IPERF -> IperfTool(pad)
            Tool.CONNECTIONS -> ConnectionsTool(pad) { t, h -> ToolArgs.host = h; tool = t }
            else -> {}
        }
    }
}
