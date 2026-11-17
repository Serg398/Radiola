package com.example.sdr.ui.decoders

import androidx.compose.foundation.background
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.sdr.ui.theme.RadiolaColors
import com.example.sdr.receiver.SstvImage
import com.example.sdr.receiver.AircraftReport

/** The existing modulation card area, populated by the selected server decoder. */
@Composable
internal fun ServerDecodedInformationPanel(label: String, text: String, onClear: () -> Unit,
    sstv: Boolean = false, image: SstvImage? = null,
    adsb: Boolean = false, aircraft: List<AircraftReport> = emptyList()) {
    var expanded by remember(sstv) { mutableStateOf(false) }
    DecoderCardFrame(modifier = Modifier.fillMaxWidth().height(160.dp), sidebar = {
        DecoderSidebar(label) {
            Text("SERVER", color = RadiolaColors.secondaryText, fontSize = 10.sp)
            if (adsb) {
                Text("1090 MHz", color = RadiolaColors.secondaryText, fontSize = 10.sp)
                Text("${aircraft.size} aircraft", color = RadiolaColors.secondaryText, fontSize = 10.sp)
            }
        }
    }) {
            DecoderToolbar {
                Text(if (adsb) "LIVE FEED / INDEPENDENT OF TUNING" else if (sstv) "IMAGE" else "DECODED DATA",
                    color = RadiolaColors.secondaryText, style = DecoderToolbarText,
                    maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f).alignByBaseline())
                DecoderAction("CLEAR", onClick = onClear)
            }
            DecoderDivider()
        if (sstv) {
            Row(Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically) {
                if (image != null) {
                    SstvPicture(image, Modifier.fillMaxHeight().aspectRatio(image.bitmap.width.toFloat() / image.bitmap.height)
                        .clickable { expanded = true })
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(image.mode, color = RadiolaColors.text, fontSize = 12.sp)
                        Text("${image.lines} / ${image.bitmap.height} lines", color = RadiolaColors.secondaryText, fontSize = 11.sp)
                        if (image.timestamp.isNotBlank()) Text(image.timestamp + " UTC",
                            color = RadiolaColors.secondaryText, fontSize = 10.sp)
                    }
                }
            }
        } else if (adsb) AircraftInformationPanel(aircraft, Modifier.fillMaxWidth().weight(1f))
        else Text(text, Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState()),
                color = RadiolaColors.text, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
        }
    if (expanded && image != null) Dialog(onDismissRequest = { expanded = false },
        properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(Modifier.fillMaxSize().background(Color.Black).padding(12.dp)) {
            SstvPicture(image, Modifier.fillMaxSize())
            TextButton(onClick = { expanded = false }, modifier = Modifier.align(Alignment.TopEnd)) {
                Text("CLOSE", color = Color.White)
            }
        }
    }
}

@Composable
private fun SstvPicture(image: SstvImage, modifier: Modifier) {
    // A new painter revision invalidates drawing after each in-place bitmap update.
    key(image.revision) {
        Image(image.bitmap.asImageBitmap(), contentDescription = "Received SSTV image",
            modifier = modifier.background(Color.Black), contentScale = ContentScale.Fit)
    }
}
