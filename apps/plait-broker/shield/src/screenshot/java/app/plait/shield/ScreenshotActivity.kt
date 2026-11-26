package app.plait.shield

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge

class ScreenshotActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            ShieldTheme { ShieldApp(0, ShieldFixture(PrivateDns("hostname", "dns.quad9.net"), 42168)) }
        }
    }
}
