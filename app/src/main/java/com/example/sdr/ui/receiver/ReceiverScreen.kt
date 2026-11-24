package com.example.sdr.ui.receiver

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
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.example.sdr.RadiolaApplication
import com.example.sdr.plugins.PluginCard
import com.example.sdr.plugins.pluginCardLayout
import com.example.sdr.plugins.PluginLayout
import com.example.sdr.plugins.PluginPicker
import com.example.sdr.plugins.PluginsPage
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.sdr.DemodulationMode
import com.example.sdr.RdsInformation
import com.example.sdr.SpectrumFrame
import com.example.sdr.platform.SignalMeterInertia
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
import com.example.sdr.ui.components.BandwidthControl
import com.example.sdr.ui.components.ModeControl
import com.example.sdr.ui.components.SourcePlaybackControl
import com.example.sdr.ui.components.WebSdrBandControl
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
    connected: Boolean,
    permitted: Boolean,
    streaming: Boolean,
    streamPending: Boolean,
    starting: Boolean = false,
    frequencyInput: String,
    frequencyInputError: String?,
    receiverError: String?,
    spectrumSpanHz: Int,
    bandwidthDefault: Boolean = true,
    receiverGain: ReceiverGain,
    gainSteps: List<Int>,
    spectrumState: State<SpectrumFrame?>,
    waterfallHistory: WaterfallHistory,
    tuningStep: TuningStep,
    actualWaterfallFps: Int,
    signalDbfs: State<Float>,
    onRequestPermission: () -> Unit,
    onStartStream: () -> Unit,
    onStopStream: () -> Unit,
    onFrequencyInput: (String) -> Unit,
    onTune: () -> Boolean,
    onReceiverGainChange: (ReceiverGain) -> Unit,
    onTuningStepChange: (TuningStep) -> Unit,
    signalMeterInertia: SignalMeterInertia, onSignalMeterInertiaChange: (SignalMeterInertia) -> Unit,
    analogSignalMeter: Boolean, onAnalogSignalMeterChange: (Boolean) -> Unit,
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
    var favoriteEditor by remember { mutableStateOf<com.example.sdr.favorites.FavoriteStation?>(null) }
    val favoriteSnapshot = presentation.favoriteSnapshot(bandwidthDefault)
    val matchingFavorite = favoriteSnapshot?.let { snapshot -> presentation.favorites.stations.firstOrNull { it.sameSettings(snapshot) } }
    favoriteEditor?.let { value ->
        FavoriteEditor(value, presentation.favorites.stations.any { it.id == value.id },
            onSave = presentation.favorites::save,
            onDelete = { presentation.favorites.delete(value.id) }, onClose = { favoriteEditor = null })
    }
    val context = LocalContext.current
    val plugins = (context.applicationContext as RadiolaApplication).plugins
    val online = presentation.online
    val uiState = presentation.state
    val source = uiState.source
    val selectedPlugin = plugins.selected
    val plugin = selectedPlugin?.takeIf { source in it.sources }
    var pluginLayout by rememberSaveable(plugin?.id) {
        mutableStateOf(PluginLayout.forInitialSize(plugin?.initialSize))
    }
    val showSpectrum = plugin == null || pluginLayout.spectrum
    val showWaterfall = plugin == null || pluginLayout.waterfall
    val webSdrPage = online.page
    val openWebRx = source == ReceiverSource.OPEN_WEB_RX
    val webSdrReceiverUrl = if (openWebRx) online.openWebRxReceiver?.url else online.receiverUrl
    val webSdrReceiverName = if (openWebRx) online.openWebRxReceiver?.name.orEmpty() else online.receiverName
    val webSession = online.session
    val mapState = androidx.compose.runtime.saveable.rememberSaveableStateHolder()
    val remote = source == ReceiverSource.WEB_SDR || openWebRx
    val tunedHz = uiState.frequencyHz
    var faxExpanded by remember(tunedHz, source, selected) { mutableStateOf(false) }
    val activeMode = uiState.mode
    val activeBandwidth = uiState.bandwidthHz
    var remoteInput by remember { mutableStateOf("") }
    val activeInput = if (remote) remoteInput else frequencyInput
    var remoteInputError by remember(source) { mutableStateOf<String?>(null) }
    val changeInput: (String) -> Unit = {
        if (remote) { remoteInput = it; remoteInputError = null } else onFrequencyInput(it)
    }
    val applyInput: () -> Boolean = {
        if (remote) {
            val bands = if (openWebRx) listOfNotNull(webSession.bands.getOrNull(webSession.currentBand)) else webSession.bands
            val result = com.example.sdr.receiver.validateFrequencyInput(remoteInput, bands.map { it.lowHz..it.highHz })
            remoteInputError = if (!webSession.tuningReady) "Connect the receiver first" else result.error?.message
            if (remoteInputError == null && result.hz != null) { webSession.tune(result.hz); true } else false
        } else onTune()
    }
    val step: (Long) -> Unit = { presentation.cancelFavorite(); presentation.step(it) }
    var ftxMode by presentation::ftxMode
    val changeMode: (DemodulationMode) -> Unit = { presentation.cancelFavorite(); presentation.changeMode(it) }
    val changeBandwidth: (Int?) -> Unit = presentation::changeBandwidth
    var editingFrequency by remember { mutableStateOf(false) }
    LaunchedEffect(tunedHz, editingFrequency) {
        if (!editingFrequency) remoteInput = "%.6f".format(Locale.US, tunedHz / 1_000_000.0)
    }
    var zoom by remember { mutableFloatStateOf(1f) }
    val onlineFrame = remember(webSession) { androidx.compose.runtime.derivedStateOf { webSession.frame } }
    val activeSpectrum = if (remote) onlineFrame else spectrumState
    val activeHistory = if (remote) webSession.waterfallHistory else waterfallHistory
    val captureSpanHz = if (remote) webSession.bands.getOrNull(webSession.currentBand)?.spanHz
        ?: spectrumSpanHz else spectrumSpanHz
    val maxWaterfallZoom = if (remote) maxOf(1f, captureSpanHz / 10_000f) else 16f
    val effectiveZoom = zoom.coerceIn(1f, maxWaterfallZoom)
    LaunchedEffect(maxWaterfallZoom) { zoom = zoom.coerceIn(1f, maxWaterfallZoom) }
    val visibleSpanHz = (captureSpanHz / effectiveZoom).roundToInt().coerceAtLeast(1)
    LaunchedEffect(webSession, receiverSquelch) { webSession.squelchSetting = receiverSquelch }
    val receiving = uiState.receiving
    val pluginPlayable = com.example.sdr.plugins.canRunAudioPlugin(
        plugin, source, receiving, openWebRx && webSession.serverDataMode,
    )
    LaunchedEffect(source, selectedPlugin, pluginPlayable) {
        if (selectedPlugin != null && plugin == null) plugins.select(null)
        else if (!pluginPlayable) plugins.stop()
    }
    var displayFrequencyHz by remember { mutableDoubleStateOf(tunedHz.toDouble()) }
    var encoderRotating by remember { mutableStateOf(false) }
    var encoderBrakeRequest by remember { mutableIntStateOf(0) }
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
                encoderBrakeRequest++
                encoderRotating = false
                frequencyMotion.snapTo(tunedHz.toDouble())
                presentation.cancelFavorite()
                selected = it
            })
        Column(
            Modifier.weight(1f).fillMaxHeight().padding(start = 8.dp, end = 8.dp, top = 6.dp, bottom = 4.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Box(Modifier.fillMaxWidth().height(84.dp)) {
                Row(Modifier.fillMaxWidth().height(52.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (remote) {
                    var serverNameExpanded by remember(webSdrReceiverName) { mutableStateOf(false) }
                    val fullServerName = webSdrReceiverName.ifBlank { "Select a server on MAP" }
                    val shortServerName = remember(fullServerName) {
                        val name = fullServerName.substringBefore(" - ").substringBefore(" \u2013 ").substringBefore(" \u2014 ").trim()
                        name.replace(Regex("(?i)^(?:RX[- ]*)?(?:WebSDR|OpenWebRX\\+?)\\s*[-:|]?\\s*"), "")
                            .ifBlank { name }
                    }
                    Column(Modifier.width(180.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        val warning = online.startError ?: webSession.error ?: webSession.waterfallWarning
                        Text(when (webSession.state) {
                            ReceiverConnectionState.Connected -> "${source.storedValue.uppercase(Locale.ROOT)}  /  LIVE"
                            ReceiverConnectionState.Connecting -> "${source.storedValue.uppercase(Locale.ROOT)}  /  CONNECTING"
                            ReceiverConnectionState.Error -> "${source.storedValue.uppercase(Locale.ROOT)}  /  CONNECTION ERROR"
                            else -> "${source.storedValue.uppercase(Locale.ROOT)}  /  OFFLINE"
                        }, color = if (webSession.state == ReceiverConnectionState.Connected) warm else muted,
                            fontSize = 8.sp, lineHeight = 10.sp, letterSpacing = 0.8.sp, maxLines = 1)
                        Text(if (serverNameExpanded) fullServerName else shortServerName,
                            Modifier.clickable(enabled = webSdrReceiverName.isNotBlank()) { serverNameExpanded = !serverNameExpanded }, color = ivory,
                            fontSize = if (serverNameExpanded) 12.sp else 16.sp,
                            lineHeight = if (serverNameExpanded) 14.sp else 18.sp, fontWeight = FontWeight.Normal,
                            style = TextStyle(platformStyle = PlatformTextStyle(includeFontPadding = false)),
                            maxLines = if (serverNameExpanded && warning == null) 2 else 1, overflow = TextOverflow.Ellipsis)
                        if (warning != null) Text(warning, color = RadiolaColors.warning, fontSize = 9.sp,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                } else GainPanel(receiverGain, gainSteps, onReceiverGainChange, Modifier.width(180.dp))
                FrequencyControl(tunedHz, activeInput, editingFrequency, changeInput, applyInput,
                    inputError = if (remote) remoteInputError else frequencyInputError,
                    onEditingChange = { editingFrequency = it }, step = {
                        encoderBrakeRequest++
                        encoderRotating = false
                        step(it)
                    }, tuningStep = tuningStep, favorite = matchingFavorite != null, favoriteEnabled = favoriteSnapshot != null,
                    onFavorite = { favoriteEditor = matchingFavorite ?: favoriteSnapshot },
                    onRemoveFavorite = { matchingFavorite?.let { presentation.favorites.delete(it.id) } }, modifier = Modifier.weight(1f))
                Row(Modifier.height(28.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    SourcePlaybackControl(
                        source = source,
                        onSourceChange = {
                            if (it != source) {
                                presentation.cancelFavorite()
                                ftxMode = null
                                presentation.faxMode = false
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
                    BatteryBacklightStatus(batteryPercent, batteryCharging, screenAlwaysOn, onScreenAlwaysOnChange)
                }
                }
                BoxWithConstraints(
                    Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(28.dp)
                        .background(surface, RoundedCornerShape(5.dp)),
                ) {
                // Leave the middle free for the server view switch in both source layouts.
                val meterWidth = maxWidth / 2 - 96.dp
                Row(Modifier.fillMaxSize(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    ModeControl(activeMode, ftxMode, remote,
                        !remote || webSession.active && (!openWebRx || webSession.serverModes?.any { it.id == "usb" } == true),
                        serverModes = if (openWebRx) webSession.serverModes else null,
                        serverModeId = webSession.serverModeId,
                        serverModeLabel = webSession.modeLabel,
                        onServerMode = presentation::selectServerMode,
                        onMode = changeMode, onFtx = presentation::selectFtx,
                        faxMode = presentation.faxSelected, onFax = presentation::selectFax)
                    BandwidthControl(activeMode, activeBandwidth,
                        if (remote) webSession.bandwidthDefault else bandwidthDefault, changeBandwidth,
                        if (remote) webSession.bandwidthOptions else BandwidthOptions.forMode(activeMode),
                        enabled = !remote || webSession.bandwidthAdjustable)
                    if (remote) WebSdrBandControl(webSession.bands, webSession.currentBand,
                        webSession.active, presentation::selectBand)
                    PluginPicker(plugins, source)
                    if (!remote && activeMode == DemodulationMode.WFM) StereoToggle(
                        activeMode == DemodulationMode.WFM, wfmStereoEnabled, wfmStereoLocked,
                        onWfmStereoChange
                    )
                    Spacer(Modifier.weight(1f))
                    com.example.sdr.ui.components.NoiseReductionControl(
                        available = com.example.sdr.audio.HamNoiseSettings.model(activeMode) != 0 && !(openWebRx && webSession.serverDataMode),
                        cw = activeMode == DemodulationMode.CW)
                    Row(Modifier.width(meterWidth).fillMaxHeight(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                    if (remote) {
                        WebSdrSignalMeter(webSession, receiverSquelch, squelchOpen, onSquelchChange,
                            Modifier.weight(1f).fillMaxHeight(), analog = analogSignalMeter, inertia = signalMeterInertia)
                    } else {
                        UsbSignalMeter(signalDbfs, receiverSquelch, squelchOpen, onSquelchChange, Modifier.weight(1f).fillMaxHeight(), analog = analogSignalMeter, inertia = signalMeterInertia)
                    }
                    }
                }
                // Center the view toggle over the same horizontal area as the RF plot.
                if (remote) Box(Modifier.align(Alignment.Center).offset(x = (ReceiverPlotGeometry.frequencyInset - ReceiverPlotGeometry.encoderReserve) / 2)) {
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
                        tuningStep = tuningStep, onStepChange = onTuningStepChange,
                        feedback = encoderFeedback, onFeedbackChange = onEncoderFeedbackChange,
                        signalMeterInertia = signalMeterInertia, onSignalMeterInertiaChange = onSignalMeterInertiaChange,
                        analogSignalMeter = analogSignalMeter, onAnalogSignalMeterChange = onAnalogSignalMeterChange,
                        screenAlwaysOn = screenAlwaysOn, onScreenAlwaysOnChange = onScreenAlwaysOnChange,
                        onClose = { selected = "Receiver" }
                    )
                } else if (selected == "Favorites") {
                    FavoritesPanel(presentation, onEdit = { favoriteEditor = it }, onClose = { selected = "Receiver" }, usbConnected = connected)
                } else if (selected == "Plugins") {
                    PluginsPage(plugins)
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
                    Modifier.fillMaxSize().padding(end = ReceiverPlotGeometry.encoderReserve),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
            if (showSpectrum || showWaterfall) ReceiverPlots(
                state = ReceiverPlotState(visibleCenterHz, visibleSpanHz, activeBandwidth, channelOffsetHz,
                    passbandOffsetHz, remote, automaticDisplay, automaticLevels, showSpectrum, showWaterfall,
                    if (!receiving) 0 else if (remote) webSession.visualFps else actualWaterfallFps),
                spectrum = activeSpectrum, history = activeHistory, favorites = presentation.favorites.stations,
                onAutomatic = { automaticDisplay = !automaticDisplay },
                onFavorite = { station ->
                    encoderBrakeRequest++
                    encoderRotating = false
                    presentation.activateFavorite(station) { selected = "Receiver" }
                },
                navigation = ReceiverPlotNavigation(effectiveZoom, maxWaterfallZoom,
                    onStep = step, onZoom = { zoom = it }, onContact = {
                        encoderBrakeRequest++
                        encoderRotating = false
                        frequencyMotion.snapTo(latestFrequencyHz.toDouble())
                        displayFrequencyHz = latestFrequencyHz.toDouble()
                    }),
                modifier = Modifier.fillMaxWidth().weight(1f),
            )
            ReceiverDecoderCard(
                presentation, openWebRx, activeMode, ftxMode, plugin != null, rdsInformation,
                tunedHz, receiving, wfmStereoEnabled, wfmStereoLocked, signalDbfs, source,
                webSdrReceiverUrl, onFaxExpand = { faxExpanded = true },
            )
            if (plugin != null) {
                PluginCard(plugins, pluginLayout, onLayout = { pluginLayout = it },
                    playable = pluginPlayable, onReceiverCommand = presentation::pluginReceiverCommand,
                    modifier = Modifier.pluginCardLayout(pluginLayout, this))
            }
                }
                androidx.compose.animation.AnimatedVisibility(
                    visible = faxExpanded && presentation.faxSelected,
                    modifier = Modifier.fillMaxSize().padding(end = ReceiverPlotGeometry.encoderReserve),
                    enter = androidx.compose.animation.fadeIn(androidx.compose.animation.core.tween(180)) +
                        androidx.compose.animation.scaleIn(
                            initialScale = .96f,
                            transformOrigin = androidx.compose.ui.graphics.TransformOrigin(.5f, 1f),
                            animationSpec = androidx.compose.animation.core.tween(220)),
                    exit = androidx.compose.animation.fadeOut(androidx.compose.animation.core.tween(140)) +
                        androidx.compose.animation.scaleOut(targetScale = .98f,
                            animationSpec = androidx.compose.animation.core.tween(160)),
                ) {
                    com.example.sdr.ui.decoders.FaxImageViewer(presentation.faxInformation,
                        onClose = { faxExpanded = false })
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
                    modifier = Modifier.align(Alignment.CenterEnd).width(ReceiverPlotGeometry.encoderWidth).fillMaxHeight()
                )
                }
                if (!remote && selected == "Receiver") {
                    ReceiverErrorNotice(receiverError,
                        Modifier.align(Alignment.BottomCenter).padding(horizontal = 12.dp, vertical = 8.dp))
                }
            }
        }
    }
}
