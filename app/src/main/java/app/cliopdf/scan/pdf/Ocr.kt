package app.cliopdf.scan.pdf

import android.graphics.Bitmap
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.tasks.await

/** On-device text recognition (the model ships inside the app, nothing is uploaded). */
object Ocr {
    private val client by lazy { TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS) }

    suspend fun read(bitmap: Bitmap): Text = client.process(InputImage.fromBitmap(bitmap, 0)).await()

    /** Plain text in reading order, one recognised line per line. */
    fun plain(text: Text): String = text.textBlocks.joinToString("\n\n") { b -> b.lines.joinToString("\n") { it.text } }
}
