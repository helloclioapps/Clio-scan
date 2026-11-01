package app.cliopdf.scan.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.cliopdf.scan.Actions
import app.cliopdf.scan.AppViewModel
import app.cliopdf.scan.R
import app.cliopdf.scan.Screen
import app.cliopdf.scan.data.Doc

private enum class Tool { Ocr, Compress, Text, Organize }

@Composable
fun HomeScreen(vm: AppViewModel, actions: Actions, snackbar: SnackbarHostState, modifier: Modifier) {
    val docs by vm.docs.collectAsState()
    val mac by vm.mac.collectAsState()
    var query by remember { mutableStateOf("") }
    var pickFor by remember { mutableStateOf<Tool?>(null) }
    var compressId by remember { mutableStateOf<String?>(null) }

    Scaffold(modifier, containerColor = Clio.Bg, snackbarHost = { SnackbarHost(snackbar) }) { pad ->
        LazyColumn(
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = pad.calculateTopPadding() + 8.dp, bottom = pad.calculateBottomPadding() + 24.dp),
        ) {
            item { Header(onSettings = { vm.go(Screen.Settings) }) }
            item {
                val s by vm.prefs.state.collectAsState()
                ScanCard(actions, s.pageLook) { look -> vm.prefs.update { it.copy(pageLook = look) } }
            }
            item {
                val unsent = docs.count { it.sentTo == null }
                val s by vm.prefs.state.collectAsState()
                MacCard(mac?.name, unsent, s.afterSend == "delete", onPair = { vm.go(Screen.Pair) }, onTest = { vm.testMac() }, onSendNew = { vm.sendAllNew() })
            }
            item {
                SectionLabel("Tools")
                Panel(padding = 8.dp) {
                    Row(Modifier.fillMaxWidth()) {
                        val m = Modifier.weight(1f)
                        ToolButton(Lucide.ScanText, "Make searchable", Clio.TOcr, m) { pickFor = Tool.Ocr }
                        ToolButton(Lucide.Files, "Merge PDFs", Clio.TMerge, m) {
                            if (docs.size < 2) vm.note("You need at least two documents to merge.") else vm.go(Screen.Merge)
                        }
                        ToolButton(Lucide.Shrink, "Compress", Clio.TCompress, m) { pickFor = Tool.Compress }
                        ToolButton(Lucide.Type, "Copy text", Clio.TText, m) { pickFor = Tool.Text }
                    }
                }
            }
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    SectionLabel("Documents", if (docs.isEmpty()) null else "${docs.size}")
                }
                if (docs.size > 4) {
                    OutlinedTextField(
                        value = query, onValueChange = { query = it },
                        modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp),
                        placeholder = { Text("Search documents") },
                        leadingIcon = { Icon(Lucide.Search, null, Modifier.size(18.dp), tint = Clio.Muted) },
                        singleLine = true,
                        shape = RoundedCornerShape(12.dp),
                        colors = OutlinedTextFieldDefaults.colors(unfocusedContainerColor = Color.White, focusedContainerColor = Color.White,
                            unfocusedBorderColor = Clio.Line),
                    )
                }
            }
            val shown = if (query.isBlank()) docs else docs.filter { it.title.contains(query.trim(), true) }
            if (docs.isEmpty()) item { EmptyState() }
            items(shown, key = { it.id }) { d ->
                DocRow(d) { vm.go(Screen.Document(d.id)) }
                Spacer(Modifier.height(8.dp))
            }
            item { PrivacyFooter() }
        }
    }

    pickFor?.let { tool ->
        DocPicker(
            title = when (tool) {
                Tool.Ocr -> "Make searchable"; Tool.Compress -> "Compress"; Tool.Text -> "Copy text"; Tool.Organize -> "Organize pages"
            },
            docs = docs,
            onPick = { d ->
                pickFor = null
                when (tool) {
                    Tool.Ocr -> vm.makeSearchable(d.id)
                    Tool.Compress -> compressId = d.id
                    Tool.Text -> vm.extractText(d.id)
                    Tool.Organize -> vm.go(Screen.Organize(d.id))
                }
            },
            onDismiss = { pickFor = null },
        )
    }
    compressId?.let { id ->
        CompressDialog(vm.prefs.state.value.compressLevel, onPick = { lvl -> compressId = null; vm.compress(id, lvl) }, onDismiss = { compressId = null })
    }
}

