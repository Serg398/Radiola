package com.example.sdr

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun BandwidthControl(mode: DemodulationMode, hz: Int, isDefault: Boolean, onChange: (Int?) -> Unit,
    options: BandwidthOptions = BandwidthOptions.forMode(mode)) {
    val warm = RadiolaColors.primary
    val muted = RadiolaColors.secondaryText
    val surface = RadiolaColors.surface
    var expanded by remember(mode) { mutableStateOf(false) }
    var custom by remember(mode) { mutableStateOf(false) }
    var input by remember(mode) { mutableStateOf(TextFieldValue("")) }
    val focus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    Box {
        Column(Modifier.width(68.dp).height(28.dp)
            .background(surface, RoundedCornerShape(5.dp)).clickable { expanded = true },
            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            Text("BW", color = muted, fontSize = 8.sp)
            Text(bandwidthLabel(hz), color = warm, fontSize = 11.sp, maxLines = 1)
        }
        DropdownMenu(expanded, { expanded = false }, containerColor = surface) {
            DropdownMenuItem(text = { Text("Default (${bandwidthLabel(options.defaultHz)})", color = if (isDefault) warm else muted) },
                onClick = { onChange(null); expanded = false })
            options.presets.forEach { value ->
                DropdownMenuItem(text = { Text(bandwidthLabel(value), color = if (!isDefault && hz == value) warm else muted) },
                    onClick = { onChange(value); expanded = false })
            }
            DropdownMenuItem(text = { Text("Custom...", color = warm) }, onClick = {
                val text = (hz / 1000.0).toString()
                input = TextFieldValue(text, selection = TextRange(0, text.length))
                expanded = false; custom = true
            })
        }
    }
    if (custom) {
        LaunchedEffect(Unit) { focus.requestFocus() }
        val value = input.text.replace(',', '.').toDoubleOrNull()?.times(1000)
            ?.takeIf { it.isFinite() && it >= options.minimumHz && it <= options.maximumHz }?.toInt()
        AlertDialog(onDismissRequest = { custom = false }, containerColor = surface,
            title = { Text("${mode.label} bandwidth", color = warm) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("${bandwidthLabel(options.minimumHz)} — ${bandwidthLabel(options.maximumHz)}", color = muted)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        BasicTextField(input, { next ->
                            if (next.text.all { it.isDigit() || it == '.' || it == ',' } &&
                                next.text.count { it == '.' || it == ',' } <= 1) input = next
                        }, singleLine = true,
                            cursorBrush = SolidColor(warm),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Done),
                            keyboardActions = KeyboardActions(onDone = {
                                value?.let { onChange(it); keyboard?.hide(); custom = false }
                            }),
                            textStyle = TextStyle(color = warm, fontSize = 22.sp),
                            modifier = Modifier.weight(1f).focusRequester(focus).border(1.dp, muted, RoundedCornerShape(4.dp)).padding(10.dp))
                        Text("kHz", Modifier.padding(start = 10.dp), color = muted)
                    }
                }
            },
            confirmButton = { TextButton(enabled = value != null, onClick = { value?.let(onChange); custom = false }) { Text("Apply", color = if (value != null) warm else muted) } },
            dismissButton = { TextButton(onClick = { custom = false }) { Text("Cancel", color = muted) } })
    }
}
