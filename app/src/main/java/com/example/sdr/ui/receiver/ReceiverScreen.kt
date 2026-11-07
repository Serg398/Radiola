package com.example.sdr.ui.receiver

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.example.sdr.DemodulationMode
import com.example.sdr.RdsInformation
import com.example.sdr.SpectrumFrame
import com.example.sdr.platform.EncoderFeedback
import com.example.sdr.receiver.BandwidthOptions
import com.example.sdr.receiver.ReceiverConnectionState
import com.example.sdr.receiver.ReceiverGain
import com.example.sdr.ui.receiver.ReceiverPresentation
import com.example.sdr.receiver.ReceiverSource
import com.example.sdr.receiver.ReceiverSquelch
import com.example.sdr.receiver.TuningStep
import com.example.sdr.receiver.WaterfallHistory
import com.example.sdr.receiver.offsetForBandwidth
import com.example.sdr.sources.usb.ReceiverState
import com.example.sdr.ui.components.BandwidthControl
import com.example.sdr.ui.components.ModeControl
import com.example.sdr.ui.components.SourcePlaybackControl
import com.example.sdr.ui.components.WebSdrBandControl
import com.example.sdr.ui.decoders.ModulationInformationPanel
import com.example.sdr.ui.decoders.ServerDecodedInformationPanel
import com.example.sdr.ui.map.WebSdrMap
import com.example.sdr.ui.settings.SettingsPanel
import com.example.sdr.ui.theme.RadiolaColors
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

private val backdrop get() = RadiolaColors.background
private val surface get() = RadiolaColors.surface
private val edge get() = RadiolaColors.border
private val warm get() = RadiolaColors.primary
private val muted get() = RadiolaColors.secondaryText
private val ivory get() = RadiolaColors.text

