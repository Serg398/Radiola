package com.example.sdr

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.layout.offset
import androidx.compose.ui.viewinterop.AndroidView
import java.util.Locale
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.roundToInt

private val backdrop = Color(0xFF0D0F11)
private val surface = Color(0xFF151719)
private val edge = Color(0xFF30302E)
private val warm = Color(0xFFF7BA6D)
private val muted = Color(0xFF968E84)
private val ivory = Color(0xFFF4E8D7)

@Composable
fun ReceiverScreen(
    deviceName: String,
    connected: Boolean,
    permitted: Boolean,
    usbCheckResult: String?,
    streaming: Boolean,
    streamPending: Boolean,
    frequencyInput: String,
    currentFrequencyHz: Long,
    spectrumSpanHz: Int,
    spectrumCenterFrequencyHz: Long,
    receiveBandwidthHz: Int,
    demodulationMode: DemodulationMode,
    receiverGain: ReceiverGain,
    gainSteps: List<Int>,
    spectrumState: State<FloatArray>,
    waterfallState: State<FloatArray>,
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
    onWfmStereoChange: (Boolean) -> Unit = {}
) {
    var selected by remember { mutableStateOf("Receiver") }
    var editingFrequency by remember { mutableStateOf(false) }
    var zoom by remember { mutableStateOf(1f) }
    val visibleSpanHz = (spectrumSpanHz / zoom).roundToInt()
    var displayFrequencyHz by remember { mutableStateOf(currentFrequencyHz.toDouble()) }
    LaunchedEffect(currentFrequencyHz) {
        val target = currentFrequencyHz.toDouble()
        // Preserve the current visual position when another encoder step arrives.
        // Very large direct jumps have no overlapping spectrum to scroll through.
        if (abs(target - displayFrequencyHz) > spectrumSpanHz * 2.0) {
            displayFrequencyHz = target
            return@LaunchedEffect
        }
        var previousFrame = System.nanoTime()
        while (abs(target - displayFrequencyHz) > 0.5) {
            withFrameNanos { frame ->
                val dt = ((frame - previousFrame) / 1_000_000_000.0).coerceIn(0.0, 0.05)
                previousFrame = frame
                displayFrequencyHz += (target - displayFrequencyHz) * (1.0 - kotlin.math.exp(-dt / 0.065))
            }
        }
        displayFrequencyHz = target
    }
    val visibleCenterHz = displayFrequencyHz
    val channelOffsetHz = 0L
    val passbandOffsetHz = demodulationMode.passbandOffsetHz.toLong()
    // Keep the viewport's actual width even while hardware catches up with tuning.
    val viewportStart = (0.5 + (visibleCenterHz - spectrumCenterFrequencyHz - visibleSpanHz / 2.0) / spectrumSpanHz).toFloat()
    val viewportEnd = viewportStart + visibleSpanHz.toFloat() / spectrumSpanHz
    val textMeasurer = rememberTextMeasurer()
    Row(Modifier.fillMaxSize().background(backdrop)) {
        Sidebar(selected, onSelect = { selected = it })
        Column(
            Modifier.weight(1f).fillMaxHeight().padding(8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Box(Modifier.fillMaxWidth().height(100.dp)) {
                Row(Modifier.fillMaxWidth().height(58.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                GainPanel(receiverGain, gainSteps, onReceiverGainChange, Modifier.width(210.dp))
                BoxWithConstraints(Modifier.weight(1f)) {
                val frequencyText = formatFrequency(currentFrequencyHz)
                val frequencyStyle = TextStyle(fontSize = 52.sp, fontWeight = FontWeight.Light, letterSpacing = 1.sp)
                val frequencyWidth = textMeasurer.measure(frequencyText, frequencyStyle, softWrap = false).size.width
                val unitsWidth = textMeasurer.measure("MHz", TextStyle(fontSize = 17.sp)).size.width
                val availableWidth = with(LocalDensity.current) { maxWidth.toPx() - 6.dp.toPx() } - unitsWidth
                val frequencyFontSize = (52f * (availableWidth / maxOf(1, frequencyWidth)).coerceIn(0.5f, 1f)).sp
                Row(
                    Modifier.align(Alignment.Center),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    if (editingFrequency) {
                        BasicTextField(
                            value = frequencyInput,
                            onValueChange = onFrequencyInput,
                            modifier = Modifier.weight(1f).alignByBaseline(),
                            singleLine = true,
                            textStyle = TextStyle(color = ivory, fontSize = frequencyFontSize, fontWeight = FontWeight.Light),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                            cursorBrush = SolidColor(warm)
                        )
                        Text("✓", Modifier.clickable { onTune(); editingFrequency = false }, color = warm, fontSize = 36.sp)
                    } else {
                        Text(
                            frequencyText,
                            Modifier.alignByBaseline().clickable { editingFrequency = true },
                            color = ivory, fontSize = frequencyFontSize, fontWeight = FontWeight.Light,
                            letterSpacing = (frequencyFontSize.value / 52f).sp, maxLines = 1
                        )
                    }
                    Text("MHz", Modifier.alignByBaseline(), color = muted, fontSize = 17.sp, maxLines = 1, softWrap = false)
                }
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Box(Modifier.width(7.dp).height(7.dp).background(if (connected && permitted) Color(0xFF49BC74) else muted, RoundedCornerShape(7.dp)))
                    Text(
                        if (connected && permitted) "CONNECTED" else "DISCONNECTED",
                        color = muted, fontSize = 10.sp, letterSpacing = 0.5.sp
                    )
                    BatteryIndicator(batteryPercent, batteryCharging)
                    SmallAction(
                        if (streaming) "STOP" else if (!permitted) "USB ACCESS" else "START",
                        !streamPending && connected,
                        Modifier.width(if (!permitted) 95.dp else 76.dp)
                    ) {
                        when {
                            !permitted -> onRequestPermission()
                            streaming -> onStopStream()
                            else -> onStartStream()
                        }
                    }
                    Canvas(Modifier.width(30.dp).height(32.dp).clickable { selected = "Settings" }.padding(6.dp)) {
                        for (line in 0..2) {
                            val y = size.height * (0.2f + 0.3f * line)
                            drawLine(ivory, Offset(0f, y), Offset(size.width, y), 1.5.dp.toPx())
                        }
                    }
                }
                }
                Row(
                    Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(36.dp),
                    horizontalArrangement = Arrangement.spacedBy(7.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    SignalMeter(signalDbfs, receiverSquelch, squelchOpen, onSquelchChange, Modifier.weight(1f).fillMaxHeight())
                    StereoToggle(
                        demodulationMode == DemodulationMode.WFM, wfmStereoEnabled, wfmStereoLocked,
                        onWfmStereoChange
                    )
                    Text("MODE", color = muted, fontSize = 11.sp, letterSpacing = 1.sp)
                    ModePill("AM", demodulationMode == DemodulationMode.AM) { onDemodulationModeChange(DemodulationMode.AM) }
                    ModePill("WFM", demodulationMode == DemodulationMode.WFM) { onDemodulationModeChange(DemodulationMode.WFM) }
                    ModePill("FM", demodulationMode == DemodulationMode.NFM) { onDemodulationModeChange(DemodulationMode.NFM) }
                    ModePill("USB", demodulationMode == DemodulationMode.USB) { onDemodulationModeChange(DemodulationMode.USB) }
                    ModePill("LSB", demodulationMode == DemodulationMode.LSB) { onDemodulationModeChange(DemodulationMode.LSB) }
                    ModePill("CW", demodulationMode == DemodulationMode.CW) { onDemodulationModeChange(DemodulationMode.CW) }
                }
            }
            Box(Modifier.fillMaxWidth().weight(1f)) {
                Column(
                    Modifier.fillMaxSize().padding(end = 82.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
            Box(
                Modifier.fillMaxWidth().weight(1f)
                    .clipToBounds()
                    .border(1.dp, edge, RoundedCornerShape(6.dp))
                    .background(Color(0xFF0C0E10), RoundedCornerShape(6.dp))
            ) {
                Column(Modifier.fillMaxSize().padding(3.dp)) {
                    if (selected != "Waterfall") {
                        BoxWithConstraints(Modifier.fillMaxWidth().weight(1f)) {
                            AmberSpectrum(spectrumState, visibleSpanHz, receiveBandwidthHz, channelOffsetHz, demodulationMode.passbandOffsetHz, viewportStart, viewportEnd, Modifier.fillMaxSize())
                            var markerWidthPx by remember { mutableStateOf(0) }
                            val markerWidth = with(LocalDensity.current) { markerWidthPx.toDp() }
                            val markerCenter = 31.dp + (maxWidth - 31.dp) * (0.5f + channelOffsetHz.toFloat() / visibleSpanHz)
                            val markerOffset = (markerCenter - markerWidth / 2).coerceIn(31.dp, maxOf(31.dp, maxWidth - markerWidth))
                            Box(Modifier.align(Alignment.TopStart).offset(x = markerOffset).onSizeChanged { markerWidthPx = it.width }.padding(top = 4.dp).background(Color(0xFF4A321D), RoundedCornerShape(12.dp)).border(1.dp, warm, RoundedCornerShape(12.dp)).padding(horizontal = 8.dp, vertical = 2.dp)) {
                                Text(formatFrequency(currentFrequencyHz), color = ivory, fontSize = 11.sp)
                            }
                            Text("dBFS", Modifier.align(Alignment.TopStart).padding(start = 5.dp, top = 3.dp), color = muted, fontSize = 11.sp)
                            Text(
                                "SPAN %d kHz  •  %s %s".format(Locale.US, visibleSpanHz / 1_000, demodulationMode.label,
                                    if (receiveBandwidthHz < 1000) "$receiveBandwidthHz Hz" else "%.1f kHz".format(Locale.US, receiveBandwidthHz / 1000.0)),
                                Modifier.align(Alignment.TopEnd).padding(end = 7.dp, top = 4.dp),
                                color = muted, fontSize = 11.sp
                            )
                        }
                    }
                    if (selected != "Spectrum") {
                        Box(Modifier.fillMaxWidth().weight(1f).pointerInput(Unit) {
                            val minimumDistance = 24.dp.toPx()
                            awaitEachGesture {
                                awaitFirstDown(requireUnconsumed = false)
                                var previousDistance = 0f
                                var previousIds: List<androidx.compose.ui.input.pointer.PointerId> = emptyList()
                                do {
                                    val event = awaitPointerEvent()
                                    val fingers = event.changes.filter { it.pressed }
                                    if (fingers.size == 2) {
                                        val ids = fingers.map { it.id }
                                        val distance = abs(fingers[0].position.x - fingers[1].position.x)
                                        if (ids == previousIds && distance >= minimumDistance && previousDistance >= minimumDistance) {
                                            zoom = (zoom * distance / previousDistance).coerceIn(1f, 16f)
                                            fingers.forEach { it.consume() }
                                        }
                                        previousDistance = distance
                                        previousIds = ids
                                    } else {
                                        previousDistance = 0f
                                        previousIds = emptyList()
                                    }
                                } while (event.changes.any { it.pressed })
                            }
                        }) {
                            AndroidView(
                                factory = { WaterfallView(it) },
                                update = { view ->
                                    view.setViewport(0.5f - 0.5f / zoom, 0.5f + 0.5f / zoom)
                                    view.setTuning(displayFrequencyHz.toLong(), visibleSpanHz)
                                    val values = waterfallState.value
                                    if (values.isNotEmpty()) view.updateSpectrum(values, spectrumCenterFrequencyHz)
                                },
                                modifier = Modifier.fillMaxSize().padding(start = 31.dp)
                            )
                            Canvas(Modifier.fillMaxSize().padding(start = 31.dp).clipToBounds()) {
                                val center = size.width * (0.5f + channelOffsetHz.toFloat() / visibleSpanHz)
                                val channelWidth = size.width * (receiveBandwidthHz.toFloat() / visibleSpanHz)
                                val passbandCenter = size.width * (0.5f + passbandOffsetHz.toFloat() / visibleSpanHz)
                                val channelLeft = passbandCenter - channelWidth / 2f
                                val channelRight = passbandCenter + channelWidth / 2f
                                drawRect(
                                    Brush.horizontalGradient(
                                        listOf(Color(0x14F7BA6D), Color(0x33F7BA6D), Color(0x14F7BA6D)),
                                        startX = channelLeft, endX = channelRight
                                    ),
                                    Offset(channelLeft, 0f), Size(channelWidth, size.height)
                                )
                                drawLine(Color(0x80F7BA6D), Offset(channelLeft, 0f), Offset(channelLeft, size.height), 1.dp.toPx())
                                drawLine(Color(0x80F7BA6D), Offset(channelRight, 0f), Offset(channelRight, size.height), 1.dp.toPx())
                                drawLine(Color(0xC9FFC073), Offset(center, 0f), Offset(center, size.height), 1.dp.toPx())
                            }
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
                if (selected == "Settings") {
                    Column(
                        Modifier.align(Alignment.Center).width(280.dp)
                            .border(1.dp, Color(0xFF76502F), RoundedCornerShape(6.dp))
                            .background(Color(0xF217191A), RoundedCornerShape(6.dp))
                            .padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(7.dp)
                    ) {
                        Text("DISPLAY SETTINGS", color = warm, fontSize = 14.sp, letterSpacing = 1.sp)
                        Text("Spectrum  30 FPS · actual $actualSpectrumFps", color = ivory, fontSize = 12.sp)
                        Text(
                            "Waterfall  ${waterfallFrameRate.framesPerSecond} FPS · actual $actualWaterfallFps  ⌄",
                            Modifier.clickable {
                                onWaterfallFrameRateChange(if (waterfallFrameRate == WaterfallFrameRate.FPS_30) WaterfallFrameRate.FPS_60 else WaterfallFrameRate.FPS_30)
                            }, color = warm, fontSize = 12.sp
                        )
                        Text(if (connected && permitted) "RTL-SDR V4 connected" else "RTL-SDR V4 disconnected", color = muted, fontSize = 12.sp)
                    }
                }
            }
            Row(Modifier.fillMaxWidth().height(86.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                ControlCard(Modifier.weight(1.13f).fillMaxHeight()) {
                    Text("RECEIVER", color = muted, fontSize = 11.sp, letterSpacing = 1.sp)
                    Row(Modifier.fillMaxWidth().height(24.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(if (streaming) "ON AIR" else "READY", color = if (streaming) warm else ivory, fontSize = 18.sp)
                        Box(Modifier.weight(1f))
                        Text("%.0f dBFS".format(Locale.US, signalDbfs), color = muted, fontSize = 13.sp)
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                        if (permitted && !streaming) {
                            SmallAction("USB TEST", !streamPending, Modifier.weight(1f)) { onCheckConnection() }
                            SmallAction("IQ TEST", !streamPending, Modifier.weight(1f)) { onCaptureIq() }
                        } else {
                            Text(
                                if (streaming) "${demodulationMode.label}  •  2.4 MSPS" else "RTL-SDR V4 waiting for USB",
                                color = muted, fontSize = 12.sp
                            )
                        }
                    }
                }
                ControlCard(Modifier.weight(0.98f).fillMaxHeight()) {
                    Text("DISPLAY", color = muted, fontSize = 11.sp, letterSpacing = 1.sp)
                    Row(Modifier.fillMaxWidth().height(24.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Text("Spectrum", color = ivory, fontSize = 13.sp)
                        Text("30 FPS", color = warm, fontSize = 13.sp)
                    }
                    Row(
                        Modifier.fillMaxWidth().height(24.dp).clickable {
                            onWaterfallFrameRateChange(if (waterfallFrameRate == WaterfallFrameRate.FPS_30) WaterfallFrameRate.FPS_60 else WaterfallFrameRate.FPS_30)
                        },
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Waterfall", color = ivory, fontSize = 13.sp)
                        Box(Modifier.weight(1f))
                        Text("${waterfallFrameRate.framesPerSecond} FPS  ⌄", color = warm, fontSize = 13.sp)
                    }
                }
                ControlCard(Modifier.weight(0.95f).fillMaxHeight()) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
                        TubeIcon(Modifier.width(30.dp).height(47.dp))
                        Column(Modifier.padding(start = 8.dp)) {
                            Text("RADIO CONNECTS PEOPLE", color = warm, fontSize = 11.sp, letterSpacing = 0.3.sp, maxLines = 1)
                            Text("${demodulationMode.label}  •  ${if (demodulationMode == DemodulationMode.WFM && wfmStereoEnabled && wfmStereoLocked) "STEREO" else "MONO"}  •  48 kHz", color = ivory, fontSize = 12.sp, maxLines = 1)
                            Text("Volume: phone buttons", color = muted, fontSize = 11.sp, maxLines = 1)
                        }
                    }
                    Text(
                        usbCheckResult ?: deviceName,
                        color = muted, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis
                    )
                }
            }
                }
                VerticalEncoder(
                    onStep = onStep,
                    tuningStep = tuningStep,
                    onTuningStepChange = onTuningStepChange,
                    modifier = Modifier.align(Alignment.CenterEnd).width(74.dp).fillMaxHeight()
                )
            }
        }
    }
}

@Composable
private fun Sidebar(selected: String, onSelect: (String) -> Unit) {
    Column(Modifier.width(108.dp).fillMaxHeight().background(Color(0xFF171819))) {
        Text("SDR", Modifier.padding(start = 13.dp, top = 8.dp), color = warm, fontSize = 31.sp, fontWeight = FontWeight.Light, letterSpacing = 2.sp)
        Text("GOOD SIGNALS", Modifier.padding(start = 13.dp, top = 1.dp, bottom = 19.dp), color = muted, fontSize = 11.sp, letterSpacing = 0.2.sp)
        listOf("Receiver" to "◉", "Spectrum" to "▥", "Waterfall" to "≋", "Bookmarks" to "☆", "Record" to "○", "Settings" to "⚙").forEach { (name, icon) ->
            val available = name in setOf("Receiver", "Spectrum", "Waterfall", "Settings")
            Row(
                Modifier.fillMaxWidth().height(36.dp)
                    .background(if (selected == name) Color(0xFF35291D) else Color.Transparent)
                    .clickable(enabled = available) { onSelect(name) }
                    .padding(start = 11.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(icon, Modifier.width(22.dp), color = if (selected == name) warm else muted, fontSize = 19.sp)
                Text(name, Modifier.padding(start = 8.dp), color = if (available) ivory else Color(0xFF5A5753), fontSize = 12.sp, maxLines = 1)
            }
        }
        Box(Modifier.weight(1f))
        Text("Good signals\nbetter days", Modifier.padding(start = 12.dp, bottom = 10.dp), color = warm.copy(alpha = 0.8f), fontSize = 12.sp, lineHeight = 15.sp)
    }
}

@Composable
private fun GainPanel(gain: ReceiverGain, gainSteps: List<Int>, onChange: (ReceiverGain) -> Unit, modifier: Modifier = Modifier) {
    val currentGain by rememberUpdatedState(gain)
    val currentChange by rememberUpdatedState(onChange)
    val index = gainSteps.indices.minByOrNull { kotlin.math.abs(gainSteps[it] - gain.gainTenthsDb) } ?: 0
    Column(modifier) {
        Row(Modifier.fillMaxWidth().height(24.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            AgcToggle("RF AGC", gain.rfAgc, Modifier.weight(0.85f)) { onChange(gain.copy(rfAgc = !gain.rfAgc)) }
            AgcToggle("AUDIO AGC", gain.audioAgc, Modifier.weight(1f)) { onChange(gain.copy(audioAgc = !gain.audioAgc)) }
            AgcToggle("BIAS-T", gain.biasT, Modifier.weight(0.85f)) { onChange(gain.copy(biasT = !gain.biasT)) }
        }
        Row(Modifier.fillMaxWidth().height(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("GAIN", color = muted, fontSize = 10.sp)
            Box(Modifier.weight(1f))
            Text(if (gain.rfAgc) "AUTO" else "%.1f dB".format(Locale.US, gain.gainTenthsDb / 10f), color = if (gain.rfAgc) muted else warm, fontSize = 10.sp)
        }
        Canvas(
            Modifier.fillMaxWidth().height(18.dp)
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
        modifier.fillMaxHeight().border(1.dp, if (enabled) Color(0xFF8A5A2D) else edge, RoundedCornerShape(4.dp))
            .background(if (enabled) Color(0xFF3C2A1D) else surface, RoundedCornerShape(4.dp))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text("$label ${if (enabled) "ON" else "OFF"}", color = if (enabled) warm else muted, fontSize = 9.sp, maxLines = 1)
    }
}

@Composable
private fun VerticalEncoder(
    onStep: (Long) -> Unit,
    tuningStep: TuningStep,
    onTuningStepChange: (TuningStep) -> Unit,
    modifier: Modifier = Modifier
) {
    val currentStep by rememberUpdatedState(onStep)
    val currentMode by rememberUpdatedState(tuningStep)
    var menuExpanded by remember { mutableStateOf(false) }
    var activeStepHz by remember { mutableStateOf(1_000L) }
    var travel by remember { mutableStateOf(0f) }
    var phase by remember { mutableStateOf(0f) }
    val density = LocalDensity.current
    val pitch = with(density) { 17.dp.toPx() }
    val notch = with(density) { 18.dp.toPx() }
    val scope = rememberCoroutineScope()
    var flingJob by remember { mutableStateOf<Job?>(null) }
    var idleJob by remember { mutableStateOf<Job?>(null) }
    fun rotate(amount: Float, speed: Float) {
        val stepHz = currentMode.hzForSpeed(speed / density.density, activeStepHz)
        activeStepHz = stepHz
        travel += amount
        phase = (phase - amount) % pitch
        val steps = (travel / notch).toInt()
        if (steps != 0) {
            travel -= steps * notch
            currentStep(steps * stepHz)
        }
    }
    Column(
        modifier.border(1.dp, edge, RoundedCornerShape(6.dp))
            .background(surface, RoundedCornerShape(6.dp))
            .pointerInput(Unit) {
                var tracker = VelocityTracker()
                var filteredSpeed = 0f
                var lastEventTime = 0L
                detectVerticalDragGestures(
                    onDragStart = {
                        flingJob?.cancel()
                        idleJob?.cancel()
                        activeStepHz = currentMode.fixedHz ?: 1_000L
                        tracker = VelocityTracker()
                        filteredSpeed = 0f
                        travel = 0f
                        lastEventTime = android.os.SystemClock.uptimeMillis()
                        tracker.addPosition(lastEventTime, it)
                    },
                    onDragCancel = {
                        idleJob?.cancel()
                        flingJob?.cancel()
                        activeStepHz = currentMode.fixedHz ?: 1_000L
                        travel = 0f
                    },
                    onDragEnd = {
                        idleJob?.cancel()
                        val rawVelocity = -tracker.calculateVelocity().y
                        val limit = 4500f * density.density
                        // A short flick must retain its release speed: the drag smoothing
                        // can still be catching up when the finger leaves the screen.
                        var velocity = rawVelocity.coerceIn(-limit, limit)
                        if (abs(velocity) > 120f * density.density) {
                            flingJob = scope.launch {
                                var previousTime = withFrameNanos { it }
                                while (abs(velocity) > 20f * density.density) {
                                    val now = withFrameNanos { it }
                                    val dt = ((now - previousTime) / 1_000_000_000f).coerceIn(0f, 0.05f)
                                    previousTime = now
                                    rotate(velocity * dt, abs(velocity))
                                    velocity *= exp(-1.8f * dt)
                                }
                                travel = 0f
                                activeStepHz = currentMode.fixedHz ?: 1_000L
                            }
                        } else {
                            activeStepHz = currentMode.fixedHz ?: 1_000L
                        }
                    },
                    onVerticalDrag = { change, dragAmount ->
                        change.consume()
                        tracker.addPosition(change.uptimeMillis, change.position)
                        val speed = abs(tracker.calculateVelocity().y).coerceAtMost(4500f * density.density)
                        val dt = ((change.uptimeMillis - lastEventTime) / 1000f).coerceIn(0f, 0.1f)
                        lastEventTime = change.uptimeMillis
                        val blend = 1f - exp(-dt / 0.08f)
                        filteredSpeed += blend * (speed - filteredSpeed)
                        rotate(-dragAmount, filteredSpeed)
                        idleJob?.cancel()
                        val position = change.position
                        idleJob = scope.launch {
                            delay(150)
                            filteredSpeed = 0f
                            travel = 0f
                            activeStepHz = currentMode.fixedHz ?: 1_000L
                            tracker = VelocityTracker()
                            lastEventTime = android.os.SystemClock.uptimeMillis()
                            tracker.addPosition(lastEventTime, position)
                        }
                    }
                )
            }
            .padding(horizontal = 7.dp, vertical = 8.dp),
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
        Canvas(Modifier.weight(1f).fillMaxWidth().padding(vertical = 8.dp)) {
            val radius = 11.dp.toPx()
            drawRoundRect(
                brush = Brush.horizontalGradient(listOf(Color(0xFF151515), Color(0xFF4E4031), Color(0xFF171717))),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(radius)
            )
            clipRect(top = 0f, bottom = size.height) {
                val gap = pitch
                var y = phase % gap
                while (y < size.height) {
                    drawLine(Color(0xFF080808), Offset(6.dp.toPx(), y), Offset(size.width - 6.dp.toPx(), y), 3.dp.toPx())
                    drawLine(Color(0xFF91795C), Offset(8.dp.toPx(), y + 3.dp.toPx()), Offset(size.width - 8.dp.toPx(), y + 3.dp.toPx()), 1.dp.toPx())
                    y += gap
                }
            }
            drawLine(warm, Offset(0f, size.height / 2), Offset(size.width, size.height / 2), 2.dp.toPx())
        }
        Text("▲  ▼", color = warm, fontSize = 11.sp)
    }
}

@Composable
private fun BatteryIndicator(percent: Int, charging: Boolean, modifier: Modifier = Modifier) {
    val tint = if (percent in 0..20 && !charging) Color(0xFFFF8D55) else muted
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
        Text(if (percent < 0) "—%" else "$percent%", color = tint, fontSize = 11.sp, maxLines = 1)
    }
}

@Composable
private fun SignalMeter(
    dbfs: Float, squelch: ReceiverSquelch, open: Boolean,
    onSquelchChange: (ReceiverSquelch) -> Unit, modifier: Modifier = Modifier
) {
    val currentSquelch by rememberUpdatedState(squelch)
    val changeSquelch by rememberUpdatedState(onSquelchChange)
    var meterWidth by remember { mutableStateOf(1) }
    val density = LocalDensity.current
    fun setThreshold(x: Float) {
        val left = with(density) { 10.dp.toPx() }
        val right = maxOf(left + 1f, meterWidth - with(density) { 86.dp.toPx() })
        val db = (-90f + 84f * ((x - left) / (right - left)).coerceIn(0f, 1f)).roundToInt().toFloat()
        changeSquelch(currentSquelch.copy(thresholdDbfs = db))
    }
    // Relative display only: raw dBFS is not calibrated antenna power.
    val level = (dbfs + 90f).coerceIn(0f, 84f)
    val reading = if (level <= 54f) "≈S${(level / 6f).roundToInt()}"
        else "≈S9 +${(level - 54f).roundToInt()}"
    val labelPaint = remember {
        android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            textAlign = android.graphics.Paint.Align.CENTER
        }
    }
    Row(modifier.border(1.dp, edge, RoundedCornerShape(5.dp))
        .background(surface, RoundedCornerShape(5.dp)), verticalAlignment = Alignment.CenterVertically) {
        Canvas(
            Modifier.weight(1f).fillMaxHeight().onSizeChanged { meterWidth = it.width }
                .pointerInput(Unit) { detectTapGestures(onTap = { setThreshold(it.x) }) }
                .pointerInput(Unit) {
                    detectDragGestures(
                        onDragStart = { setThreshold(it.x) },
                        onDrag = { change, _ -> change.consume(); setThreshold(change.position.x) }
                    )
                }
                .semantics {
                    progressBarRangeInfo = ProgressBarRangeInfo(squelch.thresholdDbfs, -90f..-6f, 83)
                    setProgress { changeSquelch(currentSquelch.copy(thresholdDbfs = it.coerceIn(-90f, -6f))); true }
                }
                .background(surface, RoundedCornerShape(5.dp))
                .semantics { contentDescription = "Relative signal $reading, ${dbfs.roundToInt()} dBFS" }
        ) {
            val left = 10.dp.toPx()
            val right = maxOf(left + 1f, size.width - 86.dp.toPx())
            val width = right - left
            val barTop = size.height - 11.dp.toPx()
            val barHeight = 6.dp.toPx()
            val labelY = 13.dp.toPx()
            val ticks = listOf(0f to "0", 6f to "1", 18f to "3", 30f to "5", 42f to "7", 54f to "9", 64f to "+10", 74f to "+20", 84f to "+30")
            labelPaint.textSize = 10.sp.toPx()
            for ((value, label) in ticks) {
                val x = left + width * value / 84f
                labelPaint.color = if (value > 54f) android.graphics.Color.rgb(255, 141, 85)
                    else android.graphics.Color.rgb(212, 194, 169)
                drawContext.canvas.nativeCanvas.drawText(label, x, labelY, labelPaint)
                drawLine(edge, Offset(x, labelY + 2.dp.toPx()), Offset(x, barTop - 1.dp.toPx()), 1.dp.toPx())
            }
            val segments = 42
            val gap = 1.dp.toPx()
            val segmentWidth = maxOf(1f, (width - gap * (segments - 1)) / segments)
            for (index in 0 until segments) {
                val threshold = (index + 1) * 84f / segments
                drawRect(
                    if (level < threshold) Color(0xFF35302B)
                    else if (threshold > 54f) Color(0xFFFF8D55) else warm,
                    Offset(left + index * (segmentWidth + gap), barTop), Size(segmentWidth, barHeight)
                )
            }
            val cursorX = left + width * level / 84f
            drawLine(ivory, Offset(cursorX, barTop - 2.dp.toPx()), Offset(cursorX, barTop + barHeight + 2.dp.toPx()), 1.dp.toPx())
            val sqlX = left + width * (squelch.thresholdDbfs + 90f) / 84f
            val sqlColor = if (!squelch.enabled) muted.copy(alpha = 0.6f) else if (open) warm else ivory
            drawLine(sqlColor, Offset(sqlX, barTop - 3.dp.toPx()), Offset(sqlX, size.height), 1.5.dp.toPx())
            val triangle = Path().apply {
                moveTo(sqlX, barTop - 1.dp.toPx())
                lineTo(sqlX - 4.dp.toPx(), barTop - 6.dp.toPx())
                lineTo(sqlX + 4.dp.toPx(), barTop - 6.dp.toPx())
                close()
            }
            drawPath(triangle, sqlColor)
            val readoutX = right + (size.width - right) / 2f
            labelPaint.color = android.graphics.Color.rgb(247, 186, 109)
            labelPaint.textSize = 14.sp.toPx()
            labelPaint.isFakeBoldText = true
            drawContext.canvas.nativeCanvas.drawText(reading, readoutX, 15.dp.toPx(), labelPaint)
            labelPaint.isFakeBoldText = false
            labelPaint.color = android.graphics.Color.rgb(143, 136, 127)
            labelPaint.textSize = 9.sp.toPx()
            drawContext.canvas.nativeCanvas.drawText("SQL %.0f dB".format(Locale.US, squelch.thresholdDbfs), readoutX, size.height - 5.dp.toPx(), labelPaint)
        }
        Column(
            Modifier.width(46.dp).fillMaxHeight()
                .background(if (squelch.enabled) Color(0xFF3C2A1D) else surface, RoundedCornerShape(5.dp))
                .clickable { onSquelchChange(squelch.copy(enabled = !squelch.enabled)) }
                .semantics { contentDescription = "Squelch ${if (squelch.enabled) "on" else "off"}" },
            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center
        ) {
            Text("SQL", color = if (squelch.enabled) warm else muted, fontSize = 11.sp, fontWeight = FontWeight.Bold)
            Text(if (!squelch.enabled) "OFF" else if (open) "OPEN" else "MUTE",
                color = if (squelch.enabled && open) warm else muted, fontSize = 8.sp)
        }
    }
}
@Composable
private fun StereoToggle(available: Boolean, enabled: Boolean, locked: Boolean, onChange: (Boolean) -> Unit) {
    val active = available && enabled
    Column(
        Modifier.width(60.dp).height(36.dp)
            .border(1.dp, if (active) Color(0xFF8A5A2D) else edge, RoundedCornerShape(5.dp))
            .background(if (active) Color(0xFF3C2A1D) else surface, RoundedCornerShape(5.dp))
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
private fun ModePill(label: String, active: Boolean, onClick: (() -> Unit)? = null) {
    Box(
        Modifier.width(41.dp).height(36.dp)
            .border(1.dp, if (active) Color(0xFF8A5A2D) else edge, RoundedCornerShape(5.dp))
            .background(if (active) Color(0xFF3C2A1D) else surface, RoundedCornerShape(5.dp))
            .clickable(enabled = onClick != null) { onClick?.invoke() },
        contentAlignment = Alignment.Center
    ) {
        Text(label, color = if (active) warm else if (onClick != null) ivory else Color(0xFF665F58), fontSize = 12.sp)
    }
}

@Composable
private fun ControlCard(modifier: Modifier, content: @Composable () -> Unit) {
    Column(
        modifier.border(1.dp, edge, RoundedCornerShape(6.dp))
            .background(surface, RoundedCornerShape(6.dp)).padding(horizontal = 10.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.SpaceBetween,
        content = { content() }
    )
}

@Composable
private fun SmallAction(label: String, enabled: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Box(
        modifier.height(28.dp).border(1.dp, if (enabled) Color(0xFF9E6D3B) else edge, RoundedCornerShape(4.dp))
            .background(if (enabled) Color(0xFF332417) else surface, RoundedCornerShape(4.dp))
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(label, color = if (enabled) warm else muted, fontSize = 12.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun TubeIcon(modifier: Modifier = Modifier) {
    Canvas(modifier) {
        drawCircle(brush = Brush.radialGradient(listOf(Color(0x88FF9D32), Color.Transparent)), radius = size.width * 0.75f)
        drawRoundRect(Color(0xFF674526), Offset(size.width * 0.25f, size.height * 0.1f), Size(size.width * 0.5f, size.height * 0.76f), cornerRadius = androidx.compose.ui.geometry.CornerRadius(8f), style = Stroke(1.dp.toPx()))
        drawLine(warm, Offset(size.width * 0.5f, size.height * 0.28f), Offset(size.width * 0.5f, size.height * 0.69f), 2.dp.toPx())
        drawCircle(warm, radius = 3.dp.toPx(), center = Offset(size.width * 0.5f, size.height * 0.47f))
    }
}

@Composable
private fun AmberSpectrum(
    valuesState: State<FloatArray>,
    spectrumSpanHz: Int,
    receiveBandwidthHz: Int,
    channelOffsetHz: Long,
    passbandOffsetHz: Int,
    viewportStart: Float,
    viewportEnd: Float,
    modifier: Modifier = Modifier
) {
    val axisPaint = remember {
        android.graphics.Paint().apply {
            color = android.graphics.Color.rgb(143, 136, 127)
            isAntiAlias = true
        }
    }
    Canvas(modifier) {
        val left = 31.dp.toPx()
        val top = 20.dp.toPx()
        val bottom = size.height
        val width = size.width - left
        val height = bottom - top
        for (step in 0..3) {
            val y = top + height * step / 3f
            drawLine(Color(0xFF252524), Offset(left, y), Offset(size.width, y), 1f)
        }
        for (step in 0..8) {
            val x = left + width * step / 8f
            drawLine(Color(0xFF222322), Offset(x, top), Offset(x, bottom), 1f)
        }
        axisPaint.textSize = 11.sp.toPx()
        for (step in 0..3) {
            drawContext.canvas.nativeCanvas.drawText(
                "${-40 * step}", 2.dp.toPx(), top + height * step / 3f + 3.dp.toPx(),
                axisPaint
            )
        }
        val values = valuesState.value
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
                val y = bottom - ((db + 120f) / 120f).coerceIn(0f, 1f) * height
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
            drawPath(fill, Brush.verticalGradient(listOf(Color(0xB3EEA54E), Color(0x665C3D20), Color.Transparent), startY = top, endY = bottom))
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
                listOf(Color(0x19F7BA6D), Color(0x38F7BA6D), Color(0x19F7BA6D)),
                startX = channelLeft, endX = channelRight
            ),
            Offset(channelLeft, top), Size(channelWidth, height)
        )
        drawLine(Color(0x80F7BA6D), Offset(channelLeft, top), Offset(channelLeft, bottom), 1.dp.toPx())
        drawLine(Color(0x80F7BA6D), Offset(channelRight, top), Offset(channelRight, bottom), 1.dp.toPx())
        drawLine(Color(0xC9FFC073), Offset(center, top), Offset(center, bottom), 1.dp.toPx())
        }
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
        spectrumCenterFrequencyHz = 100_500_000L,
        spectrumState = remember { mutableStateOf(FloatArray(1024) { -95f + if (it in 490..530) 50f else 0f }) },
        waterfallState = remember { mutableStateOf(FloatArray(1024) { -95f + if (it in 490..530) 50f else 0f }) },
        waterfallFrameRate = WaterfallFrameRate.FPS_30, actualSpectrumFps = 30, actualWaterfallFps = 30,
        signalDbfs = -45f,
        onRequestPermission = {}, onCheckConnection = {}, onCaptureIq = {}, onStartStream = {}, onStopStream = {},
        onFrequencyInput = {}, onTune = {}, onStep = {}, onTuningStepChange = {}, onWaterfallFrameRateChange = {},
        tuningStep = TuningStep.KHZ_100, demodulationMode = DemodulationMode.WFM,
        onDemodulationModeChange = {}, receiverGain = ReceiverGain(), gainSteps = ReceiverGain.defaultSteps,
        onReceiverGainChange = {}
    )
}
