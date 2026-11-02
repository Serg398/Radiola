package com.example.sdr.ui.decoders

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.sdr.receiver.AircraftReport
import com.example.sdr.ui.theme.RadiolaColors
import kotlinx.coroutines.delay

@Composable
internal fun AircraftInformationPanel(reports: List<AircraftReport>, modifier: Modifier = Modifier) {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { delay(1000); now = System.currentTimeMillis() } }
    // Keep ICAO ordering stable within live/cached groups, rather than reshuffling every packet.
    val ordered = reports.sortedWith(compareByDescending<AircraftReport> {
        it.timestampMillis?.let { time -> now - time < 60_000 } == true
    }.thenBy { it.icao })
    Column(modifier) {
        Row(Modifier.fillMaxWidth().height(20.dp), verticalAlignment = Alignment.CenterVertically) {
            AircraftCell("FLIGHT", 1.5f, header = true)
            AircraftCell("ICAO / REG", 1.5f, header = true)
            AircraftCell("ALT · ft", 1f, header = true)
            AircraftCell("SPD · kt", 1f, header = true)
            AircraftCell("TRACK", .8f, header = true)
            AircraftCell("SQUAWK", 1.2f, header = true)
            AircraftCell("LAST SEEN", 1f, header = true)
        }
        LazyColumn(Modifier.fillMaxWidth().weight(1f)) {
            items(ordered, key = { it.icao }) { report ->
                val age = report.timestampMillis?.let { ((now - it).coerceAtLeast(0) / 1000) }
                Row(Modifier.fillMaxWidth().height(32.dp), verticalAlignment = Alignment.CenterVertically) {
                    val flag = report.countryCode.takeIf { it.length == 2 && it.all { c -> c in 'A'..'Z' } }
                        ?.map { String(Character.toChars(0x1F1E6 + it.code - 'A'.code)) }?.joinToString("").orEmpty()
                    AircraftCell((if (flag.isEmpty()) "" else "$flag ") + report.flight.ifBlank { "—" }, 1.5f,
                        prominent = age == null || age < 60)
                    Column(Modifier.weight(1.5f)) {
                        Text(report.icao, color = RadiolaColors.text, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                        if (report.registration.isNotEmpty()) Text(report.registration, color = RadiolaColors.secondaryText,
                            fontSize = 9.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    AircraftCell(report.altitudeFeet?.toString() ?: "—", 1f)
                    AircraftCell(report.speedKnots?.toString() ?: "—", 1f)
                    AircraftCell(report.courseDegrees?.let { "$it°" } ?: "—", .8f)
                    AircraftCell(report.emergency.ifBlank { report.squawk }.ifBlank { "—" }, 1.2f,
                        prominent = report.emergency.isNotBlank())
                    AircraftCell(when {
                        age == null -> "—"
                        age < 60 -> "${age}s"
                        age < 3600 -> "${age / 60}m ${age % 60}s"
                        else -> "${age / 3600}h"
                    }, 1f)
                }
                Spacer(Modifier.fillMaxWidth().height(1.dp).background(RadiolaColors.border.copy(alpha = .45f)))
            }
        }
    }
}

@Composable
private fun RowScope.AircraftCell(value: String, weight: Float, header: Boolean = false, prominent: Boolean = false) {
    Text(value, modifier = Modifier.weight(weight).padding(end = 6.dp),
        color = if (prominent) RadiolaColors.primary else if (header) RadiolaColors.secondaryText else RadiolaColors.text,
        fontSize = if (header) 9.sp else 11.sp,
        fontWeight = if (prominent) FontWeight.Medium else FontWeight.Normal,
        maxLines = 1, overflow = TextOverflow.Ellipsis)
}
