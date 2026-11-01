package app.cliopdf.scan.net

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
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
import java.net.ConnectException
import java.net.HttpURLConnection
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
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

    /** True once the Mac has answered at least once. */
    val confirmed: Boolean get() = sp.getBoolean("confirmed", false)
    private fun setConfirmed(v: Boolean) = sp.edit().putBoolean("confirmed", v).apply()

    fun unpair() { mac = null; setConfirmed(false) }

    fun phoneName(): String {
        val n = try { Settings.Global.getString(context.contentResolver, Settings.Global.DEVICE_NAME) } catch (_: Exception) { null }
        return (n ?: "${Build.MANUFACTURER} ${Build.MODEL}").trim()
    }

    /** This phone's IPv4 address on Wi-Fi (or whatever network is active), for troubleshooting. */
    fun phoneIp(): String? = try {
        val cm = context.getSystemService(ConnectivityManager::class.java)
        val nets = listOfNotNull(cm.activeNetwork) + cm.allNetworks.toList()
        nets.asSequence()
            .filter { cm.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true }
            .plus(nets.asSequence())
            .mapNotNull { cm.getLinkProperties(it) }
            .flatMap { it.linkAddresses.asSequence() }
            .map { it.address }
            .firstOrNull { it is Inet4Address && !it.isLoopbackAddress }
            ?.hostAddress
    } catch (_: Exception) {
        null
    }

    /** Reads the QR code and saves the pairing; the connection is confirmed with [hello]. */
    fun accept(qr: String): PairedMac {
        val m = PairedMac.fromQr(qr) ?: throw SendError("This QR code isn't a CLIO PDF pairing code. Open File › Pair Android Phone… on the Mac and scan the code shown there.")
        mac = m
        setConfirmed(false)
        return m
    }

    /** Confirms the connection to the paired Mac; returns the address that answered. */
    suspend fun hello(onStep: (String) -> Unit = {}): String {
        val m = mac ?: throw SendError("No Mac is paired yet.")
        val host = request(m, JSONObject().put("type", "hello").put("phone", phoneName()), emptyList(), null, onStep)
        mac = m.copy(lastHost = host)
        setConfirmed(true)
        return host
    }

    /** Sends one or more files in a single encrypted request. Returns the Mac's name. */
    suspend fun send(files: List<Pair<File, String>>, folder: String?, onStep: (String) -> Unit = {}, onProgress: (Float) -> Unit): String {
        val m = mac ?: throw SendError("No Mac is paired yet.")
        val list = JSONArray()
        files.forEach { (f, name) -> list.put(JSONObject().put("name", name).put("size", f.length())) }
        val header = JSONObject().put("type", "files").put("files", list).put("phone", phoneName())
        if (folder != null && files.size > 1) header.put("folder", folder)
        if (files.size == 1) header.put("name", files[0].second)
        val host = request(m, header, files.map { it.first }, onProgress, onStep)
        mac = m.copy(lastHost = host)
        setConfirmed(true)
        return m.name
    }

    private suspend fun request(
        m: PairedMac, header: JSONObject, files: List<File>, onProgress: ((Float) -> Unit)?, onStep: (String) -> Unit,
    ): String = withContext(Dispatchers.IO) {
        onStep("Looking for ${m.name} on this network…")
        val candidates = LinkedHashSet<Pair<String, Int>>()
        discover(m.macId)?.let { candidates += it }
        m.lastHost?.let { candidates += it to m.port }
        m.hosts.forEach { candidates += it to m.port }
        if (candidates.isEmpty()) throw SendError("The QR code didn't contain your Mac's address. Make sure the Mac is connected to Wi-Fi, then pair again.")
        val failures = mutableListOf<String>()
        var kinds = mutableSetOf<String>()
        for ((host, port) in candidates) {
            onStep("Connecting to $host:$port…")
            try {
                post(m, host, port, header, files, onProgress)
                return@withContext host
            } catch (e: SendError) {
                throw e
            } catch (e: Exception) {
                val kind = classify(e)
                kinds += kind
                failures += "$host:$port — $kind"
            }
        }
        val ip = phoneIp()
        val sameNet = ip != null && candidates.any { sameSubnet(it.first, ip) }
        val advice = when {
            ip == null -> "This phone doesn't seem to be on Wi-Fi. Connect it to the same Wi-Fi as the Mac."
            !sameNet -> "This phone ($ip) is on a different network than the Mac (${candidates.first().first}). Connect both to the same Wi-Fi — not a guest network."
            "refused" in kinds -> "The Mac refused the connection: CLIO PDF isn't listening. Open CLIO PDF on the Mac, keep the pairing window open and try again."
            else -> "The Mac didn't answer. Usually the Mac's firewall is blocking CLIO PDF: open System Settings › Network › Firewall › Options and allow incoming connections for CLIO PDF. " +
                "To check the network, open http://${candidates.first().first}:${candidates.first().second} in Chrome on this phone."
        }
        throw SendError("Can't reach ${m.name}.\n\n$advice\n\nTried: ${failures.joinToString("; ")}" + (ip?.let { "\nThis phone: $it" } ?: ""))
    }

    private fun classify(e: Exception): String {
        val m = (e.message ?: "") + " " + (e.cause?.message ?: "")
        return when {
            e is SocketTimeoutException || m.contains("timed out", true) -> "no answer (timed out)"
            e is ConnectException && m.contains("refused", true) -> "refused"
            m.contains("EHOSTUNREACH", true) || m.contains("ENETUNREACH", true) || e is NoRouteToHostException -> "unreachable"
            else -> e.javaClass.simpleName + (e.message?.let { ": $it" } ?: "")
        }
    }

    private fun sameSubnet(a: String, b: String): Boolean {
        val x = a.split("."); val y = b.split(".")
        return x.size == 4 && y.size == 4 && x[0] == y[0] && x[1] == y[1] && x[2] == y[2]
    }

    private fun post(m: PairedMac, host: String, port: Int, header: JSONObject, files: List<File>, onProgress: ((Float) -> Unit)?) {
        val headerBytes = header.toString().toByteArray(Charsets.UTF_8)
        val total = files.sumOf { it.length() }
        val plainLen = 4L + headerBytes.size + total
        val nonce = ByteArray(12).also { SecureRandom().nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(m.key, "AES"), GCMParameterSpec(128, nonce))
        cipher.updateAAD(m.pairing.toByteArray(Charsets.UTF_8))

        val path = if (files.isEmpty()) "hello" else "upload"
        val h = if (host.contains(':')) "[$host]" else host
        val conn = URL("http://$h:$port/v1/$path").openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.connectTimeout = 4000
            conn.readTimeout = 120_000
            conn.doOutput = true
            conn.setFixedLengthStreamingMode(plainLen + 16)
            conn.setRequestProperty("Content-Type", "application/octet-stream")
            conn.setRequestProperty("X-Clio-Pairing", m.pairing)
            conn.setRequestProperty("X-Clio-Nonce", Base64.encodeToString(nonce, Base64.NO_WRAP))
            conn.outputStream.use { os ->
                val start = ByteBuffer.allocate(4).putInt(headerBytes.size).array() + headerBytes
                cipher.update(start)?.let { os.write(it) }
                var sent = 0L
                val buf = ByteArray(64 * 1024)
                for (file in files) {
                    file.inputStream().use { input ->
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            cipher.update(buf, 0, n)?.let { os.write(it) }
                            sent += n
                            onProgress?.invoke(sent.toFloat() / total.coerceAtLeast(1))
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
                401, 403 -> throw SendError("${m.name} doesn't recognise this phone (the pairing was removed or replaced). Pair again: on the Mac open File › Pair Android Phone… and scan the new code.")
                413 -> throw SendError("The file is too large to send.")
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
