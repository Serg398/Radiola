package com.example.sdr.plugins

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.sdr.ui.components.CompactMenuEntry
import com.example.sdr.ui.components.ToolbarCaptionStyle
import com.example.sdr.ui.components.ToolbarMenuOffset
import com.example.sdr.ui.components.ToolbarValue
import com.example.sdr.ui.components.rememberCompactMenuAnchor
import com.example.sdr.ui.components.toolbarButtonSurface
import com.example.sdr.ui.decoders.DecoderCardFrame
import com.example.sdr.ui.decoders.DecoderDivider
import com.example.sdr.ui.decoders.DecoderToolbar
import com.example.sdr.ui.decoders.DecoderToolbarText
import com.example.sdr.ui.theme.RadiolaColors
import kotlinx.coroutines.delay
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.viewinterop.AndroidView
import org.json.JSONArray
import org.json.JSONObject

internal enum class PluginLayout(val label: String, val spectrum: Boolean, val waterfall: Boolean, val size: PluginCardSize) {
    BOTH("Both", true, true, PluginCardSize.COMPACT),
    TALL("Tall card", true, true, PluginCardSize.EXPANDED),
    SPECTRUM("Spectrum", true, false, PluginCardSize.EXPANDED),
    WATERFALL("Waterfall", false, true, PluginCardSize.EXPANDED),
    CARD("Card only", false, false, PluginCardSize.FULL);

    companion object {
        fun forInitialSize(size: PluginCardSize?): PluginLayout = when (size) {
            PluginCardSize.EXPANDED -> TALL
            PluginCardSize.FULL -> CARD
            PluginCardSize.COMPACT, null -> BOTH
        }
    }
}

@Composable
internal fun PluginPicker(manager: PluginManager) {
    LaunchedEffect(manager) { manager.loadInstalled() }
    var open by remember { mutableStateOf(false) }
    val anchor = rememberCompactMenuAnchor()
    Box(anchor.modifier) {
        Column(
            Modifier.height(28.dp).widthIn(min = 64.dp, max = 120.dp)
                .toolbarButtonSurface(menuOpen = open)
                .clickable { open = true }
                .padding(horizontal = 10.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text("PLUGINS", Modifier.height(10.dp), color = RadiolaColors.secondaryText,
                fontSize = 8.sp, style = ToolbarCaptionStyle)
            ToolbarValue(manager.selected?.name ?: "Off")
        }
        DropdownMenu(offset = ToolbarMenuOffset, expanded = open, onDismissRequest = { open = false },
            modifier = Modifier.width(140.dp).heightIn(max = anchor.maxHeight),
            containerColor = RadiolaColors.surface) {
            for (plugin in listOf(null) + manager.installed) {
                CompactMenuEntry(plugin?.name ?: "Off", selected = plugin == manager.selected) {
                    open = false
                    manager.select(plugin)
                }
            }
        }
    }
}

@Composable
internal fun PluginsPage(manager: PluginManager) {
    var message by remember { mutableStateOf<String?>(null) }
    var importing by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(manager) { manager.loadInstalled() }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            importing = true
            message = null
            try {
                manager.importPlugin(uri)
                message = "Plugin imported"
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                message = "Import failed: ${error.localizedMessage ?: "Invalid plugin package"}"
            } finally {
                importing = false
            }
        }
    }
    Column(Modifier.fillMaxSize().padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("Plugins", Modifier.weight(1f), color = RadiolaColors.text, fontSize = 22.sp)
            TextButton(enabled = !importing, onClick = { picker.launch(arrayOf("*/*")) }) { Text("Import plugin") }
        }
        HorizontalDivider(color = RadiolaColors.border)
        message?.let { Text(it, color = RadiolaColors.secondaryText, fontSize = 12.sp) }
        if (manager.installed.isEmpty()) {
            Text("Import a .radiolaplugin file to get started.", color = RadiolaColors.secondaryText, fontSize = 14.sp)
        }
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            for (plugin in manager.installed) {
                Text(plugin.name, color = RadiolaColors.text, fontSize = 16.sp)
                Text("v${plugin.version} / ${if (plugin.output == PluginPort.PCM) "Online audio" else "Receiver audio"}",
                    color = RadiolaColors.secondaryText, fontSize = 12.sp)
                if (manager.selected == plugin) Text("Selected", color = RadiolaColors.primary, fontSize = 12.sp)
            }
        }
    }
}

