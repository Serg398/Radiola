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
)

internal val gainPlugin = PluginManifest(
    "radiola.gain", "JS Gain", "1.0", 1,
    PluginPort.PCM, PluginPort.PCM, PluginCardSize.COMPACT,
)
