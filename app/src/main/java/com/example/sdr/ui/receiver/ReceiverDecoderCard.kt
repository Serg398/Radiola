package com.example.sdr.ui.receiver

import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import com.example.sdr.DemodulationMode
import com.example.sdr.RdsInformation
import com.example.sdr.ui.receiver.ReceiverPresentation
import com.example.sdr.receiver.ReceiverSource
import com.example.sdr.ui.decoders.ModulationInformationPanel
import com.example.sdr.ui.decoders.ServerDecodedInformationPanel


@Composable
internal fun ReceiverDecoderCard(
    presentation: ReceiverPresentation,
    openWebRx: Boolean,
    activeMode: DemodulationMode,
    ftxMode: com.example.sdr.decoders.ftx.FtxMode?,
    pluginSelected: Boolean,
    rdsInformation: RdsInformation,
    tunedHz: Long,
    receiving: Boolean,
    wfmStereoEnabled: Boolean,
    wfmStereoLocked: Boolean,
    signalDbfs: State<Float>,
    source: ReceiverSource,
    webSdrReceiverUrl: String?,
    onFaxExpand: () -> Unit,
) {
    val webSession = presentation.online.session
    val cardKind = when {
        presentation.faxSelected -> "fax"
        openWebRx && webSession.serverDataMode -> "server"
        activeMode == DemodulationMode.CW || ftxMode != null -> "local"
        else -> null
    }
    // Keep the outgoing card composed until its exit animation has finished.
    var lastCard by remember { mutableStateOf(Triple(cardKind, activeMode, ftxMode)) }
    val card = if (cardKind != null) Triple(cardKind, activeMode, ftxMode) else lastCard
    androidx.compose.runtime.SideEffect { if (cardKind != null) lastCard = card }
    androidx.compose.animation.AnimatedVisibility(
        visible = cardKind != null && !pluginSelected,
        enter = androidx.compose.animation.expandVertically(
            animationSpec = androidx.compose.animation.core.tween(220), expandFrom = Alignment.Bottom) +
            androidx.compose.animation.fadeIn(androidx.compose.animation.core.tween(180)),
        exit = androidx.compose.animation.shrinkVertically(
            animationSpec = androidx.compose.animation.core.tween(180), shrinkTowards = Alignment.Bottom) +
            androidx.compose.animation.fadeOut(androidx.compose.animation.core.tween(120)),
    ) {
    if (card.first == "fax") com.example.sdr.ui.decoders.FaxInformationPanel(
        presentation.faxInformation, presentation.faxDecoder, onExpand = onFaxExpand)
    else if (card.first == "server") ServerDecodedInformationPanel(
        webSession.modeLabel, webSession.decodedText, webSession::clearDecodedText,
        sstv = webSession.serverModeId == "sstv", image = webSession.sstvImage,
        adsb = webSession.serverModeId == "adsb", aircraft = webSession.aircraftReports)
    else ModulationInformationPanel(
        card.second, rdsInformation, tunedHz, receiving,
        wfmStereoEnabled && wfmStereoLocked, signalDbfs.value,
        presentation.cwInformation, presentation::clearCw,
        presentation.cwEnabled,
        presentation::enableCw,
        cwBackend = presentation.cwBackend, onCwBackendChange = presentation::selectCwBackend,
        ftxMode = card.third, ftx = presentation.ftxInformation, onFtxClear = presentation::clearFtx,
        source = source, serverUrl = webSdrReceiverUrl,
    ) {}
    }
}
