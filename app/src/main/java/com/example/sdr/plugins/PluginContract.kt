package com.example.sdr.plugins

internal enum class PluginPort { PCM, CHANNEL_IQ, SPECTRUM, CARD }
internal enum class PluginCardSize { COMPACT, EXPANDED, FULL }

internal data class PluginManifest(
    val id: String,
    val name: String,
    val version: String,
    val apiVersion: Int,
    val input: PluginPort,
    val output: PluginPort,
    val initialSize: PluginCardSize,
    val uiDirectory: String,
)

internal val gainPlugin = PluginManifest(
    "radiola.gain", "JS Gain", "1.0", 1,
    PluginPort.PCM, PluginPort.PCM, PluginCardSize.COMPACT, "gain",
)

internal val audioSpectrumPlugin = PluginManifest(
    "radiola.audio-spectrum", "Audio Spectrum", "1.0", 1,
    PluginPort.PCM, PluginPort.CARD, PluginCardSize.EXPANDED, "audio-spectrum",
)

internal val bundledPlugins = listOf(gainPlugin, audioSpectrumPlugin)

internal fun canRunAudioPlugin(plugin: PluginManifest?, online: Boolean, receiving: Boolean, dataOnly: Boolean): Boolean =
    receiving && !dataOnly && (online || plugin == audioSpectrumPlugin)
