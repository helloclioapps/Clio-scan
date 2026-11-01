package app.cliopdf.scan.net

import android.content.Context
import android.net.Uri
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.provider.Settings
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.Inet4Address
import java.net.URL
import java.nio.ByteBuffer
import java.security.SecureRandom
import java.util.concurrent.atomic.AtomicBoolean
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.coroutines.resume

/** The Mac this phone is paired with (from the QR code CLIO PDF shows). */
data class PairedMac(
    val macId: String,
    val name: String,
    val pairing: String,
    val key: ByteArray,
    val port: Int,
    val hosts: List<String>,
    val lastHost: String? = null,
) {
    fun toJson(): String = JSONObject()
        .put("macId", macId).put("name", name).put("pairing", pairing)
        .put("key", Base64.encodeToString(key, Base64.NO_WRAP)).put("port", port)
        .put("hosts", JSONArray(hosts)).put("lastHost", lastHost ?: "")
        .toString()

    companion object {
        fun fromJson(s: String): PairedMac? = try {
            val o = JSONObject(s)
            val hosts = o.getJSONArray("hosts").let { a -> (0 until a.length()).map { a.getString(it) } }
            PairedMac(
                o.getString("macId"), o.getString("name"), o.getString("pairing"),
                Base64.decode(o.getString("key"), Base64.NO_WRAP), o.getInt("port"), hosts,
                o.optString("lastHost").ifEmpty { null },
            )
        } catch (_: Exception) {
            null
        }

        /** clio-pdf://pair?v=1&id=..&n=..&p=..&h=ip1,ip2&k=<base64url key>&pid=.. */
        fun fromQr(text: String): PairedMac? = try {
            val u = Uri.parse(text.trim())
            if (u.scheme != "clio-pdf" || u.host != "pair") null
            else {
                val key = Base64.decode(u.getQueryParameter("k"), Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
                if (key.size != 32) null
                else PairedMac(
                    macId = u.getQueryParameter("id")!!,
                    name = u.getQueryParameter("n") ?: "Mac",
                    pairing = u.getQueryParameter("pid")!!,
                    key = key,
                    port = u.getQueryParameter("p")!!.toInt(),
                    hosts = (u.getQueryParameter("h") ?: "").split(",").map { it.trim() }.filter { it.isNotEmpty() },
                )
            }
        } catch (_: Exception) {
            null
        }
    }
}

class SendError(message: String) : Exception(message)

/**
 * Sends files to CLIO PDF on a Mac in the same Wi-Fi network.
 *
 * Every request body is encrypted with AES-256-GCM using the 32-byte key from the pairing QR code
 * (the key itself never travels over the network). Body layout before encryption:
 *   [4-byte big-endian header length][UTF-8 JSON header][file bytes]
 * The pairing id is used as additional authenticated data.
 */
class MacLink(private val context: Context) {
    private val sp = context.getSharedPreferences("mac", Context.MODE_PRIVATE)

    var mac: PairedMac?
        get() = sp.getString("paired", null)?.let { PairedMac.fromJson(it) }
        private set(v) {
            val e = sp.edit()
            if (v == null) e.remove("paired") else e.putString("paired", v.toJson())
            e.apply()
        }

    fun unpair() { mac = null }

    fun phoneName(): String {
        val n = try { Settings.Global.getString(context.contentResolver, Settings.Global.DEVICE_NAME) } catch (_: Exception) { null }
        return (n ?: "${Build.MANUFACTURER} ${Build.MODEL}").trim()
    }

    /** Pair with the Mac from a scanned QR code and confirm the connection. */
    suspend fun pair(qr: String): PairedMac {
        val m = PairedMac.fromQr(qr) ?: throw SendError("This is not a CLIO PDF pairing code.")
        val host = request(m, JSONObject().put("type", "hello").put("phone", phoneName()), null, null)
        val saved = m.copy(lastHost = host)
        mac = saved
        return saved
    }

    /** Checks that the paired Mac can be reached; returns the host that answered. */
    suspend fun test(): String {
        val m = mac ?: throw SendError("No Mac is paired yet.")
        val host = request(m, JSONObject().put("type", "hello").put("phone", phoneName()), null, null)
        mac = m.copy(lastHost = host)
        return host
    }

    suspend fun send(file: File, name: String, onProgress: (Float) -> Unit): String {
        val m = mac ?: throw SendError("No Mac is paired yet.")
        val header = JSONObject().put("type", "file").put("name", name).put("size", file.length()).put("phone", phoneName())
        val host = request(m, header, file, onProgress)
        mac = m.copy(lastHost = host)
        return m.name
    }

    private suspend fun request(m: PairedMac, header: JSONObject, file: File?, onProgress: ((Float) -> Unit)?): String =
        withContext(Dispatchers.IO) {
            val candidates = LinkedHashSet<Pair<String, Int>>()
            discover(m.macId)?.let { candidates += it }
            m.lastHost?.let { candidates += it to m.port }
            m.hosts.forEach { candidates += it to m.port }
            if (candidates.isEmpty()) throw SendError("Your Mac's address is unknown. Pair again from CLIO PDF.")
            var last: Exception? = null
            for ((host, port) in candidates) {
                try {
                    post(m, host, port, header, file, onProgress)
                    return@withContext host
                } catch (e: SendError) {
                    throw e
                } catch (e: Exception) {
                    last = e
                }
            }
            throw SendError(
                "Can't reach ${m.name}. Make sure CLIO PDF is open on the Mac and both devices are on the same Wi-Fi." +
                    (last?.message?.let { "\n($it)" } ?: "")
            )
        }

    private fun post(m: PairedMac, host: String, port: Int, header: JSONObject, file: File?, onProgress: ((Float) -> Unit)?) {
        val headerBytes = header.toString().toByteArray(Charsets.UTF_8)
        val fileLen = file?.length() ?: 0L
        val plainLen = 4L + headerBytes.size + fileLen
        val nonce = ByteArray(12).also { SecureRandom().nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(m.key, "AES"), GCMParameterSpec(128, nonce))
        cipher.updateAAD(m.pairing.toByteArray(Charsets.UTF_8))

        val path = if (file == null) "hello" else "upload"
        val h = if (host.contains(':')) "[$host]" else host
        val conn = URL("http://$h:$port/v1/$path").openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.connectTimeout = 3500
            conn.readTimeout = 120_000
            conn.doOutput = true
            conn.setFixedLengthStreamingMode(plainLen + 16)
            conn.setRequestProperty("Content-Type", "application/octet-stream")
            conn.setRequestProperty("X-Clio-Pairing", m.pairing)
            conn.setRequestProperty("X-Clio-Nonce", Base64.encodeToString(nonce, Base64.NO_WRAP))
            conn.outputStream.use { os ->
                val start = ByteBuffer.allocate(4).putInt(headerBytes.size).array() + headerBytes
                cipher.update(start)?.let { os.write(it) }
                if (file != null) {
                    file.inputStream().use { input ->
                        val buf = ByteArray(64 * 1024)
                        var sent = 0L
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            cipher.update(buf, 0, n)?.let { os.write(it) }
                            sent += n
                            onProgress?.invoke(sent.toFloat() / fileLen.coerceAtLeast(1))
                        }
                    }
                }
                cipher.doFinal()?.let { os.write(it) }
            }
            val code = conn.responseCode
            val body = try {
                (if (code in 200..299) conn.inputStream else conn.errorStream)?.bufferedReader()?.use { it.readText() } ?: ""
            } catch (_: Exception) { "" }
            when (code) {
                200 -> return
                401, 403 -> throw SendError("${m.name} no longer recognises this phone. Pair again from CLIO PDF on the Mac.")
                413 -> throw SendError("The file is too large to send.")
                503 -> throw SendError("Receiving is turned off in CLIO PDF on ${m.name}. Turn it on in Settings › Integrations.")
                else -> throw SendError(
                    try { JSONObject(body).optString("error") } catch (_: Exception) { "" }.ifEmpty { "The Mac answered with an error ($code)." }
                )
            }
        } finally {
            conn.disconnect()
        }
    }

    /** Finds the Mac on the local network through Bonjour (DNS-SD). */
    private suspend fun discover(macId: String): Pair<String, Int>? = withTimeoutOrNull(3000) {
        suspendCancellableCoroutine<Pair<String, Int>?> { cont ->
            val nsd = context.getSystemService(NsdManager::class.java)
            val wifi = context.applicationContext.getSystemService(WifiManager::class.java)
            val lock = wifi?.createMulticastLock("clio-scan")?.apply { setReferenceCounted(false); acquire() }
            val finished = AtomicBoolean(false)
            val resolving = AtomicBoolean(false)
            lateinit var listener: NsdManager.DiscoveryListener

            fun finish(result: Pair<String, Int>?) {
                if (!finished.compareAndSet(false, true)) return
                try { nsd.stopServiceDiscovery(listener) } catch (_: Exception) {}
                try { lock?.release() } catch (_: Exception) {}
                if (cont.isActive) cont.resume(result)
            }

            listener = object : NsdManager.DiscoveryListener {
                override fun onDiscoveryStarted(serviceType: String) {}
                override fun onDiscoveryStopped(serviceType: String) {}
                override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) = finish(null)
                override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {}
                override fun onServiceLost(info: NsdServiceInfo) {}
                override fun onServiceFound(info: NsdServiceInfo) {
                    if (!resolving.compareAndSet(false, true)) return
                    @Suppress("DEPRECATION")
                    nsd.resolveService(info, object : NsdManager.ResolveListener {
                        override fun onResolveFailed(si: NsdServiceInfo, errorCode: Int) { resolving.set(false) }
                        override fun onServiceResolved(si: NsdServiceInfo) {
                            resolving.set(false)
                            val id = si.attributes["id"]?.let { String(it, Charsets.UTF_8) }
                            @Suppress("DEPRECATION")
                            val addr = si.host
                            if (id == macId && addr != null) {
                                val ip = addr.hostAddress ?: return
                                if (addr is Inet4Address || !ip.startsWith("fe80")) finish(ip to si.port)
                            }
                        }
                    })
                }
            }
            cont.invokeOnCancellation { finish(null) }
            try {
                nsd.discoverServices("_cliopdf._tcp", NsdManager.PROTOCOL_DNS_SD, listener)
            } catch (_: Exception) {
                finish(null)
            }
        }
    }
}
