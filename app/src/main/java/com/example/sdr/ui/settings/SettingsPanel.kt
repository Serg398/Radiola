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
import com.example.sdr.R
import com.example.sdr.platform.SignalMeterInertia
import com.example.sdr.platform.EncoderFeedback
import com.example.sdr.receiver.TuningStep
import com.example.sdr.ui.theme.RadiolaColors

private val settingsEdge get() = RadiolaColors.border
private val settingsWarm get() = RadiolaColors.primary
private val settingsIvory get() = RadiolaColors.text
private val settingsMuted get() = RadiolaColors.secondaryText

private enum class SettingsPage(val title: String) {
    ROOT("Settings"), DISPLAY("Display"), BACKLIGHT("Backlight"), SIGNAL_METER("S-meter"), METER_TYPE("Type"), METER_INERTIA("Inertia"),
    ENCODER("Encoder"), STEP("Tuning step"), FEEDBACK("Tactile feedback"),
    ABOUT("About"), THEME("Theme")
}

@Composable
internal fun SettingsPanel(
    tuningStep: TuningStep, onStepChange: (TuningStep) -> Unit,
    feedback: EncoderFeedback, onFeedbackChange: (EncoderFeedback) -> Unit,
    signalMeterInertia: SignalMeterInertia, onSignalMeterInertiaChange: (SignalMeterInertia) -> Unit,
    analogSignalMeter: Boolean, onAnalogSignalMeterChange: (Boolean) -> Unit,
    screenAlwaysOn: Boolean, onScreenAlwaysOnChange: (Boolean) -> Unit,
    onClose: () -> Unit,
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
                            Triple(SettingsPage.DISPLAY, "Display", "Theme, backlight and S-meter"),
                            Triple(SettingsPage.ABOUT, "About", stringResource(R.string.app_name))
                        )
                        sections.forEach { (next, title, subtitle) ->
                            SettingsEntry(title, subtitle) { open(next) }
                        }
                    }
                    SettingsPage.THEME -> {
                        val themeContext = LocalContext.current
                        RadiolaColors.Theme.entries.forEach { theme ->
                            SettingsChoice(theme.label, RadiolaColors.selected == theme) { RadiolaColors.select(theme, themeContext) }
                        }
                    }
                    SettingsPage.DISPLAY -> {
                        SettingsEntry("Theme", RadiolaColors.selected.label) { open(SettingsPage.THEME) }
                        SettingsEntry("Backlight", if (screenAlwaysOn) "Lock" else "Auto") { open(SettingsPage.BACKLIGHT) }
                        SettingsEntry("S-meter", if (analogSignalMeter) "Analog" else "Digital") { open(SettingsPage.SIGNAL_METER) }
                    }
                    SettingsPage.SIGNAL_METER -> {
                        SettingsEntry("Type", if (analogSignalMeter) "Analog" else "Digital") { open(SettingsPage.METER_TYPE) }
                        SettingsEntry("Inertia", signalMeterInertia.label) { open(SettingsPage.METER_INERTIA) }
                    }
                    SettingsPage.METER_TYPE -> {
                        SettingsChoice("Digital", !analogSignalMeter) { onAnalogSignalMeterChange(false) }
                        SettingsChoice("Analog", analogSignalMeter) { onAnalogSignalMeterChange(true) }
                    }
                    SettingsPage.METER_INERTIA -> {
                        SignalMeterInertia.entries.forEach { option ->
                            SettingsChoice(option.label, option == signalMeterInertia) { onSignalMeterInertiaChange(option) }
                        }
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
                    SettingsPage.ABOUT -> {
                        val context = LocalContext.current
                        val version = remember(context) {
                            @Suppress("DEPRECATION")
                            context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "\u2014"
                        }
                        Text(stringResource(R.string.app_name), color = settingsWarm, fontSize = 30.sp, fontWeight = FontWeight.Light)
                        SettingsInfo("Version", version)
                        SettingsInfo("Developer", "Serg398")
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
