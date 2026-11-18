package com.example.sdr.ui.decoders

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import com.example.sdr.decoders.cw.CwBackend
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.sdr.decoders.cw.CwInformation
import com.example.sdr.ui.theme.RadiolaColors

@Composable
internal fun CwInformationPanel(info: CwInformation, clear: () -> Unit,
    decodeEnabled: Boolean, onDecodeChange: (Boolean) -> Unit,
    backend: CwBackend = CwBackend.CLASSIC, onBackendChange: (CwBackend) -> Unit = {}) {
    var menu by remember { mutableStateOf(false) }
    val warm = RadiolaColors.primary
    val muted = RadiolaColors.secondaryText
    DecoderCardFrame(modifier = Modifier.fillMaxSize(), sidebar = {
        DecoderSidebar("CW") {
            Box {
                Text(backend.label + " \u25be", color = RadiolaColors.text,
                    fontSize = 13.sp, lineHeight = 18.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Medium,
                    modifier = Modifier.clickable { menu = true })
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    CwBackend.entries.forEach { value ->
                        DropdownMenuItem(text = { Text(value.label) }, onClick = { menu = false; onBackendChange(value) })
                    }
                }
            }
            if (backend == CwBackend.CLASSIC) Text(if (info.wpm > 0) "${info.wpm} WPM" else "AUTO WPM", color = muted)
            if (backend == CwBackend.CLASSIC) Text(if (info.toneHz > 0) "${info.toneHz} Hz" else "AUTO TONE",
                color = if (info.tonePresent) warm else muted)
        }
    }) {
            DecoderToolbar {
                Text("MORSE", modifier = Modifier.alignByBaseline(), color = muted, style = DecoderToolbarText, letterSpacing = .5.sp)
                Text(info.pending.uppercase(java.util.Locale.ROOT), modifier = Modifier.weight(1f).alignByBaseline(), color = warm,
                    letterSpacing = 1.sp,
                    style = DecoderToolbarText, fontFamily = FontFamily.Monospace,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                DecoderAction("DECODE", checked = decodeEnabled) { onDecodeChange(!decodeEnabled) }
                DecoderAction("CLEAR", onClick = clear)
            }
            DecoderDivider()
            CwTranscriptView(if (info.status.isNotBlank()) info.status else info.text,
                Modifier.weight(1f).fillMaxWidth())

            }
}
