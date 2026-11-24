package com.example.sdr.plugins

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.sdr.ui.decoders.DecoderCardFrame
import com.example.sdr.ui.theme.RadiolaColors
import kotlinx.coroutines.delay

@Composable
internal fun PluginPicker(manager: PluginManager) {
    var open by remember { mutableStateOf(false) }
    Box {
        Text("PLUGINS", Modifier.clickable { open = true }.padding(5.dp),
            color = if (manager.selected != null) RadiolaColors.primary else RadiolaColors.secondaryText,
            fontSize = 10.sp)
        DropdownMenu(open, { open = false }, containerColor = RadiolaColors.surface) {
            for (plugin in listOf(null, gainPlugin)) {
                DropdownMenuItem(
                    text = { Text(plugin?.name ?: "Off", color = RadiolaColors.text) },
                    onClick = { manager.select(plugin); open = false },
                )
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
    manager: PluginManager, size: PluginCardSize, onResize: () -> Unit,
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
    DecoderCardFrame(modifier, sidebar = {
        Column(Modifier.width(124.dp).padding(top = 8.dp)) {
            Text(plugin.name, color = RadiolaColors.primary, fontSize = 17.sp)
            Text("PCM / PCM", color = RadiolaColors.secondaryText, fontSize = 11.sp)
        }
    }) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { if (manager.playing) manager.stop() else manager.play() },
                enabled = playable || manager.playing) { Text(if (manager.playing) "STOP" else "PLAY") }
            Text("Gain ${(manager.gain * 100).toInt()}% / $status",
                color = RadiolaColors.secondaryText, fontSize = 12.sp)
            Spacer(Modifier.weight(1f))
            TextButton(onClick = onResize) { Text(if (size == PluginCardSize.FULL) "COLLAPSE" else "EXPAND") }
            TextButton(onClick = { manager.select(null) }) { Text("CLOSE") }
        }
        Slider(value = manager.gain.toFloat(), onValueChange = { manager.updateGain(it.toDouble()) },
            modifier = Modifier.width(220.dp).height(24.dp), valueRange = .25f..2f)
    }
}