@Composable
private fun Header(onSettings: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Image(painterResource(R.drawable.logo_mark), null, Modifier.size(38.dp))
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text("CLIO Scan", style = MaterialTheme.typography.headlineSmall, color = Clio.Navy)
            Spacer(Modifier.height(2.dp))
            Pill("100% Offline & Private", Lucide.Lock, Clio.GreenText, Clio.GreenSoft)
        }
        IconButton(onClick = onSettings) { Icon(Lucide.Settings, "Settings", tint = Clio.Text2) }
    }
}

private val LOOKS = listOf("original" to "Original", "gray" to "Grayscale", "bw" to "B&W clean")

@Composable
private fun ScanCard(actions: Actions, look: String, onLook: (String) -> Unit) {
    val shape = RoundedCornerShape(20.dp)
    Column(
        Modifier
            .padding(top = 14.dp)
            .fillMaxWidth()
            .clip(shape)
            .background(Brush.linearGradient(listOf(Color(0xFF1C8BF5), Clio.Blue, Color(0xFF1652C9)))),
    ) {
        Row(
            Modifier.fillMaxWidth().clickable(onClick = actions.scan).padding(20.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(56.dp).clip(RoundedCornerShape(16.dp)).background(Color.White.copy(alpha = 0.18f)), contentAlignment = Alignment.Center) {
                Icon(Lucide.Camera, null, tint = Color.White, modifier = Modifier.size(28.dp))
            }
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text("Scan a document", color = Color.White, style = MaterialTheme.typography.titleLarge)
                Spacer(Modifier.height(3.dp))
                Text("Edges are found automatically. Add as many pages as you need.", color = Color.White.copy(alpha = 0.85f),
                    style = MaterialTheme.typography.bodySmall)
            }
        }
        Row(
            Modifier.fillMaxWidth().background(Color.Black.copy(alpha = 0.10f)).padding(horizontal = 8.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
        ) {
            ScanChip(Lucide.ImagePlus, "Photos", actions.pickPhotos, Modifier.weight(1f))
            ScanChip(Lucide.FileUp, "Import PDF", actions.pickPdfs, Modifier.weight(1f))
            val i = LOOKS.indexOfFirst { it.first == look }.coerceAtLeast(0)
            ScanChip(Lucide.Sparkles, LOOKS[i].second, { onLook(LOOKS[(i + 1) % LOOKS.size].first) }, Modifier.weight(1f))
        }
    }
}

@Composable
private fun ScanChip(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, onClick: () -> Unit, modifier: Modifier) {
    Row(
        modifier.clip(RoundedCornerShape(10.dp)).clickable(onClick = onClick).padding(vertical = 10.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = Color.White, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Text(label, color = Color.White, style = MaterialTheme.typography.labelLarge)
    }
}

@Composable
private fun MacCard(macName: String?, unsent: Int, ephemeral: Boolean, onPair: () -> Unit, onTest: () -> Unit, onSendNew: () -> Unit) {
    Spacer(Modifier.height(12.dp))
    Panel(onClick = if (macName == null) onPair else onTest, padding = 14.dp) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconTile(Lucide.Laptop, if (macName == null) Clio.Navy else Clio.Green, 40.dp, 20.dp)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(if (macName == null) "Send to your Mac" else "Paired with $macName", style = MaterialTheme.typography.titleSmall,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    if (macName == null) "Pair once with CLIO PDF Desktop, then send over your Wi-Fi."
                    else if (ephemeral) "Encrypted over your Wi-Fi · private mode: sent files are wiped from this phone."
                    else "Documents go straight to the Mac over your Wi-Fi, encrypted.",
                    style = MaterialTheme.typography.bodySmall, color = Clio.Muted,
                )
            }
            Text(if (macName == null) "Pair" else "Test", color = Clio.Blue, style = MaterialTheme.typography.labelLarge)
        }
        if (macName != null && unsent > 0) {
            Spacer(Modifier.height(10.dp))
            androidx.compose.material3.Button(
                onClick = onSendNew,
                modifier = Modifier.fillMaxWidth().height(42.dp),
                shape = RoundedCornerShape(12.dp),
                colors = androidx.compose.material3.ButtonDefaults.buttonColors(containerColor = Clio.Blue),
            ) {
                Icon(Lucide.Send, null, Modifier.size(16.dp))
                Spacer(Modifier.width(8.dp))
                Text(if (unsent == 1) "Send 1 new document" else "Send $unsent new documents in one go")
            }
        }
    }
}

