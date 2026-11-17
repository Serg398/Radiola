package com.example.sdr.ui.decoders

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.sdr.DemodulationMode
import com.example.sdr.RdsInformation
import com.example.sdr.decoders.cw.CwInformation
import com.example.sdr.decoders.ftx.FtxInformation
import com.example.sdr.decoders.ftx.FtxMode
import com.example.sdr.receiver.ReceiverSource
import com.example.sdr.ui.theme.RadiolaColors
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
    cwBackend: com.example.sdr.decoders.cw.CwBackend = com.example.sdr.decoders.cw.CwBackend.CLASSIC,
    onCwBackendChange: (com.example.sdr.decoders.cw.CwBackend) -> Unit = {},
    ftxMode: FtxMode? = null,
    ftx: FtxInformation = FtxInformation(),
    onFtxClear: () -> Unit = {},
    source: ReceiverSource = ReceiverSource.USB,
    serverUrl: String? = null,
    otherModes: @Composable () -> Unit,
) {
    Box(Modifier.fillMaxWidth().height(if (ftxMode != null || mode == DemodulationMode.CW) 160.dp else 136.dp)) {
    if (ftxMode != null) FtxInformationRoute(ftxMode, ftx, onFtxClear, source, serverUrl)
    else when (mode) {
        DemodulationMode.WFM -> WfmInformationPanel(information, frequencyHz, streaming, stereo, signalDbfs)
        DemodulationMode.CW -> CwInformationPanel(cw, onCwClear, cwDecodeEnabled, onCwDecodeChange, cwBackend, onCwBackendChange)
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
    val warm = RadiolaColors.primary
    val muted = RadiolaColors.secondaryText
    val ivory = RadiolaColors.text
    val textColor = RadiolaColors.text
    val textStyle = TextStyle(platformStyle = PlatformTextStyle(includeFontPadding = false))
    val shape = RoundedCornerShape(6.dp)
    val metadata = buildList {
        if (rds.pi != null && rds.programmeType.isNotBlank()) add(rds.programmeType)
        if (rds.trafficAnnouncement) add("TA")
        else if (rds.trafficProgramme) add("TP")
    }.joinToString("  /  ")
    Box(
        Modifier.fillMaxSize()
            .border(1.dp, RadiolaColors.border, shape).background(RadiolaColors.surface, shape)
            .padding(horizontal = 10.dp, vertical = 8.dp),
    ) {
        Box(Modifier.offset(x = 92.dp).width(1.dp).fillMaxHeight()
            .padding(vertical = 4.dp).background(RadiolaColors.border))
        // Shared rows align the first text baseline even with different font sizes.
        // Their fixed heights keep new text and line wrapping from moving other rows.
        Column(Modifier.fillMaxSize()) {
            Row(Modifier.fillMaxWidth().height(28.dp)) {
                Text("WFM", Modifier.width(80.dp).alignByBaseline(),
                    color = warm, fontSize = 18.sp, lineHeight = 24.sp,
                    fontWeight = FontWeight.Medium, letterSpacing = .6.sp, style = textStyle)
                Spacer(Modifier.width(25.dp))
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
                Spacer(Modifier.width(25.dp))
                Text(metadata, Modifier.weight(1f).alignByBaseline(),
                    color = muted, fontSize = 11.sp, lineHeight = 14.sp, style = textStyle,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Row(Modifier.fillMaxWidth().weight(1f)) {
                Text(if (streaming) "%.0f dBFS".format(Locale.US, signalDbfs) else "",
                    Modifier.width(80.dp).alignByBaseline(),
                    color = muted, fontSize = 10.sp, lineHeight = 14.sp, style = textStyle)
                Spacer(Modifier.width(25.dp))
                Text(rds.text, Modifier.weight(1f).alignByBaseline(),
                    color = textColor, fontSize = 14.sp, lineHeight = 17.sp, style = textStyle,
                    maxLines = 4, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}
