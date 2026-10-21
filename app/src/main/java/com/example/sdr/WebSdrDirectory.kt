package com.example.sdr

import android.content.Context
import android.util.AtomicFile
import org.json.JSONArray
import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL

internal data class WebSdrBand(val lowMHz: Double, val highMHz: Double, val antenna: String)
internal data class WebSdrReceiver(
    val url: String, val name: String, val location: String,
    val lon: Float, val lat: Float, val users: Int?, val bands: List<WebSdrBand>,
)
internal data class WebSdrDirectorySnapshot(val receivers: List<WebSdrReceiver>, val updatedAt: Long)

/** Network/cache operations run on Dispatchers.IO. Only validated responses replace the cache. */
internal class WebSdrDirectory(context: Context) {
    private val cache = AtomicFile(File(context.filesDir, "websdr-directory.json"))

    fun cached(): WebSdrDirectorySnapshot? {
        memory?.takeIf { it.first == cache.baseFile.absolutePath }?.let { return it.second }
        return runCatching {
        WebSdrDirectorySnapshot(parse(cache.openRead().bufferedReader().use { it.readText() }),
            cache.baseFile.lastModified())
        }.getOrNull()?.also { memory = cache.baseFile.absolutePath to it }
    }

    fun fetch(): WebSdrDirectorySnapshot {
        val connection = URL("http://websdr.ewi.utwente.nl/~~websdrlistk?v=1&fmt=2&chseq=0")
            .openConnection() as HttpURLConnection
        val text = try {
            connection.connectTimeout = 10_000
            connection.readTimeout = 10_000
            connection.instanceFollowRedirects = false
            check(connection.responseCode == 200) { "Directory HTTP ${connection.responseCode}" }
            connection.inputStream.bufferedReader(Charsets.UTF_8).use { reader ->
                val result = StringBuilder()
                val buffer = CharArray(8192)
                while (true) {
                    val count = reader.read(buffer)
                    if (count < 0) break
                    check(result.length + count <= 2_000_000) { "Directory too large" }
                    result.append(buffer, 0, count)
                }
                result.toString()
            }
        } finally { connection.disconnect() }
        val receivers = parse(text)
        // Cache failures must not discard a successful directory download.
        runCatching {
            val output = cache.startWrite()
            try {
                output.write(text.toByteArray(Charsets.UTF_8))
                cache.finishWrite(output)
            } catch (error: Exception) {
                cache.failWrite(output)
                throw error
            }
        }
        return WebSdrDirectorySnapshot(receivers, System.currentTimeMillis()).also {
            memory = cache.baseFile.absolutePath to it
        }
    }

    companion object {
        @Volatile private var memory: Pair<String, WebSdrDirectorySnapshot>? = null
        fun parse(text: String): List<WebSdrReceiver> {
            // The endpoint prefixes its JSON array with a // comment.
            val start = text.indexOf('[')
            require(start >= 0) { "Missing directory array" }
            val array = JSONArray(text.substring(start))
            val receivers = buildList {
                for (i in 0 until array.length()) {
                    val entry = array.optJSONObject(i) ?: continue
                    val url = entry.optString("url")
                    val uri = runCatching { URI(url) }.getOrNull() ?: continue
                    if (uri.scheme !in listOf("http", "https") || uri.host.isNullOrBlank()) continue
                    val lon = entry.optDouble("lon", Double.NaN)
                    val lat = entry.optDouble("lat", Double.NaN)
                    if (!lon.isFinite() || !lat.isFinite() || lon !in -180.0..180.0 || lat !in -90.0..90.0) continue
                    val bands = entry.optJSONArray("bands") ?: JSONArray()
                    add(WebSdrReceiver(url, entry.optString("desc").ifBlank { uri.host }, entry.optString("qth"),
                        lon.toFloat(), lat.toFloat(), entry.optString("users").toIntOrNull()?.takeIf { it >= 0 },
                        buildList {
                            for (b in 0 until bands.length()) {
                                val band = bands.optJSONObject(b) ?: continue
                                val low = band.optDouble("l", Double.NaN)
                                val high = band.optDouble("h", Double.NaN)
                                if (low.isFinite() && high.isFinite() && low >= 0 && high > low)
                                    add(WebSdrBand(low, high, band.optString("a")))
                            }
                        }))
                }
            }.distinctBy { it.url }
            require(receivers.isNotEmpty()) { "Empty directory" }
            return receivers
        }
    }
}
