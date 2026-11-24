package com.example.sdr.plugins

import com.example.sdr.receiver.ReceiverSource

internal enum class PluginPort { PCM, CHANNEL_IQ, SPECTRUM, CARD }
internal enum class PluginCardSize { COMPACT, EXPANDED, FULL }

internal val pluginSources = mapOf(
    "rtl-sdr" to ReceiverSource.USB,
    "websdr" to ReceiverSource.WEB_SDR,
    "openwebrx" to ReceiverSource.OPEN_WEB_RX,
)

internal fun supportedPluginSources(output: PluginPort): Set<ReceiverSource> =
    ReceiverSource.entries.filter { it.online || output == PluginPort.CARD }.toSet()

internal data class PluginManifest(
    val id: String,
    val name: String,
    val version: String,
    val output: PluginPort,
    val initialSize: PluginCardSize,
    val script: String,
    val webFiles: Map<String, ByteArray>,
    val webWorker: Boolean = false,
    val sources: Set<ReceiverSource> = supportedPluginSources(output),
    val input: PluginPort = PluginPort.PCM,
)

internal fun canRunAudioPlugin(plugin: PluginManifest?, source: ReceiverSource, receiving: Boolean, dataOnly: Boolean): Boolean =
    plugin != null && source in plugin.sources && receiving && !dataOnly
