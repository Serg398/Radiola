package com.example.sdr.plugins

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
import com.example.sdr.ui.decoders.DecoderAction
import com.example.sdr.ui.decoders.DecoderDivider
import com.example.sdr.ui.decoders.DecoderToolbar
import com.example.sdr.ui.decoders.DecoderToolbarText
import com.example.sdr.ui.theme.RadiolaColors
import kotlinx.coroutines.delay

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
            for (plugin in listOf(null, gainPlugin)) {
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
    Column(Modifier.fillMaxSize().padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Plugins", color = RadiolaColors.text, fontSize = 22.sp)
        HorizontalDivider(color = RadiolaColors.border)
        Text(gainPlugin.name, color = RadiolaColors.text, fontSize = 16.sp)
        Text("Built-in / v${gainPlugin.version} / Online audio", color = RadiolaColors.secondaryText, fontSize = 12.sp)
        if (manager.selected == gainPlugin) Text("Selected", color = RadiolaColors.primary, fontSize = 12.sp)
    }
}

@Composable
internal fun PluginCard(
    manager: PluginManager, layout: PluginLayout, onLayout: (PluginLayout) -> Unit,
    playable: Boolean, modifier: Modifier,
) {
    val plugin = manager.selected ?: return
    var processed by remember { mutableLongStateOf(0) }
    var fallback by remember { mutableLongStateOf(0) }
    LaunchedEffect(manager.playing) {
        while (manager.playing) {
            processed = manager.processedBlocks.get()
            fallback = manager.fallbackBlocks.get()
            delay(500)
        }
    }
    val status = when {
        manager.error != null -> manager.error
        !playable -> "Requires online audio"
        manager.playing && !manager.ready -> "Starting JS"
        manager.playing && processed == 0L -> "Waiting for processed audio"
        manager.playing -> "JS active: $processed blocks / $fallback bypassed"
        else -> "Bypass"
    }
    DecoderCardFrame(modifier) {
        DecoderToolbar {
            Text(plugin.name.uppercase(java.util.Locale.ROOT), color = RadiolaColors.secondaryText,
                style = DecoderToolbarText, modifier = Modifier.weight(1f),
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("v${plugin.version}", color = RadiolaColors.secondaryText,
                style = DecoderToolbarText, maxLines = 1)
        }
        DecoderDivider()
        DecoderToolbar {
            Text("Gain ${(manager.gain * 100).toInt()}% / $status",
                color = RadiolaColors.secondaryText, style = DecoderToolbarText,
                modifier = Modifier.weight(1f).alignByBaseline(), maxLines = 1,
                overflow = TextOverflow.Ellipsis)
            DecoderAction(if (manager.playing) "STOP" else "PLAY", enabled = playable || manager.playing) {
                if (manager.playing) manager.stop() else manager.play()
            }
            DecoderAction("CLOSE") { manager.select(null) }
        }
        Slider(value = manager.gain.toFloat(), onValueChange = { manager.updateGain(it.toDouble()) },
            modifier = Modifier.width(220.dp).height(24.dp), valueRange = .25f..2f)
        DecoderToolbar {
            PluginLayout.entries.forEach { option ->
                DecoderAction(option.label, checked = layout == option) { onLayout(option) }
            }
        }
    }
}
