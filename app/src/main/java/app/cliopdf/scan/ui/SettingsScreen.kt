@file:OptIn(ExperimentalMaterial3Api::class)

package app.cliopdf.scan.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.cliopdf.scan.Actions
import app.cliopdf.scan.AppViewModel
import app.cliopdf.scan.R
import app.cliopdf.scan.Screen

@Composable
fun SettingsScreen(vm: AppViewModel, actions: Actions, snackbar: SnackbarHostState, modifier: Modifier) {
    val s by vm.prefs.state.collectAsState()
    val mac by vm.mac.collectAsState()
    val docs by vm.docs.collectAsState()
    var confirmUnpair by remember { mutableStateOf(false) }
    var confirmWipe by remember { mutableStateOf(false) }
    var licenses by remember { mutableStateOf(false) }
    val version = remember {
        val ctx = vm.getApplication<android.app.Application>()
        runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName }.getOrNull() ?: ""
    }

    Scaffold(
        modifier,
        containerColor = Clio.Bg,
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text("Settings", style = MaterialTheme.typography.titleMedium) },
                navigationIcon = { IconButton(onClick = { vm.back() }) { Icon(Lucide.ChevronLeft, "Back") } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Clio.Bg),
            )
        },
    ) { pad ->
        LazyColumn(contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = pad.calculateTopPadding(), bottom = pad.calculateBottomPadding() + 32.dp)) {
            item {
                SectionLabel("Scanning")
                Panel(padding = 0.dp) {
                    ToggleRow("Make scans searchable", "Recognise text right after scanning so you can search and copy it.", s.autoOcr) { v ->
                        vm.prefs.update { it.copy(autoOcr = v) }
                    }
                    Divider()
                    ToggleRow("Allow photos in the scanner", "Lets you add existing photos from the scanner screen.", s.galleryInScanner) { v ->
                        vm.prefs.update { it.copy(galleryInScanner = v) }
                    }
                    Divider()
                    ChoiceRow("Page look", listOf("original" to "Original", "gray" to "Grayscale", "bw" to "Black & white"), s.pageLook,
                        help = when (s.pageLook) {
                            "gray" -> "Removes shadows and evens out the paper, keeps shades of grey."
                            "bw" -> "Crisp black text on white, like a flatbed scanner. Best for forms and letters."
                            else -> "Keeps colours as captured (you can still pick a filter in the scanner)."
                        }) { v -> vm.prefs.update { it.copy(pageLook = v) } }
                    Divider()
                    ChoiceRow("Pages per scan", listOf("0" to "No limit", "1" to "1", "10" to "10", "30" to "30"), s.pageLimit.toString()) { v ->
                        vm.prefs.update { it.copy(pageLimit = v.toInt()) }
                    }
                    Divider()
                    ChoiceRow("Image quality", listOf("high" to "High", "balanced" to "Balanced", "small" to "Small"), s.quality,
                        help = when (s.quality) {
                            "high" -> "Sharpest pages, larger files."
                            "small" -> "Smallest files, good for messaging."
                            else -> "Clear text at a sensible size."
                        }) { v -> vm.prefs.update { it.copy(quality = v) } }
                    Divider()
                    Column(Modifier.padding(16.dp)) {
                        Text("Name for new scans", style = MaterialTheme.typography.titleSmall)
                        Spacer(Modifier.height(8.dp))
                        var pattern by remember(s.titlePattern) { mutableStateOf(s.titlePattern) }
                        OutlinedTextField(
                            pattern, { pattern = it; vm.prefs.update { st -> st.copy(titlePattern = it) } },
                            singleLine = true, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp),
                            colors = OutlinedTextFieldDefaults.colors(unfocusedBorderColor = Clio.Line),
                        )
                        Text("{date} becomes the date and time, e.g. “Scan 2026-11-01 14.05”.", style = MaterialTheme.typography.bodySmall,
                            color = Clio.Muted, modifier = Modifier.padding(top = 6.dp))
                    }
                }
            }
            item {
                SectionLabel("Tools")
                Panel(padding = 0.dp) {
                    ChoiceRow("Default compression", listOf("low" to "Light", "medium" to "Balanced", "high" to "Strong"), s.compressLevel) { v ->
                        vm.prefs.update { it.copy(compressLevel = v) }
                    }
                }
            }
            item {
                SectionLabel("Mac")
                Panel(padding = 0.dp) {
                    Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        IconTile(Lucide.Laptop, if (mac == null) Clio.Navy else Clio.Green, 38.dp, 19.dp)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(mac?.name ?: "Not paired", style = MaterialTheme.typography.titleSmall)
                            Text(
                                if (mac == null) "Pair with CLIO PDF Desktop to send documents to your Mac."
                                else "Files are encrypted on this phone and sent over your Wi-Fi only.",
                                style = MaterialTheme.typography.bodySmall, color = Clio.Muted,
                            )
                        }
                    }
                    Row(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 14.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (mac == null) {
                            OutlinedButton(onClick = { vm.go(Screen.Pair) }) { Text("Pair with a Mac") }
                        } else {
                            OutlinedButton(onClick = { vm.testMac() }) { Text("Test connection") }
                            OutlinedButton(onClick = { vm.go(Screen.Pair) }) { Text("Pair again") }
                            TextButton(onClick = { confirmUnpair = true }) { Text("Unpair", color = Clio.Red) }
                        }
                    }
                    if (mac != null) {
                        Divider()
                        ChoiceRow("Send as", listOf("pdf" to "PDF", "images" to "Images (JPG)"), s.sendAs,
                            help = if (s.sendAs == "images") "Each page arrives on the Mac as a separate JPG; several pages go in one transfer."
                            else "Each document arrives as one PDF; several documents go in one transfer.") { v ->
                            vm.prefs.update { it.copy(sendAs = v) }
                        }
                        Divider()
                        ChoiceRow("After sending", listOf("keep" to "Keep on phone", "delete" to "Delete from phone"), s.afterSend,
                            help = if (s.afterSend == "delete") "Private mode: once the Mac confirms it received the files, they are wiped from this phone."
                            else "Documents stay in CLIO Scan after they are sent.") { v ->
                            vm.prefs.update { it.copy(afterSend = v) }
                        }
                        Divider()
                        ToggleRow("Send new scans automatically", "Each new scan goes to ${mac?.name} right after it's saved.", s.autoSend) { v ->
                            vm.prefs.update { it.copy(autoSend = v) }
                        }
                    }
                }
            }
            item {
                SectionLabel("Privacy")
                Panel {
                    Row(verticalAlignment = Alignment.Top) {
                        Icon(Lucide.ShieldCheck, null, tint = Clio.Green, modifier = Modifier.size(22.dp))
                        Spacer(Modifier.width(12.dp))
                        Column {
                            Text("Your documents stay on this phone", style = MaterialTheme.typography.titleSmall)
                            Spacer(Modifier.height(4.dp))
                            Text(
                                "No account, no cloud and no analytics. Documents are kept inside CLIO Scan, are excluded from cloud " +
                                    "backup and leave the phone only when you share them or send them to your own Mac. Scanned pages never go to " +
                                    "your gallery, and the scanner's temporary images are deleted as soon as the PDF is made. Text recognition " +
                                    "runs on the phone and also works in airplane mode. PDFs made by CLIO Scan carry no author or software details.",
                                style = MaterialTheme.typography.bodySmall, color = Clio.Text2,
                            )
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                    OutlinedButton(onClick = { confirmWipe = true }, enabled = docs.isNotEmpty()) {
                        Icon(Lucide.Trash2, null, Modifier.size(16.dp), tint = Clio.Red)
                        Spacer(Modifier.width(6.dp))
                        Text("Delete all documents (${docs.size})", color = Clio.Red)
                    }
                }
            }
            item {
                SectionLabel("About")
                Panel {
                    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                        Image(painterResource(R.drawable.logo_mark), null, Modifier.size(52.dp))
                        Spacer(Modifier.height(8.dp))
                        Text("CLIO Scan", style = MaterialTheme.typography.titleLarge, color = Clio.Navy)
                        Text("Version $version · Companion to CLIO PDF Desktop", style = MaterialTheme.typography.bodySmall, color = Clio.Muted)
                        Spacer(Modifier.height(10.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TextButton(onClick = { licenses = true }) { Text("Open-source licenses") }
                            TextButton(onClick = { vm.prefs.reset() }) { Text("Reset settings") }
                        }
                    }
                }
            }
        }
    }

    if (confirmUnpair) ConfirmDialog("Unpair ${mac?.name ?: "Mac"}?", "You can pair again any time by scanning the QR code in CLIO PDF.",
        "Unpair", true, onConfirm = { vm.unpair() }, onDismiss = { confirmUnpair = false })
    if (confirmWipe) ConfirmDialog("Delete all documents?", "All ${docs.size} documents are removed from this phone. This can't be undone.",
        "Delete all", true, onConfirm = { vm.deleteAll() }, onDismiss = { confirmWipe = false })
    if (licenses) ConfirmDialog(
        "Open-source licenses",
        "PDFBox-Android — Apache License 2.0\nLucide icons — ISC License\nAndroidX & Jetpack Compose — Apache License 2.0\n" +
            "Kotlin & kotlinx.coroutines — Apache License 2.0\nML Kit (document scanner, text recognition, code scanner) — Google APIs Terms of Service",
        "OK", onConfirm = {}, onDismiss = { licenses = false },
    )
}

@Composable
private fun Divider() = HorizontalDivider(color = Clio.Line, modifier = Modifier.padding(start = 16.dp))

@Composable
private fun ToggleRow(title: String, sub: String, value: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(sub, style = MaterialTheme.typography.bodySmall, color = Clio.Muted)
        }
        Spacer(Modifier.width(12.dp))
        Switch(value, onChange, colors = SwitchDefaults.colors(checkedTrackColor = Clio.Green))
    }
}

@Composable
private fun ChoiceRow(title: String, options: List<Pair<String, String>>, value: String, help: String? = null, onChange: (String) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(16.dp)) {
        Text(title, style = MaterialTheme.typography.titleSmall)
        Spacer(Modifier.height(8.dp))
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            options.forEachIndexed { i, (key, label) ->
                SegmentedButton(
                    selected = key == value,
                    onClick = { onChange(key) },
                    shape = SegmentedButtonDefaults.itemShape(i, options.size),
                    icon = {},
                ) { Text(label, maxLines = 1, textAlign = TextAlign.Center) }
            }
        }
        if (help != null) Text(help, style = MaterialTheme.typography.bodySmall, color = Clio.Muted, modifier = Modifier.padding(top = 6.dp))
    }
}