@Composable
fun DocRow(d: Doc, trailing: (@Composable () -> Unit)? = null, onClick: () -> Unit) {
    Panel(onClick = onClick, padding = 10.dp) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Thumb(d.file, d.modified, Modifier.size(width = 46.dp, height = 60.dp), width = 160)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(d.title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(2.dp))
                Text(
                    "${d.pages} page${if (d.pages == 1) "" else "s"} · ${AppViewModel.size(d.size)} · ${whenText(d.modified)}",
                    style = MaterialTheme.typography.bodySmall, color = Clio.Muted, maxLines = 1,
                )
                if (d.searchable || d.sentTo != null) {
                    Spacer(Modifier.height(5.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        if (d.searchable) Pill("Searchable", Lucide.ScanText, Clio.Blue, Clio.BlueSoft)
                        if (d.sentTo != null) Pill("Sent to Mac", Lucide.Check, Clio.GreenText, Clio.GreenSoft)
                    }
                }
            }
            if (trailing != null) trailing() else Icon(Lucide.ChevronRight, null, tint = Clio.Faint, modifier = Modifier.size(18.dp))
        }
    }
}

@Composable
private fun EmptyState() {
    Panel(padding = 24.dp) {
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(Lucide.FileScan, null, tint = Clio.Faint, modifier = Modifier.size(36.dp))
            Spacer(Modifier.height(10.dp))
            Text("No documents yet", style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(4.dp))
            Text("Scan a paper, pick photos or import a PDF. Everything stays on this phone.",
                style = MaterialTheme.typography.bodySmall, color = Clio.Muted,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        }
    }
}

@Composable
private fun PrivacyFooter() {
    Row(Modifier.fillMaxWidth().padding(top = 18.dp), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
        Icon(Lucide.ShieldCheck, null, tint = Clio.Faint, modifier = Modifier.size(14.dp))
        Spacer(Modifier.width(6.dp))
        Text("No account · No cloud · Text recognition runs on this phone", style = MaterialTheme.typography.bodySmall, color = Clio.Faint)
    }
}

@Composable
fun DocPicker(title: String, docs: List<Doc>, onPick: (Doc) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            if (docs.isEmpty()) Text("Scan or import a document first.", color = Clio.Muted)
            else LazyColumn(Modifier.heightIn(max = 420.dp)) {
                items(docs, key = { it.id }) { d ->
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable { onPick(d) }.padding(vertical = 8.dp, horizontal = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Thumb(d.file, d.modified, Modifier.size(width = 34.dp, height = 44.dp), width = 120)
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text(d.title, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.SemiBold)
                            Text("${d.pages} pages · ${AppViewModel.size(d.size)}", style = MaterialTheme.typography.bodySmall, color = Clio.Muted)
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
fun CompressDialog(current: String, onPick: (String) -> Unit, onDismiss: () -> Unit) {
    val levels = listOf(
        Triple("low", "Light", "Best quality, modest savings"),
        Triple("medium", "Balanced", "Recommended for email and WhatsApp"),
        Triple("high", "Strong", "Smallest file, softer images"),
    )
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Compress") },
        text = {
            Column {
                levels.forEach { (key, label, sub) ->
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp))
                            .background(if (key == current) Clio.BlueSoft else Color.Transparent)
                            .clickable { onPick(key) }.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(label, fontWeight = FontWeight.SemiBold)
                            Text(sub, style = MaterialTheme.typography.bodySmall, color = Clio.Muted)
                        }
                        if (key == current) Pill("Default", null, Clio.Blue, Color.White)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
