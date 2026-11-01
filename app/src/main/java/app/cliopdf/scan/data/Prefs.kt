package app.cliopdf.scan.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

data class Settings(
    val autoOcr: Boolean = true,
    val galleryInScanner: Boolean = true,
    val pageLimit: Int = 0,               // 0 = no limit
    val quality: String = "balanced",     // high | balanced | small
    val titlePattern: String = "Scan {date}",
    val autoSend: Boolean = false,
    val compressLevel: String = "medium", // low | medium | high
    val pageLook: String = "original",    // original | gray | bw  (clean-up filter applied to scans)
    val afterSend: String = "keep",       // keep | delete  (delete = nothing stays on the phone)
    val sendAs: String = "pdf",           // pdf | images
)

class Prefs(context: Context) {
    private val sp = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
    private val _state = MutableStateFlow(load())
    val state: StateFlow<Settings> = _state

    private fun load() = Settings(
        autoOcr = sp.getBoolean("autoOcr", true),
        galleryInScanner = sp.getBoolean("galleryInScanner", true),
        pageLimit = sp.getInt("pageLimit", 0),
        quality = sp.getString("quality", "balanced") ?: "balanced",
        titlePattern = sp.getString("titlePattern", "Scan {date}") ?: "Scan {date}",
        autoSend = sp.getBoolean("autoSend", false),
        compressLevel = sp.getString("compressLevel", "medium") ?: "medium",
        pageLook = sp.getString("pageLook", "original") ?: "original",
        afterSend = sp.getString("afterSend", "keep") ?: "keep",
        sendAs = sp.getString("sendAs", "pdf") ?: "pdf",
    )

    fun update(change: (Settings) -> Settings) {
        val s = change(_state.value)
        sp.edit()
            .putBoolean("autoOcr", s.autoOcr)
            .putBoolean("galleryInScanner", s.galleryInScanner)
            .putInt("pageLimit", s.pageLimit)
            .putString("quality", s.quality)
            .putString("titlePattern", s.titlePattern)
            .putBoolean("autoSend", s.autoSend)
            .putString("compressLevel", s.compressLevel)
            .putString("pageLook", s.pageLook)
            .putString("afterSend", s.afterSend)
            .putString("sendAs", s.sendAs)
            .apply()
        _state.value = s
    }

    fun reset() = update { Settings() }
}

fun jpegQuality(q: String) = when (q) {
    "high" -> 92
    "small" -> 68
    else -> 82
}

fun maxImageSide(q: String) = when (q) {
    "high" -> 3300
    "small" -> 1700
    else -> 2400
}