@Composable
internal fun ReceiverScreen(
    presentation: ReceiverPresentation,
    deviceName: String,
    connected: Boolean,
    permitted: Boolean,
    usbCheckResult: String?,
    streaming: Boolean,
    streamPending: Boolean,
    starting: Boolean = false,
    receiverState: ReceiverState = if (streaming) ReceiverState.Running else ReceiverState.Idle,
    frequencyInput: String,
    spectrumSpanHz: Int,
    bandwidthDefault: Boolean = true,
    receiverGain: ReceiverGain,
    gainSteps: List<Int>,
    spectrumState: State<SpectrumFrame?>,
    waterfallState: State<SpectrumFrame?>,
    waterfallHistory: WaterfallHistory,
    waterfallFrameRate: WaterfallFrameRate,
    tuningStep: TuningStep,
    actualSpectrumFps: Int,
    actualWaterfallFps: Int,
    signalDbfs: Float,
    onRequestPermission: () -> Unit,
    onCheckConnection: () -> Unit,
    onCaptureIq: () -> Unit,
    onStartStream: () -> Unit,
    onStopStream: () -> Unit,
    onFrequencyInput: (String) -> Unit,
    onTune: () -> Unit,
    onReceiverGainChange: (ReceiverGain) -> Unit,
    onTuningStepChange: (TuningStep) -> Unit,
    onWaterfallFrameRateChange: (WaterfallFrameRate) -> Unit,
    screenAlwaysOn: Boolean,
    onScreenAlwaysOnChange: (Boolean) -> Unit,
    onPlaybackStart: () -> Unit = {},
    batteryPercent: Int = -1,
    batteryCharging: Boolean = false,
    receiverSquelch: ReceiverSquelch = ReceiverSquelch(),
    squelchOpen: Boolean = true,
    onSquelchChange: (ReceiverSquelch) -> Unit = {},
    wfmStereoEnabled: Boolean = false,
    wfmStereoLocked: Boolean = false,
    rdsInformation: RdsInformation = RdsInformation(),
    onWfmStereoChange: (Boolean) -> Unit = {},
    encoderFeedback: EncoderFeedback = EncoderFeedback.LIGHT,
    onEncoderFeedbackChange: (EncoderFeedback) -> Unit = {},
    onEncoderTick: () -> Unit = {}
) {
    var selected by remember { mutableStateOf("Receiver") }
    val context = LocalContext.current
    val online = presentation.online
    val uiState = presentation.state
    val source = uiState.source
    val webSdrPage = online.page
    val openWebRx = source == ReceiverSource.OPEN_WEB_RX
    val webSdrReceiverUrl = if (openWebRx) online.openWebRxReceiver?.url else online.receiverUrl
    val webSdrReceiverName = if (openWebRx) online.openWebRxReceiver?.name.orEmpty() else online.receiverName
    val webSession = online.session
    val mapState = androidx.compose.runtime.saveable.rememberSaveableStateHolder()
    val remote = source == ReceiverSource.WEB_SDR || openWebRx
    val tunedHz = uiState.frequencyHz
    val activeMode = uiState.mode
    val activeBandwidth = uiState.bandwidthHz
    var remoteInput by remember { mutableStateOf("") }
    val activeInput = if (remote) remoteInput else frequencyInput
    val changeInput: (String) -> Unit = { if (remote) remoteInput = it else onFrequencyInput(it) }
    val applyInput: () -> Unit = {
        if (remote) remoteInput.replace(',', '.').toDoubleOrNull()?.takeIf { it.isFinite() && it > 0 }
            ?.let { webSession.tune((it * 1_000_000).toLong()) }
        else onTune()
    }
    val step: (Long) -> Unit = presentation::step
    var ftxMode by presentation::ftxMode
    val changeMode: (DemodulationMode) -> Unit = presentation::changeMode
    val changeBandwidth: (Int?) -> Unit = presentation::changeBandwidth
    var editingFrequency by remember { mutableStateOf(false) }
    LaunchedEffect(tunedHz, editingFrequency) {
        if (!editingFrequency) remoteInput = "%.6f".format(Locale.US, tunedHz / 1_000_000.0)
    }
    var zoom by remember { mutableStateOf(1f) }
    val onlineFrame = remember(webSession) { androidx.compose.runtime.derivedStateOf { webSession.frame } }
    val activeSpectrum = if (remote) onlineFrame else spectrumState
    val activeWaterfall = if (remote) onlineFrame else waterfallState
    val activeHistory = if (remote) webSession.waterfallHistory else waterfallHistory
    val captureSpanHz = if (remote) webSession.bands.getOrNull(webSession.currentBand)?.spanHz
        ?: spectrumSpanHz else spectrumSpanHz
    val maxWaterfallZoom = if (remote) maxOf(1f, captureSpanHz / 10_000f) else 16f
    val effectiveZoom = zoom.coerceIn(1f, maxWaterfallZoom)
    LaunchedEffect(maxWaterfallZoom) { zoom = zoom.coerceIn(1f, maxWaterfallZoom) }
    val visibleSpanHz = (captureSpanHz / effectiveZoom).roundToInt().coerceAtLeast(1)
    LaunchedEffect(webSession, receiverSquelch) { webSession.squelchSetting = receiverSquelch }
    val receiving = uiState.receiving
    val ftxInformation = presentation.ftxInformation
    val cwInformation = presentation.cwInformation
    var displayFrequencyHz by remember { mutableStateOf(tunedHz.toDouble()) }
    var encoderRotating by remember { mutableStateOf(false) }
    var encoderBrakeRequest by remember { mutableStateOf(0) }
    val frequencyMotion = remember { FrequencyMotion(tunedHz.toDouble()) }
    val latestFrequencyHz by rememberUpdatedState(tunedHz)
    val latestSpanHz by rememberUpdatedState(captureSpanHz)
    // One frame loop per rotation: individual tuning steps never restart it.
    LaunchedEffect(encoderRotating) {
        if (!encoderRotating) {
            frequencyMotion.snapTo(latestFrequencyHz.toDouble())
            displayFrequencyHz = latestFrequencyHz.toDouble()
            return@LaunchedEffect
        }
        var previousFrame = System.nanoTime()
        while (encoderRotating) {
            withFrameNanos { frame ->
                val dt = ((frame - previousFrame) / 1_000_000_000.0).coerceIn(0.0, 0.05)
                previousFrame = frame
                val target = latestFrequencyHz.toDouble()
                if (abs(target - frequencyMotion.positionHz) > latestSpanHz * 2.0) frequencyMotion.snapTo(target)
                displayFrequencyHz = frequencyMotion.advance(target, dt)
            }
        }
    }
    // Both sources move the RF image under the same fixed tuning cursor.
    val visibleCenterHz = if (encoderRotating) displayFrequencyHz else tunedHz.toDouble()
    var automaticDisplay by rememberSaveable { mutableStateOf(false) }
    var automaticLevels by remember { mutableStateOf<DisplayLevels?>(null) }
    val autoViewport by rememberUpdatedState(visibleCenterHz to visibleSpanHz)
    LaunchedEffect(automaticDisplay, activeSpectrum) {
        automaticLevels = null
        if (automaticDisplay) {
            val controller = AutomaticDisplayLevels()
            androidx.compose.runtime.snapshotFlow { activeSpectrum.value to autoViewport }.collect { (frame, viewport) ->
                if (frame != null) automaticLevels = controller.update(frame, viewport.first, viewport.second,
                    android.os.SystemClock.elapsedRealtime())
            }
        }
    }
    val remoteBand = webSession.bands.getOrNull(webSession.currentBand)
    val latestViewport by rememberUpdatedState(visibleCenterHz.toLong() to visibleSpanHz)
    LaunchedEffect(remote, webSession.active, remoteBand) {
        if (remote && webSession.active && remoteBand != null) {
            androidx.compose.runtime.snapshotFlow { latestViewport }.collect { (center, span) ->
                webSession.setWaterfallViewport(center, span)
            }
        }
    }
    val channelOffsetHz = 0L
    val passbandOffsetHz = (if (remote) webSession.passbandOffsetHz else activeMode.offsetForBandwidth(activeBandwidth)).toLong()
    Row(Modifier.fillMaxSize().background(backdrop)) {
        Sidebar(selected, onSelect = {
                encoderRotating = false
                frequencyMotion.snapTo(tunedHz.toDouble())
                selected = it
            }, screenAlwaysOn = screenAlwaysOn, onScreenAlwaysOnChange = onScreenAlwaysOnChange)
        Column(
            Modifier.weight(1f).fillMaxHeight().padding(start = 8.dp, end = 8.dp, top = 6.dp, bottom = 4.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Box(Modifier.fillMaxWidth().height(84.dp)) {
                Row(Modifier.fillMaxWidth().height(52.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (remote) {
                    Column(Modifier.width(180.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        val warning = online.startError ?: webSession.error ?: webSession.waterfallWarning
                        Text(when (webSession.state) {
                            ReceiverConnectionState.Connected -> "${source.storedValue.uppercase(Locale.ROOT)}  /  LIVE"
                            ReceiverConnectionState.Connecting -> "${source.storedValue.uppercase(Locale.ROOT)}  /  CONNECTING"
                            ReceiverConnectionState.Error -> "${source.storedValue.uppercase(Locale.ROOT)}  /  CONNECTION ERROR"
                            else -> "${source.storedValue.uppercase(Locale.ROOT)}  /  OFFLINE"
                        }, color = if (webSession.state == ReceiverConnectionState.Connected) warm else muted,
                            fontSize = 8.sp, lineHeight = 10.sp, letterSpacing = 0.8.sp, maxLines = 1)
                        Text(webSdrReceiverName.ifBlank { "Select a server on MAP" }, color = ivory,
                            fontSize = 16.sp, lineHeight = 18.sp, fontWeight = FontWeight.Normal,
                            style = TextStyle(platformStyle = PlatformTextStyle(includeFontPadding = false)),
                            maxLines = if (warning == null) 2 else 1, overflow = TextOverflow.Ellipsis)
                        if (warning != null) Text(warning, color = RadiolaColors.warning, fontSize = 9.sp,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                } else GainPanel(receiverGain, gainSteps, onReceiverGainChange, Modifier.width(180.dp))
                FrequencyControl(tunedHz, activeInput, editingFrequency, changeInput, applyInput,
                    onEditingChange = { editingFrequency = it }, step = {
                        encoderBrakeRequest++
                        encoderRotating = false
                        step(it)
                    }, modifier = Modifier.weight(1f))
                Row(Modifier.height(28.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SourcePlaybackControl(
                        source = source,
                        onSourceChange = {
                            if (it != source) {
                                ftxMode = null
                                webSession.disconnect()
                                if (streaming || starting) onStopStream()
                                encoderBrakeRequest++
                                encoderRotating = false
                                online.selectSource(it)
                                selected = "Receiver"
                            }
                        },
                        connected = if (remote) webSession.state == ReceiverConnectionState.Connected else connected && permitted,
                        action = if (remote) {
                            if (online.pending) "CANCEL" else when (webSession.state) {
                                ReceiverConnectionState.Connecting -> "CANCEL"
                                ReceiverConnectionState.Connected -> "DISCONNECT"
                                else -> "CONNECT"
                            }
                        } else if (streaming || starting) "STOP" else if (streamPending) "STOPPING" else if (!permitted) "ACCESS" else "START",
                        enabled = if (remote) webSdrReceiverUrl != null || webSession.active || online.pending
                            else ((!streamPending && connected) || starting),
                        onAction = {
                        if (remote) {
                            if (webSession.active || online.pending) online.disconnect()
                            else { onPlaybackStart(); onStopStream(); online.connect() }
                        } else {
                        when {
                            streaming || starting -> onStopStream()
                            !permitted -> onRequestPermission()
                            else -> onStartStream()
                        }
                        }
                        },
                    )
                    BatteryIndicator(batteryPercent, batteryCharging, Modifier.height(28.dp))
                }
                }
                BoxWithConstraints(
                    Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(28.dp)
                        .background(surface, RoundedCornerShape(5.dp)),
                ) {
                // Leave the middle free for the server view switch in both source layouts.
                val meterWidth = maxWidth / 2 - 96.dp
                Row(Modifier.fillMaxSize(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    ModeControl(activeMode, ftxMode, remote,
                        !remote || webSession.active && (!openWebRx || webSession.serverModes?.any { it.id == "usb" } == true),
                        serverModes = if (openWebRx) webSession.serverModes else null,
                        serverModeId = webSession.serverModeId,
                        serverModeLabel = webSession.modeLabel,
                        onServerMode = presentation::selectServerMode,
                        onMode = changeMode, onFtx = presentation::selectFtx)
                    BandwidthControl(activeMode, activeBandwidth,
                        if (remote) webSession.bandwidthDefault else bandwidthDefault, changeBandwidth,
                        if (remote) webSession.bandwidthOptions else BandwidthOptions.forMode(activeMode),
                        enabled = !remote || webSession.bandwidthAdjustable)
                    if (remote) WebSdrBandControl(webSession.bands, webSession.currentBand,
                        webSession.active, presentation::selectBand)
                    if (!remote && activeMode == DemodulationMode.WFM) StereoToggle(
                        activeMode == DemodulationMode.WFM, wfmStereoEnabled, wfmStereoLocked,
                        onWfmStereoChange
                    )
                    Spacer(Modifier.weight(1f))
                    com.example.sdr.ui.components.NoiseReductionControl(
                        available = com.example.sdr.audio.HamNoiseSettings.model(activeMode) != 0 && !(openWebRx && webSession.serverDataMode),
                        cw = activeMode == DemodulationMode.CW)
                    Row(Modifier.width(meterWidth).fillMaxHeight(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                    if (remote) {
                        WebSdrSignalMeter(webSession, receiverSquelch, squelchOpen, onSquelchChange,
                            Modifier.weight(1f).fillMaxHeight())
                    } else {
                        SignalMeter(signalDbfs, receiverSquelch, squelchOpen, onSquelchChange, Modifier.weight(1f).fillMaxHeight())
                    }
                    }
                }
                // The plot excludes the encoder (78 dp) and starts after the amplitude axis (31 dp).
                if (remote) Box(Modifier.align(Alignment.Center).offset(x = (31.dp - 78.dp) / 2)) {
                    ServerViewToggle(
                        page = webSdrPage, active = selected == "Receiver",
                        onSelect = { online.selectPage(it); selected = "Receiver" },
                    )
                }
                }
            }
            Box(Modifier.fillMaxWidth().weight(1f)) {
                if (selected == "Settings") {
                    SettingsPanel(
                        gain = receiverGain, gainSteps = gainSteps, onGainChange = onReceiverGainChange,
                        squelch = receiverSquelch, onSquelchChange = onSquelchChange,
                        mode = activeMode, stereo = wfmStereoEnabled, onStereoChange = onWfmStereoChange,
                        frameRate = waterfallFrameRate, onFrameRateChange = onWaterfallFrameRateChange,
                        tuningStep = tuningStep, onStepChange = onTuningStepChange,
                        feedback = encoderFeedback, onFeedbackChange = onEncoderFeedbackChange,
                        zoom = zoom, onResetZoom = { zoom = 1f },
                        deviceName = deviceName, connected = connected, permitted = permitted,
                        streaming = streaming, pending = streamPending,
                        onPermission = onRequestPermission, onUsbTest = onCheckConnection, onIqTest = onCaptureIq,
                        onClose = { selected = "Receiver" }
                    )
                } else if (remote && webSdrPage == "MAP") {
                    mapState.SaveableStateProvider("server-map-$source") {
                    androidx.compose.runtime.key(source) {
                    WebSdrMap(
                        source = source,
                        modifier = Modifier.fillMaxSize(),
                        selectedReceiverUrl = webSdrReceiverUrl,
                        onReceiverSelected = {
                            online.select(it)
                        },
                    )
                    }
                    }
                } else {
                Column(
                    Modifier.fillMaxSize().padding(end = 78.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
            Box(
                Modifier.fillMaxWidth().weight(1f)
                    .clipToBounds()
                    .border(1.dp, edge, RoundedCornerShape(6.dp))
                    .background(RadiolaColors.plot, RoundedCornerShape(6.dp))
            ) {
                Column(Modifier.fillMaxSize().padding(4.dp)) {
                    if (selected != "Waterfall") {
                        BoxWithConstraints(Modifier.fillMaxWidth().weight(1f)) {
                            AmberSpectrum(activeSpectrum, visibleSpanHz, activeBandwidth, channelOffsetHz, passbandOffsetHz.toInt(), visibleCenterHz, Modifier.fillMaxSize(), relative = remote,
                                displayRange = if (automaticDisplay) automaticLevels?.spectrum else null)
                            var markerWidthPx by remember { mutableStateOf(0) }
                            val markerWidth = with(LocalDensity.current) { markerWidthPx.toDp() }
                            val markerCenter = 31.dp + (maxWidth - 31.dp) * (0.5f + channelOffsetHz.toFloat() / visibleSpanHz)
                            val markerOffset = (markerCenter - markerWidth / 2).coerceIn(31.dp, maxOf(31.dp, maxWidth - markerWidth))
                            Box(Modifier.align(Alignment.TopStart).offset(x = markerOffset).onSizeChanged { markerWidthPx = it.width }.padding(top = 4.dp).background(RadiolaColors.primaryTint, RoundedCornerShape(12.dp)).border(1.dp, warm, RoundedCornerShape(12.dp)).padding(horizontal = 8.dp, vertical = 2.dp)) {
                                Text(formatFrequency(tunedHz), color = ivory, fontSize = 11.sp)
                            }
                            Text(if (remote) (if (automaticDisplay) "dB" else "REL") else "dBFS", Modifier.align(Alignment.TopStart).padding(start = 5.dp, top = 3.dp), color = muted, fontSize = 11.sp)
                            Row(Modifier.align(Alignment.TopEnd).padding(end = 7.dp, top = 2.dp),
                                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("AUTO", color = if (automaticDisplay) warm else muted, fontSize = 10.sp,
                                modifier = Modifier.clickable { automaticDisplay = !automaticDisplay }
                                    .background(if (automaticDisplay) RadiolaColors.primaryTint else Color.Transparent, RoundedCornerShape(4.dp))
                                    .border(.5.dp, if (automaticDisplay) warm else edge, RoundedCornerShape(4.dp))
                                    .padding(horizontal = 6.dp, vertical = 2.dp))
                            Text(
                                "SPAN %d kHz".format(Locale.US, visibleSpanHz / 1_000),
                                color = muted, fontSize = 11.sp
                            )
                            }
                        }
                    }
                    if (selected != "Spectrum") {
                        Box(Modifier.fillMaxWidth().weight(1f).then(waterfallNavigation(
                            visibleSpanHz = visibleSpanHz,
                            zoom = effectiveZoom,
                            maxZoom = maxWaterfallZoom,
                            onStep = step,
                            onZoom = { zoom = it },
                            onContact = {
                                encoderBrakeRequest++
                                encoderRotating = false
                                frequencyMotion.snapTo(latestFrequencyHz.toDouble())
                                displayFrequencyHz = latestFrequencyHz.toDouble()
                            },
                        ))) {
                            androidx.compose.runtime.key(activeHistory) {
                            AndroidView(
                                factory = { WaterfallView(it, activeHistory, minimumRenderWidth = if (remote) activeHistory.binCount else 1024) },
                                update = { view ->
                                    view.setDisplayRange(if (automaticDisplay) automaticLevels?.waterfall else null)
                                    view.setTuning(visibleCenterHz.toLong(), visibleSpanHz)
                                    activeWaterfall.value // Subscribe to the independent waterfall snapshot.
                                    view.refresh()
                                },
                                modifier = Modifier.fillMaxSize().padding(start = 31.dp)
                            )
                            }
                            Canvas(Modifier.fillMaxSize().padding(start = 31.dp).clipToBounds()) {
                                val center = size.width * (0.5f + channelOffsetHz.toFloat() / visibleSpanHz)
                                val channelWidth = size.width * (activeBandwidth.toFloat() / visibleSpanHz)
                                val passbandCenter = size.width * (0.5f + (channelOffsetHz + passbandOffsetHz).toFloat() / visibleSpanHz)
                                val channelLeft = passbandCenter - channelWidth / 2f
                                val channelRight = passbandCenter + channelWidth / 2f
                                drawRect(
                                    Brush.horizontalGradient(
                                        listOf(RadiolaColors.adaptive(Color(0x14205BCB), Color(0x14E8B36A)), RadiolaColors.adaptive(Color(0x33205BCB), Color(0x33E8B36A)), RadiolaColors.adaptive(Color(0x14205BCB), Color(0x14E8B36A))),
                                        startX = channelLeft, endX = channelRight
                                    ),
                                    Offset(channelLeft, 0f), Size(channelWidth, size.height)
                                )
                                drawLine(RadiolaColors.adaptive(Color(0x80205BCB), Color(0x80E8B36A)), Offset(channelLeft, 0f), Offset(channelLeft, size.height), 1.dp.toPx())
                                drawLine(RadiolaColors.adaptive(Color(0x80205BCB), Color(0x80E8B36A)), Offset(channelRight, 0f), Offset(channelRight, size.height), 1.dp.toPx())
                                drawLine(RadiolaColors.adaptive(Color(0xC9205BCB), Color(0xC9E8B36A)), Offset(center, 0f), Offset(center, size.height), 1.dp.toPx())
                            }
                            Text(
                                "${if (!receiving) 0 else if (remote) webSession.visualFps else actualWaterfallFps} FPS",
                                modifier = Modifier.align(Alignment.BottomEnd).padding(6.dp)
                                    .background(Color(0xA6111D30), RoundedCornerShape(4.dp))
                                    .border(.5.dp, Color.White.copy(alpha = .15f), RoundedCornerShape(4.dp))
                                    .padding(horizontal = 6.dp, vertical = 3.dp),
                                color = Color.White.copy(alpha = .9f), fontSize = 9.sp, lineHeight = 11.sp,
                                style = TextStyle(platformStyle = PlatformTextStyle(includeFontPadding = false)),
                            )
                        }
                    }
                    Row(
                        Modifier.fillMaxWidth().height(18.dp).padding(start = 31.dp, end = 4.dp),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        for (fraction in listOf(-0.5, -0.25, 0.0, 0.25, 0.5)) {
                            Text("%.3f".format(Locale.US, (visibleCenterHz + visibleSpanHz * fraction) / 1_000_000.0), color = muted, fontSize = 11.sp)
                        }
                    }
                }

            }
            if (openWebRx && webSession.serverDataMode) ServerDecodedInformationPanel(
                webSession.modeLabel, webSession.decodedText, webSession::clearDecodedText,
                sstv = webSession.serverModeId == "sstv", image = webSession.sstvImage,
                adsb = webSession.serverModeId == "adsb", aircraft = webSession.aircraftReports)
            else if (activeMode == DemodulationMode.CW || ftxMode != null) ModulationInformationPanel(
                activeMode, rdsInformation, tunedHz, receiving,
                wfmStereoEnabled && wfmStereoLocked, signalDbfs,
                cwInformation, presentation::clearCw,
                presentation.cwEnabled,
                presentation::enableCw,
                cwBackend = presentation.cwBackend, onCwBackendChange = presentation::selectCwBackend,
                ftxMode = ftxMode, ftx = ftxInformation, onFtxClear = presentation::clearFtx,
                source = source, serverUrl = webSdrReceiverUrl,
            ) {}
                }
                VerticalEncoder(
                    brakeRequest = encoderBrakeRequest,
                    onStep = step,
                    tuningStep = tuningStep,
                    onTuningStepChange = onTuningStepChange,
                    onTick = onEncoderTick,
                    onRotate = {
                        if (!encoderRotating) {
                            frequencyMotion.snapTo(tunedHz.toDouble())
                            displayFrequencyHz = tunedHz.toDouble()
                        }
                        encoderRotating = true
                    },
                    onBrake = {
                        encoderRotating = false
                        frequencyMotion.snapTo(tunedHz.toDouble())
                        displayFrequencyHz = tunedHz.toDouble()
                    },
                    modifier = Modifier.align(Alignment.CenterEnd).width(74.dp).fillMaxHeight()
                )
                }
            }
        }
    }
}
