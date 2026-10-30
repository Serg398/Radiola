package com.example.sdr.sources.openwebrx

import com.example.sdr.sources.websdr.WebSdrDirectorySnapshot
import com.example.sdr.sources.websdr.WebSdrReceiver
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import org.json.JSONArray
import org.json.JSONTokener

/** Receiverbook embeds map data as JSON. Keep the snapshot in memory only. */
internal object OpenWebRxDirectory {
    @Volatile var snapshot: WebSdrDirectorySnapshot? = null
        private set

    fun fetch(): WebSdrDirectorySnapshot {
        val connection = URL("https://www.receiverbook.de/map?type=openwebrx").openConnection() as HttpURLConnection
        val text = try {
            connection.connectTimeout = 10_000
            connection.readTimeout = 15_000
            check(connection.responseCode == 200) { "Directory HTTP ${connection.responseCode}" }
            connection.inputStream.bufferedReader(Charsets.UTF_8).use { reader ->
                val result = StringBuilder()
                val buffer = CharArray(8192)
                while (true) {
                    val count = reader.read(buffer)
                    if (count < 0) break
                    check(result.length + count <= 4_000_000) { "Directory too large" }
                    result.append(buffer, 0, count)
                }
                result.toString()
            }
        } finally { connection.disconnect() }
        return WebSdrDirectorySnapshot(parse(text), System.currentTimeMillis()).also { snapshot = it }
    }

    fun parse(html: String): List<WebSdrReceiver> {
        val declaration = Regex("\\bvar\\s+receivers\\s*=\\s*").find(html)
            ?: error("Missing receiver directory")
        // Parse JSON only, never execute the page's JavaScript.
        val sites = JSONTokener(html.substring(declaration.range.last + 1)).nextValue() as? JSONArray
            ?: error("Invalid receiver directory")
        val result = buildList {
            for (i in 0 until sites.length()) {
                val site = sites.optJSONObject(i) ?: continue
                val coordinates = site.optJSONObject("location")?.optJSONArray("coordinates") ?: continue
                val lon = coordinates.optDouble(0, Double.NaN)
                val lat = coordinates.optDouble(1, Double.NaN)
                if (!lon.isFinite() || !lat.isFinite() || lon !in -180.0..180.0 || lat !in -90.0..90.0) continue
                val receivers = site.optJSONArray("receivers") ?: continue
                for (r in 0 until receivers.length()) {
                    val receiver = receivers.optJSONObject(r) ?: continue
                    if (!receiver.optString("type").equals("OpenWebRX", ignoreCase = true)) continue
                    val url = receiver.optString("url").ifBlank { site.optString("url") }
                    val uri = runCatching { URI(url) }.getOrNull() ?: continue
                    if (uri.scheme !in listOf("https", "http") || uri.host.isNullOrBlank()) continue
                    add(WebSdrReceiver(url, receiver.optString("label").ifBlank { site.optString("label") },
                        site.optString("label"), lon.toFloat(), lat.toFloat(), null, emptyList()))
                }
            }
        }.distinctBy { it.url }
        require(result.isNotEmpty()) { "Empty receiver directory" }
        return result
    }
}
