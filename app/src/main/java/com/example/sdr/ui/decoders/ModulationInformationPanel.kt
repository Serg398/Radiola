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
    Box(Modifier.fillMaxWidth().height(160.dp)) {
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
    val metadata = buildList {
        if (rds.pi != null && rds.programmeType.isNotBlank()) add(rds.programmeType)
        if (rds.trafficAnnouncement) add("TA")
        else if (rds.trafficProgramme) add("TP")
    }.joinToString("  /  ")
    DecoderCardFrame(sidebar = {
        DecoderSidebar("WFM") {
            Text(if (streaming) if (stereo) "STEREO" else "MONO" else "",
                color = RadiolaColors.secondaryText)
            Text(if (streaming) "%.0f dBFS".format(Locale.US, signalDbfs) else "",
                color = RadiolaColors.secondaryText)
        }
    }) {
        DecoderToolbar {
            Text(rds.station.uppercase(java.util.Locale.ROOT), Modifier.weight(1f).alignByBaseline(), color = RadiolaColors.secondaryText,
                style = DecoderToolbarText, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        DecoderDivider()
        if (metadata.isNotBlank()) Text(metadata, color = RadiolaColors.secondaryText, fontSize = 10.sp,
            maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(rds.text, Modifier.fillMaxWidth().weight(1f).padding(horizontal = 6.dp),
            color = RadiolaColors.text, fontSize = 13.sp, lineHeight = 20.sp,
            style = TextStyle(platformStyle = PlatformTextStyle(includeFontPadding = false)),
            overflow = TextOverflow.Ellipsis)
    }
}
