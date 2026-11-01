package app.cliopdf.scan

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.core.content.IntentCompat
import app.cliopdf.scan.ui.BusyDialog
import app.cliopdf.scan.ui.ClioTheme
import app.cliopdf.scan.ui.DocScreen
import app.cliopdf.scan.ui.HomeScreen
import app.cliopdf.scan.ui.MergeScreen
import app.cliopdf.scan.ui.OrganizeScreen
import app.cliopdf.scan.ui.PairScreen
import app.cliopdf.scan.ui.SettingsScreen
import app.cliopdf.scan.ui.TextDialog
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning
import com.google.mlkit.vision.documentscanner.GmsDocumentScannerOptions
import com.google.mlkit.vision.documentscanner.GmsDocumentScanning
import com.google.mlkit.vision.documentscanner.GmsDocumentScanningResult
import kotlinx.coroutines.launch

/** Things screens can ask the activity to do (launch the scanner, open pickers…). */
class Actions(
    val scan: () -> Unit,
    val pickPhotos: () -> Unit,
    val pickPdfs: () -> Unit,
    val export: (id: String, title: String) -> Unit,
    val scanQr: () -> Unit,
    val copy: (String) -> Unit,
)

class MainActivity : ComponentActivity() {
    private val vm: AppViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (savedInstanceState == null) handleIncoming(intent)
        setContent { ClioTheme { ClioRoot(vm) } }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIncoming(intent)
    }

    /** Files shared to CLIO Scan from other apps ("Share › CLIO Scan" or "Open with"). */
    private fun handleIncoming(i: Intent?) {
        if (i == null) return
        val uris: List<Uri> = when (i.action) {
            Intent.ACTION_SEND -> listOfNotNull(IntentCompat.getParcelableExtra(i, Intent.EXTRA_STREAM, Uri::class.java))
            Intent.ACTION_SEND_MULTIPLE -> IntentCompat.getParcelableArrayListExtra(i, Intent.EXTRA_STREAM, Uri::class.java) ?: emptyList()
            Intent.ACTION_VIEW -> listOfNotNull(i.data)
            else -> emptyList()
        }
        if (uris.isEmpty()) return
        val pdfs = uris.filter { (contentResolver.getType(it) ?: i.type ?: "").contains("pdf") }
        val images = uris - pdfs.toSet()
        if (pdfs.isNotEmpty()) vm.importPdfs(pdfs)
        if (images.isNotEmpty()) vm.importImages(images)
    }
}

@Composable
fun ClioRoot(vm: AppViewModel) {
    val context = LocalContext.current
    val activity = context as Activity
    val clipboard = LocalClipboardManager.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val busy by vm.busy.collectAsState()
    val text by vm.textResult.collectAsState()
    val settings by vm.prefs.state.collectAsState()
    var exportId by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        vm.notes.collect { n ->
            val r = snackbar.showSnackbar(n.text, n.action, withDismissAction = n.action == null,
                duration = if (n.action != null) SnackbarDuration.Long else SnackbarDuration.Short)
            if (r == SnackbarResult.ActionPerformed) n.onAction?.invoke()
        }
    }
    LaunchedEffect(Unit) {
        vm.intents.collect { runCatching { context.startActivity(it) } }
    }

    val scanLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { r ->
        if (r.resultCode == Activity.RESULT_OK) {
            val pages = GmsDocumentScanningResult.fromActivityResultIntent(r.data)?.pages?.map { it.imageUri } ?: emptyList()
            vm.onScanned(pages)
        }
    }
    val photoLauncher = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(60)) { vm.importImages(it) }
    val pdfLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { vm.importPdfs(it) }
    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/pdf")) { uri ->
        val id = exportId
        if (uri != null && id != null) vm.exportTo(id, uri)
        exportId = null
    }

    val actions = remember(settings) {
        Actions(
            scan = {
                val opts = GmsDocumentScannerOptions.Builder()
                    .setScannerMode(GmsDocumentScannerOptions.SCANNER_MODE_FULL)
                    .setGalleryImportAllowed(settings.galleryInScanner)
                    .setResultFormats(GmsDocumentScannerOptions.RESULT_FORMAT_JPEG)
                    .apply { if (settings.pageLimit > 0) setPageLimit(settings.pageLimit) }
                    .build()
                GmsDocumentScanning.getClient(opts).getStartScanIntent(activity)
                    .addOnSuccessListener { scanLauncher.launch(IntentSenderRequest.Builder(it).build()) }
                    .addOnFailureListener {
                        vm.note("The scanner couldn't start. Make sure Google Play services is up to date, then try again.")
                    }
            },
            pickPhotos = { photoLauncher.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
            pickPdfs = { pdfLauncher.launch(arrayOf("application/pdf")) },
            export = { id, title -> exportId = id; exportLauncher.launch("$title.pdf") },
            scanQr = {
                val opts = GmsBarcodeScannerOptions.Builder().setBarcodeFormats(Barcode.FORMAT_QR_CODE).build()
                GmsBarcodeScanning.getClient(activity, opts).startScan()
                    .addOnSuccessListener { code -> code.rawValue?.let { vm.pair(it) } }
                    .addOnFailureListener { vm.note("The QR scanner couldn't start. Make sure Google Play services is up to date.") }
            },
            copy = { s -> clipboard.setText(AnnotatedString(s)); scope.launch { snackbar.showSnackbar("Copied.") } },
        )
    }

    BackHandler(enabled = vm.stack.size > 1) { vm.back() }

    val mod = Modifier.fillMaxSize()
    when (val s = vm.screen) {
        Screen.Home -> HomeScreen(vm, actions, snackbar, mod)
        is Screen.Document -> DocScreen(vm, actions, snackbar, s.id, mod)
        is Screen.Organize -> OrganizeScreen(vm, snackbar, s.id, mod)
        Screen.Merge -> MergeScreen(vm, snackbar, mod)
        Screen.Settings -> SettingsScreen(vm, actions, snackbar, mod)
        Screen.Pair -> PairScreen(vm, actions, snackbar, mod)
    }

    busy?.let { BusyDialog(it) }
    text?.let { t ->
        TextDialog(
            t.title, t.text,
            onCopy = { actions.copy(t.text) },
            onShare = { vm.shareText(t.title, t.text) },
            onClose = { vm.textResult.value = null },
        )
    }
}
