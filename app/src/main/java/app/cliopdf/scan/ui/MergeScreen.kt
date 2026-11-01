@file:OptIn(ExperimentalMaterial3Api::class)

package app.cliopdf.scan.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.cliopdf.scan.AppViewModel

@Composable
fun MergeScreen(vm: AppViewModel, snackbar: SnackbarHostState, modifier: Modifier) {
    val docs by vm.docs.collectAsState()
    val chosen = remember { mutableStateListOf<String>() }
    var title by remember { mutableStateOf("Merged document") }
    val ordered = chosen.mapNotNull { id -> docs.firstOrNull { it.id == id } }
    val rest = docs.filter { it.id !in chosen }

    Scaffold(
        modifier,
        containerColor = Clio.Bg,
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text("Merge PDFs", style = MaterialTheme.typography.titleMedium) },
                navigationIcon = { IconButton(onClick = { vm.back() }) { Icon(Lucide.ChevronLeft, "Back") } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Clio.Bg),
            )
        },
        bottomBar = {
            Surface(color = Color.White, modifier = Modifier.shadow(8.dp)) {
                Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(16.dp)) {
                    OutlinedTextField(
                        title, { title = it }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                        label = { Text("Name of the new PDF") }, shape = RoundedCornerShape(12.dp),
                        colors = OutlinedTextFieldDefaults.colors(unfocusedBorderColor = Clio.Line),
                    )
                    Spacer(Modifier.height(10.dp))
                    Button(
                        onClick = { vm.merge(chosen.toList(), title) },
                        enabled = chosen.size >= 2,
                        modifier = Modifier.fillMaxWidth().height(48.dp),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Clio.Blue),
                    ) {
                        Icon(Lucide.Files, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(if (chosen.size < 2) "Choose at least 2 documents" else "Merge ${chosen.size} documents")
                    }
                }
            }
        },
    ) { pad ->
        LazyColumn(contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = pad.calculateTopPadding(), bottom = pad.calculateBottomPadding() + 16.dp)) {
            if (ordered.isNotEmpty()) {
                item { SectionLabel("Merge in this order", "${ordered.size}") }
                items(ordered, key = { "o" + it.id }) { d ->
                    val i = chosen.indexOf(d.id)
                    DocRow(d, trailing = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            IconButton(onClick = { if (i > 0) { chosen.removeAt(i); chosen.add(i - 1, d.id) } }, enabled = i > 0) {
                                Icon(Lucide.ArrowUp, "Move up", Modifier.size(18.dp))
                            }
                            IconButton(onClick = { if (i < chosen.lastIndex) { chosen.removeAt(i); chosen.add(i + 1, d.id) } }, enabled = i < chosen.lastIndex) {
                                Icon(Lucide.ArrowDown, "Move down", Modifier.size(18.dp))
                            }
                        }
                    }) { chosen.remove(d.id) }
                    Spacer(Modifier.height(8.dp))
                }
            }
            if (rest.isNotEmpty()) {
                item { SectionLabel(if (ordered.isEmpty()) "Tap documents to add them" else "Add more") }
                items(rest, key = { "r" + it.id }) { d ->
                    DocRow(d, trailing = {
                        Box(Modifier.size(26.dp).clip(CircleShape).background(Clio.BlueSoft), contentAlignment = Alignment.Center) {
                            Icon(Lucide.Plus, "Add", tint = Clio.Blue, modifier = Modifier.size(16.dp))
                        }
                    }) { chosen.add(d.id) }
                    Spacer(Modifier.height(8.dp))
                }
            }
            item {
                Text("Tip: tap a chosen document again to take it out.", style = MaterialTheme.typography.bodySmall,
                    color = Clio.Faint, fontWeight = FontWeight.Normal, modifier = Modifier.padding(top = 8.dp, start = 4.dp))
            }
        }
    }
}
