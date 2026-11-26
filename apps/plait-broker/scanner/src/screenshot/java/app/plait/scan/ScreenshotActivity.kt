package app.plait.scan

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import app.plait.design.PlaitTheme

class ScreenshotActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { PlaitTheme { AppRoot(sampleNetwork) } }
    }
}

private val sampleNetwork = ScanFixture(
    lan = Lan("192.0.2.42", "192.0.2.1", null, 24, listOf("192.0.2.1")),
    hosts = listOf(
        sampleHost(1, "Home router", Kind.ROUTER, 1, gateway = true),
        sampleHost(12, "Studio laptop", Kind.LAPTOP, 4),
        sampleHost(18, "Living room TV", Kind.TV, 9),
        sampleHost(23, "Office printer", Kind.PRINTER, 7),
        sampleHost(31, "Media server", Kind.NAS, 2),
        sampleHost(42, "This phone", Kind.PHONE, null, self = true),
    ),
)

private fun sampleHost(
    address: Int,
    name: String,
    kind: Kind,
    latency: Long?,
    gateway: Boolean = false,
    self: Boolean = false,
) = Host(
    ip = "192.0.2.$address",
    name = name,
    rttMs = latency,
    gateway = gateway,
    self = self,
    identity = Identity(kind, null, 3, emptyList(), name),
)
