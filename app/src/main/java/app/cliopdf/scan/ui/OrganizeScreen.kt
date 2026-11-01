@file:OptIn(ExperimentalMaterial3Api::class)

package app.cliopdf.scan.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.cliopdf.scan.AppViewModel
import app.cliopdf.scan.pdf.PageRenderer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.min

private data class PageItem(val src: Int, val rotation: Int = 0, val key: Long = System.nanoTime() + src)

@Composable
fun OrganizeScreen(vm: AppViewModel, snackbar: SnackbarHostState, id: String, modifier: Modifier) {
    val docs by vm.docs.collectAsState()
    val doc = docs.firstOrNull { it.id == id } ?: return
    val renderer = remember(doc.file.path, doc.modified) { runCatching { PageRenderer(doc.file) }.getOrNull() } ?: return
    DisposableEffect(renderer) { onDispose { renderer.close() } }

    val pages = remember(renderer) { mutableStateListOf<PageItem>().apply { addAll((0 until renderer.pageCount).map { PageItem(it) }) } }
    var confirmLeave by remember { mutableStateOf(false) }
    val changed = pages.size != renderer.pageCount || pages.withIndex().any { (i, p) -> p.src != i || p.rotation != 0 }
    fun order() = pages.map { it.src to it.rotation }

    Scaffold(
        modifier,
        containerColor = Clio.Bg,
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text("Organize pages", style = MaterialTheme.typography.titleMedium) },
                navigationIcon = {
                    IconButton(onClick = { if (changed) confirmLeave = true else vm.back() }) { Icon(Lucide.ChevronLeft, "Back") }
                },
                actions = {
                    TextButton(onClick = { vm.saveOrganized(id, order(), asCopy = true) }, enabled = changed) { Text("Save copy") }
                    TextButton(onClick = { vm.saveOrganized(id, order(), asCopy = false) }, enabled = changed) {
                        Text("Save", fontWeight = FontWeight.Bold)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Clio.Bg),
            )
        },
    ) { pad ->
        LazyVerticalGrid(
            columns = GridCells.Adaptive(150.dp),
            contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = pad.calculateTopPadding(), bottom = pad.calculateBottomPadding() + 24.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(4.dp)) {
                    Text("Rotate, move or remove pages. Nothing changes until you tap Save.",
                        style = MaterialTheme.typography.bodySmall, color = Clio.Muted, modifier = Modifier.weight(1f))
                    if (changed) TextButton(onClick = {
                        pages.clear(); pages.addAll((0 until renderer.pageCount).map { PageItem(it) })
                    }) { Text("Reset") }
                }
            }
            itemsIndexed(pages, key = { _, p -> p.key }) { i, p ->
                Panel(padding = 8.dp) {
                    PageThumb(renderer, doc.modified, p.src, p.rotation)
                    Row(Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("${i + 1}", style = MaterialTheme.typography.labelLarge, color = Clio.Text2, modifier = Modifier.padding(start = 4.dp).weight(1f))
                        SmallIcon(Lucide.ChevronLeft, "Move earlier", i > 0) {
                            val x = pages.removeAt(i); pages.add(i - 1, x)
                        }
                        SmallIcon(Lucide.ChevronRight, "Move later", i < pages.lastIndex) {
                            val x = pages.removeAt(i); pages.add(i + 1, x)
                        }
                        SmallIcon(Lucide.RotateCw, "Rotate", true) { pages[i] = p.copy(rotation = (p.rotation + 90) % 360) }
                        SmallIcon(Lucide.Trash2, "Remove", pages.size > 1, Clio.Red) { pages.removeAt(i) }
                    }
                }
            }
        }
    }

    if (confirmLeave) ConfirmDialog("Discard changes?", "The page changes haven't been saved.", "Discard", true,
        onConfirm = { vm.back() }, onDismiss = { confirmLeave = false })
}

@Composable
private fun SmallIcon(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, enabled: Boolean, tint: Color = Clio.Text2, onClick: () -> Unit) {
    Box(
        Modifier.size(30.dp).clip(RoundedCornerShape(8.dp)).clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, label, tint = if (enabled) tint else Clio.Line, modifier = Modifier.size(17.dp))
    }
}

@Composable
private fun PageThumb(renderer: PageRenderer, version: Long, index: Int, rotation: Int) {
    val bmp by produceState<Bitmap?>(null, renderer, index, version) {
        value = withContext(Dispatchers.IO) { runCatching { renderer.render(index, 360) }.getOrNull() }
    }
    Box(
        Modifier.fillMaxWidth().aspectRatio(0.78f).clip(RoundedCornerShape(6.dp)).background(Clio.Panel2).border(1.dp, Clio.Line, RoundedCornerShape(6.dp)),
        contentAlignment = Alignment.Center,
    ) {
        val b = bmp
        if (b == null) {
            CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
        } else {
            val r = b.width.toFloat() / b.height
            val box = 0.78f
            // fit the (possibly rotated) page inside the cell
            val fitted = if (rotation % 180 == 0) 1f else min(box / (1f / r), (1f / r) / box).let { min(it, 1f) }
            Image(
                b.asImageBitmap(), null,
                Modifier.fillMaxSize().padding(6.dp).graphicsLayer {
                    rotationZ = rotation.toFloat(); scaleX = fitted; scaleY = fitted
                },
                contentScale = ContentScale.Fit,
            )
        }
    }
}
