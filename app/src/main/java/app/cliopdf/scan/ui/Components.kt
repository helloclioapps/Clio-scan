package app.cliopdf.scan.ui

import android.graphics.Bitmap
import android.text.format.DateUtils
import android.util.LruCache
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import app.cliopdf.scan.Busy
import app.cliopdf.scan.pdf.PageRenderer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

@Composable
fun Panel(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    padding: Dp = 16.dp,
    content: @Composable ColumnScope.() -> Unit,
) {
    val shape = RoundedCornerShape(16.dp)
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .shadow(1.dp, shape, ambientColor = Color(0x14000000), spotColor = Color(0x14000000)),
        shape = shape,
        color = Clio.Panel,
        border = BorderStroke(1.dp, Clio.Line),
    ) {
        Column(
            Modifier
                .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
                .padding(padding),
            content = content,
        )
    }
}

@Composable
fun IconTile(icon: ImageVector, color: Color, size: Dp = 40.dp, iconSize: Dp = 20.dp) {
    Box(
        Modifier
            .size(size)
            .clip(RoundedCornerShape(size * 0.28f))
            .background(color),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, null, tint = Color.White, modifier = Modifier.size(iconSize))
    }
}

@Composable
fun Pill(text: String, icon: ImageVector? = null, fg: Color = Clio.Text2, bg: Color = Color(0xFFF1F4F8)) {
    Row(
        Modifier
            .clip(RoundedCornerShape(50))
            .background(bg)
            .padding(horizontal = 9.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        if (icon != null) Icon(icon, null, tint = fg, modifier = Modifier.size(12.dp))
        Text(text, color = fg, style = MaterialTheme.typography.labelMedium, maxLines = 1)
    }
}

@Composable
fun SectionLabel(text: String, trailing: String? = null) {
    Row(Modifier.padding(start = 4.dp, top = 22.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(text.uppercase(), style = MaterialTheme.typography.labelSmall, color = Clio.Muted)
        if (trailing != null) {
            Spacer(Modifier.width(8.dp))
            Text(trailing, style = MaterialTheme.typography.labelSmall, color = Clio.Faint)
        }
    }
}

@Composable
fun ToolButton(icon: ImageVector, label: String, color: Color, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Column(
        modifier
            .clip(RoundedCornerShape(14.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        IconTile(icon, color, 48.dp, 22.dp)
        Spacer(Modifier.height(7.dp))
        Text(label, style = MaterialTheme.typography.labelMedium, color = Clio.Text2, maxLines = 2,
            overflow = TextOverflow.Ellipsis, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
    }
}

@Composable
fun ActionButton(icon: ImageVector, label: String, modifier: Modifier = Modifier, tint: Color = Clio.Text2, onClick: () -> Unit) {
    Column(
        modifier
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp, horizontal = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(icon, null, tint = tint, modifier = Modifier.size(22.dp))
        Spacer(Modifier.height(4.dp))
        Text(label, style = MaterialTheme.typography.labelMedium, color = tint, maxLines = 1)
    }
}

// ---------------------------------------------------------------------- page images

private object Thumbs : LruCache<String, Bitmap>(24 * 1024 * 1024) {
    override fun sizeOf(key: String, value: Bitmap) = value.byteCount
}

/** First page of a PDF, rendered once and cached. */
@Composable
fun Thumb(file: File, version: Long, modifier: Modifier = Modifier, width: Int = 220) {
    val key = "${file.path}:$version:$width"
    val bmp by produceState<Bitmap?>(Thumbs.get(key), key) {
        if (value == null) value = withContext(Dispatchers.IO) {
            try { PageRenderer(file).use { it.render(0, width) }.also { Thumbs.put(key, it) } } catch (_: Exception) { null }
        }
    }
    Box(
        modifier
            .clip(RoundedCornerShape(6.dp))
            .background(Color.White)
            .border(1.dp, Clio.Line, RoundedCornerShape(6.dp)),
        contentAlignment = Alignment.Center,
    ) {
        bmp?.let { Image(it.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop, alignment = Alignment.TopCenter) }
            ?: Icon(Lucide.FileText, null, tint = Clio.Faint, modifier = Modifier.size(20.dp))
    }
}

/** One page of an open document, rendered at the given pixel width. */
@Composable
fun PageImage(renderer: PageRenderer, file: File, version: Long, index: Int, widthPx: Int, rotation: Int = 0, modifier: Modifier = Modifier) {
    val key = "${file.path}:$version:p$index:$widthPx"
    val bmp by produceState<Bitmap?>(Thumbs.get(key), key) {
        if (value == null) value = withContext(Dispatchers.IO) {
            try { renderer.render(index, widthPx).also { Thumbs.put(key, it) } } catch (_: Exception) { null }
        }
    }
    val ratio = bmp?.let { it.width.toFloat() / it.height } ?: 0.707f
    Box(
        modifier
            .fillMaxWidth()
            .then(Modifier.heightIn(min = 80.dp))
            .background(Color.White)
            .border(1.dp, Clio.Line),
        contentAlignment = Alignment.Center,
    ) {
        if (bmp != null) {
            Image(
                bmp!!.asImageBitmap(), "Page ${index + 1}",
                Modifier
                    .fillMaxWidth()
                    .then(Modifier.aspectRatioSafe(ratio))
                    .then(if (rotation != 0) Modifier.rotateBy(rotation) else Modifier),
                contentScale = ContentScale.Fit,
            )
        } else {
            Box(Modifier.fillMaxWidth().aspectRatioSafe(0.707f), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
            }
        }
    }
}

private fun Modifier.aspectRatioSafe(r: Float) = this.aspectRatio(r.coerceIn(0.2f, 5f))
private fun Modifier.rotateBy(deg: Int) = this.rotate(deg.toFloat())

// ---------------------------------------------------------------------- dialogs

@Composable
fun BusyDialog(busy: Busy) {
    AlertDialog(
        onDismissRequest = {},
        properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false),
        confirmButton = {},
        title = { Text(busy.title, style = MaterialTheme.typography.titleMedium) },
        text = {
            Column {
                if (busy.progress != null) {
                    LinearProgressIndicator(progress = { busy.progress ?: 0f }, modifier = Modifier.fillMaxWidth())
                } else {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                }
                Spacer(Modifier.height(10.dp))
                Text(busy.detail.ifEmpty { "Working on your phone…" }, color = Clio.Muted, style = MaterialTheme.typography.bodySmall)
            }
        },
    )
}

@Composable
fun TextDialog(title: String, text: String, onCopy: () -> Unit, onShare: () -> Unit, onClose: () -> Unit) {
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        text = {
            Box(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                SelectionContainer { Text(text, style = MaterialTheme.typography.bodyMedium, color = Clio.Text2) }
            }
        },
        confirmButton = { TextButton(onClick = onCopy) { Text("Copy all") } },
        dismissButton = {
            Row {
                TextButton(onClick = onShare) { Text("Share") }
                TextButton(onClick = onClose) { Text("Close") }
            }
        },
    )
}

@Composable
fun ConfirmDialog(title: String, text: String, confirm: String, danger: Boolean = false, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(text, color = Clio.Text2) },
        confirmButton = {
            TextButton(onClick = { onDismiss(); onConfirm() }) {
                Text(confirm, color = if (danger) Clio.Red else Clio.Blue, fontWeight = FontWeight.SemiBold)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

fun whenText(ms: Long): String =
    DateUtils.getRelativeTimeSpanString(ms, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS, DateUtils.FORMAT_ABBREV_RELATIVE).toString()
