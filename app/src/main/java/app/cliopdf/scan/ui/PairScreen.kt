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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.cliopdf.scan.Actions
import app.cliopdf.scan.AppViewModel

@Composable
fun PairScreen(vm: AppViewModel, actions: Actions, snackbar: SnackbarHostState, modifier: Modifier) {
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
                    Icon(Lucide.Wifi, null, tint = Clio.Faint, modifier = Modifier.size(22.dp))
                    IconTile(Lucide.Laptop, Clio.Navy, 56.dp, 26.dp)
                }
            }
            Text(
                "Send documents straight to CLIO PDF on your Mac. They travel over your own Wi-Fi, encrypted, and never pass through the internet.",
                style = MaterialTheme.typography.bodyMedium, color = Clio.Text2,
            )
            Spacer(Modifier.height(16.dp))
            Panel {
                Step(1, "Open CLIO PDF on your Mac", "Version 2.3 or later. Go to File › Pair Android Phone…, or Settings › Integrations.")
                Spacer(Modifier.height(14.dp))
                Step(2, "Use the same Wi-Fi", "Connect the phone and the Mac to the same network. A phone hotspot works too.")
                Spacer(Modifier.height(14.dp))
                Step(3, "Scan the QR code", "Tap the button below and point the camera at the code on the Mac.")
            }
            Spacer(Modifier.height(20.dp))
            Button(
                onClick = actions.scanQr,
                modifier = Modifier.fillMaxWidth().height(52.dp),
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Clio.Blue),
            ) {
                Icon(Lucide.QrCode, null, Modifier.size(20.dp))
                Spacer(Modifier.width(10.dp))
                Text("Scan QR code", style = MaterialTheme.typography.titleSmall, color = Color.White)
            }
            Spacer(Modifier.height(16.dp))
            Row(verticalAlignment = Alignment.Top) {
                Icon(Lucide.Info, null, tint = Clio.Faint, modifier = Modifier.size(16.dp).padding(top = 2.dp))
                Spacer(Modifier.width(8.dp))
                Text(
                    "If the Mac asks whether CLIO PDF may accept incoming connections or find devices on your local network, choose Allow.",
                    style = MaterialTheme.typography.bodySmall, color = Clio.Muted,
                )
            }
        }
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
