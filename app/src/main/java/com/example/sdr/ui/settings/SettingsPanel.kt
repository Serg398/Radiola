package com.example.sdr.ui.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.sdr.DemodulationMode
import com.example.sdr.R
import com.example.sdr.platform.EncoderFeedback
import com.example.sdr.receiver.ReceiverGain
import com.example.sdr.receiver.ReceiverSquelch
import com.example.sdr.receiver.TuningStep
import com.example.sdr.ui.theme.RadiolaColors
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

private val settingsEdge get() = RadiolaColors.border
private val settingsWarm get() = RadiolaColors.primary
private val settingsIvory get() = RadiolaColors.text
private val settingsMuted get() = RadiolaColors.secondaryText

private enum class SettingsPage(val title: String) {
    ROOT("Settings"), RECEIVER("Receiver"), GAIN("Gain controls"), SQL("Squelch"),
    AUDIO("Audio"), DISPLAY("Display"), BACKLIGHT("Backlight"),
    ENCODER("Encoder"), STEP("Tuning step"), FEEDBACK("Tactile feedback"),
    DEVICE("Device"), ABOUT("About"), THEME("Theme")
}

@Composable
internal fun SettingsPanel(
    gain: ReceiverGain, gainSteps: List<Int>, onGainChange: (ReceiverGain) -> Unit,
    squelch: ReceiverSquelch, onSquelchChange: (ReceiverSquelch) -> Unit,
    mode: DemodulationMode, stereo: Boolean, onStereoChange: (Boolean) -> Unit,
    tuningStep: TuningStep, onStepChange: (TuningStep) -> Unit,
    feedback: EncoderFeedback, onFeedbackChange: (EncoderFeedback) -> Unit,
    screenAlwaysOn: Boolean, onScreenAlwaysOnChange: (Boolean) -> Unit,
    deviceName: String, connected: Boolean, permitted: Boolean, streaming: Boolean, pending: Boolean,
    onPermission: () -> Unit, onUsbTest: () -> Unit, onIqTest: () -> Unit, onClose: () -> Unit,
    modifier: Modifier = Modifier
) {
    var path by remember { mutableStateOf(listOf(SettingsPage.ROOT)) }
    val page = path.last()
    fun back() { if (path.size > 1) path = path.dropLast(1) else onClose() }
    fun open(next: SettingsPage) { path = path + next }
    BackHandler { back() }
    Column(modifier.fillMaxSize().border(1.dp, settingsEdge, RoundedCornerShape(6.dp))
        .background(RadiolaColors.surface, RoundedCornerShape(6.dp)).padding(12.dp)) {
        Row(Modifier.fillMaxWidth().height(48.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            val backDescription = if (path.size > 1) "Back to ${path[path.lastIndex - 1].title}" else "Back to receiver"
            val arrowColor = settingsMuted
            Box(Modifier.size(48.dp).semantics { contentDescription = backDescription }
                .clickable(role = Role.Button, onClick = ::back), contentAlignment = Alignment.Center) {
                Canvas(Modifier.size(22.dp)) {
                    val stroke = 1.8.dp.toPx()
                    drawLine(arrowColor, Offset(size.width * .8f, size.height * .5f), Offset(size.width * .2f, size.height * .5f), stroke, StrokeCap.Round)
                    drawLine(arrowColor, Offset(size.width * .2f, size.height * .5f), Offset(size.width * .5f, size.height * .2f), stroke, StrokeCap.Round)
                    drawLine(arrowColor, Offset(size.width * .2f, size.height * .5f), Offset(size.width * .5f, size.height * .8f), stroke, StrokeCap.Round)
                }
            }
            Text(page.title, color = settingsIvory, fontSize = 22.sp, fontWeight = FontWeight.Normal)
        }
        Spacer(Modifier.height(8.dp))
        key(page) {
            Column(Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(0.dp)) {
                when (page) {
                    SettingsPage.ROOT -> {
                        val sections = listOf(
                            Triple(SettingsPage.ENCODER, "Encoder", "Tuning step and tactile feedback"),
                            Triple(SettingsPage.DISPLAY, "Display", "Theme and backlight"),
                            Triple(SettingsPage.ABOUT, "About", stringResource(R.string.app_name))
                        )
                        sections.forEach { (next, title, subtitle) ->
                            SettingsEntry(title, subtitle) { open(next) }
                        }
                    }
                    SettingsPage.THEME -> {
                        val themeContext = LocalContext.current
                        SettingsChoice("Light", !RadiolaColors.dark) { RadiolaColors.select(false, themeContext) }
                        SettingsChoice("Dark", RadiolaColors.dark) { RadiolaColors.select(true, themeContext) }
                    }
                    SettingsPage.RECEIVER -> {
                        SettingsEntry("Gain controls", "RF AGC, manual gain and bias-T") { open(SettingsPage.GAIN) }
                        SettingsEntry("Squelch", if (squelch.enabled) "ON \u00b7 ${squelch.thresholdDbfs.roundToInt()} dBFS" else "OFF") { open(SettingsPage.SQL) }
                    }
                    SettingsPage.GAIN -> {
                        SettingsToggle("RF AGC", "Automatic tuner gain", gain.rfAgc) { onGainChange(gain.copy(rfAgc = it)) }
                        if (gainSteps.isNotEmpty()) {
                            val index = gainSteps.indices.minByOrNull { abs(gainSteps[it] - gain.gainTenthsDb) } ?: 0
                            SettingsSlider("RF gain", if (gain.rfAgc) "AUTO" else "%.1f dB".format(Locale.US, gain.gainTenthsDb / 10f),
                                index.toFloat(), 0f..maxOf(1, gainSteps.lastIndex).toFloat(), !gain.rfAgc,
                                maxOf(0, gainSteps.size - 2)) { onGainChange(gain.copy(gainTenthsDb = gainSteps[it.roundToInt().coerceIn(gainSteps.indices)])) }
                        }
                        SettingsToggle("Bias-T", "Power for an active antenna or amplifier", gain.biasT) { onGainChange(gain.copy(biasT = it)) }
                    }
                    SettingsPage.SQL -> {
                        SettingsToggle("Squelch", "Mute audio below the selected signal level", squelch.enabled) { onSquelchChange(squelch.copy(enabled = it)) }
                        SettingsSlider("Threshold", "${squelch.thresholdDbfs.roundToInt()} dBFS", squelch.thresholdDbfs, -90f..-6f, true, 83) {
                            onSquelchChange(squelch.copy(thresholdDbfs = it.roundToInt().toFloat()))
                        }
                    }
                    SettingsPage.AUDIO -> {
                        SettingsToggle("Audio AGC", "Automatic playback level", gain.audioAgc) { onGainChange(gain.copy(audioAgc = it)) }
                        SettingsToggle("WFM stereo", "Automatic mono fallback without a stable pilot", stereo,
                            mode == DemodulationMode.WFM, onStereoChange)
                        Text("Volume is controlled with the phone's volume buttons.", color = settingsMuted, fontSize = 13.sp)
                    }
                    SettingsPage.DISPLAY -> {
                        SettingsEntry("Theme", if (RadiolaColors.dark) "Dark" else "Light") { open(SettingsPage.THEME) }
                        SettingsEntry("Backlight", if (screenAlwaysOn) "Lock" else "Auto") { open(SettingsPage.BACKLIGHT) }
                    }
                    SettingsPage.BACKLIGHT -> {
                        SettingsChoice("Auto", !screenAlwaysOn) { onScreenAlwaysOnChange(false) }
                        SettingsChoice("Lock", screenAlwaysOn) { onScreenAlwaysOnChange(true) }
                    }
                    SettingsPage.ENCODER -> {
                        SettingsEntry("Tuning step", tuningStep.label) { open(SettingsPage.STEP) }
                        SettingsEntry("Tactile feedback", feedback.label) { open(SettingsPage.FEEDBACK) }
                    }
                    SettingsPage.STEP -> {
                        TuningStep.entries.forEach { option ->
                            SettingsChoice(option.label, option == tuningStep) { onStepChange(option) }
                        }
                        Spacer(Modifier.height(10.dp))
                        Text("AUTO adjusts the step to rotation speed. Touch the wheel to stop inertia.", color = settingsMuted, fontSize = 13.sp)
                    }
                    SettingsPage.FEEDBACK -> {
                        EncoderFeedback.entries.forEach { option ->
                            SettingsChoice(option.label, option == feedback) { onFeedbackChange(option) }
                        }
                        Spacer(Modifier.height(10.dp))
                        Text("Short clicks at the wheel detents, controlled by OFF / LIGHT / MEDIUM.", color = settingsMuted, fontSize = 13.sp)
                    }
                    SettingsPage.DEVICE -> {
                        SettingsInfo("Receiver", deviceName)
                        SettingsInfo("USB", if (!connected) "Disconnected" else if (!permitted) "Permission required" else "Connected")
                        SettingsInfo("Reception", if (streaming) "Running" else if (pending) "Please wait" else "Stopped")
                        if (connected && !permitted) SettingsButton("Grant USB access", !pending, onPermission)
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            SettingsButton("USB test", connected && permitted && !streaming && !pending, onUsbTest)
                            SettingsButton("IQ test", connected && permitted && !streaming && !pending, onIqTest)
                        }
                    }
                    SettingsPage.ABOUT -> {
                        val context = LocalContext.current
                        val version = remember(context) {
                            @Suppress("DEPRECATION")
                            context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "\u2014"
                        }
                        Text(stringResource(R.string.app_name), color = settingsWarm, fontSize = 30.sp, fontWeight = FontWeight.Light)
                        SettingsInfo("Version", version)
                        SettingsInfo("Receiver", "RTL-SDR Blog V4")
                    }
                }
            }
        }
    }
}

