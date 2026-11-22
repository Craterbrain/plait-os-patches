package app.plait.broker

import androidx.compose.runtime.Composable
import app.plait.design.PlaitTheme

/** The shared PlaitOS look (plait-design): the system's dynamic colours, the common type scale and shapes. */
@Composable
fun BrokerTheme(content: @Composable () -> Unit) = PlaitTheme(content = content)
