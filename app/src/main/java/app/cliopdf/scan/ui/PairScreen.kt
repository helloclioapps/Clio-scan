@file:OptIn(ExperimentalMaterial3Api::class)

package app.cliopdf.scan.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.TextButton
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.cliopdf.scan.Actions
import app.cliopdf.scan.AppViewModel
import app.cliopdf.scan.PairState

@Composable
fun PairScreen(vm: AppViewModel, actions: Actions, snackbar: SnackbarHostState, modifier: Modifier) {
    val state by vm.pairState.collectAsState()
    val mac by vm.mac.collectAsState()
    val confirmed by vm.macConfirmed.collectAsState()
    DisposableEffect(Unit) { onDispose { vm.resetPairState() } }

    Scaffold(
        modifier,
        containerColor = Clio.Bg,
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text("Pair with your Mac", style = MaterialTheme.typography.titleMedium) },
                navigationIcon = { IconButton(onClick = { vm.back() }) { Icon(Lucide.ChevronLeft, "Back") } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Clio.Bg),
            )
        },
    ) { pad ->
        Column(
            Modifier.fillMaxSize().padding(pad).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
        ) {
            Box(Modifier.fillMaxWidth().padding(vertical = 12.dp), contentAlignment = Alignment.Center) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    IconTile(Lucide.Smartphone, Clio.Blue, 56.dp, 26.dp)
                    Icon(Lucide.Wifi, null, tint = if (state is PairState.Done) Clio.Green else Clio.Faint, modifier = Modifier.size(22.dp))
                    IconTile(Lucide.Laptop, Clio.Navy, 56.dp, 26.dp)
                }
            }

            when (val st = state) {
                is PairState.Working -> StatusCard(Clio.BlueSoft, Clio.Navy) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(12.dp))
                        Column {
                            Text("Pairing with ${st.mac}", style = MaterialTheme.typography.titleSmall)
                            Text(st.step, style = MaterialTheme.typography.bodySmall, color = Clio.Text2)
                        }
                    }
                }
                is PairState.Done -> {
                    StatusCard(Clio.GreenSoft, Clio.GreenText) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Lucide.CircleCheck, null, tint = Clio.Green, modifier = Modifier.size(22.dp))
                            Spacer(Modifier.width(12.dp))
                            Column {
                                Text("Paired with ${st.mac}", style = MaterialTheme.typography.titleSmall, color = Clio.GreenText)
                                Text("Tap “To Mac” on any document to send it. Files are encrypted and stay on your Wi-Fi.",
                                    style = MaterialTheme.typography.bodySmall, color = Clio.Text2)
                            }
                        }
                    }
                    Spacer(Modifier.height(14.dp))
                    Button(onClick = { vm.back() }, modifier = Modifier.fillMaxWidth().height(50.dp), shape = RoundedCornerShape(14.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Clio.Green)) { Text("Done") }
                }
                is PairState.Failed -> {
                    StatusCard(Clio.RedSoft, Clio.Red) {
                        Row(verticalAlignment = Alignment.Top) {
                            Icon(Lucide.CircleAlert, null, tint = Clio.Red, modifier = Modifier.size(22.dp))
                            Spacer(Modifier.width(12.dp))
                            Column {
                                Text(if (st.mac != null) "Couldn't connect to ${st.mac}" else "Pairing didn't work",
                                    style = MaterialTheme.typography.titleSmall, color = Clio.Red)
                                Spacer(Modifier.height(4.dp))
                                SelectionContainer {
                                    Text(st.message, style = MaterialTheme.typography.bodySmall, color = Clio.Text2)
                                }
                            }
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        if (mac != null) {
                            Button(onClick = { vm.confirmPairing() }, modifier = Modifier.weight(1f).height(48.dp), shape = RoundedCornerShape(14.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = Clio.Blue)) {
                                Icon(Lucide.RefreshCw, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text("Try again")
                            }
                        }
                        OutlinedButton(onClick = actions.scanQr, modifier = Modifier.weight(1f).height(48.dp), shape = RoundedCornerShape(14.dp)) {
                            Icon(Lucide.QrCode, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text("Scan again")
                        }
                    }
                }
                PairState.Idle -> {
                    if (mac != null) {
                        StatusCard(if (confirmed) Clio.GreenSoft else Color(0xFFFFF4E5), Clio.Text2) {
                            Text(
                                if (confirmed) "Currently paired with ${mac?.name}. Scan a new code to pair with another Mac."
                                else "${mac?.name} hasn't answered yet. Tap Try again once CLIO PDF is open on the Mac.",
                                style = MaterialTheme.typography.bodySmall,
                            )
                            if (!confirmed) TextButton(onClick = { vm.confirmPairing() }) { Text("Try again") }
                        }
                        Spacer(Modifier.height(12.dp))
                    }
                    Text(
                        "Send documents straight to CLIO PDF on your Mac. They travel over your own Wi-Fi, encrypted, and never pass through the internet.",
                        style = MaterialTheme.typography.bodyMedium, color = Clio.Text2,
                    )
                }
            }

            if (state !is PairState.Done) {
                Spacer(Modifier.height(16.dp))
                Panel {
                    Step(1, "Open CLIO PDF on your Mac", "Version 2.3.1 or later. Choose File › Pair Android Phone… — a QR code appears.")
                    Spacer(Modifier.height(14.dp))
                    Step(2, "Use the same Wi-Fi", "Phone and Mac on the same network (not a guest network). A phone hotspot works too.")
                    Spacer(Modifier.height(14.dp))
                    Step(3, "Scan the QR code", "Tap the button below and point the camera at the code on the Mac.")
                }
                if (state !is PairState.Failed) {
                    Spacer(Modifier.height(20.dp))
                    Button(
                        onClick = actions.scanQr,
                        enabled = state !is PairState.Working,
                        modifier = Modifier.fillMaxWidth().height(52.dp),
                        shape = RoundedCornerShape(14.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Clio.Blue),
                    ) {
                        Icon(Lucide.QrCode, null, Modifier.size(20.dp))
                        Spacer(Modifier.width(10.dp))
                        Text("Scan QR code", style = MaterialTheme.typography.titleSmall, color = Color.White)
                    }
                }
                Spacer(Modifier.height(16.dp))
                Row(verticalAlignment = Alignment.Top) {
                    Icon(Lucide.Info, null, tint = Clio.Faint, modifier = Modifier.size(16.dp).padding(top = 2.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "If the Mac asks whether CLIO PDF may accept incoming connections or find devices on your local network, choose Allow." +
                            (vm.phoneIp()?.let { "\nThis phone's address: $it" } ?: "\nThis phone doesn't seem to be on Wi-Fi."),
                        style = MaterialTheme.typography.bodySmall, color = Clio.Muted,
                    )
                }
            }
        }
    }
}

@Composable
private fun StatusCard(bg: Color, fg: Color, content: @Composable () -> Unit) {
    Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(bg).padding(14.dp)) {
        androidx.compose.runtime.CompositionLocalProvider(androidx.compose.material3.LocalContentColor provides fg) { Column { content() } }
    }
}

@Composable
private fun Step(n: Int, title: String, sub: String) {
    Row(verticalAlignment = Alignment.Top) {
        Box(Modifier.size(26.dp).clip(CircleShape).background(Clio.BlueSoft), contentAlignment = Alignment.Center) {
            Text("$n", color = Clio.Blue, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelLarge)
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(sub, style = MaterialTheme.typography.bodySmall, color = Clio.Muted)
        }
    }
}