@Composable
private fun SettingsEntry(title: String, subtitle: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Column(modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().heightIn(min = 40.dp).clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(title, color = settingsIvory, fontSize = 16.sp, modifier = Modifier.weight(1f))
            Text(subtitle, color = settingsMuted, fontSize = 12.sp, modifier = Modifier.weight(1.4f),
                textAlign = TextAlign.End, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("\u203a", color = settingsMuted, fontSize = 18.sp)
        }
        HorizontalDivider(color = settingsEdge.copy(alpha = 0.5f), thickness = 0.5.dp)
    }
}

@Composable
private fun SettingsInfo(title: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(title, color = settingsMuted, fontSize = 14.sp)
        Text(value, color = settingsIvory, fontSize = 14.sp)
    }
}

@Composable
private fun SettingsToggle(title: String, subtitle: String, value: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(enabled = enabled) { onChange(!value) }.padding(horizontal = 12.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, color = if (enabled) settingsIvory else settingsMuted, fontSize = 16.sp)
            Text(subtitle, color = settingsMuted, fontSize = 12.sp)
        }
        Text(if (value) "ON" else "OFF", color = if (value && enabled) settingsWarm else settingsMuted, fontSize = 15.sp)
    }
}

@Composable
private fun SettingsSlider(title: String, reading: String, value: Float, range: ClosedFloatingPointRange<Float>, enabled: Boolean,
    steps: Int, onChange: (Float) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 12.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(title, color = settingsIvory, fontSize = 16.sp)
            Text(reading, color = settingsWarm, fontSize = 15.sp)
        }
        Slider(value, onChange, enabled = enabled, valueRange = range, steps = steps,
            colors = SliderDefaults.colors(thumbColor = settingsWarm, activeTrackColor = settingsWarm, inactiveTrackColor = settingsEdge))
    }
}

@Composable
private fun SettingsChoice(label: String, active: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Column(modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().height(42.dp).clickable(onClick = onClick).padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Text(label, color = if (active) settingsWarm else settingsIvory, fontSize = 15.sp, modifier = Modifier.weight(1f))
            if (active) Text("\u2713", color = settingsWarm, fontSize = 17.sp)
        }
        HorizontalDivider(color = settingsEdge.copy(alpha = 0.5f), thickness = 0.5.dp)
    }
}

@Composable
private fun SettingsButton(label: String, enabled: Boolean = true, onClick: () -> Unit) {
    Box(Modifier.height(38.dp).clickable(enabled = enabled, onClick = onClick)
        .padding(horizontal = 12.dp), contentAlignment = Alignment.Center) {
        Text(label, color = if (enabled) settingsWarm else settingsMuted, fontSize = 14.sp)
    }
}
