package com.example.sdr

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.material3.DropdownMenu
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalContext
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.Spacer
import androidx.compose.ui.viewinterop.AndroidView
import java.util.Locale
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlin.math.abs
import kotlin.math.roundToInt

private val emptySpectrum = FloatArray(0)
private val backdrop get() = RadiolaColors.background
private val surface get() = RadiolaColors.surface
private val edge get() = RadiolaColors.border
private val warm get() = RadiolaColors.primary
private val muted get() = RadiolaColors.secondaryText
private val ivory get() = RadiolaColors.text

@Composable
fun ReceiverScreen(
    deviceName: String,
    connected: Boolean,
    permitted: Boolean,
    usbCheckResult: String?,
    streaming: Boolean,
    streamPending: Boolean,
    starting: Boolean = false,
    receiverState: ReceiverState = if (streaming) ReceiverState.Running else ReceiverState.Idle,
    frequencyInput: String,
    currentFrequencyHz: Long,
    spectrumSpanHz: Int,
    receiveBandwidthHz: Int,
    bandwidthDefault: Boolean = true,
    onBandwidthChange: (Int?) -> Unit = {},
    demodulationMode: DemodulationMode,
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
    onStep: (Long) -> Unit,
    onDemodulationModeChange: (DemodulationMode) -> Unit,
    onReceiverGainChange: (ReceiverGain) -> Unit,
    onTuningStepChange: (TuningStep) -> Unit,
    onWaterfallFrameRateChange: (WaterfallFrameRate) -> Unit,
    batteryPercent: Int = -1,
    batteryCharging: Boolean = false,
    receiverSquelch: ReceiverSquelch = ReceiverSquelch(),
    squelchOpen: Boolean = true,
    onSquelchChange: (ReceiverSquelch) -> Unit = {},
    wfmStereoEnabled: Boolean = false,
    wfmStereoLocked: Boolean = false,
    rdsInformation: RdsInformation = RdsInformation(),
    cwSnapshot: () -> CwInformation = { CwInformation() },
    onCwClear: () -> Unit = {},
    cwDecodeEnabled: Boolean = false,
    onCwDecodeChange: (Boolean) -> Unit = {},
    onWfmStereoChange: (Boolean) -> Unit = {},
    encoderFeedback: EncoderFeedback = EncoderFeedback.LIGHT,
    onEncoderFeedbackChange: (EncoderFeedback) -> Unit = {},
    onEncoderTick: () -> Unit = {}
) {
    var selected by remember { mutableStateOf("Receiver") }
    val context = LocalContext.current
    val online = remember(context.applicationContext) {
        (context.applicationContext as? RadiolaApplication)?.webSdr ?: WebSdrRuntime(context.applicationContext)
    }
    val source = online.source
    val webSdrPage = online.page
    val webSdrReceiverUrl = online.receiverUrl
    val webSdrReceiverName = online.receiverName
    val webSession = online.session
    val mapState = androidx.compose.runtime.saveable.rememberSaveableStateHolder()
    val remote = source == "WebSDR"
    val tunedHz = if (remote) webSession.frequencyHz else currentFrequencyHz
    val activeMode = if (remote) webSession.mode else demodulationMode
    val activeBandwidth = if (remote) webSession.bandwidthHz else receiveBandwidthHz
    var remoteInput by remember { mutableStateOf("") }
    val activeInput = if (remote) remoteInput else frequencyInput
    val changeInput: (String) -> Unit = { if (remote) remoteInput = it else onFrequencyInput(it) }
    val applyInput: () -> Unit = {
        if (remote) remoteInput.replace(',', '.').toDoubleOrNull()?.takeIf { it.isFinite() && it > 0 }
            ?.let { webSession.tune((it * 1_000_000).toLong()) }
        else onTune()
    }
    val step: (Long) -> Unit = { if (remote) webSession.tune(webSession.frequencyHz + it) else onStep(it) }
    var ftxMode by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf<FtxMode?>(null) }
    val changeMode: (DemodulationMode) -> Unit = { ftxMode = null; if (remote) webSession.requestMode(it) else onDemodulationModeChange(it) }
    val changeBandwidth: (Int?) -> Unit = { if (remote) webSession.setBandwidth(it) else onBandwidthChange(it) }
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
    val visibleSpanHz = (captureSpanHz / zoom).roundToInt().coerceAtLeast(1)
    val receiving = if (remote) webSession.state == WebSdrConnectionState.Connected else streaming
    val usbFtx = (context.applicationContext as? RadiolaApplication)?.receiver?.session?.ftxDecoder
    val activeFtx = if (remote) webSession.ftxDecoder else usbFtx
    var ftxInformation by remember { mutableStateOf(FtxInformation()) }
    LaunchedEffect(activeFtx, ftxMode, tunedHz, receiving, activeMode) {
        usbFtx?.configure(if (!remote && receiving && activeMode == DemodulationMode.USB) ftxMode else null, tunedHz)
        webSession.ftxDecoder.configure(if (remote && receiving && activeMode == DemodulationMode.USB) ftxMode else null, tunedHz)
        ftxInformation = FtxInformation()
        if (ftxMode != null && receiving) while (true) {
            ftxInformation = activeFtx?.snapshot() ?: FtxInformation()
            delay(200)
        }
    }
    var cwInformation by remember { mutableStateOf(CwInformation()) }
    val latestCwSnapshot by rememberUpdatedState(cwSnapshot)
    LaunchedEffect(remote, activeMode, tunedHz, receiving) {
        cwInformation = CwInformation()
        if (activeMode == DemodulationMode.CW && receiving) {
            while (true) {
                cwInformation = if (remote) webSession.cwDecoder.snapshot() else latestCwSnapshot()
                kotlinx.coroutines.delay(100)
            }
        }
    }
    val activeReceiverState = if (remote) when (webSession.state) {
        WebSdrConnectionState.Connected -> ReceiverState.Running
        WebSdrConnectionState.Connecting -> ReceiverState.Starting
        WebSdrConnectionState.Error -> ReceiverState.Error
        WebSdrConnectionState.Idle -> ReceiverState.Idle
    } else receiverState
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
    val passbandOffsetHz = activeMode.offsetForBandwidth(activeBandwidth).toLong()
    val textMeasurer = rememberTextMeasurer()
    Row(Modifier.fillMaxSize().background(backdrop)) {
        Sidebar(selected, onSelect = {
                encoderRotating = false
                frequencyMotion.snapTo(tunedHz.toDouble())
                selected = it
            })
        Column(
            Modifier.weight(1f).fillMaxHeight().padding(start = 8.dp, end = 8.dp, top = 6.dp, bottom = 4.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Box(Modifier.fillMaxWidth().height(84.dp)) {
                Row(Modifier.fillMaxWidth().height(52.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (remote) {
                    Column(Modifier.width(200.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        val warning = online.startError ?: webSession.error ?: webSession.waterfallWarning
                        Text(when (webSession.state) {
                            WebSdrConnectionState.Connected -> "WEBSDR  /  LIVE"
                            WebSdrConnectionState.Connecting -> "WEBSDR  /  CONNECTING"
                            WebSdrConnectionState.Error -> "WEBSDR  /  CONNECTION ERROR"
                            else -> "WEBSDR  /  OFFLINE"
                        }, color = if (webSession.state == WebSdrConnectionState.Connected) warm else muted,
                            fontSize = 8.sp, lineHeight = 10.sp, letterSpacing = 0.8.sp, maxLines = 1)
                        Text(webSdrReceiverName.ifBlank { "Select a server on MAP" }, color = ivory,
                            fontSize = 16.sp, lineHeight = 18.sp, fontWeight = FontWeight.Normal,
                            style = TextStyle(platformStyle = PlatformTextStyle(includeFontPadding = false)),
                            maxLines = if (warning == null) 2 else 1, overflow = TextOverflow.Ellipsis)
                        if (warning != null) Text(warning, color = RadiolaColors.warning, fontSize = 9.sp,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                } else GainPanel(receiverGain, gainSteps, onReceiverGainChange, Modifier.width(200.dp))
                BoxWithConstraints(Modifier.weight(1f)) {
                val frequencyText = formatFrequency(tunedHz)
                val frequencyStyle = TextStyle(fontSize = 40.sp, fontWeight = FontWeight.Light, letterSpacing = 0.5.sp)
                val frequencyWidth = textMeasurer.measure(frequencyText, frequencyStyle, softWrap = false).size.width
                val unitsWidth = textMeasurer.measure("MHz", TextStyle(fontSize = 12.sp)).size.width
                val snapWidth = if (editingFrequency) 0.dp else 32.dp
                val gaps = if (editingFrequency) 6.dp else 12.dp
                val availableWidth = with(LocalDensity.current) { maxWidth.toPx() - snapWidth.toPx() - gaps.toPx() } - unitsWidth
                val frequencyFontSize = (40f * (availableWidth / maxOf(1, frequencyWidth)).coerceIn(0.5f, 1f)).sp
                Row(
                    Modifier.align(Alignment.Center),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    if (editingFrequency) {
                        BasicTextField(
                            value = activeInput,
                            onValueChange = changeInput,
                            modifier = Modifier.weight(1f).alignByBaseline(),
                            singleLine = true,
                            textStyle = TextStyle(color = ivory, fontSize = frequencyFontSize, fontWeight = FontWeight.Light),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                            cursorBrush = SolidColor(warm)
                        )
                        Text("✓", Modifier.clickable { applyInput(); editingFrequency = false }, color = warm, fontSize = 36.sp)
                    } else {
                        Text(
                            frequencyText,
                            Modifier.alignByBaseline().clickable { editingFrequency = true },
                            color = ivory, fontSize = frequencyFontSize, fontWeight = FontWeight.Light,
                            letterSpacing = (frequencyFontSize.value / 80f).sp, maxLines = 1
                        )
                    }
                    Text("MHz", Modifier.alignByBaseline(), color = muted, fontSize = 12.sp, maxLines = 1, softWrap = false)
                    if (!editingFrequency) {
                        val snapDeltaHz = ((tunedHz + 500L) / 1_000L) * 1_000L - tunedHz
                        val canSnap = snapDeltaHz != 0L
                        val shape = RoundedCornerShape(4.dp)
                        Text(
                            "≈1k",
                            Modifier.alignByBaseline().width(snapWidth)
                                .semantics { contentDescription = "Round frequency to nearest kilohertz" }
                                .background(if (canSnap) RadiolaColors.primaryTint else Color.Transparent, shape)
                                .clickable(enabled = canSnap) {
                                    encoderBrakeRequest++
                                    encoderRotating = false
                                    step(snapDeltaHz)
                                }
                                .padding(vertical = 6.dp),
                            color = if (canSnap) warm else RadiolaColors.secondaryText,
                            fontSize = 10.sp, lineHeight = 12.sp,
                            style = TextStyle(platformStyle = PlatformTextStyle(includeFontPadding = false)),
                            textAlign = TextAlign.Center, maxLines = 1, softWrap = false,
                        )
                    }
                }
                }
                Row(Modifier.height(28.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SourcePlaybackControl(
                        source = source,
                        onSourceChange = {
                            if (it != source) {
                                webSession.disconnect()
                                if (streaming || starting) onStopStream()
                                encoderBrakeRequest++
                                encoderRotating = false
                                online.selectSource(it)
                                selected = "Receiver"
                            }
                        },
                        connected = if (remote) webSession.state == WebSdrConnectionState.Connected else connected && permitted,
                        action = if (remote) {
                            if (online.pending) "CANCEL" else when (webSession.state) {
                                WebSdrConnectionState.Connecting -> "CANCEL"
                                WebSdrConnectionState.Connected -> "DISCONNECT"
                                else -> "CONNECT"
                            }
                        } else if (streaming || starting) "STOP" else if (streamPending) "STOPPING" else if (!permitted) "ACCESS" else "START",
                        enabled = if (remote) webSdrReceiverUrl != null || webSession.active || online.pending
                            else ((!streamPending && connected) || starting),
                        onAction = {
                        if (remote) {
                            if (webSession.active || online.pending) online.disconnect()
                            else { onStopStream(); online.connect() }
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
                val meterWidth = maxWidth / 2 - 72.dp
                Row(Modifier.fillMaxSize(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    ModeControl(activeMode, ftxMode, remote, !remote || webSession.active,
                        onMode = changeMode, onFtx = { protocol ->
                            changeMode(DemodulationMode.USB)
                            changeBandwidth(3000)
                            ftxMode = protocol
                        })
                    ToolbarDivider()
                    BandwidthControl(activeMode, activeBandwidth,
                        if (remote) webSession.bandwidthDefault else bandwidthDefault, changeBandwidth,
                        if (remote) WebSdrBandwidth.options(activeMode, webSession.bandwidthLimit) else BandwidthOptions.forMode(activeMode))
                    if (remote) WebSdrBandControl(webSession.bands, webSession.currentBand,
                        webSession.active, webSession::selectBand)
                    if (activeMode == DemodulationMode.WFM) StereoToggle(
                        activeMode == DemodulationMode.WFM, wfmStereoEnabled, wfmStereoLocked,
                        onWfmStereoChange
                    )
                    else if (!remote) Spacer(Modifier.width(60.dp))
                    Spacer(Modifier.weight(1f))
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
                    mapState.SaveableStateProvider("websdr-map") {
                    WebSdrMap(
                        modifier = Modifier.fillMaxSize(),
                        selectedReceiverUrl = webSdrReceiverUrl,
                        onReceiverSelected = {
                            online.select(it)
                        },
                    )
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
                            AmberSpectrum(activeSpectrum, visibleSpanHz, activeBandwidth, channelOffsetHz, activeMode.offsetForBandwidth(activeBandwidth), visibleCenterHz, Modifier.fillMaxSize(), relative = remote)
                            var markerWidthPx by remember { mutableStateOf(0) }
                            val markerWidth = with(LocalDensity.current) { markerWidthPx.toDp() }
                            val markerCenter = 31.dp + (maxWidth - 31.dp) * (0.5f + channelOffsetHz.toFloat() / visibleSpanHz)
                            val markerOffset = (markerCenter - markerWidth / 2).coerceIn(31.dp, maxOf(31.dp, maxWidth - markerWidth))
                            Box(Modifier.align(Alignment.TopStart).offset(x = markerOffset).onSizeChanged { markerWidthPx = it.width }.padding(top = 4.dp).background(RadiolaColors.primaryTint, RoundedCornerShape(12.dp)).border(1.dp, warm, RoundedCornerShape(12.dp)).padding(horizontal = 8.dp, vertical = 2.dp)) {
                                Text(formatFrequency(tunedHz), color = ivory, fontSize = 11.sp)
                            }
                            Text(if (remote) "REL" else "dBFS", Modifier.align(Alignment.TopStart).padding(start = 5.dp, top = 3.dp), color = muted, fontSize = 11.sp)
                            Text(
                                "SPAN %d kHz".format(Locale.US, visibleSpanHz / 1_000),
                                Modifier.align(Alignment.TopEnd).padding(end = 7.dp, top = 4.dp),
                                color = muted, fontSize = 11.sp
                            )
                        }
                    }
                    if (selected != "Spectrum") {
                        Box(Modifier.fillMaxWidth().weight(1f).then(waterfallNavigation(
                            visibleSpanHz = visibleSpanHz,
                            zoom = zoom,
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
            if (activeMode == DemodulationMode.CW || ftxMode != null) ModulationInformationPanel(
                activeMode, rdsInformation, tunedHz, receiving,
                wfmStereoEnabled && wfmStereoLocked, signalDbfs,
                cwInformation, { if (remote) webSession.cwDecoder.clear() else onCwClear() },
                if (remote) webSession.cwDecoder.decodingEnabled else cwDecodeEnabled,
                { if (remote) webSession.cwDecoder.requestDecoding(it) else onCwDecodeChange(it) },
                ftxMode = ftxMode, ftx = ftxInformation, onFtxClear = { activeFtx?.clear() },
            ) {
                Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    ControlCard(Modifier.weight(1f).fillMaxHeight()) {
                        Text("RECEIVER", color = muted, fontSize = 11.sp, letterSpacing = 1.sp)
                        Row(Modifier.fillMaxWidth().height(24.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(when (activeReceiverState) {
                                ReceiverState.Running -> "ON AIR"
                                ReceiverState.Starting -> "STARTING"
                                ReceiverState.Stopping -> "STOPPING"
                                ReceiverState.Error -> "ERROR"
                                ReceiverState.Idle -> "READY"
                            }, modifier = Modifier.alignByBaseline(), color = if (receiving) warm else ivory, fontSize = 18.sp)
                            Box(Modifier.weight(1f))
                            Text(if (remote) "WebSDR" else "%.0f dBFS".format(Locale.US, signalDbfs), modifier = Modifier.alignByBaseline(), color = muted, fontSize = 13.sp)
                        }
                        Row(Modifier.fillMaxWidth().height(24.dp), verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                            if (!remote && permitted && !streaming) {
                                SmallAction("USB TEST", !streamPending, Modifier.weight(1f)) { onCheckConnection() }
                                SmallAction("IQ TEST", !streamPending, Modifier.weight(1f)) { onCaptureIq() }
                            } else {
                                Text(
                                    if (remote) "${activeMode.label}  •  ${captureSpanHz / 1000} kHz" else if (streaming) "${activeMode.label}  •  2.4 MSPS" else "RTL-SDR V4 waiting for USB",
                                    color = muted, fontSize = 12.sp
                                )
                            }
                        }
                    }
                    ControlCard(Modifier.weight(1f).fillMaxHeight()) {
                        Text("DISPLAY", color = muted, fontSize = 11.sp, letterSpacing = 1.sp)
                        Row(Modifier.fillMaxWidth().height(24.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Text("Spectrum", color = ivory, fontSize = 13.sp)
                            Text(if (remote) "${webSession.visualFps} FPS" else "30 FPS", color = warm, fontSize = 13.sp)
                        }
                        Row(
                            Modifier.fillMaxWidth().height(24.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("Waterfall", color = ivory, fontSize = 13.sp)
                            Box(Modifier.weight(1f))
                            Text(if (remote) "${webSession.visualFps} FPS" else "30 FPS", color = warm, fontSize = 13.sp)
                        }
                    }
                    ControlCard(Modifier.weight(1f).fillMaxHeight()) {
                        Text("AUDIO", color = muted, fontSize = 11.sp, letterSpacing = 1.sp)
                        Row(Modifier.fillMaxWidth().height(24.dp), verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("${activeMode.label}  /  ${if (activeMode == DemodulationMode.WFM && wfmStereoEnabled && wfmStereoLocked) "STEREO" else "MONO"}",
                                color = ivory, fontSize = 13.sp, maxLines = 1)
                            Text("${if (remote) "%.1f".format(Locale.US, webSession.audioSampleRate / 1000.0) else "48"} kHz",
                                color = warm, fontSize = 13.sp, maxLines = 1)
                        }
                        Box(Modifier.fillMaxWidth().height(24.dp), contentAlignment = Alignment.CenterStart) {
                        Text(
                            if (remote) online.startError ?: webSession.error ?: webSession.waterfallWarning ?: webSdrReceiverName.ifBlank { "Select a server on MAP" } else usbCheckResult ?: deviceName,
                            color = muted, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis
                        )
                        }
                    }
                }
            }
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

@Composable
private fun Sidebar(selected: String, onSelect: (String) -> Unit) {
    Column(Modifier.width(108.dp).fillMaxHeight().background(RadiolaColors.sidebar)) {
        Box(Modifier.fillMaxWidth().height(46.dp).padding(start = 12.dp), contentAlignment = Alignment.CenterStart) {
            Text(stringResource(R.string.app_name).uppercase(Locale.ROOT), color = warm, fontSize = 18.sp,
                fontWeight = FontWeight.Light, letterSpacing = 0.3.sp, maxLines = 1)
        }
        listOf("Receiver", "Settings").forEach { name ->
            val active = selected == name
            Box(
                Modifier.fillMaxWidth().height(36.dp)
                    .background(if (active) warm.copy(alpha = .16f) else Color.Transparent)
                    .clickable { onSelect(name) },
            ) {
                if (active) Box(Modifier.align(Alignment.CenterStart).width(3.dp).height(22.dp)
                    .background(warm, RoundedCornerShape(topEnd = 2.dp, bottomEnd = 2.dp)))
                Row(Modifier.fillMaxSize().padding(horizontal = 12.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    SidebarIcon(name, if (active) warm else muted)
                    Text(name, Modifier.padding(start = 8.dp), color = if (active) warm else ivory,
                        fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                        fontSize = 12.sp, maxLines = 1)
                }
            }
        }
        Box(Modifier.weight(1f))
    }
}

@Composable
private fun SidebarIcon(name: String, tint: Color) {
    Box(Modifier.width(24.dp).height(24.dp), contentAlignment = Alignment.Center) {
        Canvas(Modifier.width(20.dp).height(20.dp)) {
            val unit = size.width / 24f
            val stroke = 1.7f * unit
            fun point(x: Float, y: Float) = Offset(x * unit, y * unit)
            if (name == "Receiver") {
                val wave = Path().apply {
                    moveTo(2f * unit, 12f * unit)
                    listOf(6f to 12f, 8.5f to 5f, 12f to 19f, 15.5f to 8f,
                        18f to 12f, 22f to 12f).forEach { (x, y) -> lineTo(x * unit, y * unit) }
                }
                drawPath(wave, tint, style = Stroke(stroke, cap = StrokeCap.Round,
                    join = androidx.compose.ui.graphics.StrokeJoin.Round))
            } else {
                val gear = Path().apply {
                    // Eight rounded teeth, with the same visual bounds as the receiver icon.
                    repeat(32) { index ->
                        val angle = index * Math.PI / 16 - Math.PI / 2
                        val radius = if (index % 4 == 0 || index % 4 == 3) 10f else 8f
                        val x = (12f + kotlin.math.cos(angle).toFloat() * radius) * unit
                        val y = (12f + kotlin.math.sin(angle).toFloat() * radius) * unit
                        if (index == 0) moveTo(x, y) else lineTo(x, y)
                    }
                    close()
                }
                drawPath(gear, tint, style = Stroke(stroke,
                    join = androidx.compose.ui.graphics.StrokeJoin.Round))
                drawCircle(tint, 3.2f * unit, point(12f, 12f), style = Stroke(stroke))
            }
        }
    }
}

@Composable
private fun GainPanel(gain: ReceiverGain, gainSteps: List<Int>, onChange: (ReceiverGain) -> Unit, modifier: Modifier = Modifier) {
    val currentGain by rememberUpdatedState(gain)
    val currentChange by rememberUpdatedState(onChange)
    val index = gainSteps.indices.minByOrNull { kotlin.math.abs(gainSteps[it] - gain.gainTenthsDb) } ?: 0
    Column(modifier) {
        Row(Modifier.fillMaxWidth().height(20.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            AgcToggle("RF AGC", gain.rfAgc, Modifier.weight(0.85f)) { onChange(gain.copy(rfAgc = !gain.rfAgc)) }
            AgcToggle("AUDIO AGC", gain.audioAgc, Modifier.weight(1f)) { onChange(gain.copy(audioAgc = !gain.audioAgc)) }
            AgcToggle("BIAS-T", gain.biasT, Modifier.weight(0.85f)) { onChange(gain.copy(biasT = !gain.biasT)) }
        }
        Row(Modifier.fillMaxWidth().height(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("GAIN", color = muted, fontSize = 10.sp)
            Box(Modifier.weight(1f))
            Text(if (gain.rfAgc) "AUTO" else "%.1f dB".format(Locale.US, gain.gainTenthsDb / 10f), color = if (gain.rfAgc) muted else warm, fontSize = 10.sp)
        }
        Canvas(
            Modifier.fillMaxWidth().height(16.dp)
                .semantics {
                    contentDescription = "RF gain"
                    progressBarRangeInfo = ProgressBarRangeInfo(index.toFloat(), 0f..gainSteps.lastIndex.toFloat(), maxOf(0, gainSteps.size - 2))
                    setProgress { value ->
                        if (!gain.rfAgc && gainSteps.isNotEmpty()) {
                            onChange(gain.copy(gainTenthsDb = gainSteps[value.roundToInt().coerceIn(gainSteps.indices)]))
                            true
                        } else false
                    }
                }
                .pointerInput(gain.rfAgc, gainSteps) {
                    if (!gain.rfAgc && gainSteps.isNotEmpty()) {
                        val inset = 7.dp.toPx()
                        fun update(x: Float) {
                            val fraction = ((x - inset) / (size.width - 2 * inset)).coerceIn(0f, 1f)
                            val selected = (fraction * gainSteps.lastIndex).roundToInt()
                            currentChange(currentGain.copy(gainTenthsDb = gainSteps[selected]))
                        }
                        awaitEachGesture {
                            val down = awaitFirstDown()
                            down.consume()
                            update(down.position.x)
                            do {
                                val event = awaitPointerEvent()
                                val pointer = event.changes.firstOrNull { it.id == down.id } ?: break
                                pointer.consume()
                                update(pointer.position.x)
                            } while (pointer.pressed)
                        }
                    }
                }
        ) {
            val inset = 7.dp.toPx()
            val y = size.height / 2
            val x = inset + (size.width - 2 * inset) * index / maxOf(1, gainSteps.lastIndex)
            drawLine(edge, Offset(inset, y), Offset(size.width - inset, y), 3.dp.toPx())
            drawLine(if (gain.rfAgc) muted.copy(alpha = 0.3f) else warm, Offset(inset, y), Offset(x, y), 3.dp.toPx())
            drawCircle(surface, 6.dp.toPx(), Offset(x, y))
            drawCircle(if (gain.rfAgc) muted.copy(alpha = 0.4f) else warm, 6.dp.toPx(), Offset(x, y), style = Stroke(1.dp.toPx()))
        }
    }
}

@Composable
private fun AgcToggle(label: String, enabled: Boolean, modifier: Modifier, onClick: () -> Unit) {
    Box(
        modifier.fillMaxHeight()
            .background(if (enabled) RadiolaColors.primaryTint else Color.Transparent, RoundedCornerShape(4.dp))
            .semantics { contentDescription = "$label ${if (enabled) "on" else "off"}" }
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Box(Modifier.width(3.dp).height(3.dp).background(if (enabled) warm else edge, RoundedCornerShape(2.dp)))
            Text(label, color = if (enabled) warm else muted, fontSize = 9.sp, maxLines = 1)
        }
    }
}

@Composable
private fun VerticalEncoder(
    onStep: (Long) -> Unit,
    tuningStep: TuningStep,
    onTuningStepChange: (TuningStep) -> Unit,
    modifier: Modifier = Modifier,
    onTick: () -> Unit = {},
    onBrake: () -> Unit = {},
    onRotate: () -> Unit = {},
    brakeRequest: Int = 0,
) {
    val currentRotate by rememberUpdatedState(onRotate)
    val currentBrake by rememberUpdatedState(onBrake)
    val currentTick by rememberUpdatedState(onTick)
    val currentStep by rememberUpdatedState(onStep)
    val currentMode by rememberUpdatedState(tuningStep)
    var menuExpanded by remember { mutableStateOf(false) }
    var activeStepHz by remember { mutableStateOf(1_000L) }
    var travel by remember { mutableStateOf(0f) }
    var phase by remember { mutableStateOf(0f) }
    val density = LocalDensity.current
    val pitch = with(density) { 18.dp.toPx() }
    val notch = with(density) { 18.dp.toPx() }
    val scope = rememberCoroutineScope()
    var flingJob by remember { mutableStateOf<Job?>(null) }
    var idleJob by remember { mutableStateOf<Job?>(null) }
    var motionIdleJob by remember { mutableStateOf<Job?>(null) }
    var lastRotationSpeed by remember { mutableStateOf(0f) }
    var lastRotationTime by remember { mutableStateOf(0L) }
    var carrySpeed by remember { mutableStateOf(0f) }
    var lastRotationDirection by remember { mutableStateOf(0f) }
    LaunchedEffect(brakeRequest) {
        if (brakeRequest != 0) {
            flingJob?.cancel()
            flingJob = null
            idleJob?.cancel()
            motionIdleJob?.cancel()
            carrySpeed = 0f
            travel = 0f
            activeStepHz = currentMode.fixedHz ?: 1_000L
            currentBrake()
        }
    }
    fun rotate(amount: Float, speed: Float) {
        if (amount != 0f) {
            currentRotate()
            lastRotationDirection = amount
            lastRotationSpeed = speed
            lastRotationTime = android.os.SystemClock.uptimeMillis()
        }
        val stepHz = currentMode.hzForSpeed(speed / density.density, activeStepHz)
        activeStepHz = stepHz
        travel += amount
        phase = (phase - amount) % pitch
        val steps = (travel / notch).toInt()
        if (steps != 0) {
            travel -= steps * notch
            currentStep(steps * stepHz)
            currentTick()
        }
    }
    Column(
        modifier.border(1.dp, edge, RoundedCornerShape(6.dp))
            .background(surface, RoundedCornerShape(6.dp))
            .pointerInput(Unit) {
                awaitEachGesture {
                    // Brake on contact, before drag detection waits for touch slop.
                    // Observe without consuming so dragging and the step menu still work.
                    awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                    // Continue AUTO speed across a quick finger reposition, while
                    // still braking physical inertia immediately on contact.
                    val continuing = currentMode == TuningStep.AUTO && activeStepHz > 1_000L
                        && android.os.SystemClock.uptimeMillis() - lastRotationTime <= 200
                    carrySpeed = if (continuing) lastRotationSpeed else 0f
                    flingJob?.cancel()
                    flingJob = null
                    currentBrake()
                    idleJob?.cancel()
                    motionIdleJob?.cancel()
                    idleJob = null
                    travel = 0f
                    if (!continuing) activeStepHz = currentMode.fixedHz ?: 1_000L
                    idleJob = scope.launch {
                        delay(150)
                        carrySpeed = 0f
                        activeStepHz = currentMode.fixedHz ?: 1_000L
                    }
                }
            }
            .pointerInput(Unit) {
                var tracker = VelocityTracker()
                var filteredSpeed = 0f
                var lastEventTime = 0L
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val pointerId = down.id
                    tracker = VelocityTracker()
                    tracker.addPosition(down.uptimeMillis, down.position)
                    filteredSpeed = carrySpeed
                    carrySpeed = 0f
                    lastEventTime = down.uptimeMillis
                    var lastPosition = down.position
                    var lastMoveTime = down.uptimeMillis
                    var previousDirection = -lastRotationDirection
                    var moved = false
                    var released = false
                    try {
                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull { it.id == pointerId } ?: break
                            if (!change.pressed) {
                                tracker.addPosition(change.uptimeMillis, change.position)
                                released = true
                                break
                            }
                            val dragAmount = change.position.y - lastPosition.y
                            if (dragAmount == 0f) continue
                            moved = true
                            change.consume()
                            idleJob?.cancel()
                            motionIdleJob?.cancel()
                            if (change.uptimeMillis - lastMoveTime >= 150) filteredSpeed = 0f
                            tracker.addPosition(change.uptimeMillis, change.position)
                            val dt = ((change.uptimeMillis - lastEventTime) / 1000f).coerceIn(0.001f, 0.1f)
                            val measured = (abs(dragAmount) / dt).coerceAtMost(4500f * density.density)
                            // Reverse immediately; old AUTO momentum must not mask a fine correction.
                            if (previousDirection * dragAmount < 0f) {
                                filteredSpeed = measured
                                activeStepHz = currentMode.fixedHz ?: 1_000L
                            } else filteredSpeed = EncoderDynamics.filteredSpeed(filteredSpeed, measured, dt)
                            previousDirection = dragAmount
                            lastEventTime = change.uptimeMillis
                            lastMoveTime = change.uptimeMillis
                            lastPosition = change.position
                            rotate(-dragAmount, filteredSpeed)
                            motionIdleJob = scope.launch { delay(35); currentBrake() }
                            val position = change.position
                            idleJob = scope.launch {
                                delay(150)
                                currentBrake()
                                filteredSpeed = 0f
                                carrySpeed = 0f
                                travel = 0f
                                activeStepHz = currentMode.fixedHz ?: 1_000L
                                tracker = VelocityTracker()
                                lastEventTime = android.os.SystemClock.uptimeMillis()
                                tracker.addPosition(lastEventTime, position)
                            }
                        }
                    } finally {
                        if (moved) {
                            idleJob?.cancel()
                            motionIdleJob?.cancel()
                            val freshRelease = released && android.os.SystemClock.uptimeMillis() - lastMoveTime < 50
                            var velocity = if (freshRelease) (-tracker.calculateVelocity().y)
                                .coerceIn(-4500f * density.density, 4500f * density.density) else 0f
                            if (abs(velocity) > 120f * density.density) {
                                flingJob = scope.launch {
                                    // Start integrating on the very next frame, without a two-frame pause.
                                    var previousTime = System.nanoTime()
                                    while (abs(velocity) > 20f * density.density) {
                                        val now = withFrameNanos { it }
                                        val dt = ((now - previousTime) / 1_000_000_000f).coerceIn(0f, 0.05f)
                                        previousTime = now
                                        rotate(EncoderDynamics.displacement(velocity, dt), abs(velocity))
                                        velocity = EncoderDynamics.decayedVelocity(velocity, dt)
                                    }
                                    travel = 0f
                                    activeStepHz = currentMode.fixedHz ?: 1_000L
                                    currentBrake()
                                }
                            } else {
                                currentBrake()
                                travel = 0f
                                activeStepHz = currentMode.fixedHz ?: 1_000L
                            }
                        }
                    }
                }
            }
            .padding(horizontal = 6.dp, vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box {
            Text(
                if (tuningStep == TuningStep.AUTO) "AUTO\n${tuningStep.shortLabel(activeStepHz)}" else "STEP\n${tuningStep.shortLabel(tuningStep.fixedHz!!)}",
                modifier = Modifier.clickable { menuExpanded = true }.padding(horizontal = 3.dp, vertical = 2.dp),
                color = warm, fontSize = 10.sp, lineHeight = 11.sp
            )
            DropdownMenu(
                expanded = menuExpanded,
                onDismissRequest = { menuExpanded = false },
                modifier = Modifier.background(surface).width(132.dp),
                containerColor = surface
            ) {
                TuningStep.entries.forEach { option ->
                    Row(
                        Modifier.fillMaxWidth().height(36.dp).clickable {
                            flingJob?.cancel()
                            idleJob?.cancel()
                            motionIdleJob?.cancel()
                            currentBrake()
                            carrySpeed = 0f
                            lastRotationTime = 0L
                            activeStepHz = option.fixedHz ?: 1_000L
                            onTuningStepChange(option)
                            menuExpanded = false
                        }.padding(horizontal = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(option.label, color = if (option == tuningStep) warm else ivory, fontSize = 13.sp)
                    }
                }
            }
        }
        Canvas(Modifier.weight(1f).fillMaxWidth().padding(vertical = 4.dp)) {
            val radius = 11.dp.toPx()
            val outline = Path().apply {
                addRoundRect(RoundRect(Rect(Offset.Zero, size), CornerRadius(radius)))
            }
            val dark = RadiolaColors.dark
            clipPath(outline) {
                // A continuous barrel surface, with the centre catching the light.
                drawRect(Brush.horizontalGradient(listOf(
                    RadiolaColors.adaptive(Color(0xFFBBCBE0), Color(0xFF17191B)),
                    RadiolaColors.adaptive(Color(0xFFE4ECF7), Color(0xFF38342E)),
                    RadiolaColors.adaptive(Color(0xFFF5F8FD), Color(0xFF51483B)),
                    RadiolaColors.adaptive(Color(0xFFDDE7F4), Color(0xFF34312C)),
                    RadiolaColors.adaptive(Color(0xFFAEBFD6), Color(0xFF141618)),
                )))
                var y = phase % pitch - pitch
                val inset = 6.dp.toPx()
                val ribWidth = (size.width - inset * 2).coerceAtLeast(1f)
                while (y < size.height + pitch) {
                    drawRoundRect(
                        Color.Black.copy(alpha = if (dark) .4f else .13f),
                        topLeft = Offset(inset, y), size = Size(ribWidth, 2.5.dp.toPx()),
                        cornerRadius = CornerRadius(2.dp.toPx()),
                    )
                    drawLine(
                        Color.White.copy(alpha = if (dark) .19f else .8f),
                        Offset(inset + 1.dp.toPx(), y + 3.dp.toPx()),
                        Offset(size.width - inset - 1.dp.toPx(), y + 3.dp.toPx()),
                        strokeWidth = .8.dp.toPx(), cap = StrokeCap.Round,
                    )
                    y += pitch
                }
                // Fade ribs into the rounded ends instead of cutting them off.
                drawRect(Brush.verticalGradient(listOf(
                    RadiolaColors.adaptive(Color(0xFFBBCBE0), Color(0xFF17191B)).copy(alpha = .9f),
                    Color.Transparent, Color.Transparent,
                    RadiolaColors.adaptive(Color(0xFFAEBFD6), Color(0xFF141618)).copy(alpha = .9f),
                )))
                drawRect(Brush.horizontalGradient(listOf(Color.Black.copy(alpha = .08f),
                    Color.Transparent, Color.Transparent, Color.Black.copy(alpha = .12f))))
            }
            drawRoundRect(Color.White.copy(alpha = if (dark) .08f else .5f),
                cornerRadius = CornerRadius(radius), style = Stroke(.75.dp.toPx()))
            val middle = size.height / 2
            drawLine(warm.copy(alpha = .15f), Offset(3.dp.toPx(), middle),
                Offset(size.width - 3.dp.toPx(), middle), 5.dp.toPx(), cap = StrokeCap.Round)
            drawLine(warm, Offset(2.dp.toPx(), middle), Offset(size.width - 2.dp.toPx(), middle),
                1.5.dp.toPx(), cap = StrokeCap.Round)
            drawCircle(warm, 2.dp.toPx(), Offset(3.dp.toPx(), middle))
            drawCircle(warm, 2.dp.toPx(), Offset(size.width - 3.dp.toPx(), middle))
        }
        Text("▲  ▼", color = warm, fontSize = 11.sp)
    }
}

@Composable
private fun BatteryIndicator(percent: Int, charging: Boolean, modifier: Modifier = Modifier) {
    val tint = if (percent in 0..20 && !charging) RadiolaColors.warning else muted
    Row(
        modifier.semantics {
            contentDescription = if (percent < 0) "Battery level unavailable" else "Battery $percent percent${if (charging) ", charging" else ""}"
        },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Canvas(Modifier.width(18.dp).height(10.dp)) {
            val bodyWidth = size.width - 2.dp.toPx()
            drawRoundRect(tint, size = Size(bodyWidth, size.height), cornerRadius = androidx.compose.ui.geometry.CornerRadius(1.dp.toPx()), style = Stroke(1.dp.toPx()))
            drawRect(tint, Offset(bodyWidth, size.height * 0.3f), Size(2.dp.toPx(), size.height * 0.4f))
            val inset = 2.dp.toPx()
            drawRect(tint.copy(alpha = 0.6f), Offset(inset, inset), Size(maxOf(0f, bodyWidth - 2 * inset) * percent.coerceIn(0, 100) / 100f, maxOf(0f, size.height - 2 * inset)))
            if (charging) {
                val bolt = Path().apply {
                    moveTo(bodyWidth * 0.6f, 0f)
                    lineTo(bodyWidth * 0.3f, size.height * 0.6f)
                    lineTo(bodyWidth * 0.5f, size.height * 0.6f)
                    lineTo(bodyWidth * 0.4f, size.height)
                    lineTo(bodyWidth * 0.75f, size.height * 0.4f)
                    lineTo(bodyWidth * 0.55f, size.height * 0.4f)
                    close()
                }
                drawPath(bolt, warm)
            }
        }
        Text(if (percent < 0) "—%" else "$percent%", color = tint, fontSize = 10.sp, maxLines = 1)
    }
}

@Composable
private fun ServerViewToggle(page: String, active: Boolean, onSelect: (String) -> Unit) {
    Row(Modifier.height(28.dp).toolbarButtonSurface().padding(2.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        listOf("RADIO" to "Radio", "MAP" to "MAP").forEach { (label, targetPage) ->
            val chosen = active && page == targetPage
            Box(Modifier.width(40.dp).fillMaxHeight()
                .background(if (chosen) RadiolaColors.primaryTint else Color.Transparent, RoundedCornerShape(2.dp))
                .semantics { contentDescription = if (targetPage == "Radio") "Show radio" else "Show server map" }
                .clickable { onSelect(targetPage) }, contentAlignment = Alignment.Center) {
                Text(label, color = if (chosen) warm else muted, fontSize = 11.sp,
                    style = TextStyle(platformStyle = PlatformTextStyle(includeFontPadding = false)))
            }
        }
    }
}

@Composable
private fun WebSdrSignalMeter(session: WebSdrSession, squelch: ReceiverSquelch, open: Boolean,
    onSquelchChange: (ReceiverSquelch) -> Unit, modifier: Modifier) {
    val signal = session.signalDbm
    SignalMeter((signal ?: -127f) + 37f, squelch.copy(enabled = false), open, onSquelchChange, modifier,
        remote = true, signalAvailable = signal != null)
}

@Composable
private fun SignalMeter(
    dbfs: Float, squelch: ReceiverSquelch, open: Boolean,
    onSquelchChange: (ReceiverSquelch) -> Unit, modifier: Modifier = Modifier,
    remote: Boolean = false, signalAvailable: Boolean = true,
) {
    val currentSquelch by rememberUpdatedState(squelch)
    val displayedSignal = remember { Animatable(dbfs) }
    LaunchedEffect(dbfs) {
        displayedSignal.animateTo(dbfs, tween(
            durationMillis = if (dbfs > displayedSignal.value) 80 else 180,
            easing = LinearOutSlowInEasing
        ))
    }
    val changeSquelch by rememberUpdatedState(onSquelchChange)
    var meterWidth by remember { mutableStateOf(1) }
    val density = LocalDensity.current
    fun setThreshold(x: Float) {
        val left = with(density) { 10.dp.toPx() }
        val right = maxOf(left + 1f, meterWidth - with(density) { 66.dp.toPx() })
        val db = (-90f + 104f * ((x - left) / (right - left)).coerceIn(0f, 1f))
            .coerceIn(-90f, -6f).roundToInt().toFloat()
        changeSquelch(currentSquelch.copy(thresholdDbfs = db))
    }
    // Relative display only: raw dBFS is not calibrated antenna power.
    val rawLevel = (displayedSignal.value + 90f).coerceAtLeast(0f)
    val maximum = 104f
    val level = rawLevel.coerceAtMost(maximum)
    fun position(value: Float): Float = value / maximum
    val readingLevel = level
    val reading = if (!signalAvailable) "S0" else if (readingLevel <= 54f) "S${(readingLevel / 6f).roundToInt()}"
        else "S9 +${(readingLevel - 54f).roundToInt()}"
    val labelPaint = remember {
        android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            textAlign = android.graphics.Paint.Align.CENTER
        }
    }
    Row(modifier.background(surface, RoundedCornerShape(5.dp)), verticalAlignment = Alignment.CenterVertically) {

        Column(
            Modifier.width(38.dp).height(28.dp)
                .toolbarButtonSurface(squelch.enabled)
                .clickable(enabled = !remote) { onSquelchChange(squelch.copy(enabled = !squelch.enabled)) }
                .semantics { contentDescription = "Squelch ${if (squelch.enabled) "on" else "off"}" },
            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center
        ) {
            Text("SQL", color = muted, fontSize = 8.sp, fontWeight = FontWeight.Normal,
                style = TextStyle(lineHeight = 10.sp, platformStyle = PlatformTextStyle(includeFontPadding = false)))
            Text(if (!squelch.enabled) "OFF" else if (open) "OPEN" else "MUTE",
                color = if (squelch.enabled) warm else muted, fontSize = 11.sp,
                style = TextStyle(lineHeight = 13.sp, platformStyle = PlatformTextStyle(includeFontPadding = false)))
        }
        Spacer(Modifier.width(2.dp))
        Canvas(
            Modifier.weight(1f).fillMaxHeight().onSizeChanged { meterWidth = it.width }
                .pointerInput(remote) { if (!remote) detectTapGestures(onTap = { setThreshold(it.x) }) }
                .pointerInput(remote) {
                    if (!remote)
                    detectDragGestures(
                        onDragStart = { setThreshold(it.x) },
                        onDrag = { change, _ -> change.consume(); setThreshold(change.position.x) }
                    )
                }
                .semantics {
                    if (!remote) {
                    progressBarRangeInfo = ProgressBarRangeInfo(squelch.thresholdDbfs, -90f..-6f, 83)
                    setProgress { changeSquelch(currentSquelch.copy(thresholdDbfs = it.coerceIn(-90f, -6f))); true }
                    }
                }
                .background(surface, RoundedCornerShape(5.dp))
                .semantics { contentDescription = if (!signalAvailable) "Signal unavailable"
                    else if (remote) "WebSDR signal $reading, ${(dbfs - 37f).roundToInt()} dBm"
                    else "Relative signal $reading, ${dbfs.roundToInt()} dBFS" }
        ) {
            val left = 10.dp.toPx()
            val right = maxOf(left + 1f, size.width - 66.dp.toPx())
            val width = right - left
            val barTop = size.height - 11.dp.toPx()
            val barHeight = 5.dp.toPx()
            val labelY = 13.dp.toPx()
            val ticks = listOf(0f to "0", 6f to "1", 18f to "3", 30f to "5", 42f to "7",
                54f to "9", 64f to "+10", 84f to "+30", 104f to "+50")
            labelPaint.textAlign = android.graphics.Paint.Align.CENTER
            labelPaint.textSize = 9.sp.toPx()
            for ((value, label) in ticks) {
                val x = left + width * position(value)
                labelPaint.color = if (value > 54f) RadiolaColors.warning.toArgb()
                    else RadiolaColors.secondaryText.toArgb()
                drawContext.canvas.nativeCanvas.drawText(label, x, labelY, labelPaint)
                drawLine(edge, Offset(x, barTop - 3.dp.toPx()), Offset(x, barTop - 1.dp.toPx()), .75.dp.toPx())
            }
            val segments = 42
            val gap = 1.dp.toPx()
            val segmentWidth = maxOf(1f, (width - gap * (segments - 1)) / segments)
            for (index in 0 until segments) {
                val fraction = (index + 1f) / segments
                drawRoundRect(
                    if (!signalAvailable || position(level) < fraction) RadiolaColors.border.copy(alpha = .55f)
                    else if (fraction > position(54f)) RadiolaColors.warning else RadiolaColors.success,
                    Offset(left + index * (segmentWidth + gap), barTop), Size(segmentWidth, barHeight),
                    cornerRadius = CornerRadius(.6.dp.toPx())
                )
            }
            val cursorX = left + width * position(if (signalAvailable) level else 0f)
            drawRoundRect(ivory, Offset(cursorX - .75.dp.toPx(), barTop - 1.dp.toPx()),
                Size(1.5.dp.toPx(), barHeight + 2.dp.toPx()), CornerRadius(.75.dp.toPx()))
            val sqlX = left + width * position(squelch.thresholdDbfs + 90f)
            val sqlColor = if (!squelch.enabled) muted.copy(alpha = 0.6f) else if (open) warm else ivory
            if (!remote) {
            drawLine(sqlColor, Offset(sqlX, barTop - 3.dp.toPx()), Offset(sqlX, size.height), 1.5.dp.toPx())
            val triangle = Path().apply {
                moveTo(sqlX, barTop + barHeight + 1.dp.toPx())
                lineTo(sqlX - 3.dp.toPx(), size.height)
                lineTo(sqlX + 3.dp.toPx(), size.height)
                close()
            }
            drawPath(triangle, sqlColor)
            }
            val readoutX = right + 16.dp.toPx()
            labelPaint.textAlign = android.graphics.Paint.Align.LEFT
            labelPaint.color = RadiolaColors.primary.toArgb()
            labelPaint.textSize = 12.sp.toPx()
            labelPaint.isFakeBoldText = true
            drawContext.canvas.nativeCanvas.drawText(reading, readoutX, 12.dp.toPx(), labelPaint)
            labelPaint.isFakeBoldText = false
            labelPaint.color = RadiolaColors.secondaryText.toArgb()
            labelPaint.textSize = 9.sp.toPx()
            val detail = if (remote) "SQL —" else "SQL %.0f dB".format(Locale.US, squelch.thresholdDbfs)
            drawContext.canvas.nativeCanvas.drawText(detail, readoutX, size.height - 5.dp.toPx(), labelPaint)
        }


    }
}
@Composable
private fun StereoToggle(available: Boolean, enabled: Boolean, locked: Boolean, onChange: (Boolean) -> Unit) {
    val active = available && enabled
    Column(
        Modifier.width(60.dp).height(28.dp)
            .background(if (active) RadiolaColors.primaryTint else surface, RoundedCornerShape(5.dp))
            .clickable(enabled = available) { onChange(!enabled) }
            .semantics { contentDescription = "WFM stereo ${if (active) "enabled" else "disabled"}, pilot ${if (locked) "locked" else "absent"}" },
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center
    ) {
        Text(if (active) "STEREO" else "MONO", color = if (active) warm else if (available) ivory else muted.copy(alpha = 0.5f), fontSize = 10.sp)
        Text(if (!available) "WFM" else if (!enabled) "FORCED" else if (locked) "LOCK" else "AUTO",
            color = if (active && locked) warm else muted, fontSize = 8.sp)
    }
}

@Composable
private fun ToolbarDivider() {
    Box(Modifier.width(1.dp).height(16.dp).background(edge))
}

@Composable
private fun ControlCard(modifier: Modifier, content: @Composable () -> Unit) {
    Column(
        modifier.border(1.dp, edge.copy(alpha = 0.65f), RoundedCornerShape(6.dp))
            .background(surface, RoundedCornerShape(6.dp)).padding(horizontal = 10.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.SpaceBetween,
        content = { content() }
    )
}

@Composable
private fun SmallAction(label: String, enabled: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Box(
        modifier.height(28.dp).border(1.dp, if (enabled) RadiolaColors.primary else edge, RoundedCornerShape(4.dp))
            .background(if (enabled) RadiolaColors.primaryTint else surface, RoundedCornerShape(4.dp))
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(label, color = if (enabled) warm else muted, fontSize = 12.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun AmberSpectrum(
    valuesState: State<SpectrumFrame?>,
    spectrumSpanHz: Int,
    receiveBandwidthHz: Int,
    channelOffsetHz: Long,
    passbandOffsetHz: Int,
    visibleCenterHz: Double,
    modifier: Modifier = Modifier,
    relative: Boolean = false,
) {
    val traceSmoother = remember { SpectrumTraceSmoother() }
    val axisPaint = remember {
        android.graphics.Paint().apply {
            color = RadiolaColors.secondaryText.toArgb()
            isAntiAlias = true
        }
    }
    Canvas(modifier) {
        axisPaint.color = RadiolaColors.secondaryText.toArgb()
        val started = System.nanoTime()
        val left = 31.dp.toPx()
        val top = 20.dp.toPx()
        val bottom = size.height
        val width = size.width - left
        val height = bottom - top
        for (step in 0..3) {
            val y = top + height * step / 3f
            drawLine(RadiolaColors.plotGrid, Offset(left, y), Offset(size.width, y), 1f)
        }
        for (step in 0..8) {
            val x = left + width * step / 8f
            drawLine(RadiolaColors.plotGrid, Offset(x, top), Offset(x, bottom), 1f)
        }
        axisPaint.textSize = 11.sp.toPx()
        for (step in 0..3) {
            drawContext.canvas.nativeCanvas.drawText(
                if (relative) "${100 - (100f * step / 3).roundToInt()}" else "${-40 * step}", 2.dp.toPx(), top + height * step / 3f + 3.dp.toPx(),
                axisPaint
            )
        }
        val frame = valuesState.value
        val values = frame?.let { traceSmoother.update(it) } ?: emptySpectrum
        val captureSpan = frame?.spanHz ?: spectrumSpanHz
        val captureCenter = frame?.centerFrequencyHz?.toDouble() ?: visibleCenterHz
        val viewportStart = (0.5 + (visibleCenterHz - captureCenter - spectrumSpanHz * .5) / captureSpan).toFloat()
        val viewportEnd = viewportStart + spectrumSpanHz.toFloat() / captureSpan
        if (values.isNotEmpty()) {
            val trace = Path()
            val fill = Path()
            val startBin = (viewportStart * values.size).toInt().coerceIn(0, values.size)
            val endBin = (viewportEnd * values.size).toInt().coerceIn(startBin, values.size)
            val binCount = endBin - startBin
            val columns = minOf(512, binCount)
            for (index in 0 until columns) {
                val first = startBin + index * binCount / columns
                val last = startBin + (index + 1) * binCount / columns
                var db = values[first]
                for (bin in first + 1 until last) db = maxOf(db, values[bin])
                val fraction = (first.toFloat() / values.size - viewportStart) / (viewportEnd - viewportStart)
                val x = left + width * fraction
                val y = bottom - (if (relative) (db + 94f) / 69f else (db + 120f) / 120f).coerceIn(0f, 1f) * height
                if (index == 0) {
                    trace.moveTo(x, y)
                    fill.moveTo(x, bottom)
                    fill.lineTo(x, y)
                } else {
                    trace.lineTo(x, y)
                    fill.lineTo(x, y)
                }
            }
            if (columns > 0) {
                val endX = left + width * (endBin.toFloat() / values.size - viewportStart) / (viewportEnd - viewportStart)
                fill.lineTo(endX, bottom)
            }
            fill.close()
            drawPath(fill, Brush.verticalGradient(listOf(RadiolaColors.adaptive(Color(0xB3205BCB), Color(0xD9EDA345)), RadiolaColors.adaptive(Color(0x66205BCB), Color(0x804F351B)), RadiolaColors.adaptive(Color(0x26205BCB), Color(0x332E2115))), startY = top, endY = bottom))
            drawPath(trace, warm, style = Stroke(1.dp.toPx()))
        }
        val center = left + width * (0.5f + channelOffsetHz.toFloat() / spectrumSpanHz)
        val channelWidth = width * (receiveBandwidthHz.toFloat() / spectrumSpanHz)
        val passbandCenter = center + width * passbandOffsetHz / spectrumSpanHz
        val channelLeft = passbandCenter - channelWidth / 2f
        val channelRight = passbandCenter + channelWidth / 2f
        clipRect(left = left, top = top, right = size.width, bottom = bottom) {
        drawRect(
            Brush.horizontalGradient(
                listOf(RadiolaColors.adaptive(Color(0x19205BCB), Color(0x19E8B36A)), RadiolaColors.adaptive(Color(0x38205BCB), Color(0x38E8B36A)), RadiolaColors.adaptive(Color(0x19205BCB), Color(0x19E8B36A))),
                startX = channelLeft, endX = channelRight
            ),
            Offset(channelLeft, top), Size(channelWidth, height)
        )
        drawLine(RadiolaColors.adaptive(Color(0x80205BCB), Color(0x80E8B36A)), Offset(channelLeft, top), Offset(channelLeft, bottom), 1.dp.toPx())
        drawLine(RadiolaColors.adaptive(Color(0x80205BCB), Color(0x80E8B36A)), Offset(channelRight, top), Offset(channelRight, bottom), 1.dp.toPx())
        drawLine(RadiolaColors.adaptive(Color(0xC9205BCB), Color(0xC9E8B36A)), Offset(center, top), Offset(center, bottom), 1.dp.toPx())
        }
        RenderTimings.spectrum.record(System.nanoTime() - started)
    }
}

private fun formatFrequency(hz: Long): String = String.format(Locale.US, "%d.%03d.%03d", hz / 1_000_000, (hz / 1_000) % 1_000, hz % 1_000)

@Preview(showBackground = true, widthDp = 800, heightDp = 360)
@Composable
private fun ReceiverPreview() {
    ReceiverScreen(
        deviceName = "RTL-SDR Blog V4", connected = true, permitted = true,
        usbCheckResult = "USB connected", streaming = true, streamPending = false,
        frequencyInput = "100.500", currentFrequencyHz = 100_500_000L,
        spectrumSpanHz = 2_400_000, receiveBandwidthHz = 240_000,
        spectrumState = remember { mutableStateOf<SpectrumFrame?>(SpectrumFrame(FloatArray(1024) { -95f + if (it in 490..530) 50f else 0f }, 100_500_000, 1)) },
        waterfallState = remember { mutableStateOf<SpectrumFrame?>(SpectrumFrame(FloatArray(1024) { -95f + if (it in 490..530) 50f else 0f }, 100_500_000, 1)) },
        waterfallHistory = remember { WaterfallHistory().apply {
            append(SpectrumFrame(FloatArray(1024) { -95f + if (it in 490..530) 50f else 0f }, 100_500_000, 1))
        } },
        waterfallFrameRate = WaterfallFrameRate.FPS_30, actualSpectrumFps = 30, actualWaterfallFps = 30,
        signalDbfs = -45f,
        onRequestPermission = {}, onCheckConnection = {}, onCaptureIq = {}, onStartStream = {}, onStopStream = {},
        onFrequencyInput = {}, onTune = {}, onStep = {}, onTuningStepChange = {}, onWaterfallFrameRateChange = {},
        tuningStep = TuningStep.KHZ_100, demodulationMode = DemodulationMode.WFM,
        onDemodulationModeChange = {}, receiverGain = ReceiverGain(), gainSteps = ReceiverGain.defaultSteps,
        onReceiverGainChange = {}
    )
}
