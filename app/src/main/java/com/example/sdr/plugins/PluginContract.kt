package com.example.sdr.plugins

internal enum class PluginPort { PCM, CHANNEL_IQ, SPECTRUM, CARD }
internal enum class PluginCardSize { COMPACT, EXPANDED, FULL }

internal data class PluginManifest(
    val id: String,
    val name: String,
    val version: String,
    val output: PluginPort,
    val initialSize: PluginCardSize,
    val script: String,
    val webFiles: Map<String, ByteArray>,
)

internal fun canRunAudioPlugin(plugin: PluginManifest?, online: Boolean, receiving: Boolean, dataOnly: Boolean): Boolean =
    plugin != null && receiving && !dataOnly && (online || plugin.output == PluginPort.CARD)
