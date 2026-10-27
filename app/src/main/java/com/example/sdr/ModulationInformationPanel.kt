package com.example.sdr

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.Locale

/** Each modulation owns its information layout; new decoders can add their own panels here. */
@Composable
internal fun ModulationInformationPanel(
    mode: DemodulationMode,
    information: RdsInformation,
    frequencyHz: Long,
    streaming: Boolean,
    stereo: Boolean,
    signalDbfs: Float,
    cw: CwInformation = CwInformation(),
    onCwClear: () -> Unit = {},
    cwDecodeEnabled: Boolean = false,
    onCwDecodeChange: (Boolean) -> Unit = {},
    otherModes: @Composable () -> Unit,
) {
    Box(Modifier.fillMaxWidth().height(106.dp)) {
    when (mode) {
        DemodulationMode.WFM -> WfmInformationPanel(information, frequencyHz, streaming, stereo, signalDbfs)
        DemodulationMode.CW -> CwInformationPanel(cw, onCwClear, cwDecodeEnabled, onCwDecodeChange)
        else -> otherModes()
    }
    }
}

@Composable
private fun WfmInformationPanel(
    information: RdsInformation,
    frequencyHz: Long,
    streaming: Boolean,
    stereo: Boolean,
    signalDbfs: Float,
) {
    val rds = information.takeIf { streaming && it.frequencyHz == frequencyHz } ?: RdsInformation()
    val warm = Color(0xFFF7BA6D)
    val muted = Color(0xFF928B80)
    val ivory = Color(0xFFF4E8D7)
    val textColor = Color(0xFFD5CABB)
    val textStyle = TextStyle(platformStyle = PlatformTextStyle(includeFontPadding = false))
    val shape = RoundedCornerShape(6.dp)
    val metadata = buildList {
        if (rds.pi != null && rds.programmeType.isNotBlank()) add(rds.programmeType)
        if (rds.trafficAnnouncement) add("TA")
        else if (rds.trafficProgramme) add("TP")
    }.joinToString("  /  ")
    Box(
        Modifier.fillMaxSize()
            .border(1.dp, Color(0xFF30302E), shape).background(Color(0xFF151719), shape)
            .padding(12.dp),
    ) {
        Box(Modifier.offset(x = 96.dp).width(1.dp).fillMaxHeight()
            .padding(vertical = 4.dp).background(Color(0xFF292B2B)))
        // Shared rows align the first text baseline even with different font sizes.
        // Their fixed heights keep new text and line wrapping from moving other rows.
        Column(Modifier.fillMaxSize()) {
            Row(Modifier.fillMaxWidth().height(28.dp)) {
                Text("WFM", Modifier.width(80.dp).alignByBaseline(),
                    color = warm, fontSize = 18.sp, lineHeight = 24.sp,
                    fontWeight = FontWeight.Medium, letterSpacing = .6.sp, style = textStyle)
                Spacer(Modifier.width(33.dp))
                Text(rds.station, Modifier.weight(1f).alignByBaseline(),
                    color = ivory, fontSize = 20.sp, lineHeight = 24.sp,
                    fontWeight = FontWeight.Medium, style = textStyle,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Row(Modifier.fillMaxWidth().height(20.dp)) {
                Text(if (streaming) if (stereo) "STEREO" else "MONO" else "",
                    Modifier.width(80.dp).alignByBaseline(),
                    color = textColor, fontSize = 10.sp, lineHeight = 14.sp,
                    letterSpacing = .6.sp, style = textStyle)
                Spacer(Modifier.width(33.dp))
                Text(metadata, Modifier.weight(1f).alignByBaseline(),
                    color = muted, fontSize = 11.sp, lineHeight = 14.sp, style = textStyle,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Row(Modifier.fillMaxWidth().height(34.dp)) {
                Text(if (streaming) "%.0f dBFS".format(Locale.US, signalDbfs) else "",
                    Modifier.width(80.dp).alignByBaseline(),
                    color = muted, fontSize = 10.sp, lineHeight = 14.sp, style = textStyle)
                Spacer(Modifier.width(33.dp))
                Text(rds.text, Modifier.weight(1f).alignByBaseline(),
                    color = textColor, fontSize = 14.sp, lineHeight = 17.sp, style = textStyle,
                    maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}
