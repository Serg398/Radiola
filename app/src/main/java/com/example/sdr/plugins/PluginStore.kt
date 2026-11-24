package com.example.sdr.plugins

import android.content.Context
import android.net.Uri
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.zip.ZipInputStream
import org.json.JSONObject

internal class PluginStore(private val context: Context) {
    private val directory = File(context.filesDir, "plugins")

    fun list(): List<PluginManifest> = directory.listFiles().orEmpty()
        .filter { it.extension == "radiolaplugin" }
        .mapNotNull { file -> runCatching { file.inputStream().use(::readPackage) }.getOrNull() }
        .sortedBy { it.name }

    fun install(uri: Uri): PluginManifest {
        val archive = context.contentResolver.openInputStream(uri)?.use { it.readBounded(MAX_ARCHIVE) }
            ?: error("Cannot open plugin file")
        val plugin = readPackage(ByteArrayInputStream(archive))
        check(directory.isDirectory || directory.mkdirs()) { "Cannot create plugin directory" }
        val temporary = File.createTempFile("import-", ".tmp", directory)
        try {
            temporary.writeBytes(archive)
            Files.move(temporary.toPath(), File(directory, "${plugin.id}.radiolaplugin").toPath(),
                StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } finally {
            temporary.delete()
        }
        return plugin
    }

    private fun readPackage(stream: InputStream): PluginManifest {
        val files = mutableMapOf<String, ByteArray>()
        ZipInputStream(stream).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                val limit = limits[entry.name]
                require(!entry.isDirectory && limit != null && entry.name !in files) {
                    "Unsupported package entry: ${entry.name.take(80)}"
                }
                files[entry.name] = zip.readBounded(limit)
            }
        }
        fun required(name: String) = requireNotNull(files.remove(name)) { "Missing $name" }
        val json = JSONObject(required("manifest.json").decodeToString())
        val id = json.getString("id")
        require(id.matches(Regex("[a-z0-9][a-z0-9._-]{0,79}"))) { "Invalid plugin ID" }
        require(json.getInt("apiVersion") == 1 && json.getString("input") == "PCM") { "Unsupported plugin API" }
        val name = json.getString("name").trim()
        val version = json.getString("version").trim()
        require(name.length in 1..80 && version.length in 1..24) { "Invalid plugin name or version" }
        val output = PluginPort.valueOf(json.getString("output"))
        require(output == PluginPort.PCM || output == PluginPort.CARD) { "Unsupported audio output" }
        val size = PluginCardSize.valueOf(json.optString("initialSize", "COMPACT"))
        val script = required("process.js").decodeToString()
        require("index.html" in files) { "Missing index.html" }
        return PluginManifest(id, name, version, output, size, script, files.toMap())
    }

    private fun InputStream.readBounded(limit: Int): ByteArray {
        val result = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            val count = read(buffer)
            if (count < 0) return result.toByteArray()
            require(result.size() + count <= limit) { "Plugin file exceeds its size limit" }
            result.write(buffer, 0, count)
        }
    }

    private companion object {
        const val MAX_ARCHIVE = 1024 * 1024
        val limits = mapOf("manifest.json" to 4096, "process.js" to 96 * 1024,
            "index.html" to 128 * 1024, "view.js" to 256 * 1024,
            "common.css" to 128 * 1024, "api.js" to 32 * 1024)
    }
}
