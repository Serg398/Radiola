package com.example.sdr

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** The existing modulation card area, populated by the selected server decoder. */
@Composable
internal fun ServerDecodedInformationPanel(label: String, text: String, onClear: () -> Unit) {
    Column(Modifier.fillMaxWidth().height(144.dp)
        .background(RadiolaColors.surface, RoundedCornerShape(6.dp))
        .border(1.dp, RadiolaColors.border, RoundedCornerShape(6.dp)).padding(horizontal = 10.dp, vertical = 6.dp)) {
        Row(Modifier.fillMaxWidth().height(28.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(label, color = RadiolaColors.primary, fontSize = 16.sp)
            Spacer(Modifier.width(12.dp))
            Text("SERVER", color = RadiolaColors.secondaryText, fontSize = 9.sp)
            Spacer(Modifier.weight(1f))
            TextButton(onClick = onClear, contentPadding = PaddingValues(0.dp)) {
                Text("CLEAR", color = RadiolaColors.secondaryText, fontSize = 10.sp)
            }
        }
        Text(text, Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState()),
            color = RadiolaColors.text, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
    }
}
