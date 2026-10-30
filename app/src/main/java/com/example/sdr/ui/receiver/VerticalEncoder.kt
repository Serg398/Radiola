package com.example.sdr.ui.receiver

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.sdr.receiver.TuningStep
import com.example.sdr.ui.theme.RadiolaColors
import kotlin.math.abs
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
internal fun VerticalEncoder(
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
        modifier.border(1.dp, RadiolaColors.border, RoundedCornerShape(6.dp))
            .background(RadiolaColors.surface, RoundedCornerShape(6.dp))
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
                color = RadiolaColors.primary, fontSize = 10.sp, lineHeight = 11.sp
            )
            DropdownMenu(
                expanded = menuExpanded,
                onDismissRequest = { menuExpanded = false },
                modifier = Modifier.background(RadiolaColors.surface).width(132.dp),
                containerColor = RadiolaColors.surface
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
                        Text(option.label, color = if (option == tuningStep) RadiolaColors.primary else RadiolaColors.text, fontSize = 13.sp)
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
                // A continuous barrel RadiolaColors.surface, with the centre catching the light.
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
            drawLine(RadiolaColors.primary.copy(alpha = .15f), Offset(3.dp.toPx(), middle),
                Offset(size.width - 3.dp.toPx(), middle), 5.dp.toPx(), cap = StrokeCap.Round)
            drawLine(RadiolaColors.primary, Offset(2.dp.toPx(), middle), Offset(size.width - 2.dp.toPx(), middle),
                1.5.dp.toPx(), cap = StrokeCap.Round)
            drawCircle(RadiolaColors.primary, 2.dp.toPx(), Offset(3.dp.toPx(), middle))
            drawCircle(RadiolaColors.primary, 2.dp.toPx(), Offset(size.width - 3.dp.toPx(), middle))
        }
        Text("▲  ▼", color = RadiolaColors.primary, fontSize = 11.sp)
    }
}