@Composable
internal fun PluginCard(
    manager: PluginManager, layout: PluginLayout, onLayout: (PluginLayout) -> Unit,
    playable: Boolean, modifier: Modifier,
) {
    val plugin = manager.selected ?: return
    var processed by remember(plugin) { mutableLongStateOf(0) }
    var fallback by remember(plugin) { mutableLongStateOf(0) }
    LaunchedEffect(plugin, manager.playing) {
        while (manager.playing) {
            processed = manager.processedBlocks.get()
            fallback = manager.fallbackBlocks.get()
            delay(500)
        }
    }
    val spectrum = manager.spectrum
    val plot = remember(spectrum) {
        spectrum?.let { frame ->
            JSONObject().put("sampleRate", frame.sampleRate)
                .put("bins", JSONArray(frame.bins.toList()))
        }
    }
    fun css(color: androidx.compose.ui.graphics.Color): String =
        "#%06x".format(java.util.Locale.ROOT, color.toArgb() and 0xffffff)
    val state = JSONObject()
        .put("name", plugin.name).put("version", plugin.version)
        .put("playing", manager.playing).put("ready", manager.ready).put("available", playable)
        .put("error", manager.error ?: JSONObject.NULL)
        .put("layout", layout.name.lowercase(java.util.Locale.ROOT))
        .put("gain", manager.gain).put("processedBlocks", processed).put("fallbackBlocks", fallback)
        .put("spectrum", plot ?: JSONObject.NULL)
        .put("theme", JSONObject().put("surface", css(RadiolaColors.surface))
            .put("text", css(RadiolaColors.text)).put("muted", css(RadiolaColors.secondaryText))
            .put("border", css(RadiolaColors.border)).put("accent", css(RadiolaColors.primary))
            .put("tint", css(RadiolaColors.primaryTint)).put("grid", css(RadiolaColors.plotGrid)))
    DecoderCardFrame(modifier) {
        DecoderToolbar {
            Text(plugin.name.uppercase(java.util.Locale.ROOT), color = RadiolaColors.secondaryText,
                style = DecoderToolbarText, modifier = Modifier.weight(1f),
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("v${plugin.version}", color = RadiolaColors.secondaryText,
                style = DecoderToolbarText, maxLines = 1)
            Box(
                Modifier.size(width = 22.dp, height = 14.dp)
                    .clickable(role = Role.Button) { manager.select(null) }
                    .semantics { contentDescription = "Close plugin" },
                contentAlignment = Alignment.Center,
            ) {
                Text("×", color = RadiolaColors.secondaryText, style = DecoderToolbarText, fontSize = 13.sp)
            }
        }
        DecoderDivider()
        key(plugin.id) {
            AndroidView(
                factory = { PluginWebView(it, plugin).apply { open() } },
                modifier = Modifier.fillMaxWidth().weight(1f),
                onRelease = PluginWebView::release,
                update = { view ->
                    view.publish(state) { command ->
                        if (manager.selected != plugin) return@publish
                        when (command.optString("type")) {
                            "start" -> if (playable) manager.play()
                            "stop" -> manager.stop()
                            "gain" -> command.optDouble("value").takeIf { it.isFinite() }?.let(manager::updateGain)
                            "layout" -> PluginLayout.entries.firstOrNull {
                                it.name.equals(command.optString("value"), ignoreCase = true)
                            }?.let(onLayout)
                        }
                    }
                },
            )
        }
    }
}
