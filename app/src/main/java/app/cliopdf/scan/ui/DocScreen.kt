@file:OptIn(ExperimentalMaterial3Api::class)

package app.cliopdf.scan.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.cliopdf.scan.Actions
import app.cliopdf.scan.AppViewModel
import app.cliopdf.scan.Screen
import app.cliopdf.scan.pdf.PageRenderer

@Composable
fun DocScreen(vm: AppViewModel, actions: Actions, snackbar: SnackbarHostState, id: String, modifier: Modifier) {
    val docs by vm.docs.collectAsState()
    val doc = docs.firstOrNull { it.id == id }
    if (doc == null) {
        Box(modifier.background(Clio.Bg))
        return
    }
    var menu by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }
    var compressing by remember { mutableStateOf(false) }

    val renderer = remember(doc.file.path, doc.modified) { runCatching { PageRenderer(doc.file) }.getOrNull() }
    DisposableEffect(renderer) { onDispose { renderer?.close() } }
    val widthPx = with(LocalDensity.current) { (LocalConfiguration.current.screenWidthDp.dp - 32.dp).roundToPx() }.coerceAtMost(1600)

    Scaffold(
        modifier,
        containerColor = Clio.Bg,
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = {
                    Column(Modifier.clickable { renaming = true }) {
                        Text(doc.title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text("${doc.pages} page${if (doc.pages == 1) "" else "s"} · ${AppViewModel.size(doc.size)}",
                            style = MaterialTheme.typography.bodySmall, color = Clio.Muted)
                    }
                },
                navigationIcon = { IconButton(onClick = { vm.back() }) { Icon(Lucide.ChevronLeft, "Back") } },
                actions = {
                    IconButton(onClick = { vm.go(Screen.Organize(id)) }) { Icon(Lucide.Layers, "Organize pages", tint = Clio.Text2) }
                    Box {
                        IconButton(onClick = { menu = true }) { Icon(Lucide.EllipsisVertical, "More", tint = Clio.Text2) }
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            DropdownMenuItem(text = { Text("Rename") }, leadingIcon = { Icon(Lucide.Pencil, null, Modifier.size(18.dp)) },
                                onClick = { menu = false; renaming = true })
                            DropdownMenuItem(text = { Text("Organize pages") }, leadingIcon = { Icon(Lucide.Layers, null, Modifier.size(18.dp)) },
                                onClick = { menu = false; vm.go(Screen.Organize(id)) })
                            DropdownMenuItem(text = { Text("Save to device") }, leadingIcon = { Icon(Lucide.Download, null, Modifier.size(18.dp)) },
                                onClick = { menu = false; actions.export(id, doc.title) })
                            HorizontalDivider()
                            DropdownMenuItem(text = { Text("Delete", color = Clio.Red) },
                                leadingIcon = { Icon(Lucide.Trash2, null, Modifier.size(18.dp), tint = Clio.Red) },
                                onClick = { menu = false; deleting = true })
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Clio.Bg),
            )
        },
        bottomBar = {
            Surface(color = Color.White, modifier = Modifier.shadow(8.dp)) {
                Row(
                    Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 6.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                ) {
                    val m = Modifier.weight(1f)
                    ActionButton(Lucide.Share2, "Share", m) { vm.share(id) }
                    ActionButton(Lucide.Laptop, "To Mac", m, tint = Clio.Blue) { vm.sendToMac(id) }
                    ActionButton(Lucide.ScanText, if (doc.searchable) "Re-check" else "OCR", m) { vm.makeSearchable(id) }
                    ActionButton(Lucide.Shrink, "Compress", m) { compressing = true }
                    ActionButton(Lucide.Type, "Text", m) { vm.extractText(id) }
                }
            }
        },
    ) { pad ->
        if (renderer == null) {
            Box(Modifier.fillMaxSize().padding(pad), contentAlignment = Alignment.Center) {
                Text("This PDF can't be displayed.", color = Clio.Muted)
            }
        } else {
            LazyColumn(
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = pad.calculateTopPadding() + 4.dp, bottom = pad.calculateBottomPadding() + 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        if (doc.searchable) Pill("Searchable", Lucide.ScanText, Clio.Blue, Clio.BlueSoft)
                        doc.sentTo?.let { Pill("Sent to $it", Lucide.Check, Clio.GreenText, Clio.GreenSoft) }
                        Pill("Stored on this phone", Lucide.Lock, Clio.Text2)
                    }
                }
                items(renderer.pageCount) { i ->
                    Column {
                        PageImage(renderer, doc.file, doc.modified, i, widthPx,
                            modifier = Modifier.clip(RoundedCornerShape(4.dp)).shadow(1.dp))
                        Text("${i + 1}", Modifier.fillMaxWidth().padding(top = 4.dp), color = Clio.Faint,
                            style = MaterialTheme.typography.labelMedium, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                    }
                }
            }
        }
    }

    if (renaming) RenameDialog(doc.title, onSave = { vm.rename(id, it); renaming = false }, onDismiss = { renaming = false })
    if (deleting) ConfirmDialog("Delete “${doc.title}”?", "The document is removed from this phone. This can't be undone.", "Delete", true,
        onConfirm = { vm.delete(id) }, onDismiss = { deleting = false })
    if (compressing) CompressDialog(vm.prefs.state.value.compressLevel,
        onPick = { compressing = false; vm.compress(id, it) }, onDismiss = { compressing = false })
}

@Composable
fun RenameDialog(current: String, onSave: (String) -> Unit, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf(current) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Rename") },
        text = {
            OutlinedTextField(name, { name = it }, singleLine = true, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp))
        },
        confirmButton = { TextButton(onClick = { onSave(name) }, enabled = name.isNotBlank()) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
